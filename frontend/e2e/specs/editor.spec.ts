import { test, expect, type Page } from "@playwright/test";
import { uniq } from "../helpers/session";

/**
 * 메인 에디터 (index.html) — 노트 작성·저장·검색의 필수 경로.
 * - 자동 저장은 1분 디바운스 → 저장 검증은 반드시 수동 저장(.doc-save) 후에 한다.
 * - admin 세션이라 2FA 유예 배너(.totp-nudge-banner)가 항상 떠 있는 상태를 전제로 한다.
 * - 본문·제목에 이메일/전화번호 형태 문자열 금지 — PII 스캐너가 배너·알림을 띄워 화면이 변한다.
 */

/** 루트 빈 영역 우클릭 — "모두 접기"로 트리 하단 빈 공간을 확보한 뒤 마지막 행 아래를 우클릭한다.
    (행 위 우클릭은 stopPropagation으로 노드 메뉴가 되므로 반드시 빈 공간이어야 루트 메뉴가 뜬다) */
async function openRootContext(page: Page) {
  await page.locator('.sb-toolbar button[title="모두 접기"]').click();
  await expect(page.locator(".tree .children")).toHaveCount(0); // 접힘 반영 대기 — 행 위치 안정화
  const tree = page.locator(".tree");
  const box = (await tree.boundingBox())!;
  const rows = tree.locator(".row");
  const n = await rows.count();
  let y = 10;
  if (n > 0) {
    const last = (await rows.nth(n - 1).boundingBox())!;
    y = Math.min(last.y + last.height - box.y + 10, box.height - 5);
  }
  await tree.click({ button: "right", position: { x: box.width / 2, y } });
  await expect(page.locator(".ctx-item", { hasText: "새 노트" })).toBeVisible();
}

/** 루트에 노트 생성(생성 POST 대기). title이 null이면 rename을 취소해 기본 제목("제목 없는 노트") 유지,
    아니면 트리 rename 입력으로 제목을 붙인다(rename PATCH 대기). 생성된 노트가 에디터에 열린다. */
async function createRootNote(page: Page, title: string | null) {
  await openRootContext(page);
  const created = page.waitForResponse(
    (r) => r.url().endsWith("/api/nodes") && r.request().method() === "POST" && r.ok(),
  );
  await page.locator(".ctx-item", { hasText: "새 노트" }).click();
  await created;
  const rename = page.locator("input.tree-rename");
  await expect(rename).toBeVisible();
  if (title == null) {
    await rename.press("Escape");
    return;
  }
  const renamed = page.waitForResponse(
    (r) => r.url().includes("/api/nodes/") && r.request().method() === "PATCH" && r.ok(),
  );
  await rename.fill(title);
  await rename.press("Enter");
  await renamed;
}

test.describe("에디터 화면 (index.html)", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/index.html");
    await expect(page.locator(".topbar-me")).toBeVisible();
  });

  test("노트 생성 → 제목·본문·태그 입력 → 수동 저장 → 새로고침 후 유지된다", async ({ page }) => {
    const title = uniq("e2e-에디터노트");
    const body = "본문 자동화 검증 텍스트 " + title;
    await createRootNote(page, null); // 기본 제목으로 생성 — 제목은 에디터에서 직접 입력

    const titleInput = page.locator("textarea.title-input");
    await expect(titleInput).toHaveValue("제목 없는 노트");
    await titleInput.fill(title);
    await expect(page.locator(".tree .row .label", { hasText: title })).toBeVisible(); // 트리 라벨 즉시 반영

    await page.locator(".cm-host .cm-content").click();
    await page.keyboard.type(body);

    const tagInput = page.locator("input.tag-input");
    await tagInput.fill("qa-e2e");
    await tagInput.press("Enter");
    await expect(page.locator(".tags-row .tag", { hasText: "#qa-e2e" })).toBeVisible();

    // 수동 저장 — dirty 상태의 저장 버튼을 눌러 디바운스 없이 즉시 PATCH
    const save = page.locator("button.doc-save");
    await expect(save).toHaveClass(/dirty/);
    await expect(save).toContainText("저장");
    const patched = page.waitForResponse(
      (r) => r.url().includes("/api/nodes/") && r.request().method() === "PATCH" && r.ok(),
    );
    await save.click();
    await expect(page.locator(".toast", { hasText: "저장되었습니다" })).toBeVisible();
    await expect(save).toContainText("저장됨");
    await patched;

    await page.reload();
    await expect(page.locator("textarea.title-input")).toHaveValue(title);
    await expect(page.locator(".cm-host .cm-content")).toContainText(body);
    await expect(page.locator(".tags-row .tag", { hasText: "#qa-e2e" })).toBeVisible();
    await expect(page.locator(".crumbs .seg.cur")).toHaveText(title);
  });

  test("태그를 추가하고 × 버튼으로 삭제한다", async ({ page }) => {
    await createRootNote(page, uniq("e2e-태그노트"));
    const tagInput = page.locator("input.tag-input");
    await tagInput.fill("alpha");
    await tagInput.press("Enter");
    await tagInput.fill("beta");
    await tagInput.press("Enter");
    await expect(page.locator(".tags-row .tag")).toHaveCount(2);

    await page.locator(".tags-row .tag", { hasText: "#alpha" }).locator("button").click();
    await expect(page.locator(".tags-row .tag")).toHaveCount(1);
    await expect(page.locator(".tags-row .tag", { hasText: "#beta" })).toBeVisible();
  });

  test("⌘K 검색 → 결과 클릭으로 해당 노트로 이동한다", async ({ page }) => {
    const target = uniq("e2e-검색대상");
    const other = uniq("e2e-다른노트");
    await createRootNote(page, target);
    await createRootNote(page, other); // 마지막 생성 노트가 활성 — 검색 이동을 구분하기 위한 대조군
    await expect(page.locator("textarea.title-input")).toHaveValue(other);

    await page.keyboard.press("Control+k");
    const box = page.locator(".search-box");
    await expect(box).toBeVisible();
    await box.locator("input").fill(target);
    const item = page.locator(".sr-item", { hasText: target });
    await expect(item).toHaveCount(1);
    await item.click();

    await expect(page.locator(".search-box")).toHaveCount(0);
    await expect(page.locator("textarea.title-input")).toHaveValue(target);
    await expect(page.locator(".crumbs .seg.cur")).toHaveText(target);
  });
});
