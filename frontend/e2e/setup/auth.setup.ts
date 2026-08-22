import { test as setup, expect } from "@playwright/test";
import { ADMIN, ADMIN_STATE } from "../helpers/session";

/**
 * admin으로 UI 로그인 → 세션 쿠키(JSESSIONID)를 storageState로 저장.
 * chromium 프로젝트의 모든 spec이 이 상태를 물고 시작한다.
 */
setup("admin 로그인 세션 저장", async ({ page }) => {
  await page.goto("/login.html");
  await page.getByPlaceholder("예: S2026-0142").fill(ADMIN.emp);
  await page.locator('.auth-input[type="password"]').fill(ADMIN.password);
  await page.getByRole("button", { name: "로그인" }).click();

  // 성공 시 index.html로 이동 — 프로필 버튼(.topbar-me)이 로그인 상태의 증거
  await page.waitForURL("**/index.html");
  await expect(page.locator(".topbar-me")).toBeVisible();

  await page.context().storageState({ path: ADMIN_STATE });
});
