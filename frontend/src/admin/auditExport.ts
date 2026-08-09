/* auditExport — 감사 로그 CSV 내보내기의 순수 변환(vitest 대상).
   이 파일이 만드는 CSV는 ISMS·인증 심사에 제출되는 증적물이다. 따라서
   ① 권한 변경 델타를 반드시 포함하고(빠지면 재구성 공백이 DB가 아니라 제출물로 옮겨갈 뿐),
   ② 감사자가 파서가 아니라 사람이므로 JSON 원문이 아닌 사람이 읽는 표현으로 적는다. */
import type { ApiAudit } from "./api";
import { auditDetailLines } from "./auditDetail";
import { actLabel } from "./mappers";

/** 델타 여러 줄을 CSV 한 칸에 담는 구분자. 칸 안 줄바꿈은 RFC4180상 합법이지만 순진한 파서·
    행 구분(CRLF)과 얽혀 라운드트립이 잘 깨진다. 가운뎃점은 CSV 구분자도 수식 문자도 아니다. */
export const DETAIL_SEP = " · ";

/** 화면 표시용 부호(+ − ~)를 증적 문서용 한글 라벨로. 인쇄물에서 부호는 근거로 읽히지 않는다. */
const SIGN_WORD: Record<string, string> = { "+": "추가", "−": "회수", "~": "변경" };

/**
 * Excel·LibreOffice는 `= + - @`(및 탭·CR·LF)로 시작하는 칸을 수식으로 **실행**한다(OWASP CSV Injection).
 * 따옴표로 감싸는 것은 방어가 아니다 — 파서가 따옴표를 벗긴 뒤 평가하기 때문이다.
 * 텍스트 마커(')를 앞에 붙여 무해화하고, 정상 값은 증적 원문 그대로 둔다.
 *
 * 선두 문자 하나만 보면 두 방향으로 뚫린다:
 * ① 전각 `＝ ＋ － ＠`(U+FF1D/FF0B/FF0D/FF20) — 한글 IME로 쉽게 입력되고 Excel이 반각으로 접어 평가한다.
 * ② 트리거 앞 공백 — Excel은 칸 선두 공백을 무시하므로 `" =1+1"`도 수식이다.
 * 그래서 '공백 런 뒤의 트리거'까지 본다. `\s*`는 백트래킹하므로 탭·CR·LF 자체로 시작하는 칸도 그대로 걸린다.
 * 반대로 공백만으로는 트리거가 아니다 — 사번·한글처럼 앞에 공백이 붙었을 뿐인 정상 값을 훼손하지 않기 위함.
 */
const RISKY_PREFIX = /^\s*[=+\-@\t\r\n＝＋－＠]/;

export function csvCell(value: unknown): string {
  const s = value == null ? "" : String(value);
  const safe = RISKY_PREFIX.test(s) ? "'" + s : s;
  return '"' + safe.replace(/"/g, '""') + '"';
}

/** at은 ISO_LOCAL_DATE_TIME(마이크로초 포함 가능) — 초 단위까지만. 화면 표와 같은 규칙(단일 출처). */
export function fmtAuditAt(at: string): string {
  return at.replace("T", " ").slice(0, 19);
}

/** 권한 변경 델타 → CSV 한 칸. 델타가 없는 act는 빈 칸. */
export function auditDetailCsvCell(act: string, detail: string | null | undefined): string {
  return auditDetailLines(act, detail)
    .map((ln) => (SIGN_WORD[ln.sign] ?? ln.sign) + " " + ln.text)
    .join(DETAIL_SEP);
}

const HEAD = ["일시", "행위자", "행위", "대상", "IP/단말", "변경 내역"];

/** 현재 페이지 행 → CSV 본문(BOM·다운로드는 호출측 책임). */
export function buildAuditCsv(rows: ApiAudit[]): string {
  return [HEAD.map(csvCell).join(",")]
    .concat(rows.map((r) => [
      fmtAuditAt(r.at),
      r.who,
      actLabel(r.act),
      r.target ?? "—",
      r.ip,
      auditDetailCsvCell(r.act, r.detail),
    ].map(csvCell).join(",")))
    .join("\r\n");
}
