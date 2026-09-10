/* 백엔드 ↔ 관리자 화면 정합 가드 (프런트 테스트가 backend/src/main/java를 직접 읽는다).

   관리자 화면은 "화면을 보고 잘못된 결론을 내리는" 결함에 특히 약하다. 백엔드 쪽 사실이
   바뀌었는데 화면 목록·규칙이 안 따라오면 조용히 어긋나기 때문이다. 그 종류를 여기서 한꺼번에 막는다.

   1) 감사 행위 코드: audit.log/logRaw가 기록하는 act 전수 == auditActs.ts 등록 목록(양방향).
      새 act가 생기면 빨개진다 = 라벨 없는 원시 코드가 화면에 새지 않는다. 반대로 백엔드에
      없는 코드를 남겨두면 필터에서 고를 때 항상 0건인 유령 항목이 되므로 그것도 실패.
   2) 업로드 정책 규칙: 확장자 정규식·최대 용량 상한이 UploadPolicy.java와 같은 값인지. */
import { describe, it, expect } from "vitest";
import { AUDIT_ACTS } from "./auditActs";
import { scanAll } from "./auditActScan";
import { EXT_RE, MAX_UPLOAD_MB } from "./uploadPolicyForm";

// 소스를 문자열로 읽어온다(Vite 변환 — node:fs 타입 의존 없이 프런트 테스트에서 백엔드를 본다).
const JAVA = import.meta.glob("../../../backend/src/main/java/**/*.java", {
  query: "?raw", import: "default", eager: true,
}) as Record<string, string>;

describe("감사 행위 코드 — 백엔드 소스와 UI 목록 정합", () => {
  it("백엔드 자바 소스를 실제로 읽었다(글롭이 빈 채로 통과하는 무증상 실패 차단)", () => {
    expect(Object.keys(JAVA).length).toBeGreaterThan(100);
  });

  const scan = scanAll(JAVA);

  it("스캐너 사각지대가 없다 — act는 항상 문자열 리터럴, AuditService 필드명은 audit", () => {
    expect(scan.dynamic, "act를 변수로 넘기면 화면 라벨을 보증할 수 없다").toEqual([]);
    expect(scan.strayFields).toEqual([]);
    expect(scan.unsupported).toEqual([]);
  });

  it("백엔드가 기록하는 act가 전부 auditActs.ts에 등록돼 있다", () => {
    const known = new Set(AUDIT_ACTS.map((d) => d.act));
    const missing = scan.acts.filter((a) => !known.has(a)).sort();
    expect(missing, "라벨·필터 미등록 act — auditActs.ts에 추가하라").toEqual([]);
  });

  it("auditActs.ts에 백엔드가 기록하지 않는 유령 act가 없다", () => {
    const recorded = new Set(scan.acts);
    const ghosts = AUDIT_ACTS.map((d) => d.act).filter((a) => !recorded.has(a)).sort();
    expect(ghosts, "백엔드에 없는 act — 필터에서 고르면 항상 0건이다").toEqual([]);
  });
});

describe("업로드 정책 — 화면 검증 규칙이 백엔드와 같다", () => {
  const src = JAVA[Object.keys(JAVA).find((p) => p.endsWith("/UploadPolicy.java")) ?? ""];

  it("UploadPolicy.java를 읽었다", () => {
    expect(src, "UploadPolicy.java를 못 찾았다 — 아래 검사가 무의미해진다").toBeTruthy();
  });

  it("확장자 정규식이 같다", () => {
    const m = /EXT_PATTERN\s*=\s*Pattern\.compile\("([^"]+)"\)/.exec(src);
    expect(m, "EXT_PATTERN 선언을 못 찾았다").toBeTruthy();
    // 자바 Pattern.matches = 전체 일치. 프런트는 ^…$로 같은 의미를 만든다.
    expect(EXT_RE.source).toBe("^" + m![1] + "$");
  });

  it("최대 용량 상한이 같다", () => {
    const m = /MAX_BYTES_LIMIT\s*=\s*(\d+)L\s*\*\s*1024\s*\*\s*1024/.exec(src);
    expect(m, "MAX_BYTES_LIMIT 선언을 못 찾았다").toBeTruthy();
    expect(Number(m![1])).toBe(MAX_UPLOAD_MB);
  });
});
