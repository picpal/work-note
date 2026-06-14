# 개인정보(PII) 탐지·예외요청·관리자알림 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 노트 저장 시 백엔드가 표준 PII를 탐지해 노트에 주의 표시를 달고, 사용자의 예외 요청 → 관리자 허용/반려 워크플로와 로그인 팝업 알림을 제공한다.

**Architecture:** 백엔드는 `PiiDetector`(순수 정규식+체크섬) → `PiiService`(상태 기계, `pii_flag`/`pii_notice` 테이블) → `PiiController`(node/admin/me 엔드포인트). `VaultController.update`(PATCH)가 저장 후 `evaluate`를 호출하고 응답에 `pii`를 실어 보낸다. 프런트는 트리 GET의 `VaultNode.pii`로 사이드바·배너 초기 상태를 잡고, PATCH 응답의 `pii`를 전용 액션 `setNotePii`(디바운스 PATCH 비유발)로 라이브 반영한다. 관리자 스크린과 로그인 팝업이 알림을 처리한다.

**Tech Stack:** Java 21 · Spring Boot 3.5 · MyBatis · Flyway · SQLite (backend) / Vite 6 · TypeScript · React 18(`createElement`, NO JSX) · Vitest (frontend). 스펙: `docs/superpowers/specs/2026-06-14-worknote-개인정보-PII-탐지-design.md`.

**제약:** main 직커밋, 한국어 conventional commit, **push 금지**(사용자 명시 요청 시만).

---

## 파일 구조 (생성/수정)

**백엔드 (`backend/src/main/...`)**
- 생성 `java/com/worknote/pii/PiiType.java` — 탐지 유형 enum + CSV 직렬화
- 생성 `java/com/worknote/pii/PiiDetector.java` — 순수 탐지 함수(정규식+체크섬)
- 생성 `java/com/worknote/pii/PiiFlagRow.java` · `PiiNoticeRow.java` · `PiiInfo.java` · `PiiEval.java` — 레코드
- 생성 `java/com/worknote/pii/PiiMapper.java` + `resources/mappers/PiiMapper.xml` — flag/notice CRUD·조회
- 생성 `java/com/worknote/pii/PiiService.java` — 상태 기계·요청·결정·알림
- 생성 `java/com/worknote/pii/PiiController.java` — node/admin/me 엔드포인트
- 생성 `resources/db/migration/sqlite/V5__pii.sql` — node.updated_by + pii_flag + pii_notice
- 수정 `java/com/worknote/vault/VaultNode.java` — `pii` 필드
- 수정 `java/com/worknote/vault/NodeMapper.java` + `resources/mappers/NodeMapper.xml` — `updateFields`에 updated_by, `findActiveFlags`/assemble용
- 수정 `java/com/worknote/vault/VaultService.java` — `update` 시그니처(updatedBy), assemble의 pii 주입, purge cascade
- 수정 `java/com/worknote/vault/VaultController.java` — `update`가 evaluate 호출 + pii 반환
- 테스트 `test/java/com/worknote/pii/PiiDetectorTest.java` · `PiiServiceTest.java` · `PiiApiTest.java`

**프런트 (`frontend/src/...`)**
- 수정 `types.ts` — `NotePii` + `NoteNode.pii`
- 생성 `lib/pii.ts` — `piiWarns`, 유형 라벨
- 수정 `state/vaultReducer.ts` · `state/useVault.ts` · `state/useVaultSync.ts` — `setNotePii` 액션 + PATCH 응답 반영
- 수정 `storage/VaultApi.ts` — `update` 반환에 pii
- 생성 `storage/PiiApi.ts` — 예외 요청·내 알림
- 수정 `admin/api.ts` — pii 관리자 메서드
- 수정 `components/Sidebar.tsx` — 경고 아이콘
- 수정 `components/Editor.tsx` — 배너 + 예외 요청 버튼
- 생성 `admin/screens/Pii.tsx` + 수정 `admin/AdminApp.tsx` — 관리자 점검 스크린
- 생성 `components/PiiNoticeModal.tsx` + 수정 `App.tsx` — 로그인 팝업
- 테스트 `state/vaultReducer.test.ts`(추가) · `lib/pii.test.ts`

---

# Phase 1 — 백엔드

### Task 1: PiiType enum + PiiDetector (순수 탐지기)

**Files:**
- Create: `backend/src/main/java/com/worknote/pii/PiiType.java`
- Create: `backend/src/main/java/com/worknote/pii/PiiDetector.java`
- Test: `backend/src/test/java/com/worknote/pii/PiiDetectorTest.java`

- [ ] **Step 1: 실패 테스트 작성**

`backend/src/test/java/com/worknote/pii/PiiDetectorTest.java`:
```java
package com.worknote.pii;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class PiiDetectorTest {

    @Test void 유효_주민번호_탐지() {
        // 체크섬 유효한 샘플(테스트 전용 합성값)
        assertTrue(PiiDetector.detect("주민번호 900101-1234560 입니다").contains(PiiType.RRN));
    }

    @Test void 체크섬_무효_주민형식은_음성() {
        // 형식만 맞고 체크 자리 틀림 → 오탐 억제
        assertFalse(PiiDetector.detect("900101-1234561").contains(PiiType.RRN));
    }

    @Test void 휴대폰_탐지() {
        assertTrue(PiiDetector.detect("연락처 010-1234-5678").contains(PiiType.PHONE));
        assertTrue(PiiDetector.detect("01098765432").contains(PiiType.PHONE));
    }

    @Test void 이메일_탐지() {
        assertTrue(PiiDetector.detect("a.b+c@example.co.kr 로 회신").contains(PiiType.EMAIL));
    }

    @Test void 신용카드_Luhn_통과만_탐지() {
        assertTrue(PiiDetector.detect("4111-1111-1111-1111").contains(PiiType.CARD));   // Luhn 유효
        assertFalse(PiiDetector.detect("4111-1111-1111-1112").contains(PiiType.CARD));  // Luhn 무효
    }

    @Test void 사업자번호_체크섬() {
        assertTrue(PiiDetector.detect("220-81-62517").contains(PiiType.BIZ));   // 유효 체크섬
        assertFalse(PiiDetector.detect("123-45-67890").contains(PiiType.BIZ));  // 무효
    }

    @Test void 여권_운전면허() {
        assertTrue(PiiDetector.detect("여권 M12345678").contains(PiiType.PASSPORT));
        assertTrue(PiiDetector.detect("면허 11-22-123456-78").contains(PiiType.DRIVER));
    }

    @Test void PII_없으면_빈집합() {
        assertTrue(PiiDetector.detect("그냥 평범한 회의록 내용입니다.").isEmpty());
        assertTrue(PiiDetector.detect(null).isEmpty());
    }

    @Test void CSV_직렬화_정렬() {
        assertEquals("email,phone", PiiType.csv(Set.of(PiiType.PHONE, PiiType.EMAIL)));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew test --tests 'com.worknote.pii.PiiDetectorTest'`
Expected: FAIL (PiiType/PiiDetector 미존재 컴파일 에러)

- [ ] **Step 3: PiiType 구현**

`backend/src/main/java/com/worknote/pii/PiiType.java`:
```java
package com.worknote.pii;

import java.util.Collection;
import java.util.stream.Collectors;

/** 탐지 PII 유형. types 컬럼 직렬화는 enum name 소문자 CSV. */
public enum PiiType {
    RRN, PHONE, EMAIL, CARD, BIZ, PASSPORT, DRIVER;

    public static String csv(Collection<PiiType> types) {
        return types.stream().map(t -> t.name().toLowerCase()).sorted().collect(Collectors.joining(","));
    }
}
```

- [ ] **Step 4: PiiDetector 구현**

`backend/src/main/java/com/worknote/pii/PiiDetector.java`:
```java
package com.worknote.pii;

import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 표준 한국 PII 탐지(순수). 오탐 억제 위해 RRN/CARD/BIZ는 체크섬 검증. 유선전화·계좌는 의도적 제외. */
public final class PiiDetector {
    private PiiDetector() {}

    private static final Pattern RRN      = Pattern.compile("(?<!\\d)(\\d{2})(\\d{2})(\\d{2})[-\\s]?([1-8])(\\d{6})(?!\\d)");
    private static final Pattern PHONE    = Pattern.compile("(?<!\\d)01[016789][-\\s]?\\d{3,4}[-\\s]?\\d{4}(?!\\d)");
    private static final Pattern EMAIL    = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern CARD     = Pattern.compile("(?<!\\d)(?:\\d[ -]?){15}\\d(?!\\d)");
    private static final Pattern BIZ      = Pattern.compile("(?<!\\d)(\\d{3})-(\\d{2})-(\\d{5})(?!\\d)");
    private static final Pattern PASSPORT = Pattern.compile("(?<![A-Z0-9])[A-Z]\\d{8}(?![A-Z0-9])");
    private static final Pattern DRIVER   = Pattern.compile("(?<!\\d)\\d{2}[-\\s]?\\d{2}[-\\s]?\\d{6}[-\\s]?\\d{2}(?!\\d)");

    public static Set<PiiType> detect(String text) {
        EnumSet<PiiType> found = EnumSet.noneOf(PiiType.class);
        if (text == null || text.isEmpty()) return found;
        if (anyRrn(text)) found.add(PiiType.RRN);
        if (anyBiz(text)) found.add(PiiType.BIZ);
        if (anyCard(text)) found.add(PiiType.CARD);
        if (PHONE.matcher(text).find()) found.add(PiiType.PHONE);
        if (EMAIL.matcher(text).find()) found.add(PiiType.EMAIL);
        if (PASSPORT.matcher(text).find()) found.add(PiiType.PASSPORT);
        if (DRIVER.matcher(text).find()) found.add(PiiType.DRIVER);
        return found;
    }

    private static boolean anyRrn(String text) {
        Matcher m = RRN.matcher(text);
        while (m.find()) {
            String digits = (m.group(1) + m.group(2) + m.group(3) + m.group(4) + m.group(5));
            if (rrnChecksum(digits)) return true;
        }
        return false;
    }

    /** 주민/외국인등록번호 13자리 체크섬. */
    private static boolean rrnChecksum(String d) {
        int[] w = {2, 3, 4, 5, 6, 7, 8, 9, 2, 3, 4, 5};
        int sum = 0;
        for (int i = 0; i < 12; i++) sum += (d.charAt(i) - '0') * w[i];
        int check = (11 - (sum % 11)) % 10;
        return check == (d.charAt(12) - '0');
    }

    private static boolean anyBiz(String text) {
        Matcher m = BIZ.matcher(text);
        while (m.find()) {
            String d = (m.group(1) + m.group(2) + m.group(3));
            if (bizChecksum(d)) return true;
        }
        return false;
    }

    /** 사업자등록번호 10자리 체크섬. */
    private static boolean bizChecksum(String d) {
        int[] w = {1, 3, 7, 1, 3, 7, 1, 3, 5};
        int sum = 0;
        for (int i = 0; i < 9; i++) sum += (d.charAt(i) - '0') * w[i];
        sum += ((d.charAt(8) - '0') * 5) / 10;
        int check = (10 - (sum % 10)) % 10;
        return check == (d.charAt(9) - '0');
    }

    private static boolean anyCard(String text) {
        Matcher m = CARD.matcher(text);
        while (m.find()) {
            if (luhn(m.group().replaceAll("[ -]", ""))) return true;
        }
        return false;
    }

    private static boolean luhn(String d) {
        int sum = 0; boolean alt = false;
        for (int i = d.length() - 1; i >= 0; i--) {
            int n = d.charAt(i) - '0';
            if (alt) { n *= 2; if (n > 9) n -= 9; }
            sum += n; alt = !alt;
        }
        return sum % 10 == 0;
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests 'com.worknote.pii.PiiDetectorTest'`
Expected: PASS (9개)
> 주의: 테스트의 `220-81-62517`(BIZ), `4111-1111-1111-1111`(CARD Luhn), `900101-1234560`(RRN) 체크섬이 실제 통과해야 한다. 실패 시 테스트의 합성값을 실제 체크섬 유효값으로 교체(알고리즘은 표준이므로 구현이 아닌 샘플을 조정).

- [ ] **Step 6: 커밋**
```bash
git add backend/src/main/java/com/worknote/pii/ backend/src/test/java/com/worknote/pii/PiiDetectorTest.java
git commit -m "feat: PII 탐지기(PiiDetector) — 표준 7종 정규식+체크섬"
```

---

### Task 2: V5 마이그레이션 + PiiMapper (flag/notice 영속)

**Files:**
- Create: `backend/src/main/resources/db/migration/sqlite/V5__pii.sql`
- Create: `backend/src/main/java/com/worknote/pii/PiiFlagRow.java`
- Create: `backend/src/main/java/com/worknote/pii/PiiNoticeRow.java`
- Create: `backend/src/main/java/com/worknote/pii/PiiMapper.java`
- Create: `backend/src/main/resources/mappers/PiiMapper.xml`
- Test: `backend/src/test/java/com/worknote/pii/PiiMapperTest.java`

- [ ] **Step 1: 마이그레이션 작성**

`backend/src/main/resources/db/migration/sqlite/V5__pii.sql`:
```sql
-- V5__pii.sql  (ANSI 지향 — Oracle 전환 시 TEXT→VARCHAR2/CLOB)
ALTER TABLE node ADD COLUMN updated_by TEXT;

CREATE TABLE pii_flag (
  node_id         TEXT PRIMARY KEY REFERENCES node(id),
  status          TEXT NOT NULL CHECK (status IN ('suspected','requested','exempted','rejected')),
  types           TEXT NOT NULL,
  detected_at     TEXT NOT NULL,
  requested_by    TEXT, requested_at TEXT, request_reason  TEXT,
  decided_by      TEXT, decided_at   TEXT, decision_reason TEXT
);

CREATE TABLE pii_notice (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  node_id   TEXT NOT NULL REFERENCES node(id),
  recipient TEXT NOT NULL,
  kind      TEXT NOT NULL CHECK (kind IN ('flagged','approved','rejected')),
  message   TEXT,
  sent_by   TEXT NOT NULL, sent_at TEXT NOT NULL,
  ack_at    TEXT
);
CREATE INDEX idx_pii_notice_recipient ON pii_notice(recipient, ack_at);
```

- [ ] **Step 2: 레코드 작성**

`backend/src/main/java/com/worknote/pii/PiiFlagRow.java`:
```java
package com.worknote.pii;

public record PiiFlagRow(
    String nodeId, String status, String types, String detectedAt,
    String requestedBy, String requestedAt, String requestReason,
    String decidedBy, String decidedAt, String decisionReason
) {}
```

`backend/src/main/java/com/worknote/pii/PiiNoticeRow.java`:
```java
package com.worknote.pii;

public record PiiNoticeRow(
    Long id, String nodeId, String recipient, String kind,
    String message, String sentBy, String sentAt, String ackAt
) {}
```

- [ ] **Step 3: 매퍼 인터페이스 작성**

`backend/src/main/java/com/worknote/pii/PiiMapper.java`:
```java
package com.worknote.pii;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

@Mapper
public interface PiiMapper {
    PiiFlagRow findFlag(@Param("nodeId") String nodeId);
    void insertFlag(PiiFlagRow row);
    void updateFlag(PiiFlagRow row);
    void deleteFlag(@Param("nodeId") String nodeId);
    void deleteFlagsIn(@Param("ids") List<String> ids);
    /** 활성 노트의 플래그 — 트리 조립용. (node_id, status, types) */
    List<PiiFlagRow> activeFlags();
    /** 관리자: 전체 플래그 노트 + 제목/최종수정자 조인. */
    List<Map<String, Object>> adminList();
    /** 관리자: 예외 요청 대기(status='requested'). */
    List<Map<String, Object>> adminRequests();

    void insertNotice(PiiNoticeRow row);
    void deleteNoticesIn(@Param("ids") List<String> ids);
    /** 같은 (node,recipient,kind)의 미확인 알림 id — 중복 방지용. */
    Long findUnackedNoticeId(@Param("nodeId") String nodeId, @Param("recipient") String recipient, @Param("kind") String kind);
    void touchNotice(@Param("id") Long id, @Param("message") String message, @Param("sentAt") String sentAt);
    /** 수신자 미확인 알림 + 노트 제목. */
    List<Map<String, Object>> noticesFor(@Param("recipient") String recipient);
    void ack(@Param("recipient") String recipient, @Param("ids") List<Long> ids);
}
```

- [ ] **Step 4: 매퍼 XML 작성**

`backend/src/main/resources/mappers/PiiMapper.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.worknote.pii.PiiMapper">

  <select id="findFlag" resultType="com.worknote.pii.PiiFlagRow">
    SELECT node_id, status, types, detected_at, requested_by, requested_at, request_reason,
           decided_by, decided_at, decision_reason
    FROM pii_flag WHERE node_id = #{nodeId}
  </select>

  <insert id="insertFlag">
    INSERT INTO pii_flag (node_id, status, types, detected_at, requested_by, requested_at, request_reason, decided_by, decided_at, decision_reason)
    VALUES (#{nodeId}, #{status}, #{types}, #{detectedAt}, #{requestedBy}, #{requestedAt}, #{requestReason}, #{decidedBy}, #{decidedAt}, #{decisionReason})
  </insert>

  <update id="updateFlag">
    UPDATE pii_flag SET status=#{status}, types=#{types}, detected_at=#{detectedAt},
      requested_by=#{requestedBy}, requested_at=#{requestedAt}, request_reason=#{requestReason},
      decided_by=#{decidedBy}, decided_at=#{decidedAt}, decision_reason=#{decisionReason}
    WHERE node_id=#{nodeId}
  </update>

  <delete id="deleteFlag">DELETE FROM pii_flag WHERE node_id=#{nodeId}</delete>
  <delete id="deleteFlagsIn">
    DELETE FROM pii_flag WHERE node_id IN
    <foreach item="i" collection="ids" open="(" separator="," close=")">#{i}</foreach>
  </delete>

  <select id="activeFlags" resultType="com.worknote.pii.PiiFlagRow">
    SELECT f.node_id, f.status, f.types, f.detected_at, f.requested_by, f.requested_at, f.request_reason,
           f.decided_by, f.decided_at, f.decision_reason
    FROM pii_flag f JOIN node n ON n.id = f.node_id WHERE n.deleted_at IS NULL
  </select>

  <select id="adminList" resultType="map">
    SELECT f.node_id AS nodeId, n.name AS title, n.updated_by AS updatedBy,
           f.types AS types, f.status AS status, f.detected_at AS detectedAt
    FROM pii_flag f JOIN node n ON n.id = f.node_id
    WHERE n.deleted_at IS NULL ORDER BY f.detected_at DESC
  </select>

  <select id="adminRequests" resultType="map">
    SELECT f.node_id AS nodeId, n.name AS title, n.updated_by AS updatedBy,
           f.types AS types, f.requested_by AS requestedBy, f.requested_at AS requestedAt,
           f.request_reason AS requestReason
    FROM pii_flag f JOIN node n ON n.id = f.node_id
    WHERE n.deleted_at IS NULL AND f.status = 'requested' ORDER BY f.requested_at
  </select>

  <insert id="insertNotice">
    INSERT INTO pii_notice (node_id, recipient, kind, message, sent_by, sent_at, ack_at)
    VALUES (#{nodeId}, #{recipient}, #{kind}, #{message}, #{sentBy}, #{sentAt}, #{ackAt})
  </insert>
  <delete id="deleteNoticesIn">
    DELETE FROM pii_notice WHERE node_id IN
    <foreach item="i" collection="ids" open="(" separator="," close=")">#{i}</foreach>
  </delete>
  <select id="findUnackedNoticeId" resultType="long">
    SELECT id FROM pii_notice
    WHERE node_id=#{nodeId} AND recipient=#{recipient} AND kind=#{kind} AND ack_at IS NULL
    ORDER BY id DESC LIMIT 1
  </select>
  <update id="touchNotice">
    UPDATE pii_notice SET message=#{message}, sent_at=#{sentAt} WHERE id=#{id}
  </update>
  <select id="noticesFor" resultType="map">
    SELECT p.id AS id, p.node_id AS noteId, n.name AS noteTitle, p.kind AS kind, p.message AS message
    FROM pii_notice p JOIN node n ON n.id = p.node_id
    WHERE p.recipient=#{recipient} AND p.ack_at IS NULL ORDER BY p.id
  </select>
  <update id="ack">
    UPDATE pii_notice SET ack_at = (SELECT datetime('now'))
    WHERE recipient=#{recipient} AND ack_at IS NULL
    <if test="ids != null and ids.size() > 0">
      AND id IN <foreach item="i" collection="ids" open="(" separator="," close=")">#{i}</foreach>
    </if>
  </update>

</mapper>
```

- [ ] **Step 5: 매퍼 통합 테스트 작성**

`backend/src/test/java/com/worknote/pii/PiiMapperTest.java`:
```java
package com.worknote.pii;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class PiiMapperTest {
    @Autowired PiiMapper pii;
    @Autowired NodeMapper nodes;

    @Test void flag_upsert_and_find() {
        nodes.insert(new NodeRow("pm1", null, "note", "n", 1, "c", "2026-06-14T00:00:00", null, null));
        pii.insertFlag(new PiiFlagRow("pm1", "suspected", "rrn", "2026-06-14T00:00:00", null, null, null, null, null, null));
        PiiFlagRow row = pii.findFlag("pm1");
        assertEquals("suspected", row.status());
        assertEquals("rrn", row.types());
        assertEquals(1, pii.activeFlags().size());
        pii.deleteFlag("pm1");
        assertNull(pii.findFlag("pm1"));
    }

    @Test void notice_dedup_and_ack() {
        nodes.insert(new NodeRow("pm2", null, "note", "제목", 1, "c", "2026-06-14T00:00:00", null, null));
        pii.insertNotice(new PiiNoticeRow(null, "pm2", "e100", "flagged", null, "admin", "2026-06-14T00:00:00", null));
        Long dup = pii.findUnackedNoticeId("pm2", "e100", "flagged");
        assertNotNull(dup);
        List<java.util.Map<String, Object>> mine = pii.noticesFor("e100");
        assertEquals(1, mine.size());
        assertEquals("제목", mine.get(0).get("noteTitle"));
        pii.ack("e100", null);
        assertTrue(pii.noticesFor("e100").isEmpty());
    }
}
```

- [ ] **Step 6: 테스트 실행 (실패→구현 확인)**

Run: `cd backend && ./gradlew test --tests 'com.worknote.pii.PiiMapperTest'`
Expected: PASS (마이그레이션 V5 적용 + 매퍼 동작). 실패 시 XML 매핑/마이그레이션 점검.

- [ ] **Step 7: 커밋**
```bash
git add backend/src/main/resources/db/migration/sqlite/V5__pii.sql backend/src/main/java/com/worknote/pii/ backend/src/main/resources/mappers/PiiMapper.xml backend/src/test/java/com/worknote/pii/PiiMapperTest.java
git commit -m "feat: V5 마이그레이션(pii_flag·pii_notice·node.updated_by) + PiiMapper"
```

---

### Task 3: PiiService.evaluate (상태 기계)

**Files:**
- Create: `backend/src/main/java/com/worknote/pii/PiiEval.java`
- Create: `backend/src/main/java/com/worknote/pii/PiiService.java`
- Test: `backend/src/test/java/com/worknote/pii/PiiServiceTest.java`

- [ ] **Step 1: PiiEval 레코드 작성**

`backend/src/main/java/com/worknote/pii/PiiEval.java`:
```java
package com.worknote.pii;

import java.util.List;

/** PATCH 응답·평가 결과. status ∈ none/suspected/requested/exempted/rejected. */
public record PiiEval(String status, List<String> types) {}
```

- [ ] **Step 2: 실패 테스트 작성**

`backend/src/test/java/com/worknote/pii/PiiServiceTest.java`:
```java
package com.worknote.pii;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class PiiServiceTest {
    @Autowired PiiService svc;
    @Autowired PiiMapper pii;
    @Autowired NodeMapper nodes;

    private void note(String id) {
        nodes.insert(new NodeRow(id, null, "note", "n", 1, "", "2026-06-14T00:00:00", null, null));
    }

    @Test void none_when_no_pii() {
        note("s1");
        PiiEval e = svc.evaluate("s1", "평범한 내용");
        assertEquals("none", e.status());
        assertNull(pii.findFlag("s1"));
    }

    @Test void suspected_on_detect_then_stays() {
        note("s2");
        assertEquals("suspected", svc.evaluate("s2", "010-1234-5678").status());
        assertEquals("suspected", svc.evaluate("s2", "010-1234-5678 추가").status());
        assertEquals("suspected", pii.findFlag("s2").status());
    }

    @Test void removed_pii_deletes_flag() {
        note("s3");
        svc.evaluate("s3", "010-1234-5678");
        assertEquals("none", svc.evaluate("s3", "내용 정리됨").status());
        assertNull(pii.findFlag("s3"));
    }

    @Test void exempted_stays_for_same_types_but_reverts_on_new_type() {
        note("s4");
        svc.evaluate("s4", "010-1234-5678");
        svc.requestException("s4", "e1", "오탐");
        svc.approve("s4", "admin");
        assertEquals("exempted", svc.evaluate("s4", "010-1234-5678 동일유형").status());      // 유지
        assertEquals("suspected", svc.evaluate("s4", "010-1234-5678 a.b@c.com").status());     // 새 유형 EMAIL → 복귀
    }

    @Test void rejected_persists_through_save() {
        note("s5");
        svc.evaluate("s5", "010-1234-5678");
        svc.requestException("s5", "e1", null);
        svc.reject("s5", "admin", "실제 개인정보로 보임");
        assertEquals("rejected", svc.evaluate("s5", "010-1234-5678 여전히").status());
    }

    @Test void request_only_from_suspected_or_rejected() {
        note("s6");
        svc.evaluate("s6", "010-1234-5678");
        svc.requestException("s6", "e1", null);   // suspected→requested OK
        assertThrows(RuntimeException.class, () -> svc.requestException("s6", "e1", null)); // requested에서 재요청 거부
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `cd backend && ./gradlew test --tests 'com.worknote.pii.PiiServiceTest'`
Expected: FAIL (PiiService 미존재)

- [ ] **Step 4: PiiService 구현(evaluate + request/approve/reject 일부)**

`backend/src/main/java/com/worknote/pii/PiiService.java`:
```java
package com.worknote.pii;

import com.worknote.vault.VaultException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** PII 상태 기계 + 예외 요청/관리자 결정/알림. 탐지는 PiiDetector(순수)에 위임. */
@Service
public class PiiService {

    private final PiiMapper mapper;
    private final Clock clock;

    public PiiService(PiiMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    private String now() {
        return LocalDateTime.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private static List<String> typesList(String csv) {
        return (csv == null || csv.isEmpty()) ? List.of() : Arrays.asList(csv.split(","));
    }

    /** 저장 시 재탐지 + 상태 기계 적용. content가 변경된 PATCH에서만 호출. */
    @Transactional
    public PiiEval evaluate(String nodeId, String content) {
        String matched = PiiType.csv(PiiDetector.detect(content));
        PiiFlagRow cur = mapper.findFlag(nodeId);

        if (matched.isEmpty()) {
            if (cur != null) mapper.deleteFlag(nodeId);
            return new PiiEval("none", List.of());
        }
        if (cur == null) {
            mapper.insertFlag(new PiiFlagRow(nodeId, "suspected", matched, now(),
                null, null, null, null, null, null));
            return new PiiEval("suspected", typesList(matched));
        }
        // exempted: 기존 검토 유형의 부분집합이면 유지, 새 유형 등장 시 suspected 복귀
        if ("exempted".equals(cur.status())) {
            Set<String> old = new TreeSet<>(typesList(cur.types()));
            Set<String> nw = new TreeSet<>(typesList(matched));
            if (old.containsAll(nw)) {
                return new PiiEval("exempted", typesList(cur.types()));
            }
            mapper.updateFlag(new PiiFlagRow(nodeId, "suspected", matched, now(),
                null, null, null, null, null, null));   // 요청/결정 필드 초기화
            return new PiiEval("suspected", typesList(matched));
        }
        // suspected/requested/rejected: 상태 유지, types/detected_at 갱신
        mapper.updateFlag(new PiiFlagRow(nodeId, cur.status(), matched, now(),
            cur.requestedBy(), cur.requestedAt(), cur.requestReason(),
            cur.decidedBy(), cur.decidedAt(), cur.decisionReason()));
        return new PiiEval(cur.status(), typesList(matched));
    }

    /** 사용자 예외 요청 — suspected/rejected에서만 허용. */
    @Transactional
    public void requestException(String nodeId, String emp, String reason) {
        PiiFlagRow cur = mapper.findFlag(nodeId);
        if (cur == null || !(cur.status().equals("suspected") || cur.status().equals("rejected"))) {
            throw VaultException.invalid("예외 요청할 수 있는 상태가 아닙니다");
        }
        mapper.updateFlag(new PiiFlagRow(nodeId, "requested", cur.types(), cur.detectedAt(),
            emp, now(), reason, null, null, null));
    }

    /** 관리자 허용 → exempted. */
    @Transactional
    public void approve(String nodeId, String adminEmp) {
        PiiFlagRow cur = requireFlag(nodeId);
        mapper.updateFlag(new PiiFlagRow(nodeId, "exempted", cur.types(), cur.detectedAt(),
            cur.requestedBy(), cur.requestedAt(), cur.requestReason(), adminEmp, now(), null));
    }

    /** 관리자 반려 → rejected(+사유). */
    @Transactional
    public void reject(String nodeId, String adminEmp, String reason) {
        PiiFlagRow cur = requireFlag(nodeId);
        mapper.updateFlag(new PiiFlagRow(nodeId, "rejected", cur.types(), cur.detectedAt(),
            cur.requestedBy(), cur.requestedAt(), cur.requestReason(), adminEmp, now(), reason));
    }

    private PiiFlagRow requireFlag(String nodeId) {
        PiiFlagRow cur = mapper.findFlag(nodeId);
        if (cur == null) throw VaultException.notFound("플래그가 없습니다: " + nodeId);
        return cur;
    }
}
```
> `VaultException.invalid`/`notFound`는 기존 vault 패키지의 정적 팩터리(VaultService에서 사용 중). import 경로 `com.worknote.vault.VaultException`.

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests 'com.worknote.pii.PiiServiceTest'`
Expected: PASS (6개)

- [ ] **Step 6: 커밋**
```bash
git add backend/src/main/java/com/worknote/pii/PiiEval.java backend/src/main/java/com/worknote/pii/PiiService.java backend/src/test/java/com/worknote/pii/PiiServiceTest.java
git commit -m "feat: PiiService 상태 기계(evaluate/request/approve/reject)"
```

---

### Task 4: node.updated_by 배선 (update 경로)

**Files:**
- Modify: `backend/src/main/java/com/worknote/vault/NodeMapper.java`
- Modify: `backend/src/main/resources/mappers/NodeMapper.xml:35-41`
- Modify: `backend/src/main/java/com/worknote/vault/VaultService.java:90-97`
- Test: `backend/src/test/java/com/worknote/pii/UpdatedByTest.java`

- [ ] **Step 1: 실패 테스트 작성**

`backend/src/test/java/com/worknote/pii/UpdatedByTest.java`:
```java
package com.worknote.pii;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import com.worknote.vault.VaultService;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class UpdatedByTest {
    @Autowired VaultService vault;
    @Autowired NodeMapper nodes;

    @Test void update_sets_updated_by() {
        nodes.insert(new NodeRow("ub1", null, "note", "n", 1, "old", "2026-06-14T00:00:00", null, null));
        vault.update("ub1", null, "new content", List.of(), "e777");
        // updated_by는 매퍼 전용 조회로 확인
        assertEquals("e777", nodes.findUpdatedBy("ub1"));
    }
}
```

- [ ] **Step 2: NodeMapper 인터페이스에 메서드 추가**

`backend/src/main/java/com/worknote/vault/NodeMapper.java` — `updateFields` 시그니처 변경 + `findUpdatedBy` 추가(기존 인터페이스 파일에 다음 메서드 시그니처가 존재/추가되도록):
```java
    void updateFields(@org.apache.ibatis.annotations.Param("id") String id,
                      @org.apache.ibatis.annotations.Param("name") String name,
                      @org.apache.ibatis.annotations.Param("content") String content,
                      @org.apache.ibatis.annotations.Param("updatedAt") String updatedAt,
                      @org.apache.ibatis.annotations.Param("updatedBy") String updatedBy);

    String findUpdatedBy(@org.apache.ibatis.annotations.Param("id") String id);
```
> 기존 `updateFields(id, name, content, updatedAt)`가 `@Param` 없이 정의돼 있었다면 위처럼 `@Param` 명시로 교체(XML이 `#{updatedBy}` 등 이름 참조). 다른 호출부는 VaultService뿐.

- [ ] **Step 3: NodeMapper.xml 수정**

`backend/src/main/resources/mappers/NodeMapper.xml`의 `updateFields`를 교체하고 `findUpdatedBy` 추가:
```xml
  <update id="updateFields">
    UPDATE node SET
      name = COALESCE(#{name}, name),
      content = CASE WHEN #{content} IS NULL THEN content ELSE #{content} END,
      updated_at = #{updatedAt},
      updated_by = COALESCE(#{updatedBy}, updated_by)
    WHERE id = #{id}
  </update>

  <select id="findUpdatedBy" resultType="string">
    SELECT updated_by FROM node WHERE id = #{id}
  </select>
```

- [ ] **Step 4: VaultService.update 시그니처 변경**

`backend/src/main/java/com/worknote/vault/VaultService.java:90-97` 교체:
```java
    @Transactional
    public void update(String id, String name, String content, List<String> tags, String updatedBy) {
        requireActive(id);
        mapper.updateFields(id, name, content, nowIso(), updatedBy);
        if (tags != null) {
            mapper.replaceTags(id, tags);
        }
    }
```
> 호출부는 `VaultController.update` 하나(다음 Task 6에서 함께 수정). 이 Task 동안 컴파일을 위해 컨트롤러 호출을 임시로 `svc.update(id, body.name(), body.content(), body.tags(), guard.who(user(req)))`로 갱신(Task 6에서 evaluate까지 마저 배선).

- [ ] **Step 5: 테스트 통과 확인 + 전체 회귀**

Run: `cd backend && ./gradlew test --tests 'com.worknote.pii.UpdatedByTest'`
Expected: PASS
Run: `cd backend && ./gradlew test`
Expected: 기존 테스트 전부 GREEN(컨트롤러 호출부 갱신으로 컴파일 통과).

- [ ] **Step 6: 커밋**
```bash
git add backend/src/main/java/com/worknote/vault/NodeMapper.java backend/src/main/resources/mappers/NodeMapper.xml backend/src/main/java/com/worknote/vault/VaultService.java backend/src/main/java/com/worknote/vault/VaultController.java backend/src/test/java/com/worknote/pii/UpdatedByTest.java
git commit -m "feat: 노트 content 저장 시 updated_by(최종 수정자) 기록"
```

---

### Task 5: VaultNode.pii + 트리 조립 주입

**Files:**
- Create: `backend/src/main/java/com/worknote/pii/PiiInfo.java`
- Modify: `backend/src/main/java/com/worknote/vault/VaultNode.java`
- Modify: `backend/src/main/java/com/worknote/vault/VaultService.java` (생성자 호출부 6곳 + assemble에 pii 주입)
- Test: `backend/src/test/java/com/worknote/pii/TreePiiTest.java`

- [ ] **Step 1: PiiInfo 레코드 작성**

`backend/src/main/java/com/worknote/pii/PiiInfo.java`:
```java
package com.worknote.pii;

import java.util.List;

/** 트리 응답에 실리는 노트 PII 요약. status + 유형. null이면 플래그 없음. */
public record PiiInfo(String status, List<String> types) {}
```

- [ ] **Step 2: 실패 테스트 작성**

`backend/src/test/java/com/worknote/pii/TreePiiTest.java`:
```java
package com.worknote.pii;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import com.worknote.vault.VaultNode;
import com.worknote.vault.VaultService;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class TreePiiTest {
    @Autowired VaultService vault;
    @Autowired PiiService pii;
    @Autowired NodeMapper nodes;

    @Test void tree_carries_pii_for_flagged_note() {
        nodes.insert(new NodeRow("tp1", null, "note", "노트", 1, "010-1234-5678", "2026-06-14T00:00:00", null, null));
        pii.evaluate("tp1", "010-1234-5678");
        VaultNode n = vault.tree().stream().filter(v -> v.id().equals("tp1")).findFirst().orElseThrow();
        assertNotNull(n.pii());
        assertEquals("suspected", n.pii().status());
        assertTrue(n.pii().types().contains("phone"));
    }

    @Test void tree_pii_null_when_clean() {
        nodes.insert(new NodeRow("tp2", null, "note", "노트", 2, "clean", "2026-06-14T00:00:00", null, null));
        VaultNode n = vault.tree().stream().filter(v -> v.id().equals("tp2")).findFirst().orElseThrow();
        assertNull(n.pii());
    }
}
```

- [ ] **Step 3: VaultNode에 pii 필드 추가**

`backend/src/main/java/com/worknote/vault/VaultNode.java` 교체:
```java
package com.worknote.vault;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.worknote.pii.PiiInfo;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record VaultNode(
    String id, String type, String name, String title,   // folder→name, note→title (둘 중 하나만 non-null)
    Integer position, List<VaultNode> children,           // folder만 children
    List<String> tags, String updated, String content,    // note만
    PiiInfo pii                                            // note만(플래그 있을 때) — null이면 직렬화 생략
) {}
```

- [ ] **Step 4: VaultService 생성자 호출부 6곳 + assemble 주입**

`VaultService.java`에서 `new VaultNode(...)` 호출 6곳에 마지막 인자 추가:
- `create` note(라인 ~85): `..., toDate(updatedAt), content)` → `..., toDate(updatedAt), content, null)`
- `create` folder(라인 ~87): `..., null, null, null)` → `..., null, null, null, null)`
- `trashList` note(라인 ~172): 끝에 `, null)`
- `trashList` folder(라인 ~175): 끝에 `, null)`
- `assemble` note(라인 ~201): `tagsByNode...., toDate(row.updatedAt()), row.content())` → `..., row.content(), piiByNode.get(row.id()))`
- `assemble` folder(라인 ~205): `assemble(...), null, null, null)` → `assemble(...), null, null, null, null)`

`tree(Set<String> readable)`에서 piiByNode 맵 구성 후 assemble로 전달:
```java
    @Transactional(readOnly = true)
    public List<VaultNode> tree(Set<String> readable) {
        Map<String, List<NodeRow>> byParent = new LinkedHashMap<>();
        for (NodeRow row : mapper.findActive()) {
            if (readable != null && !readable.contains(row.id())) continue;
            byParent.computeIfAbsent(row.parentId(), k -> new ArrayList<>()).add(row);
        }
        Map<String, List<String>> tagsByNode = new LinkedHashMap<>();
        for (TagRow t : mapper.findAllTags()) {
            tagsByNode.computeIfAbsent(t.nodeId(), k -> new ArrayList<>()).add(t.tag());
        }
        Map<String, PiiInfo> piiByNode = new LinkedHashMap<>();
        for (PiiFlagRow f : piiMapper.activeFlags()) {
            piiByNode.put(f.nodeId(), new PiiInfo(f.status(),
                f.types().isEmpty() ? List.of() : Arrays.asList(f.types().split(","))));
        }
        return assemble(null, byParent, tagsByNode, piiByNode);
    }
```
`assemble` 시그니처에 `Map<String, PiiInfo> piiByNode` 추가하고 재귀 호출에도 전달. 생성자 주입에 `PiiMapper piiMapper` 추가:
```java
    private final PiiMapper piiMapper;
    public VaultService(NodeMapper mapper, AclMapper aclMapper, ShareLinkMapper shareLinks,
                        AttachmentService attachments, PiiMapper piiMapper, Clock clock) {
        ...
        this.piiMapper = piiMapper;
        this.clock = clock;
    }
```
import 추가: `com.worknote.pii.PiiInfo`, `com.worknote.pii.PiiFlagRow`, `com.worknote.pii.PiiMapper`, `java.util.Arrays`.

- [ ] **Step 5: 테스트 통과 + 회귀 확인**

Run: `cd backend && ./gradlew test --tests 'com.worknote.pii.TreePiiTest'`
Expected: PASS
Run: `cd backend && ./gradlew test`
Expected: 전부 GREEN (VaultNode 생성자 변경 호출부 모두 갱신됨).

- [ ] **Step 6: 커밋**
```bash
git add backend/src/main/java/com/worknote/pii/PiiInfo.java backend/src/main/java/com/worknote/vault/VaultNode.java backend/src/main/java/com/worknote/vault/VaultService.java backend/src/test/java/com/worknote/pii/TreePiiTest.java
git commit -m "feat: 트리 응답 VaultNode.pii 주입(사이드바·배너 초기 상태)"
```

---

### Task 6: VaultController.update가 evaluate 호출 + pii 반환

**Files:**
- Modify: `backend/src/main/java/com/worknote/vault/VaultController.java:58-64`
- Test: `backend/src/test/java/com/worknote/pii/PiiApiTest.java` (PATCH 반환 케이스)

- [ ] **Step 1: 실패 테스트 작성 (MockMvc, AttachmentApiTest 패턴)**

`backend/src/test/java/com/worknote/pii/PiiApiTest.java` (1차):
```java
package com.worknote.pii;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PiiApiTest {
    @Autowired MockMvc mvc;
    @Autowired NodeMapper nodes;

    @Test void patch_returns_pii_when_detected() throws Exception {
        nodes.insert(new NodeRow("pa1", null, "note", "n", 1, "", "2026-06-14T00:00:00", null, null));
        mvc.perform(patch("/api/nodes/pa1").contentType("application/json")
                .content("{\"content\":\"내 번호 010-1234-5678\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pii.status").value("suspected"))
            .andExpect(jsonPath("$.pii.types[0]").value("phone"));
    }

    @Test void patch_tags_only_no_pii_key() throws Exception {
        nodes.insert(new NodeRow("pa2", null, "note", "n", 2, "x", "2026-06-14T00:00:00", null, null));
        mvc.perform(patch("/api/nodes/pa2").contentType("application/json")
                .content("{\"tags\":[\"a\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pii").doesNotExist());
    }
}
```
> local 모드(테스트 기본)는 무인증이라 가드 통과. `guard.who(null)` = "local".

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew test --tests 'com.worknote.pii.PiiApiTest'`
Expected: FAIL (현재 PATCH는 204 void)

- [ ] **Step 3: 컨트롤러 update 교체**

`VaultController.java`에 `PiiService pii` 주입(생성자 인자 추가) 후 `update` 교체:
```java
    @PatchMapping("/nodes/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody UpdateNodeRequest body, HttpServletRequest req) {
        UserRow user = user(req);
        guard.requireEdit(user, id);
        svc.update(id, body.name(), body.content(), body.tags(), guard.who(user));
        Map<String, Object> resp = new HashMap<>();
        if (body.content() != null) {   // content 변경 시에만 재탐지
            PiiEval e = pii.evaluate(id, body.content());
            resp.put("pii", Map.of("status", e.status(), "types", e.types()));
        }
        return resp;   // tags-only면 {} → 프런트는 pii 미변경으로 취급
    }
```
- `@ResponseStatus(HttpStatus.NO_CONTENT)` 제거(이제 200 JSON).
- import 추가: `com.worknote.pii.PiiEval`, `com.worknote.pii.PiiService`, `java.util.HashMap`. (`java.util.Map`는 이미 import).
- 생성자: `public VaultController(VaultService svc, VaultGuard guard, AuditService audit, ExposureService exposure, PiiService pii)` + `this.pii = pii;` 필드 추가.

- [ ] **Step 4: 테스트 통과 + 회귀**

Run: `cd backend && ./gradlew test --tests 'com.worknote.pii.PiiApiTest'`
Expected: PASS
Run: `cd backend && ./gradlew test`
Expected: 전부 GREEN. (PATCH가 204→200 됐으니 기존 update 호출 테스트가 204를 단언하면 200으로 갱신 필요 — 있으면 함께 수정.)

- [ ] **Step 5: 커밋**
```bash
git add backend/src/main/java/com/worknote/vault/VaultController.java backend/src/test/java/com/worknote/pii/PiiApiTest.java
git commit -m "feat: PATCH 저장 시 PII 재탐지 후 응답에 pii 반환"
```

---

### Task 7: 알림·요청 조회 서비스 메서드 (notice/ack/list)

**Files:**
- Modify: `backend/src/main/java/com/worknote/pii/PiiService.java`
- Test: `backend/src/test/java/com/worknote/pii/PiiServiceTest.java` (추가)

- [ ] **Step 1: 실패 테스트 추가**

`PiiServiceTest.java`에 추가:
```java
    @Test void notice_dedup_then_ack() {
        note("n1");
        svc.evaluate("n1", "010-1234-5678");
        svc.notice("n1", "e9", "admin");      // recipient="e9"라고 가정(컨트롤러가 updated_by 전달)
        svc.notice("n1", "e9", "admin");      // 중복 → 신규 미생성
        assertEquals(1, svc.noticesFor("e9").size());
        svc.ack("e9", null);
        assertTrue(svc.noticesFor("e9").isEmpty());
    }

    @Test void approve_creates_notice_to_requester() {
        note("n2");
        svc.evaluate("n2", "010-1234-5678");
        svc.requestException("n2", "eReq", "오탐");
        svc.approveWithNotice("n2", "admin");
        assertEquals(1, svc.noticesFor("eReq").size());
        assertEquals("approved", svc.noticesFor("eReq").get(0).get("kind"));
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew test --tests 'com.worknote.pii.PiiServiceTest'`
Expected: FAIL (notice/noticesFor/ack/approveWithNotice 미존재)

- [ ] **Step 3: PiiService에 메서드 추가**

`PiiService.java`에 추가(기존 approve/reject는 유지하고, 알림까지 묶는 *WithNotice 래퍼 + 조회 추가):
```java
    /** 능동 알림(flagged) — recipient에게. 중복(미확인 동일 kind) 시 sent_at만 갱신. */
    @Transactional
    public void notice(String nodeId, String recipient, String adminEmp) {
        sendNotice(nodeId, recipient, "flagged", null, adminEmp);
    }

    /** 허용 + 요청자에게 approved 알림. */
    @Transactional
    public void approveWithNotice(String nodeId, String adminEmp) {
        PiiFlagRow cur = requireFlag(nodeId);
        approve(nodeId, adminEmp);
        if (cur.requestedBy() != null) sendNotice(nodeId, cur.requestedBy(), "approved", null, adminEmp);
    }

    /** 반려 + 요청자에게 rejected 알림(사유 포함). */
    @Transactional
    public void rejectWithNotice(String nodeId, String adminEmp, String reason) {
        PiiFlagRow cur = requireFlag(nodeId);
        reject(nodeId, adminEmp, reason);
        if (cur.requestedBy() != null) sendNotice(nodeId, cur.requestedBy(), "rejected", reason, adminEmp);
    }

    private void sendNotice(String nodeId, String recipient, String kind, String message, String adminEmp) {
        Long dup = mapper.findUnackedNoticeId(nodeId, recipient, kind);
        if (dup != null) { mapper.touchNotice(dup, message, now()); return; }
        mapper.insertNotice(new PiiNoticeRow(null, nodeId, recipient, kind, message, adminEmp, now(), null));
    }

    @Transactional(readOnly = true)
    public List<java.util.Map<String, Object>> noticesFor(String recipient) {
        return mapper.noticesFor(recipient);
    }

    @Transactional
    public void ack(String recipient, List<Long> ids) {
        mapper.ack(recipient, ids);
    }

    @Transactional(readOnly = true)
    public List<java.util.Map<String, Object>> adminList() { return mapper.adminList(); }

    @Transactional(readOnly = true)
    public List<java.util.Map<String, Object>> adminRequests() { return mapper.adminRequests(); }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests 'com.worknote.pii.PiiServiceTest'`
Expected: PASS (기존 6 + 신규 2 = 8)

- [ ] **Step 5: 커밋**
```bash
git add backend/src/main/java/com/worknote/pii/PiiService.java backend/src/test/java/com/worknote/pii/PiiServiceTest.java
git commit -m "feat: PII 알림 생성·중복방지·조회·ack + 결정 시 알림 발송"
```

---

### Task 8: PiiController (node/admin/me 엔드포인트)

**Files:**
- Create: `backend/src/main/java/com/worknote/pii/PiiController.java`
- Modify: `backend/src/main/java/com/worknote/vault/VaultService.java` (purge는 Task 9)
- Test: `backend/src/test/java/com/worknote/pii/PiiApiTest.java` (추가)

- [ ] **Step 1: 실패 테스트 추가**

`PiiApiTest.java`에 추가:
```java
    @org.springframework.beans.factory.annotation.Autowired PiiService pii;

    @Test void exception_request_then_admin_list_and_approve() throws Exception {
        nodes.insert(new NodeRow("pc1", null, "note", "노트제목", 5, "", "2026-06-14T00:00:00", null, null));
        pii.evaluate("pc1", "010-1234-5678");

        // 예외 요청
        mvc.perform(post("/api/nodes/pc1/pii/exception").contentType("application/json").content("{\"reason\":\"오탐\"}"))
            .andExpect(status().isNoContent());

        // 관리자 요청 대기 목록(local=관리자)
        mvc.perform(get("/api/admin/pii/requests"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].nodeId").value("pc1"))
            .andExpect(jsonPath("$[0].title").value("노트제목"));

        // 허용
        mvc.perform(post("/api/admin/pii/notes/pc1/approve"))
            .andExpect(status().isNoContent());
    }

    @Test void me_notices_and_ack() throws Exception {
        nodes.insert(new NodeRow("pc2", null, "note", "노트2", 6, "", "2026-06-14T00:00:00", null, null));
        pii.evaluate("pc2", "010-1234-5678");
        pii.notice("pc2", "local", "admin");   // local 세션 recipient="local"
        mvc.perform(get("/api/me/pii-notices"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].noteTitle").value("노트2"));
        mvc.perform(post("/api/me/pii-notices/ack").contentType("application/json").content("{}"))
            .andExpect(status().isNoContent());
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew test --tests 'com.worknote.pii.PiiApiTest'`
Expected: FAIL (PiiController 미존재 → 404)

- [ ] **Step 3: PiiController 구현**

`backend/src/main/java/com/worknote/pii/PiiController.java`:
```java
package com.worknote.pii;

import com.worknote.admin.AdminGuard;
import com.worknote.audit.AuditService;
import com.worknote.auth.AuthFilter;
import com.worknote.auth.UserRow;
import com.worknote.vault.VaultGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** PII 노트 표시·예외 요청(노트 write) / 관리자 점검·결정(admin) / 내 알림(me). */
@RestController
@RequestMapping("/api")
public class PiiController {

    private final PiiService pii;
    private final VaultGuard vaultGuard;
    private final AdminGuard adminGuard;
    private final AuditService audit;

    public PiiController(PiiService pii, VaultGuard vaultGuard, AdminGuard adminGuard, AuditService audit) {
        this.pii = pii;
        this.vaultGuard = vaultGuard;
        this.adminGuard = adminGuard;
        this.audit = audit;
    }

    private static UserRow user(HttpServletRequest req) {
        return (UserRow) req.getAttribute(AuthFilter.CURRENT_USER);
    }

    // ---- 노트 소유자: 예외 요청 ----
    @PostMapping("/nodes/{id}/pii/exception")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void requestException(@PathVariable String id, @RequestBody(required = false) Map<String, String> body,
                                 HttpServletRequest req) {
        UserRow u = user(req);
        vaultGuard.requireEdit(u, id);
        String reason = body == null ? null : body.get("reason");
        pii.requestException(id, vaultGuard.who(u), reason);
        audit.log(u, "pii.request", id, req.getRemoteAddr());
    }

    // ---- 관리자: 목록·요청·결정·능동 알림 ----
    @GetMapping("/admin/pii/notes")
    public List<Map<String, Object>> adminNotes(HttpServletRequest req) {
        adminGuard.requireAdmin(user(req));
        return pii.adminList();
    }

    @GetMapping("/admin/pii/requests")
    public List<Map<String, Object>> adminRequests(HttpServletRequest req) {
        adminGuard.requireAdmin(user(req));
        return pii.adminRequests();
    }

    @PostMapping("/admin/pii/notes/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@PathVariable String id, HttpServletRequest req) {
        UserRow u = user(req);
        adminGuard.requireAdmin(u);
        pii.approveWithNotice(id, vaultGuard.who(u));
        audit.log(u, "pii.approve", id, req.getRemoteAddr());
    }

    @PostMapping("/admin/pii/notes/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@PathVariable String id, @RequestBody(required = false) Map<String, String> body,
                       HttpServletRequest req) {
        UserRow u = user(req);
        adminGuard.requireAdmin(u);
        pii.rejectWithNotice(id, vaultGuard.who(u), body == null ? null : body.get("reason"));
        audit.log(u, "pii.reject", id, req.getRemoteAddr());
    }

    @PostMapping("/admin/pii/notes/{id}/notice")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void notice(@PathVariable String id, HttpServletRequest req) {
        UserRow u = user(req);
        adminGuard.requireAdmin(u);
        String recipient = pii.recipientForNotice(id);   // updated_by, 없으면 예외
        pii.notice(id, recipient, vaultGuard.who(u));
        audit.log(u, "pii.notice", id, req.getRemoteAddr());
    }

    // ---- 로그인 사용자: 내 알림 ----
    @GetMapping("/me/pii-notices")
    public List<Map<String, Object>> myNotices(HttpServletRequest req) {
        UserRow u = user(req);
        return pii.noticesFor(vaultGuard.who(u));
    }

    @PostMapping("/me/pii-notices/ack")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ack(@RequestBody(required = false) Map<String, List<Number>> body, HttpServletRequest req) {
        UserRow u = user(req);
        List<Long> ids = null;
        if (body != null && body.get("ids") != null) {
            ids = body.get("ids").stream().map(Number::longValue).toList();
        }
        pii.ack(vaultGuard.who(u), ids);
    }
}
```
> `pii.recipientForNotice(id)`는 `notice` 능동 알림 수신자(updated_by)를 구한다. PiiService에 추가:
```java
    /** 능동 알림 수신자 = 최종 수정자(node.updated_by). 없으면 400. */
    @Transactional(readOnly = true)
    public String recipientForNotice(String nodeId) {
        String emp = nodeMapper.findUpdatedBy(nodeId);
        if (emp == null) throw com.worknote.vault.VaultException.invalid("최종 수정자가 없어 알림을 보낼 수 없습니다");
        return emp;
    }
```
PiiService 생성자에 `NodeMapper nodeMapper`를 추가 주입(필드 + 생성자 인자). import `com.worknote.vault.NodeMapper`.

- [ ] **Step 4: 테스트 통과 + 회귀**

Run: `cd backend && ./gradlew test --tests 'com.worknote.pii.PiiApiTest'`
Expected: PASS
Run: `cd backend && ./gradlew test`
Expected: 전부 GREEN.

- [ ] **Step 5: 커밋**
```bash
git add backend/src/main/java/com/worknote/pii/PiiController.java backend/src/main/java/com/worknote/pii/PiiService.java backend/src/test/java/com/worknote/pii/PiiApiTest.java
git commit -m "feat: PiiController(예외요청·관리자 점검/결정·내 알림) 엔드포인트"
```

---

### Task 9: purge cascade (pii_flag/pii_notice 정리)

**Files:**
- Modify: `backend/src/main/java/com/worknote/vault/VaultService.java:142-158` (purge)
- Test: `backend/src/test/java/com/worknote/pii/PiiPurgeTest.java`

- [ ] **Step 1: 실패 테스트 작성**

`backend/src/test/java/com/worknote/pii/PiiPurgeTest.java`:
```java
package com.worknote.pii;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import com.worknote.vault.VaultService;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class PiiPurgeTest {
    @Autowired VaultService vault;
    @Autowired PiiService pii;
    @Autowired PiiMapper piiMapper;
    @Autowired NodeMapper nodes;

    @Test void purge_removes_flag_and_notice() {
        nodes.insert(new NodeRow("pp1", null, "note", "n", 1, "010-1234-5678", "2026-06-14T00:00:00", "2026-06-14T00:00:00", "local"));
        pii.evaluate("pp1", "010-1234-5678");
        pii.notice("pp1", "local", "admin");
        vault.purge("pp1");
        assertNull(piiMapper.findFlag("pp1"));
        assertTrue(piiMapper.noticesFor("local").isEmpty());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew test --tests 'com.worknote.pii.PiiPurgeTest'`
Expected: FAIL (잔여 flag/notice)

- [ ] **Step 3: purge에 cascade 추가**

`VaultService.purge`의 종속행 삭제 블록에 추가(이미 주입된 `piiMapper` 사용):
```java
        shareLinks.deleteIn(ids);    // 공유 링크도 영구 삭제
        piiMapper.deleteFlagsIn(ids);     // PII 플래그 정리
        piiMapper.deleteNoticesIn(ids);   // PII 알림 정리
        mapper.purgeSubtree(id);
```

- [ ] **Step 4: 테스트 통과 + 회귀**

Run: `cd backend && ./gradlew test --tests 'com.worknote.pii.PiiPurgeTest'`
Expected: PASS
Run: `cd backend && ./gradlew test`
Expected: 전부 GREEN (백엔드 Phase 1 완료).

- [ ] **Step 5: 커밋**
```bash
git add backend/src/main/java/com/worknote/vault/VaultService.java backend/src/test/java/com/worknote/pii/PiiPurgeTest.java
git commit -m "feat: 노트 purge 시 PII 플래그·알림 cascade 정리"
```

---

# Phase 2 — 프런트엔드

### Task 10: 타입 + API 클라이언트 (NotePii, PiiApi, admin)

**Files:**
- Modify: `frontend/src/types.ts`
- Create: `frontend/src/lib/pii.ts`
- Modify: `frontend/src/storage/VaultApi.ts`
- Create: `frontend/src/storage/PiiApi.ts`
- Modify: `frontend/src/admin/api.ts`
- Test: `frontend/src/lib/pii.test.ts`

- [ ] **Step 1: 실패 테스트 작성**

`frontend/src/lib/pii.test.ts`:
```ts
import { describe, it, expect } from "vitest";
import { piiWarns, piiTypeLabel } from "./pii";

describe("piiWarns", () => {
  it("suspected/requested/rejected는 경고", () => {
    expect(piiWarns({ status: "suspected", types: [] })).toBe(true);
    expect(piiWarns({ status: "requested", types: [] })).toBe(true);
    expect(piiWarns({ status: "rejected", types: [] })).toBe(true);
  });
  it("exempted/none/null은 비경고", () => {
    expect(piiWarns({ status: "exempted", types: [] })).toBe(false);
    expect(piiWarns({ status: "none", types: [] })).toBe(false);
    expect(piiWarns(null)).toBe(false);
    expect(piiWarns(undefined)).toBe(false);
  });
});

describe("piiTypeLabel", () => {
  it("유형 코드 → 한글 라벨", () => {
    expect(piiTypeLabel("rrn")).toBe("주민등록번호");
    expect(piiTypeLabel("phone")).toBe("휴대폰번호");
    expect(piiTypeLabel("unknown")).toBe("unknown");
  });
});
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd frontend && pnpm test -- lib/pii.test.ts`
Expected: FAIL (모듈 없음)

- [ ] **Step 3: 타입·헬퍼 구현**

`frontend/src/types.ts`의 `NoteNode`에 `pii` 추가 + 타입 export:
```ts
export type PiiStatus = "none" | "suspected" | "requested" | "exempted" | "rejected";
export interface NotePii { status: PiiStatus; types: string[]; }

export interface NoteNode {
  id: string;
  type: "note";
  title: string;
  tags: string[];
  updated: string; // YYYY-MM-DD
  content: string;
  pii?: NotePii | null;
}
```

`frontend/src/lib/pii.ts`:
```ts
import type { NotePii } from "../types";

/** 경고를 띄워야 하는 상태(미해결). exempted/none/없음은 비경고. */
export function piiWarns(pii: NotePii | null | undefined): boolean {
  return !!pii && (pii.status === "suspected" || pii.status === "requested" || pii.status === "rejected");
}

const LABELS: Record<string, string> = {
  rrn: "주민등록번호", phone: "휴대폰번호", email: "이메일", card: "신용카드번호",
  biz: "사업자등록번호", passport: "여권번호", driver: "운전면허번호",
};
export function piiTypeLabel(code: string): string {
  return LABELS[code] ?? code;
}
```

- [ ] **Step 4: VaultApi.update 반환 타입 변경**

`frontend/src/storage/VaultApi.ts`:
```ts
import type { VaultTree, NotePii } from "../types";
...
  update: (id: string, patch: { name?: string; content?: string; tags?: string[] }) =>
    req<{ pii?: NotePii }>(`/nodes/${id}`, { method: "PATCH", body: JSON.stringify(patch) }),
```
> `req`는 200 JSON을 파싱해 `{}` 또는 `{pii}`를 반환. tags-only PATCH는 `{}`.

- [ ] **Step 5: PiiApi(예외 요청·내 알림) 작성**

`frontend/src/storage/PiiApi.ts`:
```ts
/* PiiApi — 노트 예외 요청 + 내 PII 알림. 공유 fetch 코어(req) 사용. */
import { req } from "../api/http";

export interface PiiNotice { id: number; kind: "flagged" | "approved" | "rejected"; message: string | null; noteId: string; noteTitle: string; }

export const PiiApi = {
  requestException: (nodeId: string, reason?: string) =>
    req<void>(`/nodes/${encodeURIComponent(nodeId)}/pii/exception`, { method: "POST", body: JSON.stringify({ reason: reason ?? null }) }),
  myNotices: () => req<PiiNotice[]>("/me/pii-notices"),
  ackNotices: (ids?: number[]) =>
    req<void>("/me/pii-notices/ack", { method: "POST", body: JSON.stringify(ids ? { ids } : {}) }),
};
```

- [ ] **Step 6: admin/api.ts에 PII 관리자 메서드 추가**

`frontend/src/admin/api.ts`의 인터페이스·AdminApi에 추가:
```ts
export interface ApiPiiNote { nodeId: string; title: string; updatedBy: string | null; types: string; status: string; detectedAt: string; }
export interface ApiPiiRequest { nodeId: string; title: string; updatedBy: string | null; types: string; requestedBy: string | null; requestedAt: string | null; requestReason: string | null; }
```
AdminApi 객체에 추가:
```ts
  piiNotes: () => req<ApiPiiNote[]>("/admin/pii/notes"),
  piiRequests: () => req<ApiPiiRequest[]>("/admin/pii/requests"),
  piiApprove: (nodeId: string) => req<void>(`/admin/pii/notes/${encodeURIComponent(nodeId)}/approve`, { method: "POST" }),
  piiReject: (nodeId: string, reason: string) => req<void>(`/admin/pii/notes/${encodeURIComponent(nodeId)}/reject`, { method: "POST", body: JSON.stringify({ reason }) }),
  piiNotice: (nodeId: string) => req<void>(`/admin/pii/notes/${encodeURIComponent(nodeId)}/notice`, { method: "POST" }),
```

- [ ] **Step 7: 테스트 통과 + 빌드**

Run: `cd frontend && pnpm test -- lib/pii.test.ts`
Expected: PASS
Run: `cd frontend && pnpm build`
Expected: tsc + vite 성공.

- [ ] **Step 8: 커밋**
```bash
git add frontend/src/types.ts frontend/src/lib/pii.ts frontend/src/lib/pii.test.ts frontend/src/storage/VaultApi.ts frontend/src/storage/PiiApi.ts frontend/src/admin/api.ts
git commit -m "feat: PII 프런트 타입·API 클라이언트(NotePii·PiiApi·admin)"
```

---

### Task 11: setNotePii 액션 + PATCH 응답 라이브 반영

**Files:**
- Modify: `frontend/src/state/vaultReducer.ts`
- Modify: `frontend/src/state/useVault.ts:77-101`
- Modify: `frontend/src/state/useVaultSync.ts`
- Test: `frontend/src/state/vaultReducer.test.ts`

- [ ] **Step 1: 실패 테스트 작성**

`frontend/src/state/vaultReducer.test.ts` (없으면 생성):
```ts
import { describe, it, expect } from "vitest";
import { vaultReducer } from "./vaultReducer";
import type { VaultTree } from "../types";

const base: VaultTree = [{ id: "n1", type: "note", title: "T", tags: [], updated: "2026-01-01", content: "c" }];

describe("setNotePii", () => {
  it("pii만 설정하고 updated는 건드리지 않는다", () => {
    const out = vaultReducer(base, { type: "setNotePii", id: "n1", pii: { status: "suspected", types: ["phone"] } });
    const note = out[0] as any;
    expect(note.pii.status).toBe("suspected");
    expect(note.updated).toBe("2026-01-01"); // 디바운스 PATCH 유발 안 함 — updated 불변
  });
});
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd frontend && pnpm test -- state/vaultReducer.test.ts`
Expected: FAIL (setNotePii 미존재)

- [ ] **Step 3: reducer 액션 추가**

`frontend/src/state/vaultReducer.ts`:
```ts
import type { VaultTree, VaultNode, NoteNode, NotePii } from "../types";
...
export type VaultAction =
  | { type: "toggle"; id: string }
  | ... (기존 유지)
  | { type: "updateNote"; id: string; patch: Partial<NoteNode> }
  | { type: "setNotePii"; id: string; pii: NotePii | null }
  | { type: "replace"; tree: VaultTree };
```
switch에 case 추가(updateNote와 달리 updated 미변경):
```ts
    case "setNotePii":
      return updateNode(tree, a.id, (n) => {
        if (n.type === "note") (n as NoteNode).pii = a.pii;
      });
```

- [ ] **Step 4: useVault actions에 setNotePii 추가**

`frontend/src/state/useVault.ts`의 `actions` 객체에 추가:
```ts
    setNotePii: (id: string, pii: import("../types").NotePii | null) => dispatch({ type: "setNotePii", id, pii }),
```

- [ ] **Step 5: useVaultSync — 패스스루 + PATCH 응답 반영**

`frontend/src/state/useVaultSync.ts` 변경:

(a) `syncAction`의 update case가 결과를 반환하도록(반환 타입 `Promise<unknown>`):
```ts
export async function syncAction(api: VaultApiType, op: SyncOp): Promise<unknown> {
  switch (op.kind) {
    case "create": await api.create(op.node); return;
    case "rename": await api.update(op.id, { name: op.name }); return;
    case "update": {
      const patch: UpdatePatch = {};
      if (op.name !== undefined) patch.name = op.name;
      if (op.content !== undefined) patch.content = op.content;
      if (op.tags !== undefined) patch.tags = op.tags;
      return await api.update(op.id, patch);   // { pii? } 반환
    }
    case "remove": await api.trash(op.id); return;
    case "move": await api.move(op.id, op.parentId); return;
  }
}
```

(b) `fire`가 onSuccess에 결과를 전달:
```ts
  const fire = (op: SyncOp, onSuccess?: (res?: unknown) => void) => {
    syncAction(VaultApi, op)
      .then((res) => onSuccess?.(res))
      .catch((e: unknown) => { /* 기존 동일 */ });
  };
```

(c) 디바운스 flush의 onSuccess에서 pii 반영(`updateNote` 케이스 내부 setTimeout):
```ts
        const timer = setTimeout(() => {
          pendingRef.current.delete(id);
          fire(buildUpdateOp(id, merged), (res) => {
            clearPending(id);
            const pii = (res as { pii?: import("../types").NotePii })?.pii;
            if (pii !== undefined) actionsRef.current.setNotePii(id, pii.status === "none" ? null : pii);
          });
        }, PATCH_DEBOUNCE);
```
그리고 언마운트 flush의 `fire(buildUpdateOp(id, p.patch), () => clearPending(id))`도 동일 콜백으로 교체(동일 코드).

(d) `synced` 객체에 `setNotePii` 패스스루(서버 호출 없음):
```ts
      setNotePii: (id, pii) => actionsRef.current.setNotePii(id, pii),
```

- [ ] **Step 6: 테스트 통과 + 빌드**

Run: `cd frontend && pnpm test -- state/vaultReducer.test.ts`
Expected: PASS
Run: `cd frontend && pnpm build`
Expected: 성공. (VaultActions 타입에 setNotePii 포함됨.)

- [ ] **Step 7: 커밋**
```bash
git add frontend/src/state/vaultReducer.ts frontend/src/state/vaultReducer.test.ts frontend/src/state/useVault.ts frontend/src/state/useVaultSync.ts
git commit -m "feat: setNotePii 액션 + PATCH 응답으로 PII 상태 라이브 반영(디바운스 비유발)"
```

---

### Task 12: 사이드바 경고 아이콘

**Files:**
- Modify: `frontend/src/components/Sidebar.tsx:69-70`
- Test: 수동(빌드 + 브라우저). 단위 테스트는 트리 렌더 구조상 생략(piiWarns는 Task 10에서 커버).

- [ ] **Step 1: 아이콘 분기 적용**

`frontend/src/components/Sidebar.tsx`의 노트/폴더 아이콘 라인(69-70)을 교체. 상단에 import 추가: `import { piiWarns } from "../lib/pii";`
```tsx
    React.createElement("span", { className: "ic" + (!isFolder && piiWarns((node as NoteNode).pii) ? " pii-warn" : "") },
      React.createElement(Icon, {
        name: isFolder
          ? ((node as { open?: boolean }).open ? "folderOpen" : "folder")
          : (piiWarns((node as NoteNode).pii) ? "alert" : "fileLines"),
      })),
```
> `NoteNode`는 이미 import됨(파일 상단 `(node as NoteNode)` 사용 중). `pii-warn` 클래스는 색 강조용(아래 CSS).

- [ ] **Step 2: CSS — 경고 색**

`frontend/src/styles/app.css`에 추가:
```css
.row .ic.pii-warn { color: var(--danger, #d9534f); }
```
> 기존 토큰에 `--danger`가 없으면 `#d9534f` 폴백 그대로 사용.

- [ ] **Step 3: 빌드 + 수동 확인**

Run: `cd frontend && pnpm build`
Expected: 성공. 서버 모드에서 PII 노트의 사이드바 아이콘이 경고(삼각형+!)로 표시되는지 브라우저로 확인.

- [ ] **Step 4: 커밋**
```bash
git add frontend/src/components/Sidebar.tsx frontend/src/styles/app.css
git commit -m "feat: 사이드바 PII 노트 경고 아이콘(alert)"
```

---

### Task 13: 에디터 배너 + 예외 요청 버튼

**Files:**
- Modify: `frontend/src/components/Editor.tsx` (제목 밑 배너 + 상태별 버튼)
- Test: 수동(빌드 + 브라우저).

- [ ] **Step 1: 배너 컴포넌트 삽입**

`frontend/src/components/Editor.tsx` 상단 import 추가:
```tsx
import { piiWarns, piiTypeLabel } from "../lib/pii";
import { PiiApi } from "../storage/PiiApi";
```
`Editor` 컴포넌트 내부, `return createElement("div", { className: "doc" ... }`의 `title-rule`(라인 210) 직후에 배너를 추가. 배너는 `props.canUpload`(서버 모드)일 때만 예외 요청 버튼을 노출한다. 다음 헬퍼와 엘리먼트를 추가:
```tsx
  const pii = note.pii;
  const requestException = async () => {
    try {
      await PiiApi.requestException(note.id);
      props.onSetPii?.(note.id, { status: "requested", types: pii?.types || [] });
      props.toast("개인정보 예외를 요청했습니다", "check");
    } catch (e) {
      props.toast(e instanceof Error ? e.message : "요청 실패");
    }
  };
  const piiBanner = piiWarns(pii)
    ? createElement("div", { className: "pii-banner " + pii!.status },
        createElement("span", { className: "pii-ic" }, createElement(Icon, { name: "alert" })),
        createElement("span", { className: "pii-msg" },
          pii!.status === "suspected" ? "개인정보 기입 확인"
            : pii!.status === "requested" ? "개인정보 예외 검토 중"
            : "개인정보 예외 반려됨" + (pii!.decisionReason ? " — " + pii!.decisionReason : "")),
        props.canUpload && pii!.status === "suspected" &&
          createElement("button", { className: "pii-act", onClick: () => void requestException() }, "예외 요청"),
        props.canUpload && pii!.status === "rejected" &&
          createElement("button", { className: "pii-act", onClick: () => void requestException() }, "다시 요청"))
    : null;
```
> 주: `pii.decisionReason`은 트리 응답에 포함하지 않으므로(스펙: 사유는 알림으로 전달) 배너 문구는 "개인정보 예외 반려됨"까지만 표시하고 `decisionReason` 부분은 생략한다. 위 코드에서 `+ (pii!.decisionReason ? ...)`은 제거하고 `"개인정보 예외 반려됨"` 고정으로 둘 것.

수정 후 반려 문구:
```tsx
            : "개인정보 예외 반려됨"),
```

`title-rule` 직후에 `piiBanner`를 렌더 트리에 삽입:
```tsx
    createElement("div", { className: "title-rule" }),
    piiBanner,
    createElement("div", { className: "tags-row" }, ...),
```

- [ ] **Step 2: Editor props에 onSetPii 추가 + App 배선**

`Editor.tsx`의 `EditorProps`(파일 상단 인터페이스)에 추가:
```tsx
  onSetPii?: (id: string, pii: import("../types").NotePii) => void;
```
`frontend/src/App.tsx`의 Editor 생성부(라인 327-333)에 prop 전달:
```tsx
              toast, canUpload: storageMode === "http",
              onSetPii: (id, pii) => actions.setNotePii(id, pii),
```

- [ ] **Step 3: 배너 CSS**

`frontend/src/styles/app.css`에 추가:
```css
.pii-banner { display: flex; align-items: center; gap: 8px; margin: 10px 0 4px; padding: 8px 12px; border-radius: 8px; font-size: 13px; }
.pii-banner .pii-ic { display: grid; place-items: center; }
.pii-banner .pii-msg { font-weight: 600; }
.pii-banner.suspected { background: var(--bg-sunken); color: var(--ink); border: 1px solid var(--border); }
.pii-banner.suspected .pii-ic { color: #d9534f; }
.pii-banner.requested { background: var(--bg-sunken); color: var(--text-2); border: 1px solid var(--border); }
.pii-banner.rejected { background: rgba(217,83,79,.10); color: #c0392b; border: 1px solid rgba(217,83,79,.35); }
.pii-act { margin-left: auto; padding: 4px 10px; font-size: 12px; border-radius: 6px; border: 1px solid var(--border); background: var(--bg-elev); cursor: pointer; }
.pii-act:hover { background: var(--bg-active); }
```

- [ ] **Step 4: 빌드 + 수동 확인**

Run: `cd frontend && pnpm build`
Expected: 성공. 브라우저: 주민번호/휴대폰 입력→1.5초 후 "개인정보 기입 확인" 배너+[예외 요청] 출현, 클릭→"예외 검토 중"으로 전환.

- [ ] **Step 5: 커밋**
```bash
git add frontend/src/components/Editor.tsx frontend/src/App.tsx frontend/src/styles/app.css
git commit -m "feat: 에디터 PII 배너(상태별) + 예외 요청/다시 요청 버튼"
```

---

### Task 14: 관리자 "개인정보 점검" 스크린

**Files:**
- Create: `frontend/src/admin/screens/Pii.tsx`
- Modify: `frontend/src/admin/AdminApp.tsx` (NAV/TITLES/screenMap/import)
- Test: 수동(빌드 + 브라우저).

- [ ] **Step 1: 스크린 작성**

`frontend/src/admin/screens/Pii.tsx`:
```tsx
/* Admin screen: 개인정보 점검 — 예외 요청 대기 + 전체 플래그 노트 */
import React from "react";
import { AdminApi, type ApiPiiNote, type ApiPiiRequest } from "../api";
import { ApiError } from "../../api/http";
import { SecHead, Empty, Modal } from "../common";
import { Icon } from "../../components/Icon";
import { piiTypeLabel } from "../../lib/pii";

const { useState, useEffect, useCallback } = React;
const h = React.createElement;

const typeChips = (csv: string) =>
  (csv ? csv.split(",") : []).map((t) => h("span", { key: t, className: "chip" }, piiTypeLabel(t)));

export function Pii({ toast }: { toast: (msg: string, icon?: string) => void }) {
  const [reqs, setReqs] = useState<ApiPiiRequest[]>([]);
  const [notes, setNotes] = useState<ApiPiiNote[]>([]);
  const [reject, setReject] = useState<{ nodeId: string; title: string } | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [r, n] = await Promise.all([AdminApi.piiRequests(), AdminApi.piiNotes()]);
      setReqs(r); setNotes(n);
    } catch (e) { toast(e instanceof ApiError ? e.message : "불러오기 실패"); }
  }, [toast]);
  useEffect(() => { void load(); }, [load]);

  const approve = async (nodeId: string) => {
    setBusy(nodeId);
    try { await AdminApi.piiApprove(nodeId); await load(); toast("예외를 허용했습니다", "check"); }
    catch (e) { toast(e instanceof ApiError ? e.message : "실패"); }
    finally { setBusy(null); }
  };
  const doReject = async () => {
    const { nodeId } = reject!; setReject(null);
    setBusy(nodeId);
    try { await AdminApi.piiReject(nodeId, reason); setReason(""); await load(); toast("예외를 반려했습니다", "ban"); }
    catch (e) { toast(e instanceof ApiError ? e.message : "실패"); }
    finally { setBusy(null); }
  };
  const notify = async (nodeId: string) => {
    setBusy(nodeId);
    try { await AdminApi.piiNotice(nodeId); toast("최종 수정자에게 알림을 보냈습니다", "check"); }
    catch (e) { toast(e instanceof ApiError ? e.message : "실패"); }
    finally { setBusy(null); }
  };

  return h("div", { className: "apage" },
    h(SecHead, { title: "예외 요청 대기", hint: "사용자가 올린 개인정보 탐지 예외 요청을 검토합니다" }),
    reqs.length === 0
      ? h(Empty, { icon: "userCheck", title: "대기 중인 예외 요청이 없습니다", desc: "요청이 들어오면 이곳에 표시됩니다." })
      : h("div", { className: "table-wrap" }, h("table", { className: "atable" },
          h("thead", null, h("tr", null,
            h("th", null, "노트"), h("th", null, "최종 수정자"), h("th", null, "탐지 유형"),
            h("th", null, "사유"), h("th", { className: "right" }, "처리"))),
          h("tbody", null, reqs.map((r) => h("tr", { key: r.nodeId },
            h("td", null, r.title),
            h("td", { className: "mono" }, r.updatedBy ?? "—"),
            h("td", null, h("div", { className: "chips" }, typeChips(r.types))),
            h("td", null, r.requestReason || "—"),
            h("td", { className: "right" }, h("div", { className: "actions" },
              h("button", { className: "btn sm primary", disabled: busy === r.nodeId, onClick: () => void approve(r.nodeId) }, "허용"),
              h("button", { className: "btn sm danger", disabled: busy === r.nodeId, onClick: () => setReject({ nodeId: r.nodeId, title: r.title }) }, "반려")))))))),

    h("div", { style: { height: 22 } }),
    h(SecHead, { title: "전체 개인정보 노트", hint: "탐지된 모든 노트(허용 제외). 능동 알림 발송 가능" }),
    notes.filter((n) => n.status !== "exempted").length === 0
      ? h(Empty, { icon: "shield", title: "표시할 노트가 없습니다", desc: "탐지된 노트가 이곳에 나열됩니다." })
      : h("div", { className: "table-wrap" }, h("table", { className: "atable" },
          h("thead", null, h("tr", null,
            h("th", null, "노트"), h("th", null, "최종 수정자"), h("th", null, "탐지 유형"),
            h("th", null, "상태"), h("th", null, "탐지 시각"), h("th", { className: "right" }, "알림"))),
          h("tbody", null, notes.filter((n) => n.status !== "exempted").map((n) => h("tr", { key: n.nodeId },
            h("td", null, n.title),
            h("td", { className: "mono" }, n.updatedBy ?? "—"),
            h("td", null, h("div", { className: "chips" }, typeChips(n.types))),
            h("td", null, n.status),
            h("td", { className: "mono" }, n.detectedAt?.slice(0, 16).replace("T", " ")),
            h("td", { className: "right" },
              h("button", { className: "btn sm", disabled: busy === n.nodeId || !n.updatedBy, onClick: () => void notify(n.nodeId) }, "알림 보내기"))))))),

    reject && h(Modal, {
      icon: "ban", iconWarn: true, title: "예외 반려", confirmLabel: "반려", confirmDanger: true,
      onConfirm: () => { void doReject(); }, onClose: () => { setReject(null); setReason(""); },
    },
      h("div", null,
        h("p", { style: { marginBottom: 8 } }, h("b", null, reject.title), " 의 예외 요청을 반려합니다. 사유를 남기면 요청자에게 전달됩니다."),
        h("input", { className: "inp", placeholder: "반려 사유(선택)", value: reason,
          onChange: (e: React.ChangeEvent<HTMLInputElement>) => setReason(e.target.value) })))
  );
}
```
> `SecHead`/`Empty`/`Modal`은 `admin/common`에서 제공(다른 스크린과 동일). `chip`/`chips`/`inp` 클래스는 공용 스타일 가정 — 없으면 Step 3에서 최소 CSS 추가.

- [ ] **Step 2: AdminApp에 등록**

`frontend/src/admin/AdminApp.tsx`:
- import 추가: `import { Pii } from "./screens/Pii";`
- `NAV` 배열에 추가(audit 앞이 자연스러움): `{ id: "pii", label: "개인정보 점검", icon: "alert" },`
- `TITLES`에 추가: `pii: ["개인정보 점검", "PII 탐지 노트·예외 요청 처리"],`
- `screenMap`에 추가: `pii: Pii,`

- [ ] **Step 3: 최소 CSS(필요 시)**

`frontend/src/styles/admin.css`(또는 admin 스타일 파일)에 chip 스타일이 없으면 추가:
```css
.chips { display: flex; flex-wrap: wrap; gap: 4px; }
.chip { font-size: 11px; padding: 2px 7px; border-radius: 999px; background: var(--bg-sunken); border: 1px solid var(--border); color: var(--text-2); }
```
> 이미 chip 스타일이 있으면 이 단계 생략.

- [ ] **Step 4: 빌드 + 수동 확인**

Run: `cd frontend && pnpm build`
Expected: 성공. 관리자 페이지 좌측 nav에 "개인정보 점검" 출현, 요청 대기/전체 목록 표시, 허용/반려/알림 동작.

- [ ] **Step 5: 커밋**
```bash
git add frontend/src/admin/screens/Pii.tsx frontend/src/admin/AdminApp.tsx frontend/src/styles/admin.css
git commit -m "feat: 관리자 개인정보 점검 스크린(예외 요청 처리·전체 목록·알림)"
```

---

### Task 15: 로그인 팝업(PiiNoticeModal)

**Files:**
- Create: `frontend/src/components/PiiNoticeModal.tsx`
- Modify: `frontend/src/App.tsx` (overlays에 마운트)
- Test: 수동(빌드 + 브라우저).

- [ ] **Step 1: 팝업 컴포넌트 작성**

`frontend/src/components/PiiNoticeModal.tsx`:
```tsx
/* PiiNoticeModal — 로그인 직후 미확인 PII 알림을 팝업으로 표시. 확인 시 ack. */
import { useState, useEffect, createElement } from "react";
import { PiiApi, type PiiNotice } from "../storage/PiiApi";
import { Icon } from "./Icon";

const KIND_LABEL: Record<string, string> = {
  flagged: "개인정보가 감지되었습니다",
  approved: "예외 요청이 허용되었습니다",
  rejected: "예외 요청이 반려되었습니다",
};

export function PiiNoticeModal() {
  const [notices, setNotices] = useState<PiiNotice[] | null>(null);

  useEffect(() => {
    let alive = true;
    PiiApi.myNotices().then((n) => { if (alive && n.length) setNotices(n); }).catch(() => {});
    return () => { alive = false; };
  }, []);

  if (!notices || notices.length === 0) return null;

  const close = () => {
    void PiiApi.ackNotices().catch(() => {});
    setNotices(null);
  };

  // kind별 그룹 표시
  const groups: Record<string, PiiNotice[]> = {};
  for (const n of notices) (groups[n.kind] ||= []).push(n);

  return createElement("div", { className: "modal-backdrop", onClick: close },
    createElement("div", { className: "modal pii-notice-modal", onClick: (e: React.MouseEvent) => e.stopPropagation() },
      createElement("div", { className: "modal-head" },
        createElement("span", { className: "pii-ic", style: { color: "#d9534f" } }, createElement(Icon, { name: "alert" })),
        createElement("h3", null, "개인정보 알림")),
      createElement("div", { className: "modal-body" },
        Object.entries(groups).map(([kind, items]) =>
          createElement("div", { key: kind, className: "pii-notice-group" },
            createElement("div", { className: "pii-notice-kind" }, KIND_LABEL[kind] || kind),
            createElement("ul", { className: "pii-notice-list" },
              items.map((it) =>
                createElement("li", { key: it.id },
                  // 제목은 텍스트 노드로만 — XSS 하드닝(자동 이스케이프)
                  createElement("span", { className: "pii-notice-title" }, it.noteTitle || "(제목 없음)"),
                  it.message ? createElement("span", { className: "pii-notice-reason" }, " — " + it.message) : null)))))),
      createElement("div", { className: "modal-foot" },
        createElement("button", { className: "btn primary", onClick: close }, "확인"))));
}
```
> `createElement`로 텍스트를 자식에 넣으면 React가 자동 이스케이프하므로 제목/사유 XSS 안전. `modal-backdrop`/`modal`/`btn` 등은 기존 모달 스타일 재사용(ShareModal 등과 동일 클래스).

- [ ] **Step 2: App overlays에 마운트**

`frontend/src/App.tsx`:
- import 추가: `import { PiiNoticeModal } from "./components/PiiNoticeModal";`
- overlays 영역(라인 346~, `searchOpen && ...` 인근)에 조건부 마운트 추가:
```tsx
    storageMode === "http" && me != null && createElement(PiiNoticeModal, { key: "pii-notice-" + me.emp }),
```
> 서버 모드 + 로그인 상태에서만. `key`에 emp를 넣어 사용자 교체 시 재마운트(재조회).

- [ ] **Step 3: 팝업 CSS(필요 시)**

기존 모달 스타일을 재사용하되, 목록 전용 최소 스타일을 `app.css`에 추가:
```css
.pii-notice-group { margin-bottom: 12px; }
.pii-notice-kind { font-size: 12px; font-weight: 700; color: var(--text-2); margin-bottom: 4px; }
.pii-notice-list { margin: 0; padding-left: 16px; }
.pii-notice-list li { font-size: 13px; margin: 2px 0; }
.pii-notice-title { color: var(--ink); font-weight: 600; }
.pii-notice-reason { color: var(--text-2); }
```
> `modal-backdrop`/`modal`/`modal-head/body/foot`/`btn primary`가 기존에 없으면 ShareModal이 쓰는 실제 클래스명으로 맞춘다(해당 파일 확인 후 동일 클래스 사용).

- [ ] **Step 4: 빌드 + 수동 확인**

Run: `cd frontend && pnpm build`
Expected: 성공. 시나리오: 관리자가 알림 보내기/반려 → 수신자(최종수정자/요청자)로 로그인 → 팝업에 노트 제목 목록 표시 → 확인 → 재로그인 시 미표시.

- [ ] **Step 5: 커밋**
```bash
git add frontend/src/components/PiiNoticeModal.tsx frontend/src/App.tsx frontend/src/styles/app.css
git commit -m "feat: 로그인 시 PII 알림 팝업(미확인 알림 목록·ack)"
```

---

### Task 16: 전체 회귀 + 통합 점검

**Files:** 없음(검증 단계)

- [ ] **Step 1: 백엔드 전체 테스트**

Run: `cd backend && ./gradlew test`
Expected: 전부 GREEN (기존 294 + 신규 PII 테스트).

- [ ] **Step 2: 프런트 전체 테스트 + 빌드**

Run: `cd frontend && pnpm test`
Expected: 전부 GREEN.
Run: `cd frontend && pnpm build`
Expected: 성공.

- [ ] **Step 3: 단일 jar 통합 빌드**

Run: `cd backend && ./gradlew bootJar`
Expected: 성공(frontend dist 포함).

- [ ] **Step 4: 수동 e2e(server 모드)**

`WORKNOTE_MODE=server WORKNOTE_ADMIN_PASSWORD=... java -jar build/libs/worknote-0.1.0.jar` 기동 후:
1. 노트에 `010-1234-5678` 입력 → 배너 "개인정보 기입 확인" + 사이드바 경고 아이콘.
2. [예외 요청] → "예외 검토 중".
3. 관리자 "개인정보 점검" → 요청 대기에 노트 표시 → [반려(사유)] → 노트 배너 "개인정보 예외 반려됨".
4. 요청자 재로그인 → 팝업에 반려 알림(사유) → 확인.
5. 본문에서 번호 제거 후 저장 → 배너·아이콘 사라짐.

- [ ] **Step 5: 최종 커밋(필요 시 문서 갱신)**

회귀에서 발견한 수정만 커밋. 기능 완료.

---

## Self-Review (작성자 체크)

**1. 스펙 커버리지**
- §3 탐지(저장 시 백엔드 본문) → Task 1·6 ✓
- §4 상태 기계(suspected/requested/exempted/rejected, 재탐지 규칙) → Task 3 ✓
- §5 탐지기 7종+체크섬 → Task 1 ✓
- §6 데이터 모델 V5 + purge cascade → Task 2·4·5·9 ✓
- §7 API(node/admin/me + tree pii + 감사) → Task 6·8 + tree Task 5 ✓
- §8 백엔드 컴포넌트 → Task 1~9 ✓
- §9 프런트(types·sidebar·banner·예외요청·setNotePii·admin·팝업) → Task 10~15 ✓
- §10 테스트 → 각 Task에 단위/통합 + Task 16 e2e ✓
- §11 엣지(updated_by 부재·반려 재요청·요청 중 약화·유형 비노출·디바운스 루프 차단) → Task 11(setNotePii 분리), Task 8(recipientForNotice 400), Task 13(상태별 배너) ✓

**2. 플레이스홀더 스캔:** 모든 코드 블록 실체 포함. "유형 비노출"은 배너가 status만 사용(types 칩은 관리자 화면에만)으로 충족.

**3. 타입 일관성:** `NotePii{status,types}`(FE) ↔ `PiiInfo/PiiEval{status,types}`(BE) ↔ tree `pii`. `setNotePii`/`piiWarns`/`PiiApi`/`AdminApi.pii*` 이름 일관. `evaluate(nodeId,content)`·`requestException(nodeId,emp,reason)`·`approveWithNotice`·`rejectWithNotice`·`notice(nodeId,recipient,adminEmp)`·`recipientForNotice` 시그니처가 Task 3·6·7·8에서 일치.

**알려진 보강 포인트(실행 중 확인):**
- `req`(api/http) 200 JSON 파싱·204 처리 방식 — Task 10 실행 시 실제 동작 확인(빈 바디 `{}` 반환 가정).
- `admin/common`의 `SecHead/Empty/Modal/StatusBadge` 시그니처 — Task 14에서 기존 스크린(Pending.tsx) 사용법 그대로 차용.
- 모달 클래스명(`modal-backdrop` 등) — Task 15에서 ShareModal 실제 클래스에 맞춤.
