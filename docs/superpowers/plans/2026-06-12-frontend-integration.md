# 프런트 연동(4단계) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** mock 데이터로 동작 중인 login·admin 페이지와 노트 앱을 백엔드 실 API(세션 인증 + 관리자 API 24개)에 배선하고, mock에 없던 팀·스페이스 관리 UI를 신규 추가한다.

**Architecture:** 3-SPA 구조(index/login/admin) 유지. 공유 fetch 코어(`src/api/http.ts`)를 추출해 VaultApi·AuthApi·AdminApi가 공유하고, 401은 전역 핸들러로 login.html 리다이렉트. admin 스크린은 mock import를 API 데이터로 치환하되 JSX 레이아웃은 최대한 보존. 테스트는 기존 패턴 유지(vitest node env, fetch stub, 순수 함수 — testing-library/msw 미도입).

**Tech Stack:** Vite 6 + TypeScript + React 18, vitest. 백엔드는 기존 Spring Boot API 사용(이 플랜에서 백엔드 추가는 Task 5의 GET /api/admin/public 1개뿐).

---

## 확정 결정

| # | 결정 | 근거 |
|---|------|------|
| 1 | admin·login 앱은 **항상 HTTP** (VITE_STORAGE 무관) | 인증·관리 도메인은 백엔드 없이 무의미. mock 이중 경로 제거(YAGNI). dev에서 admin/login 작업 시 백엔드 기동 필요 — README에 명시 |
| 2 | 공유 fetch 코어 `src/api/http.ts` 추출, VaultApi는 코어 사용으로 리팩터 | req/ApiError 중복 3벌 방지. `ApiError`는 VaultApi에서 re-export(기존 import 호환) |
| 3 | 401 처리 = **전역 on401 핸들러**(설치형) → `location.href = "login.html"` | notes(http 모드)·admin 엔트리에서만 설치. login 앱은 미설치(로그인 실패 401이 리다이렉트 루프가 되면 안 됨) |
| 4 | 403/409/422는 토스트(기존 낙관적 UI 관례 유지), 401만 리다이렉트 | 세션 만료만 복구 불능 상태 |
| 5 | 노트 앱 세션: http 모드에서만 `GET /api/auth/me` 부트스트랩. 백엔드 local 모드는 me가 local admin을 반환하므로 로그인 화면 없이 자연 동작 | 모드 매트릭스 단순화 — 프런트는 백엔드 모드를 모름 |
| 6 | admin 링크/진입 가드 판정 = `me.caps.includes("admin.users")` (표시용. 최종 방어는 백엔드 AdminGuard) | 프런트 판정은 UX용 — 백엔드 24/24 가드가 enforce |
| 7 | Pending 스크린 = users 목록의 `status === "pending"` 클라이언트 필터. 거절 = `PATCH status=disabled` | 별도 pending API 없음. 거절 전용 API도 없음 — disabled가 의미상 거절 |
| 8 | status/caps/act 한국어 라벨은 `admin/mappers.ts` 순수 함수로 집중 (active→활성 등) | 스크린마다 매핑 흩어짐 방지, 테스트 용이 |
| 9 | **백엔드 1건 추가**: `GET /api/admin/public` → `List<PublicFlagRow>` | public 플래그 **조회** API가 없어 Permissions 화면이 현재 상태를 표시할 수 없음(쓰기만 존재). 조회는 감사 안 함(3단계 결정 #13과 일치) |
| 10 | Permissions 화면은 **노드 중심**으로 재구성: 트리에서 노드 선택 → 직접 ACL 엔트리 편집(PUT replace-all) + 조상 상속 엔트리 read-only + public 토글 | mock은 사용자 중심(grants)이었으나 실제 모델은 노드별 ACL(주체 user/team/all). 상속 표시는 클라이언트 조상 walk(`admin/aclView.ts` 순수 함수) |
| 11 | 팀·스페이스 = **신규 스크린 1개**(`screens/Teams.tsx`, NAV "팀·스페이스") — 팀 CRUD+멤버, 스페이스 지정/해제 | 스페이스는 팀에 종속 개념이라 한 화면. mock 부재 → 기존 admin CSS 클래스 재사용해 신규 작성 |
| 12 | Security 스크린 = **read-only**: 서버 고정 정책 표시(비밀번호 최소 8자, 세션 30분, 가입 승인 필수, PBKDF2 120k) + "정책은 서버에 고정" 배너. 편집 컨트롤 제거 | 보안 설정 API 없음(이월 안 함 — 폐쇄망 소규모에서 동적 정책 YAGNI) |
| 13 | Dashboard = users + audit(최근 50) 조합으로 실통계 | 전용 통계 API 불필요 |
| 14 | 테스트: vitest node env 유지, `vi.stubGlobal("fetch", vi.fn())` 패턴. API 모듈·매퍼·aclView·폼 로직(순수 함수 추출) 단위 테스트. DOM 테스트 미도입 | 기존 useVaultSync.test 패턴 계승. testing-library 도입은 별도 결정 사항 |
| 15 | 로그인 페이지 mount 시 me() 성공하면 index.html로 리다이렉트 | 이미 로그인된 사용자/백엔드 local 모드에서 로그인 화면 무의미 |
| 16 | 커밋은 main 직커밋, 한국어 conventional commit, **push 금지** | 기존 세션 관례 |

## 백엔드 API 계약 (배선 대상 — 구현자는 이 표를 신뢰해도 됨)

에러 공통: `{ "error": string }` — 401(AuthException)/403/404/409/422(VaultException).

**Auth** (`/api/auth`):
| 메서드 | 경로 | 요청 | 응답 |
|---|---|---|---|
| POST | /login | `{emp, password}` | 201 `{id, emp, name, roleId, caps: string[]}` / 401 |
| POST | /signup | `{emp, name, email?, password}` (pw 8~128자) | 201 `{id, status:"pending"}` / 409 사번 중복 / 422 |
| POST | /logout | — | 204 |
| GET | /me | — | 200 MeResponse (local 모드: `{id:"local",emp:"local",name:"local",roleId:"admin",caps:[...]}`) / server 모드 무세션 401 |

**Admin** (`/api/admin`, 전부 AdminGuard — 비관리자 403):
| 메서드 | 경로 | 요청/응답 |
|---|---|---|
| GET | /users | `UserRow[]` = `{id, emp, email, name, roleId, status:"pending"\|"active"\|"disabled", lastLogin: string\|null}` |
| POST | /users | `{emp, name, email?, roleId, password}` → 201 UserRow |
| PATCH | /users/{id} | `{name?, email?, roleId?, status?: "active"\|"disabled"}` → UserRow. self 변경·마지막 admin 강등 422 |
| POST | /users/{id}/approve | → UserRow (pending→active) |
| POST | /users/{id}/reset-password | `{password}` → 204 (대상 세션 즉시 무효) |
| GET/POST | /roles | `RoleView[]` = `{id, name, system: boolean, caps: string[], userCount}` / 생성 `{id, name, caps}` (id: `[a-z][a-z0-9-]*`) |
| PATCH/DELETE | /roles/{id} | `{name?, caps?}` / 사용 중 삭제 409, system 역할 422 |
| GET | /teams | `TeamView[]` = `{id, name, members: UserRow[]}` |
| POST | /teams | `{name}` → 201 `{id, name}` |
| PATCH/DELETE | /teams/{id} | `{name}` → 204 / 스페이스 소유 팀 삭제 409 |
| POST | /teams/{id}/members | `{userId}` → 204 |
| DELETE | /teams/{id}/members/{userId} | → 204 |
| GET | /spaces | `SpaceRow[]` = `{nodeId, teamId: string\|null}` (null=공용) |
| PUT/DELETE | /spaces/{nodeId} | `{teamId: string\|null}` → 204 (최상위 활성 폴더만 422) |
| GET | /acl | `AclRow[]` = `{principalType:"user"\|"team"\|"all", principalId, nodeId, grantType:"read"\|"edit"\|"deny"}` |
| GET/PUT | /nodes/{id}/acl | PUT `{entries: [{principalType, principalId, grantType}]}` → 204 (replace-all, 중복 주체 422) |
| PUT/DELETE | /nodes/{id}/public | `{mode: "public"\|"exclude"}` → 204 |
| GET | /public | **Task 5에서 신설** → `PublicFlagRow[]` = `{nodeId, mode}` |
| GET | /audit | `?who=&act=&from=&to=&limit=&offset=` → `{total: number, rows: AuditRow[]}` = `{id, at, who, act, target, ip}` (at DESC, limit 1..200 기본 50) |

caps 실값: ADMIN = `admin.users, admin.permissions, admin.roles, admin.security, admin.audit` / RES = `res.read, res.edit, res.create, res.delete, res.export, res.share`.
audit act 실값: `login.success, login.fail, logout, signup, signup.fail, user.create, user.update, user.approve, user.reset, role.create, role.update, role.delete, team.create, team.update, team.delete, team.member.add, team.member.remove, acl.set, public.set, public.unset, space.set, space.unset, node.create, node.move, node.trash, node.restore, node.purge`.

## 파일 구조

```
frontend/src/
  api/
    http.ts            신규 — req<T>, ApiError, setOn401 (공유 코어)
    http.test.ts       신규
    auth.ts            신규 — AuthApi (login/signup/logout/me), Me 타입
    auth.test.ts       신규
  storage/VaultApi.ts  수정 — http.ts 코어 사용, ApiError re-export
  login/LoginPage.tsx  수정 — 실 API 배선
  login/loginLogic.ts  신규 — 폼 검증·제출 순수 로직
  login/loginLogic.test.ts 신규
  state/useSession.ts  신규 — 노트 앱 me 부트스트랩 + on401 설치 + logout
  App.tsx (외)         수정 — useSession 배선, admin 링크 가드, 로그아웃
  admin/
    api.ts             신규 — AdminApi 전체 + Api* 타입
    api.test.ts        신규
    mappers.ts         신규 — 라벨 매핑(status/caps/act), 파생 변환
    mappers.test.ts    신규
    aclView.ts         신규 — 직접/상속 엔트리·public 상태 계산(순수)
    aclView.test.ts    신규
    AdminApp.tsx       수정 — 가드, 컨텍스트, NAV+팀·스페이스, 로그아웃
    data.ts            수정(축소) — mock 상수 제거, 타입 일부만 잔존/이동
    screens/*.tsx      수정 — 7스크린 배선
    screens/Teams.tsx  신규 — 팀·스페이스
backend/src/main/java/com/worknote/admin/AdminAclController.java  수정 — GET /public
backend/src/main/java/com/worknote/acl/AclMapper.java(+XML)       수정 — findAllPublicFlags
```

---

### Task 1: 공유 HTTP 코어 추출 (`src/api/http.ts`)

**Files:**
- Create: `frontend/src/api/http.ts`, `frontend/src/api/http.test.ts`
- Modify: `frontend/src/storage/VaultApi.ts`

- [x] **Step 1: 실패하는 테스트 작성** — `frontend/src/api/http.test.ts`

```typescript
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { req, ApiError, setOn401 } from "./http";

function jsonRes(status: number, body?: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: () => (body === undefined ? Promise.reject(new Error("no body")) : Promise.resolve(body)),
  } as unknown as Response;
}

describe("req", () => {
  beforeEach(() => { vi.stubGlobal("fetch", vi.fn()); });
  afterEach(() => { vi.unstubAllGlobals(); setOn401(null); });

  it("성공 시 JSON 반환, /api prefix와 Content-Type 적용", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(jsonRes(200, { a: 1 }));
    const out = await req<{ a: number }>("/tree");
    expect(out).toEqual({ a: 1 });
    expect(fetch).toHaveBeenCalledWith("/api/tree", expect.objectContaining({
      headers: expect.objectContaining({ "Content-Type": "application/json" }),
    }));
  });

  it("204는 undefined", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(jsonRes(204));
    await expect(req<void>("/x", { method: "POST" })).resolves.toBeUndefined();
  });

  it("에러 바디의 error 메시지로 ApiError", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(jsonRes(409, { error: "중복" }));
    await expect(req("/x")).rejects.toMatchObject({ status: 409, message: "중복" });
  });

  it("에러 바디가 JSON이 아니면 HTTP n 메시지", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(jsonRes(500));
    await expect(req("/x")).rejects.toMatchObject({ status: 500, message: "HTTP 500" });
  });

  it("401이면 on401 핸들러 호출 후에도 ApiError throw", async () => {
    const handler = vi.fn();
    setOn401(handler);
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(jsonRes(401, { error: "인증이 필요합니다" }));
    await expect(req("/x")).rejects.toBeInstanceOf(ApiError);
    expect(handler).toHaveBeenCalledOnce();
  });

  it("on401 미설치면 401도 그냥 throw", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(jsonRes(401, {}));
    await expect(req("/x")).rejects.toMatchObject({ status: 401 });
  });
});
```

- [x] **Step 2: 실패 확인** — `cd frontend && pnpm test` → http.test.ts FAIL (모듈 없음)

- [x] **Step 3: 구현** — `frontend/src/api/http.ts`

```typescript
/* 공유 fetch 코어 — VaultApi/AuthApi/AdminApi가 공용. 세션 쿠키는 same-origin 자동 전송. */
const BASE = "/api";

export class ApiError extends Error {
  constructor(message: string, public status: number) {
    super(message);
  }
}

/** 401 전역 핸들러(세션 만료 → login.html). login 앱은 설치하지 않는다 — 로그인 실패 401이 리다이렉트가 되면 안 됨. */
let on401: (() => void) | null = null;
export function setOn401(handler: (() => void) | null) {
  on401 = handler;
}

export async function req<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(BASE + path, {
    ...init,
    headers: { "Content-Type": "application/json", ...(init?.headers as Record<string, string>) },
  });
  if (!res.ok) {
    if (res.status === 401 && on401) on401();
    const body = (await res.json().catch(() => ({}))) as { error?: string };
    throw new ApiError(body.error ?? `HTTP ${res.status}`, res.status);
  }
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
}
```

- [x] **Step 4: VaultApi 리팩터** — `frontend/src/storage/VaultApi.ts`의 자체 `req`/`ApiError` 정의를 삭제하고 `import { req, ApiError } from "../api/http"` 사용. 기존 import 호환을 위해 `export { ApiError };` 유지. VaultApi 객체의 메서드 본문은 그대로.

- [x] **Step 5: 전체 테스트 green 확인** — `pnpm test` → http.test.ts + 기존 useVaultSync.test 등 전부 PASS

- [x] **Step 6: 커밋** — `git add -A && git commit -m "refactor(frontend): 공유 HTTP 코어 추출 — req/ApiError/setOn401"`

### Task 2: AuthApi (`src/api/auth.ts`)

**Files:**
- Create: `frontend/src/api/auth.ts`, `frontend/src/api/auth.test.ts`

- [x] **Step 1: 실패하는 테스트 작성** — `frontend/src/api/auth.test.ts`

```typescript
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { AuthApi } from "./auth";

describe("AuthApi", () => {
  beforeEach(() => { vi.stubGlobal("fetch", vi.fn()); });
  afterEach(() => { vi.unstubAllGlobals(); });

  it("login은 POST /api/auth/login에 emp/password를 보낸다", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
      ok: true, status: 201,
      json: () => Promise.resolve({ id: "u1", emp: "S1", name: "n", roleId: "admin", caps: ["admin.users"] }),
    });
    const me = await AuthApi.login("S1", "pw123456");
    expect(me.roleId).toBe("admin");
    expect(fetch).toHaveBeenCalledWith("/api/auth/login", expect.objectContaining({
      method: "POST", body: JSON.stringify({ emp: "S1", password: "pw123456" }),
    }));
  });

  it("signup은 POST /api/auth/signup", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
      ok: true, status: 201, json: () => Promise.resolve({ id: "u9", status: "pending" }),
    });
    const out = await AuthApi.signup({ emp: "S9", name: "신규", email: "a@b", password: "pw123456" });
    expect(out.status).toBe("pending");
  });

  it("logout은 POST /api/auth/logout (204)", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue({ ok: true, status: 204, json: () => Promise.reject(new Error()) });
    await expect(AuthApi.logout()).resolves.toBeUndefined();
  });

  it("me는 GET /api/auth/me", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
      ok: true, status: 200,
      json: () => Promise.resolve({ id: "local", emp: "local", name: "local", roleId: "admin", caps: [] }),
    });
    const me = await AuthApi.me();
    expect(me.id).toBe("local");
    expect(fetch).toHaveBeenCalledWith("/api/auth/me", expect.anything());
  });
});
```

- [x] **Step 2: 실패 확인** — `pnpm test` → FAIL

- [x] **Step 3: 구현** — `frontend/src/api/auth.ts`

```typescript
import { req } from "./http";

/** GET /api/auth/me 응답. 백엔드 local 모드는 {id:"local", roleId:"admin", ...}. */
export interface Me {
  id: string;
  emp: string;
  name: string;
  roleId: string;
  caps: string[];
}

export interface SignupForm {
  emp: string;
  name: string;
  email: string;
  password: string;
}

export const AuthApi = {
  login: (emp: string, password: string) =>
    req<Me>("/auth/login", { method: "POST", body: JSON.stringify({ emp, password }) }),
  signup: (form: SignupForm) =>
    req<{ id: string; status: string }>("/auth/signup", { method: "POST", body: JSON.stringify(form) }),
  logout: () => req<void>("/auth/logout", { method: "POST" }),
  me: () => req<Me>("/auth/me"),
};
```

- [x] **Step 4: green 확인 + 커밋** — `pnpm test` PASS 후 `git commit -m "feat(frontend): AuthApi — login/signup/logout/me"`

### Task 3: 로그인 페이지 배선

**Files:**
- Create: `frontend/src/login/loginLogic.ts`, `frontend/src/login/loginLogic.test.ts`
- Modify: `frontend/src/login/LoginPage.tsx`

- [x] **Step 1: 실패하는 테스트 작성** — `frontend/src/login/loginLogic.test.ts`

```typescript
import { describe, it, expect, vi } from "vitest";
import { validateSignup, submitLogin, submitSignup } from "./loginLogic";
import { ApiError } from "../api/http";

describe("validateSignup", () => {
  it("필수 필드 누락이면 메시지", () => {
    expect(validateSignup({ emp: "", name: "n", email: "", password: "12345678", password2: "12345678" }))
      .toContain("사번");
  });
  it("비밀번호 8자 미만이면 메시지", () => {
    expect(validateSignup({ emp: "S1", name: "n", email: "", password: "1234567", password2: "1234567" }))
      .toContain("8자");
  });
  it("비밀번호 불일치면 메시지", () => {
    expect(validateSignup({ emp: "S1", name: "n", email: "", password: "12345678", password2: "12345679" }))
      .toContain("일치");
  });
  it("정상이면 null", () => {
    expect(validateSignup({ emp: "S1", name: "n", email: "", password: "12345678", password2: "12345678" }))
      .toBeNull();
  });
});

describe("submitLogin", () => {
  it("성공 시 onSuccess 호출", async () => {
    const api = { login: vi.fn().mockResolvedValue({ id: "u1" }) };
    const onSuccess = vi.fn();
    const err = await submitLogin(api as never, "S1", "pw", onSuccess);
    expect(err).toBeNull();
    expect(onSuccess).toHaveBeenCalled();
  });
  it("401이면 에러 메시지 반환", async () => {
    const api = { login: vi.fn().mockRejectedValue(new ApiError("사번 또는 비밀번호가 올바르지 않습니다", 401)) };
    const err = await submitLogin(api as never, "S1", "bad", vi.fn());
    expect(err).toContain("올바르지");
  });
});

describe("submitSignup", () => {
  it("409(사번 중복)면 서버 메시지 반환", async () => {
    const api = { signup: vi.fn().mockRejectedValue(new ApiError("이미 존재하는 사번", 409)) };
    const out = await submitSignup(api as never, { emp: "S1", name: "n", email: "", password: "12345678" });
    expect(out.error).toContain("이미 존재");
  });
  it("성공이면 done", async () => {
    const api = { signup: vi.fn().mockResolvedValue({ id: "u9", status: "pending" }) };
    const out = await submitSignup(api as never, { emp: "S9", name: "n", email: "", password: "12345678" });
    expect(out.done).toBe(true);
  });
});
```

- [x] **Step 2: 실패 확인** — `pnpm test` → FAIL

- [x] **Step 3: 구현** — `frontend/src/login/loginLogic.ts`

```typescript
import { ApiError } from "../api/http";
import type { AuthApi as AuthApiType, SignupForm } from "../api/auth";

export interface SignupInput extends SignupForm {
  password2: string;
}

/** 클라이언트 선검증 — 통과 시 null, 실패 시 사용자 메시지. 서버 검증(@Valid)이 최종. */
export function validateSignup(f: SignupInput): string | null {
  if (!f.emp.trim() || !f.name.trim()) return "사번과 이름을 입력하세요";
  if (f.password.length < 8) return "비밀번호는 8자 이상이어야 합니다";
  if (f.password !== f.password2) return "비밀번호가 일치하지 않습니다";
  return null;
}

export async function submitLogin(
  api: typeof AuthApiType, emp: string, password: string, onSuccess: () => void,
): Promise<string | null> {
  try {
    await api.login(emp.trim(), password);
    onSuccess();
    return null;
  } catch (e) {
    return e instanceof ApiError ? e.message : "서버에 연결할 수 없습니다";
  }
}

export async function submitSignup(
  api: typeof AuthApiType, form: SignupForm,
): Promise<{ done: boolean; error: string | null }> {
  try {
    await api.signup({ ...form, emp: form.emp.trim(), name: form.name.trim() });
    return { done: true, error: null };
  } catch (e) {
    return { done: false, error: e instanceof ApiError ? e.message : "서버에 연결할 수 없습니다" };
  }
}
```

- [x] **Step 4: LoginPage 배선** — `LoginPage.tsx` 수정:
  - `doLogin`: sessionStorage 저장 제거 → `submitLogin(AuthApi, emp, pw, () => { location.href = "index.html"; })` 호출, 반환 메시지를 `setErr`. 제출 중 버튼 disabled(`busy` state).
  - `doSignup`: `validateSignup` 선검증 → 실패 메시지 setErr → 통과 시 `submitSignup` → `done`이면 `setMode("done")`, 아니면 setErr.
  - mount 시 이미 로그인 상태면 통과: `useEffect(() => { AuthApi.me().then(() => { location.href = "index.html"; }).catch(() => {}); }, [])` (결정 #15). `setOn401`은 설치하지 않는다.

- [x] **Step 5: green 확인 + 수동 점검** — `pnpm test` PASS. (수동 검증은 Task 14 스모크에서 일괄)

- [x] **Step 6: 커밋** — `git commit -m "feat(frontend): 로그인·가입 실 API 배선"`

### Task 4: 노트 앱 세션 부트스트랩

**Files:**
- Create: `frontend/src/state/useSession.ts`
- Modify: `frontend/src/App.tsx` (admin 링크·프로필 영역 — 구현자는 현 구조 확인 후 해당 컴포넌트에 배선. admin 진입점이 ProfileModal/Sidebar 어디든 me 가드 적용)

- [x] **Step 1: 구현** — `frontend/src/state/useSession.ts` (순수 로직이 얇아 훅 자체 테스트는 생략 — http 모드 분기는 기존 useVaultSync와 동일 패턴)

```typescript
import React from "react";
import { AuthApi, type Me } from "../api/auth";
import { setOn401 } from "../api/http";
import { storageMode } from "../storage";   // 실제 export 이름은 storage/index.ts 확인 후 일치시킬 것

const { useState, useEffect } = React;

/** http 모드 전용 세션. local 스토리지 모드는 me=null 고정(인증 개념 없음). */
export function useSession(): { me: Me | null; isAdmin: boolean; logout: () => void } {
  const [me, setMe] = useState<Me | null>(null);

  useEffect(() => {
    if (storageMode !== "http") return;
    setOn401(() => { location.href = "login.html"; });
    AuthApi.me().then(setMe).catch(() => { /* 401은 on401이 처리, 그 외(서버 다운)는 무세션 표시 */ });
    return () => setOn401(null);
  }, []);

  const logout = () => {
    AuthApi.logout().finally(() => { location.href = "login.html"; });
  };

  return { me, isAdmin: me?.caps.includes("admin.users") ?? false, logout };
}
```

- [x] **Step 2: App 배선** — App.tsx(또는 admin 링크가 있는 컴포넌트)에서 `useSession()` 사용:
  - admin 진입 버튼/링크(`location.href = "admin.html"`)는 `local 스토리지 모드이거나 isAdmin`일 때만 렌더.
  - me가 있으면 프로필 영역에 `me.name (me.emp)` 표시, http 모드에서 로그아웃 버튼 추가(클릭 → `logout()`).
  - local 스토리지 모드 동작 무변화 확인(me=null 경로).

- [x] **Step 3: 전체 테스트 + 타입 체크** — `pnpm test && pnpm build` PASS

- [x] **Step 4: 커밋** — `git commit -m "feat(frontend): 노트 앱 세션 부트스트랩 — me·401 리다이렉트·로그아웃·admin 링크 가드"`

### Task 5: (백엔드) GET /api/admin/public

**Files:**
- Modify: `backend/src/main/java/com/worknote/admin/AdminAclController.java`, `backend/src/main/java/com/worknote/admin/AclAdminService.java`, `backend/src/main/java/com/worknote/acl/AclMapper.java`, `backend/src/main/resources/mapper/AclMapper.xml`
- Test: `backend/src/test/java/com/worknote/admin/AdminPublicApiTest.java`

- [x] **Step 1: 실패하는 테스트 작성** — AdminPublicApiTest에 추가 (기존 클래스 패턴·인메모리 DB 관례 준수: @BeforeEach public_flag 정리 유의)

```java
@Test
void 전체_public_플래그를_조회한다() throws Exception {
    String admin = loginAdmin();
    createFolder(admin, "pf-f1", "공개폴더");
    createNote(admin, "pf-n1", "pf-f1", "노트");
    setPublic(admin, "pf-f1", "public");
    setPublic(admin, "pf-n1", "exclude");

    mvc.perform(get("/api/admin/public").session(session(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.nodeId=='pf-f1')].mode").value("public"))
        .andExpect(jsonPath("$[?(@.nodeId=='pf-n1')].mode").value("exclude"));
}

@Test
void 비관리자는_public_조회_403() throws Exception {
    // 기존 클래스의 비관리자 세션 헬퍼 패턴 그대로 사용
}
```

(헬퍼 메서드명은 기존 AdminPublicApiTest의 실제 헬퍼에 맞출 것 — 테스트 클래스에 이미 setPublic/노드 생성 패턴 존재)

- [x] **Step 2: 실패 확인** — `./gradlew test --tests AdminPublicApiTest` → 404로 FAIL

- [x] **Step 3: 구현**
  - `AclMapper.java`: `List<PublicFlagRow> findAllPublicFlags();`
  - `AclMapper.xml`: `<select id="findAllPublicFlags" resultType="com.worknote.acl.PublicFlagRow">SELECT node_id AS nodeId, mode FROM public_flag ORDER BY node_id</select>`
  - `AclAdminService.java`: `public List<PublicFlagRow> listPublicFlags() { return acl.findAllPublicFlags(); }`
  - `AdminAclController.java`: 

```java
@GetMapping("/public")
public List<PublicFlagRow> listPublic(HttpServletRequest req) {
    guard.requireAdmin(user(req));
    return svc.listPublicFlags();
}
```

  조회이므로 감사 기록 없음(3단계 결정 #13과 동일).

- [x] **Step 4: green 확인** — `./gradlew test` 전체 PASS (195+)

- [x] **Step 5: 커밋** — `git commit -m "feat(backend): GET /api/admin/public — public 플래그 전체 조회 (프런트 표시용)"`

### Task 6: AdminApi + mappers

**Files:**
- Create: `frontend/src/admin/api.ts`, `frontend/src/admin/api.test.ts`, `frontend/src/admin/mappers.ts`, `frontend/src/admin/mappers.test.ts`

- [x] **Step 1: 실패하는 테스트 작성** — `api.test.ts`는 fetch stub으로 대표 경로 검증(전 엔드포인트 URL/메서드/바디 — users/roles/teams/spaces/acl/public/audit 각 1개 이상, audit은 쿼리스트링 조립 검증), `mappers.test.ts`는 라벨 함수 검증:

```typescript
// mappers.test.ts 핵심 케이스
import { describe, it, expect } from "vitest";
import { statusLabel, capLabel, actLabel, actType, roleName } from "./mappers";

describe("mappers", () => {
  it("statusLabel", () => {
    expect(statusLabel("active")).toBe("활성");
    expect(statusLabel("disabled")).toBe("비활성");
    expect(statusLabel("pending")).toBe("대기");
  });
  it("capLabel은 미지 cap이면 원문", () => {
    expect(capLabel("admin.users")).toBe("사용자 관리");
    expect(capLabel("res.export")).toBe("내보내기");
    expect(capLabel("x.y")).toBe("x.y");
  });
  it("actLabel은 dot 명명을 한국어로, 미지 act는 원문", () => {
    expect(actLabel("login.success")).toBe("로그인");
    expect(actLabel("user.approve")).toBe("계정 승인");
    expect(actLabel("unknown.act")).toBe("unknown.act");
  });
  it("actType은 배지 분류", () => {
    expect(actType("login.fail")).toBe("loginfail");
    expect(actType("user.approve")).toBe("approve");
    expect(actType("acl.set")).toBe("grant");
    expect(actType("user.reset")).toBe("reset");
    expect(actType("login.success")).toBe("login");
  });
  it("roleName은 roles에서 찾고 없으면 id", () => {
    expect(roleName("admin", [{ id: "admin", name: "관리자", system: true, caps: [], userCount: 1 }])).toBe("관리자");
    expect(roleName("ghost", [])).toBe("ghost");
  });
});
```

```typescript
// api.test.ts 핵심 케이스 (발췌 — 전 메서드를 같은 패턴으로)
it("audit은 빈 필터를 쿼리에서 생략한다", async () => {
  (fetch as ReturnType<typeof vi.fn>).mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve({ total: 0, rows: [] }) });
  await AdminApi.audit({ who: "", act: "login.fail", limit: 50, offset: 0 });
  expect(fetch).toHaveBeenCalledWith("/api/admin/audit?act=login.fail&limit=50&offset=0", expect.anything());
});
it("setAcl은 PUT replace-all", async () => {
  (fetch as ReturnType<typeof vi.fn>).mockResolvedValue({ ok: true, status: 204, json: () => Promise.reject(new Error()) });
  await AdminApi.setAcl("n1", [{ principalType: "team", principalId: "t1", grantType: "read" }]);
  expect(fetch).toHaveBeenCalledWith("/api/admin/nodes/n1/acl", expect.objectContaining({
    method: "PUT", body: JSON.stringify({ entries: [{ principalType: "team", principalId: "t1", grantType: "read" }] }),
  }));
});
```

- [x] **Step 2: 실패 확인** — `pnpm test` → FAIL

- [x] **Step 3: 구현** — `frontend/src/admin/api.ts` (타입은 위 "백엔드 API 계약" 표와 1:1):

```typescript
import { req } from "../api/http";

export interface ApiUser { id: string; emp: string; email: string | null; name: string; roleId: string; status: "pending" | "active" | "disabled"; lastLogin: string | null; }
export interface ApiRole { id: string; name: string; system: boolean; caps: string[]; userCount: number; }
export interface ApiTeam { id: string; name: string; members: ApiUser[]; }
export interface ApiSpace { nodeId: string; teamId: string | null; }
export interface ApiAclEntry { principalType: "user" | "team" | "all"; principalId: string; grantType: "read" | "edit" | "deny"; }
export interface ApiAclRow extends ApiAclEntry { nodeId: string; }
export interface ApiPublicFlag { nodeId: string; mode: "public" | "exclude"; }
export interface ApiAudit { id: number; at: string; who: string; act: string; target: string; ip: string; }
export interface AuditQuery { who?: string; act?: string; from?: string; to?: string; limit?: number; offset?: number; }

function qs(params: Record<string, string | number | undefined>): string {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== "") p.set(k, String(v));
  }
  const s = p.toString();
  return s ? "?" + s : "";
}

export const AdminApi = {
  users: () => req<ApiUser[]>("/admin/users"),
  createUser: (b: { emp: string; name: string; email?: string; roleId: string; password: string }) =>
    req<ApiUser>("/admin/users", { method: "POST", body: JSON.stringify(b) }),
  updateUser: (id: string, patch: { name?: string; email?: string; roleId?: string; status?: "active" | "disabled" }) =>
    req<ApiUser>(`/admin/users/${id}`, { method: "PATCH", body: JSON.stringify(patch) }),
  approveUser: (id: string) => req<ApiUser>(`/admin/users/${id}/approve`, { method: "POST" }),
  resetPassword: (id: string, password: string) =>
    req<void>(`/admin/users/${id}/reset-password`, { method: "POST", body: JSON.stringify({ password }) }),

  roles: () => req<ApiRole[]>("/admin/roles"),
  createRole: (b: { id: string; name: string; caps: string[] }) =>
    req<ApiRole>("/admin/roles", { method: "POST", body: JSON.stringify(b) }),
  updateRole: (id: string, patch: { name?: string; caps?: string[] }) =>
    req<ApiRole>(`/admin/roles/${id}`, { method: "PATCH", body: JSON.stringify(patch) }),
  deleteRole: (id: string) => req<void>(`/admin/roles/${id}`, { method: "DELETE" }),

  teams: () => req<ApiTeam[]>("/admin/teams"),
  createTeam: (name: string) => req<{ id: string; name: string }>("/admin/teams", { method: "POST", body: JSON.stringify({ name }) }),
  renameTeam: (id: string, name: string) => req<void>(`/admin/teams/${id}`, { method: "PATCH", body: JSON.stringify({ name }) }),
  deleteTeam: (id: string) => req<void>(`/admin/teams/${id}`, { method: "DELETE" }),
  addMember: (teamId: string, userId: string) =>
    req<void>(`/admin/teams/${teamId}/members`, { method: "POST", body: JSON.stringify({ userId }) }),
  removeMember: (teamId: string, userId: string) =>
    req<void>(`/admin/teams/${teamId}/members/${userId}`, { method: "DELETE" }),

  spaces: () => req<ApiSpace[]>("/admin/spaces"),
  setSpace: (nodeId: string, teamId: string | null) =>
    req<void>(`/admin/spaces/${nodeId}`, { method: "PUT", body: JSON.stringify({ teamId }) }),
  unsetSpace: (nodeId: string) => req<void>(`/admin/spaces/${nodeId}`, { method: "DELETE" }),

  aclAll: () => req<ApiAclRow[]>("/admin/acl"),
  aclForNode: (nodeId: string) => req<ApiAclRow[]>(`/admin/nodes/${nodeId}/acl`),
  setAcl: (nodeId: string, entries: ApiAclEntry[]) =>
    req<void>(`/admin/nodes/${nodeId}/acl`, { method: "PUT", body: JSON.stringify({ entries }) }),
  publicFlags: () => req<ApiPublicFlag[]>("/admin/public"),
  setPublic: (nodeId: string, mode: "public" | "exclude") =>
    req<void>(`/admin/nodes/${nodeId}/public`, { method: "PUT", body: JSON.stringify({ mode }) }),
  unsetPublic: (nodeId: string) => req<void>(`/admin/nodes/${nodeId}/public`, { method: "DELETE" }),

  audit: (q: AuditQuery) => req<{ total: number; rows: ApiAudit[] }>("/admin/audit" + qs(q as Record<string, string | number | undefined>)),
};
```

  `mappers.ts`:

```typescript
import type { ApiRole, ApiUser } from "./api";

const STATUS: Record<ApiUser["status"], string> = { active: "활성", disabled: "비활성", pending: "대기" };
export function statusLabel(s: ApiUser["status"]): string { return STATUS[s] ?? s; }

const CAPS: Record<string, string> = {
  "admin.users": "사용자 관리", "admin.permissions": "권한 관리", "admin.roles": "역할 관리",
  "admin.security": "보안 설정", "admin.audit": "감사 로그 조회",
  "res.read": "노트 열람", "res.edit": "노트 편집", "res.create": "노트 생성",
  "res.delete": "노트 삭제", "res.export": "내보내기", "res.share": "공유",
};
export function capLabel(cap: string): string { return CAPS[cap] ?? cap; }

const ACTS: Record<string, string> = {
  "login.success": "로그인", "login.fail": "로그인 실패", logout: "로그아웃",
  signup: "가입 신청", "signup.fail": "가입 실패",
  "user.create": "사용자 생성", "user.update": "사용자 변경", "user.approve": "계정 승인", "user.reset": "비밀번호 초기화",
  "role.create": "역할 생성", "role.update": "역할 변경", "role.delete": "역할 삭제",
  "team.create": "팀 생성", "team.update": "팀 변경", "team.delete": "팀 삭제",
  "team.member.add": "팀원 추가", "team.member.remove": "팀원 제외",
  "acl.set": "권한 설정", "public.set": "공개 설정", "public.unset": "공개 해제",
  "space.set": "스페이스 지정", "space.unset": "스페이스 해제",
  "node.create": "노드 생성", "node.move": "노드 이동", "node.trash": "휴지통 이동",
  "node.restore": "복구", "node.purge": "영구 삭제",
};
export function actLabel(act: string): string { return ACTS[act] ?? act; }

/** Audit 화면 배지 색 분류 — 기존 mock actType 클래스 재사용. */
export function actType(act: string): string {
  if (act.endsWith(".fail")) return "loginfail";
  if (act === "user.approve") return "approve";
  if (act === "user.reset") return "reset";
  if (act === "acl.set" || act.startsWith("public.") || act.startsWith("space.")) return "grant";
  if (act === "user.update" || act === "role.delete" || act === "team.member.remove") return "revoke";
  return "login";
}

export function roleName(roleId: string, roles: ApiRole[]): string {
  return roles.find((r) => r.id === roleId)?.name ?? roleId;
}
```

- [x] **Step 4: green 확인 + 커밋** — `pnpm test` PASS 후 `git commit -m "feat(frontend): AdminApi 전체 엔드포인트 + 한국어 라벨 매퍼"`

### Task 7: AdminApp 가드 + 공통 데이터 컨텍스트

**Files:**
- Modify: `frontend/src/admin/AdminApp.tsx`
- Create: `frontend/src/admin/useAdminData.ts` (컨텍스트 + 훅)

- [x] **Step 1: 구현** — `useAdminData.ts`: `AdminDataContext` = `{ me: Me | null, users: ApiUser[], roles: ApiRole[], teams: ApiTeam[], reload: () => Promise<void>, toast: (msg: string, icon?: string) => void }`. AdminApp이 provider:
  - mount 시 `setOn401(() => { location.href = "login.html"; })` 설치, unmount 시 해제.
  - `AuthApi.me()` → `caps.includes("admin.users")` 아니면 `location.href = "index.html"` (결정 #6).
  - 가드 통과 후 `Promise.all([AdminApi.users(), AdminApi.roles(), AdminApi.teams()])` 로드. 로드 전 로딩 표시(간단 텍스트), 실패 시 토스트.
  - `reload()`는 같은 3종 재로드 — 변이 후 스크린들이 호출.
- [x] **Step 2: NAV 변경** — pending 배지 = `users.filter(u => u.status === "pending").length` (mock import 제거). NAV에 `{ id: "teams", label: "팀·스페이스", icon: "users" }`를 "역할 관리" 다음에 추가, TITLES에 `teams: ["팀·스페이스", "팀 구성·팀 스페이스 관리"]`. screenMap에 Teams 등록(Task 12 전까지는 placeholder 컴포넌트 — `h("div", null, "준비 중")` 인라인이 아닌, Task 12에서 실제 파일 생성 시점에 등록해도 됨. **이 태스크에서는 NAV/TITLES만 추가하고 screenMap 등록은 Task 12로 미룬다** — 미등록 route는 Dashboard 폴백이라 안전).
- [x] **Step 3: 로그아웃** — atopbar 우측에 로그아웃 버튼: `AuthApi.logout().finally(() => { location.href = "login.html"; })`.
- [x] **Step 4: 빌드·테스트 확인** — `pnpm test && pnpm build` PASS (스크린들은 아직 mock — 다음 태스크에서 치환. data.ts mock은 이 시점 삭제 금지)
- [x] **Step 5: 커밋** — `git commit -m "feat(frontend): AdminApp 가드·공통 데이터 컨텍스트·로그아웃·팀 NAV"`

### Task 8: Pending + Users 스크린 배선

**Files:**
- Modify: `frontend/src/admin/screens/Pending.tsx`, `frontend/src/admin/screens/Users.tsx`

- [x] **Step 1: Pending 배선** — `ADMIN_PENDING` 제거 → `useAdminData()`의 `users.filter(u => u.status === "pending")`. 승인 = `AdminApi.approveUser(id)` → `reload()` + 토스트 "계정을 승인했습니다". 거절 = `AdminApi.updateUser(id, { status: "disabled" })` → reload + 토스트 (결정 #7). 에러는 `e instanceof ApiError ? e.message : "요청 실패"` 토스트. 신청 일시 컬럼은 백엔드에 가입 시각 필드가 없으므로 컬럼 제거 또는 "—" 표시(구현자 판단 — 데이터 없는 컬럼 유지 금지).
- [x] **Step 2: Users 배선** — `ADMIN_USERS`/`ADMIN_ROLES` 제거 → 컨텍스트 users/roles. 표기: `statusLabel(u.status)`, `roleName(u.roleId, roles)`, last = `u.lastLogin ?? "—"`.
  - 역할 변경: `AdminApi.updateUser(id, { roleId })`, 상태 토글: `{ status: "active" | "disabled" }` — 422(self/마지막 admin) 메시지는 서버 메시지 그대로 토스트.
  - 비밀번호 초기화: 새 비밀번호 입력 모달(8자 미만 클라이언트 차단) → `AdminApi.resetPassword`.
  - 사용자 생성 모달(기존 mock에 있으면 재사용, 없으면 추가): emp/name/email/roleId/password → `AdminApi.createUser` → 409 토스트.
  - 모든 변이 성공 후 `reload()`.
- [x] **Step 3: 테스트·빌드** — `pnpm test && pnpm build` PASS
- [x] **Step 4: 커밋** — `git commit -m "feat(frontend): 가입 승인·사용자 관리 스크린 실 API 배선"`

### Task 9: Roles 스크린 배선

**Files:**
- Modify: `frontend/src/admin/screens/Roles.tsx`

- [x] **Step 1: 배선** — `ADMIN_ROLES` 제거 → 컨텍스트 roles. 표시: `count` → `userCount`, `policy` 리스트 → `role.caps.map(capLabel)`, desc는 mock 전용 필드였으므로 제거(caps 라벨이 정책 설명을 대체).
  - 생성: id(`[a-z][a-z0-9-]*` 클라이언트 패턴 검증)/name/caps 체크박스(KNOWN 11종 — mappers의 CAPS 키 사용) → `AdminApi.createRole`.
  - 수정: system 역할은 편집/삭제 버튼 비활성(서버도 422). name/caps → `AdminApi.updateRole`.
  - 삭제: 사용 중 409 → 서버 메시지 토스트.
  - 변이 후 `reload()`.
- [x] **Step 2: 테스트·빌드 + 커밋** — PASS 후 `git commit -m "feat(frontend): 역할 관리 스크린 실 API 배선"`

### Task 10: Audit 스크린 배선

**Files:**
- Modify: `frontend/src/admin/screens/Audit.tsx`

- [x] **Step 1: 배선** — `ADMIN_AUDIT` 제거. 스크린 자체 state로 `AdminApi.audit({ who, act, from, to, limit: 50, offset })` 호출(mount + 필터 변경 + 페이지 이동 시).
  - 필터 UI: who 텍스트 입력, act는 mappers ACTS 키 셀렉트(전체 옵션 포함), from/to 날짜 입력(값은 `YYYY-MM-DD` → from은 그대로, to는 `T23:59:59` 접미 — ISO 사전순 비교 계약).
  - 표시: `actLabel(row.act)` + 배지 클래스 `actType(row.act)`, target/ip 그대로.
  - 페이징: total 기반 이전/다음 버튼(offset ±50).
- [x] **Step 2: 테스트·빌드 + 커밋** — PASS 후 `git commit -m "feat(frontend): 감사 로그 스크린 실 API 배선 — 필터·페이징"`

### Task 11: Permissions 스크린 배선 (노드 중심 재구성)

**Files:**
- Create: `frontend/src/admin/aclView.ts`, `frontend/src/admin/aclView.test.ts`
- Modify: `frontend/src/admin/screens/Permissions.tsx`

- [x] **Step 1: 실패하는 테스트 작성** — `aclView.test.ts`

```typescript
import { describe, it, expect } from "vitest";
import { ancestorsOf, inheritedEntries, directPublicMode, effectivePublic } from "./aclView";
import type { ApiAclRow, ApiPublicFlag } from "./api";

// VaultNode 트리(/api/tree 형태): folder는 children 보유
const tree = [
  { id: "f1", type: "folder", name: "A", children: [
    { id: "f2", type: "folder", name: "B", children: [
      { id: "n1", type: "note", title: "노트", children: undefined },
    ]},
  ]},
];

const acl: ApiAclRow[] = [
  { nodeId: "f1", principalType: "team", principalId: "t1", grantType: "read" },
  { nodeId: "f2", principalType: "user", principalId: "u1", grantType: "deny" },
  { nodeId: "n1", principalType: "user", principalId: "u2", grantType: "edit" },
];

describe("aclView", () => {
  it("ancestorsOf는 가까운 조상부터", () => {
    expect(ancestorsOf("n1", tree as never)).toEqual(["f2", "f1"]);
    expect(ancestorsOf("f1", tree as never)).toEqual([]);
  });
  it("inheritedEntries는 조상의 엔트리를 출처와 함께 (직접 엔트리 제외)", () => {
    const inh = inheritedEntries("n1", tree as never, acl);
    expect(inh).toHaveLength(2);
    expect(inh[0]).toMatchObject({ fromNodeId: "f2", grantType: "deny" });
    expect(inh[1]).toMatchObject({ fromNodeId: "f1", grantType: "read" });
  });
  it("directPublicMode / effectivePublic — nearest flag 의미론", () => {
    const flags: ApiPublicFlag[] = [{ nodeId: "f1", mode: "public" }, { nodeId: "n1", mode: "exclude" }];
    expect(directPublicMode("f1", flags)).toBe("public");
    expect(directPublicMode("f2", flags)).toBeNull();
    expect(effectivePublic("f2", tree as never, flags)).toBe(true);   // f1 public 상속
    expect(effectivePublic("n1", tree as never, flags)).toBe(false);  // 자기 exclude가 nearest
  });
});
```

- [x] **Step 2: 실패 확인** — `pnpm test` FAIL

- [x] **Step 3: 구현** — `aclView.ts`

```typescript
import type { ApiAclRow, ApiPublicFlag } from "./api";

interface TreeNode { id: string; type: string; name?: string; title?: string; children?: TreeNode[]; }

/** parent 맵 구축 후 가까운 조상부터 반환. */
export function ancestorsOf(nodeId: string, tree: TreeNode[]): string[] {
  const parent = new Map<string, string | null>();
  const walk = (nodes: TreeNode[], p: string | null) => {
    for (const n of nodes) {
      parent.set(n.id, p);
      if (n.children) walk(n.children, n.id);
    }
  };
  walk(tree, null);
  const out: string[] = [];
  let cur = parent.get(nodeId) ?? null;
  while (cur) {
    out.push(cur);
    cur = parent.get(cur) ?? null;
  }
  return out;
}

export interface InheritedEntry extends ApiAclRow { fromNodeId: string; }

/** 조상 노드들의 ACL 엔트리 — 가까운 조상 순. 표시 전용(유효 권한 계산은 서버 책임). */
export function inheritedEntries(nodeId: string, tree: TreeNode[], all: ApiAclRow[]): InheritedEntry[] {
  const byNode = new Map<string, ApiAclRow[]>();
  for (const r of all) {
    const list = byNode.get(r.nodeId) ?? [];
    list.push(r);
    byNode.set(r.nodeId, list);
  }
  const out: InheritedEntry[] = [];
  for (const anc of ancestorsOf(nodeId, tree)) {
    for (const r of byNode.get(anc) ?? []) out.push({ ...r, fromNodeId: anc });
  }
  return out;
}

export function directPublicMode(nodeId: string, flags: ApiPublicFlag[]): "public" | "exclude" | null {
  return flags.find((f) => f.nodeId === nodeId)?.mode ?? null;
}

/** nearest-flag: 자기 → 조상 순으로 첫 플래그. public이면 노출(서버 AclResolver.publicRead와 동일 의미론 — 표시용). */
export function effectivePublic(nodeId: string, tree: TreeNode[], flags: ApiPublicFlag[]): boolean {
  const direct = directPublicMode(nodeId, flags);
  if (direct) return direct === "public";
  for (const anc of ancestorsOf(nodeId, tree)) {
    const m = directPublicMode(anc, flags);
    if (m) return m === "public";
  }
  return false;
}
```

- [x] **Step 4: Permissions.tsx 재구성** — mock(ADMIN_TREE/ADMIN_GRANTS/ADMIN_PUBLIC) 제거. 데이터: `VaultApi.tree()` + `AdminApi.aclAll()` + `AdminApi.publicFlags()` mount 로드, users/teams는 컨텍스트.
  - 좌: 트리(기존 트리 렌더 재사용, /api/tree의 VaultNode 형태에 맞춤 — note는 `title`, folder는 `name`).
  - 우(노드 선택 시): ① 직접 ACL 엔트리 테이블 — 주체 타입 셀렉트(user/team/all), 주체 셀렉트(users는 emp+name, teams는 name, all은 고정 `@all`), grant 셀렉트(read/edit/deny), 행 추가/삭제 → "저장" 버튼이 `AdminApi.setAcl(nodeId, entries)` (replace-all — 중복 주체는 클라이언트에서도 차단). ② 상속 엔트리 read-only 목록(`inheritedEntries` — 출처 노드명 표기). ③ public 토글: 현재 `directPublicMode` 표시, 변경 시 `setPublic`/`unsetPublic` (폴더는 public, 노트는 public/exclude 선택 가능), `effectivePublic` 상태 뱃지.
  - 변이 성공 후 aclAll/publicFlags 재로드 + 토스트, 에러는 서버 메시지 토스트.
- [x] **Step 5: 테스트·빌드 + 커밋** — PASS 후 `git commit -m "feat(frontend): 권한 관리 스크린 — 노드 중심 ACL 편집·상속 표시·public 토글"`

### Task 12: 팀·스페이스 신규 스크린

**Files:**
- Create: `frontend/src/admin/screens/Teams.tsx`
- Modify: `frontend/src/admin/AdminApp.tsx` (screenMap에 teams 등록)

- [x] **Step 1: 구현** — 기존 admin 스크린의 CSS 클래스·테이블 패턴 재사용(예: Users.tsx 구조 참고). 데이터: 컨텍스트 teams/users + `AdminApi.spaces()` + `VaultApi.tree()`(최상위 폴더 목록·노드명).
  - 팀 섹션: 팀 목록(이름, 멤버 수) — 생성(이름 입력), 이름 변경, 삭제(스페이스 소유 팀 409 → 서버 메시지 토스트). 팀 선택 시 멤버 목록(emp/name) + 멤버 추가 셀렉트(미소속 active 사용자) + 제외 버튼.
  - 스페이스 섹션: `spaces` 목록(노드명 = tree에서 lookup, 소유 팀명 또는 "공용") — 지정: 최상위 폴더 셀렉트 + 팀 셀렉트(공용 옵션 포함) → `setSpace`, 해제 → `unsetSpace`. 422(최상위 활성 폴더 아님)는 서버 메시지 토스트.
  - 변이 후 컨텍스트 `reload()` + spaces 재로드.
- [x] **Step 2: screenMap 등록 확인** — `#teams` 라우트 동작.
- [x] **Step 3: 테스트·빌드 + 커밋** — PASS 후 `git commit -m "feat(frontend): 팀·스페이스 관리 스크린 신규"`

### Task 13: Dashboard 실데이터 + Security read-only

**Files:**
- Modify: `frontend/src/admin/screens/Dashboard.tsx`, `frontend/src/admin/screens/Security.tsx`
- Modify: `frontend/src/admin/data.ts` (잔존 mock 상수 제거 — 타입 중 스크린이 여전히 쓰는 것은 유지/이동)

- [x] **Step 1: Dashboard** — 통계 카드: 전체 사용자(users.length), 활성(active count), 가입 대기(pending count, 클릭 → `go("pending")`), 팀 수(teams.length). 최근 활동: `AdminApi.audit({ limit: 8 })` rows를 `actLabel`/`actType`으로 표시.
- [x] **Step 2: Security** — 편집 컨트롤 제거, read-only 정책 표(서버 고정값): 비밀번호 최소 8자, 세션 타임아웃 30분, 가입 승인 필수, 해시 PBKDF2-SHA256 120k iter, 로그인 실패 감사 기록. 상단 배너 "보안 정책은 서버에 고정되어 있습니다. 변경은 서버 설정으로." (결정 #12).
- [x] **Step 3: data.ts 정리** — `ADMIN_PENDING/ADMIN_USERS/ADMIN_ROLES/ADMIN_AUDIT/ADMIN_TREE/ADMIN_GRANTS/ADMIN_PUBLIC/ADMIN_SECURITY/ADMIN_ME` 상수 전부 제거. 어떤 스크린도 mock을 import하지 않는지 `grep -rn "from \"./data\"\|from \"../data\"" src/admin` 으로 확인 — 잔존 참조는 타입뿐이어야 함(타입은 data.ts에 남기거나 사용처로 이동).
- [x] **Step 4: 테스트·빌드 + 커밋** — PASS 후 `git commit -m "feat(frontend): 대시보드 실데이터·보안 설정 read-only 전환, mock 데이터 제거"`

### Task 14: 통합 검증 + 문서

**Files:**
- Modify: `backend/README.md`, `CLAUDE.md`, 이 플랜(체크박스), `frontend/README.md`(있으면)

- [x] **Step 1: 전체 테스트** — `cd frontend && pnpm test` 전부 green, `cd backend && ./gradlew test` 전부 green (×2회, `--rerun-tasks`로 순서 무관성).
- [x] **Step 2: 빌드 체인** — `cd frontend && pnpm build` → `cd backend && ./gradlew bootJar`.
- [x] **Step 3: server 모드 jar 스모크** — `WORKNOTE_MODE=server WORKNOTE_ADMIN_PASSWORD=... WORKNOTE_DB=/tmp/smoke4.db java -jar build/libs/worknote-0.1.0.jar` 기동 후 curl로: ① `/login.html` 200 ② `/api/auth/me` 401 ③ 로그인 → 세션 쿠키로 `/api/admin/users` 200 ④ `/api/admin/public` 200 ⑤ 가입 → 승인 → 신규 계정 로그인 ⑥ 로그아웃 후 me 401. local 모드 jar로 ⑦ `/api/auth/me`가 local admin 반환 확인.
- [x] **Step 4: 문서 갱신** — backend/README.md: GET /api/admin/public 행 추가, 이월 목록에서 "프런트 연동" 제거. CLAUDE.md: frontend 줄에 "백엔드 연동 완료(로그인·admin 8스크린)", dev 명령에 admin/login은 백엔드 필요 명시. 이 플랜 체크박스 전부 [x].
- [x] **Step 5: 커밋** — `git commit -m "docs: 프런트 연동(4단계) 완료 반영 — README·CLAUDE.md·플랜"`

---

## Self-Review 결과

- 스펙 커버리지: 이월 항목 "프런트 연동(로그인·admin 페이지 API 배선, 403 처리, me 기반 UI 가드, 세션 만료 401 처리)" — Task 3(로그인), 4(me 가드·401), 7(admin 가드), 8~13(스크린), 4·7(403/401 공통 처리) 전부 매핑. 팀/스페이스 신규 UI Task 12. public 조회 갭은 Task 5로 해소.
- 잔여 이월(이 플랜 범위 아님): 공유 링크(V3), 30일 purge 스케줄러, 이동 노출 변경 경고(§7), /tree findActive 최적화.
- 타입 일관성: Api* 타입은 Task 6에서 단일 정의, 이후 태스크는 전부 그것을 import. ApiError는 http.ts 단일 출처(VaultApi re-export는 호환용).
