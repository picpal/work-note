import { describe, it, expect } from "vitest";
import { emptyVaultView } from "./emptyVaultPolicy";

const base = { mode: "http" as const, ready: true, loadError: false, treeEmpty: true, isAdmin: false };

describe("emptyVaultView", () => {
  it("빈 트리가 아니면 안내하지 않는다", () => {
    expect(emptyVaultView({ ...base, treeEmpty: false, isAdmin: true })).toBe("none");
  });

  it("아직 로드 전이면 판단을 보류한다", () => {
    expect(emptyVaultView({ ...base, ready: false, isAdmin: true })).toBe("none");
  });

  it("백엔드 다운(차단 화면)이면 빈 트리 안내를 띄우지 않는다", () => {
    expect(emptyVaultView({ ...base, loadError: true, isAdmin: true })).toBe("none");
  });

  it("local 모드의 빈 트리는 기존 동작을 유지한다(시드 안내 없음)", () => {
    expect(emptyVaultView({ ...base, mode: "local", isAdmin: true })).toBe("none");
    expect(emptyVaultView({ ...base, mode: "local", isAdmin: false })).toBe("none");
  });

  it("http 모드 비관리자의 빈 트리는 '권한 없음' 안내 — 시드 생성 금지", () => {
    expect(emptyVaultView({ ...base, isAdmin: false })).toBe("noAccess");
  });

  it("http 모드 관리자의 빈 트리만 '서버가 비었음'으로 확정할 수 있다", () => {
    // 백엔드 VaultGuard.readableIds()는 관리자에게 무필터(null)를 준다 → 관리자의 빈 트리 = 활성 노드 0개.
    expect(emptyVaultView({ ...base, isAdmin: true })).toBe("seedable");
  });
});
