# 4단계 이월 3건 (본인 비번 변경 · 401 유실 복구 · 다운 배너) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 4단계(프런트 연동)에서 이월된 운영 견고성 3건을 마감한다 — ① 본인 비밀번호 변경 실 API ② 401/크래시로 인한 디바운스 편집 유실 복구 ③ http 모드 백엔드 다운 시 "가짜 정상" 대신 차단 배너.

**Architecture:** 백엔드는 기존 세션/감사/예외 패턴 위에 `POST /api/auth/change-password` 1엔드포인트 증설(본인 세션 salt 갱신으로 자기 세션 유지). 프런트는 ① 미전송 편집을 localStorage(`wn.pending.v1`)에 write-through 후 재로그인 시 복구, ② 초기 tree fetch 실패(401 제외)를 차단 화면으로 승격. 모든 신규 검증·판정 로직은 순수 함수로 분리해 node 환경 단위 테스트(jsdom 미도입 — 컴포넌트는 통합 리뷰).

**Tech Stack:** Java 21 / Spring Boot 3.5 / MyBatis (backend), Vite 6 / React 18 / TypeScript / Vitest (frontend).

---

## 확정 설계 결정 (D1–D11)

| # | 결정 |
|---|------|
| **D1** | 비번 변경 에러는 **VaultException.invalid(422)** — 절대 AuthException.unauthorized(401) 금지. 401이면 프런트 on401이 로그아웃시킴. 현재 비번 오류·짧은 새 비번·동일 비번 모두 422 |
| **D2** | 변경 후 컨트롤러가 **세션 `SESSION_CRED`를 새 salt로 갱신** → 본인 현재 세션 유지. 다른 기기 세션은 옛 salt라 AuthFilter가 자동 401(의도된 보안) |
| **D3** | 백엔드도 **새 비번 10자 이상 재검증**(프런트 우회 방지). 현재 비번 검증을 가장 먼저(실패 시 즉시 422) |
| **D4** | 감사 act = `auth.password.change` (신규). `audit.log(user, act, null, ip)`. KNOWN_ACTS(mappers.ts) 추가, actType은 "etc" 폴백 |
| **D5** | change-password는 AuthFilter ALLOWLIST에 **넣지 않음** → server 모드에서 미인증 요청은 필터가 먼저 401. local 모드(필터 미등록)는 CURRENT_USER null → 컨트롤러 방어 403 |
| **D6** | 401/크래시 편집 유실 복구 = **localStorage write-through 스냅샷**. `pendingStore`(`wn.pending.v1`, Map<noteId, {title?,content?,tags?}>). updateNote마다 미러링, 서버 확정 시 삭제 |
| **D7** | 복구는 **App에서** 수행(tree 접근으로 존재 노트만 재적용). ready 시 1회, http 전용. clearAllPending 후 존재 노트만 `actions.updateNote` 재적용(synced 데코레이터가 재미러링+디바운스 재전송) |
| **D8** | 초기 tree fetch 실패 판정 = 순수 `isBackendDown(e, mode)`. http && !(401) → 차단. local·401은 차단 안 함 |
| **D9** | 차단 시 App이 `ConnectionLost`(전체 화면, 재시도=`location.reload()`)를 ready/seed보다 우선 렌더 |
| **D10** | ProfileModal.changePw: http 모드만 실 API. local 모드는 "로컬 모드에서는 비밀번호를 변경할 수 없습니다" 안내(가짜 성공 토스트 제거). 검증은 순수 `validatePasswordChange` 추출 |
| **D11** | **스코프 외**: name/email 본인 프로필 저장은 백엔드 API 부재로 이번 미포함 — 기존 localStorage mock 유지, README 이월. 비밀번호만 실 API |

## File Structure

**Task 1 (backend, 본인 비번 변경):**
- Create `backend/src/main/java/com/worknote/auth/dto/ChangePasswordRequest.java`
- Modify `backend/src/main/java/com/worknote/auth/AuthService.java` (changePassword)
- Modify `backend/src/main/java/com/worknote/auth/AuthController.java` (endpoint + 세션 salt 갱신 + 감사)
- Create `backend/src/test/java/com/worknote/auth/ChangePasswordApiTest.java` (server 모드 통합)
- Modify `backend/src/test/java/com/worknote/auth/AuthServiceTest.java` (단위 3케이스 추가)

**Task 2 (frontend, 비번 변경 배선):**
- Modify `frontend/src/api/auth.ts` (changePassword)
- Create `frontend/src/components/passwordValidation.ts` (순수 검증)
- Create `frontend/src/components/passwordValidation.test.ts`
- Create `frontend/src/api/auth.test.ts` (fetch stub)
- Modify `frontend/src/components/ProfileModal.tsx` (changePw async + 모드 분기)
- Modify `frontend/src/admin/mappers.ts` (KNOWN_ACTS)

**Task 3 (frontend, 401 유실 복구):**
- Create `frontend/src/state/pendingStore.ts`
- Create `frontend/src/state/pendingStore.test.ts`
- Modify `frontend/src/state/useVaultSync.ts` (write-through + clear-on-success)
- Modify `frontend/src/App.tsx` (복구 effect)

**Task 4 (frontend, 다운 배너):**
- Create `frontend/src/state/loadErrorPolicy.ts`
- Create `frontend/src/state/loadErrorPolicy.test.ts`
- Modify `frontend/src/state/useVault.ts` (loadError 상태)
- Create `frontend/src/components/ConnectionLost.tsx`
- Modify `frontend/src/App.tsx` (loadError 차단 분기)
- Modify `frontend/src/styles/app.css` (.conn-lost)

---

## Task 1: 백엔드 본인 비밀번호 변경 API

**Files:**
- Create: `backend/src/main/java/com/worknote/auth/dto/ChangePasswordRequest.java`
- Modify: `backend/src/main/java/com/worknote/auth/AuthService.java`
- Modify: `backend/src/main/java/com/worknote/auth/AuthController.java`
- Test: `backend/src/test/java/com/worknote/auth/ChangePasswordApiTest.java`, `AuthServiceTest.java`

- [ ] **Step 1: 단위 테스트 작성 (AuthServiceTest 보강)**

`AuthServiceTest.java` 상단 import에 추가:
```java
import com.worknote.vault.VaultException;
```
클래스 끝(마지막 `}` 직전)에 추가:
```java
    @Test
    void changePasswordReplacesCredentialAndReturnsNewSalt() {
        createUser("u1", "10001", "operator", "active", "pw-current");
        String oldSalt = users.findCredential("u1").salt();
        String newSalt = auth.changePassword("u1", "pw-current", "new-pw-9999");
        assertThat(newSalt).isNotEqualTo(oldSalt);
        assertThat(users.findCredential("u1").salt()).isEqualTo(newSalt);
        assertThat(auth.login("10001", "new-pw-9999").user().id()).isEqualTo("u1"); // 새 비번 검증 통과
    }

    @Test
    void changePasswordWrongCurrentIs422() {
        createUser("u1", "10001", "operator", "active", "pw-current");
        assertThatThrownBy(() -> auth.changePassword("u1", "WRONG", "new-pw-9999"))
            .isInstanceOf(VaultException.class)
            .satisfies(e -> assertThat(((VaultException) e).status()).isEqualTo(VaultException.Status.INVALID));
    }

    @Test
    void changePasswordShortNewIs422() {
        createUser("u1", "10001", "operator", "active", "pw-current");
        assertThatThrownBy(() -> auth.changePassword("u1", "pw-current", "short"))
            .isInstanceOf(VaultException.class)
            .satisfies(e -> assertThat(((VaultException) e).status()).isEqualTo(VaultException.Status.INVALID));
    }
```

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests AuthServiceTest`
Expected: FAIL — `auth.changePassword` 메서드 없음 (컴파일 에러).

- [ ] **Step 3: AuthService.changePassword 구현**

`AuthService.java` 의 `signup` 메서드 다음, `caps` 메서드 앞에 추가:
```java
    /** 본인 비밀번호 변경 — 현재 비밀번호 검증 후 새 salt/hash로 교체.
        반환하는 새 salt로 컨트롤러가 현재 세션의 SESSION_CRED를 갱신해 본인 세션을 유지한다
        (다른 기기 세션은 옛 salt라 AuthFilter가 자동 무효화 — 비번 변경 시 타 기기 로그아웃은 의도된 보안). */
    @Transactional
    public String changePassword(String userId, String currentPassword, String newPassword) {
        CredentialRow cred = users.findCredential(userId);
        if (cred == null || !PasswordHasher.verify(currentPassword, cred.salt(), cred.passwordHash())) {
            throw VaultException.invalid("현재 비밀번호가 올바르지 않습니다");   // 422 — 401 금지(프런트 on401 로그아웃 유발)
        }
        if (newPassword.length() < 10) {
            throw VaultException.invalid("새 비밀번호는 10자 이상이어야 합니다");
        }
        if (newPassword.equals(currentPassword)) {
            throw VaultException.invalid("현재 비밀번호와 다른 비밀번호를 사용하세요");
        }
        String salt = PasswordHasher.newSalt();
        users.updateCredential(new CredentialRow(userId, salt, PasswordHasher.hash(newPassword, salt)));
        return salt;
    }
```
(VaultException은 이미 import됨 — line 3.)

- [ ] **Step 4: 단위 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests AuthServiceTest`
Expected: PASS (기존 + 신규 3케이스).

- [ ] **Step 5: 통합 테스트 작성 (ChangePasswordApiTest 신규)**

`backend/src/test/java/com/worknote/auth/ChangePasswordApiTest.java`:
```java
package com.worknote.auth;

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

/** 본인 비밀번호 변경(POST /api/auth/change-password) — server 모드. AuthControllerTest 컨벤션(u-admin 보존) 따름. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:phase2mem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
@AutoConfigureMockMvc
class ChangePasswordApiTest {
    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        users.insert(new UserRow("u1", "10001", null, "홍길동", "operator", "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("u1", salt, PasswordHasher.hash("pw-current", salt)));
    }

    private MockHttpSession login(String emp, String pw) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"" + emp + "\",\"password\":\"" + pw + "\"}"))
            .andExpect(status().isOk());
        return session;
    }

    private static String body(String cur, String next) {
        return "{\"currentPassword\":\"" + cur + "\",\"newPassword\":\"" + next + "\"}";
    }

    // 1. 성공 → 204 + 본인 세션 유지(같은 세션 me 200) + 새 비번 로그인 가능
    @Test
    void changeSucceedsKeepsOwnSession() throws Exception {
        MockHttpSession s = login("10001", "pw-current");
        mvc.perform(post("/api/auth/change-password").session(s).contentType(APPLICATION_JSON)
                .content(body("pw-current", "new-pw-9999")))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(s)).andExpect(status().isOk());           // 본인 세션 유지
        mvc.perform(post("/api/auth/login").session(new MockHttpSession()).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10001\",\"password\":\"new-pw-9999\"}"))
            .andExpect(status().isOk());                                                   // 새 비번으로 신규 로그인
    }

    // 2. 다른 기기(옛 salt) 세션은 변경 후 무효 → 401
    @Test
    void otherSessionInvalidatedAfterChange() throws Exception {
        MockHttpSession other = login("10001", "pw-current");
        MockHttpSession s = login("10001", "pw-current");
        mvc.perform(post("/api/auth/change-password").session(s).contentType(APPLICATION_JSON)
                .content(body("pw-current", "new-pw-9999")))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(other)).andExpect(status().isUnauthorized());
    }

    // 3. 현재 비번 틀림 → 422 (401 아님 — 세션 유지)
    @Test
    void wrongCurrentPasswordIs422NotUnauthorized() throws Exception {
        MockHttpSession s = login("10001", "pw-current");
        mvc.perform(post("/api/auth/change-password").session(s).contentType(APPLICATION_JSON)
                .content(body("WRONG", "new-pw-9999")))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/auth/me").session(s)).andExpect(status().isOk());            // 세션 유효 유지
    }

    // 4. 새 비번 10자 미만 → 422
    @Test
    void shortNewPasswordIs422() throws Exception {
        MockHttpSession s = login("10001", "pw-current");
        mvc.perform(post("/api/auth/change-password").session(s).contentType(APPLICATION_JSON)
                .content(body("pw-current", "short")))
            .andExpect(status().isUnprocessableEntity());
    }

    // 5. 새 비번 == 현재 비번 → 422
    @Test
    void sameAsCurrentIs422() throws Exception {
        MockHttpSession s = login("10001", "pw-current");
        mvc.perform(post("/api/auth/change-password").session(s).contentType(APPLICATION_JSON)
                .content(body("pw-current", "pw-current")))
            .andExpect(status().isUnprocessableEntity());
    }

    // 6. 빈 본문 → 400 (@NotBlank) — 단, 세션 있어 필터 통과 후 검증
    @Test
    void blankBodyIs400() throws Exception {
        MockHttpSession s = login("10001", "pw-current");
        mvc.perform(post("/api/auth/change-password").session(s).contentType(APPLICATION_JSON)
                .content(body("", "")))
            .andExpect(status().isBadRequest());
    }

    // 7. 미인증(세션 없음) → 401 (AuthFilter가 컨트롤러 전에 차단)
    @Test
    void unauthenticatedIs401() throws Exception {
        mvc.perform(post("/api/auth/change-password").contentType(APPLICATION_JSON)
                .content(body("pw-current", "new-pw-9999")))
            .andExpect(status().isUnauthorized());
    }

    // 8. 감사 기록 auth.password.change 1건
    @Test
    void changeIsAudited() throws Exception {
        MockHttpSession s = login("10001", "pw-current");
        mvc.perform(post("/api/auth/change-password").session(s).contentType(APPLICATION_JSON)
                .content(body("pw-current", "new-pw-9999")))
            .andExpect(status().isNoContent());
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act = 'auth.password.change'", Integer.class);
        assertThat(n).isEqualTo(1);
    }
}
```

- [ ] **Step 6: 실패 확인**

Run: `cd backend && ./gradlew test --tests ChangePasswordApiTest`
Expected: FAIL — 엔드포인트 없음 (404), DTO 없음.

- [ ] **Step 7: ChangePasswordRequest DTO 생성**

`backend/src/main/java/com/worknote/auth/dto/ChangePasswordRequest.java`:
```java
package com.worknote.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** 본인 비밀번호 변경 요청. 길이 정책(새 비번 10자)은 서비스에서 재검증 — DTO는 공백만 차단. */
public record ChangePasswordRequest(
    @NotBlank String currentPassword,
    @NotBlank String newPassword) {}
```

- [ ] **Step 8: AuthController 엔드포인트 추가**

`AuthController.java` import에 추가:
```java
import com.worknote.auth.dto.ChangePasswordRequest;
import jakarta.servlet.http.HttpSession;
```
(`HttpSession`은 logout이 이미 쓰므로 import 존재 가능 — 중복 시 생략.)

`me` 메서드 앞(또는 logout 다음)에 추가:
```java
    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest req, HttpServletRequest http) {
        UserRow user = (UserRow) http.getAttribute(AuthFilter.CURRENT_USER);
        if (user == null) {
            // server 모드는 AuthFilter가 먼저 401 — 여기 도달은 local 모드(무인증, 본인 개념 없음)
            throw AuthException.forbidden("비밀번호 변경은 로그인 상태에서만 가능합니다");
        }
        String newSalt = auth.changePassword(user.id(), req.currentPassword(), req.newPassword());
        HttpSession session = http.getSession(false);
        if (session != null) {
            session.setAttribute(SESSION_CRED, newSalt);   // 본인 현재 세션 유지 (AuthFilter credChanged 통과)
        }
        audit.log(user, "auth.password.change", null, http.getRemoteAddr());
    }
```

- [ ] **Step 9: 전체 백엔드 테스트 통과 확인**

Run: `cd backend && ./gradlew test`
Expected: PASS (기존 248 + 신규 11 = 259). 회귀 없음.

- [ ] **Step 10: 커밋**

```bash
cd backend && git add src/main/java/com/worknote/auth/ src/test/java/com/worknote/auth/
git commit -m "feat: 본인 비밀번호 변경 API (POST /api/auth/change-password)"
```

---

## Task 2: 프런트 비밀번호 변경 배선

**Files:**
- Modify: `frontend/src/api/auth.ts`
- Create: `frontend/src/components/passwordValidation.ts`, `passwordValidation.test.ts`
- Create: `frontend/src/api/auth.test.ts`
- Modify: `frontend/src/components/ProfileModal.tsx`
- Modify: `frontend/src/admin/mappers.ts`

- [ ] **Step 1: 순수 검증 테스트 작성**

`frontend/src/components/passwordValidation.test.ts`:
```ts
import { describe, it, expect } from "vitest";
import { validatePasswordChange } from "./passwordValidation";

describe("validatePasswordChange", () => {
  it("모두 채워지고 10자 이상·일치·현재와 다르면 통과(null)", () => {
    expect(validatePasswordChange("old-pw-123", "new-pw-9999", "new-pw-9999")).toBeNull();
  });
  it("빈 항목이 있으면 안내", () => {
    expect(validatePasswordChange("", "new-pw-9999", "new-pw-9999")).toMatch(/입력/);
  });
  it("새 비번 10자 미만이면 거부", () => {
    expect(validatePasswordChange("old-pw-123", "short", "short")).toMatch(/10자/);
  });
  it("새 비번 확인 불일치면 거부", () => {
    expect(validatePasswordChange("old-pw-123", "new-pw-9999", "different9")).toMatch(/일치/);
  });
  it("새 비번이 현재와 같으면 거부", () => {
    expect(validatePasswordChange("samepass-12", "samepass-12", "samepass-12")).toMatch(/다른/);
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `cd frontend && pnpm test --run passwordValidation`
Expected: FAIL — 모듈 없음.

- [ ] **Step 3: passwordValidation 구현**

`frontend/src/components/passwordValidation.ts`:
```ts
/* 비밀번호 변경 폼 클라이언트 선검증 — null이면 통과, 아니면 에러 메시지. 서버(AuthService)도 동일 정책 재검증. */
export function validatePasswordChange(cur: string, next: string, confirm: string): string | null {
  if (!cur || !next || !confirm) return "모든 비밀번호 항목을 입력하세요.";
  if (next.length < 10) return "새 비밀번호는 10자 이상이어야 합니다.";
  if (next !== confirm) return "새 비밀번호가 일치하지 않습니다.";
  if (next === cur) return "현재 비밀번호와 다른 비밀번호를 사용하세요.";
  return null;
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd frontend && pnpm test --run passwordValidation`
Expected: PASS.

- [ ] **Step 5: AuthApi.changePassword fetch stub 테스트 작성**

`frontend/src/api/auth.test.ts`:
```ts
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { AuthApi } from "./auth";
import { ApiError } from "./http";

describe("AuthApi.changePassword", () => {
  beforeEach(() => { vi.stubGlobal("fetch", vi.fn()); });
  afterEach(() => { vi.unstubAllGlobals(); });

  it("POST /auth/change-password 로 두 비번을 본문에 담아 호출", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue({ ok: true, status: 204, json: () => Promise.resolve(undefined) });
    await AuthApi.changePassword("cur-pw-123", "new-pw-9999");
    expect(fetch).toHaveBeenCalledWith("/api/auth/change-password", expect.objectContaining({
      method: "POST",
      body: JSON.stringify({ currentPassword: "cur-pw-123", newPassword: "new-pw-9999" }),
    }));
  });

  it("422 응답은 ApiError(status 422, 서버 메시지)로 던진다", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
      ok: false, status: 422, json: () => Promise.resolve({ error: "현재 비밀번호가 올바르지 않습니다" }),
    });
    await expect(AuthApi.changePassword("wrong", "new-pw-9999")).rejects.toMatchObject({
      name: "ApiError", status: 422, message: "현재 비밀번호가 올바르지 않습니다",
    });
    void ApiError; // import 유지
  });
});
```

- [ ] **Step 6: 실패 확인**

Run: `cd frontend && pnpm test --run auth`
Expected: FAIL — `AuthApi.changePassword` 없음.

- [ ] **Step 7: AuthApi.changePassword 추가**

`frontend/src/api/auth.ts` 의 `me:` 라인 다음(객체 마지막)에 추가:
```ts
  changePassword: (currentPassword: string, newPassword: string) =>
    req<void>("/auth/change-password", { method: "POST", body: JSON.stringify({ currentPassword, newPassword }) }),
```

- [ ] **Step 8: 통과 확인**

Run: `cd frontend && pnpm test --run auth`
Expected: PASS.

- [ ] **Step 9: ProfileModal.changePw 실 API 배선**

`frontend/src/components/ProfileModal.tsx` import 블록(상단)에 추가:
```ts
import { AuthApi } from "../api/auth";
import { storageMode } from "../storage";
import { ApiError } from "../api/http";
import { validatePasswordChange } from "./passwordValidation";
```
`changePw` 함수 전체(라인 40-49)를 교체:
```ts
  const changePw = async () => {
    const err = validatePasswordChange(curPw, newPw, newPw2);
    if (err) { setPwMsg({ type: "err", text: err }); return; }
    if (storageMode !== "http") {
      // local 모드(무인증 단일 PC) — 서버 신원이 없어 변경 대상이 없음
      setPwMsg({ type: "err", text: "로컬 모드에서는 비밀번호를 변경할 수 없습니다." });
      return;
    }
    try {
      await AuthApi.changePassword(curPw, newPw);
      setCurPw(""); setNewPw(""); setNewPw2("");
      setPwMsg(null);
      toast && toast("비밀번호를 변경했습니다", "check");
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : "비밀번호 변경에 실패했습니다.";
      setPwMsg({ type: "err", text: msg });
    }
  };
```

- [ ] **Step 10: KNOWN_ACTS 에 auth.password.change 추가**

`frontend/src/admin/mappers.ts` 의 KNOWN_ACTS 첫 줄(`"login.success", ...`)을 교체:
```ts
  "login.success", "login.fail", "logout", "signup", "signup.fail", "auth.password.change",
```

- [ ] **Step 11: 전체 프런트 테스트 + 타입체크 통과 확인**

Run: `cd frontend && pnpm test --run && pnpm build`
Expected: PASS — 기존 128 + 신규 7 = 135, 빌드(tsc 포함) 성공. mappers 드리프트 가드 테스트가 있으면 통과(추가만 했으므로).

- [ ] **Step 12: 커밋**

```bash
cd frontend && git add src/api/auth.ts src/api/auth.test.ts src/components/passwordValidation.ts src/components/passwordValidation.test.ts src/components/ProfileModal.tsx src/admin/mappers.ts
git commit -m "feat: 본인 비밀번호 변경 실 API 배선 (ProfileModal http 모드)"
```

---

## Task 3: 401/크래시 디바운스 편집 유실 복구

**Files:**
- Create: `frontend/src/state/pendingStore.ts`, `pendingStore.test.ts`
- Modify: `frontend/src/state/useVaultSync.ts`
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: pendingStore 테스트 작성**

`frontend/src/state/pendingStore.test.ts`:
```ts
import { describe, it, expect, beforeEach, vi, afterEach } from "vitest";
import { savePending, clearPending, loadPending, clearAllPending } from "./pendingStore";

// node 환경 localStorage 스텁
function stubStorage() {
  const data = new Map<string, string>();
  vi.stubGlobal("localStorage", {
    getItem: (k: string) => (data.has(k) ? data.get(k)! : null),
    setItem: (k: string, v: string) => { data.set(k, v); },
    removeItem: (k: string) => { data.delete(k); },
  });
}

describe("pendingStore", () => {
  beforeEach(() => { stubStorage(); });
  afterEach(() => { vi.unstubAllGlobals(); });

  it("save 후 load 로 patch 회수", () => {
    savePending("n1", { title: "T", content: "C" });
    expect(loadPending()).toEqual({ n1: { title: "T", content: "C" } });
  });

  it("같은 id 재저장은 덮어쓴다", () => {
    savePending("n1", { content: "a" });
    savePending("n1", { content: "b", tags: ["x"] });
    expect(loadPending().n1).toEqual({ content: "b", tags: ["x"] });
  });

  it("여러 노트를 독립 보관", () => {
    savePending("n1", { content: "a" });
    savePending("n2", { content: "b" });
    expect(Object.keys(loadPending()).sort()).toEqual(["n1", "n2"]);
  });

  it("clearPending 은 해당 id만 제거", () => {
    savePending("n1", { content: "a" });
    savePending("n2", { content: "b" });
    clearPending("n1");
    expect(loadPending()).toEqual({ n2: { content: "b" } });
  });

  it("clearAllPending 은 전부 제거", () => {
    savePending("n1", { content: "a" });
    clearAllPending();
    expect(loadPending()).toEqual({});
  });

  it("손상된 JSON 은 빈 객체로 폴백", () => {
    localStorage.setItem("wn.pending.v1", "{not json");
    expect(loadPending()).toEqual({});
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `cd frontend && pnpm test --run pendingStore`
Expected: FAIL — 모듈 없음.

- [ ] **Step 3: pendingStore 구현**

`frontend/src/state/pendingStore.ts`:
```ts
/* pendingStore — 서버에 아직 반영되지 않은 노트 편집(디바운스 대기·네트워크 실패)을 localStorage에 미러링.
   세션 만료(401 리다이렉트)·탭 종료·크래시로 편집이 조용히 사라지지 않도록 write-through 후 재로그인 시 복구한다. */
const KEY = "wn.pending.v1";
export type PendingPatch = { title?: string; content?: string; tags?: string[] };

function readAll(): Record<string, PendingPatch> {
  try { return JSON.parse(localStorage.getItem(KEY) || "{}") as Record<string, PendingPatch>; }
  catch { return {}; }
}
function writeAll(map: Record<string, PendingPatch>): void {
  try { localStorage.setItem(KEY, JSON.stringify(map)); }
  catch { /* 용량 초과 등 — 복구는 best-effort, 실패해도 본 동기화는 진행 */ }
}

export function savePending(id: string, patch: PendingPatch): void {
  const map = readAll(); map[id] = patch; writeAll(map);
}
export function clearPending(id: string): void {
  const map = readAll();
  if (id in map) { delete map[id]; writeAll(map); }
}
export function loadPending(): Record<string, PendingPatch> {
  return readAll();
}
export function clearAllPending(): void {
  try { localStorage.removeItem(KEY); } catch { /* 무시 */ }
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd frontend && pnpm test --run pendingStore`
Expected: PASS (6케이스).

- [ ] **Step 5: useVaultSync write-through + clear-on-success 배선**

`frontend/src/state/useVaultSync.ts` import에 추가:
```ts
import { savePending, clearPending } from "./pendingStore";
```
`fire` 함수(라인 114-119)를 onSuccess 콜백 지원으로 교체:
```ts
  const fire = (op: SyncOp, onSuccess?: () => void) => {
    syncAction(VaultApi, op)
      .then(() => onSuccess?.())
      .catch((e: unknown) => {
        if (e instanceof ApiError && e.status === 401) return; // 세션 만료 — 전역 on401 리다이렉트가 처리(pending은 localStorage에 남아 복구됨)
        toastRef.current("서버 동기화 실패: " + (e instanceof Error ? e.message : String(e)));
      });
  };
```
`remove` 핸들러(라인 135-139)에 clearPending 추가:
```ts
      remove: (id) => {
        cancelPending(id);   // 삭제된 노트로 늦은 PATCH가 날아가지 않도록
        clearPending(id);    // 미전송 미러도 함께 폐기
        actionsRef.current.remove(id);
        fire({ kind: "remove", id });
      },
```
`updateNote` 핸들러(라인 144-158)를 write-through + clear-on-success로 교체:
```ts
      updateNote: (id, patch) => {
        actionsRef.current.updateNote(id, patch);
        if (patch.title === undefined && patch.content === undefined && patch.tags === undefined) return;
        const prev = pendingRef.current.get(id);
        if (prev) clearTimeout(prev.timer);
        const merged: MergedPatch = { ...prev?.patch };
        if (patch.title !== undefined) merged.title = patch.title; // flush 때 name으로 변환
        if (patch.content !== undefined) merged.content = patch.content;
        if (patch.tags !== undefined) merged.tags = patch.tags;
        savePending(id, merged);   // write-through — 401/크래시로 끊겨도 미전송분 복구 가능
        const timer = setTimeout(() => {
          pendingRef.current.delete(id);
          fire(buildUpdateOp(id, merged), () => clearPending(id)); // 서버 확정 시에만 미러 제거
        }, PATCH_DEBOUNCE);
        pendingRef.current.set(id, { timer, patch: merged });
      },
```
언마운트 flush(라인 174-183)의 fire에도 clear-on-success 부여:
```ts
  useEffect(() => {
    return () => {
      for (const [id, p] of pendingRef.current) {
        clearTimeout(p.timer);
        fire(buildUpdateOp(id, p.patch), () => clearPending(id));
      }
      pendingRef.current.clear();
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
```

- [ ] **Step 6: App 복구 effect 추가**

`frontend/src/App.tsx` import에 추가:
```ts
import { loadPending, clearAllPending } from "./state/pendingStore";
```
`bootstrapIfEmpty` effect(라인 100-104) 다음에 복구 effect 추가:
```ts
  // ---- 미저장 편집 복구 (HTTP 모드: 401/크래시로 유실된 디바운스 편집을 재로그인 후 재적용·재전송) ----
  const recoveredRef = useRef(false);
  useEffect(() => {
    if (storageMode !== "http" || !ready || recoveredRef.current) return;
    recoveredRef.current = true;
    const pending = loadPending();
    const ids = Object.keys(pending);
    if (ids.length === 0) return;
    clearAllPending(); // 존재 노트만 재적용이 다시 미러링 — 사라진 노트는 자연 정리
    let n = 0;
    for (const id of ids) {
      if (!findNode(tree, id).node) continue; // 그 사이 삭제된 노트는 건너뜀
      actions.updateNote(id, pending[id]);    // synced.updateNote → 재미러링 + 디바운스 재전송
      n++;
    }
    if (n > 0) toast("미저장 변경 " + n + "건을 복구해 다시 저장합니다", "check");
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready]);
```
(`useRef`, `findNode`, `storageMode`, `tree`, `actions`, `toast`는 이미 App에 존재.)

- [ ] **Step 7: 전체 프런트 테스트 + 빌드 확인**

Run: `cd frontend && pnpm test --run && pnpm build`
Expected: PASS — 기존 135 + 신규 6 = 141, 빌드 성공.

- [ ] **Step 8: 커밋**

```bash
cd frontend && git add src/state/pendingStore.ts src/state/pendingStore.test.ts src/state/useVaultSync.ts src/App.tsx
git commit -m "feat: 401/크래시 시 디바운스 편집 유실 복구 (pending write-through)"
```

---

## Task 4: http 모드 백엔드 다운 차단 배너

**Files:**
- Create: `frontend/src/state/loadErrorPolicy.ts`, `loadErrorPolicy.test.ts`
- Modify: `frontend/src/state/useVault.ts`
- Create: `frontend/src/components/ConnectionLost.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/styles/app.css`

- [ ] **Step 1: 판정 순수 함수 테스트 작성**

`frontend/src/state/loadErrorPolicy.test.ts`:
```ts
import { describe, it, expect } from "vitest";
import { isBackendDown } from "./loadErrorPolicy";
import { ApiError } from "../api/http";

describe("isBackendDown", () => {
  it("local 모드는 절대 차단하지 않음", () => {
    expect(isBackendDown(new Error("x"), "local")).toBe(false);
    expect(isBackendDown(new ApiError("e", 500), "local")).toBe(false);
  });
  it("http 401 은 차단 안 함 (on401 리다이렉트가 처리)", () => {
    expect(isBackendDown(new ApiError("unauth", 401), "http")).toBe(false);
  });
  it("http 네트워크 오류·5xx 는 차단", () => {
    expect(isBackendDown(new TypeError("fetch failed"), "http")).toBe(true);
    expect(isBackendDown(new ApiError("boom", 500), "http")).toBe(true);
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `cd frontend && pnpm test --run loadErrorPolicy`
Expected: FAIL — 모듈 없음.

- [ ] **Step 3: loadErrorPolicy 구현**

`frontend/src/state/loadErrorPolicy.ts`:
```ts
import { ApiError } from "../api/http";

/** 초기 트리 로드 실패를 "백엔드 다운(차단 화면)"으로 볼지 판정.
    http 모드에서 401(세션 만료 — on401 리다이렉트가 처리)만 제외한 모든 실패가 차단 대상.
    local 모드는 localStorage라 사실상 실패하지 않고, 실패해도 seed가 정상 — 차단하지 않는다. */
export function isBackendDown(e: unknown, mode: "http" | "local"): boolean {
  if (mode !== "http") return false;
  if (e instanceof ApiError && e.status === 401) return false;
  return true;
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd frontend && pnpm test --run loadErrorPolicy`
Expected: PASS.

- [ ] **Step 5: useVault 에 loadError 상태 추가**

`frontend/src/state/useVault.ts` import에 추가:
```ts
import { storageMode } from "../storage";
import { isBackendDown } from "./loadErrorPolicy";
```
`const [ready, setReady] = useState(false);`(라인 17) 다음에 추가:
```ts
  const [loadError, setLoadError] = useState(false); // http 백엔드 다운 → App 차단 화면
```
초기 load의 catch(라인 34-38)를 교체:
```ts
    }).catch((e) => {
      console.warn("vault load failed — falling back to seed", e);
      if (isBackendDown(e, storageMode)) setLoadError(true); // http 다운: seed를 '정상'인 양 보여주지 않고 차단
      readyRef.current = true;
      setReady(true); // 시드 트리로 렌더 (차단 화면이 App에서 우선)
    });
```
return(라인 92)에 loadError 추가:
```ts
  return { tree, actions, savedTick, ready, loadError };
```

- [ ] **Step 6: ConnectionLost 컴포넌트 생성**

`frontend/src/components/ConnectionLost.tsx`:
```tsx
/* ConnectionLost — HTTP 모드에서 초기 데이터 로드 실패(백엔드 다운) 시의 전체 차단 화면.
   seed를 정상인 양 노출하지 않는다 — 편집이 저장되지 않으므로 진입 자체를 막고 재시도를 유도. */
import React from "react";
import { Icon } from "./Icon";

const h = React.createElement;

export function ConnectionLost({ onRetry }: { onRetry: () => void }) {
  return h("div", { className: "conn-lost" },
    h("div", { className: "cl-card" },
      h("div", { className: "cl-icon" }, h(Icon, { name: "alert" })),
      h("h2", null, "서버에 연결할 수 없습니다"),
      h("p", null, "편집 내용이 저장되지 않습니다. 서버 상태를 확인한 뒤 다시 시도하세요."),
      h("button", { className: "pf-btn primary", onClick: onRetry }, "다시 시도")));
}
```

- [ ] **Step 7: App 에서 loadError 차단 분기**

`frontend/src/App.tsx` import에 추가:
```ts
import { ConnectionLost } from "./components/ConnectionLost";
```
useVault 구조분해(라인 48)를 교체:
```ts
  const { tree, actions: rawActions, savedTick, ready, loadError } = useVault(repository);
```
`if (!ready) return null;`(라인 183)을 교체:
```ts
  if (loadError) return createElement(ConnectionLost, { onRetry: () => location.reload() });
  if (!ready) return null;
```

- [ ] **Step 8: CSS 추가**

`frontend/src/styles/app.css` 끝에 추가:
```css
/* 백엔드 다운 차단 화면 */
.conn-lost { position: fixed; inset: 0; display: flex; align-items: center; justify-content: center; background: var(--bg, #fff); z-index: 1000; }
.cl-card { max-width: 360px; text-align: center; padding: 32px 28px; }
.cl-icon { width: 48px; height: 48px; margin: 0 auto 16px; color: var(--danger, #c0392b); }
.cl-icon svg { width: 100%; height: 100%; }
.cl-card h2 { font-size: 18px; margin: 0 0 8px; }
.cl-card p { font-size: 13.5px; color: var(--muted, #888); margin: 0 0 20px; line-height: 1.5; }
```
(실제 CSS 변수명은 app.css 기존 정의를 따른다 — `--bg`/`--muted`/`--danger`가 없으면 인접 스타일이 쓰는 변수로 교체.)

- [ ] **Step 9: 전체 프런트 테스트 + 빌드 확인**

Run: `cd frontend && pnpm test --run && pnpm build`
Expected: PASS — 기존 141 + 신규 3 = 144, 빌드 성공.

- [ ] **Step 10: 커밋**

```bash
cd frontend && git add src/state/loadErrorPolicy.ts src/state/loadErrorPolicy.test.ts src/state/useVault.ts src/components/ConnectionLost.tsx src/App.tsx src/styles/app.css
git commit -m "feat: http 모드 백엔드 다운 시 차단 화면 (가짜 정상 제거)"
```

---

## 최종 통합 검증 (모든 Task 후)

- [ ] **백엔드 전체 테스트**: `cd backend && ./gradlew test` — 259 green
- [ ] **프런트 전체 테스트 + 빌드**: `cd frontend && pnpm test --run && pnpm build` — 144 green, dist 생성
- [ ] **bootJar**: `cd backend && ./gradlew bootJar` (frontend dist 포함)
- [ ] **server 모드 jar 스모크**: 로그인 → 비번 변경(204, 같은 세션 me 200) → 옛 비번 로그인 실패 → 새 비번 로그인 성공 → 잘못된 현재 비번(422·세션 유지) → 감사 `auth.password.change` 1건
- [ ] **local 모드 스모크**: 노트 앱 기존 동작 무변경(ProfileModal 비번 변경 시 "로컬 모드" 안내), 차단 화면 미발생
- [ ] 최종 통합 리뷰 subagent: D1–D11 준수 + 교차 정합(세션 salt 갱신/401 제외 판정/write-through 생명주기) 대조

## Self-Review (작성자 체크)

- **스펙 커버리지**: 이월 3건(본인 비번 변경 / 401 유실 복구 / 다운 배너) 각각 Task 1·2 / 3 / 4로 매핑. name/email 본인 수정은 D11로 명시적 스코프 제외(이월).
- **타입 정합**: `PendingPatch{title?,content?,tags?}` ↔ `MergedPatch`(useVaultSync) ↔ `Partial<NoteNode>`(actions.updateNote) 필드명 일치(title/content/tags) — 복구 시 무변환 전달. `changePassword(cur, next)` 시그니처 백엔드 DTO(currentPassword/newPassword) ↔ AuthApi body ↔ 테스트 본문 일치. `isBackendDown(e, mode)` mode 타입 "http"|"local" = storageMode 타입.
- **401 일관성**: 비번 변경 오류는 422(D1)로 on401 미발동, 초기 로드 401은 차단 제외(D8)로 리다이렉트에 위임 — 두 경로 모두 401 의미론 보존.
- **플레이스홀더 스캔**: 모든 step에 실제 코드/명령/기대결과 포함. CSS 변수명만 "기존 정의 따름" 단서 — 구현자가 app.css 확인.
