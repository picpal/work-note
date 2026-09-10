import { describe, it, expect } from "vitest";
import { confirmChrome } from "./ConfirmDialog";

describe("confirmChrome — 위험도 구분", () => {
  it("일반 확인은 primary 버튼 + 회색 메시지", () => {
    const c = confirmChrome({ message: "계속할까요?" });
    expect(c.confirmClass).toBe("pf-btn primary");
    expect(c.leadClass).toBe("pf-msg ok");
    expect(c.confirmLabel).toBe("확인");
    expect(c.cancelLabel).toBe("취소");
    expect(c.icon).toBe("info");
  });

  it("되돌릴 수 없는 파괴적 동작은 danger 버튼 + 빨간 메시지", () => {
    const c = confirmChrome({ message: "지울까요?", danger: true });
    expect(c.confirmClass).toBe("pf-btn danger");
    expect(c.leadClass).toBe("pf-msg err");
    expect(c.confirmLabel).toBe("삭제");
    expect(c.icon).toBe("alert");
  });

  it("라벨·아이콘은 호출측이 덮어쓸 수 있다", () => {
    const c = confirmChrome({ message: "x", danger: true, confirmLabel: "비활성화", cancelLabel: "그만두기", icon: "shield" });
    expect(c.confirmLabel).toBe("비활성화");
    expect(c.cancelLabel).toBe("그만두기");
    expect(c.icon).toBe("shield");
    expect(c.confirmClass).toBe("pf-btn danger"); // 라벨을 바꿔도 위험도는 유지
  });

  it("문자열 하나든 여러 줄이든 줄 목록으로 normalize한다", () => {
    expect(confirmChrome({ message: "한 줄" }).lines).toEqual(["한 줄"]);
    expect(confirmChrome({ message: ["첫 줄", "둘째 줄"] }).lines).toEqual(["첫 줄", "둘째 줄"]);
  });

  it("빈 줄은 떨어뜨린다 — 조건부 문장을 그대로 넘겨도 빈 문단이 안 생긴다", () => {
    expect(confirmChrome({ message: ["첫 줄", "", "  "] }).lines).toEqual(["첫 줄"]);
  });
});
