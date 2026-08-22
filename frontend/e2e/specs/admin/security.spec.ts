import { test, expect } from "@playwright/test";

/**
 * 보안 설정 (admin.html#security) — 읽기 전용 화면.
 * 서버 고정 정책 값이 정확히 표기되는지 확인한다.
 */

test.describe("보안 설정 화면", () => {
  test("정책 패널 2종과 고정 값이 렌더된다", async ({ page }) => {
    await page.goto("/admin.html#security");
    await expect(page.locator(".atopbar h1")).toHaveText("보안 설정");
    await expect(page.locator(".asec-head h2")).toHaveText("보안 정책");
    await expect(page.locator(".changebar")).toContainText("보안 정책은 서버에 고정되어 있습니다");

    const pwPanel = page.locator(".panel").filter({ has: page.locator(".panel-head", { hasText: "비밀번호 정책" }) });
    await expect(pwPanel).toBeVisible();
    await expect(pwPanel.locator(".frow").filter({ hasText: "최소 길이" })).toContainText("10자 (최대 128자)");
    await expect(pwPanel.locator(".frow").filter({ hasText: "해시 저장" })).toContainText("PBKDF2-SHA256");

    const sessPanel = page.locator(".panel").filter({ has: page.locator(".panel-head", { hasText: "접근 · 세션" }) });
    await expect(sessPanel).toBeVisible();
    await expect(sessPanel.locator(".frow").filter({ hasText: "세션 타임아웃" })).toContainText("30분");
    await expect(sessPanel.locator(".frow").filter({ hasText: "신규 가입" })).toContainText("관리자 승인 필수");
    await expect(sessPanel.locator(".frow").filter({ hasText: "감사 기록" })).toContainText("로그인 실패 · 전체 변이 기록");
  });
});
