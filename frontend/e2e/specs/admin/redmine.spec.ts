import { test, expect } from "@playwright/test";

/**
 * admin #redmine — 연동 미설정(기본) 상태 렌더만 검증.
 * 저장은 전역 설정 변경이라 수행하지 않는다(연동 활성화는 Redmine 서버 없이는 의미도 없다).
 */

test.describe("admin — Redmine 연동 화면", () => {
  test("연동 미설정 상태: 꺼짐 토글 + 빈 서버 주소 + 안내", async ({ page }) => {
    await page.goto("/admin.html#redmine");
    await expect(page.locator(".asec-head h2", { hasText: "Redmine 연동" })).toBeVisible();
    await expect(page.locator(".panel-head", { hasText: "연동 설정" })).toBeVisible();

    // 기본값: 비활성(꺼짐), 서버 주소 빈 값
    await expect(page.locator('input[type="checkbox"]')).not.toBeChecked();
    await expect(page.getByText("꺼짐")).toBeVisible();
    const url = page.getByPlaceholder("http://redmine.intra");
    await expect(url).toBeVisible();
    await expect(url).toHaveValue("");

    // 설정 로드가 끝나야 저장 버튼이 활성화된다(loaded 게이트)
    await expect(page.locator("button.btn.primary", { hasText: "저장" })).toBeEnabled();
    await expect(page.getByText("사용자는 프로필 > Redmine 연동에서")).toBeVisible();
  });
});
