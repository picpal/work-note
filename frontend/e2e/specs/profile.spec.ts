import { test, expect } from "@playwright/test";
import { ADMIN, uniq } from "../helpers/session";

/**
 * 프로필 모달 (index.html .topbar-me) — 렌더 구성·이름 변경 저장·2FA 배너 진입점.
 * 비밀번호 변경은 하지 않는다 — 공유 storageState(admin 세션)를 오염시킨다.
 * admin은 2FA 유예 기간이라 상단 권고 배너(.totp-nudge-banner)가 항상 떠 있다.
 */

test.describe("프로필 모달", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/index.html");
    await expect(page.locator(".topbar-me")).toBeVisible();
  });

  test("탑바 프로필 클릭 → 프로필 정보·비밀번호 변경·보안 섹션이 렌더된다", async ({ page }) => {
    await page.locator(".topbar-me").click();
    const card = page.locator(".pf-card");
    await expect(card).toBeVisible();
    await expect(card.locator(".pf-emp")).toHaveText(ADMIN.emp);
    await expect(card.locator(".pf-sec-label", { hasText: "프로필 정보" })).toBeVisible();
    await expect(card.locator(".pf-sec-label", { hasText: "비밀번호 변경" })).toBeVisible();
    // 2FA 보안 섹션 — http 모드 + admin totp 정보가 있어야 렌더 (SecurityTab idle 라벨은 "2단계 인증 (TOTP)")
    await expect(card.locator(".pf-sec-label", { hasText: "2단계 인증 (TOTP)" })).toBeVisible();
    await expect(card.getByPlaceholder("이름을 입력하세요")).toBeVisible();
    // 사번은 수정 불가
    await expect(card.locator("input.pf-input").first()).toBeDisabled();

    await page.keyboard.press("Escape");
    await expect(card).toHaveCount(0);
  });

  test("이름 변경 저장 → 저장됨 표시와 탑바 라벨에 반영된다", async ({ page }) => {
    const name = uniq("이름");
    await page.locator(".topbar-me").click();
    const card = page.locator(".pf-card");
    await card.getByPlaceholder("이름을 입력하세요").fill(name);

    const saved = page.waitForResponse(
      (r) => r.url().includes("/api/auth/update-profile") && r.ok(),
    );
    await card.locator(".pf-btn", { hasText: "정보 저장" }).click();
    await saved;
    await expect(page.locator(".toast", { hasText: "프로필을 저장했습니다" })).toBeVisible();
    await expect(card.locator(".pf-btn", { hasText: "저장됨" })).toBeVisible();

    await page.keyboard.press("Escape");
    await expect(card).toHaveCount(0);
    await expect(page.locator(".topbar-me .tm-emp")).toHaveText(`${name} (${ADMIN.emp})`);
  });

  test("2FA 유예 배너의 '지금 등록' → 프로필 모달 보안 섹션으로 진입한다", async ({ page }) => {
    const banner = page.locator(".totp-nudge-banner");
    await expect(banner).toBeVisible();
    await expect(banner).toContainText("2FA(TOTP) 등록을 완료하세요");

    await banner.locator("button", { hasText: "지금 등록" }).click();
    const card = page.locator(".pf-card");
    await expect(card).toBeVisible();
    await expect(card.locator(".pf-sec-label", { hasText: "2단계 인증 (TOTP)" })).toBeVisible();
  });
});
