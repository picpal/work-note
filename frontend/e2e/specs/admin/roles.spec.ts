import { test, expect } from "@playwright/test";
import { uniq } from "../../helpers/session";

/**
 * 역할 관리 (admin.html#roles).
 * 시스템 역할 3종(관리자·일반사용자·방문자)은 편집 불가 — 읽기 전용 "보기" 모달이 디자인 결정.
 * 커스텀 역할만 "편집"이 된다. 커스텀 역할 생성 1건 포함.
 */

test.describe("역할 관리 화면", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/admin.html#roles");
    await expect(page.locator(".atopbar h1")).toHaveText("역할 관리");
  });

  test("시스템 역할 3종이 시스템 배지와 함께 보인다", async ({ page }) => {
    for (const name of ["관리자", "일반사용자", "방문자"]) {
      const card = page.locator(".role-card").filter({ has: page.locator(".rc-name", { hasText: name }) });
      await expect(card).toHaveCount(1);
      await expect(card.locator(".badge", { hasText: "시스템" })).toBeVisible();
      // 시스템 역할: 액션은 "보기"(편집 아님), 삭제는 잠김
      await expect(card.getByRole("button", { name: "보기" })).toBeEnabled();
      await expect(card.getByRole("button", { name: "삭제" })).toBeDisabled();
    }
  });

  test("시스템 역할 '보기'는 읽기 전용 모달이다", async ({ page }) => {
    const card = page.locator(".role-card").filter({ has: page.locator(".rc-name", { hasText: "관리자" }) });
    await card.getByRole("button", { name: "보기" }).click();

    const modal = page.locator(".modal-ov .modal");
    await expect(modal.locator("h3")).toHaveText("역할 보기");
    await expect(modal).toContainText("시스템 역할은 변경할 수 없습니다");
    // 역할 ID·이름·권한 체크박스 전부 비활성(읽기 전용)
    await expect(modal.locator("input.tinput.mono")).toHaveValue("admin");
    await expect(modal.locator("input.tinput.mono")).toBeDisabled();
    const checks = modal.locator('input[type="checkbox"]');
    const n = await checks.count();
    expect(n).toBeGreaterThan(0);
    for (let i = 0; i < n; i++) await expect(checks.nth(i)).toBeDisabled();

    await modal.locator(".modal-foot button", { hasText: "닫기" }).click();
    await expect(modal).toHaveCount(0);
  });

  test("커스텀 역할 생성 → 카드 반영 → 편집 모드 제공", async ({ page }) => {
    const id = uniq("e2e-role"); // 소문자·숫자·하이픈 — ID 규칙 충족
    await page.getByRole("button", { name: "역할 추가" }).click();

    const modal = page.locator(".modal-ov .modal");
    await expect(modal.locator("h3")).toHaveText("역할 추가");
    await modal.getByPlaceholder("예: reviewer").fill(id);
    await modal.getByPlaceholder("예: 검토자").fill("E2E 검토자");
    // 리소스 읽기 권한 하나 체크
    await modal.locator("label", { hasText: "res.read" }).locator('input[type="checkbox"]').check();
    await modal.locator(".modal-foot button", { hasText: "추가" }).click();
    await expect(modal).toHaveCount(0);

    const card = page.locator(".role-card").filter({ has: page.locator(".rc-name", { hasText: "E2E 검토자" }) });
    await expect(card).toHaveCount(1);
    await expect(card.locator(".badge", { hasText: "시스템" })).toHaveCount(0);
    await expect(card).toContainText("리소스 권한 1개");
    // 커스텀 역할은 편집·삭제 모두 열려 있다
    await expect(card.getByRole("button", { name: "편집" })).toBeEnabled();
    await expect(card.getByRole("button", { name: "삭제" })).toBeEnabled();
  });
});
