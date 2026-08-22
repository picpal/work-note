import { test, expect, request } from "@playwright/test";
import { ADMIN, uniq, E2E_PORT } from "../../helpers/session";

/**
 * admin #pii — 탐지 노트 목록 + 본문 뷰어(라인 포커스).
 * PII 스캔은 content PATCH에 동기 실행된다(VaultController.update → PiiService.evaluate) —
 * 별도 스케줄러 대기 없이 시딩 직후 목록에 뜬다. RRN 패턴은 \d{6}[- \t]?[1-8]\d{6}.
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

const NOTE = uniq("피검출노트");
const RRN = "900101-1234567";

test.describe("admin — 개인정보 점검 화면", () => {
  test.beforeAll(async () => {
    const api = await seedLogin();
    const node = await api.post("/api/nodes", { data: { type: "note", name: NOTE, content: "" } });
    expect(node.status()).toBe(201);
    const { id } = await node.json();
    // content PATCH가 동기 스캔을 트리거 — 응답에 pii 평가가 실려 온다
    const patch = await api.patch(`/api/nodes/${encodeURIComponent(id)}`, {
      data: { content: `테스트 주민등록번호 ${RRN} 포함 줄` },
    });
    expect(patch.ok()).toBeTruthy();
    const { pii } = await patch.json();
    expect(pii?.status).toBe("suspected");
    await api.dispose();
  });

  test("탐지 노트가 '전체 개인정보 노트'에 유형·상태와 함께 뜬다", async ({ page }) => {
    await page.goto("/admin.html#pii");
    await expect(page.locator(".asec-head h2", { hasText: "예외 요청 대기" })).toBeVisible();
    await expect(page.locator(".asec-head h2", { hasText: "전체 개인정보 노트" })).toBeVisible();
    await expect(page.locator(".asec-head h2", { hasText: "예외 처리된 노트" })).toBeVisible();

    const row = page.locator("table.atable tbody tr", { hasText: NOTE });
    await expect(row).toHaveCount(1);
    await expect(row).toContainText("주민등록번호"); // 탐지 유형 chip
    await expect(row).toContainText("탐지됨");       // status=suspected
    await expect(row).toContainText("admin");        // 최종 수정자
  });

  test("행 클릭 → 본문 뷰어 — 매치 네비게이션·라인 포커스·하이라이트", async ({ page }) => {
    await page.goto("/admin.html#pii");
    await page.locator("table.atable tbody tr", { hasText: NOTE }).click();

    const viewer = page.locator(".modal.pii-viewer");
    await expect(viewer).toBeVisible();
    await expect(viewer.locator("h3")).toHaveText(NOTE);
    await expect(viewer.locator(".pii-nav")).toContainText("주민등록번호 · 1 / 1");
    await expect(viewer.locator(".pii-line.active mark")).toHaveText(RRN);

    await viewer.locator("button.btn", { hasText: "닫기" }).click();
    await expect(page.locator(".modal-ov")).toHaveCount(0);
  });
});
