import { test, expect } from "@playwright/test";
import { ADMIN, EMPTY_STATE } from "../helpers/session";

/**
 * 로그인 화면 (login.html) — 파일럿 spec.
 * 로그인/가입 신청/오류 표시/리다이렉트/로그아웃의 필수 경로만 다룬다.
 */

test.describe("로그인 화면 — 비로그인", () => {
  test.use({ storageState: EMPTY_STATE });

  test.beforeEach(async ({ page }) => {
    await page.goto("/login.html");
  });

  test("초기 렌더: 로그인 폼 구성이 보인다", async ({ page }) => {
    await expect(page.locator(".auth-card")).toBeVisible();
    await expect(page.locator("h1.auth-title")).toHaveText("로그인");
    await expect(page.getByPlaceholder("예: S2026-0142")).toBeVisible();
    await expect(page.locator('.auth-input[type="password"]')).toBeVisible();
    // 가입 전환 링크 + 승인 안내 문구
    await expect(page.locator(".auth-link", { hasText: "가입 신청" })).toBeVisible();
    await expect(page.locator(".auth-note")).toContainText("관리자 승인 후");
  });

  test("빈 입력으로 제출하면 안내 오류가 뜬다", async ({ page }) => {
    await page.locator("button.auth-btn").click();
    await expect(page.locator(".auth-err")).toHaveText("사번과 비밀번호를 입력하세요");
  });

  test("잘못된 비밀번호는 오류를 표시하고 화면에 머문다", async ({ page }) => {
    await page.getByPlaceholder("예: S2026-0142").fill(ADMIN.emp);
    await page.locator('.auth-input[type="password"]').fill("wrong-password-123");
    await page.locator("button.auth-btn").click();
    await expect(page.locator(".auth-err")).not.toBeEmpty();
    await expect(page).toHaveURL(/login\.html/);
  });

  test("정상 로그인하면 에디터로 진입한다", async ({ page }) => {
    await page.getByPlaceholder("예: S2026-0142").fill(ADMIN.emp);
    await page.locator('.auth-input[type="password"]').fill(ADMIN.password);
    await page.locator("button.auth-btn").click();
    await page.waitForURL("**/index.html");
    await expect(page.locator(".topbar-me")).toBeVisible();
  });

  test("가입 신청 → 완료 안내 → 로그인 폼 복귀", async ({ page }) => {
    await page.locator(".auth-link", { hasText: "가입 신청" }).click();
    await expect(page.locator("h1.auth-title")).toHaveText("가입 신청");

    // 가입 모드에선 이름·이메일이 중간에 삽입돼 인덱스가 변하므로 placeholder/type으로 잡는다
    await page.getByPlaceholder("예: S2026-0142").fill("S2026-9001");
    await page.getByPlaceholder("이름을 입력하세요").fill("테스트 사용자");
    await page.getByPlaceholder("name@corp.local").fill("t9001@corp.local");
    const pwInputs = page.locator('.auth-input[type="password"]');
    await pwInputs.nth(0).fill("signup-pass-1234");
    await pwInputs.nth(1).fill("signup-pass-1234");
    await page.locator("button.auth-btn").click();

    await expect(page.locator(".auth-ok h2")).toHaveText("가입 신청 완료");
    await expect(page.locator(".auth-ok")).toContainText("관리자 승인 후 계정이 활성화됩니다");

    await page.locator("button.auth-btn", { hasText: "로그인으로 돌아가기" }).click();
    await expect(page.locator("h1.auth-title")).toHaveText("로그인");
  });
});

test.describe("로그인 화면 — 로그인 상태", () => {
  // 프로젝트 기본 storageState(admin) 사용

  test("이미 로그인된 세션이면 login.html이 index로 넘긴다", async ({ page }) => {
    await page.goto("/login.html");
    await page.waitForURL("**/index.html");
    await expect(page.locator(".topbar-me")).toBeVisible();
  });

});

// 로그아웃은 세션을 서버측에서 무효화한다 — 공유 storageState(admin) 세션으로 수행하면
// 이후의 모든 spec이 401로 전멸한다 (lessons.md #17). 전용 컨텍스트에서 자체 로그인 후 수행.
test.describe("로그아웃", () => {
  test.use({ storageState: EMPTY_STATE });

  test("로그아웃하면 login.html로 복귀한다", async ({ page }) => {
    await page.goto("/login.html");
    await page.getByPlaceholder("예: S2026-0142").fill(ADMIN.emp);
    await page.locator('.auth-input[type="password"]').fill(ADMIN.password);
    await page.locator("button.auth-btn").click();
    await page.waitForURL("**/index.html");
    await expect(page.locator(".topbar-me")).toBeVisible();

    await page.locator('.sb-fbtn[title="로그아웃"]').click();
    await page.waitForURL("**/login.html");
    await expect(page.locator("h1.auth-title")).toHaveText("로그인");
  });
});
