import { describe, it, expect } from "vitest";
import { saveButtonState, saveButtonClass, syncBanner, shareFlushGate } from "./syncStatus";

describe("saveButtonState", () => {
  it("미저장 편집이 없으면 '저장됨'", () => {
    const s = saveButtonState(false, 0);
    expect(s.label).toBe("저장됨");
    expect(s.disabled).toBe(true);
    expect(s.danger).toBe(false);
  });

  it("편집 중이면 '저장'", () => {
    const s = saveButtonState(true, 0);
    expect(s.label).toBe("저장");
    expect(s.disabled).toBe(false);
  });

  it("서버 전송이 실패해 대기 중이면 '저장됨'이라고 말하지 않는다 (D-2)", () => {
    const s = saveButtonState(false, 2);
    expect(s.label).not.toBe("저장됨");
    expect(s.label).toContain("실패");
    expect(s.danger).toBe(true);
    expect(s.disabled).toBe(false);      // 수동 재시도가 가능해야 한다
    expect(s.title).toContain("2");
  });

  it("편집 중이어도 전송 실패가 우선 표시된다", () => {
    expect(saveButtonState(true, 1).danger).toBe(true);
  });
});

describe("syncBanner", () => {
  it("대기 중인 변경이 없으면 배너 없음", () => {
    expect(syncBanner(0, false)).toBeNull();
    expect(syncBanner(0, true)).toBeNull();
  });

  it("서버 연결 실패는 '연결할 수 없음'으로 지속 표시한다", () => {
    const msg = syncBanner(3, true)!;
    expect(msg).toContain("3");
    expect(msg).toContain("연결");
  });

  it("연결은 되지만 저장에 실패한 경우도 건수를 계속 알린다", () => {
    const msg = syncBanner(1, false)!;
    expect(msg).toContain("1");
    expect(msg).toContain("저장");
  });
});

describe("shareFlushGate", () => {
  it("모두 저장됐으면 그대로 진행", () => {
    expect(shareFlushGate({ ok: true, unsynced: 0 })).toEqual({ proceed: true });
  });

  it("미저장 변경이 남으면 사용자에게 묻는다 — 조용히 만들지 않는다 (D-3)", () => {
    const g = shareFlushGate({ ok: false, unsynced: 1, error: "Failed to fetch" });
    expect(g.proceed).toBe(false);
    expect(g.message).toContain("저장되지 않은 변경");
    expect(g.message).toContain("Failed to fetch");
  });

  it("오류 메시지가 없어도 경고 문구는 만들어진다", () => {
    const g = shareFlushGate({ ok: false, unsynced: 2 });
    expect(g.proceed).toBe(false);
    expect(g.message).toContain("2");
  });
});

describe("saveButtonClass", () => {
  it("저장됨 = 기본 스타일", () => {
    expect(saveButtonClass(saveButtonState(false, 0))).toBe("doc-save");
  });

  it("미저장(dirty) = dirty 스타일", () => {
    expect(saveButtonClass(saveButtonState(true, 0))).toBe("doc-save dirty");
  });

  it("저장 실패는 dirty와 다르게 보여야 한다 — 텍스트로만 구분되면 못 알아챈다 (D-2)", () => {
    const fail = saveButtonClass(saveButtonState(true, 3));
    expect(fail).toBe("doc-save danger");
    expect(fail).not.toBe(saveButtonClass(saveButtonState(true, 0)));
  });

  it("dirty가 아니어도 미전송분이 있으면 실패 스타일", () => {
    expect(saveButtonClass(saveButtonState(false, 1))).toBe("doc-save danger");
  });
});
