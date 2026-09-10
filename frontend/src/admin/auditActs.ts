/* 감사 행위 코드 단일 출처.

   백엔드가 audit.log/logRaw로 실제 기록하는 act 전수를 한 배열에 모아두고,
   감사 화면의 한글 라벨(mappers.actLabel) · 필터 드롭다운(mappers.KNOWN_ACTS) ·
   배지 색(mappers.actType) · 월간 리포트의 관리자 작업 분류(auditReport.isAdminAction)가
   전부 여기서 파생된다. 예전에는 라벨 맵과 필터 목록을 각각 손으로 나열해 서로 어긋났고,
   백엔드에만 있는 25종은 관리자 화면에 원시 코드로 샜다.

   백엔드와의 정합은 backendSync.test.ts가 backend/src/main/java 전량을 스캔해 강제한다
   (양방향 — 백엔드에만 있는 코드도, 여기에만 있는 유령 코드도 실패). 새 act를 추가하면
   그 테스트가 빨개지고, 여기에 한 줄 넣는 것으로 라벨·필터·분류가 동시에 따라온다. */

/** 필터 드롭다운의 optgroup 겸 canonical 정렬 단위. */
export type AuditActGroup =
  | "auth" | "twofa" | "user" | "role" | "team" | "perm" | "node"
  | "view" | "attachment" | "share" | "pii" | "settings" | "template";

/** Audit 화면 배지 클래스. 미지정 = "etc"(무채색). */
export type AuditActTone = "login" | "loginfail" | "approve" | "reset" | "grant" | "revoke";

export interface AuditActDef {
  /** 백엔드가 기록하는 act 코드 그대로. */
  act: string;
  /** 감사 화면·리포트에 노출되는 한글 라벨. */
  label: string;
  group: AuditActGroup;
  /** 실패·잠금은 적색, 승인/회수 등은 배지 분류. 없으면 무채색. */
  tone?: AuditActTone;
  /**
   * 월간 감사 리포트 §3 "관리자 작업" 집계 대상.
   * 기준: 관리자 권한(admin.*) 없이는 수행할 수 없는 행위 — 본인이 하는 self-service는 제외한다.
   * (예: 2fa.enabled는 사용자가 자기 계정에 켜는 것이라 관리자 작업이 아니고,
   *  2fa.admin.reset은 관리자가 남의 2FA를 초기화하는 것이라 관리자 작업이다.)
   */
  admin?: true;
}

export const AUDIT_ACT_GROUP_LABEL: Record<AuditActGroup, string> = {
  auth: "인증 · 세션",
  twofa: "2단계 인증(2FA)",
  user: "계정 관리",
  role: "역할",
  team: "팀",
  perm: "권한 · 공개",
  node: "노드",
  view: "조회 · 내보내기",
  attachment: "첨부",
  share: "공유 링크",
  pii: "개인정보",
  settings: "설정 · 연동",
  template: "노트 템플릿",
};

/** 백엔드 기록 act 전수 67종 — 그룹 순서 = 필터 드롭다운 순서. */
export const AUDIT_ACTS: readonly AuditActDef[] = [
  // ── 인증 · 세션 ────────────────────────────────────────────────────────
  { act: "login.success", label: "로그인", group: "auth", tone: "login" },
  { act: "login.fail", label: "로그인 실패", group: "auth", tone: "loginfail" },
  { act: "login.locked", label: "로그인 차단(잠금 중)", group: "auth", tone: "loginfail" },
  { act: "auth.lockout", label: "계정 잠금 발동", group: "auth", tone: "loginfail" },
  { act: "logout", label: "로그아웃", group: "auth" },
  { act: "signup", label: "가입 신청", group: "auth" },
  { act: "signup.fail", label: "가입 실패", group: "auth", tone: "loginfail" },
  { act: "auth.password.change", label: "비밀번호 변경", group: "auth" },
  { act: "auth.profile.update", label: "프로필 수정", group: "auth" },
  { act: "auth.break_glass", label: "최후 복구(브레이크글래스)", group: "auth", tone: "loginfail", admin: true },

  // ── 2단계 인증 ─────────────────────────────────────────────────────────
  { act: "2fa.setup", label: "2FA 등록 시작", group: "twofa" },
  { act: "2fa.enabled", label: "2FA 사용 설정", group: "twofa" },
  { act: "2fa.disabled", label: "2FA 해제", group: "twofa", tone: "revoke" },
  { act: "2fa.disable.blocked", label: "2FA 해제 거부(의무 대상)", group: "twofa", tone: "loginfail" },
  { act: "2fa.grace_start", label: "2FA 유예 시작", group: "twofa" },
  { act: "2fa.challenge", label: "2FA 인증 요구", group: "twofa" },
  { act: "2fa.verify.success", label: "2FA 인증 성공", group: "twofa", tone: "login" },
  { act: "2fa.verify.fail", label: "2FA 인증 실패", group: "twofa", tone: "loginfail" },
  { act: "2fa.locked", label: "2FA 차단(잠금 중)", group: "twofa", tone: "loginfail" },
  { act: "2fa.recover.request", label: "2FA 복구 요청", group: "twofa" },
  { act: "2fa.recover.success", label: "2FA 복구 성공", group: "twofa" },
  { act: "2fa.recover.fail", label: "2FA 복구 실패", group: "twofa", tone: "loginfail" },
  { act: "recover.locked", label: "2FA 복구 차단(잠금 중)", group: "twofa", tone: "loginfail" },
  { act: "2fa.admin.reset", label: "2FA 관리자 초기화", group: "twofa", tone: "reset", admin: true },

  // ── 계정 관리 ──────────────────────────────────────────────────────────
  { act: "user.create", label: "사용자 생성", group: "user", admin: true },
  { act: "user.update", label: "사용자 변경", group: "user", admin: true },
  { act: "user.approve", label: "계정 승인", group: "user", tone: "approve", admin: true },
  { act: "user.reset", label: "비밀번호 초기화", group: "user", tone: "reset", admin: true },

  // ── 역할 ───────────────────────────────────────────────────────────────
  { act: "role.create", label: "역할 생성", group: "role", admin: true },
  { act: "role.update", label: "역할 변경", group: "role", admin: true },
  { act: "role.delete", label: "역할 삭제", group: "role", tone: "revoke", admin: true },

  // ── 팀 ─────────────────────────────────────────────────────────────────
  { act: "team.create", label: "팀 생성", group: "team", admin: true },
  { act: "team.update", label: "팀 변경", group: "team", admin: true },
  { act: "team.delete", label: "팀 삭제", group: "team", tone: "revoke", admin: true },
  { act: "team.member.add", label: "팀원 추가", group: "team", admin: true },
  { act: "team.member.remove", label: "팀원 제외", group: "team", tone: "revoke", admin: true },

  // ── 권한 · 공개 ────────────────────────────────────────────────────────
  { act: "acl.set", label: "권한 설정", group: "perm", tone: "grant", admin: true },
  { act: "public.set", label: "공개 설정", group: "perm", tone: "grant", admin: true },
  { act: "public.unset", label: "공개 해제", group: "perm", tone: "grant", admin: true },
  { act: "space.set", label: "스페이스 지정", group: "perm", tone: "grant", admin: true },
  { act: "space.unset", label: "스페이스 해제", group: "perm", tone: "grant", admin: true },

  // ── 노드 ───────────────────────────────────────────────────────────────
  { act: "node.create", label: "노드 생성", group: "node" },
  { act: "node.move", label: "노드 이동", group: "node" },
  { act: "node.trash", label: "휴지통 이동", group: "node" },
  { act: "node.restore", label: "복구", group: "node" },
  // 관리자 수동 purge와 30일 자동 purge(who=system)가 같은 코드를 쓴다 — 둘 다 관리 행위로 집계.
  { act: "node.purge", label: "영구 삭제", group: "node", tone: "revoke", admin: true },

  // ── 조회 · 내보내기 ────────────────────────────────────────────────────
  { act: "note.view", label: "노트 조회", group: "view" },
  { act: "note.export", label: "내보내기", group: "view" },

  // ── 첨부 ───────────────────────────────────────────────────────────────
  { act: "attachment.add", label: "첨부 추가", group: "attachment" },
  { act: "attachment.remove", label: "첨부 삭제", group: "attachment", tone: "revoke" },
  { act: "attachment.download", label: "첨부 다운로드", group: "attachment" },

  // ── 공유 링크 ──────────────────────────────────────────────────────────
  { act: "share.create", label: "공유 링크 생성", group: "share", tone: "grant" },
  { act: "share.view", label: "공유 링크 열람", group: "share" },
  { act: "share.revoke", label: "공유 링크 취소", group: "share", tone: "revoke" },

  // ── 개인정보 ───────────────────────────────────────────────────────────
  // 신청(request)만 일반 사용자 경로, 나머지 4종은 관리자 점검 화면(AdminGuard) 전용.
  { act: "pii.request", label: "개인정보 예외 신청", group: "pii" },
  { act: "pii.approve", label: "개인정보 예외 승인", group: "pii", tone: "approve", admin: true },
  { act: "pii.reject", label: "개인정보 예외 반려", group: "pii", tone: "revoke", admin: true },
  { act: "pii.notice", label: "개인정보 조치 통보", group: "pii", admin: true },
  { act: "pii.view", label: "개인정보 열람", group: "pii", admin: true },

  // ── 설정 · 연동 ────────────────────────────────────────────────────────
  { act: "settings.upload", label: "업로드 정책 변경", group: "settings", admin: true },
  { act: "settings.redmine", label: "Redmine 연동 설정", group: "settings", admin: true },
  { act: "redmine.import", label: "Redmine 이슈 가져오기", group: "settings" },
  { act: "redmine.token.set", label: "Redmine 토큰 등록", group: "settings" },
  { act: "redmine.token.delete", label: "Redmine 토큰 삭제", group: "settings", tone: "revoke" },

  // ── 노트 템플릿 ────────────────────────────────────────────────────────
  { act: "template.system.create", label: "시스템 템플릿 생성", group: "template", admin: true },
  { act: "template.system.update", label: "시스템 템플릿 변경", group: "template", admin: true },
  { act: "template.system.delete", label: "시스템 템플릿 삭제", group: "template", tone: "revoke", admin: true },
];

const BY_ACT: ReadonlyMap<string, AuditActDef> = new Map(AUDIT_ACTS.map((d) => [d.act, d]));

export function auditActDef(act: string): AuditActDef | undefined {
  return BY_ACT.get(act);
}

/** 필터 드롭다운을 optgroup으로 묶기 위한 그룹별 분해(등록 순서 유지). */
export function auditActsByGroup(): Array<{ group: AuditActGroup; label: string; acts: AuditActDef[] }> {
  const out: Array<{ group: AuditActGroup; label: string; acts: AuditActDef[] }> = [];
  for (const d of AUDIT_ACTS) {
    const last = out[out.length - 1];
    if (last && last.group === d.group) last.acts.push(d);
    else out.push({ group: d.group, label: AUDIT_ACT_GROUP_LABEL[d.group], acts: [d] });
  }
  return out;
}
