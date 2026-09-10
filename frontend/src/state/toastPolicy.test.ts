import { describe, it, expect } from "vitest";
import { toastKind, toastDuration } from "./toastPolicy";

describe("toastKind", () => {
  it("호출부가 명시한 종류를 최우선으로 따른다", () => {
    expect(toastKind("저장되었습니다", "check", "warn")).toBe("warn");
    expect(toastKind("실패했습니다", undefined, "ok")).toBe("ok");
  });

  it("아이콘이 있으면 성공 토스트로 본다(레포 관례: 성공만 아이콘을 넘긴다)", () => {
    expect(toastKind("저장되었습니다", "check")).toBe("ok");
    expect(toastKind("링크를 복사했습니다", "clipboard")).toBe("ok");
    expect(toastKind("노트를 삭제했습니다", "trash")).toBe("ok");
  });

  it("아이콘 없는 메시지는 실패·경고로 본다", () => {
    expect(toastKind("서버 동기화 실패: Failed to fetch")).toBe("warn");
    expect(toastKind("허용하지 않는 파일 형식입니다: .exe")).toBe("warn");
    expect(toastKind("만료 일수는 1~365 사이여야 합니다: 0")).toBe("warn");
    expect(toastKind("프로필에서 Redmine 키를 먼저 등록하세요")).toBe("warn");
  });

  it("말줄임으로 끝나는 진행 알림은 짧게 유지한다", () => {
    expect(toastKind("업로드 중…")).toBe("ok");
    expect(toastKind("불러오는 중...")).toBe("ok");
  });
});

describe("toastDuration", () => {
  it("성공은 짧게, 실패·경고는 읽을 시간을 준다", () => {
    expect(toastDuration("ok")).toBe(1500);
    expect(toastDuration("warn")).toBeGreaterThanOrEqual(4000);
    expect(toastDuration("warn")).toBeLessThanOrEqual(5000);
  });
});
