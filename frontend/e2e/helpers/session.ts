import { APIRequestContext, expect, request } from "@playwright/test";

/** start-server.sh와 반드시 일치해야 하는 고정 계정/키 */
export const ADMIN = { emp: "admin", password: "e2e-admin-pass-1234" };

/** 포트별 격리 — 병렬 실행 시 상태 파일·DB가 충돌하지 않도록 모든 경로에 포트를 붙인다 */
export const E2E_PORT = Number(process.env.E2E_PORT ?? 8331);

export const ADMIN_STATE = `e2e/.auth/admin-${E2E_PORT}.json`;

/** 비로그인 컨텍스트용 — test.use({ storageState: EMPTY_STATE }) */
export const EMPTY_STATE = { cookies: [], origins: [] } as const;

/**
 * API 시딩용 로그인 컨텍스트.
 * 주의 1: 백엔드 CSRF 방어(OriginValidator)가 변경 메서드에서 Origin(또는 Referer)을
 * 검증하고, 둘 다 없는데 세션 쿠키가 실려 있으면 403 invalid_origin을 낸다.
 * 따라서 모든 요청에 Origin 헤더를 명시한다.
 * 주의 2 (lessons.md #11): 전역 request.newContext()는 프로젝트 use.storageState
 * (공유 admin 쿠키)를 상속한다. 그대로 로그인하면 같은 JSESSIONID로 재로그인 →
 * 백엔드 세션 고정 방어가 id를 회전 → 공유 storageState가 전면 무효가 된다.
 * 그래서 아래에서 빈 storageState를 강제한다. UI 세션으로 시딩하려면 이 함수 대신
 * page.request(+Origin 헤더)를 쓸 것.
 */
export async function apiLogin(
  baseURL: string,
  emp: string = ADMIN.emp,
  password: string = ADMIN.password,
): Promise<APIRequestContext> {
  const ctx = await request.newContext({
    baseURL,
    storageState: { cookies: [], origins: [] },
    extraHTTPHeaders: { Origin: baseURL },
  });
  const res = await ctx.post("/api/auth/login", { data: { emp, password } });
  expect(res.ok(), `로그인 실패: ${emp} → ${res.status()}`).toBeTruthy();
  return ctx;
}

/** 유일한 이름이 필요한 시딩 데이터용 접미사 */
export function uniq(prefix: string): string {
  return `${prefix}-${Date.now().toString(36)}`;
}
