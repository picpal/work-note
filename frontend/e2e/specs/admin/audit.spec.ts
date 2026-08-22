import { test, expect } from "@playwright/test";

/**
 * admin #audit — 감사 로그 행 존재·필터 UI·월간 리포트 모달.
 * 행 시딩 불필요: auth.setup의 UI 로그인이 login.success("로그인") 이벤트를 이미 남겼다.
 * 리포트는 생성(다운로드/인쇄 팝업)까지 가지 않고 모달 열림·구성·닫힘만 검증한다.
 */

test.describe("admin — 감사 로그 화면", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/admin.html#audit");
  });

  test("렌더: 필터 툴바 + 로그인 이벤트 행", async ({ page }) => {
    await expect(page.locator(".asec-head h2", { hasText: "감사 로그" })).toBeVisible();
    // 필터 UI — 행위자 검색, 행위 select, 기간 date 2개
    await expect(page.getByPlaceholder("행위자(사번) — Enter로 적용")).toBeVisible();
    await expect(page.locator(".atoolbar select.aselect")).toBeVisible();
    await expect(page.locator('.atoolbar input[type="date"]')).toHaveCount(2);
    // setup의 UI 로그인이 남긴 행 — 행위자 admin, 행위 "로그인"
    const loginRow = page.locator("table.atable tbody tr", { hasText: "로그인" }).filter({ hasText: "admin" });
    await expect(loginRow.first()).toBeVisible();
  });

  test("행위자 필터: 없는 사번 → 빈 상태, admin → 행 복귀", async ({ page }) => {
    const who = page.getByPlaceholder("행위자(사번) — Enter로 적용");
    await who.fill("no-such-emp-e2e");
    await who.press("Enter");
    await expect(page.locator(".empty", { hasText: "조건에 맞는 로그가 없습니다" })).toBeVisible();
    await who.fill("admin");
    await who.press("Enter");
    await expect(page.locator("table.atable tbody tr").first()).toBeVisible();
  });

  test("감사 리포트 모달 열기 → 년/월 선택 UI → 닫기", async ({ page }) => {
    await page.locator("button.btn", { hasText: "감사 리포트" }).click();
    const modal = page.locator(".modal-ov .modal");
    await expect(modal).toBeVisible();
    await expect(modal.locator("h3")).toHaveText("월간 감사 리포트");
    await expect(modal.locator("select.aselect")).toHaveCount(2); // 년 + 월
    await expect(modal.locator("button.btn", { hasText: "Markdown" })).toBeVisible();
    await expect(modal.locator("button.btn", { hasText: "PDF" })).toBeVisible();
    await modal.locator("button.btn", { hasText: "취소" }).click();
    await expect(page.locator(".modal-ov")).toHaveCount(0);
  });
});
