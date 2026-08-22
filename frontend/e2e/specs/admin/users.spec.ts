import { test, expect } from "@playwright/test";
import { ADMIN, uniq } from "../../helpers/session";

/**
 * 사용자 관리 (admin.html#users).
 * 목록·사용자 추가·검색 필터의 필수 경로. 생성 사번은 uniq — 병렬/반복 실행 충돌 방지.
 */

test.describe("사용자 관리 화면", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/admin.html#users");
    await expect(page.locator(".atopbar h1")).toHaveText("사용자 관리");
  });

  test("목록에 admin 계정이 활성·관리자 역할로 보인다", async ({ page }) => {
    const row = page.locator("table.atable tbody tr").filter({ hasText: ADMIN.emp });
    await expect(row).toHaveCount(1);
    await expect(row.locator(".badge.role")).toHaveText("관리자");
    await expect(row.locator(".badge:not(.role)")).toHaveText(/활성/);
    // 행 액션 — 비활성화는 본인 계정이라 잠긴다
    await expect(row.locator("button.lact", { hasText: "비활성화" })).toBeDisabled();
  });

  test("사용자 추가 → 목록 반영", async ({ page }) => {
    const emp = uniq("e2e-u");
    await page.getByRole("button", { name: "사용자 추가" }).click();

    const modal = page.locator(".modal-ov .modal");
    await expect(modal.locator("h3")).toHaveText("사용자 추가");
    await modal.getByPlaceholder("예: 24011").fill(emp);
    // 이름 필드는 placeholder가 없다 — tinput 순서(사번·이름·이메일·비밀번호)로 선택
    await modal.locator("input.tinput").nth(1).fill("추가 테스트");
    await modal.locator('input[type="password"]').fill("e2e-pass-123456"); // 10자 이상 정책
    await modal.locator(".modal-foot button", { hasText: "생성" }).click();

    await expect(modal).toHaveCount(0);
    const row = page.locator("table.atable tbody tr").filter({ hasText: emp });
    await expect(row).toHaveCount(1);
    await expect(row).toContainText("활성");
  });

  test("검색 필터로 특정 사용자만 남는다", async ({ page }) => {
    const search = page.locator(".atoolbar .afield input");
    await search.fill(ADMIN.emp);
    // admin 사번 검색 — admin 행만 남는다 (uniq 사번들은 'admin'을 포함하지 않음)
    await expect(page.locator("table.atable tbody tr")).toHaveCount(1);
    await expect(page.locator("table.atable tbody tr").first()).toContainText(ADMIN.emp);

    // 일치하는 사용자가 없으면 빈 상태 안내
    await search.fill("존재하지-않는-사번-zzz");
    await expect(page.locator(".empty h3")).toHaveText("조건에 맞는 사용자가 없습니다");
  });
});
