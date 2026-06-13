# 본인 프로필 수정 API · 드래그앤드롭 이동 · 비번 길이 정책 통일 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ① 본인 프로필(name/email) 수정을 실 API로 전환, ③ 폴더/노트 이동을 드래그앤드롭으로 보강, ④ 비밀번호 최소 길이를 10자로 상향 통일.

**Architecture:** ① 기존 change-password 패턴 재사용(CURRENT_USER·audit·VaultException 422). MeResponse에 email 추가해 모달이 서버값을 프리로드, 저장 응답으로 세션 me 갱신. ③ HTML5 DnD를 Sidebar Row에 부착, 드롭 검증은 순수 `canDropOn`, 드롭 시 기존 move-preview/경고 흐름을 재사용(경고 UI는 MoveModal에서 추출해 공유). ④ 매직넘버를 백엔드 `PasswordPolicy.MIN_LENGTH`/프런트 `MIN_PASSWORD_LENGTH` 상수로 단일화.

**Tech Stack:** Java 21 + Spring Boot 3.5 + MyBatis(백엔드), Vite + React 18 + TypeScript(프런트), Vitest(node env, 순수함수+fetch stub만), JUnit5 + MockMvc(백엔드).

**실행 순서:** Area① (Task 1→2→3) → Area③ (Task 4→5) → Area④ (Task 6→7). 사용자 지정 순서 1→3→4.

**공통 규칙:** main 직커밋, push 금지(사용자 명시 요청 시만). 한국어 conventional commit. 각 태스크 후 `cd backend && ./gradlew test` 또는 `cd frontend && pnpm test` 그린 확인 후 커밋.

---

## Area ① 본인 프로필 수정 API

### Task 1: 백엔드 — AuthService.updateProfile + UpdateProfileRequest DTO

**Files:**
- Create: `backend/src/main/java/com/worknote/auth/dto/UpdateProfileRequest.java`
- Modify: `backend/src/main/java/com/worknote/auth/AuthService.java` (changePassword 메서드 뒤, line 97 이후에 추가)
- Test: `backend/src/test/java/com/worknote/auth/AuthServiceTest.java`

- [ ] **Step 1: 실패 테스트 작성** — `AuthServiceTest.java` 끝(line 108의 `}` 앞)에 3개 추가:

```java
    @Test
    void updateProfileChangesNameAndEmail() {
        createUser("u1", "10001", "operator", "active", "pw-current");
        UserRow updated = auth.updateProfile("u1", "새이름", "new@corp.local");
        assertThat(updated.name()).isEqualTo("새이름");
        assertThat(updated.email()).isEqualTo("new@corp.local");
        UserRow row = users.findById("u1");
        assertThat(row.name()).isEqualTo("새이름");
        assertThat(row.email()).isEqualTo("new@corp.local");
        assertThat(row.roleId()).isEqualTo("operator");   // 역할·상태 불변
        assertThat(row.status()).isEqualTo("active");
    }

    @Test
    void updateProfileBlankEmailNormalizesToNull() {
        createUser("u1", "10001", "operator", "active", "pw-current");
        UserRow updated = auth.updateProfile("u1", "이름", "   ");
        assertThat(updated.email()).isNull();
        assertThat(users.findById("u1").email()).isNull();
    }

    @Test
    void updateProfileBlankNameIs422() {
        createUser("u1", "10001", "operator", "active", "pw-current");
        assertThatThrownBy(() -> auth.updateProfile("u1", "  ", "x@corp.local"))
            .isInstanceOf(VaultException.class)
            .satisfies(e -> assertThat(((VaultException) e).status()).isEqualTo(VaultException.Status.INVALID));
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew test --tests com.worknote.auth.AuthServiceTest`
Expected: FAIL — `updateProfile` 메서드 없음(컴파일 에러)

- [ ] **Step 3: AuthService.updateProfile 구현** — `AuthService.java`의 changePassword 메서드 뒤(line 97 `}` 다음, line 99 `caps` 앞)에 추가:

```java
    /** 본인 프로필(name/email) 수정 — 역할·상태·credential은 불변. email 공백은 null로 정규화. */
    @Transactional
    public UserRow updateProfile(String userId, String name, String email) {
        UserRow current = users.findById(userId);
        if (current == null) {
            throw VaultException.invalid("사용자를 찾을 수 없습니다");
        }
        if (name == null || name.isBlank()) {
            throw VaultException.invalid("이름은 빈 값일 수 없습니다");
        }
        String normalizedEmail = (email != null && !email.isBlank()) ? email.trim() : null;
        UserRow merged = new UserRow(current.id(), current.emp(), normalizedEmail, name.trim(),
            current.roleId(), current.status(), current.lastLogin());
        users.update(merged);
        return merged;
    }
```

> 참고: `UserMapper.update`(UserMapper.xml:22-25)는 `name, email, role_id, status`를 갱신 — merged가 roleId/status를 current로 유지하므로 사실상 name/email만 바뀐다. UserRow 필드 순서: `(id, emp, email, name, roleId, status, lastLogin)`.

- [ ] **Step 4: UpdateProfileRequest DTO 생성** — `backend/src/main/java/com/worknote/auth/dto/UpdateProfileRequest.java`:

```java
package com.worknote.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
    @NotBlank @Size(max = 64) String name,
    @Size(max = 128) String email
) {}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests com.worknote.auth.AuthServiceTest`
Expected: PASS (전체)

- [ ] **Step 6: 커밋**

```bash
git add backend/src/main/java/com/worknote/auth/AuthService.java backend/src/main/java/com/worknote/auth/dto/UpdateProfileRequest.java backend/src/test/java/com/worknote/auth/AuthServiceTest.java
git commit -m "feat: 본인 프로필 수정 서비스 추가 (updateProfile + DTO)"
```

---

### Task 2: 백엔드 — AuthController 엔드포인트 + MeResponse email 필드 + 통합 테스트

**Files:**
- Modify: `backend/src/main/java/com/worknote/auth/dto/MeResponse.java`
- Modify: `backend/src/main/java/com/worknote/auth/AuthController.java`
- Create: `backend/src/test/java/com/worknote/auth/UpdateProfileApiTest.java`

- [ ] **Step 1: 통합 테스트 작성** — 먼저 `ChangePasswordApiTest.java`를 읽어 server-mode 테스트 하네스(@SpringBootTest properties, login 헬퍼, MockHttpSession 패턴)를 그대로 본떠 `UpdateProfileApiTest.java` 작성. 다음 케이스 포함:

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "worknote.mode=server",
    "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared"
})
@AutoConfigureMockMvc
class UpdateProfileApiTest {
    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM user_credential");
        jdbc.update("DELETE FROM app_user");
        users.insert(new UserRow("u1", "10001", "old@corp.local", "옛이름", "operator", "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("u1", salt, PasswordHasher.hash("pw-current", salt)));
    }

    private MockHttpSession login(String emp, String pw) throws Exception {
        MockHttpSession s = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(s).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"" + emp + "\",\"password\":\"" + pw + "\"}"))
            .andExpect(status().isOk());
        return s;
    }

    private String body(String name, String email) {
        return "{\"name\":\"" + name + "\",\"email\":\"" + email + "\"}";
    }

    @Test
    void updateProfileReturnsMeWithEmailAndPersists() throws Exception {
        MockHttpSession s = login("10001", "pw-current");
        mvc.perform(post("/api/auth/update-profile").session(s).contentType(APPLICATION_JSON)
                .content(body("새이름", "new@corp.local")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("새이름"))
            .andExpect(jsonPath("$.email").value("new@corp.local"));
        assertThat(users.findById("u1").name()).isEqualTo("새이름");
        assertThat(users.findById("u1").email()).isEqualTo("new@corp.local");
    }

    @Test
    void blankEmailStoredAsNull() throws Exception {
        MockHttpSession s = login("10001", "pw-current");
        mvc.perform(post("/api/auth/update-profile").session(s).contentType(APPLICATION_JSON)
                .content(body("이름", "")))
            .andExpect(status().isOk());
        assertThat(users.findById("u1").email()).isNull();
    }

    @Test
    void blankNameIs400() throws Exception {
        MockHttpSession s = login("10001", "pw-current");
        mvc.perform(post("/api/auth/update-profile").session(s).contentType(APPLICATION_JSON)
                .content(body("", "x@corp.local")))
            .andExpect(status().isBadRequest());   // @NotBlank
    }

    @Test
    void unauthenticatedIs401() throws Exception {
        mvc.perform(post("/api/auth/update-profile").contentType(APPLICATION_JSON)
                .content(body("이름", "x@corp.local")))
            .andExpect(status().isUnauthorized());   // AuthFilter (server 모드, ALLOWLIST 미포함)
    }

    @Test
    void profileUpdateIsAudited() throws Exception {
        MockHttpSession s = login("10001", "pw-current");
        mvc.perform(post("/api/auth/update-profile").session(s).contentType(APPLICATION_JSON)
                .content(body("이름", "x@corp.local")))
            .andExpect(status().isOk());
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act = 'auth.profile.update'", Integer.class);
        assertThat(n).isEqualTo(1);
    }
}
```

> 주의: 실제 audit_log 테이블/컬럼명과 login 응답 형식은 `ChangePasswordApiTest.java`를 읽어 정확히 맞출 것. 위 SQL/JSON 경로가 기존 테스트와 다르면 기존 테스트 쪽에 맞춘다.

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew test --tests com.worknote.auth.UpdateProfileApiTest`
Expected: FAIL — 엔드포인트 없음(404/405) + MeResponse에 email 없음(jsonPath 실패)

- [ ] **Step 3: MeResponse에 email 필드 추가** — `MeResponse.java`:

```java
package com.worknote.auth.dto;

import java.util.Set;

public record MeResponse(String id, String emp, String name, String email, String roleId, Set<String> caps) {}
```

- [ ] **Step 4: AuthController 수정** — `toMe`와 local 리터럴에 email 반영 + 엔드포인트 추가.

`toMe`(line 114-116) 교체:
```java
    private static MeResponse toMe(UserRow user, Set<String> caps) {
        return new MeResponse(user.id(), user.emp(), user.name(), user.email(), user.roleId(), caps);
    }
```

local 리터럴(line 111) 교체:
```java
        return new MeResponse("local", "local", "local", null, "admin", roleCaps.of("admin"));
```

import 추가(line 7 부근):
```java
import com.worknote.auth.dto.UpdateProfileRequest;
```

엔드포인트 추가 — changePassword(line 98 `}`) 뒤, me(line 100) 앞:
```java
    @PostMapping("/update-profile")
    public MeResponse updateProfile(@Valid @RequestBody UpdateProfileRequest req, HttpServletRequest http) {
        UserRow user = (UserRow) http.getAttribute(AuthFilter.CURRENT_USER);
        if (user == null) {
            // server 모드는 AuthFilter가 먼저 401 — 여기 도달은 local 모드(본인 개념 없음)
            throw AuthException.forbidden("프로필 변경은 로그인 상태에서만 가능합니다");
        }
        UserRow updated = auth.updateProfile(user.id(), req.name(), req.email());
        audit.log(user, "auth.profile.update", null, http.getRemoteAddr());
        return toMe(updated, auth.caps(updated));
    }
```

- [ ] **Step 5: MeResponse 변경 파급 확인** — `MeResponse(` 생성 지점을 전수 grep해 5인자→6인자 전환 누락이 없는지 확인. 기존 테스트(login/me JSON 단언)는 필드 추가로 깨지지 않지만, MeResponse를 직접 `new` 하는 테스트가 있으면 email 인자를 추가한다.

Run: `cd backend && ./gradlew test`
Expected: 전체 PASS (기존 + 신규 5건)

- [ ] **Step 6: 커밋**

```bash
git add backend/src/main/java/com/worknote/auth/dto/MeResponse.java backend/src/main/java/com/worknote/auth/AuthController.java backend/src/test/java/com/worknote/auth/UpdateProfileApiTest.java
git commit -m "feat: 본인 프로필 수정 엔드포인트 + MeResponse email 추가"
```

---

### Task 3: 프런트 — AuthApi.updateProfile + Me.email + validateProfile + ProfileModal 배선 + mappers act

**Files:**
- Modify: `frontend/src/api/auth.ts`
- Modify: `frontend/src/api/auth.test.ts`
- Create: `frontend/src/components/profileValidation.ts`
- Create: `frontend/src/components/profileValidation.test.ts`
- Modify: `frontend/src/admin/mappers.ts`
- Modify: `frontend/src/admin/mappers.test.ts`
- Modify: `frontend/src/state/useSession.ts`
- Modify: `frontend/src/components/ProfileModal.tsx`
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: AuthApi 테스트 작성** — `auth.test.ts` 끝에 updateProfile describe 추가(기존 changePassword 테스트 패턴 모방: `vi.stubGlobal("fetch", ...)`). 케이스: ① POST /auth/update-profile 호출 + body에 name/email ② 빈 email은 body에서 생략(undefined) ③ 응답 Me 반환.

```ts
describe("updateProfile", () => {
  it("POST로 name/email 전송하고 Me 반환", async () => {
    const me = { id: "u1", emp: "10001", name: "새이름", email: "x@corp.local", roleId: "operator", caps: [] };
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true, status: 200, json: async () => me,
    });
    vi.stubGlobal("fetch", fetchMock);
    const res = await AuthApi.updateProfile("새이름", "x@corp.local");
    expect(res).toEqual(me);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toContain("/auth/update-profile");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body)).toEqual({ name: "새이름", email: "x@corp.local" });
  });

  it("빈 email은 body에서 생략", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({}) });
    vi.stubGlobal("fetch", fetchMock);
    await AuthApi.updateProfile("이름", "");
    const body = JSON.parse(fetchMock.mock.calls[0][1].body);
    expect(body).toEqual({ name: "이름" });   // email 키 없음
    expect("email" in body).toBe(false);
  });
});
```

> 기존 `auth.test.ts`의 import/stub 정리(afterEach unstub 등) 관례를 그대로 따를 것.

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd frontend && pnpm test auth.test`
Expected: FAIL — `AuthApi.updateProfile` 없음

- [ ] **Step 3: AuthApi.updateProfile + Me.email 구현** — `auth.ts`:

`Me` 인터페이스에 email 추가(line 9 `caps` 앞):
```ts
  email: string | null;
```

`AuthApi`에 메서드 추가(changePassword 뒤):
```ts
  updateProfile: (name: string, email: string) =>
    req<Me>("/auth/update-profile", { method: "POST", body: JSON.stringify({ name, email: email.trim() || undefined }) }),
```

- [ ] **Step 4: auth 테스트 통과 확인**

Run: `cd frontend && pnpm test auth.test`
Expected: PASS

- [ ] **Step 5: validateProfile 순수함수 + 테스트** — `profileValidation.ts`:

```ts
/* 프로필 정보 폼 클라이언트 선검증 — null이면 통과, 아니면 에러 메시지. 서버(@NotBlank @Size)가 최종. */
export function validateProfile(name: string): string | null {
  if (!name.trim()) return "이름을 입력하세요.";
  if (name.trim().length > 64) return "이름은 64자 이하여야 합니다.";
  return null;
}
```

`profileValidation.test.ts`:
```ts
import { describe, it, expect } from "vitest";
import { validateProfile } from "./profileValidation";

describe("validateProfile", () => {
  it("빈 이름 거부", () => { expect(validateProfile("   ")).toMatch(/이름/); });
  it("64자 초과 거부", () => { expect(validateProfile("a".repeat(65))).toMatch(/64자/); });
  it("정상 통과", () => { expect(validateProfile("홍길동")).toBeNull(); });
});
```

Run: `cd frontend && pnpm test profileValidation`
Expected: PASS

- [ ] **Step 6: mappers act 추가 + 드리프트 가드 갱신** — `mappers.ts`:

ACTS 맵(line 23, `"auth.password.change": "비밀번호 변경",` 뒤)에 추가:
```ts
  "auth.profile.update": "프로필 수정",
```

KNOWN_ACTS 배열 첫 줄(line 38)에 `auth.password.change` 뒤 추가:
```ts
  "login.success", "login.fail", "logout", "signup", "signup.fail", "auth.password.change", "auth.profile.update",
```

주석(line 36) "act 31종" → "act 32종"으로 갱신.

`mappers.test.ts`의 드리프트 가드 카운트 `31` → `32`로 갱신(KNOWN_ACTS.length 단언). 라벨 존재 가드는 ACTS에 추가했으므로 통과.

Run: `cd frontend && pnpm test mappers`
Expected: PASS

- [ ] **Step 7: useSession에 setMe 노출** — `useSession.ts`:

반환 타입과 객체에 setMe 추가:
```ts
export function useSession(): { me: Me | null; setMe: (m: Me | null) => void; isAdmin: boolean; logout: () => void } {
  const [me, setMe] = useState<Me | null>(null);
  // ... (기존 effect/logout 그대로)
  return { me, setMe, isAdmin: me?.caps.includes("admin.users") ?? false, logout };
}
```

- [ ] **Step 8: ProfileModal 배선** — `ProfileModal.tsx`:

import 추가:
```ts
import { validateProfile } from "./profileValidation";
import type { Me } from "../api/auth";
```

Props 인터페이스에 추가(line 18-24):
```ts
  email?: string | null; // http 모드 세션 이메일 (null 가능)
  onSaved?: (me: Me) => void; // http 저장 성공 시 세션 me 갱신
```

구조분해(line 26) 갱신:
```ts
export function ProfileModal({ emp, role, name: sessionName, email: sessionEmail, onClose, onSaved, toast }: ProfileModalProps) {
```

email 프리로드(line 29) 교체 — http면 세션값(null→빈), local이면 기존 init:
```ts
  const [email, setEmail] = useState(storageMode === "http" ? (sessionEmail ?? "") : init.email);
```

infoMsg 상태 추가(line 30 `savedInfo` 뒤):
```ts
  const [infoMsg, setInfoMsg] = useState<{ type: string; text: string } | null>(null);
```

saveInfo 교체(line 37-42) — http=API, local=기존 localStorage:
```ts
  const saveInfo = async () => {
    if (storageMode !== "http") {
      try { localStorage.setItem(PKEY, JSON.stringify({ name: name.trim(), email: email.trim() })); } catch (e) {}
      setSavedInfo(true);
      setInfoMsg(null);
      toast && toast("프로필을 저장했습니다", "check");
      return;
    }
    const err = validateProfile(name);
    if (err) { setInfoMsg({ type: "err", text: err }); return; }
    try {
      const updated = await AuthApi.updateProfile(name.trim(), email.trim());
      setSavedInfo(true);
      setInfoMsg(null);
      onSaved && onSaved(updated);
      toast && toast("프로필을 저장했습니다", "check");
    } catch (e) {
      setInfoMsg({ type: "err", text: e instanceof ApiError ? e.message : "프로필 저장에 실패했습니다." });
    }
  };
```

정보 섹션에 infoMsg 렌더 추가 — line 86 `h("div", { className: "pf-foot" }` 직전에:
```ts
          infoMsg && h("div", { className: "pf-msg " + infoMsg.type }, infoMsg.text),
```

> 주의: 이름 입력 onChange에서 `setSavedInfo(false)` 옆에 `setInfoMsg(null)`도 추가(이메일 onChange도 동일)해 입력 시 에러 문구가 사라지게 한다(line 81, 85).

- [ ] **Step 9: App.tsx 배선** — `App.tsx`:

useSession 구조분해(line 67)에 setMe 추가:
```ts
  const { me, setMe, isAdmin, logout } = useSession();
```

ProfileModal 렌더(line 295-299)에 email/onSaved 전달:
```ts
    profileOpen && createElement(ProfileModal, {
      emp: me ? me.emp : currentEmp, role: me ? me.roleId : "운영자", name: me?.name, email: me?.email,
      onSaved: setMe,
      onClose: () => setProfileOpen(false),
      toast,
    }),
```

- [ ] **Step 10: 전체 프런트 테스트 + 빌드**

Run: `cd frontend && pnpm test && pnpm build`
Expected: 전체 PASS, 빌드 성공(타입 에러 0)

- [ ] **Step 11: 커밋**

```bash
git add frontend/src/api/auth.ts frontend/src/api/auth.test.ts frontend/src/components/profileValidation.ts frontend/src/components/profileValidation.test.ts frontend/src/admin/mappers.ts frontend/src/admin/mappers.test.ts frontend/src/state/useSession.ts frontend/src/components/ProfileModal.tsx frontend/src/App.tsx
git commit -m "feat: 프로필 수정 프런트 연동 (실 API·세션 갱신·검증·감사 라벨)"
```

---

## Area ③ 드래그앤드롭 이동 UX

### Task 4: lib/dnd.ts canDropOn 순수함수 + MoveWarn 경고 UI 추출(DRY)

**Files:**
- Create: `frontend/src/lib/dnd.ts`
- Create: `frontend/src/lib/dnd.test.ts`
- Create: `frontend/src/components/MoveWarnDialog.tsx`
- Modify: `frontend/src/components/MoveModal.tsx`

- [ ] **Step 1: canDropOn 실패 테스트** — `dnd.test.ts`:

```ts
import { describe, it, expect } from "vitest";
import { canDropOn } from "./dnd";
import type { VaultTree } from "../types";

// f1 > (n1, f2 > n2),  f3,  n3(root)
const tree: VaultTree = [
  { id: "f1", type: "folder", name: "F1", children: [
    { id: "n1", type: "note", title: "N1", tags: [], updated: "2026-06-13", content: "" },
    { id: "f2", type: "folder", name: "F2", children: [
      { id: "n2", type: "note", title: "N2", tags: [], updated: "2026-06-13", content: "" },
    ] },
  ] },
  { id: "f3", type: "folder", name: "F3", children: [] },
  { id: "n3", type: "note", title: "N3", tags: [], updated: "2026-06-13", content: "" },
];

describe("canDropOn", () => {
  it("노트를 다른 폴더로 드롭 허용", () => { expect(canDropOn(tree, "n3", "f1")).toBe(true); });
  it("폴더를 다른 폴더로 드롭 허용", () => { expect(canDropOn(tree, "f3", "f1")).toBe(true); });
  it("노트 위로는 드롭 불가", () => { expect(canDropOn(tree, "n3", "n1")).toBe(false); });
  it("자기 자신 위로 불가", () => { expect(canDropOn(tree, "f1", "f1")).toBe(false); });
  it("자손 폴더로 불가", () => { expect(canDropOn(tree, "f1", "f2")).toBe(false); });
  it("이미 그 부모면 무변경(불가)", () => { expect(canDropOn(tree, "n1", "f1")).toBe(false); });
  it("중첩 노드를 루트로 드롭 허용", () => { expect(canDropOn(tree, "n1", null)).toBe(true); });
  it("이미 루트면 루트로 불가", () => { expect(canDropOn(tree, "f3", null)).toBe(false); });
  it("존재하지 않는 dragged 불가", () => { expect(canDropOn(tree, "zzz", "f1")).toBe(false); });
});
```

- [ ] **Step 2: 실패 확인**

Run: `cd frontend && pnpm test dnd`
Expected: FAIL — `canDropOn` 없음

- [ ] **Step 3: canDropOn 구현** — `dnd.ts`:

```ts
import { findNode, isSelfOrDescendant } from "./tree";
import type { VaultTree } from "../types";

/** DnD 드롭 허용 판정. targetId=null 은 루트.
 *  허용 조건: dragged 존재 · (타깃이 폴더이거나 루트) · 자기/자손 폴더 아님 · 현재 부모와 다름. */
export function canDropOn(tree: VaultTree, draggedId: string, targetId: string | null): boolean {
  if (draggedId === targetId) return false;
  const dragged = findNode(tree, draggedId);
  if (!dragged.node) return false;
  if (targetId !== null) {
    const target = findNode(tree, targetId);
    if (!target.node || target.node.type !== "folder") return false;
    if (isSelfOrDescendant(tree, draggedId, targetId)) return false;
  }
  const currentParentId = dragged.parentNode?.id ?? null;
  if (currentParentId === targetId) return false;
  return true;
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd frontend && pnpm test dnd`
Expected: PASS

- [ ] **Step 5: MoveWarn 경고 UI 추출** — `MoveWarnDialog.tsx`. `MoveModal`의 warn 단계 마크업을 공유 컴포넌트로 추출. `MoveWarnContent`(본문만)와 `MoveWarnDialog`(독립 오버레이) 둘 다 export:

```tsx
/* MoveWarnDialog — 이동 노출 경고 UI. MoveModal(피커 내부)와 DnD(독립 오버레이)가 공유. */
import React from "react";
import { Icon } from "./Icon";
import type { MovePreview } from "../storage/VaultApi";
import { shouldWarn } from "./moveWarning";

const h = React.createElement;

/** 경고 본문(라벨 + 변경 라인 + 메시지). 푸터 버튼은 호출측이 별도 구성. */
export function MoveWarnContent({ preview }: { preview: MovePreview }) {
  const warn = shouldWarn(preview);
  return h(React.Fragment, null,
    h("div", { className: "pf-sec-label" }, "이동 시 변경 사항"),
    (warn.lines ?? []).map((line, i) => h("div", { className: "mv-warn-line", key: i }, line)),
    h("div", { className: "pf-msg " + (warn.strong ? "err" : "ok") },
      warn.strong ? "노출 범위가 넓어집니다. 계속하시겠습니까?" : "계속하시겠습니까?"));
}

interface MoveWarnDialogProps {
  name: string;
  preview: MovePreview;
  onConfirm: () => void;
  onCancel: () => void;
}

/** 독립 오버레이 경고 다이얼로그 — DnD 드롭 경로용. */
export function MoveWarnDialog({ name, preview, onConfirm, onCancel }: MoveWarnDialogProps) {
  return h("div", { className: "pf-overlay", onMouseDown: onCancel },
    h("div", { className: "pf-card", onMouseDown: (e: React.MouseEvent) => e.stopPropagation() },
      h("div", { className: "pf-head" },
        h("span", { className: "pf-av" }, h(Icon, { name: "move" })),
        h("div", { className: "pf-id" },
          h("div", { className: "pf-emp" }, name),
          h("div", { className: "pf-role" }, "이동")),
        h("button", { className: "icon-btn pf-x", onClick: onCancel, title: "닫기" }, h(Icon, { name: "x" }))),
      h("div", { className: "pf-body" },
        h("div", { className: "pf-sec" },
          h(MoveWarnContent, { preview }),
          h("div", { className: "pf-foot" },
            h("button", { className: "pf-btn", onClick: onCancel }, "취소"),
            h("button", { className: "pf-btn danger", onClick: onConfirm }, "이동"))))));
}
```

- [ ] **Step 6: MoveModal이 MoveWarnContent 재사용** — `MoveModal.tsx`:

import 추가(line 9 `shouldWarn` import 옆):
```ts
import { MoveWarnContent } from "./MoveWarnDialog";
```

warn 단계 body(line 99-107) 교체 — 인라인 마크업을 MoveWarnContent로:
```ts
      : h("div", { className: "pf-sec" },
          h(MoveWarnContent, { preview: preview! }),
          h("div", { className: "pf-foot" },
            h("button", { className: "pf-btn", onClick: () => setPhase("pick") }, "취소"),
            h("button", { className: "pf-btn danger", disabled: busy, onClick: doMove }, "이동")));
```

이제 `const warn = preview ? shouldWarn(preview) : null;`(line 71)은 미사용이면 제거. (pick 단계에서 쓰지 않으면 삭제)

- [ ] **Step 7: 전체 프런트 테스트 + 빌드(리팩터 무손상 확인)**

Run: `cd frontend && pnpm test && pnpm build`
Expected: PASS, 빌드 성공. MoveModal 동작 변화 없음(마크업 동일).

- [ ] **Step 8: 커밋**

```bash
git add frontend/src/lib/dnd.ts frontend/src/lib/dnd.test.ts frontend/src/components/MoveWarnDialog.tsx frontend/src/components/MoveModal.tsx
git commit -m "feat: DnD 드롭 검증(canDropOn) + 이동 경고 UI 공유 컴포넌트 추출"
```

---

### Task 5: Sidebar Row DnD 핸들러 + App 드롭 흐름 + drop-target CSS

**Files:**
- Modify: `frontend/src/components/Sidebar.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/styles/app.css`

> 이 태스크는 DOM 이벤트 배선(통합)이라 단위 테스트 없음 — 순수 판정은 Task 4의 `canDropOn`이 커버. 검증은 `pnpm build` 타입체크 + 최종 통합 리뷰. 구현 후 빌드 그린 필수.

- [ ] **Step 1: Sidebar에 DnD props 추가** — `Sidebar.tsx`:

`RowProps`에 추가:
```ts
  draggingId: string | null;
  dragOverId: string | null;            // 폴더 id 또는 루트 토큰
  onNodeDragStart: (id: string, e: React.DragEvent) => void;
  onNodeDragOver: (id: string | null, e: React.DragEvent) => void;
  onNodeDragLeave: (id: string | null) => void;
  onNodeDrop: (id: string | null, e: React.DragEvent) => void;
  onNodeDragEnd: () => void;
```

`SidebarProps`에도 동일 7개 추가(Sidebar가 Row로 `{...props}` 전달하므로 타입만 추가하면 흐름은 그대로 내려간다). 또한 루트 드롭용으로 `dragOverId`를 tree 컨테이너 클래스에 사용.

- [ ] **Step 2: Row에 draggable/drop 핸들러 부착** — `Row` 함수의 `rowEl`(line 35-62) `props` 객체에 추가. 폴더와 노트 모두 draggable(드래그 가능), 드롭 타깃은 폴더만:

`const { ... }` 구조분해에 새 props 추가, 그리고 className/핸들러:
```ts
  const isDragOver = isFolder && dragOverId === node.id;
  // rowEl props 객체:
  className: "row" + (isActive ? " active" : "") + (isDragOver ? " drop-target" : "") + (draggingId === node.id ? " dragging" : ""),
  draggable: !renaming,
  onDragStart: (e: React.DragEvent) => { e.stopPropagation(); onNodeDragStart(node.id, e); },
  onDragEnd: () => onNodeDragEnd(),
  ...(isFolder ? {
    onDragOver: (e: React.DragEvent) => onNodeDragOver(node.id, e),
    onDragLeave: () => onNodeDragLeave(node.id),
    onDrop: (e: React.DragEvent) => { e.stopPropagation(); onNodeDrop(node.id, e); },
  } : {}),
```

> 주의: 기존 `onClick`/`onContextMenu`는 유지. draggable 요소 내부 rename input은 `!renaming`으로 draggable 비활성. 객체 스프레드로 조건부 핸들러를 합칠 때 createElement props 객체 작성에 유의.

- [ ] **Step 3: 루트 드롭 영역** — Sidebar의 `.tree` 컨테이너(line 127-133)에 핸들러 + 하이라이트 클래스 추가:

```ts
    React.createElement(
      "div", {
        className: "tree" + (props.dragOverId === "__ROOT__" ? " root-drop" : ""),
        onContextMenu: (e: React.MouseEvent) => { e.preventDefault(); props.onContext(e.clientX, e.clientY, null); },
        onDragOver: (e: React.DragEvent) => props.onNodeDragOver(null, e),
        onDragLeave: () => props.onNodeDragLeave(null),
        onDrop: (e: React.DragEvent) => props.onNodeDrop(null, e),
      },
      tree.map((n) => React.createElement(Row, { key: n.id, ...props, node: n, depth: 0 }))
    ),
```

- [ ] **Step 4: App.tsx 드롭 상태·핸들러·다이얼로그** — `App.tsx`:

import 추가(line 14·24 부근):
```ts
import { MoveWarnDialog } from "./components/MoveWarnDialog";
import { canDropOn } from "./lib/dnd";
import { VaultApi } from "./storage/VaultApi";
import type { MovePreview } from "./storage/VaultApi";
import { shouldWarn } from "./components/moveWarning";
import { ApiError } from "./api/http";
```
> 이미 import된 것(findNode 등)은 중복 추가 금지. VaultApi/MovePreview/shouldWarn/ApiError가 기존에 없으면 추가.

상태 추가(line 59 `moveTarget` 뒤):
```ts
  const ROOT_DROP = "__ROOT__";
  const [draggingId, setDraggingId] = useState<string | null>(null);
  const [dragOverId, setDragOverId] = useState<string | null>(null);
  const [pendingWarn, setPendingWarn] = useState<{ id: string; parentId: string | null; preview: MovePreview } | null>(null);
```

핸들러 추가(onContext 함수 뒤, line 181 이후):
```ts
  const nodeName = (id: string) => {
    const { node } = findNode(tree, id);
    if (!node) return "";
    return node.type === "folder" ? node.name : (node.title || "제목 없음");
  };
  const attemptDnDMove = async (id: string, parentId: string | null) => {
    if (storageMode !== "http") { actions.move(id, parentId); toast("이동했습니다", "check"); return; }
    try {
      const p = await VaultApi.movePreview(id, parentId);
      if (!shouldWarn(p).warn) { actions.move(id, parentId); toast("이동했습니다", "check"); return; }
      setPendingWarn({ id, parentId, preview: p });
    } catch (e) {
      toast(e instanceof ApiError ? e.message : "이동할 수 없습니다");
    }
  };
  const onNodeDragStart = (id: string, e: React.DragEvent) => {
    setDraggingId(id);
    try { e.dataTransfer.setData("text/plain", id); e.dataTransfer.effectAllowed = "move"; } catch (err) {}
  };
  const onNodeDragEnd = () => { setDraggingId(null); setDragOverId(null); };
  const onNodeDragOver = (targetId: string | null, e: React.DragEvent) => {
    if (!draggingId || !canDropOn(tree, draggingId, targetId)) return;
    e.preventDefault();
    try { e.dataTransfer.dropEffect = "move"; } catch (err) {}
    setDragOverId(targetId ?? ROOT_DROP);
  };
  const onNodeDragLeave = (targetId: string | null) => {
    const token = targetId ?? ROOT_DROP;
    setDragOverId((cur) => (cur === token ? null : cur));
  };
  const onNodeDrop = (targetId: string | null, e: React.DragEvent) => {
    e.preventDefault();
    const id = draggingId;
    setDraggingId(null); setDragOverId(null);
    if (!id || !canDropOn(tree, id, targetId)) return;
    void attemptDnDMove(id, targetId);
  };
```

Sidebar createElement(line 210-222)에 props 전달 추가:
```ts
      draggingId, dragOverId,
      onNodeDragStart, onNodeDragOver, onNodeDragLeave, onNodeDrop, onNodeDragEnd,
```

MoveWarnDialog 렌더 추가(line 302 moveTarget 렌더 뒤):
```ts
    pendingWarn && createElement(MoveWarnDialog, {
      name: nodeName(pendingWarn.id),
      preview: pendingWarn.preview,
      onConfirm: () => { actions.move(pendingWarn.id, pendingWarn.parentId); toast("이동했습니다", "check"); setPendingWarn(null); },
      onCancel: () => setPendingWarn(null),
    }),
```

- [ ] **Step 5: drop-target CSS** — `app.css`. 트리 노드 영역(`.row` 정의 근처)에 추가:

```css
.row.dragging { opacity: .5; }
.row.drop-target { background: var(--accent-soft, rgba(80,120,255,.12)); box-shadow: inset 0 0 0 1.5px var(--accent, #5078ff); border-radius: 6px; }
.tree.root-drop { box-shadow: inset 0 0 0 2px var(--accent, #5078ff); border-radius: 6px; }
```
> 기존 CSS 변수 네이밍을 확인해 `--accent` 류 토큰이 있으면 그걸 쓰고, 없으면 위 폴백 색을 사용. 모노톤 디자인 톤에 맞게 과하지 않게.

- [ ] **Step 6: 빌드 + 전체 테스트**

Run: `cd frontend && pnpm test && pnpm build`
Expected: PASS, 빌드 성공(타입 에러 0)

- [ ] **Step 7: 커밋**

```bash
git add frontend/src/components/Sidebar.tsx frontend/src/App.tsx frontend/src/styles/app.css
git commit -m "feat: 트리 노드 드래그앤드롭 이동 (드롭 검증·노출 경고 재사용·하이라이트)"
```

---

## Area ④ 비밀번호 최소 길이 정책 통일 (10자)

### Task 6: 백엔드 — PasswordPolicy 상수 + DTO 3종 + changePassword 통일

**Files:**
- Create: `backend/src/main/java/com/worknote/auth/PasswordPolicy.java`
- Modify: `backend/src/main/java/com/worknote/auth/dto/SignupRequest.java`
- Modify: `backend/src/main/java/com/worknote/admin/dto/CreateUserRequest.java`
- Modify: `backend/src/main/java/com/worknote/admin/dto/ResetPasswordRequest.java`
- Modify: `backend/src/main/java/com/worknote/auth/AuthService.java`
- Test: `backend/src/test/java/com/worknote/auth/AuthSignupTest.java` (+ 기존 테스트 보정)

- [ ] **Step 1: 실패 테스트 — 가입 9자 거부** — `AuthSignupTest.java`에 추가(기존 `signup_shortPassword_400` 패턴 모방):

```java
    @Test
    void signup_ninePassword_400() throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(APPLICATION_JSON)
                .content("{\"emp\":\"S9\",\"name\":\"n\",\"password\":\"123456789\"}"))
            .andExpect(status().isBadRequest());   // 9자 < 10
    }
```

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests com.worknote.auth.AuthSignupTest`
Expected: FAIL — 현재 min=8이라 9자는 201 Created

- [ ] **Step 3: PasswordPolicy 상수 생성** — `PasswordPolicy.java`:

```java
package com.worknote.auth;

/** 비밀번호 정책 단일 출처 — 가입·관리자 생성·초기화·본인 변경 모두 이 최소 길이를 따른다. */
public final class PasswordPolicy {
    private PasswordPolicy() {}
    public static final int MIN_LENGTH = 10;
}
```

- [ ] **Step 4: DTO 3종 @Size min 상수화** — 각 파일에서 `@Size(min = 8, max = 128)` → `@Size(min = PasswordPolicy.MIN_LENGTH, max = 128)` + import 추가.

`SignupRequest.java`(line 11) + import `import com.worknote.auth.PasswordPolicy;`
`CreateUserRequest.java`(line 11) + import `import com.worknote.auth.PasswordPolicy;`
`ResetPasswordRequest.java`(line 6) + import `import com.worknote.auth.PasswordPolicy;`

- [ ] **Step 5: AuthService.changePassword 상수화** — `AuthService.java` line 88-90:

```java
        if (newPassword.length() < PasswordPolicy.MIN_LENGTH) {
            throw VaultException.invalid("새 비밀번호는 " + PasswordPolicy.MIN_LENGTH + "자 이상이어야 합니다");
        }
```
(같은 패키지라 import 불필요)

- [ ] **Step 6: 기존 테스트 보정** — 가입/관리자생성/초기화 엔드포인트로 8~9자 비밀번호를 POST해 **성공을 기대하던** 기존 테스트가 있으면 비밀번호 리터럴을 10자 이상으로 상향. 다음으로 전수 확인:

Run: `cd backend && ./gradlew test`
Expected: 신규 PASS. 만약 `AdminUserApiTest`/`AuthSignupTest`/리셋 관련 테스트에서 8~9자 성공 케이스가 깨지면(이제 400) 해당 비밀번호를 `"pw-1234567890"` 등 10자 이상으로 교체 후 재실행. (로그인/credential 직접 insert 테스트는 길이 검증을 거치지 않으므로 무관)

- [ ] **Step 7: 커밋**

```bash
git add backend/src/main/java/com/worknote/auth/PasswordPolicy.java backend/src/main/java/com/worknote/auth/dto/SignupRequest.java backend/src/main/java/com/worknote/admin/dto/CreateUserRequest.java backend/src/main/java/com/worknote/admin/dto/ResetPasswordRequest.java backend/src/main/java/com/worknote/auth/AuthService.java backend/src/test/java/com/worknote/auth/AuthSignupTest.java
git commit -m "feat: 비밀번호 최소 길이 10자 통일 + PasswordPolicy 상수화"
```

---

### Task 7: 프런트 — MIN_PASSWORD_LENGTH 상수 + validateSignup + UI 문구

**Files:**
- Create: `frontend/src/lib/passwordPolicy.ts`
- Modify: `frontend/src/login/loginLogic.ts`
- Modify: `frontend/src/login/loginLogic.test.ts`
- Modify: `frontend/src/components/passwordValidation.ts`
- Modify: `frontend/src/admin/screens/Security.tsx`
- Modify: `frontend/src/admin/screens/Users.tsx`

- [ ] **Step 1: 실패 테스트 — validateSignup 10자** — `loginLogic.test.ts`의 기존 "8자 미만" 테스트(line 10-13) 단언을 10자로 갱신 + 9자 케이스 추가:

```ts
  it("비밀번호 9자 이하이면 메시지", () => {
    expect(validateSignup({ emp: "S1", name: "n", email: "", password: "123456789", password2: "123456789" }))
      .toContain("10자");
  });
  it("비밀번호 10자이면 길이 통과", () => {
    // 길이는 통과(다른 사유로 null이거나 일치 검증으로 넘어감) — 10자 메시지가 안 나와야 함
    expect(validateSignup({ emp: "S1", name: "n", email: "", password: "1234567890", password2: "1234567890" }))
      .toBeNull();
  });
```
(기존 `password: "1234567"` "8자" 단언 테스트는 위 9자 케이스로 대체하거나 메시지를 "10자"로 수정)

- [ ] **Step 2: 실패 확인**

Run: `cd frontend && pnpm test loginLogic`
Expected: FAIL — 현재 8자 기준이라 9자 통과 + 메시지 "8자"

- [ ] **Step 3: passwordPolicy 상수 + loginLogic 적용** — `passwordPolicy.ts`:

```ts
/** 비밀번호 최소 길이 단일 출처 — 백엔드 PasswordPolicy.MIN_LENGTH(10)와 일치. */
export const MIN_PASSWORD_LENGTH = 10;
```

`loginLogic.ts` line 12 교체 + import:
```ts
import { MIN_PASSWORD_LENGTH } from "../lib/passwordPolicy";
// ...
  if (f.password.length < MIN_PASSWORD_LENGTH) return "비밀번호는 " + MIN_PASSWORD_LENGTH + "자 이상이어야 합니다";
```

- [ ] **Step 4: passwordValidation 상수화** — `passwordValidation.ts` line 4 교체 + import:
```ts
import { MIN_PASSWORD_LENGTH } from "../lib/passwordPolicy";
// ...
  if (next.length < MIN_PASSWORD_LENGTH) return "새 비밀번호는 " + MIN_PASSWORD_LENGTH + "자 이상이어야 합니다.";
```
(값은 이미 10이라 동작 동일 — 상수 단일화 목적)

- [ ] **Step 5: UI 문구 갱신**

`Security.tsx` line 25: `"8자 (최대 128자)"` → `"10자 (최대 128자)"`
`Users.tsx` line 162: `"새 비밀번호 (8자 이상)"` → `"새 비밀번호 (10자 이상)"`
`Users.tsx` line 177: `"비밀번호 (8자 이상)"` → `"비밀번호 (10자 이상)"`

- [ ] **Step 6: 전체 테스트 + 빌드**

Run: `cd frontend && pnpm test && pnpm build`
Expected: PASS(loginLogic·passwordValidation 포함), 빌드 성공

- [ ] **Step 7: 커밋**

```bash
git add frontend/src/lib/passwordPolicy.ts frontend/src/login/loginLogic.ts frontend/src/login/loginLogic.test.ts frontend/src/components/passwordValidation.ts frontend/src/admin/screens/Security.tsx frontend/src/admin/screens/Users.tsx
git commit -m "feat: 프런트 비밀번호 최소 길이 10자 통일 + 상수화·UI 문구"
```

---

## 최종 통합 검증 (모든 태스크 후)

- [ ] **백엔드 전체 그린**: `cd backend && ./gradlew test`
- [ ] **프런트 전체 그린 + 빌드**: `cd frontend && pnpm test && pnpm build`
- [ ] **단일 jar 스모크(선택)**: `cd backend && ./gradlew bootJar` 후 server 모드 기동 → `/api/auth/update-profile`·비번 10자 거부·DnD 정적 번들 포함 확인
- [ ] **문서/메모리 갱신**: `backend/README.md` 이월 목록(프로필 mock·비번 정책 불일치 항목 제거), 새 메모리 파일 + MEMORY.md 인덱스
- [ ] **최종 코드 리뷰 서브에이전트**: 교차 정합(MeResponse 6인자 전파, audit act 드리프트, 비번 상수 단일 출처, DnD/MoveModal 경고 UI 공유) 위반 0 확인

## 교차 의존성 메모

- **MeResponse 6인자화(Task 2)**는 login·me 응답에도 email을 싣는다 — Task 3 프런트 `Me.email`과 정합 필수.
- **mappers 드리프트 가드(Task 3)**: KNOWN_ACTS 31→32 + ACTS 라벨 동반 추가 안 하면 테스트 실패.
- **MoveWarnContent 추출(Task 4)**을 Task 5가 사용 — Task 4가 먼저 끝나야 Task 5 가능.
- **PasswordPolicy(Task 6)/MIN_PASSWORD_LENGTH(Task 7)**: 백/프런트 동일 값(10) — 한쪽만 바꾸면 프런트 선검증 통과 후 서버 400 불일치.
- **비번 상향(Task 6)**으로 기존 8~9자 성공 기대 테스트가 깨질 수 있음 — Task 6 Step 6에서 전수 보정.
