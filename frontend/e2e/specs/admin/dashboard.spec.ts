import { test, expect } from "@playwright/test";

/**
 * 관리자 대시보드 (admin.html#dashboard).
 * 통계 카드·패널 렌더와 "전체 보기" 내비게이션만 다룬다.
 * 다른 spec이 사용자/팀을 만들 수 있으므로 수치는 존재 여부(≥1)만 확인한다.
 */

test.describe("관리자 대시보드", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/admin.html#dashboard");
  });

  test("셸 렌더: 타이틀·좌측 내비·활성 항목", async ({ page }) => {
    await expect(page.locator(".atopbar h1")).toHaveText("대시보드");
    await expect(page.locator(".atopbar .sub")).toHaveText("워크스페이스 운영 현황");
    await expect(page.locator(".anav-brand .name")).toHaveText("WorkNote");
    await expect(page.locator(".anav-item.active")).toHaveText("대시보드");
    await expect(page.locator(".anav-back")).toContainText("노트로 돌아가기");
  });

  test("통계 카드 4종이 수치와 함께 보인다", async ({ page }) => {
    const grid = page.locator(".stat-grid");
    for (const label of ["전체 사용자", "활성 계정", "가입 대기", "팀"]) {
      const card = grid.locator(".stat").filter({ has: page.locator(".label", { hasText: label }) });
      await expect(card).toHaveCount(1);
      await expect(card.locator(".num")).toHaveText(/^\d+$/);
    }
    // admin 계정은 항상 존재 — 전체 사용자는 1 이상
    const total = grid.locator(".stat").filter({ has: page.locator(".label", { hasText: "전체 사용자" }) });
    await expect(total.locator(".num")).toHaveText(/^[1-9]\d*$/);
  });

  test("승인 대기·최근 활동 패널이 렌더된다", async ({ page }) => {
    const pendingPanel = page.locator(".panel").filter({ has: page.locator(".panel-head", { hasText: "승인 대기" }) });
    const recentPanel = page.locator(".panel").filter({ has: page.locator(".panel-head", { hasText: "최근 활동" }) });
    await expect(pendingPanel).toBeVisible();
    await expect(recentPanel).toBeVisible();
    // 최근 활동은 로드 완료 후 빈 상태 또는 로그 리스트 중 하나를 보인다
    await expect(recentPanel.locator(".panel-body")).not.toContainText("불러오는 중");
  });

  test("승인 대기 '전체 보기'는 가입 승인 화면으로 이동한다", async ({ page }) => {
    const pendingPanel = page.locator(".panel").filter({ has: page.locator(".panel-head", { hasText: "승인 대기" }) });
    await pendingPanel.getByRole("button", { name: "전체 보기" }).click();
    await expect(page).toHaveURL(/#pending$/);
    await expect(page.locator(".atopbar h1")).toHaveText("가입 승인 대기");
  });
});
