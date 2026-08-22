import { test, expect, request } from "@playwright/test";
import { ADMIN, E2E_PORT } from "../../helpers/session";

/**
 * admin #uploads — 업로드 정책(허용 확장자 chip + 파일당 용량) 편집·유지 왕복.
 * 전역 설정(app_setting)을 건드리므로: 원본 정책을 beforeAll에서 API로 캡처하고
 * afterAll에서 무조건 원복한다 — 중간 실패해도 다른 spec(첨부 업로드)에 누출되지 않도록.
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

let orig: { allowedExt: string[]; maxBytes: number } | null = null;

test.describe("admin — 업로드 정책 화면", () => {
  test.beforeAll(async () => {
    const api = await seedLogin();
    const res = await api.get("/api/admin/settings/upload");
    expect(res.ok()).toBeTruthy();
    orig = await res.json();
    await api.dispose();
  });

  test.afterAll(async () => {
    if (!orig) return;
    const api = await seedLogin();
    await api.put("/api/admin/settings/upload", { data: orig });
    await api.dispose();
  });

  test("정책 폼 렌더: 기본 확장자 chip + 용량 입력값", async ({ page }) => {
    await page.goto("/admin.html#uploads");
    await expect(page.locator(".asec-head h2", { hasText: "업로드 정책" })).toBeVisible();
    await expect(page.locator(".chip", { hasText: ".png" })).toBeVisible(); // 기본 정책 로드 증거
    const mb = Math.max(1, Math.round(orig!.maxBytes / 1024 / 1024));
    await expect(page.locator('input[type="number"]')).toHaveValue(String(mb));
    await expect(page.locator("button.btn.primary", { hasText: "저장" })).toBeEnabled();
  });

  test("확장자 chip 추가/삭제 UI (저장 없이 로컬만)", async ({ page }) => {
    await page.goto("/admin.html#uploads");
    const draft = page.getByPlaceholder("예: png (Enter로 추가)");
    await draft.fill("e2etmp");
    await draft.press("Enter");
    const chip = page.locator(".chip", { hasText: ".e2etmp" });
    await expect(chip).toBeVisible();
    await chip.locator('button[title="삭제"]').click();
    await expect(chip).toHaveCount(0);
  });

  test("용량 변경 저장 → 재진입 유지 → 원래 값으로 원복", async ({ page }) => {
    await page.goto("/admin.html#uploads");
    await expect(page.locator(".chip", { hasText: ".png" })).toBeVisible(); // 로드 완료 대기
    const num = page.locator('input[type="number"]');
    const before = Number(await num.inputValue());
    const save = page.locator("button.btn.primary", { hasText: "저장" });

    await num.fill(String(before + 3));
    await save.click();
    await expect(page.locator(".toast", { hasText: "저장했습니다" })).toBeVisible();

    await page.reload(); // 재진입 — 서버에서 다시 읽는다
    await expect(num).toHaveValue(String(before + 3));

    await num.fill(String(before));
    await save.click();
    await expect(page.locator(".toast", { hasText: "저장했습니다" })).toBeVisible();
    await page.reload();
    await expect(num).toHaveValue(String(before));
  });
});
