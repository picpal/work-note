import { test, expect, type Page, type Locator } from "@playwright/test";
import { uniq, E2E_PORT } from "../helpers/session";

/**
 * 사이드바 트리 (index.html) — 폴더/노트 생성·이름 변경·이동(MoveModal)·휴지통 복구/영구 삭제.
 * - 이동 노출 경고는 fresh DB(ACL·public·스페이스 없음)에선 절대 뜨지 않는다(ExposureService 델타 0).
 *   경고 경로는 admin API로 폴더에 public 플래그를 심어 재현한다.
 * - 전체 스위트가 한 DB를 공유해 루트 행이 누적된다 → 루트 우클릭 빈 공간 확보를 위해 뷰포트를 키운다.
 */

test.use({ viewport: { width: 1280, height: 1100 } });

const BASE_URL = `http://localhost:${E2E_PORT}`;

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

/** rename 입력(자동 포커스)에 이름을 넣고 Enter — rename PATCH까지 대기. */
async function commitRename(page: Page, name: string) {
  const rename = page.locator("input.tree-rename");
  await expect(rename).toBeVisible();
  const renamed = page.waitForResponse(
    (r) => r.url().includes("/api/nodes/") && r.request().method() === "PATCH" && r.ok(),
  );
  await rename.fill(name);
  await rename.press("Enter");
  await renamed;
}

/** 컨텍스트 메뉴 항목 클릭 전, 생성 POST 응답 대기를 함께 건다. */
async function createViaContext(page: Page, itemLabel: "새 노트" | "새 폴더", name: string) {
  const created = page.waitForResponse(
    (r) => r.url().endsWith("/api/nodes") && r.request().method() === "POST" && r.ok(),
  );
  await page.locator(".ctx-item", { hasText: itemLabel }).click();
  await created;
  await commitRename(page, name);
}

const rowByName = (page: Page, name: string): Locator =>
  page.locator(".tree .row").filter({ hasText: name });

/** 접힌 폴더면 클릭해 펼친다 — 접힌 폴더에 노트를 만들면 rename 입력이 렌더되지 않는다. */
async function ensureFolderOpen(page: Page, row: Locator) {
  const cls = (await row.locator(".twirl").getAttribute("class")) ?? "";
  if (!cls.includes("open")) await row.click();
}

/** 폴더 우클릭 → 새 노트 → 이름 커밋. */
async function createNoteInFolder(page: Page, folderName: string, noteName: string) {
  const row = rowByName(page, folderName);
  await ensureFolderOpen(page, row);
  await row.click({ button: "right" });
  await createViaContext(page, "새 노트", noteName);
}

test.describe("사이드바 트리", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/index.html");
    await expect(page.locator(".topbar-me")).toBeVisible();
  });

  test("폴더 생성 → 그 하위에 노트 생성", async ({ page }) => {
    const folder = uniq("e2e-폴더");
    const note = uniq("e2e-하위노트");

    await openRootContext(page);
    await createViaContext(page, "새 폴더", folder);
    await expect(page.locator(".tree .row .label", { hasText: folder })).toBeVisible();

    await createNoteInFolder(page, folder, note);
    await expect(page.locator(".tree .row .label", { hasText: note })).toBeVisible();
    // 폴더 노트 수 배지 = 1 → 노트가 폴더 하위로 들어갔다는 사용자 가시 증거
    await expect(rowByName(page, folder).locator(".count")).toHaveText("1");
  });

  test("컨텍스트 메뉴로 노트 이름을 변경한다", async ({ page }) => {
    const before = uniq("e2e-개명전");
    const after = uniq("e2e-개명후");
    await openRootContext(page);
    await createViaContext(page, "새 노트", before);

    await rowByName(page, before).click({ button: "right" });
    await page.locator(".ctx-item", { hasText: "이름 변경" }).click();
    await commitRename(page, after);

    await expect(page.locator(".tree .row .label", { hasText: after })).toBeVisible();
    await expect(page.locator(".tree .row .label", { hasText: before })).toHaveCount(0);
  });

  test("MoveModal로 노트를 다른 폴더로 이동한다 (노출 변화 없음 → 경고 없음)", async ({ page }) => {
    const folderA = uniq("e2e-출발지");
    const folderB = uniq("e2e-도착지");
    const note = uniq("e2e-이동노트");

    await openRootContext(page);
    await createViaContext(page, "새 폴더", folderA);
    await openRootContext(page);
    await createViaContext(page, "새 폴더", folderB);
    await createNoteInFolder(page, folderA, note);

    await rowByName(page, note).click({ button: "right" });
    await page.locator(".ctx-item", { hasText: "이동" }).click();
    const modal = page.locator(".pf-card.mv-modal");
    await expect(modal).toBeVisible();

    await modal.locator(".mv-search").fill(folderB);
    const opt = modal.locator(".mv-opt", { hasText: folderB });
    await expect(opt).toHaveCount(1);
    await opt.click();

    const moved = page.waitForResponse(
      (r) => r.url().includes("/move") && r.request().method() === "POST" && r.ok(),
    );
    await modal.locator(".pf-btn.primary", { hasText: "이동" }).click();
    await moved; // fresh 권한 상태에선 move-preview 델타가 없어 경고 없이 즉시 이동
    await expect(page.locator(".toast", { hasText: "이동했습니다" })).toBeVisible();
    await expect(modal).toHaveCount(0);

    await expect(rowByName(page, folderB).locator(".count")).toHaveText("1");
    await expect(rowByName(page, folderA).locator(".count")).toHaveCount(0);
  });

  test("public 폴더로 이동 시 노출 경고가 뜨고, 확인하면 이동된다", async ({ page }) => {
    const folder = uniq("e2e-공개폴더");
    const note = uniq("e2e-공개이동노트");

    await openRootContext(page);
    await createViaContext(page, "새 폴더", folder);
    await openRootContext(page);
    await createViaContext(page, "새 노트", note);

    // admin API로 폴더에 public(cascade) 플래그 — publicBefore=false → publicAfter=true 경고 조건 구성.
    // 주의: apiLogin(재로그인)을 쓰면 안 된다 — 테스트 러너의 request.newContext가 프로젝트 use.storageState
    // (공유 admin 세션 쿠키)를 기본으로 물고 로그인해, 서버 세션 고정 방어(changeSessionId)가 그 세션 id를
    // 회전시켜 이 테스트 이후 모든 테스트의 공유 세션이 죽는다. 같은 UI 세션(page.request)으로 호출한다.
    const tree = (await (await page.request.get("/api/tree")).json()) as Array<{
      id: string; name?: string; title?: string; children?: unknown[];
    }>;
    const findByName = (nodes: typeof tree, name: string): (typeof tree)[number] | null => {
      for (const n of nodes) {
        if ((n.name ?? n.title) === name) return n;
        const found = n.children ? findByName(n.children as typeof tree, name) : null;
        if (found) return found;
      }
      return null;
    };
    const folderNode = findByName(tree, folder);
    expect(folderNode, "public 플래그를 심을 폴더를 /api/tree에서 찾지 못함").not.toBeNull();
    const res = await page.request.put(`/api/admin/nodes/${folderNode!.id}/public`, {
      data: { mode: "public" },
      headers: { Origin: BASE_URL }, // CSRF: 세션 쿠키가 실린 변경 메서드는 Origin 필수 (lessons #7)
    });
    expect(res.status()).toBe(204);

    await rowByName(page, note).click({ button: "right" });
    await page.locator(".ctx-item", { hasText: "이동" }).click();
    const modal = page.locator(".pf-card.mv-modal");
    await modal.locator(".mv-search").fill(folder);
    await modal.locator(".mv-opt", { hasText: folder }).click();
    await modal.locator(".pf-btn.primary", { hasText: "이동" }).click();

    // 경고 단계 — 공개 노출 안내 + 강한 경고 문구, 확인 버튼은 danger
    await expect(modal.locator(".pf-sec-label", { hasText: "이동 시 변경 사항" })).toBeVisible();
    await expect(modal.locator(".mv-warn-line", { hasText: "전 직원이 읽을 수 있게 됩니다" })).toBeVisible();
    await expect(modal.locator(".pf-msg.err")).toContainText("노출 범위가 넓어집니다");

    const moved = page.waitForResponse(
      (r) => r.url().includes("/move") && r.request().method() === "POST" && r.ok(),
    );
    await modal.locator(".pf-btn.danger", { hasText: "이동" }).click();
    await moved;
    await expect(page.locator(".toast", { hasText: "이동했습니다" })).toBeVisible();
    await expect(modal).toHaveCount(0);
    await expect(rowByName(page, folder).locator(".count")).toHaveText("1");
  });

  test("삭제 → 휴지통 복구 → 재삭제 → 영구 삭제", async ({ page }) => {
    const note = uniq("e2e-휴지통노트");
    await openRootContext(page);
    await createViaContext(page, "새 노트", note);

    const trashRow = () => page.locator(".sh-row").filter({ hasText: note });
    const deleteNote = async () => {
      await rowByName(page, note).click({ button: "right" });
      const removed = page.waitForResponse(
        (r) => r.url().includes("/api/nodes/") && r.request().method() === "DELETE" && r.ok(),
      );
      await page.locator(".ctx-item.danger", { hasText: "삭제" }).click();
      // 삭제→복구→재삭제가 토스트 수명(1.5s) 안에 끝나면 같은 문구 토스트가 겹칠 수 있다 → first()
      await expect(page.locator(".toast", { hasText: "노트를 삭제했습니다" }).first()).toBeVisible();
      await removed;
      await expect(rowByName(page, note)).toHaveCount(0);
    };

    // 1) 삭제 → 휴지통에서 복구
    await deleteNote();
    await page.locator('.sb-fbtn[title="휴지통"]').click();
    await expect(page.locator(".pf-emp", { hasText: "휴지통" })).toBeVisible();
    await expect(trashRow()).toHaveCount(1);
    await trashRow().locator(".pf-btn.primary", { hasText: "복구" }).click();
    await expect(page.locator(".toast", { hasText: "복구했습니다" })).toBeVisible();
    await expect(trashRow()).toHaveCount(0);
    await page.locator(".pf-x").click();
    await expect(rowByName(page, note)).toBeVisible(); // 트리 재동기화로 복귀

    // 2) 재삭제 → 영구 삭제 (2단계 확인)
    await deleteNote();
    await page.locator('.sb-fbtn[title="휴지통"]').click();
    await expect(trashRow()).toHaveCount(1);
    await trashRow().locator(".pf-btn", { hasText: "영구 삭제" }).click();
    await trashRow().locator(".pf-btn.danger", { hasText: "영구 삭제 확인" }).click();
    await expect(page.locator(".toast", { hasText: "영구 삭제했습니다" })).toBeVisible();
    await expect(trashRow()).toHaveCount(0);
    await page.locator(".pf-x").click();
    await expect(rowByName(page, note)).toHaveCount(0); // 영구 삭제 — 트리에도 없다
  });
});
