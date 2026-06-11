# Backend Phase 2 코어 (인증 + 권한 엔진) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 2단계(서버 공용) 백엔드 코어 — 세션 기반 인증 + 스펙(`docs/superpowers/specs/2026-06-10-worknote-권한-디렉토리-design.md`)의 권한 해석 엔진을 구현하고 기존 Vault API에 적용한다.

**Architecture:** `worknote.mode` 프로퍼티(local|server, 기본 local)로 2단계 기능을 스위치한다. local 모드는 1단계 동작 그대로(무인증·전체 허용·기존 31 테스트 불변), server 모드에서만 AuthFilter(세션 검사)와 권한 엔진이 활성화된다. 권한 검사는 컨트롤러 앞단의 `VaultGuard`에 집중 — `VaultService`는 시그니처 최소 변경(tree/trashList 필터 인자만 추가). 해석 로직의 순수 부분은 `AclResolver`(정적, DB 무관)로 분리해 유닛 테스트한다.

**Tech Stack:** 기존 스택 유지 — Spring Boot 3.5(Java 21) + MyBatis + Flyway + SQLite(유지 확정). 인증은 Spring Security 없이 서블릿 필터 + `HttpSession`(인메모리, 단일 서버). 비밀번호는 JDK 내장 `PBKDF2WithHmacSHA256` + 사용자별 salt **별도 테이블**(`user_credential`) — 사용자 확정 요구사항.

**범위(사용자 확정):** 인증 + 권한 엔진 + 팀 스키마 + 감사 로그 + 기존 Vault API enforcement까지. **다음 계획으로 이월:** 공유 링크, 관리자 API(사용자/역할/팀/ACL CRUD, 가입 승인), 프런트 로그인·admin 연동, 30일 자동 purge.

---

## 확정 설계 결정 (구현 중 재논의 금지)

| # | 결정 | 근거 |
|---|---|---|
| 1 | `worknote.mode: local\|server` 스위치, 기본 local | 스펙 §0 "단일 사용자 빌드에 ACL 엔진을 넣지 않는다". 1단계 jar 사용성·기존 테스트 보존 |
| 2 | 테이블명 `app_user`, 컬럼명 `grant_type` | `user`/`grant`는 Oracle·PG 예약어 — ANSI 이식성 원칙 유지 (스펙 8.2의 `"user"`/`grant`에서 의도적 변경) |
| 3 | salt 별도 테이블 `user_credential(user_id, salt, password_hash)` | 사용자 명시 요구. 해시는 PBKDF2WithHmacSHA256 120,000회, salt 16바이트 SecureRandom, Base64 저장 |
| 4 | 세션 = 서블릿 `HttpSession` 인메모리 (Spring Session 안 씀) | 폐쇄망 단일 서버 + SQLite — 외부 세션 스토어 불필요. 타임아웃 30분, HttpOnly 쿠키(부트 기본) |
| 5 | 관리자 판정 = 역할 caps가 `admin.*` 5종 전부 포함 | 스펙 §3.1 "관리자 = admin.* 전체 + 우회". 커스텀 관리자급 역할도 동일 기준으로 동작 |
| 6 | 로그인 식별자 = `emp`(사번). 자격 오류는 401 단일 메시지, 비활성 계정은 403 | 계정 존재 여부 노출 방지(비밀번호 검증을 status 검사보다 먼저), pending/disabled는 UX상 구분 |
| 7 | 최초 관리자 = 부트스트랩 러너(`WORKNOTE_ADMIN_PASSWORD` env 필수, 없으면 fail-fast) | Flyway에 해시를 박을 수 없음(salt 무작위). secure-by-default — 기본 비밀번호 금지 |
| 8 | 트리 필터: 읽을 수 있는 자손이 있는 폴더는 **이름만 스텁 노출** | nearest-explicit 상속상 깊은 grant의 조상 폴더는 read가 없지만, 경로 없이는 트리 UI가 성립 안 함. 폴더는 content가 없어 이름 외 노출 없음 |
| 9 | 루트 생성·루트로 이동 = 관리자 전용 (`edit(root)` 기본 관리자) | 스펙 §4.1 |
| 10 | 휴지통 목록 = 본인 삭제분만(관리자는 전체), restore = 삭제자·관리자, purge = 관리자 전용 | 스펙 §4.3 "삭제자/관리자만 복구용으로 본다", "purge 30일 후 관리자" |
| 11 | 감사 로그 = login.success/fail·logout·node.create/move/trash/restore/purge. **PATCH(편집)는 제외** | 스펙 §7 감사 목록에 편집 없음 + 1.5초 디바운스라도 고빈도. local 모드(u=null)는 vault 감사 생략 |
| 12 | `space`·`share_link` 중 space 테이블만 V2에 생성(미사용), share_link는 다음 계획 V3 | space는 스키마 완결성(팀 스페이스 1급) — 관리자 API 계획에서 사용. share_link는 전용 기능과 함께 |
| 13 | server 모드 테스트는 별도 인메모리 DB(`file:phase2mem`) 사용 | 기존 local 테스트와 `file::memory:` 공유 시 AdminBootstrap의 `countUsers==0` 조건이 오염됨 |
| 14 | FK enforcement OFF 유지, Hikari pool=1 유지, 쓰기 API 204 유지 | 1단계 확정 결정 계승 |

**권한 공식 (스펙 §5.2 — 코드가 이걸 그대로 구현해야 함):**

```text
read(U,N)  : admin → T. deny(합집합) → F. ACL read/edit → roleHas(res.read). publicRead(N) → roleHas(res.read). else F
edit(U,N)  : admin → T. deny → F. ACL edit ∧ roleHas(res.edit)        # public은 read 전용 — edit에 무관
create(F)  : roleHas(res.create) ∧ edit(F)                            # F=null(루트) → 관리자만
delete(N)  : roleHas(res.delete) ∧ edit(N)
move(N,F') : edit(N) ∧ edit(F')
다중 주체   : {U} ∪ teams(U) ∪ {@all} — 각 주체의 nearest-explicit를 모아 deny-우선 합집합
nearest    : N→루트 조상 walk에서 첫 명시 entry가 그 주체의 값.
             단 **deny-sticky**: 체인 어딘가에 그 주체의 deny가 있으면 더 가까운 allow와 무관하게 deny
             (§5.1 "한 주체 안에서 deny 아래 재허용은 없다"를 해석기에서 강제 — Task 9 리뷰로 확정)
public     : 체인에서 가장 가까운 public_flag가 'public'이면 true ('exclude'가 더 가까우면 false)
```

---

## File Structure

```
backend/src/main/java/com/worknote/
  auth/                              ← 신규 패키지
    PasswordHasher.java              PBKDF2 해시·검증 (정적 유틸)
    AuthException.java               UNAUTHORIZED(401)/FORBIDDEN(403)
    UserRow.java  CredentialRow.java  RoleRow.java     (record)
    UserMapper.java  RoleMapper.java  (MyBatis 인터페이스)
    RoleCaps.java                    caps JSON 파싱 공용 컴포넌트
    AuthService.java                 로그인 검증·last_login
    AuthController.java              /api/auth/login·logout·me
    dto/LoginRequest.java  dto/MeResponse.java
    AuthFilter.java  AuthFilterConfig.java   server 모드 전용 필터
    AdminBootstrap.java              최초 관리자 생성 러너
  acl/                               ← 신규 패키지
    AclRow.java  PublicFlagRow.java  (record)
    AclMapper.java  TeamMapper.java
    Access.java                      enum NONE/READ/EDIT/DENY
    AclResolver.java                 순수 해석 로직 (정적)
    PermissionService.java           DB 연동 해석기 + readableIds
  audit/                             ← 신규 패키지
    AuditMapper.java  AuditService.java
  vault/
    VaultGuard.java                  ← 신규: 컨트롤러 권한 가드
    VaultController.java             ← 수정: 가드+감사 적용
    VaultService.java                ← 수정: tree(filter)/trashList(filter)
    VaultException.java              ← 수정: FORBIDDEN 추가
  ApiExceptionHandler.java           ← 수정: AuthException·FORBIDDEN 매핑
backend/src/main/resources/
  db/migration/sqlite/V2__phase2_auth_acl.sql   ← 신규
  mappers/UserMapper.xml  RoleMapper.xml  AclMapper.xml  TeamMapper.xml  AuditMapper.xml  ← 신규
  application.yml                    ← 수정: worknote.mode 등
backend/src/test/java/com/worknote/
  auth/PasswordHasherTest.java  UserMapperTest.java  AuthServiceTest.java
       AuthControllerTest.java  AuthFilterTest.java  AdminBootstrapTest.java
  acl/AclMapperTest.java  AclResolverTest.java  PermissionServiceTest.java
  audit/AuditServiceTest.java
  vault/VaultPermissionApiTest.java  (server 모드 enforcement)
  SchemaMigrationTest.java           ← 수정: V2 테이블 검증 추가
```

**테스트 공통 상수:**
- local 모드 테스트(기존): `jdbc:sqlite:file::memory:?cache=shared` — 그대로.
- server 모드 테스트(신규): `jdbc:sqlite:file:phase2mem?mode=memory&cache=shared` + `worknote.mode=server` + `worknote.admin-password=boot-pass-1`.

---

### Task 1: V2 마이그레이션 — 권한 스키마 + 시스템 역할 시드

**Files:**
- Create: `backend/src/main/resources/db/migration/sqlite/V2__phase2_auth_acl.sql`
- Modify: `backend/src/test/java/com/worknote/SchemaMigrationTest.java`

- [ ] **Step 1: 실패하는 테스트 작성** — `SchemaMigrationTest`에 추가:

```java
@Test
void phase2TablesExist() {
    List<String> tables = jdbc.queryForList(
        "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", String.class);
    assertThat(tables).contains("role", "app_user", "user_credential",
        "team", "team_member", "space", "acl", "public_flag", "audit_log");
}

@Test
void systemRolesSeeded() {
    List<String> roles = jdbc.queryForList("SELECT id FROM role WHERE system = 1 ORDER BY id", String.class);
    assertThat(roles).containsExactly("admin", "operator", "visitor");
    String adminCaps = jdbc.queryForObject("SELECT caps FROM role WHERE id = 'admin'", String.class);
    assertThat(adminCaps).contains("admin.permissions").contains("res.share");
}
```

(기존 테스트 클래스의 import·필드 구조를 그대로 따른다. `List`/`assertThat` import가 없으면 추가.)

- [ ] **Step 2: 실패 확인** — `cd backend && ./gradlew test --tests SchemaMigrationTest` → FAIL (no such table)

- [ ] **Step 3: 마이그레이션 작성** — `V2__phase2_auth_acl.sql`:

```sql
-- V2__phase2_auth_acl.sql  (phase 2 권한 스키마 — node/tag는 V1 그대로, ANSI 지향)
-- 'user'·'grant'는 Oracle/PG 예약어 → app_user / grant_type 사용
CREATE TABLE role (
  id     TEXT PRIMARY KEY,
  name   TEXT NOT NULL,
  system INTEGER NOT NULL DEFAULT 0,
  caps   TEXT NOT NULL               -- JSON 배열 문자열: res.*/admin.* 집합
);

CREATE TABLE app_user (
  id         TEXT PRIMARY KEY,
  emp        TEXT NOT NULL UNIQUE,   -- 사번 = 로그인 식별자
  email      TEXT,
  name       TEXT NOT NULL,
  role_id    TEXT NOT NULL REFERENCES role(id),
  status     TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('pending','active','disabled')),
  last_login TEXT
);

-- 사용자별 salt 분리 테이블 (요구사항: salt는 사용자별 맵핑 테이블로 별도 관리)
CREATE TABLE user_credential (
  user_id       TEXT PRIMARY KEY REFERENCES app_user(id),
  salt          TEXT NOT NULL,       -- Base64(16바이트 SecureRandom)
  password_hash TEXT NOT NULL        -- Base64(PBKDF2-HMAC-SHA256, 120000회, 256bit)
);

CREATE TABLE team (
  id   TEXT PRIMARY KEY,
  name TEXT NOT NULL
);
CREATE TABLE team_member (
  team_id TEXT NOT NULL REFERENCES team(id),
  user_id TEXT NOT NULL REFERENCES app_user(id),
  PRIMARY KEY (team_id, user_id)
);
CREATE INDEX idx_team_member_user ON team_member(user_id);   -- teamsOf(사용자→팀) 조회 경로

-- 팀 스페이스(1급): 최상위 폴더 ↔ 소유 팀. 관리자 API(다음 계획)에서 사용 — 스키마는 한 번에
CREATE TABLE space (
  node_id TEXT PRIMARY KEY REFERENCES node(id),
  team_id TEXT REFERENCES team(id)   -- NULL = 공용(소유 팀 없음)
);

CREATE TABLE acl (
  principal_type TEXT NOT NULL CHECK (principal_type IN ('user','team','all')),
  principal_id   TEXT NOT NULL,      -- @all은 센티넬 '@all'
  node_id        TEXT NOT NULL REFERENCES node(id),
  grant_type     TEXT NOT NULL CHECK (grant_type IN ('read','edit','deny')),
  PRIMARY KEY (principal_type, principal_id, node_id)
);
CREATE INDEX idx_acl_node ON acl(node_id);

CREATE TABLE public_flag (
  node_id TEXT PRIMARY KEY REFERENCES node(id),
  mode    TEXT NOT NULL CHECK (mode IN ('public','exclude'))
);

-- AUDIT은 Oracle 완전 예약어 → audit_log (app_user/grant_type와 동일한 회피 규칙)
CREATE TABLE audit_log (
  id     INTEGER PRIMARY KEY,        -- SQLite rowid 자동 증가 (AUTOINCREMENT 불필요)
  at     TEXT NOT NULL,
  who    TEXT NOT NULL,
  act    TEXT NOT NULL,
  target TEXT,
  ip     TEXT
);
CREATE INDEX idx_audit_log_at ON audit_log(at);

INSERT INTO role (id, name, system, caps) VALUES
 ('admin',    '관리자', 1, '["admin.users","admin.permissions","admin.roles","admin.security","admin.audit","res.read","res.edit","res.create","res.delete","res.export","res.share"]'),
 ('operator', '운영자', 1, '["res.read","res.edit","res.create","res.delete","res.export","res.share"]'),
 ('visitor',  '방문자', 1, '["res.read"]');
```

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests SchemaMigrationTest` → PASS. 전체 `./gradlew test` 도 green(기존 31 불변).

- [ ] **Step 5: Commit** — `git add backend && git commit -m "feat(backend): V2 마이그레이션 — 권한 스키마 + 시스템 역할 시드"`

---

### Task 2: PasswordHasher — PBKDF2 + 사용자별 salt

**Files:**
- Create: `backend/src/main/java/com/worknote/auth/PasswordHasher.java`
- Test: `backend/src/test/java/com/worknote/auth/PasswordHasherTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.worknote.auth;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PasswordHasherTest {

    @Test
    void newSaltIsRandomBase64() {
        String s1 = PasswordHasher.newSalt();
        String s2 = PasswordHasher.newSalt();
        assertThat(s1).isNotEqualTo(s2);
        assertThat(java.util.Base64.getDecoder().decode(s1)).hasSize(16);
    }

    @Test
    void hashIsDeterministicForSameSalt() {
        String salt = PasswordHasher.newSalt();
        assertThat(PasswordHasher.hash("pw-1234", salt))
            .isEqualTo(PasswordHasher.hash("pw-1234", salt));
    }

    @Test
    void differentSaltDifferentHash() {
        assertThat(PasswordHasher.hash("pw-1234", PasswordHasher.newSalt()))
            .isNotEqualTo(PasswordHasher.hash("pw-1234", PasswordHasher.newSalt()));
    }

    @Test
    void verifyMatchesAndRejects() {
        String salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash("correct-pw", salt);
        assertThat(PasswordHasher.verify("correct-pw", salt, hash)).isTrue();
        assertThat(PasswordHasher.verify("wrong-pw", salt, hash)).isFalse();
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests PasswordHasherTest` → 컴파일 실패(클래스 없음)

- [ ] **Step 3: 구현**

```java
package com.worknote.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** PBKDF2-HMAC-SHA256 비밀번호 해시. salt는 사용자별 — user_credential 테이블에 별도 저장. */
public final class PasswordHasher {

    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {}

    public static String newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    public static String hash(String password, String saltBase64) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(),
                Base64.getDecoder().decode(saltBase64), ITERATIONS, KEY_BITS);
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(key);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 사용 불가", e);
        }
    }

    public static boolean verify(String password, String saltBase64, String expectedHashBase64) {
        byte[] actual = Base64.getDecoder().decode(hash(password, saltBase64));
        byte[] expected = Base64.getDecoder().decode(expectedHashBase64);
        return MessageDigest.isEqual(actual, expected);   // 타이밍 공격 방지 비교
    }
}
```

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests PasswordHasherTest` → PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(backend): PasswordHasher — PBKDF2 + 사용자별 salt"`

---

### Task 3: Auth 도메인 record + UserMapper/RoleMapper

**Files:**
- Create: `backend/src/main/java/com/worknote/auth/UserRow.java`, `CredentialRow.java`, `RoleRow.java`, `UserMapper.java`, `RoleMapper.java`
- Create: `backend/src/main/resources/mappers/UserMapper.xml`, `RoleMapper.xml`
- Test: `backend/src/test/java/com/worknote/auth/UserMapperTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.worknote.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared")
class UserMapperTest {
    @Autowired UserMapper mapper;
    @Autowired RoleMapper roleMapper;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM user_credential");
        jdbc.update("DELETE FROM app_user");
    }

    private UserRow user(String id, String emp, String roleId, String status) {
        return new UserRow(id, emp, emp + "@corp.local", "이름-" + emp, roleId, status, null);
    }

    @Test
    void insertAndFindByEmp() {
        mapper.insert(user("u1", "10001", "operator", "active"));
        UserRow found = mapper.findByEmp("10001");
        assertThat(found.id()).isEqualTo("u1");
        assertThat(found.roleId()).isEqualTo("operator");
        assertThat(mapper.findByEmp("99999")).isNull();
    }

    @Test
    void findByIdAndCount() {
        assertThat(mapper.countUsers()).isZero();
        mapper.insert(user("u1", "10001", "operator", "active"));
        assertThat(mapper.findById("u1").emp()).isEqualTo("10001");
        assertThat(mapper.countUsers()).isEqualTo(1);
    }

    @Test
    void credentialRoundTrip() {
        mapper.insert(user("u1", "10001", "operator", "active"));
        mapper.insertCredential(new CredentialRow("u1", "c2FsdA==", "aGFzaA=="));
        CredentialRow cred = mapper.findCredential("u1");
        assertThat(cred.salt()).isEqualTo("c2FsdA==");
        assertThat(cred.passwordHash()).isEqualTo("aGFzaA==");
        assertThat(mapper.findCredential("none")).isNull();
    }

    @Test
    void stampLastLogin() {
        mapper.insert(user("u1", "10001", "operator", "active"));
        mapper.stampLastLogin("u1", "2026-06-11T10:00:00");
        assertThat(mapper.findById("u1").lastLogin()).isEqualTo("2026-06-11T10:00:00");
    }

    @Test
    void roleSeedReadable() {
        RoleRow admin = roleMapper.findById("admin");
        assertThat(admin.system()).isEqualTo(1);
        assertThat(admin.caps()).contains("admin.audit");
        assertThat(roleMapper.findById("visitor").caps()).contains("res.read");
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests UserMapperTest` → 컴파일 실패

- [ ] **Step 3: 구현** — record 3개 + 매퍼 2개 + XML 2개:

```java
package com.worknote.auth;

/** app_user 1행. status: pending|active|disabled. */
public record UserRow(String id, String emp, String email, String name,
                      String roleId, String status, String lastLogin) {}
```

```java
package com.worknote.auth;

/** user_credential 1행 — salt는 사용자별 분리 저장(설계 결정 #3). */
public record CredentialRow(String userId, String salt, String passwordHash) {}
```

```java
package com.worknote.auth;

/** role 1행. caps = JSON 배열 문자열 (파싱은 RoleCaps). */
public record RoleRow(String id, String name, int system, String caps) {}
```

```java
package com.worknote.auth;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {
    void insert(UserRow row);
    UserRow findById(@Param("id") String id);
    UserRow findByEmp(@Param("emp") String emp);
    int countUsers();
    void stampLastLogin(@Param("id") String id, @Param("at") String at);
    CredentialRow findCredential(@Param("userId") String userId);
    void insertCredential(CredentialRow row);
}
```

```java
package com.worknote.auth;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RoleMapper {
    RoleRow findById(@Param("id") String id);
}
```

`UserMapper.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.worknote.auth.UserMapper">

  <insert id="insert">
    INSERT INTO app_user (id, emp, email, name, role_id, status, last_login)
    VALUES (#{id}, #{emp}, #{email}, #{name}, #{roleId}, #{status}, #{lastLogin})
  </insert>

  <select id="findById" resultType="com.worknote.auth.UserRow">
    SELECT * FROM app_user WHERE id = #{id}
  </select>

  <select id="findByEmp" resultType="com.worknote.auth.UserRow">
    SELECT * FROM app_user WHERE emp = #{emp}
  </select>

  <select id="countUsers" resultType="int">
    SELECT COUNT(*) FROM app_user
  </select>

  <update id="stampLastLogin">
    UPDATE app_user SET last_login = #{at} WHERE id = #{id}
  </update>

  <select id="findCredential" resultType="com.worknote.auth.CredentialRow">
    SELECT * FROM user_credential WHERE user_id = #{userId}
  </select>

  <insert id="insertCredential">
    INSERT INTO user_credential (user_id, salt, password_hash)
    VALUES (#{userId}, #{salt}, #{passwordHash})
  </insert>

</mapper>
```

`RoleMapper.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.worknote.auth.RoleMapper">
  <select id="findById" resultType="com.worknote.auth.RoleRow">
    SELECT * FROM role WHERE id = #{id}
  </select>
</mapper>
```

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests UserMapperTest` → PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(backend): auth 도메인 record + User/Role 매퍼"`

---

### Task 4: AuthException + RoleCaps + AuthService

**Files:**
- Create: `backend/src/main/java/com/worknote/auth/AuthException.java`, `RoleCaps.java`, `AuthService.java`
- Modify: `backend/src/main/java/com/worknote/ApiExceptionHandler.java`
- Test: `backend/src/test/java/com/worknote/auth/AuthServiceTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.worknote.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared")
class AuthServiceTest {
    @Autowired AuthService auth;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM user_credential");
        jdbc.update("DELETE FROM app_user");
    }

    private void createUser(String id, String emp, String roleId, String status, String password) {
        users.insert(new UserRow(id, emp, null, "이름-" + emp, roleId, status, null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow(id, salt, PasswordHasher.hash(password, salt)));
    }

    @Test
    void loginSuccessReturnsUserAndCapsAndStampsLastLogin() {
        createUser("u1", "10001", "operator", "active", "pw-1234");
        AuthService.AuthUser result = auth.login("10001", "pw-1234");
        assertThat(result.user().id()).isEqualTo("u1");
        assertThat(result.caps()).contains("res.edit").doesNotContain("admin.users");
        assertThat(users.findById("u1").lastLogin()).isNotNull();
    }

    @Test
    void loginWrongPasswordIs401() {
        createUser("u1", "10001", "operator", "active", "pw-1234");
        assertThatThrownBy(() -> auth.login("10001", "nope"))
            .isInstanceOf(AuthException.class)
            .satisfies(e -> assertThat(((AuthException) e).status()).isEqualTo(AuthException.Status.UNAUTHORIZED));
    }

    @Test
    void loginUnknownEmpIs401SameMessage() {
        createUser("u1", "10001", "operator", "active", "pw-1234");
        Throwable unknown = catchThrowable(() -> auth.login("99999", "pw-1234"));
        Throwable wrongPw = catchThrowable(() -> auth.login("10001", "nope"));
        assertThat(unknown.getMessage()).isEqualTo(wrongPw.getMessage());  // 계정 존재 노출 금지
    }

    @Test
    void loginPendingUserIs403() {
        createUser("u1", "10001", "operator", "pending", "pw-1234");
        assertThatThrownBy(() -> auth.login("10001", "pw-1234"))
            .isInstanceOf(AuthException.class)
            .satisfies(e -> assertThat(((AuthException) e).status()).isEqualTo(AuthException.Status.FORBIDDEN));
    }

    @Test
    void capsParsesAdminRole() {
        createUser("u1", "10001", "admin", "active", "pw-1234");
        assertThat(auth.login("10001", "pw-1234").caps())
            .contains("admin.users", "admin.permissions", "admin.roles", "admin.security", "admin.audit");
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests AuthServiceTest` → 컴파일 실패

- [ ] **Step 3: 구현**

```java
package com.worknote.auth;

/** 인증 도메인 예외. UNAUTHORIZED→401, FORBIDDEN→403 (ApiExceptionHandler). */
public class AuthException extends RuntimeException {

    public enum Status { UNAUTHORIZED, FORBIDDEN }

    private final Status status;

    public AuthException(Status status, String message) {
        super(message);
        this.status = status;
    }

    public Status status() {
        return status;
    }

    public static AuthException unauthorized(String message) {
        return new AuthException(Status.UNAUTHORIZED, message);
    }

    public static AuthException forbidden(String message) {
        return new AuthException(Status.FORBIDDEN, message);
    }
}
```

```java
package com.worknote.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Set;

/** role.caps(JSON 배열 문자열) 파싱 — AuthService·PermissionService 공용. */
@Component
public class RoleCaps {

    private final RoleMapper roles;
    private final ObjectMapper json;

    public RoleCaps(RoleMapper roles, ObjectMapper json) {
        this.roles = roles;
        this.json = json;
    }

    public Set<String> of(String roleId) {
        RoleRow role = roles.findById(roleId);
        if (role == null) {
            return Set.of();   // 알 수 없는 역할 = 능력 없음 (default-deny)
        }
        try {
            return json.readValue(role.caps(), new TypeReference<Set<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("role.caps JSON 파싱 실패: " + roleId, e);
        }
    }
}
```

```java
package com.worknote.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/** 로그인 검증·last_login 스탬프. 세션 부여는 AuthController. */
@Service
public class AuthService {

    private static final String BAD_CREDENTIALS = "사번 또는 비밀번호가 올바르지 않습니다";

    private final UserMapper users;
    private final RoleCaps roleCaps;
    private final Clock clock;

    public AuthService(UserMapper users, RoleCaps roleCaps, Clock clock) {
        this.users = users;
        this.roleCaps = roleCaps;
        this.clock = clock;
    }

    public record AuthUser(UserRow user, Set<String> caps) {}

    @Transactional
    public AuthUser login(String emp, String password) {
        UserRow user = users.findByEmp(emp);
        if (user == null) {
            throw AuthException.unauthorized(BAD_CREDENTIALS);
        }
        CredentialRow cred = users.findCredential(user.id());
        if (cred == null || !PasswordHasher.verify(password, cred.salt(), cred.passwordHash())) {
            throw AuthException.unauthorized(BAD_CREDENTIALS);
        }
        // 비밀번호 검증 후에만 상태 노출 — 미인증 상대에게 계정 상태를 알리지 않음
        if (!"active".equals(user.status())) {
            throw AuthException.forbidden("활성화되지 않은 계정입니다 (상태: " + user.status() + ")");
        }
        users.stampLastLogin(user.id(),
            LocalDateTime.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return new AuthUser(user, roleCaps.of(user.roleId()));
    }

    public Set<String> caps(UserRow user) {
        return roleCaps.of(user.roleId());
    }
}
```

`ApiExceptionHandler.java`에 추가 (기존 핸들러 유지):

```java
@ExceptionHandler(AuthException.class)
public ResponseEntity<Map<String, String>> auth(AuthException e) {
    HttpStatus status = switch (e.status()) {
        case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
        case FORBIDDEN -> HttpStatus.FORBIDDEN;
    };
    return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
}
```

(import `com.worknote.auth.AuthException` 추가.)

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests AuthServiceTest` → PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(backend): AuthService 로그인 검증 + RoleCaps + AuthException"`

---

### Task 5: AuthController — /api/auth/login·logout·me + 세션

**Files:**
- Create: `backend/src/main/java/com/worknote/auth/AuthController.java`, `dto/LoginRequest.java`, `dto/MeResponse.java`
- Modify: `backend/src/main/resources/application.yml` (worknote.mode + 세션 타임아웃)
- Test: `backend/src/test/java/com/worknote/auth/AuthControllerTest.java`

**컨텍스트:** 이 시점엔 AuthFilter가 없으므로 테스트는 컨트롤러 단독 동작(login/logout)과 local 모드 `/me`를 검증한다. server 모드 `/me`(필터 경유)는 Task 6에서.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.worknote.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared")
@AutoConfigureMockMvc
class AuthControllerTest {
    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM user_credential");
        jdbc.update("DELETE FROM app_user");
        users.insert(new UserRow("u1", "10001", null, "홍길동", "operator", "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("u1", salt, PasswordHasher.hash("pw-1234", salt)));
    }

    @Test
    void loginSetsSessionAndReturnsMe() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.emp").value("10001"))
            .andExpect(jsonPath("$.name").value("홍길동"))
            .andExpect(jsonPath("$.roleId").value("operator"))
            .andExpect(jsonPath("$.caps").isArray());
        assertThat(session.getAttribute(AuthController.SESSION_USER)).isEqualTo("u1");
    }

    @Test
    void loginFailureIs401AndNoSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10001\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").exists());
        assertThat(session.getAttribute(AuthController.SESSION_USER)).isNull();
    }

    @Test
    void loginValidatesBody() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"emp\":\"\",\"password\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void logoutInvalidatesSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").session(session))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void meInLocalModeReturnsSyntheticLocalAdmin() throws Exception {
        mvc.perform(get("/api/auth/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("local"))
            .andExpect(jsonPath("$.roleId").value("admin"));
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests AuthControllerTest` → 컴파일 실패

- [ ] **Step 3: 구현**

```java
package com.worknote.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank String emp, @NotBlank String password) {}
```

```java
package com.worknote.auth.dto;

import java.util.Set;

public record MeResponse(String id, String emp, String name, String roleId, Set<String> caps) {}
```

```java
package com.worknote.auth;

import com.worknote.auth.dto.LoginRequest;
import com.worknote.auth.dto.MeResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/** 세션 기반 인증 API. server 모드에선 AuthFilter가 login/health 외 전부를 가드한다. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public static final String SESSION_USER = "worknote.userId";

    private final AuthService auth;
    private final boolean serverMode;

    public AuthController(AuthService auth, @Value("${worknote.mode:local}") String mode) {
        this.auth = auth;
        this.serverMode = "server".equals(mode);
    }

    @PostMapping("/login")
    public MeResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        AuthService.AuthUser result = auth.login(req.emp(), req.password());
        http.getSession(true).setAttribute(SESSION_USER, result.user().id());
        return toMe(result.user(), result.caps());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest http) {
        HttpSession session = http.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    @GetMapping("/me")
    public MeResponse me(HttpServletRequest http) {
        UserRow user = (UserRow) http.getAttribute(AuthFilter.CURRENT_USER);
        if (user != null) {
            return toMe(user, auth.caps(user));
        }
        if (serverMode) {
            // server 모드에선 필터가 먼저 401을 반환 — 방어적 가드
            throw AuthException.unauthorized("인증이 필요합니다");
        }
        return new MeResponse("local", "local", "local", "admin", Set.of());  // 1단계 호환
    }

    private static MeResponse toMe(UserRow user, Set<String> caps) {
        return new MeResponse(user.id(), user.emp(), user.name(), user.roleId(), caps);
    }
}
```

**주의:** `AuthFilter.CURRENT_USER` 상수는 Task 6에서 만들지만 컴파일이 필요하므로, 이 태스크에서 상수만 가진 빈 필터 클래스를 먼저 만들지 **말고** — Task 5에서는 `AuthController` 안에 `public static final String CURRENT_USER_ATTR = "worknote.currentUser";`를 임시로 두지 않는다. 대신 **이 태스크에서 `AuthFilter.java`의 상수 정의까지만 미리 생성**한다:

```java
package com.worknote.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** server 모드 세션 가드 — 등록·검증 로직은 Task 6에서 완성. */
public class AuthFilter extends OncePerRequestFilter {

    public static final String CURRENT_USER = "worknote.currentUser";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(req, res);   // Task 6에서 구현
    }
}
```

`application.yml` 수정 (전체 파일):

```yaml
spring:
  datasource:
    url: jdbc:sqlite:${WORKNOTE_DB:./worknote.db}
    driver-class-name: org.sqlite.JDBC
    hikari:
      maximum-pool-size: 1   # SQLite 단일 라이터 — 풀 1로 SQLITE_BUSY 방지
  flyway:
    locations: classpath:db/migration/sqlite   # Oracle 전환 시 vendor 디렉토리 추가
mybatis:
  mapper-locations: classpath:mappers/*.xml
  configuration:
    map-underscore-to-camel-case: true
server:
  port: 8080
  servlet:
    session:
      timeout: 30m           # server 모드 세션 타임아웃 (스펙 §7 보안 정책)
worknote:
  mode: ${WORKNOTE_MODE:local}                     # local=1단계(무인증), server=2단계(인증+권한)
  admin-password: ${WORKNOTE_ADMIN_PASSWORD:}      # server 모드 최초 기동 시 관리자 비밀번호 (필수)
```

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests AuthControllerTest` → PASS, 전체 green
- [ ] **Step 5: Commit** — `git commit -m "feat(backend): AuthController — 세션 로그인/로그아웃/me"`

---

### Task 6: AuthFilter 완성 + mode 스위치

**Files:**
- Modify: `backend/src/main/java/com/worknote/auth/AuthFilter.java`
- Create: `backend/src/main/java/com/worknote/auth/AuthFilterConfig.java`
- Test: `backend/src/test/java/com/worknote/auth/AuthFilterTest.java`

**컨텍스트:** server 모드 테스트는 별도 인메모리 DB(`file:phase2mem`)를 쓴다(설계 결정 #13). `worknote.admin-password`도 줘야 AdminBootstrap(Task 7 이후 합류)이 fail-fast하지 않는다 — Task 7 이전인 지금도 미리 줘 둔다(무해).

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.worknote.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:phase2mem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
@AutoConfigureMockMvc
class AuthFilterTest {
    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        users.insert(new UserRow("u1", "10001", null, "홍길동", "operator", "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("u1", salt, PasswordHasher.hash("pw-1234", salt)));
    }

    private MockHttpSession login(String emp, String pw) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"" + emp + "\",\"password\":\"" + pw + "\"}"))
            .andExpect(status().isOk());
        return session;
    }

    @Test
    void unauthenticatedApiIs401() throws Exception {
        mvc.perform(get("/api/tree"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void loginAndHealthAreAllowlisted() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"emp\":\"x\",\"password\":\"y\"}"))
            .andExpect(status().isUnauthorized());   // 401(자격 오류)이지 필터 차단이 아님 — error 바디 존재
    }

    @Test
    void authenticatedSessionPassesAndMeWorks() throws Exception {
        MockHttpSession session = login("10001", "pw-1234");
        mvc.perform(get("/api/auth/me").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.emp").value("10001"));
        mvc.perform(get("/api/tree").session(session))
            .andExpect(status().isOk());
    }

    @Test
    void logoutThen401() throws Exception {
        MockHttpSession session = login("10001", "pw-1234");
        mvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void disabledUserSessionIsRejected() throws Exception {
        MockHttpSession session = login("10001", "pw-1234");
        jdbc.update("UPDATE app_user SET status = 'disabled' WHERE id = 'u1'");
        mvc.perform(get("/api/tree").session(session))
            .andExpect(status().isUnauthorized());   // 세션 살아있어도 비활성화 즉시 차단
    }
}
```

추가로 **local 모드 무영향 회귀**: 기존 `VaultControllerTest`(local)가 그대로 green이어야 한다 — 필터 미등록 확인은 전체 테스트로 갈음.

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests AuthFilterTest` → FAIL (`/api/tree` 200 — 필터 없음). **주의:** Task 7 전이라 AdminBootstrap이 없어 `u-admin`은 존재하지 않지만 DELETE의 `<> 'u-admin'` 조건은 무해.

- [ ] **Step 3: 구현** — `AuthFilter` 본문 완성:

```java
package com.worknote.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/** server 모드 세션 가드. 통과 시 request attribute에 UserRow를 싣는다. local 모드에선 미등록. */
public class AuthFilter extends OncePerRequestFilter {

    public static final String CURRENT_USER = "worknote.currentUser";
    private static final Set<String> ALLOWLIST = Set.of("/api/auth/login", "/api/health");

    private final UserMapper users;
    private final ObjectMapper json;

    public AuthFilter(UserMapper users, ObjectMapper json) {
        this.users = users;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (ALLOWLIST.contains(req.getRequestURI())) {
            chain.doFilter(req, res);
            return;
        }
        HttpSession session = req.getSession(false);
        String userId = session != null ? (String) session.getAttribute(AuthController.SESSION_USER) : null;
        UserRow user = userId != null ? users.findById(userId) : null;
        if (user == null || !"active".equals(user.status())) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write(json.writeValueAsString(Map.of("error", "인증이 필요합니다")));
            return;
        }
        req.setAttribute(CURRENT_USER, user);
        chain.doFilter(req, res);
    }
}
```

```java
package com.worknote.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** worknote.mode=server에서만 /api/*에 AuthFilter 등록 — local 모드는 1단계 동작 그대로. */
@Configuration
public class AuthFilterConfig {

    @Bean
    @ConditionalOnProperty(name = "worknote.mode", havingValue = "server")
    public FilterRegistrationBean<AuthFilter> authFilter(UserMapper users, ObjectMapper json) {
        FilterRegistrationBean<AuthFilter> reg = new FilterRegistrationBean<>(new AuthFilter(users, json));
        reg.addUrlPatterns("/api/*");
        return reg;
    }
}
```

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests AuthFilterTest` → PASS, **전체 `./gradlew test` green**(local 모드 회귀 포함)
- [ ] **Step 5: Commit** — `git commit -m "feat(backend): AuthFilter — server 모드 세션 가드 + mode 스위치"`

---

### Task 7: AdminBootstrap — 최초 관리자 생성

**Files:**
- Create: `backend/src/main/java/com/worknote/auth/AdminBootstrap.java`
- Test: `backend/src/test/java/com/worknote/auth/AdminBootstrapTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.worknote.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:phase2mem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
@AutoConfigureMockMvc
class AdminBootstrapTest {
    @Autowired UserMapper users;
    @Autowired MockMvc mvc;

    @Test
    void adminUserCreatedOnFirstBoot() {
        UserRow admin = users.findById("u-admin");
        assertThat(admin).isNotNull();
        assertThat(admin.emp()).isEqualTo("admin");
        assertThat(admin.roleId()).isEqualTo("admin");
        assertThat(admin.status()).isEqualTo("active");
        assertThat(users.findCredential("u-admin")).isNotNull();
    }

    @Test
    void adminCanLoginWithEnvPassword() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"emp\":\"admin\",\"password\":\"boot-pass-1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roleId").value("admin"));
    }
}
```

그리고 fail-fast 유닛 테스트(컨텍스트 기동 없이 러너 직접 호출):

```java
package com.worknote.auth;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AdminBootstrapUnitTest {

    @Test
    void blankPasswordFailsFastWhenNoUsers() {
        UserMapper users = mock(UserMapper.class);
        when(users.countUsers()).thenReturn(0);
        AdminBootstrap boot = new AdminBootstrap(users, "");
        assertThatThrownBy(() -> boot.run(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void existingUsersSkipBootstrap() throws Exception {
        UserMapper users = mock(UserMapper.class);
        when(users.countUsers()).thenReturn(3);
        new AdminBootstrap(users, "").run(null);   // 예외 없이 통과
        verify(users, never()).insert(any());
    }
}
```

(파일: `backend/src/test/java/com/worknote/auth/AdminBootstrapUnitTest.java`. Mockito는 spring-boot-starter-test에 포함.)

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests AdminBootstrapTest --tests AdminBootstrapUnitTest` → 컴파일 실패

- [ ] **Step 3: 구현**

```java
package com.worknote.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** server 모드 최초 기동: 사용자 0명이면 WORKNOTE_ADMIN_PASSWORD로 관리자 생성. 없으면 fail-fast. */
@Component
@ConditionalOnProperty(name = "worknote.mode", havingValue = "server")
public class AdminBootstrap implements ApplicationRunner {

    public static final String ADMIN_ID = "u-admin";

    private final UserMapper users;
    private final String adminPassword;

    public AdminBootstrap(UserMapper users, @Value("${worknote.admin-password:}") String adminPassword) {
        this.users = users;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.countUsers() > 0) {
            return;
        }
        if (adminPassword == null || adminPassword.isBlank()) {
            throw new IllegalStateException(
                "server 모드 최초 기동: WORKNOTE_ADMIN_PASSWORD 환경변수로 관리자 비밀번호를 지정하세요");
        }
        users.insert(new UserRow(ADMIN_ID, "admin", null, "관리자", "admin", "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow(ADMIN_ID, salt, PasswordHasher.hash(adminPassword, salt)));
    }
}
```

- [ ] **Step 4: 통과 확인** — 두 테스트 + 전체 green. **주의:** `AuthFilterTest`와 같은 컨텍스트 properties라 캐시 공유 — AdminBootstrap이 이미 admin을 만들었을 수 있으니 `countUsers()` 조건이 그대로 idempotent하게 동작하는지 확인.
- [ ] **Step 5: Commit** — `git commit -m "feat(backend): AdminBootstrap — 최초 관리자 생성 (env 필수, fail-fast)"`

---

### Task 8: ACL·팀 매퍼 + ancestorChain CTE

**Files:**
- Create: `backend/src/main/java/com/worknote/acl/AclRow.java`, `PublicFlagRow.java`, `AclMapper.java`, `TeamMapper.java`
- Create: `backend/src/main/resources/mappers/AclMapper.xml`, `TeamMapper.xml`
- Test: `backend/src/test/java/com/worknote/acl/AclMapperTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.worknote.acl;

import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared")
class AclMapperTest {
    @Autowired AclMapper acl;
    @Autowired TeamMapper teams;
    @Autowired NodeMapper nodes;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM acl");
        jdbc.update("DELETE FROM public_flag");
        jdbc.update("DELETE FROM team_member");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM node");
    }

    private void node(String id, String parentId, String type) {
        nodes.insert(new NodeRow(id, parentId, type, "N-" + id, 1, null, null, null, null));
    }

    @Test
    void ancestorChainSelfToRoot() {
        node("f1", null, "folder");
        node("f2", "f1", "folder");
        node("n1", "f2", "note");
        assertThat(acl.ancestorChain("n1")).containsExactly("n1", "f2", "f1");  // 자신→루트 순
        assertThat(acl.ancestorChain("f1")).containsExactly("f1");
    }

    @Test
    void aclInsertAndFindForNodes() {
        node("f1", null, "folder");
        node("n1", "f1", "note");
        acl.insertAcl(new AclRow("team", "t-pay", "f1", "edit"));
        acl.insertAcl(new AclRow("user", "u1", "n1", "deny"));
        List<AclRow> rows = acl.findAclForNodes(List.of("n1", "f1"));
        assertThat(rows).hasSize(2);
        assertThat(acl.findAllAcl()).hasSize(2);
    }

    @Test
    void publicFlagsRoundTrip() {
        node("f1", null, "folder");
        node("n1", "f1", "note");
        acl.insertPublicFlag("f1", "public");
        acl.insertPublicFlag("n1", "exclude");
        assertThat(acl.findPublicFlagsForNodes(List.of("n1", "f1"))).hasSize(2);
        assertThat(acl.findAllPublicFlags())
            .extracting(PublicFlagRow::mode).containsExactlyInAnyOrder("public", "exclude");
    }

    @Test
    void teamsOfUser() {
        teams.insertTeam("t-pay", "결제팀");
        teams.insertTeam("t-sec", "보안팀");
        teams.addMember("t-pay", "u1");
        teams.addMember("t-sec", "u1");
        teams.addMember("t-pay", "u2");
        assertThat(teams.teamsOf("u1")).containsExactlyInAnyOrder("t-pay", "t-sec");
        assertThat(teams.teamsOf("u-none")).isEmpty();
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests AclMapperTest` → 컴파일 실패

- [ ] **Step 3: 구현**

```java
package com.worknote.acl;

/** acl 1행. principalType: user|team|all (@all 센티넬), grantType: read|edit|deny. */
public record AclRow(String principalType, String principalId, String nodeId, String grantType) {}
```

```java
package com.worknote.acl;

/** public_flag 1행. mode: public(폴더 cascade) | exclude(노트 카브아웃). */
public record PublicFlagRow(String nodeId, String mode) {}
```

```java
package com.worknote.acl;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AclMapper {
    List<String> ancestorChain(@Param("id") String id);   // 재귀 CTE — 자신→루트 순(depth asc)
    List<AclRow> findAclForNodes(@Param("nodeIds") List<String> nodeIds);
    List<AclRow> findAllAcl();
    List<PublicFlagRow> findPublicFlagsForNodes(@Param("nodeIds") List<String> nodeIds);
    List<PublicFlagRow> findAllPublicFlags();
    void insertAcl(AclRow row);
    void insertPublicFlag(@Param("nodeId") String nodeId, @Param("mode") String mode);
}
```

```java
package com.worknote.acl;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface TeamMapper {
    void insertTeam(@Param("id") String id, @Param("name") String name);
    void addMember(@Param("teamId") String teamId, @Param("userId") String userId);
    List<String> teamsOf(@Param("userId") String userId);
}
```

`AclMapper.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.worknote.acl.AclMapper">

  <!-- 재귀 CTE: Oracle 전환 시 'WITH RECURSIVE' → 'WITH' (NodeMapper.xml과 동일 패턴) -->
  <select id="ancestorChain" resultType="string">
    WITH RECURSIVE chain(id, parent_id, depth) AS (
      SELECT id, parent_id, 0 FROM node WHERE id = #{id}
      UNION ALL
      SELECT n.id, n.parent_id, c.depth + 1 FROM node n JOIN chain c ON n.id = c.parent_id
    )
    SELECT id FROM chain ORDER BY depth
  </select>

  <select id="findAclForNodes" resultType="com.worknote.acl.AclRow">
    SELECT principal_type, principal_id, node_id, grant_type FROM acl
    WHERE node_id IN
    <foreach item="n" collection="nodeIds" open="(" separator="," close=")">#{n}</foreach>
  </select>

  <select id="findAllAcl" resultType="com.worknote.acl.AclRow">
    SELECT principal_type, principal_id, node_id, grant_type FROM acl
  </select>

  <select id="findPublicFlagsForNodes" resultType="com.worknote.acl.PublicFlagRow">
    SELECT node_id, mode FROM public_flag
    WHERE node_id IN
    <foreach item="n" collection="nodeIds" open="(" separator="," close=")">#{n}</foreach>
  </select>

  <select id="findAllPublicFlags" resultType="com.worknote.acl.PublicFlagRow">
    SELECT node_id, mode FROM public_flag
  </select>

  <insert id="insertAcl">
    INSERT INTO acl (principal_type, principal_id, node_id, grant_type)
    VALUES (#{principalType}, #{principalId}, #{nodeId}, #{grantType})
  </insert>

  <insert id="insertPublicFlag">
    INSERT INTO public_flag (node_id, mode) VALUES (#{nodeId}, #{mode})
  </insert>

</mapper>
```

`TeamMapper.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.worknote.acl.TeamMapper">
  <insert id="insertTeam">INSERT INTO team (id, name) VALUES (#{id}, #{name})</insert>
  <insert id="addMember">INSERT INTO team_member (team_id, user_id) VALUES (#{teamId}, #{userId})</insert>
  <select id="teamsOf" resultType="string">
    SELECT team_id FROM team_member WHERE user_id = #{userId}
  </select>
</mapper>
```

**계약:** `ancestorChain`은 존재하는 노드면 자기 자신 포함 ≥1 — `findAclForNodes`/`findPublicFlagsForNodes`의 IN-리스트는 비지 않는다 (`subtreeIds`와 같은 계약).

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests AclMapperTest` → PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(backend): ACL·팀 매퍼 + ancestorChain 재귀 CTE"`

---

### Task 9: AclResolver — 순수 해석 엔진 (스펙 §5)

**Files:**
- Create: `backend/src/main/java/com/worknote/acl/Access.java`, `AclResolver.java`
- Test: `backend/src/test/java/com/worknote/acl/AclResolverTest.java`

- [ ] **Step 1: 실패하는 테스트 작성** — 스펙 §5의 규칙을 그대로 케이스로:

```java
package com.worknote.acl;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class AclResolverTest {

    // chain은 항상 자신→루트 순
    private static final List<String> CHAIN = List.of("n1", "f2", "f1");

    @Test
    void nearestExplicitPicksClosestEntry() {
        // f1=read, f2=edit → n1 기준 가장 가까운 명시는 f2의 edit
        assertThat(AclResolver.nearestExplicit(CHAIN, Map.of("f1", "read", "f2", "edit")))
            .isEqualTo("edit");
        assertThat(AclResolver.nearestExplicit(CHAIN, Map.of("f1", "read"))).isEqualTo("read");
        assertThat(AclResolver.nearestExplicit(CHAIN, Map.of())).isNull();
    }

    @Test
    void carveOutDenyOnNoteBeatsAncestorEdit() {
        // 폴더 edit + 노트 deny → 노트에서 deny (한 주체 안 nearest-explicit)
        assertThat(AclResolver.nearestExplicit(CHAIN, Map.of("f1", "edit", "n1", "deny")))
            .isEqualTo("deny");
    }

    @Test
    void combineDenyWinsOverAnyAllow() {
        // 다중 주체: 개인 edit + 팀 deny → 차단 (deny-우선 합집합)
        assertThat(AclResolver.combine(Arrays.asList("edit", "deny"))).isEqualTo(Access.DENY);
        assertThat(AclResolver.combine(Arrays.asList("deny", null))).isEqualTo(Access.DENY);
    }

    @Test
    void combineUnionsAllowsMostGenerous() {
        assertThat(AclResolver.combine(Arrays.asList("read", "edit"))).isEqualTo(Access.EDIT);
        assertThat(AclResolver.combine(Arrays.asList("read", null))).isEqualTo(Access.READ);
        assertThat(AclResolver.combine(Arrays.asList((String) null, null))).isEqualTo(Access.NONE);
        assertThat(AclResolver.combine(List.of())).isEqualTo(Access.NONE);
    }

    @Test
    void publicReadNearestFlagWins() {
        // f1=public → n1 public 상속
        assertThat(AclResolver.publicRead(CHAIN, Map.of("f1", "public"))).isTrue();
        // n1=exclude가 더 가까움 → 차단 (새 노트 기본 제외)
        assertThat(AclResolver.publicRead(CHAIN, Map.of("f1", "public", "n1", "exclude"))).isFalse();
        // 플래그 없음 → default-deny
        assertThat(AclResolver.publicRead(CHAIN, Map.of())).isFalse();
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests AclResolverTest` → 컴파일 실패

- [ ] **Step 3: 구현**

```java
package com.worknote.acl;

/** 다중 주체 합산 결과. DENY는 절대(allow로 못 뒤집음 — 스펙 §5.1). */
public enum Access { NONE, READ, EDIT, DENY }
```

```java
package com.worknote.acl;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 스펙 §5 해석 알고리즘의 순수 부분 — DB 무관, 정적. 입출력은 PermissionService가 만든다. */
public final class AclResolver {

    /** 관리자 판정 기준: 역할 caps가 admin.* 5종 전부 포함 (스펙 §3.1 — 관리자는 ACL/deny 우회). */
    public static final Set<String> ADMIN_CAPS = Set.of(
        "admin.users", "admin.permissions", "admin.roles", "admin.security", "admin.audit");

    private AclResolver() {}

    /**
     * 한 주체의 nearest-explicit grant.
     * @param chain 노드 자신→루트 순 조상 체인
     * @param grantsByNode 그 주체의 nodeId→grant 엔트리
     * @return 가장 가까운 명시 grant, 없으면 null
     */
    public static String nearestExplicit(List<String> chain, Map<String, String> grantsByNode) {
        for (String nodeId : chain) {
            String grant = grantsByNode.get(nodeId);
            if (grant != null) {
                return grant;
            }
        }
        return null;
    }

    /** 다중 주체 deny-우선 합집합: deny 하나라도 있으면 DENY, 없으면 allow의 최대치. */
    public static Access combine(Collection<String> nearestGrants) {
        boolean edit = false;
        boolean read = false;
        for (String grant : nearestGrants) {
            if (grant == null) continue;
            switch (grant) {
                case "deny" -> { return Access.DENY; }
                case "edit" -> edit = true;
                case "read" -> read = true;
            }
        }
        return edit ? Access.EDIT : read ? Access.READ : Access.NONE;
    }

    /** 체인에서 가장 가까운 public_flag가 'public'이면 true ('exclude'가 더 가까우면 false). */
    public static boolean publicRead(List<String> chain, Map<String, String> flagsByNode) {
        for (String nodeId : chain) {
            String mode = flagsByNode.get(nodeId);
            if (mode != null) {
                return "public".equals(mode);
            }
        }
        return false;
    }
}
```

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests AclResolverTest` → PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(backend): AclResolver — nearest-explicit + deny-우선 합집합 + public 해석"`

---

### Task 10: PermissionService — DB 연동 해석기 + 트리 필터

**Files:**
- Create: `backend/src/main/java/com/worknote/acl/PermissionService.java`
- Test: `backend/src/test/java/com/worknote/acl/PermissionServiceTest.java`

- [ ] **Step 1: 실패하는 테스트 작성** — 스펙 시나리오 통합 검증:

```java
package com.worknote.acl;

import com.worknote.auth.UserRow;
import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared")
class PermissionServiceTest {
    @Autowired PermissionService perm;
    @Autowired AclMapper acl;
    @Autowired TeamMapper teams;
    @Autowired NodeMapper nodes;
    @Autowired JdbcTemplate jdbc;

    private static final UserRow OPERATOR = new UserRow("u1", "10001", null, "운영", "operator", "active", null);
    private static final UserRow VISITOR  = new UserRow("u2", "10002", null, "방문", "visitor", "active", null);
    private static final UserRow ADMIN    = new UserRow("u9", "90009", null, "관리", "admin", "active", null);

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM acl");
        jdbc.update("DELETE FROM public_flag");
        jdbc.update("DELETE FROM team_member");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM node");
        // 트리: f1 > f2 > n1,  f1 > n2
        node("f1", null, "folder");
        node("f2", "f1", "folder");
        node("n1", "f2", "note");
        node("n2", "f1", "note");
    }

    private void node(String id, String parentId, String type) {
        nodes.insert(new NodeRow(id, parentId, type, "N-" + id, 1, null, null, null, null));
    }

    @Test
    void defaultDenyWithoutAcl() {
        assertThat(perm.canRead(OPERATOR, "n1")).isFalse();
        assertThat(perm.canEdit(OPERATOR, "n1")).isFalse();
    }

    @Test
    void folderGrantInheritsToDescendants() {
        acl.insertAcl(new AclRow("user", "u1", "f1", "edit"));
        assertThat(perm.canRead(OPERATOR, "n1")).isTrue();
        assertThat(perm.canEdit(OPERATOR, "n1")).isTrue();
    }

    @Test
    void carveOutDenyOnNote() {
        acl.insertAcl(new AclRow("user", "u1", "f1", "edit"));
        acl.insertAcl(new AclRow("user", "u1", "n1", "deny"));
        assertThat(perm.canRead(OPERATOR, "n1")).isFalse();
        assertThat(perm.canRead(OPERATOR, "n2")).isTrue();   // 형제는 영향 없음
    }

    @Test
    void teamDenyBeatsPersonalGrant() {
        teams.insertTeam("t1", "결제팀");
        teams.addMember("t1", "u1");
        acl.insertAcl(new AclRow("user", "u1", "n1", "edit"));
        acl.insertAcl(new AclRow("team", "t1", "f1", "deny"));
        assertThat(perm.canRead(OPERATOR, "n1")).isFalse();  // deny-우선 합집합
    }

    @Test
    void allowUnionAcrossPrincipals() {
        teams.insertTeam("t1", "결제팀");
        teams.addMember("t1", "u1");
        acl.insertAcl(new AclRow("team", "t1", "f1", "read"));
        acl.insertAcl(new AclRow("user", "u1", "f2", "edit"));
        assertThat(perm.canEdit(OPERATOR, "n1")).isTrue();   // 합집합의 최대치
        assertThat(perm.canEdit(OPERATOR, "n2")).isFalse();  // f2 밖은 read뿐
        assertThat(perm.canRead(OPERATOR, "n2")).isTrue();
    }

    @Test
    void roleCapsCapAclGrant() {
        // 방문자(res.read만)에게 edit grant → read는 되지만 edit은 역할 상한에 캡
        acl.insertAcl(new AclRow("user", "u2", "f1", "edit"));
        assertThat(perm.canRead(VISITOR, "n1")).isTrue();
        assertThat(perm.canEdit(VISITOR, "n1")).isFalse();
    }

    @Test
    void publicFolderCascadeAndNoteExclude() {
        acl.insertPublicFlag("f1", "public");
        assertThat(perm.canRead(VISITOR, "n1")).isTrue();    // public cascade
        assertThat(perm.canEdit(VISITOR, "n1")).isFalse();   // public은 read 전용
        acl.insertPublicFlag("n1", "exclude");
        assertThat(perm.canRead(VISITOR, "n1")).isFalse();   // 노트 exclude 카브아웃
    }

    @Test
    void denyBeatsPublic() {
        acl.insertPublicFlag("f1", "public");
        acl.insertAcl(new AclRow("user", "u2", "n1", "deny"));
        assertThat(perm.canRead(VISITOR, "n1")).isFalse();
    }

    @Test
    void atAllGrantAppliesToEveryone() {
        acl.insertAcl(new AclRow("all", "@all", "f1", "read"));
        assertThat(perm.canRead(VISITOR, "n1")).isTrue();
        assertThat(perm.canRead(OPERATOR, "n2")).isTrue();
    }

    @Test
    void adminBypassesDeny() {
        acl.insertAcl(new AclRow("user", "u9", "f1", "deny"));
        assertThat(perm.canRead(ADMIN, "n1")).isTrue();
        assertThat(perm.canEdit(ADMIN, "n1")).isTrue();
        assertThat(perm.canEdit(ADMIN, null)).isTrue();      // 루트 edit = 관리자만
    }

    @Test
    void rootEditDeniedForNonAdmin() {
        assertThat(perm.canEdit(OPERATOR, null)).isFalse();
    }

    @Test
    void readableIdsFiltersTreeWithFolderStubs() {
        // 깊은 노트에만 grant → 조상 폴더는 스텁으로 포함
        acl.insertAcl(new AclRow("user", "u1", "n1", "read"));
        Set<String> ids = perm.readableIds(OPERATOR, nodes.findActive());
        assertThat(ids).containsExactlyInAnyOrder("n1", "f2", "f1");  // n2 제외
    }

    @Test
    void readableIdsRespectsDenyAndPublic() {
        acl.insertPublicFlag("f1", "public");
        acl.insertPublicFlag("n1", "exclude");
        acl.insertAcl(new AclRow("user", "u2", "n2", "deny"));
        Set<String> ids = perm.readableIds(VISITOR, nodes.findActive());
        assertThat(ids).containsExactlyInAnyOrder("f1", "f2");  // n1=exclude, n2=deny — f2는 public 폴더 자체
    }

    @Test
    void readableIdsEmptyForNoGrants() {
        assertThat(perm.readableIds(OPERATOR, nodes.findActive())).isEmpty();
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests PermissionServiceTest` → 컴파일 실패

- [ ] **Step 3: 구현**

```java
package com.worknote.acl;

import com.worknote.auth.RoleCaps;
import com.worknote.auth.UserRow;
import com.worknote.vault.NodeRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 스펙 §5 해석기 — DB에서 ACL·팀·public을 읽어 AclResolver로 합산.
 * local 모드(user=null)는 전체 허용, server 모드에서 user=null은 차단(방어).
 */
@Service
public class PermissionService {

    private final AclMapper acl;
    private final TeamMapper teams;
    private final RoleCaps roleCaps;
    private final boolean serverMode;

    public PermissionService(AclMapper acl, TeamMapper teams, RoleCaps roleCaps,
                             @Value("${worknote.mode:local}") String mode) {
        this.acl = acl;
        this.teams = teams;
        this.roleCaps = roleCaps;
        this.serverMode = "server".equals(mode);
    }

    public boolean serverMode() {
        return serverMode;
    }

    public boolean isAdmin(UserRow user) {
        return roleCaps.of(user.roleId()).containsAll(AclResolver.ADMIN_CAPS);
    }

    public boolean roleHas(UserRow user, String cap) {
        return roleCaps.of(user.roleId()).contains(cap);
    }

    /** read(U,N) — 스펙 §5.2. (공유 링크는 다음 계획) */
    @Transactional(readOnly = true)
    public boolean canRead(UserRow user, String nodeId) {
        if (user == null) return !serverMode;
        if (isAdmin(user)) return true;
        List<String> chain = acl.ancestorChain(nodeId);
        Access access = resolveAcl(user, chain);
        if (access == Access.DENY) return false;
        if (access == Access.READ || access == Access.EDIT) return roleHas(user, "res.read");
        Map<String, String> flags = flagMap(acl.findPublicFlagsForNodes(chain));
        return AclResolver.publicRead(chain, flags) && roleHas(user, "res.read");
    }

    /** edit(U,N) — 스펙 §5.2. nodeId=null은 루트 — 관리자 전용. */
    @Transactional(readOnly = true)
    public boolean canEdit(UserRow user, String nodeId) {
        if (user == null) return !serverMode;
        if (isAdmin(user)) return true;
        if (nodeId == null) return false;
        List<String> chain = acl.ancestorChain(nodeId);
        return resolveAcl(user, chain) == Access.EDIT && roleHas(user, "res.edit");
    }

    /** GET /tree 필터 — 읽을 수 있는 노드 + 그 조상 폴더 스텁. */
    @Transactional(readOnly = true)
    public Set<String> readableIds(UserRow user, List<NodeRow> activeNodes) {
        if (!roleHas(user, "res.read")) return Set.of();
        List<String> principals = principals(user);
        Map<String, Map<String, String>> aclByPrincipal = groupAcl(acl.findAllAcl());
        Map<String, String> flags = flagMap(acl.findAllPublicFlags());
        Map<String, List<NodeRow>> byParent = new LinkedHashMap<>();
        for (NodeRow row : activeNodes) {
            byParent.computeIfAbsent(row.parentId(), k -> new ArrayList<>()).add(row);
        }
        Set<String> out = new HashSet<>();
        Map<String, String> rootNearest = new HashMap<>();   // 주체별 nearest — 루트에선 전부 미설정
        for (NodeRow root : byParent.getOrDefault(null, List.of())) {
            walk(root, byParent, aclByPrincipal, flags, principals, rootNearest, null, out);
        }
        return out;
    }

    // ---- internal ----

    private List<String> principals(UserRow user) {
        List<String> keys = new ArrayList<>();
        keys.add("user:" + user.id());
        for (String teamId : teams.teamsOf(user.id())) {
            keys.add("team:" + teamId);
        }
        keys.add("all:@all");
        return keys;
    }

    private static String key(AclRow row) {
        return row.principalType() + ":" + row.principalId();
    }

    private static Map<String, Map<String, String>> groupAcl(List<AclRow> rows) {
        Map<String, Map<String, String>> out = new HashMap<>();
        for (AclRow row : rows) {
            out.computeIfAbsent(key(row), k -> new HashMap<>()).put(row.nodeId(), row.grantType());
        }
        return out;
    }

    private static Map<String, String> flagMap(List<PublicFlagRow> rows) {
        Map<String, String> out = new HashMap<>();
        for (PublicFlagRow row : rows) {
            out.put(row.nodeId(), row.mode());
        }
        return out;
    }

    private Access resolveAcl(UserRow user, List<String> chain) {
        Map<String, Map<String, String>> byPrincipal = groupAcl(acl.findAclForNodes(chain));
        List<String> nearest = new ArrayList<>();
        for (String principal : principals(user)) {
            nearest.add(AclResolver.nearestExplicit(chain, byPrincipal.getOrDefault(principal, Map.of())));
        }
        return AclResolver.combine(nearest);
    }

    /**
     * 트리 top-down walk — 주체별 nearest grant와 public 상태를 부모에서 물려받아 갱신.
     * @return 이 노드(또는 자손)가 포함됐는지 — 폴더 스텁 판단용
     */
    private boolean walk(NodeRow node, Map<String, List<NodeRow>> byParent,
                         Map<String, Map<String, String>> aclByPrincipal, Map<String, String> flags,
                         List<String> principals, Map<String, String> inheritedNearest,
                         Boolean inheritedPublic, Set<String> out) {
        Map<String, String> nearest = inheritedNearest;
        for (String principal : principals) {
            String grant = aclByPrincipal.getOrDefault(principal, Map.of()).get(node.id());
            // deny-sticky: 조상에서 deny된 주체는 더 깊은 allow로 재허용되지 않음 (스펙 §5.1)
            if (grant != null && !"deny".equals(nearest.get(principal))) {
                if (nearest == inheritedNearest) nearest = new HashMap<>(inheritedNearest);  // copy-on-write
                nearest.put(principal, grant);
            }
        }
        Boolean publicState = inheritedPublic;
        String flag = flags.get(node.id());
        if (flag != null) {
            publicState = "public".equals(flag);
        }
        Access access = AclResolver.combine(nearest.values());
        boolean selfReadable = access != Access.DENY
            && (access == Access.READ || access == Access.EDIT || Boolean.TRUE.equals(publicState));
        boolean anyChild = false;
        for (NodeRow child : byParent.getOrDefault(node.id(), List.of())) {
            anyChild |= walk(child, byParent, aclByPrincipal, flags, principals, nearest, publicState, out);
        }
        boolean include = selfReadable || anyChild;   // 폴더 스텁: 자손이 읽히면 경로(이름만) 노출
        if (include) {
            out.add(node.id());
        }
        return include;
    }
}
```

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests PermissionServiceTest` → PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(backend): PermissionService — 권한 해석 + 트리 필터(폴더 스텁)"`

---

### Task 11: AuditService

**Files:**
- Create: `backend/src/main/java/com/worknote/audit/AuditMapper.java`, `AuditService.java`
- Create: `backend/src/main/resources/mappers/AuditMapper.xml`
- Test: `backend/src/test/java/com/worknote/audit/AuditServiceTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.worknote.audit;

import com.worknote.auth.UserRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared")
class AuditServiceTest {
    @Autowired AuditService audit;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() { jdbc.update("DELETE FROM audit_log"); }

    @Test
    void logWritesRow() {
        UserRow user = new UserRow("u1", "10001", null, "홍길동", "operator", "active", null);
        audit.log(user, "node.create", "n-123", "10.0.0.5");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM audit_log");
        assertThat(row.get("who")).isEqualTo("10001");
        assertThat(row.get("act")).isEqualTo("node.create");
        assertThat(row.get("target")).isEqualTo("n-123");
        assertThat(row.get("ip")).isEqualTo("10.0.0.5");
        assertThat((String) row.get("at")).isNotBlank();
    }

    @Test
    void logSkipsNullUser() {
        audit.log(null, "node.create", "n-123", "10.0.0.5");   // local 모드 — 감사 생략
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class)).isZero();
    }

    @Test
    void logRawWritesWithoutUser() {
        audit.logRaw("10001", "login.fail", null, "10.0.0.5");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class)).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests AuditServiceTest` → 컴파일 실패

- [ ] **Step 3: 구현**

```java
package com.worknote.audit;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AuditMapper {
    void insert(@Param("at") String at, @Param("who") String who, @Param("act") String act,
                @Param("target") String target, @Param("ip") String ip);
}
```

`AuditMapper.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.worknote.audit.AuditMapper">
  <insert id="insert">
    INSERT INTO audit_log (at, who, act, target, ip) VALUES (#{at}, #{who}, #{act}, #{target}, #{ip})
  </insert>
</mapper>
```

```java
package com.worknote.audit;

import com.worknote.auth.UserRow;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 감사 로그 기록. local 모드(user=null) vault 감사는 생략, 인증 이벤트는 logRaw로 항상 기록. */
@Service
public class AuditService {

    private final AuditMapper mapper;
    private final Clock clock;

    public AuditService(AuditMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public void log(UserRow user, String act, String target, String ip) {
        if (user == null) return;
        logRaw(user.emp(), act, target, ip);
    }

    public void logRaw(String who, String act, String target, String ip) {
        mapper.insert(LocalDateTime.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            who, act, target, ip);
    }
}
```

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests AuditServiceTest` → PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(backend): AuditService — 감사 로그 기록"`

---

### Task 12: VaultGuard + VaultService 필터 인자

**Files:**
- Create: `backend/src/main/java/com/worknote/vault/VaultGuard.java`
- Modify: `backend/src/main/java/com/worknote/vault/VaultException.java` (FORBIDDEN 추가)
- Modify: `backend/src/main/java/com/worknote/ApiExceptionHandler.java` (FORBIDDEN→403)
- Modify: `backend/src/main/java/com/worknote/vault/VaultService.java` (tree/trashList 필터 오버로드)
- Test: `backend/src/test/java/com/worknote/vault/VaultGuardTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.worknote.vault;

import com.worknote.acl.AclMapper;
import com.worknote.acl.AclRow;
import com.worknote.auth.UserRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:phase2mem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
class VaultGuardTest {
    @Autowired VaultGuard guard;
    @Autowired VaultService svc;
    @Autowired NodeMapper nodes;
    @Autowired AclMapper acl;
    @Autowired JdbcTemplate jdbc;

    private static final UserRow OPERATOR = new UserRow("u1", "10001", null, "운영", "operator", "active", null);
    private static final UserRow ADMIN    = new UserRow("u9", "90009", null, "관리", "admin", "active", null);

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM acl");
        jdbc.update("DELETE FROM public_flag");
        jdbc.update("DELETE FROM tag");
        jdbc.update("DELETE FROM node");
        nodes.insert(new NodeRow("f1", null, "folder", "F1", 1, null, null, null, null));
        nodes.insert(new NodeRow("n1", "f1", "note", "N1", 1, "body", "2026-06-11T09:00:00", null, null));
    }

    @Test
    void requireEditThrowsForbiddenWithoutGrant() {
        assertThatThrownBy(() -> guard.requireEdit(OPERATOR, "n1"))
            .isInstanceOf(VaultException.class)
            .satisfies(e -> assertThat(((VaultException) e).status()).isEqualTo(VaultException.Status.FORBIDDEN));
        acl.insertAcl(new AclRow("user", "u1", "f1", "edit"));
        assertThatCode(() -> guard.requireEdit(OPERATOR, "n1")).doesNotThrowAnyException();
    }

    @Test
    void requireCreateNeedsCapAndParentEdit() {
        acl.insertAcl(new AclRow("user", "u1", "f1", "edit"));
        assertThatCode(() -> guard.requireCreate(OPERATOR, "f1")).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.requireCreate(OPERATOR, null)).isInstanceOf(VaultException.class);  // 루트=관리자만
        assertThatCode(() -> guard.requireCreate(ADMIN, null)).doesNotThrowAnyException();
    }

    @Test
    void requireMoveNeedsBothEnds() {
        nodes.insert(new NodeRow("f2", null, "folder", "F2", 2, null, null, null, null));
        acl.insertAcl(new AclRow("user", "u1", "f1", "edit"));
        assertThatThrownBy(() -> guard.requireMove(OPERATOR, "n1", "f2")).isInstanceOf(VaultException.class);
        acl.insertAcl(new AclRow("user", "u1", "f2", "edit"));
        assertThatCode(() -> guard.requireMove(OPERATOR, "n1", "f2")).doesNotThrowAnyException();
    }

    @Test
    void restoreOnlyByDeleterOrAdmin() {
        svc.trash("n1", "10001");
        assertThatCode(() -> guard.requireRestore(OPERATOR, "n1")).doesNotThrowAnyException();
        UserRow other = new UserRow("u2", "10002", null, "남", "operator", "active", null);
        assertThatThrownBy(() -> guard.requireRestore(other, "n1")).isInstanceOf(VaultException.class);
        assertThatCode(() -> guard.requireRestore(ADMIN, "n1")).doesNotThrowAnyException();
    }

    @Test
    void purgeAdminOnly() {
        assertThatThrownBy(() -> guard.requirePurge(OPERATOR)).isInstanceOf(VaultException.class);
        assertThatCode(() -> guard.requirePurge(ADMIN)).doesNotThrowAnyException();
    }

    @Test
    void treeFilterAndTrashFilter() {
        acl.insertAcl(new AclRow("user", "u1", "n1", "read"));
        Set<String> ids = guard.readableIds(OPERATOR);
        assertThat(ids).containsExactlyInAnyOrder("n1", "f1");
        assertThat(guard.readableIds(ADMIN)).isNull();        // null = 무필터
        assertThat(guard.trashFilter(OPERATOR)).isEqualTo("10001");
        assertThat(guard.trashFilter(ADMIN)).isNull();
    }

    @Test
    void vaultServiceTreeAcceptsFilter() {
        assertThat(svc.tree(Set.of("f1"))).hasSize(1);
        assertThat(svc.tree(Set.of("f1")).get(0).children()).isEmpty();   // n1 필터됨
        assertThat(svc.tree(null)).hasSize(1);                            // 무필터
        assertThat(svc.tree(null).get(0).children()).hasSize(1);
    }

    @Test
    void trashListFiltersByDeleter() {
        svc.trash("n1", "10001");
        assertThat(svc.trashList("10001")).hasSize(1);
        assertThat(svc.trashList("10002")).isEmpty();
        assertThat(svc.trashList(null)).hasSize(1);
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests VaultGuardTest` → 컴파일 실패

- [ ] **Step 3: 구현**

`VaultException.java` — enum에 `FORBIDDEN` 추가 + 팩토리:

```java
public enum Status { NOT_FOUND, CONFLICT, INVALID, FORBIDDEN }

public static VaultException forbidden(String message) {
    return new VaultException(Status.FORBIDDEN, message);
}
```

`ApiExceptionHandler.java` switch에 추가:

```java
case FORBIDDEN -> HttpStatus.FORBIDDEN;
```

`VaultService.java` 수정 — `tree()`/`trashList()`를 필터 오버로드로:

```java
@Transactional(readOnly = true)
public List<VaultNode> tree() {
    return tree(null);
}

/** readable=null이면 무필터(local/관리자). 필터 시 포함 노드만 조립 — 스텁 폴더는 readable에 이미 포함. */
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
    return assemble(null, byParent, tagsByNode);
}

@Transactional(readOnly = true)
public List<VaultNode> trashList() {
    return trashList(null);
}

/** deletedBy=null이면 전체(관리자/local), 아니면 본인 삭제분만 (스펙 §4.3). */
@Transactional(readOnly = true)
public List<VaultNode> trashList(String deletedBy) {
    List<VaultNode> out = new ArrayList<>();
    for (NodeRow row : mapper.findTrashRoots()) {
        if (deletedBy != null && !deletedBy.equals(row.deletedBy())) continue;
        if (NOTE.equals(row.type())) {
            out.add(new VaultNode(row.id(), NOTE, null, row.name(), null, null,
                List.of(), toDate(row.updatedAt()), null));
        } else {
            out.add(new VaultNode(row.id(), FOLDER, row.name(), null, null, null, null, null, null));
        }
    }
    return out;
}
```

(`java.util.Set` import 추가. 기존 인자 없는 메서드 본문은 오버로드 위임으로 교체.)

`VaultGuard.java`:

```java
package com.worknote.vault;

import com.worknote.acl.PermissionService;
import com.worknote.auth.UserRow;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Vault API 권한 가드 — 컨트롤러 앞단. local 모드(user=null)와 관리자는 전체 허용. */
@Component
public class VaultGuard {

    private final PermissionService perm;
    private final NodeMapper nodes;

    public VaultGuard(PermissionService perm, NodeMapper nodes) {
        this.perm = perm;
        this.nodes = nodes;
    }

    /** local 모드(user=null) 또는 관리자 — 검사 전부 통과. */
    private boolean bypass(UserRow user) {
        return user == null ? !perm.serverMode() : perm.isAdmin(user);
    }

    public void requireCreate(UserRow user, String parentId) {
        if (bypass(user)) return;
        if (!perm.roleHas(user, "res.create") || !perm.canEdit(user, parentId)) {
            throw VaultException.forbidden("생성 권한이 없습니다");
        }
    }

    public void requireEdit(UserRow user, String id) {
        if (bypass(user)) return;
        if (!perm.canEdit(user, id)) {
            throw VaultException.forbidden("편집 권한이 없습니다: " + id);
        }
    }

    /** move = edit(원본) ∧ edit(대상). 대상 null(루트)은 관리자만 — canEdit가 처리. */
    public void requireMove(UserRow user, String id, String newParentId) {
        requireEdit(user, id);
        if (bypass(user)) return;
        if (!perm.canEdit(user, newParentId)) {
            throw VaultException.forbidden("대상 폴더 편집 권한이 없습니다");
        }
    }

    public void requireDelete(UserRow user, String id) {
        if (bypass(user)) return;
        if (!perm.roleHas(user, "res.delete") || !perm.canEdit(user, id)) {
            throw VaultException.forbidden("삭제 권한이 없습니다: " + id);
        }
    }

    /** restore = 삭제자 본인 또는 관리자 (스펙 §4.3). */
    public void requireRestore(UserRow user, String id) {
        if (bypass(user)) return;
        NodeRow row = nodes.findById(id);
        if (row == null || !who(user).equals(row.deletedBy())) {
            throw VaultException.forbidden("복구 권한이 없습니다: " + id);
        }
    }

    /** purge = 관리자 전용 (스펙 §4.3). */
    public void requirePurge(UserRow user) {
        if (bypass(user)) return;
        throw VaultException.forbidden("영구 삭제는 관리자만 가능합니다");
    }

    /** GET /tree 필터 — null = 무필터(local/관리자). */
    public Set<String> readableIds(UserRow user) {
        if (bypass(user)) return null;
        return perm.readableIds(user, nodes.findActive());
    }

    /** 휴지통 가시성 — null = 전체(local/관리자), 아니면 본인(emp) 삭제분만. */
    public String trashFilter(UserRow user) {
        return bypass(user) ? null : who(user);
    }

    public String who(UserRow user) {
        return user == null ? "local" : user.emp();
    }
}
```

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests VaultGuardTest` → PASS, 전체 green (기존 tree()/trashList() 호출부 불변)
- [ ] **Step 5: Commit** — `git commit -m "feat(backend): VaultGuard + VaultService 권한 필터 인자"`

---

### Task 13: 컨트롤러 enforcement + 감사 연결

**Files:**
- Modify: `backend/src/main/java/com/worknote/vault/VaultController.java` (전면 개정)
- Modify: `backend/src/main/java/com/worknote/auth/AuthController.java` (감사 연결)
- Test: `backend/src/test/java/com/worknote/vault/VaultPermissionApiTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.worknote.vault;

import com.worknote.acl.AclMapper;
import com.worknote.acl.AclRow;
import com.worknote.auth.CredentialRow;
import com.worknote.auth.PasswordHasher;
import com.worknote.auth.UserMapper;
import com.worknote.auth.UserRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:phase2mem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
@AutoConfigureMockMvc
class VaultPermissionApiTest {
    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired NodeMapper nodes;
    @Autowired AclMapper acl;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM acl");
        jdbc.update("DELETE FROM public_flag");
        jdbc.update("DELETE FROM tag");
        jdbc.update("DELETE FROM node");
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        createUser("u1", "10001", "operator");
        createUser("u2", "20002", "visitor");
        // 트리: f1 > n1
        nodes.insert(new NodeRow("f1", null, "folder", "F1", 1, null, null, null, null));
        nodes.insert(new NodeRow("n1", "f1", "note", "N1", 1, "body", "2026-06-11T09:00:00", null, null));
    }

    private void createUser(String id, String emp, String roleId) {
        users.insert(new UserRow(id, emp, null, "이름-" + emp, roleId, "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow(id, salt, PasswordHasher.hash("pw-1234", salt)));
    }

    private MockHttpSession login(String emp, String pw) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"" + emp + "\",\"password\":\"" + pw + "\"}"))
            .andExpect(status().isOk());
        return session;
    }

    @Test
    void treeIsFilteredByPermission() throws Exception {
        acl.insertAcl(new AclRow("user", "u1", "n1", "read"));
        MockHttpSession session = login("10001", "pw-1234");
        mvc.perform(get("/api/tree").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].id").value("f1"))                  // 스텁 폴더
            .andExpect(jsonPath("$[0].children[0].id").value("n1"));
        MockHttpSession other = login("20002", "pw-1234");
        mvc.perform(get("/api/tree").session(other))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(0)));                       // grant 없음 — 빈 트리
    }

    @Test
    void createForbiddenWithoutParentEdit() throws Exception {
        MockHttpSession session = login("10001", "pw-1234");
        mvc.perform(post("/api/nodes").session(session).contentType(APPLICATION_JSON)
                .content("{\"id\":\"n2\",\"parentId\":\"f1\",\"type\":\"note\",\"name\":\"새 노트\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").exists());
        acl.insertAcl(new AclRow("user", "u1", "f1", "edit"));
        mvc.perform(post("/api/nodes").session(session).contentType(APPLICATION_JSON)
                .content("{\"id\":\"n2\",\"parentId\":\"f1\",\"type\":\"note\",\"name\":\"새 노트\"}"))
            .andExpect(status().isCreated());
    }

    @Test
    void visitorEditCappedByRole() throws Exception {
        acl.insertAcl(new AclRow("user", "u2", "f1", "edit"));   // grant는 있지만 역할 상한이 read
        MockHttpSession session = login("20002", "pw-1234");
        mvc.perform(patch("/api/nodes/n1").session(session).contentType(APPLICATION_JSON)
                .content("{\"content\":\"변경\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/tree").session(session))
            .andExpect(jsonPath("$[0].children[0].id").value("n1"));    // read는 됨
    }

    @Test
    void deleteRequiresCapAndAuditLogged() throws Exception {
        acl.insertAcl(new AclRow("user", "u1", "f1", "edit"));
        MockHttpSession session = login("10001", "pw-1234");
        mvc.perform(delete("/api/nodes/n1").session(session)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act = 'node.trash' AND who = '10001' AND target = 'n1'",
            Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT deleted_by FROM node WHERE id = 'n1'", String.class))
            .isEqualTo("10001");   // deleted_by = 사번
    }

    @Test
    void trashVisibilityAndRestorePolicy() throws Exception {
        acl.insertAcl(new AclRow("user", "u1", "f1", "edit"));
        MockHttpSession op = login("10001", "pw-1234");
        mvc.perform(delete("/api/nodes/n1").session(op)).andExpect(status().isNoContent());
        // 다른 사용자(방문자) — 휴지통 빈 목록 + 복구 403
        MockHttpSession visitor = login("20002", "pw-1234");
        mvc.perform(get("/api/trash").session(visitor))
            .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(post("/api/trash/n1/restore").session(visitor))
            .andExpect(status().isForbidden());
        // 삭제자 본인 — 목록 + 복구 가능
        mvc.perform(get("/api/trash").session(op)).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(post("/api/trash/n1/restore").session(op)).andExpect(status().isNoContent());
    }

    @Test
    void purgeIsAdminOnly() throws Exception {
        acl.insertAcl(new AclRow("user", "u1", "f1", "edit"));
        MockHttpSession op = login("10001", "pw-1234");
        mvc.perform(delete("/api/nodes/n1").session(op)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/trash/n1").session(op)).andExpect(status().isForbidden());
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(delete("/api/trash/n1").session(admin)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act = 'node.purge' AND who = 'admin'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    void loginAuditLogged() throws Exception {
        login("10001", "pw-1234");
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10001\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act = 'login.success' AND who = '10001'", Integer.class))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act = 'login.fail' AND who = '10001'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    void adminSeesEverything() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(get("/api/tree").session(admin))
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].children", hasSize(1)));
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests VaultPermissionApiTest` → FAIL (403이 안 나옴 — 가드 미적용)

- [ ] **Step 3: VaultController 전면 개정**

```java
package com.worknote.vault;

import com.worknote.audit.AuditService;
import com.worknote.auth.AuthFilter;
import com.worknote.auth.UserRow;
import com.worknote.vault.dto.CreateNodeRequest;
import com.worknote.vault.dto.MoveNodeRequest;
import com.worknote.vault.dto.UpdateNodeRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** vault REST API. 권한은 VaultGuard(앞단), 도메인 검증은 VaultService, HTTP 매핑은 ApiExceptionHandler. */
@RestController
@RequestMapping("/api")
public class VaultController {

    private final VaultService svc;
    private final VaultGuard guard;
    private final AuditService audit;

    public VaultController(VaultService svc, VaultGuard guard, AuditService audit) {
        this.svc = svc;
        this.guard = guard;
        this.audit = audit;
    }

    /** server 모드에선 AuthFilter가 적재한 사용자, local 모드는 null(무인증). */
    private static UserRow user(HttpServletRequest req) {
        return (UserRow) req.getAttribute(AuthFilter.CURRENT_USER);
    }

    @GetMapping("/tree")
    public List<VaultNode> tree(HttpServletRequest req) {
        return svc.tree(guard.readableIds(user(req)));
    }

    @PostMapping("/nodes")
    public ResponseEntity<VaultNode> create(@Valid @RequestBody CreateNodeRequest body, HttpServletRequest req) {
        UserRow user = user(req);
        guard.requireCreate(user, body.parentId());
        String id = body.id() != null ? body.id() : UUID.randomUUID().toString();
        VaultNode node = svc.create(id, body.parentId(), body.type(), body.name(), body.content());
        audit.log(user, "node.create", id, req.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(node);
    }

    @PatchMapping("/nodes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(@PathVariable String id, @RequestBody UpdateNodeRequest body, HttpServletRequest req) {
        guard.requireEdit(user(req), id);
        svc.update(id, body.name(), body.content(), body.tags());
        // PATCH는 1.5초 디바운스 고빈도 — 감사 제외 (스펙 §7 감사 목록에 편집 없음)
    }

    @PostMapping("/nodes/{id}/move")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void move(@PathVariable String id, @RequestBody MoveNodeRequest body, HttpServletRequest req) {
        UserRow user = user(req);
        guard.requireMove(user, id, body.parentId());
        svc.move(id, body.parentId());
        audit.log(user, "node.move", id, req.getRemoteAddr());
    }

    @DeleteMapping("/nodes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void trash(@PathVariable String id, HttpServletRequest req) {
        UserRow user = user(req);
        guard.requireDelete(user, id);
        svc.trash(id, guard.who(user));
        audit.log(user, "node.trash", id, req.getRemoteAddr());
    }

    @GetMapping("/trash")
    public List<VaultNode> trashList(HttpServletRequest req) {
        return svc.trashList(guard.trashFilter(user(req)));
    }

    @PostMapping("/trash/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restore(@PathVariable String id, HttpServletRequest req) {
        UserRow user = user(req);
        guard.requireRestore(user, id);
        svc.restore(id);
        audit.log(user, "node.restore", id, req.getRemoteAddr());
    }

    @DeleteMapping("/trash/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void purge(@PathVariable String id, HttpServletRequest req) {
        UserRow user = user(req);
        guard.requirePurge(user);
        svc.purge(id);
        audit.log(user, "node.purge", id, req.getRemoteAddr());
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
```

(기존 `LOCAL_USER` 상수는 `guard.who(null)`이 "local"을 돌려주므로 삭제.)

`AuthController` 감사 연결 — login/logout 메서드만 수정 (생성자에 `AuditService audit` 주입 추가):

```java
@PostMapping("/login")
public MeResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
    AuthService.AuthUser result;
    try {
        result = auth.login(req.emp(), req.password());
    } catch (AuthException e) {
        audit.logRaw(req.emp(), "login.fail", null, http.getRemoteAddr());
        throw e;
    }
    http.getSession(true).setAttribute(SESSION_USER, result.user().id());
    audit.logRaw(result.user().emp(), "login.success", null, http.getRemoteAddr());
    return toMe(result.user(), result.caps());
}

@PostMapping("/logout")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void logout(HttpServletRequest http) {
    HttpSession session = http.getSession(false);
    if (session != null) {
        UserRow user = (UserRow) http.getAttribute(AuthFilter.CURRENT_USER);
        audit.log(user, "logout", null, http.getRemoteAddr());
        session.invalidate();
    }
}
```

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests VaultPermissionApiTest` → PASS. **전체 `./gradlew test` green** — 기존 local 모드 컨트롤러 테스트(user=null → bypass)가 그대로 통과해야 한다.
- [ ] **Step 5: Commit** — `git commit -m "feat(backend): Vault API 권한 enforcement + 감사 로그 연결"`

---

### Task 14: 전체 회귀 + 문서 갱신 + jar 스모크

**Files:**
- Modify: `backend/README.md`, `CLAUDE.md` (루트)

- [ ] **Step 1: 전체 테스트** — `cd backend && ./gradlew test` → 전부 green (기존 31 + 신규 전부)

- [ ] **Step 2: local 모드 jar 스모크** (1단계 사용성 회귀):

```bash
cd frontend && pnpm build && cd ../backend && ./gradlew bootJar
WORKNOTE_DB=/tmp/wn-smoke.db java -jar build/libs/worknote-0.1.0.jar &
sleep 5
curl -s localhost:8080/api/health          # {"status":"ok"}
curl -s localhost:8080/api/tree            # 200 — 무인증 (local 모드)
kill %1; rm /tmp/wn-smoke.db
```

- [ ] **Step 3: server 모드 jar 스모크**:

```bash
WORKNOTE_DB=/tmp/wn-srv.db WORKNOTE_MODE=server WORKNOTE_ADMIN_PASSWORD=smoke-pw-1 \
  java -jar build/libs/worknote-0.1.0.jar &
sleep 5
curl -s localhost:8080/api/tree                          # 401 {"error":...}
curl -s -c /tmp/wn-cookie.txt -H 'Content-Type: application/json' \
  -d '{"emp":"admin","password":"smoke-pw-1"}' localhost:8080/api/auth/login   # 200 me
curl -s -b /tmp/wn-cookie.txt localhost:8080/api/tree    # 200 []
kill %1; rm /tmp/wn-srv.db /tmp/wn-cookie.txt
```

추가: `WORKNOTE_MODE=server`만 주고(비밀번호 없이) 기동하면 fail-fast로 죽는지 확인.

- [ ] **Step 4: 문서 갱신**
  - `backend/README.md`: 인증 API 표(/api/auth/*), worknote.mode 스위치, server 모드 기동법(`WORKNOTE_MODE`, `WORKNOTE_ADMIN_PASSWORD`), 권한 모델 적용 현황(엔드포인트별 요구 권한 표), 설계 결정 #1~#14 기록, 다음 계획 이월 목록(공유 링크·관리자 API·가입 승인·프런트 연동·30일 purge) 갱신.
  - 루트 `CLAUDE.md`: 백엔드 설명에 "2단계 코어(인증+권한 엔진) 완료 — `worknote.mode`로 스위치, 프런트 연동·관리자 API는 미구현" 반영.

- [ ] **Step 5: Commit** — `git add -A && git commit -m "docs: 2단계 코어(인증+권한) 문서 갱신 + 스모크 검증"`

---

## Self-Review 체크 결과

- **스펙 커버리지:** §5 해석(nearest-explicit·deny-우선 합집합·public·역할 상한 캡·default-deny) → Task 9·10. §4.3 라이프사이클 권한(move 양끝 edit·휴지통 가시성·restore·purge) → Task 12·13. §7 중 감사·세션 정책 → Task 11·13·5. §7 가입 승인·§6 공유 링크·§3 커스텀 역할 CRUD는 **명시적 이월**(범위 결정).
- **타입 일관성:** `UserRow`(7필드)·`AclRow`(4필드)·`Access`·`AuthService.AuthUser`가 태스크 간 동일 시그니처로 사용됨. `VaultException.Status.FORBIDDEN`은 Task 12에서 추가되고 Task 13 테스트가 사용.
- **순서 의존성:** Task 5가 `AuthFilter.CURRENT_USER` 상수를 선생성(스텁 필터) — Task 6이 본문 완성. Task 12(가드)가 Task 10(PermissionService)·Task 11(Audit과 무관) 뒤, Task 13이 12·11 뒤.
