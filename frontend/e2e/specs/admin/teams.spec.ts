import { test, expect } from "@playwright/test";
import { uniq } from "../../helpers/session";

/**
 * 팀·스페이스 (admin.html#teams).
 * 팀 생성 → 목록 반영 → 멤버 관리 패널(우측)까지. "멤버 관리"는 모달이 아니라 우측 패널이다.
 * 스페이스 섹션은 렌더·컨트롤 노출만 확인한다(폴더 시딩 없이는 지정 후보가 없을 수 있음).
 */

test.describe("팀·스페이스 화면", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/admin.html#teams");
    await expect(page.locator(".atopbar h1")).toHaveText("팀·스페이스");
  });

  test("팀 생성 → 목록 반영 → 멤버 관리 패널이 열린다", async ({ page }) => {
    const name = uniq("e2e팀");
    await page.getByRole("button", { name: "팀 생성" }).click();

    const modal = page.locator(".modal-ov .modal");
    await expect(modal.locator("h3")).toHaveText("팀 생성");
    await modal.getByPlaceholder("예: 결제팀").fill(name);
    await modal.locator(".modal-foot button", { hasText: "생성" }).click();
    await expect(modal).toHaveCount(0);

    // 목록 반영 — 새 팀은 멤버 0명
    const row = page.locator("table.atable tbody tr").filter({ hasText: name });
    await expect(row).toHaveCount(1);
    await expect(row).toContainText("0명");

    // 멤버 관리 — 우측 패널이 열리고 멤버 검색 입력이 활성화된다
    await row.getByRole("button", { name: "멤버 관리" }).click();
    const panel = page.locator(".panel").filter({ has: page.locator(".panel-head", { hasText: name }) });
    await expect(panel).toBeVisible();
    await expect(panel.locator(".panel-head")).toContainText("멤버");
    await expect(panel.locator(".panel-body")).toContainText("멤버가 없습니다");
    // admin(활성 비멤버)이 있으므로 검색 입력은 활성 상태
    await expect(panel.locator(".member-search-input")).toBeEnabled();
    // 선택 전에는 추가 버튼이 잠겨 있다
    await expect(panel.getByRole("button", { name: "추가" })).toBeDisabled();
  });

  test("스페이스 섹션이 렌더되고 지정 컨트롤이 보인다", async ({ page }) => {
    const head = page.locator(".asec-head").filter({ hasText: "스페이스" });
    await expect(head).toContainText("최상위 팀 폴더");
    // 로드가 끝나면 스켈레톤 대신 실제 테이블(폴더·소유·작업)이 뜬다
    await expect(page.locator("table.atable th", { hasText: "폴더" })).toBeVisible();
    await expect(page.getByRole("button", { name: "스페이스 지정" })).toBeVisible();
    // 폴더를 고르기 전에는 지정 버튼이 잠겨 있다
    await expect(page.getByRole("button", { name: "스페이스 지정" })).toBeDisabled();
  });
});
