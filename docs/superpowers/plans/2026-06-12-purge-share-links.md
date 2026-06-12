# 30일 Purge 스케줄러 + 공유 링크 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 휴지통 30일 자동 purge 스케줄러(스펙 §4.3)와 공유 링크(스펙 §6 — deny를 넘는 유일한 read 예외, 만료·취소·로깅)를 백엔드+프런트 풀스택으로 구현한다.

**Architecture:** Part A(purge)는 기존 `VaultService.purge`를 재사용하는 얇은 스케줄러. Part B(공유 링크)는 V3 마이그레이션 + `com.worknote.share` 패키지(Mapper/Service/Controller) + 프런트 3개 접점(노트 앱 공유 모달, share.html 열람 페이지, admin 활성 링크 화면).

**Tech Stack:** Java 21 + Spring Boot 3.5 + MyBatis + Flyway + SQLite / Vite 6 + TS + React 18, Vitest(node env + fetch stub)

---

## 확정 결정

| # | 결정 | 근거 |
|---|---|---|
| P1 | purge 스케줄러는 **모드 무관**(local+server) 동작. 기동 60초 후 1회 + 24시간 간격 | §4.3은 데이터 정책(모드 무관). 개인 PC는 상시 기동이 아님 — 기동 직후 1회가 실효 |
| P2 | `worknote.purge.retention-days` 기본 30, **0 이하면 비활성** | 운영 스위치. env `WORKNOTE_PURGE_RETENTION_DAYS` |
| P3 | cutoff 판정은 ISO 문자열 **사전순 비교**(`deleted_at < cutoff`) | nowIso()와 동일 포맷(ISO_LOCAL_DATE_TIME) — 기존 audit at 비교와 같은 기법 |
| P4 | 자동 purge 감사 = `who="system"`, act는 기존 `node.purge` 재사용, target에 사유 부기 | 행위 사전 불릴 필요 없음 — 수동/자동 구분은 who |
| P5 | 휴지통 루트별 try/catch — 한 루트 실패가 나머지를 막지 않음 | 부분 실패 격리 |
| P6 | 스케줄러 테스트는 Clock 조작 대신 **deleted_at을 과거로 직접 UPDATE** | Clock은 싱글턴 빈 — 테스트 간 오염 없이 시간 경과를 만드는 가장 단순한 방법 |
| S1 | 토큰 = `SecureRandom` 32바이트 base64url(패딩 없음, 43자), **DB 원문 저장** | 폐쇄망 사내 — 해시 저장의 운영 이득 없음. 관리자 목록·재복사 UX 우선 |
| S2 | `GET /api/share/{token}` 무효 사유(미존재/만료/취소/열람 초과/pin 불일치/휴지통)는 **전부 404 단일 응답** | 존재·사유 비노출 |
| S3 | trash=suspend는 상태 변경 없이 **접근 시 `node.deleted_at` 판정** | restore 시 자동 부활(§4.3), 데이터 정합 단순 |
| S4 | purge 시 share_link 행도 영구 삭제(기존 종속행 삭제 체인에 합류) | id 재생성 fail-open 방지 — tag/acl/public/space와 동일 컨벤션 |
| S5 | 열람은 **인증 필수**(ALLOWLIST 미추가). local 모드는 AuthFilter 미등록이라 자연히 무인증 — pin 검사는 viewer=null이면 생략 | 스펙 "인증 직원만". local은 단일 사용자 환경 |
| S6 | 감사 target에 **token 원문 비기록** — link id + node id만 | 토큰은 capability — 로그 열람으로 사용 가능해지는 것 방지 |
| S7 | 감사 act 3종: `share.create` / `share.view` / `share.revoke` | 스펙 §6 "생성·열람·취소 전부 로그" |
| S8 | 링크 URL은 **프런트가 조립**(`{현재 디렉토리}/share.html?token=…`), 백엔드는 token만 반환 | base "./" — 배포 경로 비결합 |
| S9 | 생성 가드 = `res.share ∧ canRead(N)` + **노트만**(폴더 422). 열람은 read 권한 **불요**(deny 우회가 본질) | §5.2 share(N) / §5.1 우선순위 1위 |
| S10 | 취소 = 생성자 본인 ∨ 관리자(/local). 이미 취소된 링크 재취소는 409 | 멱등 204로 하면 감사가 중복 기록됨 |
| S11 | pin은 사번 배열(DB엔 JSON 문자열, API 경계에선 배열). **존재 검증 안 함** | 오타 시 아무도 못 여는 링크일 뿐 — fail-closed |
| S12 | share.html은 setOn401 **미설치** — 401이면 로그인 안내 화면 표시 | 리다이렉트하면 로그인 후 링크로 복귀 불가. login 앱과 동일 근거 |
| S13 | 노트 컨텍스트 메뉴 "공유 링크"는 **http 모드에서만** 노출 | local 모드 링크는 의미 없음 |
| S14 | admin 활성 링크 목록은 node JOIN으로 nodeName 포함, **휴지통 노드 링크도 노출**(suspend 표시) | suspend 중에도 취소 가능해야 함 |
| S15 | 만료·취소된 링크 행은 삭제하지 않음(목록에서만 제외) | 감사 재구성 — ACL 컨벤션과 동일. purge 시에만 물리 삭제 |
| S16 | share API 클라이언트는 `src/api/share.ts` 단일 — 노트 앱·share 앱 공용. admin은 AdminApi에 2메서드 추가 | 단일 출처 관례 |
| S17 | 클립보드 복사는 `navigator.clipboard` 부재 시 textarea+execCommand 폴백 | 폐쇄망 http(비보안 컨텍스트)에선 clipboard API 없음 |

## API 계약 (신규 6 엔드포인트)

| Method | Path | 권한 | 성공 | 비고 |
|---|---|---|---|---|
| POST | `/api/nodes/{id}/share` | `res.share ∧ read(N)`, 노트만 | 201 `{id, token, expiresAt}` | body `{days?=7(1~365), maxViews?(≥1), pinEmps?: string[]}` |
| GET | `/api/nodes/{id}/shares` | `res.share ∧ read(N)` | 200 `ShareLinkDto[]` | **활성만**. 관리자/local=전체, 그 외 본인 생성분 |
| GET | `/api/share/{token}` | 인증만 (read 권한 불요) | 200 `{name, content, updatedAt}` | 무효는 전부 404. 성공 시 view_count++ + 감사 |
| DELETE | `/api/shares/{id}` | 생성자 본인 ∨ 관리자 | 204 | 재취소 409 |
| GET | `/api/admin/shares` | 관리자 | 200 `AdminShareDto[]` | 활성만 + nodeId/nodeName/suspended |

`ShareLinkDto = {id, token, expiresAt, maxViews: number|null, viewCount, pinEmps: string[]|null, createdBy, createdAt}` — pinEmps는 컨트롤러가 파싱해 배열로 반환(JSON 문자열 비노출).
`AdminShareDto = ShareLinkDto + {nodeId, nodeName, suspended: boolean}` (suspended = 노드가 휴지통).

감사: `share.create` target=`{linkId} -> {nodeId}`, `share.view` 동일, `share.revoke` 동일. local 모드 user=null은 기존 컨벤션대로 감사 생략.

---

## Part A — 30일 Purge 스케줄러

### Task 1: findExpiredTrashRoots + TrashPurgeService

**Files:**
- Modify: `backend/src/main/java/com/worknote/vault/NodeMapper.java`
- Modify: `backend/src/main/resources/mappers/NodeMapper.xml`
- Create: `backend/src/main/java/com/worknote/vault/TrashPurgeService.java`
- Test: `backend/src/test/java/com/worknote/vault/TrashPurgeServiceTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

기존 백엔드 테스트 컨벤션을 따른다(@SpringBootTest + 인메모리 SQLite, 기존 VaultService 테스트 클래스의 셋업 패턴 참조 — 공유 인메모리 DB라 @BeforeEach에서 잔여 행 정리 필요 여부 확인). 시간 경과는 Clock 조작이 아니라 deleted_at 직접 UPDATE로 만든다(결정 P6).

```java
package com.worknote.vault;

import com.worknote.audit.AuditMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TrashPurgeServiceTest {

    @Autowired TrashPurgeService purge;
    @Autowired VaultService vault;
    @Autowired NodeMapper nodes;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private String ago(int days) {
        return LocalDateTime.now(clock).minusDays(days).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    @Test
    void 보존기한_경과_휴지통_루트는_purge되고_감사가_남는다() {
        vault.create("pg-old", null, "note", "오래된 노트", "x");
        vault.trash("pg-old", "tester");
        jdbc.update("UPDATE node SET deleted_at = ? WHERE id = 'pg-old'", ago(31));

        int purged = purge.purgeExpired();

        assertThat(purged).isEqualTo(1);
        assertThat(nodes.findById("pg-old")).isNull();
        Integer audits = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE who='system' AND act='node.purge' AND target LIKE 'pg-old%'",
            Integer.class);
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void 보존기한_이내_노드는_보존된다() {
        vault.create("pg-new", null, "note", "최근 삭제", "x");
        vault.trash("pg-new", "tester");
        jdbc.update("UPDATE node SET deleted_at = ? WHERE id = 'pg-new'", ago(29));

        purge.purgeExpired();

        assertThat(nodes.findById("pg-new")).isNotNull();
        jdbc.update("DELETE FROM node WHERE id = 'pg-new'");   // 정리 — 공유 인메모리 DB
    }

    @Test
    void 폴더_서브트리는_루트만으로_통째_purge된다() {
        vault.create("pg-f", null, "folder", "폴더", null);
        vault.create("pg-c", "pg-f", "note", "자식", "x");
        vault.trash("pg-f", "tester");
        jdbc.update("UPDATE node SET deleted_at = ? WHERE id IN ('pg-f','pg-c')", ago(40));

        int purged = purge.purgeExpired();

        assertThat(purged).isEqualTo(1);   // 루트 1건으로 집계
        assertThat(nodes.findById("pg-f")).isNull();
        assertThat(nodes.findById("pg-c")).isNull();
    }
}
```

- [ ] **Step 2: 테스트 실행 — 컴파일 실패 확인**

Run: `cd backend && ./gradlew test --tests TrashPurgeServiceTest`
Expected: FAIL — `TrashPurgeService` 미존재 컴파일 에러

- [ ] **Step 3: 구현**

`NodeMapper.java`에 추가:

```java
List<NodeRow> findExpiredTrashRoots(@Param("cutoff") String cutoff);  // 휴지통 루트 중 deleted_at < cutoff
```

`NodeMapper.xml`에 추가 (`findTrashRoots` 바로 아래):

```xml
<select id="findExpiredTrashRoots" resultType="com.worknote.vault.NodeRow">
  SELECT c.* FROM node c
  LEFT JOIN node p ON c.parent_id = p.id
  WHERE c.deleted_at IS NOT NULL AND c.deleted_at &lt; #{cutoff}
    AND (p.id IS NULL OR p.deleted_at IS NULL)
  ORDER BY c.deleted_at
</select>
```

`TrashPurgeService.java` 신규:

```java
package com.worknote.vault;

import com.worknote.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 휴지통 30일 자동 purge (스펙 §4.3). 스케줄 배선은 TrashPurgeScheduler — 로직 분리로 테스트 직접 호출. */
@Service
public class TrashPurgeService {

    private static final Logger log = LoggerFactory.getLogger(TrashPurgeService.class);

    private final VaultService vault;
    private final NodeMapper nodes;
    private final AuditService audit;
    private final Clock clock;
    private final int retentionDays;

    public TrashPurgeService(VaultService vault, NodeMapper nodes, AuditService audit, Clock clock,
                             @Value("${worknote.purge.retention-days:30}") int retentionDays) {
        this.vault = vault;
        this.nodes = nodes;
        this.audit = audit;
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    /** @return purge된 휴지통 루트 수. retention-days 0 이하 = 비활성. */
    public int purgeExpired() {
        if (retentionDays <= 0) return 0;
        String cutoff = LocalDateTime.now(clock).minusDays(retentionDays)
            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        int purged = 0;
        for (NodeRow root : nodes.findExpiredTrashRoots(cutoff)) {
            try {
                vault.purge(root.id());
                // 자동/수동 구분은 who — act는 node.purge 재사용 (결정 P4)
                audit.logRaw("system", "node.purge",
                    root.id() + " (보존기한 " + retentionDays + "일 경과)", null);
                purged++;
            } catch (Exception e) {
                // 한 루트 실패가 나머지를 막지 않음 (결정 P5)
                log.warn("자동 purge 실패: {}", root.id(), e);
            }
        }
        return purged;
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests TrashPurgeServiceTest`
Expected: PASS 3/3

- [ ] **Step 5: 전체 테스트 + 커밋**

Run: `cd backend && ./gradlew test`
Expected: 기존 196 + 신규 3 전부 green

```bash
git add backend/src
git commit -m "feat: 휴지통 보존기한 경과 자동 purge 서비스 (스펙 §4.3)"
```

### Task 2: 스케줄러 배선 + 설정

**Files:**
- Create: `backend/src/main/java/com/worknote/vault/TrashPurgeScheduler.java`
- Modify: `backend/src/main/java/com/worknote/WorknoteApplication.java` (@EnableScheduling)
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/worknote/vault/TrashPurgeSchedulerTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

스케줄 발화 자체를 기다리는 테스트는 느리고 불안정 — 빈 존재 + @Scheduled 메타데이터(기동 1회+24h 간격) 단언으로 배선을 검증한다.

```java
package com.worknote.vault;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TrashPurgeSchedulerTest {

    @Autowired TrashPurgeScheduler scheduler;

    @Test
    void 기동_60초_후_1회_그리고_24시간_간격으로_배선된다() throws Exception {
        Method run = TrashPurgeScheduler.class.getMethod("run");
        Scheduled sched = run.getAnnotation(Scheduled.class);
        assertThat(sched).isNotNull();
        assertThat(sched.timeUnit()).isEqualTo(TimeUnit.SECONDS);
        assertThat(sched.initialDelay()).isEqualTo(60);
        assertThat(sched.fixedDelay()).isEqualTo(24 * 60 * 60);
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `cd backend && ./gradlew test --tests TrashPurgeSchedulerTest`
Expected: FAIL — 컴파일 에러(클래스 미존재)

- [ ] **Step 3: 구현**

`TrashPurgeScheduler.java`:

```java
package com.worknote.vault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** purge 스케줄 배선. 개인 PC는 상시 기동이 아님 — 기동 60초 후 1회가 실효(결정 P1). 테스트(60초 미만)에선 발화 안 함. */
@Component
public class TrashPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(TrashPurgeScheduler.class);

    private final TrashPurgeService purge;

    public TrashPurgeScheduler(TrashPurgeService purge) {
        this.purge = purge;
    }

    @Scheduled(initialDelay = 60, fixedDelay = 24 * 60 * 60, timeUnit = TimeUnit.SECONDS)
    public void run() {
        int n = purge.purgeExpired();
        if (n > 0) {
            log.info("휴지통 자동 purge: {}건", n);
        }
    }
}
```

`WorknoteApplication.java`에 `@EnableScheduling` 추가:

```java
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WorknoteApplication {
```

`application.yml`의 `worknote:` 블록에 추가:

```yaml
worknote:
  mode: ${WORKNOTE_MODE:local}                     # local=1단계(무인증), server=2단계(인증+권한)
  admin-password: ${WORKNOTE_ADMIN_PASSWORD:}      # server 모드 최초 기동 시 관리자 비밀번호 (필수)
  purge:
    retention-days: ${WORKNOTE_PURGE_RETENTION_DAYS:30}   # 휴지통 보존기한 (0 이하 = 자동 purge 끔)
```

- [ ] **Step 4: 테스트 통과 + 전체 회귀**

Run: `cd backend && ./gradlew test`
Expected: 전부 green (@EnableScheduling이 다른 테스트를 깨지 않는지 확인 — initialDelay 60초라 테스트 중 발화 없음)

- [ ] **Step 5: 커밋**

```bash
git add backend/src
git commit -m "feat: 30일 purge 스케줄러 배선 (기동 60초 후 1회 + 24시간 간격)"
```

---

## Part B — 공유 링크

### Task 3: V3 마이그레이션 + ShareLinkMapper

**Files:**
- Create: `backend/src/main/resources/db/migration/sqlite/V3__share_link.sql`
- Create: `backend/src/main/java/com/worknote/share/ShareLinkRow.java`
- Create: `backend/src/main/java/com/worknote/share/ActiveShareRow.java`
- Create: `backend/src/main/java/com/worknote/share/ShareLinkMapper.java`
- Create: `backend/src/main/resources/mappers/ShareLinkMapper.xml`
- Test: `backend/src/test/java/com/worknote/share/ShareLinkMapperTest.java`

- [ ] **Step 1: V3 마이그레이션 작성**

```sql
-- V3__share_link.sql  (스펙 §6 공유 링크 — deny를 넘는 유일한 read 예외, 열거 가능)
CREATE TABLE share_link (
  id         TEXT PRIMARY KEY,
  token      TEXT NOT NULL UNIQUE,            -- SecureRandom 32B base64url — 원문 저장(폐쇄망, 결정 S1)
  node_id    TEXT NOT NULL REFERENCES node(id),
  created_by TEXT NOT NULL,                   -- 사번(emp), local 모드는 'local'
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,                   -- 기본 7일 (스펙 §6)
  max_views  INTEGER,                         -- NULL = 무제한
  view_count INTEGER NOT NULL DEFAULT 0,
  pin_emps   TEXT,                            -- NULL = 전 직원, 값 = JSON 배열(사번)
  revoked_at TEXT                             -- NULL = 미취소
);
CREATE INDEX idx_share_link_node ON share_link(node_id);
```

- [ ] **Step 2: 실패하는 매퍼 테스트 작성**

```java
package com.worknote.share;

import com.worknote.vault.VaultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ShareLinkMapperTest {

    @Autowired ShareLinkMapper mapper;
    @Autowired VaultService vault;
    @Autowired JdbcTemplate jdbc;

    private static final String NOW = "2026-06-12T10:00:00";

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM share_link");   // 공유 인메모리 DB — 형제 클래스 잔여 행 정리
    }

    private ShareLinkRow row(String id, String token, String nodeId) {
        return new ShareLinkRow(id, token, nodeId, "100001", NOW, "2026-06-19T10:00:00", null, 0, null, null);
    }

    @Test
    void insert_findByToken_라운드트립() {
        if (vault.tree().stream().noneMatch(n -> "sl-n1".equals(n.id()))) {
            vault.create("sl-n1", null, "note", "노트", "본문");
        }
        mapper.insert(row("sl-1", "tok-1", "sl-n1"));

        ShareLinkRow found = mapper.findByToken("tok-1");
        assertThat(found.id()).isEqualTo("sl-1");
        assertThat(found.nodeId()).isEqualTo("sl-n1");
        assertThat(found.viewCount()).isZero();
        assertThat(mapper.findById("sl-1").token()).isEqualTo("tok-1");
    }

    @Test
    void findActiveByNode는_만료_취소_열람소진을_제외한다() {
        if (vault.tree().stream().noneMatch(n -> "sl-n2".equals(n.id()))) {
            vault.create("sl-n2", null, "note", "노트2", "본문");
        }
        mapper.insert(row("sl-a", "tok-a", "sl-n2"));                                          // 활성
        mapper.insert(new ShareLinkRow("sl-e", "tok-e", "sl-n2", "100001", NOW,
            "2026-06-01T00:00:00", null, 0, null, null));                                      // 만료
        mapper.insert(row("sl-r", "tok-r", "sl-n2"));
        mapper.revoke("sl-r", NOW);                                                            // 취소
        mapper.insert(new ShareLinkRow("sl-v", "tok-v", "sl-n2", "100001", NOW,
            "2026-06-19T10:00:00", 1, 1, null, null));                                         // 열람 소진

        List<ShareLinkRow> active = mapper.findActiveByNode("sl-n2", NOW);
        assertThat(active).extracting(ShareLinkRow::id).containsExactly("sl-a");
    }

    @Test
    void incrementViewCount와_findAllActive_노드명_조인() {
        if (vault.tree().stream().noneMatch(n -> "sl-n3".equals(n.id()))) {
            vault.create("sl-n3", null, "note", "조인노트", "본문");
        }
        mapper.insert(row("sl-j", "tok-j", "sl-n3"));
        mapper.incrementViewCount("sl-j");

        List<ActiveShareRow> all = mapper.findAllActive(NOW);
        ActiveShareRow found = all.stream().filter(r -> "sl-j".equals(r.id())).findFirst().orElseThrow();
        assertThat(found.nodeName()).isEqualTo("조인노트");
        assertThat(found.viewCount()).isEqualTo(1);
    }

    @Test
    void deleteIn은_노드_목록의_링크를_삭제한다() {
        if (vault.tree().stream().noneMatch(n -> "sl-n4".equals(n.id()))) {
            vault.create("sl-n4", null, "note", "노트4", "본문");
        }
        mapper.insert(row("sl-d", "tok-d", "sl-n4"));
        mapper.deleteIn(List.of("sl-n4"));
        assertThat(mapper.findById("sl-d")).isNull();
    }
}
```

- [ ] **Step 3: 테스트 실행 — 실패 확인**

Run: `cd backend && ./gradlew test --tests ShareLinkMapperTest`
Expected: FAIL — 컴파일 에러

- [ ] **Step 4: 구현**

`ShareLinkRow.java`:

```java
package com.worknote.share;

/** share_link 행. pinEmps는 JSON 배열 문자열(NULL=전 직원) — 파싱은 서비스/컨트롤러 책임. */
public record ShareLinkRow(String id, String token, String nodeId, String createdBy,
                           String createdAt, String expiresAt, Integer maxViews,
                           int viewCount, String pinEmps, String revokedAt) {}
```

`ActiveShareRow.java`:

```java
package com.worknote.share;

/** 관리자 활성 링크 목록 행 — node JOIN. nodeDeletedAt != null = suspend(휴지통). */
public record ActiveShareRow(String id, String token, String nodeId, String nodeName,
                             String nodeDeletedAt, String createdBy, String createdAt,
                             String expiresAt, Integer maxViews, int viewCount, String pinEmps) {}
```

`ShareLinkMapper.java`:

```java
package com.worknote.share;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface ShareLinkMapper {
    void insert(ShareLinkRow row);
    ShareLinkRow findById(@Param("id") String id);
    ShareLinkRow findByToken(@Param("token") String token);
    List<ShareLinkRow> findActiveByNode(@Param("nodeId") String nodeId, @Param("now") String now);
    List<ActiveShareRow> findAllActive(@Param("now") String now);   // 휴지통 노드 링크 포함(suspend 표시 — 결정 S14)
    void incrementViewCount(@Param("id") String id);
    void revoke(@Param("id") String id, @Param("revokedAt") String revokedAt);
    void deleteIn(@Param("nodeIds") List<String> nodeIds);          // purge 종속행 삭제 (결정 S4)
}
```

`ShareLinkMapper.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.worknote.share.ShareLinkMapper">

  <insert id="insert">
    INSERT INTO share_link (id, token, node_id, created_by, created_at, expires_at,
                            max_views, view_count, pin_emps, revoked_at)
    VALUES (#{id}, #{token}, #{nodeId}, #{createdBy}, #{createdAt}, #{expiresAt},
            #{maxViews}, #{viewCount}, #{pinEmps}, #{revokedAt})
  </insert>

  <select id="findById" resultType="com.worknote.share.ShareLinkRow">
    SELECT * FROM share_link WHERE id = #{id}
  </select>

  <select id="findByToken" resultType="com.worknote.share.ShareLinkRow">
    SELECT * FROM share_link WHERE token = #{token}
  </select>

  <sql id="activeCond">
    revoked_at IS NULL AND expires_at &gt; #{now}
    AND (max_views IS NULL OR view_count &lt; max_views)
  </sql>

  <select id="findActiveByNode" resultType="com.worknote.share.ShareLinkRow">
    SELECT * FROM share_link
    WHERE node_id = #{nodeId} AND <include refid="activeCond"/>
    ORDER BY created_at DESC, id
  </select>

  <!-- 휴지통 노드 링크도 노출(suspend 중에도 취소 가능해야 — 결정 S14). n.deleted_at으로 suspend 판정 -->
  <select id="findAllActive" resultType="com.worknote.share.ActiveShareRow">
    SELECT s.id, s.token, s.node_id, n.name AS node_name, n.deleted_at AS node_deleted_at,
           s.created_by, s.created_at, s.expires_at, s.max_views, s.view_count, s.pin_emps
    FROM share_link s JOIN node n ON s.node_id = n.id
    WHERE s.<include refid="activeCond"/>
    ORDER BY s.created_at DESC, s.id
  </select>

  <update id="incrementViewCount">
    UPDATE share_link SET view_count = view_count + 1 WHERE id = #{id}
  </update>

  <update id="revoke">
    UPDATE share_link SET revoked_at = #{revokedAt} WHERE id = #{id}
  </update>

  <delete id="deleteIn">
    DELETE FROM share_link WHERE node_id IN
    <foreach item="i" collection="nodeIds" open="(" separator="," close=")">#{i}</foreach>
  </delete>

</mapper>
```

주의: `<sql id="activeCond">`를 `findAllActive`에서 `s.` 접두로 include하면 첫 컬럼(revoked_at)에만 접두가 붙는다 — include 그대로 쓰면 컬럼 모호성이 없는지 확인하고, 모호하면 activeCond를 인라인으로 풀어 `s.revoked_at` 등 전체 접두 명시.

- [ ] **Step 5: 테스트 통과 + 전체 회귀 + 커밋**

Run: `cd backend && ./gradlew test`
Expected: 전부 green (SchemaMigrationTest 류가 있으면 V3 반영 확인)

```bash
git add backend/src
git commit -m "feat: share_link V3 마이그레이션 + 매퍼 (스펙 §6)"
```

### Task 4: ShareLinkService (생성·열람·취소·목록)

**Files:**
- Create: `backend/src/main/java/com/worknote/share/ShareLinkService.java`
- Create: `backend/src/main/java/com/worknote/share/ShareView.java`
- Test: `backend/src/test/java/com/worknote/share/ShareLinkServiceTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.worknote.share;

import com.worknote.vault.VaultException;
import com.worknote.vault.VaultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ShareLinkServiceTest {

    @Autowired ShareLinkService svc;
    @Autowired ShareLinkMapper mapper;
    @Autowired VaultService vault;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM share_link");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM node WHERE id='ss-note'", Integer.class) == 0) {
            vault.create("ss-note", null, "note", "공유 노트", "공유 본문");
        }
        jdbc.update("UPDATE node SET deleted_at = NULL, deleted_by = NULL WHERE id = 'ss-note'");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM node WHERE id='ss-folder'", Integer.class) == 0) {
            vault.create("ss-folder", null, "folder", "폴더", null);
        }
    }

    @Test
    void 생성_기본값은_7일_만료_무제한_열람_전직원() {
        ShareLinkRow row = svc.create("ss-note", "100001", null, null, null);
        assertThat(row.token()).hasSize(43);                       // 32B base64url 무패딩
        assertThat(row.maxViews()).isNull();
        assertThat(row.pinEmps()).isNull();
        // 만료 = 생성 + 7일 (둘 다 ISO — 날짜부 비교)
        assertThat(row.expiresAt().substring(0, 10))
            .isEqualTo(java.time.LocalDate.parse(row.createdAt().substring(0, 10)).plusDays(7).toString());
    }

    @Test
    void 폴더_공유는_422() {
        assertThatThrownBy(() -> svc.create("ss-folder", "100001", null, null, null))
            .isInstanceOf(VaultException.class).hasMessageContaining("노트만");
    }

    @Test
    void 잘못된_파라미터는_422() {
        assertThatThrownBy(() -> svc.create("ss-note", "100001", 0, null, null))
            .isInstanceOf(VaultException.class);
        assertThatThrownBy(() -> svc.create("ss-note", "100001", 366, null, null))
            .isInstanceOf(VaultException.class);
        assertThatThrownBy(() -> svc.create("ss-note", "100001", null, 0, null))
            .isInstanceOf(VaultException.class);
    }

    @Test
    void 열람_성공시_뷰카운트_증가() {
        ShareLinkRow row = svc.create("ss-note", "100001", null, null, null);
        ShareView view = svc.resolve(row.token(), "100002");
        assertThat(view.name()).isEqualTo("공유 노트");
        assertThat(view.content()).isEqualTo("공유 본문");
        assertThat(mapper.findById(row.id()).viewCount()).isEqualTo(1);
    }

    @Test
    void 무효_사유는_전부_404() {
        // 미존재 토큰
        assertThatThrownBy(() -> svc.resolve("no-such-token", "100002"))
            .isInstanceOf(VaultException.class).hasMessageContaining("유효하지 않습니다");
        // 만료
        ShareLinkRow exp = svc.create("ss-note", "100001", null, null, null);
        jdbc.update("UPDATE share_link SET expires_at = '2020-01-01T00:00:00' WHERE id = ?", exp.id());
        assertThatThrownBy(() -> svc.resolve(exp.token(), "100002")).isInstanceOf(VaultException.class);
        // 취소
        ShareLinkRow rev = svc.create("ss-note", "100001", null, null, null);
        svc.revoke(rev.id(), "100001", false);
        assertThatThrownBy(() -> svc.resolve(rev.token(), "100002")).isInstanceOf(VaultException.class);
        // 열람 소진
        ShareLinkRow lim = svc.create("ss-note", "100001", null, 1, null);
        svc.resolve(lim.token(), "100002");
        assertThatThrownBy(() -> svc.resolve(lim.token(), "100002")).isInstanceOf(VaultException.class);
        // pin 불일치
        ShareLinkRow pin = svc.create("ss-note", "100001", null, null, List.of("100009"));
        assertThatThrownBy(() -> svc.resolve(pin.token(), "100002")).isInstanceOf(VaultException.class);
        assertThat(svc.resolve(pin.token(), "100009").name()).isEqualTo("공유 노트");
    }

    @Test
    void 휴지통_노드는_suspend_복구하면_부활() {
        ShareLinkRow row = svc.create("ss-note", "100001", null, null, null);
        vault.trash("ss-note", "tester");
        assertThatThrownBy(() -> svc.resolve(row.token(), "100002")).isInstanceOf(VaultException.class);
        vault.restore("ss-note");
        assertThat(svc.resolve(row.token(), "100002").name()).isEqualTo("공유 노트");
    }

    @Test
    void local_모드_viewer_null은_pin_검사_생략() {
        ShareLinkRow pin = svc.create("ss-note", "100001", null, null, List.of("100009"));
        assertThat(svc.resolve(pin.token(), null).name()).isEqualTo("공유 노트");
    }

    @Test
    void 취소는_생성자_본인_또는_관리자만_재취소는_409() {
        ShareLinkRow row = svc.create("ss-note", "100001", null, null, null);
        assertThatThrownBy(() -> svc.revoke(row.id(), "999999", false))
            .isInstanceOf(VaultException.class).hasMessageContaining("취소 권한");
        svc.revoke(row.id(), "999999", true);    // 관리자
        assertThatThrownBy(() -> svc.revoke(row.id(), "100001", false))
            .isInstanceOf(VaultException.class).hasMessageContaining("이미 취소");
    }

    @Test
    void 노드별_목록은_본인_생성분_필터() {
        svc.create("ss-note", "100001", null, null, null);
        svc.create("ss-note", "100002", null, null, null);
        assertThat(svc.listForNode("ss-note", "100001")).hasSize(1);
        assertThat(svc.listForNode("ss-note", null)).hasSize(2);   // 관리자/local = 전체
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `cd backend && ./gradlew test --tests ShareLinkServiceTest`
Expected: FAIL — 컴파일 에러

- [ ] **Step 3: 구현**

`ShareView.java`:

```java
package com.worknote.share;

/** 공유 열람 응답 + 감사 target 구성용 식별자. */
public record ShareView(String linkId, String nodeId, String name, String content, String updatedAt) {}
```

`ShareLinkService.java`:

```java
package com.worknote.share;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import com.worknote.vault.VaultException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * 공유 링크 도메인 (스펙 §6). 열람은 read 권한 검사를 하지 않는다 —
 * deny를 넘는 유일한 예외가 본질이며, 통제는 만료·취소·열람수·pin·감사로 한다.
 */
@Service
public class ShareLinkService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int DEFAULT_DAYS = 7;   // 스펙 §6 기본 만료

    private final ShareLinkMapper mapper;
    private final NodeMapper nodes;
    private final ObjectMapper json;
    private final Clock clock;

    public ShareLinkService(ShareLinkMapper mapper, NodeMapper nodes, ObjectMapper json, Clock clock) {
        this.mapper = mapper;
        this.nodes = nodes;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public ShareLinkRow create(String nodeId, String createdBy, Integer days, Integer maxViews,
                               List<String> pinEmps) {
        NodeRow node = nodes.findById(nodeId);
        if (node == null || node.deletedAt() != null) {
            throw VaultException.notFound("노드를 찾을 수 없습니다: " + nodeId);
        }
        if (!"note".equals(node.type())) {
            throw VaultException.invalid("노트만 공유할 수 있습니다 (스펙 §6 — 노트 1개 read 캡)");
        }
        int d = days != null ? days : DEFAULT_DAYS;
        if (d < 1 || d > 365) {
            throw VaultException.invalid("만료 일수는 1~365 사이여야 합니다: " + d);
        }
        if (maxViews != null && maxViews < 1) {
            throw VaultException.invalid("최대 열람수는 1 이상이어야 합니다: " + maxViews);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        ShareLinkRow row = new ShareLinkRow(UUID.randomUUID().toString(), token, nodeId, createdBy,
            iso(now), iso(now.plusDays(d)), maxViews, 0, toJson(pinEmps), null);
        mapper.insert(row);
        return row;
    }

    /** 열람 — 무효 사유는 전부 404 단일화(존재·사유 비노출, 결정 S2). viewer=null은 local 모드(pin 생략, 결정 S5). */
    @Transactional
    public ShareView resolve(String token, String viewerEmp) {
        ShareLinkRow row = mapper.findByToken(token);
        if (row == null || row.revokedAt() != null
            || row.expiresAt().compareTo(iso(LocalDateTime.now(clock))) <= 0
            || (row.maxViews() != null && row.viewCount() >= row.maxViews())
            || (row.pinEmps() != null && viewerEmp != null && !fromJson(row.pinEmps()).contains(viewerEmp))) {
            throw invalidLink();
        }
        NodeRow node = nodes.findById(row.nodeId());
        if (node == null || node.deletedAt() != null) {   // 휴지통 = suspend (결정 S3)
            throw invalidLink();
        }
        mapper.incrementViewCount(row.id());
        return new ShareView(row.id(), row.nodeId(), node.name(), node.content(),
            node.updatedAt() == null ? null : node.updatedAt().substring(0, 10));
    }

    /** @return 취소된 행(감사 target 구성용). privileged = 관리자 또는 local 모드. */
    @Transactional
    public ShareLinkRow revoke(String id, String byEmp, boolean privileged) {
        ShareLinkRow row = mapper.findById(id);
        if (row == null) {
            throw VaultException.notFound("공유 링크를 찾을 수 없습니다: " + id);
        }
        if (row.revokedAt() != null) {
            throw VaultException.conflict("이미 취소된 링크입니다: " + id);
        }
        if (!privileged && !row.createdBy().equals(byEmp)) {
            throw VaultException.forbidden("취소 권한이 없습니다: " + id);
        }
        mapper.revoke(id, iso(LocalDateTime.now(clock)));
        return row;
    }

    /** byEmp=null이면 전체(관리자/local), 아니면 본인 생성분만. 활성만 반환. */
    @Transactional(readOnly = true)
    public List<ShareLinkRow> listForNode(String nodeId, String byEmp) {
        List<ShareLinkRow> rows = mapper.findActiveByNode(nodeId, iso(LocalDateTime.now(clock)));
        return byEmp == null ? rows : rows.stream().filter(r -> byEmp.equals(r.createdBy())).toList();
    }

    @Transactional(readOnly = true)
    public List<ActiveShareRow> listActive() {
        return mapper.findAllActive(iso(LocalDateTime.now(clock)));
    }

    public List<String> parsePins(String pinEmps) {
        return pinEmps == null ? null : fromJson(pinEmps);
    }

    // ---- internal ----

    private static VaultException invalidLink() {
        return VaultException.notFound("공유 링크가 유효하지 않습니다");
    }

    private String iso(LocalDateTime t) {
        return t.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private String toJson(List<String> pins) {
        if (pins == null) return null;
        List<String> cleaned = pins.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (cleaned.isEmpty()) return null;
        try {
            return json.writeValueAsString(cleaned);
        } catch (JsonProcessingException e) {
            throw VaultException.invalid("pin 목록을 처리할 수 없습니다");
        }
    }

    private List<String> fromJson(String pins) {
        try {
            return json.readValue(pins, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            // 저장 시 우리가 직렬화한 값 — 파싱 실패는 데이터 손상. fail-closed로 빈 목록(아무도 못 염)
            return List.of();
        }
    }
}
```

주의: `VaultException`에 conflict/invalid/notFound/forbidden 팩토리가 이미 있는지 확인하고 시그니처를 따른다(3단계에서 conflict 사용 이력 있음).

- [ ] **Step 4: 테스트 통과 + 전체 회귀 + 커밋**

Run: `cd backend && ./gradlew test`
Expected: 전부 green

```bash
git add backend/src
git commit -m "feat: 공유 링크 서비스 — 생성·열람(404 단일화)·취소·suspend (스펙 §6)"
```

### Task 5: ShareController + 가드 + 감사 + purge 합류

**Files:**
- Create: `backend/src/main/java/com/worknote/share/ShareController.java`
- Create: `backend/src/main/java/com/worknote/share/AdminShareController.java`
- Create: `backend/src/main/java/com/worknote/share/dto/CreateShareRequest.java`
- Modify: `backend/src/main/java/com/worknote/vault/VaultGuard.java` (requireShare, privileged)
- Modify: `backend/src/main/java/com/worknote/vault/VaultService.java` (purge에 share_link 삭제)
- Test: `backend/src/test/java/com/worknote/share/ShareApiTest.java` (server 모드 MockMvc)
- Test: 기존 purge 관련 테스트에 share_link 정리 검증 추가

- [ ] **Step 1: 실패하는 통합 테스트 작성**

기존 server 모드 MockMvc 테스트 클래스(3단계 AdminApiTest 류)의 셋업 패턴(관리자 세션 확보, 사용자 생성 헬퍼)을 그대로 따른다. 핵심 시나리오:

```java
// 명세 (기존 server 모드 테스트 클래스 패턴으로 작성):
// 1. 운영자(res.share 보유)가 자기가 read 가능한 노트에 POST /api/nodes/{id}/share → 201 {id, token(43자), expiresAt}
//    + audit_log에 share.create 행 (target = linkId + " -> " + nodeId, token 미포함)
// 2. 방문자(res.share 없음)가 생성 시도 → 403
// 3. 읽기 불가(deny) 노트에 운영자가 생성 시도 → 403
// 4. ★핵심★ 해당 노트에 deny가 걸린 사용자도 GET /api/share/{token} → 200 (deny를 넘는 유일 예외)
//    + audit_log에 share.view 행
// 5. 폴더에 생성 시도 → 422
// 6. 비로그인 GET /api/share/{token} → 401 (ALLOWLIST 미포함)
// 7. pin 불일치 사용자 → 404, pin 일치 사용자 → 200
// 8. DELETE /api/shares/{id}: 타인 → 403, 생성자 본인 → 204 + share.revoke 감사, 재취소 → 409
// 9. 관리자 GET /api/admin/shares → 활성 링크 목록(nodeName 포함), 비관리자 → 403
// 10. 노트 trash → GET 404 (suspend), restore → 200
// 11. 노트 trash 후 관리자 purge → share_link 행 자체가 삭제됨 (SELECT COUNT = 0)
// 12. GET /api/nodes/{id}/shares: 생성자에겐 본인 링크, 관리자에겐 전체
```

전부 실제 MockMvc 코드로 작성한다(위는 케이스 명세). 기존 테스트 헬퍼(로그인 세션, 사용자/역할 시드)를 재사용해 중복 셋업을 만들지 않는다.

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `cd backend && ./gradlew test --tests ShareApiTest`
Expected: FAIL — 컴파일 에러

- [ ] **Step 3: 구현**

`CreateShareRequest.java`:

```java
package com.worknote.share.dto;

import java.util.List;

/** 검증(범위)은 ShareLinkService — DTO는 운반만. */
public record CreateShareRequest(Integer days, Integer maxViews, List<String> pinEmps) {}
```

`VaultGuard.java`에 추가:

```java
/** share(N) = roleHas(res.share) ∧ read(N) — 스펙 §5.2. 생성·노드별 목록 가드. */
public void requireShare(UserRow user, String id) {
    if (bypass(user)) return;
    requireUser(user);
    if (!perm.roleHas(user, "res.share") || !perm.canRead(user, id)) {
        throw VaultException.forbidden("공유 권한이 없습니다: " + id);
    }
}

/** 관리자/local 특권 여부 — 공유 링크 취소·전체 목록 분기용. */
public boolean privileged(UserRow user) {
    return bypass(user);
}
```

`ShareController.java`:

```java
package com.worknote.share;

import com.worknote.audit.AuditService;
import com.worknote.auth.AuthFilter;
import com.worknote.auth.UserRow;
import com.worknote.share.dto.CreateShareRequest;
import com.worknote.vault.VaultGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 공유 링크 API. 생성·목록은 VaultGuard.requireShare, 열람은 인증만(deny 우회가 본질 — 결정 S9). */
@RestController
@RequestMapping("/api")
public class ShareController {

    private final ShareLinkService svc;
    private final VaultGuard guard;
    private final AuditService audit;

    public ShareController(ShareLinkService svc, VaultGuard guard, AuditService audit) {
        this.svc = svc;
        this.guard = guard;
        this.audit = audit;
    }

    private static UserRow user(HttpServletRequest req) {
        return (UserRow) req.getAttribute(AuthFilter.CURRENT_USER);
    }

    @PostMapping("/nodes/{id}/share")
    public ResponseEntity<Map<String, String>> create(@PathVariable String id,
                                                      @RequestBody(required = false) CreateShareRequest body,
                                                      HttpServletRequest req) {
        UserRow user = user(req);
        guard.requireShare(user, id);
        CreateShareRequest b = body != null ? body : new CreateShareRequest(null, null, null);
        ShareLinkRow row = svc.create(id, guard.who(user), b.days(), b.maxViews(), b.pinEmps());
        audit.log(user, "share.create", row.id() + " -> " + id, req.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(Map.of("id", row.id(), "token", row.token(), "expiresAt", row.expiresAt()));
    }

    @GetMapping("/nodes/{id}/shares")
    public List<Map<String, Object>> listForNode(@PathVariable String id, HttpServletRequest req) {
        UserRow user = user(req);
        guard.requireShare(user, id);
        String filter = guard.privileged(user) ? null : user.emp();
        return svc.listForNode(id, filter).stream().map(this::dto).toList();
    }

    @GetMapping("/share/{token}")
    public Map<String, Object> view(@PathVariable String token, HttpServletRequest req) {
        UserRow user = user(req);
        ShareView view = svc.resolve(token, user == null ? null : user.emp());
        audit.log(user, "share.view", view.linkId() + " -> " + view.nodeId(), req.getRemoteAddr());
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("name", view.name());
        out.put("content", view.content());
        out.put("updatedAt", view.updatedAt());
        return out;
    }

    @DeleteMapping("/shares/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String id, HttpServletRequest req) {
        UserRow user = user(req);
        ShareLinkRow row = svc.revoke(id, guard.who(user), guard.privileged(user));
        audit.log(user, "share.revoke", id + " -> " + row.nodeId(), req.getRemoteAddr());
    }

    /** pinEmps는 배열로 노출(JSON 문자열 비노출), token 포함(생성자 재복사용). */
    private Map<String, Object> dto(ShareLinkRow row) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", row.id());
        out.put("token", row.token());
        out.put("expiresAt", row.expiresAt());
        out.put("maxViews", row.maxViews());
        out.put("viewCount", row.viewCount());
        out.put("pinEmps", svc.parsePins(row.pinEmps()));
        out.put("createdBy", row.createdBy());
        out.put("createdAt", row.createdAt());
        return out;
    }
}
```

`AdminShareController.java`:

```java
package com.worknote.share;

import com.worknote.admin.AdminGuard;
import com.worknote.auth.AuthFilter;
import com.worknote.auth.UserRow;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 관리자 활성 링크 일괄 조회 (스펙 §6 — 취소는 DELETE /api/shares/{id} 공용). 조회라 감사 없음. */
@RestController
@RequestMapping("/api/admin")
public class AdminShareController {

    private final ShareLinkService svc;
    private final AdminGuard guard;

    public AdminShareController(ShareLinkService svc, AdminGuard guard) {
        this.svc = svc;
        this.guard = guard;
    }

    @GetMapping("/shares")
    public List<Map<String, Object>> shares(HttpServletRequest req) {
        guard.requireAdmin((UserRow) req.getAttribute(AuthFilter.CURRENT_USER));
        return svc.listActive().stream().map(r -> {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("id", r.id());
            out.put("token", r.token());
            out.put("nodeId", r.nodeId());
            out.put("nodeName", r.nodeName());
            out.put("suspended", r.nodeDeletedAt() != null);
            out.put("createdBy", r.createdBy());
            out.put("createdAt", r.createdAt());
            out.put("expiresAt", r.expiresAt());
            out.put("maxViews", r.maxViews());
            out.put("viewCount", r.viewCount());
            out.put("pinEmps", svc.parsePins(r.pinEmps()));
            return out;
        }).toList();
    }
}
```

`VaultService.purge`에 share_link 삭제 합류 — `ShareLinkMapper` 주입(순환 없음: share→vault 단방향이던 것이 vault→share 매퍼 의존 추가, 매퍼는 서비스가 아니라 순환 안 됨):

```java
// 생성자에 ShareLinkMapper shareLinks 주입 추가 후, purge()의 삭제 체인에:
        mapper.deleteTagsIn(ids);
        aclMapper.deleteAclIn(ids);
        aclMapper.deletePublicFlagIn(ids);
        aclMapper.deleteSpaceIn(ids);
        shareLinks.deleteIn(ids);    // 공유 링크도 영구 삭제 — id 재생성 fail-open 방지 (결정 S4)
        mapper.purgeSubtree(id);
```

- [ ] **Step 4: 테스트 통과 + 전체 회귀 + 커밋**

Run: `cd backend && ./gradlew test`
Expected: 전부 green ×2회 (공유 인메모리 DB 간섭 확인)

```bash
git add backend/src
git commit -m "feat: 공유 링크 API 6종 + 감사 + purge 종속행 합류 (스펙 §6)"
```

### Task 6: 프런트 API 클라이언트 + admin 확장 + 감사 라벨

**Files:**
- Create: `frontend/src/api/share.ts`
- Test: `frontend/src/api/share.test.ts`
- Modify: `frontend/src/admin/api.ts` (shares/revokeShare + ApiShare 타입)
- Modify: `frontend/src/admin/api.test.ts`
- Modify: `frontend/src/admin/mappers.ts` (ACTS 3종 + actType)
- Modify: `frontend/src/admin/mappers.test.ts` (드리프트 가드 27→30)

- [ ] **Step 1: 실패하는 테스트 작성**

`share.test.ts` — 기존 `api.test.ts`의 fetch stub 패턴 재사용:

```typescript
import { describe, it, expect, vi, beforeEach } from "vitest";
import { ShareApi, shareUrl } from "./share";

const ok = (body: unknown, status = 200) =>
  Promise.resolve(new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } }));

describe("ShareApi", () => {
  beforeEach(() => { vi.restoreAllMocks(); });

  it("create는 POST /nodes/{id}/share에 본문을 보낸다", async () => {
    const spy = vi.spyOn(globalThis, "fetch").mockReturnValue(ok({ id: "l1", token: "t", expiresAt: "e" }, 201));
    const res = await ShareApi.create("n1", { days: 3, maxViews: 5, pinEmps: ["100002"] });
    expect(res.token).toBe("t");
    const [url, init] = spy.mock.calls[0];
    expect(String(url)).toBe("/api/nodes/n1/share");
    expect(init?.method).toBe("POST");
    expect(JSON.parse(String(init?.body))).toEqual({ days: 3, maxViews: 5, pinEmps: ["100002"] });
  });

  it("listForNode / revoke / view 경로", async () => {
    const spy = vi.spyOn(globalThis, "fetch").mockReturnValue(ok([]));
    await ShareApi.listForNode("n1");
    expect(String(spy.mock.calls[0][0])).toBe("/api/nodes/n1/shares");

    spy.mockReturnValue(Promise.resolve(new Response(null, { status: 204 })));
    await ShareApi.revoke("l1");
    expect(String(spy.mock.calls[1][0])).toBe("/api/shares/l1");
    expect(spy.mock.calls[1][1]?.method).toBe("DELETE");

    spy.mockReturnValue(ok({ name: "n", content: "c", updatedAt: null }));
    const view = await ShareApi.view("tok");
    expect(String(spy.mock.calls[2][0])).toBe("/api/share/tok");
    expect(view.name).toBe("n");
  });

  it("shareUrl은 현재 디렉토리 기준 share.html을 가리킨다", () => {
    expect(shareUrl("abc", "http://10.0.0.1:8080", "/wn/index.html"))
      .toBe("http://10.0.0.1:8080/wn/share.html?token=abc");
    expect(shareUrl("a/b", "http://h", "/index.html")).toBe("http://h/share.html?token=a%2Fb");
  });
});
```

`api.test.ts`에 추가:

```typescript
  it("shares / revokeShare", async () => {
    const spy = vi.spyOn(globalThis, "fetch").mockReturnValue(ok([]));
    await AdminApi.shares();
    expect(String(spy.mock.calls[0][0])).toBe("/api/admin/shares");
    spy.mockReturnValue(Promise.resolve(new Response(null, { status: 204 })));
    await AdminApi.revokeShare("l1");
    expect(String(spy.mock.calls[1][0])).toBe("/api/shares/l1");
    expect(spy.mock.calls[1][1]?.method).toBe("DELETE");
  });
```

`mappers.test.ts` 드리프트 가드: KNOWN_ACTS 개수 27→30, `share.create`/`share.view`/`share.revoke` 라벨 존재 단언, actType(share.create)="grant", actType(share.revoke)="revoke", actType(share.view)="etc".

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `cd frontend && pnpm test`
Expected: FAIL — share.ts 미존재 + 드리프트 가드 개수 불일치

- [ ] **Step 3: 구현**

`src/api/share.ts`:

```typescript
/* 공유 링크 API — 노트 앱(ShareModal)·share 앱(SharePage) 공용 단일 출처. */
import { req } from "./http";

export interface ShareLink {
  id: string;
  token: string;
  expiresAt: string;
  maxViews: number | null;
  viewCount: number;
  pinEmps: string[] | null;
  createdBy: string;
  createdAt: string;
}

export interface ShareView {
  name: string;
  content: string;
  updatedAt: string | null;
}

export interface CreateShareBody {
  days?: number;
  maxViews?: number;
  pinEmps?: string[];
}

const JSON_HEADERS = { "Content-Type": "application/json" };

export const ShareApi = {
  create: (nodeId: string, body: CreateShareBody) =>
    req<{ id: string; token: string; expiresAt: string }>(
      `/nodes/${encodeURIComponent(nodeId)}/share`,
      { method: "POST", body: JSON.stringify(body), headers: JSON_HEADERS }),
  listForNode: (nodeId: string) =>
    req<ShareLink[]>(`/nodes/${encodeURIComponent(nodeId)}/shares`),
  revoke: (id: string) =>
    req<void>(`/shares/${encodeURIComponent(id)}`, { method: "DELETE" }),
  view: (token: string) =>
    req<ShareView>(`/share/${encodeURIComponent(token)}`),
};

/** 링크 URL 조립 — 백엔드는 token만 반환(결정 S8). base "./" 배포라 현재 디렉토리 기준. */
export function shareUrl(token: string, origin = location.origin, pathname = location.pathname): string {
  return origin + pathname.replace(/[^/]*$/, "") + "share.html?token=" + encodeURIComponent(token);
}
```

`admin/api.ts`에 타입 + 메서드 추가:

```typescript
export interface ApiShare {
  id: string;
  token: string;
  nodeId: string;
  nodeName: string;
  suspended: boolean;
  createdBy: string;
  createdAt: string;
  expiresAt: string;
  maxViews: number | null;
  viewCount: number;
  pinEmps: string[] | null;
}

// AdminApi 객체에:
  shares: () => req<ApiShare[]>("/admin/shares"),
  revokeShare: (id: string) => req<void>(`/shares/${encodeURIComponent(id)}`, { method: "DELETE" }),
```

`admin/mappers.ts`의 ACTS에 추가(라벨은 기존 톤 유지):

```typescript
  "share.create": "공유 링크 생성",
  "share.view": "공유 링크 열람",
  "share.revoke": "공유 링크 취소",
```

actType: `share.create`는 "grant" 분기에, `share.revoke`는 "revoke" 분기에 추가. `share.view`는 분기 추가 없이 폴백 "etc".

- [ ] **Step 4: 테스트 통과 + 커밋**

Run: `cd frontend && pnpm test`
Expected: 전부 green

```bash
git add frontend/src
git commit -m "feat: 공유 링크 프런트 API 클라이언트 + admin 확장 + 감사 라벨 3종"
```

### Task 7: 노트 앱 공유 모달 + 컨텍스트 메뉴

**Files:**
- Create: `frontend/src/components/ShareModal.tsx`
- Modify: `frontend/src/App.tsx` (노트 컨텍스트 메뉴 + 모달 상태)

- [ ] **Step 1: 구현**

`ShareModal.tsx` — 기존 모달(ProfileModal/SettingsModal)의 마크업·CSS 클래스 패턴을 따른다. run() 패턴(busy 가드 + ApiError 토스트) 준수:

```typescript
/* 공유 링크 모달 — 활성 링크 목록(복사·취소) + 새 링크 생성 폼. http 모드 전용(결정 S13). */
import React from "react";
import { ShareApi, ShareLink, shareUrl } from "../api/share";
import { ApiError } from "../api/http";
import { Icon } from "./Icon";

const { useState, useEffect } = React;
const h = React.createElement;

/** 비보안 컨텍스트(폐쇄망 http) 폴백 포함 복사 (결정 S17). */
function copyText(text: string): Promise<void> {
  if (navigator.clipboard?.writeText) return navigator.clipboard.writeText(text);
  const ta = document.createElement("textarea");
  ta.value = text;
  ta.style.position = "fixed";
  ta.style.opacity = "0";
  document.body.appendChild(ta);
  ta.select();
  document.execCommand("copy");
  document.body.removeChild(ta);
  return Promise.resolve();
}

export function ShareModal({ note, onClose, toast }: {
  note: { id: string; name: string };
  onClose: () => void;
  toast: (msg: string, icon?: string) => void;
}) {
  const [links, setLinks] = useState<ShareLink[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [days, setDays] = useState("7");
  const [maxViews, setMaxViews] = useState("");
  const [pins, setPins] = useState("");

  const reload = () =>
    ShareApi.listForNode(note.id)
      .then(setLinks)
      .catch((e) => toast(e instanceof ApiError ? e.message : "공유 링크를 불러오지 못했습니다"))
      .finally(() => setLoading(false));

  useEffect(() => { reload(); }, [note.id]);

  const create = async () => {
    if (busy) return;
    setBusy(true);
    try {
      const d = parseInt(days, 10);
      const mv = maxViews.trim() === "" ? undefined : parseInt(maxViews, 10);
      const pe = pins.trim() === "" ? undefined : pins.split(",").map((s) => s.trim()).filter(Boolean);
      const res = await ShareApi.create(note.id, { days: Number.isFinite(d) ? d : undefined, maxViews: mv, pinEmps: pe });
      await copyText(shareUrl(res.token));
      toast("공유 링크를 만들어 복사했습니다", "check");
      await reload();
    } catch (e) {
      toast(e instanceof ApiError ? e.message : "공유 링크 생성에 실패했습니다");
    } finally {
      setBusy(false);
    }
  };

  const revoke = async (id: string) => {
    if (busy) return;
    setBusy(true);
    try {
      await ShareApi.revoke(id);
      toast("공유 링크를 취소했습니다", "check");
      await reload();
    } catch (e) {
      toast(e instanceof ApiError ? e.message : "취소에 실패했습니다");
    } finally {
      setBusy(false);
    }
  };

  // 마크업: 모달 오버레이 + 헤더(노트명) + 활성 링크 목록(만료일·열람수·pin, [복사][취소]) +
  // 생성 폼(만료 일수, 최대 열람수(빈칸=무제한), pin 사번 콤마 구분) + [링크 만들기]
  // — 기존 모달 컴포넌트의 클래스(.modal-overlay 등)를 그대로 따라 구현
  ...
}
```

(마크업 세부는 기존 ProfileModal/SettingsModal의 구조를 읽고 동일 클래스로 작성 — 디자인 시스템 일탈 금지.)

`App.tsx` 배선:

```typescript
import { ShareModal } from "./components/ShareModal";
import { storageMode } from "./storage";

// 상태 추가:
const [shareNote, setShareNote] = useState<{ id: string; name: string } | null>(null);

// onContext의 노트(else) 분기 items에, "내보내기" 항목 다음에:
...(storageMode === "http"
  ? [{ icon: "link", label: "공유 링크", onClick: () => setShareNote({ id: node.id, name: node.name }) }]
  : []),

// 렌더 트리에 (다른 모달들과 같은 위치):
shareNote && h(ShareModal, { note: shareNote, onClose: () => setShareNote(null), toast }),
```

Icon에 "link" 아이콘이 없으면 기존 아이콘 셋에서 적절한 것(예: "export"와 구분되는 체인 모양)을 추가 — `components/Icon.tsx`의 기존 path 패턴을 따른다.

- [ ] **Step 2: 검증 + 커밋**

Run: `cd frontend && pnpm test && pnpm build`
Expected: 테스트 green + 빌드 성공 (모달은 API 배선 컴포넌트 — 단위 테스트는 share.ts에 이미 있음, 화면은 Task 10 스모크에서 확인)

```bash
git add frontend/src
git commit -m "feat: 노트 공유 링크 모달 + 컨텍스트 메뉴 (http 모드 전용)"
```

### Task 8: share.html 열람 페이지

**Files:**
- Create: `frontend/share.html`
- Create: `frontend/src/share.tsx`
- Create: `frontend/src/share/SharePage.tsx`
- Modify: `frontend/vite.config.ts` (input에 share 추가)

- [ ] **Step 1: 구현**

`share.html` — login.html과 동일 골격(타이틀 "WorkNote · 공유 노트", `/src/share.tsx` 로드).

`vite.config.ts` input에 추가:

```typescript
        share: resolve(__dirname, "share.html"),
```

`src/share.tsx` — **setOn401 미설치**(결정 S12):

```typescript
/* share 앱 엔트리 — 401 핸들러를 설치하지 않는다: 리다이렉트하면 로그인 후 링크로 복귀 불가(결정 S12). */
import React from "react";
import { createRoot } from "react-dom/client";
import { SharePage } from "./share/SharePage";
// (main.tsx/login.tsx가 import하는 전역 CSS를 동일하게 import)

createRoot(document.getElementById("root")!).render(React.createElement(SharePage));
```

`src/share/SharePage.tsx`:

```typescript
/* 공유 노트 read-only 열람. 상태: loading / ok / unauthorized(로그인 안내) / invalid(404 등). */
import React from "react";
import { ShareApi, ShareView } from "../api/share";
import { ApiError } from "../api/http";
import { renderMarkdown, enhanceMermaid } from "../lib/markdown";

const { useState, useEffect, useRef } = React;
const h = React.createElement;

type State =
  | { kind: "loading" }
  | { kind: "ok"; view: ShareView }
  | { kind: "unauthorized" }
  | { kind: "invalid"; message: string };

export function SharePage() {
  const [state, setState] = useState<State>({ kind: "loading" });
  const bodyRef = useRef<HTMLDivElement>(null);
  const token = new URLSearchParams(location.search).get("token");

  useEffect(() => {
    if (!token) {
      setState({ kind: "invalid", message: "공유 링크 주소가 올바르지 않습니다" });
      return;
    }
    ShareApi.view(token)
      .then((view) => { document.title = "WorkNote · " + view.name; setState({ kind: "ok", view }); })
      .catch((e) => {
        if (e instanceof ApiError && e.status === 401) setState({ kind: "unauthorized" });
        else setState({ kind: "invalid", message: e instanceof ApiError ? e.message : "공유 링크가 유효하지 않습니다" });
      });
  }, []);

  useEffect(() => {
    if (state.kind === "ok" && bodyRef.current) enhanceMermaid(bodyRef.current);
  }, [state]);

  if (state.kind === "loading") return h("div", { className: "share-page" }, "불러오는 중…");
  if (state.kind === "unauthorized") {
    return h("div", { className: "share-page" },
      h("h1", null, "로그인이 필요합니다"),
      h("p", null, "공유 노트는 인증된 직원만 열람할 수 있습니다. 로그인 후 받은 링크를 다시 열어주세요."),
      h("button", { className: "btn", onClick: () => { location.href = "login.html"; } }, "로그인하러 가기"));
  }
  if (state.kind === "invalid") {
    return h("div", { className: "share-page" },
      h("h1", null, "열 수 없는 링크입니다"),
      h("p", null, state.message + " — 만료되었거나 취소되었을 수 있습니다. 링크를 보낸 사람에게 문의하세요."));
  }
  return h("div", { className: "share-page" },
    h("header", { className: "share-head" },
      h("h1", null, state.view.name),
      state.view.updatedAt && h("span", { className: "muted" }, "마지막 수정 " + state.view.updatedAt),
      h("span", { className: "share-badge" }, "읽기 전용 공유")),
    h("div", {
      ref: bodyRef,
      className: "share-body markdown-body",   // 에디터 프리뷰와 같은 렌더 클래스 재사용
      dangerouslySetInnerHTML: { __html: renderMarkdown(state.view.content || "") },
    }));
}
```

스타일: 에디터 프리뷰가 쓰는 마크다운 CSS 클래스를 확인해 동일 클래스 재사용(프리뷰 클래스명이 다르면 그에 맞춤). share 전용 래퍼(가운데 정렬, max-width)만 소량 추가 — 기존 CSS 파일의 변수(--ink 등) 사용.

주의: `renderMarkdown`의 출력이 에디터 프리뷰에서 이미 신뢰되는 경로(자기 vault 노트)와 동일하게, 공유 노트도 사내 작성 콘텐츠다. 단 XSS 표면이 "남이 쓴 노트"로 넓어지므로 renderMarkdown 내부에 sanitize가 있는지 확인하고, 없으면 리뷰에서 판단할 수 있게 구현 보고에 명시할 것.

- [ ] **Step 2: 검증 + 커밋**

Run: `cd frontend && pnpm test && pnpm build`
Expected: green + dist에 share.html 생성 확인 (`ls dist/share.html`)

```bash
git add frontend/share.html frontend/src frontend/vite.config.ts
git commit -m "feat: 공유 노트 열람 페이지 share.html (read-only + 로그인 안내)"
```

### Task 9: admin 활성 링크 화면

**Files:**
- Create: `frontend/src/admin/screens/Shares.tsx`
- Modify: `frontend/src/admin/AdminApp.tsx` (NAV + screenMap 등록)

- [ ] **Step 1: 구현**

`Shares.tsx` — 기존 스크린(Teams.tsx/Audit.tsx)의 구조·클래스·run() 패턴 복붙 허용(공통화 리팩터 금지 관례):

```typescript
/* Admin screen: 활성 공유 링크 — 스펙 §6 "관리자 활성 링크 목록에서 일괄 조회·취소". */
import React from "react";
import { AdminApi, ApiShare } from "../api";
import { ApiError } from "../../api/http";
import { SecHead, Empty, SkeletonTable } from "../common";
import { Icon } from "../../components/Icon";

const { useState, useEffect } = React;
const h = React.createElement;

export function Shares({ toast }: { toast: (msg: string, icon?: string) => void }) {
  const [rows, setRows] = useState<ApiShare[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<string | null>(null);

  const reload = () =>
    AdminApi.shares()
      .then(setRows)
      .catch((e) => toast(e instanceof ApiError ? e.message : "공유 링크 목록 조회 실패"))
      .finally(() => setLoading(false));

  useEffect(() => { reload(); }, []);

  const revoke = async (r: ApiShare) => {
    if (busyId) return;
    if (!confirm(`'${r.nodeName}' 공유 링크를 취소할까요? 받은 사람은 더 이상 열 수 없습니다.`)) return;
    setBusyId(r.id);
    try {
      await AdminApi.revokeShare(r.id);
      toast("공유 링크를 취소했습니다", "check");
      await reload();
    } catch (e) {
      toast(e instanceof ApiError ? e.message : "취소에 실패했습니다");
    } finally {
      setBusyId(null);
    }
  };

  const fmtAt = (at: string) => at.replace("T", " ").slice(0, 19);

  // 테이블: 노트(nodeName + suspended면 "휴지통" 뱃지), 생성자, 생성일, 만료일,
  // 열람(viewCount + (maxViews ? " / " + maxViews : " / ∞")), 대상(pinEmps?.join(", ") ?? "전 직원"), [취소]
  // 빈 상태: Empty { icon: "link", title: "활성 공유 링크가 없습니다" }
  ...
}
```

`AdminApp.tsx`: NAV 배열에 `{ id: "shares", label: "공유 링크", icon: "link" }`(Teams 항목 다음), screenMap에 `shares: Shares` 등록. 기존 등록 방식 그대로.

- [ ] **Step 2: 검증 + 커밋**

Run: `cd frontend && pnpm test && pnpm build`
Expected: green

```bash
git add frontend/src
git commit -m "feat: admin 활성 공유 링크 화면 — 일괄 조회·취소"
```

### Task 10: 통합 검증 + 문서 갱신

**Files:**
- Modify: `backend/README.md`, `CLAUDE.md` (남은 것에서 공유 링크·purge 제거, 테스트 수 갱신)
- Modify: 이 플랜 문서 체크박스

- [ ] **Step 1: 전체 테스트 ×2**

Run: `cd backend && ./gradlew test && ./gradlew test`
Run: `cd frontend && pnpm test && pnpm test`
Expected: 전부 green 2회 연속

- [ ] **Step 2: 빌드 + jar 스모크**

```bash
cd frontend && pnpm build
cd ../backend && ./gradlew bootJar
WORKNOTE_DB=/tmp/wn-smoke.db WORKNOTE_MODE=server WORKNOTE_ADMIN_PASSWORD=smoke1234 \
  java -jar build/libs/worknote-0.1.0.jar &
```

스모크 시나리오(curl, 세션 쿠키 jar):
1. admin 로그인 → 200
2. 노트 생성 POST /api/nodes → 201
3. POST /api/nodes/{id}/share → 201, token 확보
4. GET /share.html → 200 (정적 서빙)
5. GET /api/share/{token} (admin 세션) → 200 본문 확인
6. 무세션 GET /api/share/{token} → 401
7. GET /api/admin/shares → 1건, nodeName 일치
8. DELETE /api/shares/{id} → 204, 재열람 → 404
9. GET /api/admin/audit → share.create/share.view/share.revoke 행 확인 (target에 token 미포함 확인)
10. 종료 후 local 모드 기동 → GET /api/share/{token} 무인증 404(취소됨) 확인 — 선택

- [ ] **Step 3: 문서 갱신 + 커밋**

- `CLAUDE.md`: backend 설명의 "남은 것: 공유 링크(V3), 30일 purge 스케줄러" 제거, 구현 완료 반영
- `backend/README.md`: 이월 목록 갱신(공유 링크·purge 완료 처리), 테스트 수·API 표 갱신
- 이 플랜 문서의 모든 체크박스 [x]

```bash
git add -A
git commit -m "docs: 공유 링크·purge 스케줄러 완료 반영"
```

---

## 이월 후보 (이번 범위 제외 — 변경 금지)

- 이동 노출 변경 경고(§7) — 비공개 노트의 public 폴더 이동 무경고 공개 포함
- 401 리다이렉트 시 디바운스 pending patch 유실 복구
- http 모드 백엔드 다운 시 시드 fallback 차단 배너
- ProfileModal 본인 비밀번호 변경 API
- pin 사번 존재 검증(현재 미검증 — fail-closed라 무해, UX 이슈만)
- 만료/취소 링크 행 정리 배치(현재 영구 보존 — 감사 재구성 우선)
