import { test, expect, request } from "@playwright/test";
import { ADMIN, uniq, E2E_PORT } from "../../helpers/session";

/**
 * admin #permissions — 노드 트리 + 직접 ACL 편집(부여·해제)·저장 왕복.
 * 대상 노드는 uniq 폴더를 API로 시딩 — 시드 부트스트랩 상태(auth.setup의 부분 업로드 가능)와 무관하게 결정적.
 * 관리자 화면 트리(TreeRow)는 로컬 open 기본 true라 시딩 폴더가 바로 보인다.
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

const FOLDER = uniq("권한폴더");

test.describe("admin — 권한 관리 화면", () => {
  test.beforeAll(async () => {
    const api = await seedLogin();
    const res = await api.post("/api/nodes", { data: { type: "folder", name: FOLDER } });
    expect(res.status()).toBe(201);
    await api.dispose();
  });

  test("초기 렌더: 트리 + 노드 선택 안내", async ({ page }) => {
    await page.goto("/admin.html#permissions");
    await expect(page.locator(".asec-head h2", { hasText: "권한 관리" })).toBeVisible();
    await expect(page.locator(".panel-head", { hasText: "노드 선택" })).toBeVisible();
    await expect(page.locator(".ptree-row", { hasText: FOLDER })).toBeVisible();
    await expect(page.locator(".empty", { hasText: "노드를 선택하세요" })).toBeVisible();
  });

  test("노드 선택 → ACL 부여 저장 → 재진입 유지 → 해제", async ({ page }) => {
    await page.goto("/admin.html#permissions");
    await page.locator(".ptree-row", { hasText: FOLDER }).click();
    await expect(page.locator(".panel-head", { hasText: "의 접근 제어" })).toContainText(FOLDER);
    await expect(page.getByText("직접 엔트리가 없습니다")).toBeVisible();

    // 행 추가 → 주체(admin) 선택 → 저장. 주체 id는 option 라벨(emp)에서 역산
    await page.locator("button.btn", { hasText: "행 추가" }).click();
    const draftRow = page.locator("table.atable tbody tr").first();
    const subject = draftRow.locator("select").nth(1);
    const adminId = await subject.locator("option", { hasText: "admin" }).first().getAttribute("value");
    expect(adminId).toBeTruthy();
    await subject.selectOption(adminId!);
    await page.locator("button.btn.primary", { hasText: "저장" }).click();
    await expect(page.locator(".toast", { hasText: "ACL을 저장했습니다" }).last()).toBeVisible();

    // 재진입해도 서버에 저장돼 있다
    await page.reload();
    await page.locator(".ptree-row", { hasText: FOLDER }).click();
    const savedRow = page.locator("table.atable tbody tr").first();
    await expect(savedRow.locator("select").nth(1)).toHaveValue(adminId!);
    await expect(savedRow.locator("select").nth(2)).toHaveValue("read");

    // 해제 — 행 삭제 후 저장하면 빈 상태로 돌아온다
    await savedRow.locator("button.lact", { hasText: "삭제" }).click();
    await page.locator("button.btn.primary", { hasText: "저장" }).click();
    await expect(page.locator(".toast", { hasText: "ACL을 저장했습니다" }).last()).toBeVisible();
    await expect(page.getByText("직접 엔트리가 없습니다")).toBeVisible();
  });
});
