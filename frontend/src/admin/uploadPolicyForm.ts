/* 업로드 정책 화면의 입력 검증 — 순수 함수(vitest 단위 검증, 화면은 결과만 표시).

   백엔드 UploadPolicy.EXT_PATTERN / MAX_BYTES_LIMIT와 같은 규칙을 화면에서도 적용한다.
   백엔드가 최종 방어선이지만(API 직접 호출 경로가 있다), 저장 버튼을 누른 뒤에야 사유를 아는 건
   나쁜 UX라 입력 시점에 같은 사유로 막는다. 두 규칙이 어긋나지 않는지는
   backendSync.test.ts가 백엔드 소스를 읽어 확인한다. */

/** 백엔드 UploadPolicy.EXT_PATTERN과 같은 규칙: 영문 소문자·숫자 1~16자. */
export const EXT_RE = /^[a-z0-9]{1,16}$/;

/** 백엔드 UploadPolicy.MAX_BYTES_LIMIT(=application.yml multipart 상한)과 같은 값. */
export const MAX_UPLOAD_MB = 64;

/** 입력 표기 정리 — 앞뒤 공백 제거, 소문자화, 맨 앞 점 1개 제거. 백엔드 normalizeExt와 동일. */
export function normalizeExt(raw: string): string {
  return raw.trim().toLowerCase().replace(/^\./, "");
}

export type ExtCheck = { ok: true; ext: string } | { ok: false; reason: string };

/** 확장자 칩 추가 판정. 형식 위반·중복은 사유와 함께 거부한다(조용히 추가하지 않는다). */
export function checkExt(raw: string, existing: readonly string[] = []): ExtCheck {
  const ext = normalizeExt(raw);
  if (ext === "") return { ok: false, reason: "확장자를 입력하세요" };
  if (!EXT_RE.test(ext)) {
    return {
      ok: false,
      reason: `"${raw.trim()}" 는 확장자 형식이 아닙니다 — 점(.) 없이 영문 소문자·숫자 1~16자로 입력하세요`,
    };
  }
  if (existing.includes(ext)) return { ok: false, reason: `.${ext} 는 이미 추가돼 있습니다` };
  return { ok: true, ext };
}

/** 이미 저장돼 있던 값 중 형식 위반 — 어떤 파일과도 매치되지 않는 죽은 항목이라 화면에서 드러낸다. */
export function invalidExts(exts: readonly string[]): string[] {
  return exts.filter((e) => !EXT_RE.test(e));
}

export type MaxMbCheck =
  | { ok: false; reason: string }
  /** note가 있으면 입력값을 보정했다는 뜻 — 토스트에 그대로 실어 관리자가 눈치채게 한다. */
  | { ok: true; mb: number; note: string | null };

/** 최대 용량(MB) 판정 — 범위를 벗어나면 보정하되 보정 사실을 반드시 돌려준다. */
export function checkMaxMb(raw: string | number): MaxMbCheck {
  const text = String(raw).trim();
  if (text === "") return { ok: false, reason: "최대 용량을 입력하세요" };
  const n = Number(text);
  if (!Number.isFinite(n)) return { ok: false, reason: `"${text}" 는 숫자가 아닙니다` };
  const mb = Math.min(MAX_UPLOAD_MB, Math.max(1, Math.floor(n)));
  if (mb === n) return { ok: true, mb, note: null };
  return {
    ok: true,
    mb,
    note: `입력한 ${text}MB를 ${mb}MB로 보정했습니다 (허용 범위 1~${MAX_UPLOAD_MB}MB)`,
  };
}

/** 바이트 → 화면 표시용 MB(내림, 최소 1). 정책 로드 시 입력란 초기값. */
export function bytesToMb(bytes: number): number {
  return Math.min(MAX_UPLOAD_MB, Math.max(1, Math.floor(bytes / 1024 / 1024)));
}
