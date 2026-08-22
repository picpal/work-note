import { test, expect, request } from "@playwright/test";
import { ADMIN, uniq, E2E_PORT } from "../../helpers/session";

/**
 * admin #shares — 활성 공유 링크 일괄 조회·취소.
 * 노트+링크는 API로 시딩(생성 UI는 share.spec.ts가 커버). 행 식별은 uniq 노트명으로만 —
 * 같은 실행의 다른 spec이 만든 링크가 섞여 있어도 깨지지 않는다.
 */

const BASE = `http://localhost:${E2E_PORT}`;

/** helpers.apiLogin 대체 — 빈 storageState 명시. 상속된 setup 쿠키로 로그인하면
 *  서버 changeSessionId()가 setup 세션을 무효화한다 (share.spec.ts 주석 참조). */
async function seedLogin() {
  const ctx = await request.newContext({
    baseURL: BASE,
    extraHTTPHeaders: { Origin: BASE },
    storageState: { cookies: [], origins: [] },
  });
  const res = await ctx.post("/api/auth/login", { data: { emp: ADMIN.emp, password: ADMIN.password } });
  expect(res.ok(), `시딩 로그인 실패: ${res.status()}`).toBeTruthy();
  return ctx;
}

const NOTE = uniq("관리자공유노트");

let token = "";

test.describe("admin — 공유 링크 화면", () => {
  test.beforeAll(async () => {
    const api = await seedLogin();
    const node = await api.post("/api/nodes", { data: { type: "note", name: NOTE, content: "관리자 공유 e2e 본문" } });
    expect(node.status()).toBe(201);
    const { id } = await node.json();
    const share = await api.post(`/api/nodes/${encodeURIComponent(id)}/share`, { data: {} });
    expect(share.status()).toBe(201);
    token = (await share.json()).token;
    await api.dispose();
  });

  test("목록: 시딩한 링크가 노트·생성자·열람·대상과 함께 보인다", async ({ page }) => {
    await page.goto("/admin.html#shares");
    const row = page.locator("table.atable tbody tr", { hasText: NOTE });
    await expect(row).toHaveCount(1);
    await expect(row).toContainText("admin"); // 생성자
    await expect(row).toContainText("0 / ∞"); // 열람수 / 최대(무제한)
    await expect(row).toContainText("전 직원"); // pin 미지정
  });

  test("취소(revoke) → 목록에서 사라지고 share 페이지는 무효 카드", async ({ page }) => {
    await page.goto("/admin.html#shares");
    const row = page.locator("table.atable tbody tr", { hasText: NOTE });
    await expect(row).toHaveCount(1);

    page.on("dialog", (d) => void d.accept()); // confirm() 수락
    await row.locator("button.lact", { hasText: "취소" }).click();
    await expect(page.locator(".toast", { hasText: "공유 링크를 취소했습니다" })).toBeVisible();
    await expect(row).toHaveCount(0);

    await page.goto(`/share.html?token=${encodeURIComponent(token)}`);
    await expect(page.locator(".share-state h2")).toHaveText("열 수 없는 링크입니다");
  });
});
