import type { TotpInfo } from "../api/auth";

/** 이메일이 있어야 2FA 등록 가능 (분실 시 이메일 복구 필요). */
export function canEnroll(t: TotpInfo): boolean {
  return t.emailPresent;
}

/** 등록 불가 사유 — 가능하면 null. */
export function enrollBlockReason(t: TotpInfo): string | null {
  return t.emailPresent
    ? null
    : "복구를 위해 먼저 프로필에 이메일을 등록하세요 — 이메일이 없으면 2FA 분실 시 복구할 수 없습니다.";
}

/** 강제 등록 즉시 필요: enforced + 유예 만료 + 미등록. */
export function mustEnrollNow(t: TotpInfo): boolean {
  return t.enforced && t.graceExpired && !t.enabled;
}

/** 등록 권고 (유예 기간 내): enforced + 미등록 + 유예 미만료. */
export function shouldNudge(t: TotpInfo): boolean {
  return t.enforced && !t.graceExpired && !t.enabled;
}
