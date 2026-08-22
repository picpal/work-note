import { test, expect, request } from "@playwright/test";
import { ADMIN, uniq, EMPTY_STATE, E2E_PORT } from "../helpers/session";

/**
 * 공유 링크 — 메인 앱에서 생성(ShareModal) → share.html read-only 열람.
 * - 모달은 URL 원문을 DOM에 표시하지 않으므로(복사 버튼뿐) 토큰은 생성 POST 응답에서 얻는다
 *   (클립보드는 headless에서 불안정 — 사용 금지).
 * - 공유 열람(POST /api/share/{token}/view)은 AuthFilter 허용목록 밖 = 로그인 필수.
 *   비로그인은 콘텐츠가 아니라 "로그인이 필요한 링크" 카드가 정답이다 (SharePage 결정 S12).
 */

const BASE = `http://localhost:${E2E_PORT}`;

/** helpers.apiLogin 대체 — request.newContext()는 프로젝트 use.storageState(setup 세션 쿠키)를
 *  상속하므로, 그 쿠키로 로그인하면 서버 changeSessionId()(세션 고정 방어)가 세션 id를 교체해
 *  setup이 저장한 storageState 전체를 무효화한다. 빈 상태를 명시해 회피. */
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

const FOLDER = uniq("공유폴더");
const TITLE = uniq("공유노트");
const BODY = "share e2e 본문 단락입니다";

let token = ""; // 첫 테스트에서 확보 — workers=1 직렬 실행 전제

test.describe("공유 링크 — 생성과 열람", () => {
  test.beforeAll(async () => {
    // 우클릭 대상 폴더만 API로 시딩 — 노트 생성·저장·공유 발급은 UI로 수행
    const api = await seedLogin();
    const res = await api.post("/api/nodes", { data: { type: "folder", name: FOLDER } });
    expect(res.status()).toBe(201);
    await api.dispose();
  });

  test("노트 작성 → 수동 저장 → 컨텍스트 메뉴 '공유 링크' → 링크 만들기", async ({ page }) => {
    await page.goto("/index.html");
    await expect(page.locator(".topbar-me")).toBeVisible();

    // 서버 트리는 open 필드가 없어 로드 직후 폴더가 닫혀 있다 — 클릭으로 연 뒤 우클릭
    const folderRow = page.locator(".sidebar .row", { hasText: FOLDER });
    await folderRow.click();
    await folderRow.click({ button: "right" });
    const created = page.waitForResponse((r) => r.url().includes("/api/nodes") && r.request().method() === "POST");
    await page.locator(".ctx-item", { hasText: "새 노트" }).click();
    await created;

    // 트리 인라인 rename — Enter 커밋이 즉시 PATCH(name)를 발사한다
    const renamed = page.waitForResponse((r) => r.url().includes("/api/nodes/") && r.request().method() === "PATCH");
    const rename = page.locator("input.tree-rename");
    await rename.fill(TITLE);
    await rename.press("Enter");
    await renamed;

    // 본문 입력(60초 디바운스) → 수동 저장으로 즉시 flush PATCH.
    // ⌘S(ControlOrMeta+s)는 headless에서 앱 핸들러에 도달하지 않아(플로팅 저장 버튼이 dirty로 남음)
    // 같은 saveNow 경로인 우측 하단 저장 버튼(.doc-save)을 클릭한다.
    await page.locator(".cm-host .cm-content").click();
    await page.keyboard.insertText(BODY);
    const saved = page.waitForResponse((r) => r.url().includes("/api/nodes/") && r.request().method() === "PATCH");
    await page.locator("button.doc-save.dirty").click();
    await saved;
    await expect(page.locator("button.doc-save")).toContainText("저장됨");

    // 노트 우클릭 → 공유 링크 모달
    await page.locator(".sidebar .row", { hasText: TITLE }).click({ button: "right" });
    await page.locator(".ctx-item", { hasText: "공유 링크" }).click();
    const modal = page.locator(".pf-card");
    await expect(modal.locator(".pf-emp")).toHaveText(TITLE);
    await expect(modal.locator(".sh-empty", { hasText: "활성 링크가 없습니다" })).toBeVisible();

    const createShare = page.waitForResponse((r) => r.url().includes("/share") && r.request().method() === "POST");
    await modal.locator("button.pf-btn.primary", { hasText: "링크 만들기" }).click();
    const res = await createShare;
    expect(res.status()).toBe(201);
    token = (await res.json()).token;
    expect(token).toBeTruthy();

    await expect(modal.locator(".sh-row")).toHaveCount(1);
    await expect(page.locator(".toast", { hasText: "공유 링크를 만들어 복사했습니다" })).toBeVisible();
  });

  test("로그인 상태에서 share.html 열람 — 제목·본문 read-only 렌더", async ({ page }) => {
    expect(token, "선행 테스트에서 토큰을 확보하지 못했습니다").toBeTruthy();
    await page.goto(`/share.html?token=${encodeURIComponent(token)}`);
    await expect(page.locator(".share-badge")).toHaveText("읽기 전용 공유");
    await expect(page.locator("h1.share-title")).toHaveText(TITLE);
    await expect(page.locator(".share-body")).toContainText(BODY);
    // read-only — 편집 표면(CodeMirror)이 없다
    await expect(page.locator(".cm-content")).toHaveCount(0);
  });

  test("존재하지 않는 토큰 → '열 수 없는 링크' 카드", async ({ page }) => {
    await page.goto("/share.html?token=e2e-no-such-token");
    await expect(page.locator(".share-state h2")).toHaveText("열 수 없는 링크입니다");
  });
});

test.describe("공유 링크 — 비로그인", () => {
  test.use({ storageState: EMPTY_STATE });

  test("비로그인 접속은 콘텐츠 대신 로그인 안내 카드", async ({ page }) => {
    expect(token, "선행 테스트에서 토큰을 확보하지 못했습니다").toBeTruthy();
    await page.goto(`/share.html?token=${encodeURIComponent(token)}`);
    await expect(page.locator(".share-state h2")).toHaveText("로그인이 필요한 링크입니다");
    await expect(page.locator("button.share-btn", { hasText: "로그인하러 가기" })).toBeVisible();
  });
});
