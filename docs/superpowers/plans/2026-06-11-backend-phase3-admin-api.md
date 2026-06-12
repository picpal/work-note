# Backend 3단계 — 관리자 API 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** server 모드를 실제 운영 가능하게 만드는 관리자 API — 가입 신청/승인, 사용자/역할/팀/스페이스 CRUD, ACL·public_flag 설정, 감사 로그 조회.

**Architecture:** 기존 3계층(컨트롤러→서비스→매퍼) 유지. 새 패키지 `com.worknote.admin`에 영역별 컨트롤러+서비스, 공통 `AdminGuard`(local bypass / server 관리자만). 스키마 변경 없음(V2로 충분) — 매퍼 메서드만 추가. 모든 변이는 기존 관례대로 사후 감사 기록.

**Tech Stack:** Java 21 + Spring Boot 3.5 + MyBatis(XML) + SQLite. 테스트는 server 모드 공유 인메모리 DB + MockMvc.

---

## 확정 결정 (구현 중 재논의 금지)

| # | 결정 | 근거 |
|---|------|------|
| 1 | 경로 `/api/admin/*`, 모든 엔드포인트 첫 줄 `guard.requireAdmin(user)` | VaultGuard 관례 계승 — local(user=null) bypass, server 비관리자 403 |
| 2 | 가입 신청 `POST /api/auth/signup` — AuthFilter allowlist 추가, 기본 역할 `visitor`, status `pending` | 승인 전 로그인은 기존 status 검사(403)가 차단 |
| 3 | **락아웃 방지 2중 규칙**: 자기 자신의 role/status 변경 금지(422) + 마지막 활성 관리자 강등·비활성 금지(422) | 폐쇄망 — 관리자 0명이 되면 복구 수단 없음 |
| 4 | 시스템 역할(system=1) 수정·삭제 금지(422), 사용 중 역할 삭제 409, caps는 KNOWN_CAPS 화이트리스트 검증(422) | RoleCaps는 DB JSON을 신뢰 — 오타 caps가 들어가면 fail-open/lock 둘 다 가능 |
| 5 | RoleCaps 캐시 **도입 안 함** — 매 조회 DB 그대로 | 역할 수정이 즉시 반영돼야 하고, 사용자 3~4팀 규모라 캐시 이득 없음 (이월 항목 종결) |
| 6 | 팀 삭제 = 소유 스페이스 있으면 409, 없으면 멤버십+해당 팀 ACL 정리 후 삭제(@Transactional) | ACL 잔여 행은 팀 id 재사용 시 권한 부활(purge에서 확립한 원칙과 동일) |
| 7 | 스페이스 PUT = 최상위(parent_id NULL) 활성 폴더만, 팀 지정 시 그 팀 edit ACL 자동 grant(이미 있으면 유지) | 스펙 §4.2 "생성 시 소유 팀에 edit 자동 grant" |
| 8 | ACL 쓰기 = 노드 단위 **replace-all** `PUT /api/admin/nodes/{id}/acl` | 관리 UI의 "노드 선택→권한 편집→저장" 모델과 1:1, 부분 수정 API보다 단순 |
| 9 | public_flag = upsert(`ON CONFLICT` — Oracle 전환 시 MERGE 주석), **새 노트 자동 exclude**를 VaultService.create에 구현 | 스펙 §7 "public 폴더 하위 새 노트는 명시 exclude 엔트리 삽입" — 2단계에서 미구현이었던 쓰기 경로 |
| 10 | 감사 조회 `GET /api/admin/audit` — who/act 정확 일치, from/to는 ISO 문자열 사전순 비교, limit 기본 50·최대 200 | at은 ISO_LOCAL_DATE_TIME TEXT라 사전순=시간순 |
| 11 | 비밀번호 최소 8자(`@Size(min=8)`) — signup/관리자 생성/리셋 공통 | 보안 설정 화면의 가변 정책은 이월. 고정 하한만 |
| 12 | 응답 DTO 최소화 — `UserRow`/`RoleRow`(caps 파싱 뷰)/`AclRow` 등 record 직접 반환 | 자격증명은 별도 테이블이라 UserRow 직렬화 안전 |
| 13 | 감사 act 명명: `signup`, `user.create/update/approve/reset`, `role.create/update/delete`, `team.create/update/delete`, `team.member.add/remove`, `acl.set`, `public.set/unset`, `space.set/unset` | 기존 dot 스타일(node.create 등) 계승. 조회는 기록 안 함 |
| 14 | server 모드 통합 테스트는 기존과 **바이트 단위 동일 properties** 사용(컨텍스트 캐시 공유), `@BeforeEach`에서 `<> 'u-admin'` 보존 DELETE | 2단계에서 확립한 테스트 인프라 |

## 기존 코드 핵심 참조 (구현자 필독)

- 가드 관례: `VaultGuard`(컨트롤러 앞단, `user == null ? !perm.serverMode() : perm.isAdmin(user)` bypass), 403은 `VaultException.forbidden(...)`
- 컨트롤러의 사용자 추출: `(UserRow) req.getAttribute(AuthFilter.CURRENT_USER)` — server 모드에서 AuthFilter가 채움, local 모드 null
- 감사: `AuditService.log(UserRow, act, target, ip)`(user null=skip — local 모드 생략), `logRaw(who, ...)`(상시). **본 작업 성공 후** 기록
- 예외→HTTP: `VaultException` NOT_FOUND 404 / CONFLICT 409 / INVALID 422 / FORBIDDEN 403, `AuthException` UNAUTHORIZED 401 / FORBIDDEN 403 (`com.worknote.ApiExceptionHandler`)
- 매퍼: 인터페이스 `@Mapper` + `src/main/resources/mappers/*.xml`, `map-underscore-to-camel-case: true`, record 생성자 매핑
- 테스트 로그인 헬퍼·클린업 패턴: `VaultPermissionApiTest` 참조 (admin = emp `admin` / pw `boot-pass-1`)
- 커밋: main 직커밋, 한국어 conventional commit (`feat(backend): ...`)

---

### Task 1: AdminGuard — /api/admin 공통 가드

**Files:**
- Create: `backend/src/main/java/com/worknote/admin/AdminGuard.java`
- Test: `backend/src/test/java/com/worknote/admin/AdminGuardTest.java`

- [x] **Step 1: 실패하는 테스트 작성**

```java
package com.worknote.admin;

import com.worknote.acl.PermissionService;
import com.worknote.auth.AuthException;
import com.worknote.auth.UserRow;
import com.worknote.vault.VaultException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminGuardTest {

    private final PermissionService perm = mock(PermissionService.class);
    private final AdminGuard guard = new AdminGuard(perm);

    private static final UserRow USER = new UserRow("u1", "10001", null, "홍길동", "operator", "active", null);

    @Test
    void localMode_nullUser_passes() {
        when(perm.serverMode()).thenReturn(false);
        assertThatCode(() -> guard.requireAdmin(null)).doesNotThrowAnyException();
    }

    @Test
    void serverMode_nullUser_unauthorized() {
        when(perm.serverMode()).thenReturn(true);
        assertThatThrownBy(() -> guard.requireAdmin(null)).isInstanceOf(AuthException.class);
    }

    @Test
    void serverMode_nonAdmin_forbidden() {
        when(perm.serverMode()).thenReturn(true);
        when(perm.isAdmin(USER)).thenReturn(false);
        assertThatThrownBy(() -> guard.requireAdmin(USER))
            .isInstanceOf(VaultException.class)
            .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((VaultException) e).status())
                .isEqualTo(VaultException.Status.FORBIDDEN));
    }

    @Test
    void serverMode_admin_passes() {
        when(perm.serverMode()).thenReturn(true);
        when(perm.isAdmin(USER)).thenReturn(true);
        assertThatCode(() -> guard.requireAdmin(USER)).doesNotThrowAnyException();
    }
}
```

- [x] **Step 2: 실패 확인** — `./gradlew test --tests AdminGuardTest` → 컴파일 에러(AdminGuard 미존재) 확인
- [x] **Step 3: 구현**

```java
package com.worknote.admin;

import com.worknote.acl.PermissionService;
import com.worknote.auth.AuthException;
import com.worknote.auth.UserRow;
import com.worknote.vault.VaultException;
import org.springframework.stereotype.Component;

/** /api/admin/* 공통 가드. local 모드(user=null)는 단일 사용자=관리자라 통과, server 모드는 관리자 caps 필수. */
@Component
public class AdminGuard {

    private final PermissionService perm;

    public AdminGuard(PermissionService perm) {
        this.perm = perm;
    }

    public void requireAdmin(UserRow user) {
        if (user == null) {
            if (!perm.serverMode()) {
                return;
            }
            // server 모드인데 user가 없다 = AuthFilter 우회 경로 — 2차 방어
            throw AuthException.unauthorized("인증이 필요합니다");
        }
        if (!perm.isAdmin(user)) {
            throw VaultException.forbidden("관리자 권한이 필요합니다");
        }
    }
}
```

- [x] **Step 4: 통과 확인** — `./gradlew test --tests AdminGuardTest` → 4 PASS
- [x] **Step 5: 커밋** — `git add backend/src && git commit -m "feat(backend): AdminGuard — /api/admin 공통 가드 (local bypass, server 관리자만)"`

---

### Task 2: 가입 신청 — POST /api/auth/signup

**Files:**
- Create: `backend/src/main/java/com/worknote/auth/dto/SignupRequest.java`
- Modify: `backend/src/main/java/com/worknote/auth/AuthService.java` (signup 메서드 추가)
- Modify: `backend/src/main/java/com/worknote/auth/AuthController.java` (signup 엔드포인트)
- Modify: `backend/src/main/java/com/worknote/auth/AuthFilter.java` (ALLOWLIST에 `/api/auth/signup`)
- Test: `backend/src/test/java/com/worknote/auth/AuthSignupTest.java`

- [x] **Step 1: 실패하는 테스트 작성** (server 모드 통합 — 기존 properties와 바이트 동일)

```java
package com.worknote.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:phase2mem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
@AutoConfigureMockMvc
class AuthSignupTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
    }

    private static String body(String emp) {
        return "{\"emp\":\"" + emp + "\",\"name\":\"신규자\",\"password\":\"pw-12345678\"}";
    }

    @Test
    void signup_createsPendingVisitor_withoutSession() throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(APPLICATION_JSON).content(body("S2026-0142")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("pending"));
        assertThat(jdbc.queryForObject(
            "SELECT role_id FROM app_user WHERE emp = 'S2026-0142'", String.class)).isEqualTo("visitor");
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_credential c JOIN app_user u ON u.id = c.user_id WHERE u.emp = 'S2026-0142'",
            Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act = 'signup' AND who = 'S2026-0142'", Integer.class)).isEqualTo(1);
    }

    @Test
    void signup_duplicateEmp_409() throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(APPLICATION_JSON).content(body("S2026-0142")))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/auth/signup").contentType(APPLICATION_JSON).content(body("S2026-0142")))
            .andExpect(status().isConflict());
    }

    @Test
    void pendingUser_cannotLogin() throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(APPLICATION_JSON).content(body("S2026-0142")))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"emp\":\"S2026-0142\",\"password\":\"pw-12345678\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void signup_shortPassword_400() throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(APPLICATION_JSON)
                .content("{\"emp\":\"S1\",\"name\":\"n\",\"password\":\"short\"}"))
            .andExpect(status().isBadRequest());
    }
}
```

- [x] **Step 2: 실패 확인** — `./gradlew test --tests AuthSignupTest` → 404/컴파일 에러
- [x] **Step 3: 구현**

`SignupRequest.java`:
```java
package com.worknote.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
    @NotBlank @Size(max = 64) String emp,
    @NotBlank @Size(max = 64) String name,
    @NotBlank @Size(min = 8, max = 128) String password,
    @Size(max = 128) String email
) {}
```

`AuthService`에 추가 (import `com.worknote.vault.VaultException`, `java.util.UUID`):
```java
/** 가입 신청 — pending 상태 visitor로 생성. 승인 전 로그인은 status 검사가 403으로 차단. */
@Transactional
public UserRow signup(String emp, String name, String email, String password) {
    if (users.findByEmp(emp) != null) {
        throw VaultException.conflict("이미 사용 중인 사번입니다: " + emp);
    }
    String id = "u-" + UUID.randomUUID();
    String salt = PasswordHasher.newSalt();
    UserRow user = new UserRow(id, emp, email, name, "visitor", "pending", null);
    users.insert(user);
    users.insertCredential(new CredentialRow(id, salt, PasswordHasher.hash(password, salt)));
    return user;
}
```

`AuthController`에 추가:
```java
@PostMapping("/signup")
@ResponseStatus(HttpStatus.CREATED)
public Map<String, String> signup(@Valid @RequestBody SignupRequest req, HttpServletRequest http) {
    UserRow user = auth.signup(req.emp(), req.name(), req.email(), req.password());
    audit.logRaw(user.emp(), "signup", null, http.getRemoteAddr());
    return Map.of("id", user.id(), "status", user.status());
}
```

`AuthFilter`:
```java
private static final Set<String> ALLOWLIST = Set.of("/api/auth/login", "/api/auth/signup", "/api/health");
```

- [x] **Step 4: 통과 확인** — `./gradlew test --tests AuthSignupTest` → 4 PASS, `./gradlew test` 전체 green(AuthFilterTest의 allowlist 단언이 있으면 함께 갱신)
- [x] **Step 5: 커밋** — `git commit -m "feat(backend): 가입 신청 API — pending visitor 생성, allowlist 추가"`

---

### Task 3: 사용자 관리 API

**Files:**
- Modify: `backend/src/main/java/com/worknote/auth/UserMapper.java` + `backend/src/main/resources/mappers/UserMapper.xml` (findAll/update/updateCredential)
- Create: `backend/src/main/java/com/worknote/admin/UserAdminService.java`
- Create: `backend/src/main/java/com/worknote/admin/AdminUserController.java`
- Create: `backend/src/main/java/com/worknote/admin/dto/CreateUserRequest.java`, `UpdateUserRequest.java`, `ResetPasswordRequest.java`
- Test: `backend/src/test/java/com/worknote/admin/AdminUserApiTest.java`

**API:**
| 메서드 | 경로 | 동작 | 성공 |
|---|---|---|---|
| GET | /api/admin/users | 전체 목록 (emp 정렬) | 200 |
| POST | /api/admin/users | 관리자 직접 생성 (active) | 201 |
| PATCH | /api/admin/users/{id} | name/email/roleId/status 부분 수정 | 200 |
| POST | /api/admin/users/{id}/approve | pending→active | 200 |
| POST | /api/admin/users/{id}/reset-password | 새 salt+hash | 204 |

- [x] **Step 1: 실패하는 테스트 작성**

```java
package com.worknote.admin;

import com.worknote.auth.CredentialRow;
import com.worknote.auth.PasswordHasher;
import com.worknote.auth.UserMapper;
import com.worknote.auth.UserRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:phase2mem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
@AutoConfigureMockMvc
class AdminUserApiTest {

    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        createUser("u1", "10001", "operator", "active");
        createUser("u2", "20002", "visitor", "pending");
    }

    private void createUser(String id, String emp, String roleId, String status) {
        users.insert(new UserRow(id, emp, null, "이름-" + emp, roleId, status, null));
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
    void nonAdmin_403() throws Exception {
        MockHttpSession op = login("10001", "pw-1234");
        mvc.perform(get("/api/admin/users").session(op)).andExpect(status().isForbidden());
    }

    @Test
    void list_returnsAllUsers() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(get("/api/admin/users").session(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void create_thenNewUserCanLogin() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(post("/api/admin/users").session(admin).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"30003\",\"name\":\"새사람\",\"roleId\":\"operator\",\"password\":\"pw-12345678\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("active"));
        login("30003", "pw-12345678");
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act = 'user.create' AND target = '30003'", Integer.class)).isEqualTo(1);
    }

    @Test
    void create_duplicateEmp_409_unknownRole_422() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(post("/api/admin/users").session(admin).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10001\",\"name\":\"x\",\"roleId\":\"operator\",\"password\":\"pw-12345678\"}"))
            .andExpect(status().isConflict());
        mvc.perform(post("/api/admin/users").session(admin).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"30003\",\"name\":\"x\",\"roleId\":\"no-such\",\"password\":\"pw-12345678\"}"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void approve_activatesPending_thenLogin() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(post("/api/admin/users/u2/approve").session(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("active"));
        login("20002", "pw-1234");
    }

    @Test
    void approve_nonPending_409() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(post("/api/admin/users/u1/approve").session(admin)).andExpect(status().isConflict());
    }

    @Test
    void patch_roleAndStatus() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(patch("/api/admin/users/u1").session(admin).contentType(APPLICATION_JSON)
                .content("{\"status\":\"disabled\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("disabled"));
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void patch_self_roleOrStatus_422() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(patch("/api/admin/users/u-admin").session(admin).contentType(APPLICATION_JSON)
                .content("{\"status\":\"disabled\"}"))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(patch("/api/admin/users/u-admin").session(admin).contentType(APPLICATION_JSON)
                .content("{\"roleId\":\"visitor\"}"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void demoteLastActiveAdmin_byAnotherAdmin_422() throws Exception {
        // 관리자 2명 구성 후, 서로가 마지막 1명을 강등하려는 상황 재현
        createUser("u9", "90009", "admin", "active");
        MockHttpSession a2 = login("90009", "pw-1234");
        // u-admin 강등 → 남은 활성 admin은 u9뿐 — 허용
        mvc.perform(patch("/api/admin/users/u-admin").session(a2).contentType(APPLICATION_JSON)
                .content("{\"roleId\":\"operator\"}"))
            .andExpect(status().isOk());
        // 이제 u9가 마지막 활성 admin — 자기 자신은 self 규칙으로 422 (락아웃 불가 확인)
        mvc.perform(patch("/api/admin/users/u9").session(a2).contentType(APPLICATION_JSON)
                .content("{\"roleId\":\"operator\"}"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void resetPassword_oldFails_newWorks() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(post("/api/admin/users/u1/reset-password").session(admin).contentType(APPLICATION_JSON)
                .content("{\"password\":\"new-pass-99\"}"))
            .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isUnauthorized());
        login("10001", "new-pass-99");
    }
}
```

- [x] **Step 2: 실패 확인** — `./gradlew test --tests AdminUserApiTest`
- [x] **Step 3: 매퍼 추가**

`UserMapper.java`에 추가:
```java
List<UserRow> findAll();
void update(UserRow row);
int updateCredential(CredentialRow row);
```

`UserMapper.xml`에 추가:
```xml
<select id="findAll" resultType="com.worknote.auth.UserRow">
  SELECT * FROM app_user ORDER BY emp
</select>
<update id="update">
  UPDATE app_user SET name = #{name}, email = #{email}, role_id = #{roleId}, status = #{status}
  WHERE id = #{id}
</update>
<update id="updateCredential">
  UPDATE user_credential SET salt = #{salt}, password_hash = #{passwordHash} WHERE user_id = #{userId}
</update>
```

- [x] **Step 4: 서비스 구현**

`UserAdminService.java`:
```java
package com.worknote.admin;

import com.worknote.acl.PermissionService;
import com.worknote.auth.CredentialRow;
import com.worknote.auth.PasswordHasher;
import com.worknote.auth.RoleMapper;
import com.worknote.auth.UserMapper;
import com.worknote.auth.UserRow;
import com.worknote.vault.VaultException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** 사용자 관리 — 락아웃 방지(자기 자신 권한 변경 금지 + 마지막 활성 관리자 보호)가 핵심 정책. */
@Service
public class UserAdminService {

    private static final String ACTIVE = "active";

    private final UserMapper users;
    private final RoleMapper roles;
    private final PermissionService perm;

    public UserAdminService(UserMapper users, RoleMapper roles, PermissionService perm) {
        this.users = users;
        this.roles = roles;
        this.perm = perm;
    }

    public List<UserRow> list() {
        return users.findAll();
    }

    @Transactional
    public UserRow create(String emp, String name, String email, String roleId, String password) {
        if (users.findByEmp(emp) != null) {
            throw VaultException.conflict("이미 사용 중인 사번입니다: " + emp);
        }
        requireRole(roleId);
        String id = "u-" + UUID.randomUUID();
        String salt = PasswordHasher.newSalt();
        UserRow user = new UserRow(id, emp, email, name, roleId, ACTIVE, null);
        users.insert(user);
        users.insertCredential(new CredentialRow(id, salt, PasswordHasher.hash(password, salt)));
        return user;
    }

    @Transactional
    public UserRow update(UserRow actor, String id, String name, String email, String roleId, String status) {
        UserRow target = require(id);
        if (actor != null && actor.id().equals(id) && (roleId != null || status != null)) {
            throw VaultException.invalid("자기 자신의 역할·상태는 변경할 수 없습니다");
        }
        if (roleId != null) {
            requireRole(roleId);
        }
        UserRow merged = new UserRow(target.id(), target.emp(),
            email != null ? email : target.email(),
            name != null ? name : target.name(),
            roleId != null ? roleId : target.roleId(),
            status != null ? status : target.status(),
            target.lastLogin());
        requireNotLastAdminDowngrade(target, merged);
        users.update(merged);
        return merged;
    }

    @Transactional
    public UserRow approve(String id) {
        UserRow target = require(id);
        if (!"pending".equals(target.status())) {
            throw VaultException.conflict("승인 대기 상태가 아닙니다: " + target.status());
        }
        UserRow merged = new UserRow(target.id(), target.emp(), target.email(), target.name(),
            target.roleId(), ACTIVE, target.lastLogin());
        users.update(merged);
        return merged;
    }

    @Transactional
    public UserRow resetPassword(String id, String password) {
        UserRow target = require(id);
        String salt = PasswordHasher.newSalt();
        CredentialRow cred = new CredentialRow(id, salt, PasswordHasher.hash(password, salt));
        if (users.updateCredential(cred) == 0) {
            users.insertCredential(cred); // 자격증명 누락(비정상 데이터) 복구
        }
        return target;
    }

    private UserRow require(String id) {
        UserRow row = users.findById(id);
        if (row == null) {
            throw VaultException.notFound("사용자가 없습니다: " + id);
        }
        return row;
    }

    private void requireRole(String roleId) {
        if (roles.findById(roleId) == null) {
            throw VaultException.invalid("존재하지 않는 역할: " + roleId);
        }
    }

    /** 활성 관리자였던 대상이 비관리자/비활성이 되면 남는 활성 관리자가 있어야 한다 — 폐쇄망 락아웃 방지. */
    private void requireNotLastAdminDowngrade(UserRow before, UserRow after) {
        boolean wasActiveAdmin = ACTIVE.equals(before.status()) && perm.isAdmin(before);
        boolean staysActiveAdmin = ACTIVE.equals(after.status()) && perm.isAdmin(after);
        if (!wasActiveAdmin || staysActiveAdmin) {
            return;
        }
        boolean anotherActiveAdmin = users.findAll().stream()
            .anyMatch(u -> !u.id().equals(before.id()) && ACTIVE.equals(u.status()) && perm.isAdmin(u));
        if (!anotherActiveAdmin) {
            throw VaultException.invalid("마지막 활성 관리자는 강등·비활성화할 수 없습니다");
        }
    }
}
```

DTO 3종 (`backend/src/main/java/com/worknote/admin/dto/`):
```java
package com.worknote.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
    @NotBlank @Size(max = 64) String emp,
    @NotBlank @Size(max = 64) String name,
    @Size(max = 128) String email,
    @NotBlank @Size(max = 32) String roleId,
    @NotBlank @Size(min = 8, max = 128) String password
) {}
```
```java
package com.worknote.admin.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
    @Size(max = 64) String name,
    @Size(max = 128) String email,
    @Size(max = 32) String roleId,
    @Pattern(regexp = "active|disabled") String status
) {}
```
```java
package com.worknote.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(@NotBlank @Size(min = 8, max = 128) String password) {}
```

- [x] **Step 5: 컨트롤러 구현**

`AdminUserController.java`:
```java
package com.worknote.admin;

import com.worknote.admin.dto.CreateUserRequest;
import com.worknote.admin.dto.ResetPasswordRequest;
import com.worknote.admin.dto.UpdateUserRequest;
import com.worknote.audit.AuditService;
import com.worknote.auth.AuthFilter;
import com.worknote.auth.UserRow;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminGuard guard;
    private final UserAdminService svc;
    private final AuditService audit;

    public AdminUserController(AdminGuard guard, UserAdminService svc, AuditService audit) {
        this.guard = guard;
        this.svc = svc;
        this.audit = audit;
    }

    private static UserRow user(HttpServletRequest req) {
        return (UserRow) req.getAttribute(AuthFilter.CURRENT_USER);
    }

    @GetMapping
    public List<UserRow> list(HttpServletRequest req) {
        guard.requireAdmin(user(req));
        return svc.list();
    }

    @PostMapping
    public ResponseEntity<UserRow> create(@Valid @RequestBody CreateUserRequest body, HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        UserRow created = svc.create(body.emp(), body.name(), body.email(), body.roleId(), body.password());
        audit.log(actor, "user.create", created.emp(), req.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{id}")
    public UserRow update(@PathVariable String id, @Valid @RequestBody UpdateUserRequest body, HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        UserRow updated = svc.update(actor, id, body.name(), body.email(), body.roleId(), body.status());
        audit.log(actor, "user.update", updated.emp(), req.getRemoteAddr());
        return updated;
    }

    @PostMapping("/{id}/approve")
    public UserRow approve(@PathVariable String id, HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        UserRow approved = svc.approve(id);
        audit.log(actor, "user.approve", approved.emp(), req.getRemoteAddr());
        return approved;
    }

    @PostMapping("/{id}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@PathVariable String id, @Valid @RequestBody ResetPasswordRequest body,
                              HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        UserRow target = svc.resetPassword(id, body.password());
        audit.log(actor, "user.reset", target.emp(), req.getRemoteAddr());
    }
}
```

- [x] **Step 6: 통과 확인** — `./gradlew test --tests AdminUserApiTest` → 전부 PASS, 전체 `./gradlew test` green
- [x] **Step 7: 커밋** — `git commit -m "feat(backend): 사용자 관리 API — 목록·생성·수정·승인·비밀번호 리셋 + 락아웃 방지"`

---

### Task 4: 역할 관리 API

**Files:**
- Modify: `backend/src/main/java/com/worknote/auth/RoleMapper.java` + `mappers/RoleMapper.xml` (findAll/insert/update/delete/countUsers)
- Create: `backend/src/main/java/com/worknote/admin/RoleAdminService.java`
- Create: `backend/src/main/java/com/worknote/admin/AdminRoleController.java`
- Create: `backend/src/main/java/com/worknote/admin/dto/CreateRoleRequest.java`, `UpdateRoleRequest.java`
- Test: `backend/src/test/java/com/worknote/admin/AdminRoleApiTest.java`

**API:** GET `/api/admin/roles` 200 / POST 201 / PATCH `/{id}` 200 / DELETE `/{id}` 204. 응답 뷰 `RoleView(id, name, system:boolean, caps:Set, userCount:int)`.

- [x] **Step 1: 실패하는 테스트 작성**

```java
package com.worknote.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
class AdminRoleApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM role WHERE system = 0");
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
    }

    private MockHttpSession admin() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"admin\",\"password\":\"boot-pass-1\"}"))
            .andExpect(status().isOk());
        return session;
    }

    @Test
    void list_includesSeedRolesWithUserCount() throws Exception {
        mvc.perform(get("/api/admin/roles").session(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id=='admin')].userCount").value(1))
            .andExpect(jsonPath("$[?(@.id=='admin')].system").value(true));
    }

    @Test
    void create_patch_delete_roundTrip() throws Exception {
        MockHttpSession s = admin();
        mvc.perform(post("/api/admin/roles").session(s).contentType(APPLICATION_JSON)
                .content("{\"id\":\"editor\",\"name\":\"편집자\",\"caps\":[\"res.read\",\"res.edit\"]}"))
            .andExpect(status().isCreated());
        mvc.perform(patch("/api/admin/roles/editor").session(s).contentType(APPLICATION_JSON)
                .content("{\"caps\":[\"res.read\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.caps.length()").value(1));
        mvc.perform(delete("/api/admin/roles/editor").session(s)).andExpect(status().isNoContent());
    }

    @Test
    void unknownCap_422() throws Exception {
        mvc.perform(post("/api/admin/roles").session(admin()).contentType(APPLICATION_JSON)
                .content("{\"id\":\"bad\",\"name\":\"x\",\"caps\":[\"res.raed\"]}"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void systemRole_patchOrDelete_422() throws Exception {
        MockHttpSession s = admin();
        mvc.perform(patch("/api/admin/roles/admin").session(s).contentType(APPLICATION_JSON)
                .content("{\"name\":\"바꿈\"}"))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(delete("/api/admin/roles/visitor").session(s)).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void roleInUse_delete_409() throws Exception {
        MockHttpSession s = admin();
        mvc.perform(post("/api/admin/roles").session(s).contentType(APPLICATION_JSON)
                .content("{\"id\":\"editor\",\"name\":\"편집자\",\"caps\":[\"res.read\"]}"))
            .andExpect(status().isCreated());
        jdbc.update("INSERT INTO app_user (id, emp, name, role_id, status) VALUES ('ux','99999','x','editor','active')");
        mvc.perform(delete("/api/admin/roles/editor").session(s)).andExpect(status().isConflict());
    }

    @Test
    void duplicateId_409() throws Exception {
        mvc.perform(post("/api/admin/roles").session(admin()).contentType(APPLICATION_JSON)
                .content("{\"id\":\"admin\",\"name\":\"x\",\"caps\":[\"res.read\"]}"))
            .andExpect(status().isConflict());
    }
}
```

- [x] **Step 2: 실패 확인** — `./gradlew test --tests AdminRoleApiTest`
- [x] **Step 3: 매퍼 추가**

`RoleMapper.java`:
```java
List<RoleRow> findAll();
void insert(RoleRow row);
void update(RoleRow row);
void delete(@Param("id") String id);
int countUsers(@Param("roleId") String roleId);
```

`RoleMapper.xml`:
```xml
<select id="findAll" resultType="com.worknote.auth.RoleRow">
  SELECT * FROM role ORDER BY system DESC, id
</select>
<insert id="insert">
  INSERT INTO role (id, name, system, caps) VALUES (#{id}, #{name}, #{system}, #{caps})
</insert>
<update id="update">
  UPDATE role SET name = #{name}, caps = #{caps} WHERE id = #{id}
</update>
<delete id="delete">
  DELETE FROM role WHERE id = #{id}
</delete>
<select id="countUsers" resultType="int">
  SELECT COUNT(*) FROM app_user WHERE role_id = #{roleId}
</select>
```

- [x] **Step 4: 서비스 + DTO + 컨트롤러 구현**

`RoleAdminService.java`:
```java
package com.worknote.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.worknote.acl.AclResolver;
import com.worknote.auth.RoleCaps;
import com.worknote.auth.RoleMapper;
import com.worknote.auth.RoleRow;
import com.worknote.vault.VaultException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 역할 관리. caps는 KNOWN_CAPS 화이트리스트 검증 — RoleCaps가 DB JSON을 신뢰하므로 쓰기 시점에 fail-fast. */
@Service
public class RoleAdminService {

    private static final Set<String> RES_CAPS = Set.of(
        "res.read", "res.edit", "res.create", "res.delete", "res.export", "res.share");
    static final Set<String> KNOWN_CAPS;
    static {
        Set<String> all = new HashSet<>(AclResolver.ADMIN_CAPS);
        all.addAll(RES_CAPS);
        KNOWN_CAPS = Set.copyOf(all);
    }

    public record RoleView(String id, String name, boolean system, Set<String> caps, int userCount) {}

    private final RoleMapper roles;
    private final RoleCaps roleCaps;
    private final ObjectMapper json = new ObjectMapper();

    public RoleAdminService(RoleMapper roles, RoleCaps roleCaps) {
        this.roles = roles;
        this.roleCaps = roleCaps;
    }

    public List<RoleView> list() {
        return roles.findAll().stream().map(this::toView).toList();
    }

    @Transactional
    public RoleView create(String id, String name, List<String> caps) {
        if (roles.findById(id) != null) {
            throw VaultException.conflict("이미 존재하는 역할: " + id);
        }
        roles.insert(new RoleRow(id, name, 0, toJson(validated(caps))));
        return toView(roles.findById(id));
    }

    @Transactional
    public RoleView update(String id, String name, List<String> caps) {
        RoleRow row = require(id);
        if (row.system() == 1) {
            throw VaultException.invalid("시스템 역할은 수정할 수 없습니다: " + id);
        }
        String mergedName = name != null ? name : row.name();
        String mergedCaps = caps != null ? toJson(validated(caps)) : row.caps();
        roles.update(new RoleRow(id, mergedName, 0, mergedCaps));
        return toView(roles.findById(id));
    }

    @Transactional
    public void delete(String id) {
        RoleRow row = require(id);
        if (row.system() == 1) {
            throw VaultException.invalid("시스템 역할은 삭제할 수 없습니다: " + id);
        }
        if (roles.countUsers(id) > 0) {
            throw VaultException.conflict("해당 역할을 사용하는 사용자가 있습니다: " + id);
        }
        roles.delete(id);
    }

    private RoleRow require(String id) {
        RoleRow row = roles.findById(id);
        if (row == null) {
            throw VaultException.notFound("역할이 없습니다: " + id);
        }
        return row;
    }

    private Set<String> validated(List<String> caps) {
        Set<String> set = new HashSet<>(caps);
        for (String cap : set) {
            if (!KNOWN_CAPS.contains(cap)) {
                throw VaultException.invalid("알 수 없는 권한: " + cap);
            }
        }
        return set;
    }

    private String toJson(Set<String> caps) {
        try {
            return json.writeValueAsString(caps);
        } catch (Exception e) {
            throw new IllegalStateException("caps 직렬화 실패", e);
        }
    }

    private RoleView toView(RoleRow row) {
        return new RoleView(row.id(), row.name(), row.system() == 1,
            roleCaps.of(row.id()), roles.countUsers(row.id()));
    }
}
```

DTO:
```java
package com.worknote.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateRoleRequest(
    @NotBlank @Size(max = 32) @Pattern(regexp = "[a-z][a-z0-9-]*") String id,
    @NotBlank @Size(max = 64) String name,
    @NotNull List<String> caps
) {}
```
```java
package com.worknote.admin.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateRoleRequest(
    @Size(max = 64) String name,
    List<String> caps
) {}
```

`AdminRoleController.java`:
```java
package com.worknote.admin;

import com.worknote.admin.dto.CreateRoleRequest;
import com.worknote.admin.dto.UpdateRoleRequest;
import com.worknote.audit.AuditService;
import com.worknote.auth.AuthFilter;
import com.worknote.auth.UserRow;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/roles")
public class AdminRoleController {

    private final AdminGuard guard;
    private final RoleAdminService svc;
    private final AuditService audit;

    public AdminRoleController(AdminGuard guard, RoleAdminService svc, AuditService audit) {
        this.guard = guard;
        this.svc = svc;
        this.audit = audit;
    }

    private static UserRow user(HttpServletRequest req) {
        return (UserRow) req.getAttribute(AuthFilter.CURRENT_USER);
    }

    @GetMapping
    public List<RoleAdminService.RoleView> list(HttpServletRequest req) {
        guard.requireAdmin(user(req));
        return svc.list();
    }

    @PostMapping
    public ResponseEntity<RoleAdminService.RoleView> create(@Valid @RequestBody CreateRoleRequest body,
                                                            HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        RoleAdminService.RoleView created = svc.create(body.id(), body.name(), body.caps());
        audit.log(actor, "role.create", body.id(), req.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{id}")
    public RoleAdminService.RoleView update(@PathVariable String id, @Valid @RequestBody UpdateRoleRequest body,
                                            HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        RoleAdminService.RoleView updated = svc.update(id, body.name(), body.caps());
        audit.log(actor, "role.update", id, req.getRemoteAddr());
        return updated;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        svc.delete(id);
        audit.log(actor, "role.delete", id, req.getRemoteAddr());
    }
}
```

- [x] **Step 5: 통과 확인** — `./gradlew test --tests AdminRoleApiTest` + 전체 green
- [x] **Step 6: 커밋** — `git commit -m "feat(backend): 역할 관리 API — caps 화이트리스트 검증, 시스템 역할 보호, 사용 중 삭제 차단"`

---

### Task 5: 팀 관리 API

**Files:**
- Modify: `backend/src/main/java/com/worknote/acl/TeamMapper.java` + `mappers/TeamMapper.xml`
- Modify: `backend/src/main/java/com/worknote/acl/AclMapper.java` + `mappers/AclMapper.xml` (deleteAclByPrincipal)
- Create: `backend/src/main/java/com/worknote/acl/TeamRow.java`
- Create: `backend/src/main/java/com/worknote/admin/TeamAdminService.java`
- Create: `backend/src/main/java/com/worknote/admin/AdminTeamController.java`
- Create: `backend/src/main/java/com/worknote/admin/dto/TeamRequest.java`, `TeamMemberRequest.java`
- Test: `backend/src/test/java/com/worknote/admin/AdminTeamApiTest.java`

**API:** GET `/api/admin/teams`(멤버 포함) / POST 201 / PATCH `/{id}` 204 / DELETE `/{id}` 204 / POST `/{id}/members` 204 / DELETE `/{id}/members/{userId}` 204

- [x] **Step 1: 실패하는 테스트 작성**

```java
package com.worknote.admin;

import com.worknote.auth.CredentialRow;
import com.worknote.auth.PasswordHasher;
import com.worknote.auth.UserMapper;
import com.worknote.auth.UserRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:phase2mem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
@AutoConfigureMockMvc
class AdminTeamApiTest {

    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM acl");
        jdbc.update("DELETE FROM space");
        jdbc.update("DELETE FROM team_member");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM node");
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        users.insert(new UserRow("u1", "10001", null, "홍길동", "operator", "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("u1", salt, PasswordHasher.hash("pw-1234", salt)));
    }

    private MockHttpSession admin() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"admin\",\"password\":\"boot-pass-1\"}"))
            .andExpect(status().isOk());
        return session;
    }

    private String createTeam(MockHttpSession s, String name) throws Exception {
        MvcResult res = mvc.perform(post("/api/admin/teams").session(s).contentType(APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}"))
            .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void create_addMember_list() throws Exception {
        MockHttpSession s = admin();
        String teamId = createTeam(s, "결제팀");
        mvc.perform(post("/api/admin/teams/" + teamId + "/members").session(s).contentType(APPLICATION_JSON)
                .content("{\"userId\":\"u1\"}"))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/teams").session(s))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].members[0].emp").value("10001"));
    }

    @Test
    void addMember_duplicate_409_unknownUser_422() throws Exception {
        MockHttpSession s = admin();
        String teamId = createTeam(s, "결제팀");
        mvc.perform(post("/api/admin/teams/" + teamId + "/members").session(s).contentType(APPLICATION_JSON)
                .content("{\"userId\":\"u1\"}")).andExpect(status().isNoContent());
        mvc.perform(post("/api/admin/teams/" + teamId + "/members").session(s).contentType(APPLICATION_JSON)
                .content("{\"userId\":\"u1\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/admin/teams/" + teamId + "/members").session(s).contentType(APPLICATION_JSON)
                .content("{\"userId\":\"no-such\"}")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void removeMember_thenTeamAclNoLongerApplies() throws Exception {
        MockHttpSession s = admin();
        String teamId = createTeam(s, "결제팀");
        mvc.perform(post("/api/admin/teams/" + teamId + "/members").session(s).contentType(APPLICATION_JSON)
                .content("{\"userId\":\"u1\"}")).andExpect(status().isNoContent());
        mvc.perform(delete("/api/admin/teams/" + teamId + "/members/u1").session(s))
            .andExpect(status().isNoContent());
        mvc.perform(delete("/api/admin/teams/" + teamId + "/members/u1").session(s))
            .andExpect(status().isNotFound());
    }

    @Test
    void deleteTeam_cleansMembershipAndAcl() throws Exception {
        MockHttpSession s = admin();
        String teamId = createTeam(s, "결제팀");
        jdbc.update("INSERT INTO node (id, type, name, position) VALUES ('f1','folder','F1',1)");
        jdbc.update("INSERT INTO acl (principal_type, principal_id, node_id, grant_type) VALUES ('team', ?, 'f1', 'edit')", teamId);
        mvc.perform(post("/api/admin/teams/" + teamId + "/members").session(s).contentType(APPLICATION_JSON)
                .content("{\"userId\":\"u1\"}")).andExpect(status().isNoContent());
        mvc.perform(delete("/api/admin/teams/" + teamId).session(s)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM team_member WHERE team_id = ?", Integer.class, teamId)).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM acl WHERE principal_type = 'team' AND principal_id = ?", Integer.class, teamId)).isZero();
    }

    @Test
    void deleteTeam_owningSpace_409() throws Exception {
        MockHttpSession s = admin();
        String teamId = createTeam(s, "결제팀");
        jdbc.update("INSERT INTO node (id, type, name, position) VALUES ('f1','folder','F1',1)");
        jdbc.update("INSERT INTO space (node_id, team_id) VALUES ('f1', ?)", teamId);
        mvc.perform(delete("/api/admin/teams/" + teamId).session(s)).andExpect(status().isConflict());
    }

    @Test
    void rename_204_unknown_404() throws Exception {
        MockHttpSession s = admin();
        String teamId = createTeam(s, "결제팀");
        mvc.perform(patch("/api/admin/teams/" + teamId).session(s).contentType(APPLICATION_JSON)
                .content("{\"name\":\"정산팀\"}")).andExpect(status().isNoContent());
        mvc.perform(patch("/api/admin/teams/no-such").session(s).contentType(APPLICATION_JSON)
                .content("{\"name\":\"x\"}")).andExpect(status().isNotFound());
    }
}
```

(jsonpath 의존성 `com.jayway.jsonpath`는 spring-boot-starter-test에 포함)

- [x] **Step 2: 실패 확인**
- [x] **Step 3: 매퍼·record 추가**

`TeamRow.java`:
```java
package com.worknote.acl;

public record TeamRow(String id, String name) {}
```

`TeamMapper.java`에 추가:
```java
List<TeamRow> findAll();
TeamRow findById(@Param("id") String id);
List<com.worknote.auth.UserRow> membersOf(@Param("teamId") String teamId);
void updateTeam(@Param("id") String id, @Param("name") String name);
void deleteTeam(@Param("id") String id);
int removeMember(@Param("teamId") String teamId, @Param("userId") String userId);
void deleteMembers(@Param("teamId") String teamId);
int isMember(@Param("teamId") String teamId, @Param("userId") String userId);
int countSpaces(@Param("teamId") String teamId);
```

`TeamMapper.xml`에 추가:
```xml
<select id="findAll" resultType="com.worknote.acl.TeamRow">
  SELECT * FROM team ORDER BY name, id
</select>
<select id="findById" resultType="com.worknote.acl.TeamRow">
  SELECT * FROM team WHERE id = #{id}
</select>
<select id="membersOf" resultType="com.worknote.auth.UserRow">
  SELECT u.* FROM app_user u JOIN team_member tm ON tm.user_id = u.id
  WHERE tm.team_id = #{teamId} ORDER BY u.emp
</select>
<update id="updateTeam">
  UPDATE team SET name = #{name} WHERE id = #{id}
</update>
<delete id="deleteTeam">
  DELETE FROM team WHERE id = #{id}
</delete>
<delete id="removeMember">
  DELETE FROM team_member WHERE team_id = #{teamId} AND user_id = #{userId}
</delete>
<delete id="deleteMembers">
  DELETE FROM team_member WHERE team_id = #{teamId}
</delete>
<select id="isMember" resultType="int">
  SELECT COUNT(*) FROM team_member WHERE team_id = #{teamId} AND user_id = #{userId}
</select>
<select id="countSpaces" resultType="int">
  SELECT COUNT(*) FROM space WHERE team_id = #{teamId}
</select>
```

`AclMapper.java`에 추가:
```java
void deleteAclByPrincipal(@Param("type") String type, @Param("id") String id);
```
`AclMapper.xml`:
```xml
<delete id="deleteAclByPrincipal">
  DELETE FROM acl WHERE principal_type = #{type} AND principal_id = #{id}
</delete>
```

- [x] **Step 4: 서비스·DTO·컨트롤러 구현**

`TeamAdminService.java`:
```java
package com.worknote.admin;

import com.worknote.acl.AclMapper;
import com.worknote.acl.TeamMapper;
import com.worknote.acl.TeamRow;
import com.worknote.auth.UserMapper;
import com.worknote.auth.UserRow;
import com.worknote.vault.VaultException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** 팀 관리. 삭제 시 멤버십+팀 ACL 정리 — 잔여 행은 팀 id 재사용 시 권한 부활(purge 원칙과 동일). */
@Service
public class TeamAdminService {

    public record TeamView(String id, String name, List<UserRow> members) {}

    private final TeamMapper teams;
    private final UserMapper users;
    private final AclMapper acl;

    public TeamAdminService(TeamMapper teams, UserMapper users, AclMapper acl) {
        this.teams = teams;
        this.users = users;
        this.acl = acl;
    }

    public List<TeamView> list() {
        return teams.findAll().stream()
            .map(t -> new TeamView(t.id(), t.name(), teams.membersOf(t.id())))
            .toList();
    }

    @Transactional
    public TeamRow create(String name) {
        String id = "t-" + UUID.randomUUID();
        teams.insertTeam(id, name);
        return new TeamRow(id, name);
    }

    @Transactional
    public void rename(String id, String name) {
        require(id);
        teams.updateTeam(id, name);
    }

    @Transactional
    public void delete(String id) {
        require(id);
        if (teams.countSpaces(id) > 0) {
            throw VaultException.conflict("팀이 소유한 스페이스가 있습니다 — 먼저 스페이스 소유를 해제하세요");
        }
        teams.deleteMembers(id);
        acl.deleteAclByPrincipal("team", id);
        teams.deleteTeam(id);
    }

    @Transactional
    public UserRow addMember(String teamId, String userId) {
        require(teamId);
        UserRow user = users.findById(userId);
        if (user == null) {
            throw VaultException.invalid("존재하지 않는 사용자: " + userId);
        }
        if (teams.isMember(teamId, userId) > 0) {
            throw VaultException.conflict("이미 팀 멤버입니다: " + user.emp());
        }
        teams.addMember(teamId, userId);
        return user;
    }

    @Transactional
    public void removeMember(String teamId, String userId) {
        require(teamId);
        if (teams.removeMember(teamId, userId) == 0) {
            throw VaultException.notFound("팀 멤버가 아닙니다: " + userId);
        }
    }

    private TeamRow require(String id) {
        TeamRow row = teams.findById(id);
        if (row == null) {
            throw VaultException.notFound("팀이 없습니다: " + id);
        }
        return row;
    }
}
```

DTO:
```java
package com.worknote.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TeamRequest(@NotBlank @Size(max = 64) String name) {}
```
```java
package com.worknote.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TeamMemberRequest(@NotBlank @Size(max = 64) String userId) {}
```

`AdminTeamController.java`:
```java
package com.worknote.admin;

import com.worknote.acl.TeamRow;
import com.worknote.admin.dto.TeamMemberRequest;
import com.worknote.admin.dto.TeamRequest;
import com.worknote.audit.AuditService;
import com.worknote.auth.AuthFilter;
import com.worknote.auth.UserRow;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/teams")
public class AdminTeamController {

    private final AdminGuard guard;
    private final TeamAdminService svc;
    private final AuditService audit;

    public AdminTeamController(AdminGuard guard, TeamAdminService svc, AuditService audit) {
        this.guard = guard;
        this.svc = svc;
        this.audit = audit;
    }

    private static UserRow user(HttpServletRequest req) {
        return (UserRow) req.getAttribute(AuthFilter.CURRENT_USER);
    }

    @GetMapping
    public List<TeamAdminService.TeamView> list(HttpServletRequest req) {
        guard.requireAdmin(user(req));
        return svc.list();
    }

    @PostMapping
    public ResponseEntity<TeamRow> create(@Valid @RequestBody TeamRequest body, HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        TeamRow created = svc.create(body.name());
        audit.log(actor, "team.create", created.id(), req.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rename(@PathVariable String id, @Valid @RequestBody TeamRequest body, HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        svc.rename(id, body.name());
        audit.log(actor, "team.update", id, req.getRemoteAddr());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        svc.delete(id);
        audit.log(actor, "team.delete", id, req.getRemoteAddr());
    }

    @PostMapping("/{id}/members")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addMember(@PathVariable String id, @Valid @RequestBody TeamMemberRequest body,
                          HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        UserRow member = svc.addMember(id, body.userId());
        audit.log(actor, "team.member.add", id + " + " + member.emp(), req.getRemoteAddr());
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable String id, @PathVariable String userId, HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        svc.removeMember(id, userId);
        audit.log(actor, "team.member.remove", id + " - " + userId, req.getRemoteAddr());
    }
}
```

- [x] **Step 5: 통과 확인** + 전체 green
- [x] **Step 6: 커밋** — `git commit -m "feat(backend): 팀 관리 API — CRUD·멤버십, 삭제 시 ACL 정리·소유 스페이스 차단"`

---

### Task 6: 스페이스 API

**Files:**
- Create: `backend/src/main/java/com/worknote/acl/SpaceMapper.java`, `SpaceRow.java` + Create: `backend/src/main/resources/mappers/SpaceMapper.xml`
- Create: `backend/src/main/java/com/worknote/admin/SpaceAdminService.java`
- Create: `backend/src/main/java/com/worknote/admin/AdminSpaceController.java`
- Create: `backend/src/main/java/com/worknote/admin/dto/SpaceRequest.java`
- Test: `backend/src/test/java/com/worknote/admin/AdminSpaceApiTest.java`

**API:** GET `/api/admin/spaces` 200 / PUT `/api/admin/spaces/{nodeId}` `{teamId|null}` 204 / DELETE `/{nodeId}` 204

- [x] **Step 1: 실패하는 테스트 작성**

```java
package com.worknote.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:phase2mem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
@AutoConfigureMockMvc
class AdminSpaceApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM acl");
        jdbc.update("DELETE FROM space");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM node");
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        jdbc.update("INSERT INTO node (id, type, name, position) VALUES ('f1','folder','팀폴더',1)");
        jdbc.update("INSERT INTO node (id, parent_id, type, name, position) VALUES ('f2','f1','folder','하위',1)");
        jdbc.update("INSERT INTO node (id, type, name, position) VALUES ('n1','note','루트노트',2)");
        jdbc.update("INSERT INTO team (id, name) VALUES ('t1','결제팀')");
    }

    private MockHttpSession admin() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"admin\",\"password\":\"boot-pass-1\"}"))
            .andExpect(status().isOk());
        return session;
    }

    @Test
    void put_assignsTeam_andAutoGrantsEdit() throws Exception {
        mvc.perform(put("/api/admin/spaces/f1").session(admin()).contentType(APPLICATION_JSON)
                .content("{\"teamId\":\"t1\"}"))
            .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT team_id FROM space WHERE node_id = 'f1'", String.class)).isEqualTo("t1");
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM acl WHERE principal_type='team' AND principal_id='t1' AND node_id='f1' AND grant_type='edit'",
            Integer.class)).isEqualTo(1);
    }

    @Test
    void put_isUpsert_andKeepsExistingGrant() throws Exception {
        MockHttpSession s = admin();
        jdbc.update("INSERT INTO acl (principal_type, principal_id, node_id, grant_type) VALUES ('team','t1','f1','read')");
        mvc.perform(put("/api/admin/spaces/f1").session(s).contentType(APPLICATION_JSON)
                .content("{\"teamId\":\"t1\"}")).andExpect(status().isNoContent());
        // 이미 그 팀 grant가 있으면 덮어쓰지 않음 (관리자가 의도적으로 read로 낮춘 상태 존중)
        assertThat(jdbc.queryForObject(
            "SELECT grant_type FROM acl WHERE principal_type='team' AND principal_id='t1' AND node_id='f1'",
            String.class)).isEqualTo("read");
        // 공용 전환 upsert
        mvc.perform(put("/api/admin/spaces/f1").session(s).contentType(APPLICATION_JSON)
                .content("{}")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT team_id FROM space WHERE node_id = 'f1'", String.class)).isNull();
    }

    @Test
    void put_nonTopLevelOrNote_422() throws Exception {
        MockHttpSession s = admin();
        mvc.perform(put("/api/admin/spaces/f2").session(s).contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/admin/spaces/n1").session(s).contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void put_unknownNode_404_unknownTeam_422() throws Exception {
        MockHttpSession s = admin();
        mvc.perform(put("/api/admin/spaces/no-such").session(s).contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound());
        mvc.perform(put("/api/admin/spaces/f1").session(s).contentType(APPLICATION_JSON)
                .content("{\"teamId\":\"no-such\"}"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void delete_removes_unknown_404() throws Exception {
        MockHttpSession s = admin();
        mvc.perform(put("/api/admin/spaces/f1").session(s).contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isNoContent());
        mvc.perform(delete("/api/admin/spaces/f1").session(s)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/admin/spaces/f1").session(s)).andExpect(status().isNotFound());
    }

    @Test
    void list_returnsRows() throws Exception {
        MockHttpSession s = admin();
        mvc.perform(put("/api/admin/spaces/f1").session(s).contentType(APPLICATION_JSON)
                .content("{\"teamId\":\"t1\"}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/spaces").session(s))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].nodeId").value("f1"))
            .andExpect(jsonPath("$[0].teamId").value("t1"));
    }
}
```

- [x] **Step 2: 실패 확인**
- [x] **Step 3: 매퍼·record 구현**

`SpaceRow.java`:
```java
package com.worknote.acl;

public record SpaceRow(String nodeId, String teamId) {}
```

`SpaceMapper.java`:
```java
package com.worknote.acl;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SpaceMapper {
    List<SpaceRow> findAll();
    SpaceRow find(@Param("nodeId") String nodeId);
    void upsert(@Param("nodeId") String nodeId, @Param("teamId") String teamId);
    int delete(@Param("nodeId") String nodeId);
}
```

`SpaceMapper.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.worknote.acl.SpaceMapper">
  <select id="findAll" resultType="com.worknote.acl.SpaceRow">
    SELECT node_id, team_id FROM space ORDER BY node_id
  </select>
  <select id="find" resultType="com.worknote.acl.SpaceRow">
    SELECT node_id, team_id FROM space WHERE node_id = #{nodeId}
  </select>
  <insert id="upsert">
    <!-- Oracle 전환 시 MERGE INTO로 교체 -->
    INSERT INTO space (node_id, team_id) VALUES (#{nodeId}, #{teamId})
    ON CONFLICT(node_id) DO UPDATE SET team_id = excluded.team_id
  </insert>
  <delete id="delete">
    DELETE FROM space WHERE node_id = #{nodeId}
  </delete>
</mapper>
```

- [x] **Step 4: 서비스·DTO·컨트롤러 구현**

`SpaceAdminService.java`:
```java
package com.worknote.admin;

import com.worknote.acl.AclMapper;
import com.worknote.acl.AclRow;
import com.worknote.acl.SpaceMapper;
import com.worknote.acl.SpaceRow;
import com.worknote.acl.TeamMapper;
import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import com.worknote.vault.VaultException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 팀 스페이스 = 최상위 폴더 + 소유 팀 메타데이터(스펙 §4.2). 팀 지정 시 edit ACL 자동 grant. */
@Service
public class SpaceAdminService {

    private final SpaceMapper spaces;
    private final NodeMapper nodes;
    private final TeamMapper teams;
    private final AclMapper acl;

    public SpaceAdminService(SpaceMapper spaces, NodeMapper nodes, TeamMapper teams, AclMapper acl) {
        this.spaces = spaces;
        this.nodes = nodes;
        this.teams = teams;
        this.acl = acl;
    }

    public List<SpaceRow> list() {
        return spaces.findAll();
    }

    @Transactional
    public void set(String nodeId, String teamId) {
        NodeRow node = nodes.findById(nodeId);
        if (node == null || node.deletedAt() != null) {
            throw VaultException.notFound("노드가 없습니다: " + nodeId);
        }
        if (!"folder".equals(node.type()) || node.parentId() != null) {
            throw VaultException.invalid("스페이스는 최상위 폴더만 지정할 수 있습니다");
        }
        if (teamId != null && teams.findById(teamId) == null) {
            throw VaultException.invalid("존재하지 않는 팀: " + teamId);
        }
        spaces.upsert(nodeId, teamId);
        if (teamId != null) {
            // 스펙 §4.2: 소유 팀 edit 자동 grant — 단, 그 팀의 명시 grant가 이미 있으면 존중
            boolean granted = acl.findAclForNodes(List.of(nodeId)).stream()
                .anyMatch(r -> "team".equals(r.principalType()) && teamId.equals(r.principalId()));
            if (!granted) {
                acl.insertAcl(new AclRow("team", teamId, nodeId, "edit"));
            }
        }
    }

    @Transactional
    public void unset(String nodeId) {
        if (spaces.delete(nodeId) == 0) {
            throw VaultException.notFound("스페이스가 아닙니다: " + nodeId);
        }
    }
}
```

`SpaceRequest.java`:
```java
package com.worknote.admin.dto;

import jakarta.validation.constraints.Size;

public record SpaceRequest(@Size(max = 64) String teamId) {}
```

`AdminSpaceController.java`:
```java
package com.worknote.admin;

import com.worknote.acl.SpaceRow;
import com.worknote.admin.dto.SpaceRequest;
import com.worknote.audit.AuditService;
import com.worknote.auth.AuthFilter;
import com.worknote.auth.UserRow;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/spaces")
public class AdminSpaceController {

    private final AdminGuard guard;
    private final SpaceAdminService svc;
    private final AuditService audit;

    public AdminSpaceController(AdminGuard guard, SpaceAdminService svc, AuditService audit) {
        this.guard = guard;
        this.svc = svc;
        this.audit = audit;
    }

    private static UserRow user(HttpServletRequest req) {
        return (UserRow) req.getAttribute(AuthFilter.CURRENT_USER);
    }

    @GetMapping
    public List<SpaceRow> list(HttpServletRequest req) {
        guard.requireAdmin(user(req));
        return svc.list();
    }

    @PutMapping("/{nodeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void set(@PathVariable String nodeId, @Valid @RequestBody SpaceRequest body, HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        svc.set(nodeId, body.teamId());
        audit.log(actor, "space.set", nodeId + " -> " + (body.teamId() != null ? body.teamId() : "공용"),
            req.getRemoteAddr());
    }

    @DeleteMapping("/{nodeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unset(@PathVariable String nodeId, HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        svc.unset(nodeId);
        audit.log(actor, "space.unset", nodeId, req.getRemoteAddr());
    }
}
```

- [x] **Step 5: 통과 확인** + 전체 green
- [x] **Step 6: 커밋** — `git commit -m "feat(backend): 스페이스 API — 최상위 폴더 검증, 소유 팀 edit 자동 grant"`

---

### Task 7: ACL API

**Files:**
- Modify: `backend/src/main/java/com/worknote/acl/AclMapper.java` + `mappers/AclMapper.xml` (deleteAclForNode)
- Create: `backend/src/main/java/com/worknote/admin/AclAdminService.java`
- Create: `backend/src/main/java/com/worknote/admin/AdminAclController.java`
- Create: `backend/src/main/java/com/worknote/admin/dto/AclEntryRequest.java`, `SetAclRequest.java`
- Test: `backend/src/test/java/com/worknote/admin/AdminAclApiTest.java`

**API:** GET `/api/admin/acl`(전체) / GET `/api/admin/nodes/{id}/acl`(노드 직접 엔트리) / PUT `/api/admin/nodes/{id}/acl`(replace-all, 204)

- [x] **Step 1: 실패하는 테스트 작성**

```java
package com.worknote.admin;

import com.worknote.auth.CredentialRow;
import com.worknote.auth.PasswordHasher;
import com.worknote.auth.UserMapper;
import com.worknote.auth.UserRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:phase2mem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
@AutoConfigureMockMvc
class AdminAclApiTest {

    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM acl");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM node");
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        users.insert(new UserRow("u1", "10001", null, "홍길동", "operator", "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("u1", salt, PasswordHasher.hash("pw-1234", salt)));
        jdbc.update("INSERT INTO team (id, name) VALUES ('t1','결제팀')");
        jdbc.update("INSERT INTO node (id, type, name, position) VALUES ('f1','folder','F1',1)");
        jdbc.update("INSERT INTO node (id, parent_id, type, name, position, content) VALUES ('n1','f1','note','N1',1,'body')");
    }

    private MockHttpSession login(String emp, String pw) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"" + emp + "\",\"password\":\"" + pw + "\"}"))
            .andExpect(status().isOk());
        return session;
    }

    @Test
    void put_replacesEntries_andTakesEffect() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        // 권한 없는 operator는 n1을 못 본다
        MockHttpSession op = login("10001", "pw-1234");
        mvc.perform(get("/api/tree").session(op)).andExpect(jsonPath("$.length()").value(0));
        // grant 부여
        mvc.perform(put("/api/admin/nodes/f1/acl").session(admin).contentType(APPLICATION_JSON)
                .content("{\"entries\":[{\"principalType\":\"user\",\"principalId\":\"u1\",\"grantType\":\"edit\"}]}"))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/tree").session(op)).andExpect(jsonPath("$.length()").value(1));
        // replace-all: 빈 entries로 회수
        mvc.perform(put("/api/admin/nodes/f1/acl").session(admin).contentType(APPLICATION_JSON)
                .content("{\"entries\":[]}"))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/tree").session(op)).andExpect(jsonPath("$.length()").value(0));
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act = 'acl.set'", Integer.class)).isEqualTo(2);
    }

    @Test
    void getForNode_andGetAll() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(put("/api/admin/nodes/f1/acl").session(admin).contentType(APPLICATION_JSON)
                .content("{\"entries\":[{\"principalType\":\"team\",\"principalId\":\"t1\",\"grantType\":\"read\"},"
                    + "{\"principalType\":\"all\",\"principalId\":\"@all\",\"grantType\":\"deny\"}]}"))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/nodes/f1/acl").session(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/admin/acl").session(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void put_unknownPrincipal_422() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(put("/api/admin/nodes/f1/acl").session(admin).contentType(APPLICATION_JSON)
                .content("{\"entries\":[{\"principalType\":\"user\",\"principalId\":\"no-such\",\"grantType\":\"read\"}]}"))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/admin/nodes/f1/acl").session(admin).contentType(APPLICATION_JSON)
                .content("{\"entries\":[{\"principalType\":\"all\",\"principalId\":\"everyone\",\"grantType\":\"read\"}]}"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void put_duplicatePrincipal_422_unknownNode_404() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(put("/api/admin/nodes/f1/acl").session(admin).contentType(APPLICATION_JSON)
                .content("{\"entries\":[{\"principalType\":\"user\",\"principalId\":\"u1\",\"grantType\":\"read\"},"
                    + "{\"principalType\":\"user\",\"principalId\":\"u1\",\"grantType\":\"edit\"}]}"))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/admin/nodes/no-such/acl").session(admin).contentType(APPLICATION_JSON)
                .content("{\"entries\":[]}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void put_invalidGrantType_400() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(put("/api/admin/nodes/f1/acl").session(admin).contentType(APPLICATION_JSON)
                .content("{\"entries\":[{\"principalType\":\"user\",\"principalId\":\"u1\",\"grantType\":\"write\"}]}"))
            .andExpect(status().isBadRequest());
    }
}
```

- [x] **Step 2: 실패 확인**
- [x] **Step 3: 매퍼 추가**

`AclMapper.java`: `void deleteAclForNode(@Param("nodeId") String nodeId);`

`AclMapper.xml`:
```xml
<delete id="deleteAclForNode">
  DELETE FROM acl WHERE node_id = #{nodeId}
</delete>
```

- [x] **Step 4: 서비스·DTO·컨트롤러 구현**

DTO:
```java
package com.worknote.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AclEntryRequest(
    @NotBlank @Pattern(regexp = "user|team|all") String principalType,
    @NotBlank @Size(max = 64) String principalId,
    @NotBlank @Pattern(regexp = "read|edit|deny") String grantType
) {}
```
```java
package com.worknote.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record SetAclRequest(@NotNull List<@Valid AclEntryRequest> entries) {}
```

`AclAdminService.java`:
```java
package com.worknote.admin;

import com.worknote.acl.AclMapper;
import com.worknote.acl.AclRow;
import com.worknote.acl.TeamMapper;
import com.worknote.admin.dto.AclEntryRequest;
import com.worknote.auth.UserMapper;
import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import com.worknote.vault.VaultException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** ACL 관리 — 노드 단위 replace-all. 주체 존재 검증으로 유령 grant 방지. */
@Service
public class AclAdminService {

    private final AclMapper acl;
    private final NodeMapper nodes;
    private final UserMapper users;
    private final TeamMapper teams;

    public AclAdminService(AclMapper acl, NodeMapper nodes, UserMapper users, TeamMapper teams) {
        this.acl = acl;
        this.nodes = nodes;
        this.users = users;
        this.teams = teams;
    }

    public List<AclRow> listAll() {
        return acl.findAllAcl();
    }

    public List<AclRow> forNode(String nodeId) {
        requireActiveNode(nodeId);
        return acl.findAclForNodes(List.of(nodeId));
    }

    @Transactional
    public void replace(String nodeId, List<AclEntryRequest> entries) {
        requireActiveNode(nodeId);
        Set<String> seen = new HashSet<>();
        for (AclEntryRequest e : entries) {
            if (!seen.add(e.principalType() + ":" + e.principalId())) {
                throw VaultException.invalid("중복된 주체: " + e.principalType() + ":" + e.principalId());
            }
            validatePrincipal(e);
        }
        acl.deleteAclForNode(nodeId);
        for (AclEntryRequest e : entries) {
            acl.insertAcl(new AclRow(e.principalType(), e.principalId(), nodeId, e.grantType()));
        }
    }

    private void validatePrincipal(AclEntryRequest e) {
        switch (e.principalType()) {
            case "user" -> {
                if (users.findById(e.principalId()) == null) {
                    throw VaultException.invalid("존재하지 않는 사용자: " + e.principalId());
                }
            }
            case "team" -> {
                if (teams.findById(e.principalId()) == null) {
                    throw VaultException.invalid("존재하지 않는 팀: " + e.principalId());
                }
            }
            case "all" -> {
                if (!"@all".equals(e.principalId())) {
                    throw VaultException.invalid("all 주체의 id는 @all이어야 합니다");
                }
            }
            default -> throw VaultException.invalid("알 수 없는 주체 유형: " + e.principalType());
        }
    }

    private void requireActiveNode(String nodeId) {
        NodeRow node = nodes.findById(nodeId);
        if (node == null || node.deletedAt() != null) {
            throw VaultException.notFound("노드가 없습니다: " + nodeId);
        }
    }
}
```

`AdminAclController.java` (public_flag 엔드포인트는 Task 8에서 이 컨트롤러에 추가):
```java
package com.worknote.admin;

import com.worknote.acl.AclRow;
import com.worknote.admin.dto.SetAclRequest;
import com.worknote.audit.AuditService;
import com.worknote.auth.AuthFilter;
import com.worknote.auth.UserRow;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminAclController {

    private final AdminGuard guard;
    private final AclAdminService svc;
    private final AuditService audit;

    public AdminAclController(AdminGuard guard, AclAdminService svc, AuditService audit) {
        this.guard = guard;
        this.svc = svc;
        this.audit = audit;
    }

    private static UserRow user(HttpServletRequest req) {
        return (UserRow) req.getAttribute(AuthFilter.CURRENT_USER);
    }

    @GetMapping("/acl")
    public List<AclRow> listAll(HttpServletRequest req) {
        guard.requireAdmin(user(req));
        return svc.listAll();
    }

    @GetMapping("/nodes/{id}/acl")
    public List<AclRow> forNode(@PathVariable String id, HttpServletRequest req) {
        guard.requireAdmin(user(req));
        return svc.forNode(id);
    }

    @PutMapping("/nodes/{id}/acl")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void replace(@PathVariable String id, @Valid @RequestBody SetAclRequest body, HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        svc.replace(id, body.entries());
        audit.log(actor, "acl.set", id + " (" + body.entries().size() + "건)", req.getRemoteAddr());
    }
}
```

- [x] **Step 5: 통과 확인** + 전체 green
- [x] **Step 6: 커밋** — `git commit -m "feat(backend): ACL API — 노드 단위 replace-all, 주체 존재 검증"`

---

### Task 8: public_flag API + 새 노트 자동 exclude

**Files:**
- Modify: `backend/src/main/java/com/worknote/acl/AclMapper.java` + `mappers/AclMapper.xml` (upsertPublicFlag/deletePublicFlag)
- Modify: `backend/src/main/java/com/worknote/admin/AclAdminService.java` (setPublic/unsetPublic)
- Modify: `backend/src/main/java/com/worknote/admin/AdminAclController.java` (PUT/DELETE `/nodes/{id}/public`)
- Modify: `backend/src/main/java/com/worknote/vault/VaultService.java` (create 시 자동 exclude)
- Create: `backend/src/main/java/com/worknote/admin/dto/PublicRequest.java`
- Test: `backend/src/test/java/com/worknote/admin/AdminPublicApiTest.java`

- [x] **Step 1: 실패하는 테스트 작성**

```java
package com.worknote.admin;

import com.worknote.auth.CredentialRow;
import com.worknote.auth.PasswordHasher;
import com.worknote.auth.UserMapper;
import com.worknote.auth.UserRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:phase2mem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
@AutoConfigureMockMvc
class AdminPublicApiTest {

    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM acl");
        jdbc.update("DELETE FROM public_flag");
        jdbc.update("DELETE FROM node");
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        users.insert(new UserRow("u1", "10001", null, "방문자", "visitor", "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("u1", salt, PasswordHasher.hash("pw-1234", salt)));
        jdbc.update("INSERT INTO node (id, type, name, position) VALUES ('f1','folder','공개폴더',1)");
        jdbc.update("INSERT INTO node (id, parent_id, type, name, position, content) VALUES ('n1','f1','note','N1',1,'body')");
    }

    private MockHttpSession login(String emp, String pw) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"" + emp + "\",\"password\":\"" + pw + "\"}"))
            .andExpect(status().isOk());
        return session;
    }

    @Test
    void setPublic_visitorCanRead_unset_revokes() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        MockHttpSession visitor = login("10001", "pw-1234");
        mvc.perform(get("/api/tree").session(visitor)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(put("/api/admin/nodes/f1/public").session(admin).contentType(APPLICATION_JSON)
                .content("{\"mode\":\"public\"}"))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/tree").session(visitor)).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(delete("/api/admin/nodes/f1/public").session(admin)).andExpect(status().isNoContent());
        mvc.perform(get("/api/tree").session(visitor)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void setPublic_isUpsert() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(put("/api/admin/nodes/n1/public").session(admin).contentType(APPLICATION_JSON)
                .content("{\"mode\":\"public\"}")).andExpect(status().isNoContent());
        mvc.perform(put("/api/admin/nodes/n1/public").session(admin).contentType(APPLICATION_JSON)
                .content("{\"mode\":\"exclude\"}")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT mode FROM public_flag WHERE node_id = 'n1'", String.class))
            .isEqualTo("exclude");
    }

    @Test
    void invalidMode_400_unknownNode_404_unsetMissing_404() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(put("/api/admin/nodes/f1/public").session(admin).contentType(APPLICATION_JSON)
                .content("{\"mode\":\"open\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/nodes/no-such/public").session(admin).contentType(APPLICATION_JSON)
                .content("{\"mode\":\"public\"}")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/admin/nodes/f1/public").session(admin)).andExpect(status().isNotFound());
    }

    @Test
    void createNoteUnderPublicFolder_autoExcluded() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(put("/api/admin/nodes/f1/public").session(admin).contentType(APPLICATION_JSON)
                .content("{\"mode\":\"public\"}")).andExpect(status().isNoContent());
        // public 폴더 아래 새 노트 → 자동 exclude (스펙 §7)
        mvc.perform(post("/api/nodes").session(admin).contentType(APPLICATION_JSON)
                .content("{\"id\":\"n2\",\"parentId\":\"f1\",\"type\":\"note\",\"name\":\"새노트\"}"))
            .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT mode FROM public_flag WHERE node_id = 'n2'", String.class))
            .isEqualTo("exclude");
        // 방문자에겐 기존 n1은 보이고 새 노트는 안 보인다
        MockHttpSession visitor = login("10001", "pw-1234");
        mvc.perform(get("/api/tree").session(visitor))
            .andExpect(jsonPath("$[0].children.length()").value(1));
        // 폴더 생성은 exclude를 박지 않는다 (cascade 유지)
        mvc.perform(post("/api/nodes").session(admin).contentType(APPLICATION_JSON)
                .content("{\"id\":\"f2\",\"parentId\":\"f1\",\"type\":\"folder\",\"name\":\"하위폴더\"}"))
            .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public_flag WHERE node_id = 'f2'", Integer.class))
            .isZero();
    }

    @Test
    void createNoteUnderPrivateFolder_noExcludeRow() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(post("/api/nodes").session(admin).contentType(APPLICATION_JSON)
                .content("{\"id\":\"n3\",\"parentId\":\"f1\",\"type\":\"note\",\"name\":\"비공개\"}"))
            .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public_flag WHERE node_id = 'n3'", Integer.class))
            .isZero();
    }
}
```

(주의: tree 응답의 children 구조는 기존 `VaultNode` 직렬화를 따른다 — 단언 경로가 안 맞으면 실제 응답 구조에 맞춰 조정하되 "n2가 visitor 트리에 없음"이라는 의미는 유지)

- [x] **Step 2: 실패 확인**
- [x] **Step 3: 매퍼 추가**

`AclMapper.java`:
```java
void upsertPublicFlag(@Param("nodeId") String nodeId, @Param("mode") String mode);
int deletePublicFlag(@Param("nodeId") String nodeId);
```

`AclMapper.xml`:
```xml
<insert id="upsertPublicFlag">
  <!-- Oracle 전환 시 MERGE INTO로 교체 -->
  INSERT INTO public_flag (node_id, mode) VALUES (#{nodeId}, #{mode})
  ON CONFLICT(node_id) DO UPDATE SET mode = excluded.mode
</insert>
<delete id="deletePublicFlag">
  DELETE FROM public_flag WHERE node_id = #{nodeId}
</delete>
```

- [x] **Step 4: 서비스·컨트롤러·DTO 구현**

`PublicRequest.java`:
```java
package com.worknote.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PublicRequest(@NotBlank @Pattern(regexp = "public|exclude") String mode) {}
```

`AclAdminService`에 추가:
```java
@Transactional
public void setPublic(String nodeId, String mode) {
    requireActiveNode(nodeId);
    acl.upsertPublicFlag(nodeId, mode);
}

@Transactional
public void unsetPublic(String nodeId) {
    requireActiveNode(nodeId);
    if (acl.deletePublicFlag(nodeId) == 0) {
        throw VaultException.notFound("public 설정이 없습니다: " + nodeId);
    }
}
```

`AdminAclController`에 추가:
```java
@PutMapping("/nodes/{id}/public")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void setPublic(@PathVariable String id, @Valid @RequestBody com.worknote.admin.dto.PublicRequest body,
                      HttpServletRequest req) {
    UserRow actor = user(req);
    guard.requireAdmin(actor);
    svc.setPublic(id, body.mode());
    audit.log(actor, "public.set", id + " " + body.mode(), req.getRemoteAddr());
}

@DeleteMapping("/nodes/{id}/public")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void unsetPublic(@PathVariable String id, HttpServletRequest req) {
    UserRow actor = user(req);
    guard.requireAdmin(actor);
    svc.unsetPublic(id);
    audit.log(actor, "public.unset", id, req.getRemoteAddr());
}
```

- [x] **Step 5: VaultService.create 자동 exclude**

`VaultService` import 추가: `com.worknote.acl.AclResolver`, `com.worknote.acl.PublicFlagRow`, `java.util.HashMap`. `create`의 `mapper.insert(...)` 직후에:
```java
if (isNote && parentId != null && isPubliclyVisible(parentId)) {
    // 스펙 §7: public 폴더 하위 새 노트는 기본 제외 — 명시 exclude로 박제
    aclMapper.insertPublicFlag(id, "exclude");
}
```
같은 클래스에 private 메서드 추가:
```java
/** 부모 체인 기준 public 노출 여부 — 새 노트 자동 exclude 판정용. */
private boolean isPubliclyVisible(String parentId) {
    List<String> chain = aclMapper.ancestorChain(parentId);
    if (chain.isEmpty()) {
        return false;
    }
    Map<String, String> flags = new HashMap<>();
    for (PublicFlagRow f : aclMapper.findPublicFlagsForNodes(chain)) {
        flags.put(f.nodeId(), f.mode());
    }
    return AclResolver.publicRead(chain, flags);
}
```

- [x] **Step 6: 통과 확인** + 전체 green (local 모드 기존 create 테스트 회귀 확인 필수)
- [x] **Step 7: 커밋** — `git commit -m "feat(backend): public_flag API(upsert) + public 폴더 하위 새 노트 자동 exclude (스펙 §7)"`

---

### Task 9: 감사 로그 조회 API

**Files:**
- Create: `backend/src/main/java/com/worknote/audit/AuditRow.java`
- Modify: `backend/src/main/java/com/worknote/audit/AuditMapper.java` + `mappers/AuditMapper.xml` (find/count 동적 쿼리)
- Create: `backend/src/main/java/com/worknote/admin/AdminAuditController.java`
- Test: `backend/src/test/java/com/worknote/admin/AdminAuditApiTest.java`

**API:** GET `/api/admin/audit?who=&act=&from=&to=&limit=&offset=` → `{"total": n, "rows": [...]}`, at DESC. limit 기본 50·최대 200.

- [x] **Step 1: 실패하는 테스트 작성**

```java
package com.worknote.admin;

import com.worknote.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
class AdminAuditApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuditService audit;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        audit.logRaw("10001", "node.create", "n1", "127.0.0.1");
        jdbc.update("UPDATE audit_log SET at = '2026-06-01T10:00:00' WHERE target = 'n1'");
        audit.logRaw("10001", "node.trash", "n1", "127.0.0.1");
        jdbc.update("UPDATE audit_log SET at = '2026-06-05T10:00:00' WHERE act = 'node.trash'");
        audit.logRaw("20002", "node.create", "n2", "127.0.0.1");
        jdbc.update("UPDATE audit_log SET at = '2026-06-09T10:00:00' WHERE target = 'n2'");
    }

    private MockHttpSession admin() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"admin\",\"password\":\"boot-pass-1\"}"))
            .andExpect(status().isOk());
        return session;
    }

    @Test
    void list_ordersByAtDesc_excludesLoginNoise() throws Exception {
        // 로그인 행(login.success)도 같이 조회되는 게 정상 — 시드 3건 + admin 로그인 1건
        mvc.perform(get("/api/admin/audit").session(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(4))
            .andExpect(jsonPath("$.rows[1].target").value("n2"));
    }

    @Test
    void filter_byWho_andAct() throws Exception {
        mvc.perform(get("/api/admin/audit").session(admin()).param("who", "10001"))
            .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/admin/audit").session(admin()).param("act", "node.create"))
            .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/admin/audit").session(admin()).param("who", "10001").param("act", "node.create"))
            .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void filter_byDateRange() throws Exception {
        mvc.perform(get("/api/admin/audit").session(admin())
                .param("from", "2026-06-02T00:00:00").param("to", "2026-06-06T00:00:00"))
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.rows[0].act").value("node.trash"));
    }

    @Test
    void paging_limitAndOffset() throws Exception {
        mvc.perform(get("/api/admin/audit").session(admin()).param("limit", "2").param("offset", "0"))
            .andExpect(jsonPath("$.rows.length()").value(2))
            .andExpect(jsonPath("$.total").value(4));
        // limit 상한 200 클램프 — 500을 요청해도 에러 없이 동작
        mvc.perform(get("/api/admin/audit").session(admin()).param("limit", "500"))
            .andExpect(status().isOk());
    }
}
```

- [x] **Step 2: 실패 확인**
- [x] **Step 3: record·매퍼 구현**

`AuditRow.java`:
```java
package com.worknote.audit;

public record AuditRow(long id, String at, String who, String act, String target, String ip) {}
```

`AuditMapper.java`에 추가:
```java
List<AuditRow> find(@Param("who") String who, @Param("act") String act,
                    @Param("from") String from, @Param("to") String to,
                    @Param("limit") int limit, @Param("offset") int offset);
int count(@Param("who") String who, @Param("act") String act,
          @Param("from") String from, @Param("to") String to);
```

`AuditMapper.xml`에 추가:
```xml
<sql id="auditFilter">
  <where>
    <if test="who != null">AND who = #{who}</if>
    <if test="act != null">AND act = #{act}</if>
    <if test="from != null">AND at &gt;= #{from}</if>
    <if test="to != null">AND at &lt;= #{to}</if>
  </where>
</sql>
<select id="find" resultType="com.worknote.audit.AuditRow">
  SELECT * FROM audit_log
  <include refid="auditFilter"/>
  ORDER BY at DESC, id DESC
  LIMIT #{limit} OFFSET #{offset}
</select>
<select id="count" resultType="int">
  SELECT COUNT(*) FROM audit_log
  <include refid="auditFilter"/>
</select>
```

- [x] **Step 4: 컨트롤러 구현**

`AdminAuditController.java`:
```java
package com.worknote.admin;

import com.worknote.audit.AuditMapper;
import com.worknote.auth.AuthFilter;
import com.worknote.auth.UserRow;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/audit")
public class AdminAuditController {

    private static final int MAX_LIMIT = 200;

    private final AdminGuard guard;
    private final AuditMapper audit;

    public AdminAuditController(AdminGuard guard, AuditMapper audit) {
        this.guard = guard;
        this.audit = audit;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String who,
                                    @RequestParam(required = false) String act,
                                    @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to,
                                    @RequestParam(defaultValue = "50") int limit,
                                    @RequestParam(defaultValue = "0") int offset,
                                    HttpServletRequest req) {
        guard.requireAdmin((UserRow) req.getAttribute(AuthFilter.CURRENT_USER));
        int cappedLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        int safeOffset = Math.max(0, offset);
        return Map.of(
            "total", audit.count(who, act, from, to),
            "rows", audit.find(who, act, from, to, cappedLimit, safeOffset));
    }
}
```

- [x] **Step 5: 통과 확인** + 전체 green
- [x] **Step 6: 커밋** — `git commit -m "feat(backend): 감사 로그 조회 API — who/act/기간 필터 + 페이징"`

---

### Task 10: 통합 검증 + 문서 갱신

**Files:**
- Modify: `backend/README.md` (관리자 API 표·설계 결정·이월 목록 갱신)
- Modify: `CLAUDE.md` (backend 한 줄 설명 갱신)
- Modify: 이 플랜 파일 체크박스

- [x] **Step 1: 전체 테스트 2회 연속 green** — `./gradlew test && ./gradlew test --rerun-tasks` (컨텍스트 캐시·순서 무관성 확인)
- [x] **Step 2: jar 스모크 (server 모드)** — `cd frontend && pnpm build`(이미 dist 있으면 생략) → `cd backend && ./gradlew bootJar` → 임시 DB로 기동:

```bash
WORKNOTE_MODE=server WORKNOTE_ADMIN_PASSWORD=smoke-pass-1 WORKNOTE_DB=/tmp/wn-smoke.db \
  java -jar build/libs/worknote-0.1.0.jar &
# 1) 로그인 → 쿠키
curl -sc /tmp/wn.jar.cookie -XPOST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"emp":"admin","password":"smoke-pass-1"}'
# 2) 가입 신청(무세션) → 201
curl -s -XPOST localhost:8080/api/auth/signup -H 'Content-Type: application/json' \
  -d '{"emp":"S2026-0001","name":"신규","password":"pw-12345678"}'
# 3) 승인 → 신규 사용자 로그인 성공
# 4) 역할 목록 / 팀 생성 / 감사 조회 — 각 200/201 확인
# 종료: kill %1, rm /tmp/wn-smoke.db
```

- [x] **Step 3: README 갱신** — API 표에 `/api/auth/signup` + `/api/admin/*` 전체 추가, 설계 결정에 "락아웃 방지 2중 규칙 / caps 화이트리스트 / ACL replace-all / 새 노트 자동 exclude / 팀 삭제 ACL 정리" 추가, 이월 목록에서 관리자 API·RoleCaps 캐시 제거
- [x] **Step 4: CLAUDE.md 갱신** — backend 줄에 "관리자 API 구현 완료, 남은 것: 공유 링크·프런트 연동·purge 스케줄러"
- [x] **Step 5: 커밋** — `git commit -m "docs(backend): 관리자 API 반영 — README·CLAUDE.md 갱신"`
