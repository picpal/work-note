/* 백엔드 자바 소스에서 "실제로 기록되는 감사 행위 코드"를 추출하는 순수 스캐너.

   감사 화면의 라벨·필터는 auditActs.ts 한 곳에서 파생되는데, 그 목록이 백엔드와 어긋나면
   관리자는 원시 코드를 보거나(라벨 누락) 필터에서 이벤트를 못 찾는다(필터 누락).
   backendSync.test.ts가 이 스캐너로 backend/src/main/java 전량을 훑어 두 집합이 정확히
   같은지 강제한다 — 백엔드에 새 act가 생기면 프런트 테스트가 즉시 빨개진다.

   스캐너는 조용히 실패하지 않는다: act 인자가 문자열 리터럴이 아니거나(dynamic),
   AuditService 필드명이 관례(audit)를 벗어나거나(strayFields), 텍스트 블록이 등장하면
   (unsupported) 각각 목록으로 돌려주고 테스트가 그것들을 실패로 다룬다. */

export interface AuditActScan {
  /** audit.log(...) / audit.logRaw(...)의 2번째 인자에서 뽑은 act 문자열 리터럴(중복 포함 X, 등장 순). */
  acts: string[];
  /** act 인자가 문자열 리터럴이 아닌 호출 — 스캐너가 값을 알 수 없다(=사각지대). */
  dynamic: string[];
  /** 관례(audit)를 벗어난 AuditService 필드/파라미터 이름 — 그 호출은 스캔에 안 잡힌다. */
  strayFields: string[];
  /** 스캐너가 다루지 못하는 자바 문법(텍스트 블록 """). */
  unsupported: string[];
}

/** 문자열·문자 리터럴은 보존한 채 행 주석·블록 주석만 제거. 주석 속 "audit.log(null)" 오탐 차단용. */
export function stripJavaComments(src: string): string {
  let out = "";
  let i = 0;
  const n = src.length;
  while (i < n) {
    const c = src[i];
    if (c === '"' || c === "'") {
      const quote = c;
      out += c;
      i++;
      while (i < n) {
        if (src[i] === "\\") { out += src[i] + (src[i + 1] ?? ""); i += 2; continue; }
        out += src[i];
        const done = src[i] === quote;
        i++;
        if (done) break;
      }
      continue;
    }
    if (c === "/" && src[i + 1] === "/") {
      while (i < n && src[i] !== "\n") i++;
      continue;   // 개행은 다음 루프에서 그대로 복사된다
    }
    if (c === "/" && src[i + 1] === "*") {
      i += 2;
      while (i < n && !(src[i] === "*" && src[i + 1] === "/")) i++;
      i += 2;
      out += " ";
      continue;
    }
    out += c;
    i++;
  }
  return out;
}

/** open = '(' 의 인덱스. 최상위 콤마로 나눈 인자 문자열들. 괄호가 안 닫히면 null. */
export function splitArgs(src: string, open: number): string[] | null {
  const args: string[] = [];
  let depth = 0;
  let cur = "";
  for (let i = open; i < src.length; i++) {
    const c = src[i];
    if (c === '"' || c === "'") {
      const quote = c;
      cur += c;
      i++;
      let closed = false;
      for (; i < src.length; i++) {
        if (src[i] === "\\") { cur += src[i] + (src[i + 1] ?? ""); i++; continue; }
        cur += src[i];
        if (src[i] === quote) { closed = true; break; }
      }
      if (!closed) return null;
      continue;
    }
    if (c === "(" || c === "[" || c === "{") {
      depth++;
      if (depth === 1) continue;   // 여는 괄호 자체는 인자에 넣지 않는다
      cur += c;
      continue;
    }
    if (c === ")" || c === "]" || c === "}") {
      depth--;
      if (depth === 0) { args.push(cur.trim()); return args; }
      cur += c;
      continue;
    }
    if (c === "," && depth === 1) { args.push(cur.trim()); cur = ""; continue; }
    cur += c;
  }
  return null;
}

const LITERAL = /^"([^"\\]*)"$/;
const CALL = /\baudit\s*\.\s*(log|logRaw)\s*\(/g;
const AUDIT_FIELD = /\bAuditService\s+([A-Za-z_$][\w$]*)/g;

/** 자바 소스 1개를 훑어 감사 act 코드를 추출. label은 실패 메시지에 쓸 파일 이름. */
export function scanAuditActs(source: string, label = "<source>"): AuditActScan {
  const acts: string[] = [];
  const dynamic: string[] = [];
  const strayFields: string[] = [];
  const unsupported: string[] = [];

  if (source.includes('"""')) unsupported.push(`${label}: 텍스트 블록(""")은 스캐너가 다루지 못한다`);

  const src = stripJavaComments(source);

  for (const m of src.matchAll(AUDIT_FIELD)) {
    if (m[1] !== "audit") strayFields.push(`${label}: AuditService ${m[1]} — 필드명은 audit여야 스캔된다`);
  }

  CALL.lastIndex = 0;
  for (const m of src.matchAll(CALL)) {
    const open = (m.index ?? 0) + m[0].length - 1;
    const args = splitArgs(src, open);
    const act = args?.[1];
    if (act === undefined) {
      dynamic.push(`${label}: ${m[0]}…) 인자 파싱 실패`);
      continue;
    }
    const lit = LITERAL.exec(act);
    if (!lit) { dynamic.push(`${label}: audit.${m[1]}(…, ${act}, …) — act가 문자열 리터럴이 아니다`); continue; }
    if (!acts.includes(lit[1])) acts.push(lit[1]);
  }
  return { acts, dynamic, strayFields, unsupported };
}

/** 여러 파일의 스캔 결과 병합(파일명 사전순 입력 전제 — acts 순서는 안정적). */
export function scanAll(files: Record<string, string>): AuditActScan {
  const merged: AuditActScan = { acts: [], dynamic: [], strayFields: [], unsupported: [] };
  for (const path of Object.keys(files).sort()) {
    const r = scanAuditActs(files[path], path.split("/").pop() ?? path);
    for (const a of r.acts) if (!merged.acts.includes(a)) merged.acts.push(a);
    merged.dynamic.push(...r.dynamic);
    merged.strayFields.push(...r.strayFields);
    merged.unsupported.push(...r.unsupported);
  }
  return merged;
}
