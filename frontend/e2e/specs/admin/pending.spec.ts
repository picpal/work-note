import { test, expect, type Browser, type BrowserContext } from "@playwright/test";
import { EMPTY_STATE, uniq } from "../../helpers/session";

/**
 * 가입 승인 대기 (admin.html#pending).
 * 실제 가입 신청(비로그인 컨텍스트) → 승인/반려 → 결과(로그인 가능 여부)까지 잇는 end-to-end.
 * 기본 page 픽스처는 admin 세션, 가입·로그인 확인은 browser.newContext()의 빈 컨텍스트로 수행.
 */

const SIGNUP_PW = "e2e-signup-pass-1234";

/**
 * 비로그인 컨텍스트 — 주의: @playwright/test의 browser.newContext()는 프로젝트 use 옵션
 * (admin storageState 포함)을 상속하므로 EMPTY_STATE를 명시해야 진짜 비로그인이 된다.
 */
function emptyContext(browser: Browser): Promise<BrowserContext> {
  return browser.newContext({ storageState: EMPTY_STATE as { cookies: []; origins: [] } });
}

/** 비로그인 컨텍스트에서 login.html 가입 폼으로 신청을 넣는다. */
async function signupViaUi(browser: Browser, baseURL: string, emp: string, name: string) {
  const ctx = await emptyContext(browser);
  const p = await ctx.newPage();
  await p.goto(baseURL + "/login.html");
  await p.locator(".auth-link", { hasText: "가입 신청" }).click();
  // 가입 모드에선 이름·이메일이 중간 삽입돼 인덱스가 변한다 — placeholder/type으로 선택 (lessons #6)
  await p.getByPlaceholder("예: S2026-0142").fill(emp);
  await p.getByPlaceholder("이름을 입력하세요").fill(name);
  const pw = p.locator('.auth-input[type="password"]');
  await pw.nth(0).fill(SIGNUP_PW);
  await pw.nth(1).fill(SIGNUP_PW);
  await p.locator("button.auth-btn").click();
  await expect(p.locator(".auth-ok h2")).toHaveText("가입 신청 완료");
  await ctx.close();
}

test.describe("가입 승인 대기 화면", () => {
  test("가입 신청 → 목록 표시 → 승인 → 해당 계정으로 로그인된다", async ({ page, browser, baseURL }) => {
    const emp = uniq("e2e-appr");
    await signupViaUi(browser, baseURL!, emp, "승인 테스트");

    await page.goto("/admin.html#pending");
    await expect(page.locator(".atopbar h1")).toHaveText("가입 승인 대기");

    const row = page.locator("table.atable tbody tr").filter({ hasText: emp });
    await expect(row).toHaveCount(1);
    await expect(row).toContainText("승인 테스트");
    await expect(row.locator(".badge")).toHaveText(/대기/);

    await row.getByRole("button", { name: "승인" }).click();
    const modal = page.locator(".modal-ov .modal");
    await expect(modal.locator("h3")).toHaveText("가입 승인");
    await expect(modal).toContainText(emp);
    await modal.locator(".modal-foot button", { hasText: "승인" }).click();

    // 승인되면 대기 목록에서 사라진다
    await expect(row).toHaveCount(0);

    // 승인된 계정(기본 역할: 방문자)으로 로그인 성공
    const ctx = await emptyContext(browser);
    const p = await ctx.newPage();
    await p.goto(baseURL + "/login.html");
    await p.getByPlaceholder("예: S2026-0142").fill(emp);
    await p.locator('.auth-input[type="password"]').fill(SIGNUP_PW);
    await p.locator("button.auth-btn").click();
    await p.waitForURL("**/index.html");
    await expect(p.locator(".topbar-me")).toBeVisible();
    await ctx.close();
  });

  test("가입 신청 → 반려 → 대기 목록에서 사라진다", async ({ page, browser, baseURL }) => {
    const emp = uniq("e2e-rej");
    await signupViaUi(browser, baseURL!, emp, "반려 테스트");

    await page.goto("/admin.html#pending");
    const row = page.locator("table.atable tbody tr").filter({ hasText: emp });
    await expect(row).toHaveCount(1);

    await row.getByRole("button", { name: "반려" }).click();
    const modal = page.locator(".modal-ov .modal");
    await expect(modal.locator("h3")).toHaveText("가입 반려");
    await modal.locator(".modal-foot button.danger", { hasText: "반려" }).click();

    await expect(row).toHaveCount(0);
  });
});
