import { test, expect, type Page } from "@playwright/test";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { uniq } from "../helpers/session";

/**
 * 파일 첨부 (index.html 에디터 툴바 "파일 첨부") — 업로드·첨부바·이미지 인라인·정책 거부.
 * 업로드 기본 정책(app_setting 시드): 허용 확장자 png,jpg,jpeg,gif,webp,pdf,docx,xlsx,pptx,txt,md,csv,zip
 * / 최대 25MB — txt·png는 허용, exe는 422 거부된다.
 * 이미지는 본문 커서 위치에 ![파일명](/api/attachments/{id}) 인라인 삽입, 비이미지는 첨부바에만 등록.
 */

const FIXTURES = path.join(path.dirname(fileURLToPath(import.meta.url)), "../fixtures");

/** 루트 빈 영역 우클릭 — "모두 접기"로 트리 하단 빈 공간을 확보한 뒤 마지막 행 아래를 우클릭한다. */
async function openRootContext(page: Page) {
  await page.locator('.sb-toolbar button[title="모두 접기"]').click();
  await expect(page.locator(".tree .children")).toHaveCount(0);
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

/** 루트에 노트 생성(생성 POST 대기) + 트리 rename으로 제목 부여(PATCH 대기). 에디터에 열린다. */
async function createRootNote(page: Page, title: string) {
  await openRootContext(page);
  const created = page.waitForResponse(
    (r) => r.url().endsWith("/api/nodes") && r.request().method() === "POST" && r.ok(),
  );
  await page.locator(".ctx-item", { hasText: "새 노트" }).click();
  await created;
  const rename = page.locator("input.tree-rename");
  await expect(rename).toBeVisible();
  const renamed = page.waitForResponse(
    (r) => r.url().includes("/api/nodes/") && r.request().method() === "PATCH" && r.ok(),
  );
  await rename.fill(title);
  await rename.press("Enter");
  await renamed;
}

/** 툴바 "파일 첨부" → filechooser로 픽스처 업로드. 업로드 응답(성공/거부)을 반환. */
async function attachFixture(page: Page, filename: string) {
  const uploaded = page.waitForResponse(
    (r) => r.url().includes("/attachments") && r.request().method() === "POST",
  );
  const chooserPromise = page.waitForEvent("filechooser");
  await page.locator('.etoolbar button[title="파일 첨부"]').click();
  const chooser = await chooserPromise;
  await chooser.setFiles(path.join(FIXTURES, filename));
  return uploaded;
}

test.describe("파일 첨부", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/index.html");
    await expect(page.locator(".topbar-me")).toBeVisible();
  });

  test("텍스트 파일 첨부 → 첨부바에만 등록되고 본문에는 삽입되지 않는다", async ({ page }) => {
    await createRootNote(page, uniq("e2e-첨부txt"));
    const res = await attachFixture(page, "sample.txt");
    expect(res.ok()).toBeTruthy();

    const bar = page.locator(".attach-bar");
    await expect(bar).toBeVisible();
    await expect(bar.locator(".attach-bar-head")).toHaveText("첨부파일 1");
    await expect(bar.locator(".attach-name")).toHaveText("sample.txt");
    // 비이미지는 본문 인라인 삽입 없음
    await expect(page.locator(".cm-host .cm-content")).not.toContainText("sample.txt");
  });

  test("이미지 첨부 → 첨부바 등록 + 본문 인라인 미리보기 삽입", async ({ page }) => {
    await createRootNote(page, uniq("e2e-첨부img"));
    const res = await attachFixture(page, "sample.png");
    expect(res.ok()).toBeTruthy();

    const bar = page.locator(".attach-bar");
    await expect(bar).toBeVisible();
    await expect(bar.locator(".attach-name")).toHaveText("sample.png");

    // 커서가 삽입 라인 밖으로 이동해 이미지 위젯이 렌더된다
    const img = page.locator(".cm-host .cm-md-image img");
    await expect(img).toBeVisible();
    await expect(img).toHaveAttribute("src", /\/api\/attachments\//);
  });

  test("허용하지 않는 확장자는 422 거부 + 오류 토스트", async ({ page }) => {
    await createRootNote(page, uniq("e2e-첨부exe"));
    const res = await attachFixture(page, "sample.exe");
    expect(res.status()).toBe(422);

    await expect(page.locator(".toast", { hasText: "허용하지 않는 파일 형식" })).toBeVisible();
    // 거부된 파일은 첨부바에 나타나지 않는다 (항목 0 → 첨부바 자체가 렌더되지 않음)
    await expect(page.locator(".attach-bar")).toHaveCount(0);
  });
});
