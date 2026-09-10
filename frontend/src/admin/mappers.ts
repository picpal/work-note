/* 백엔드 enum/코드 → 한국어 라벨 매퍼. 미지 값은 원문 그대로 노출(데이터 손실 방지). */
import type { ApiRole, ApiUser } from "./api";
import { AUDIT_ACTS, auditActDef } from "./auditActs";

const STATUS: Record<ApiUser["status"], string> = { active: "활성", disabled: "비활성", pending: "대기" };
export function statusLabel(s: ApiUser["status"]): string { return STATUS[s] ?? s; }

const CAPS: Record<string, string> = {
  "admin.users": "사용자 관리", "admin.permissions": "권한 관리", "admin.roles": "역할 관리",
  "admin.security": "보안 설정", "admin.audit": "감사 로그 조회",
  "res.read": "노트 열람", "res.edit": "노트 편집", "res.create": "노트 생성",
  "res.delete": "노트 삭제", "res.export": "내보내기", "res.share": "공유",
};
export function capLabel(cap: string): string { return CAPS[cap] ?? cap; }

/** 백엔드가 인정하는 cap 11종 — canonical 순서(admin.* 5종 → res.* 6종). CAPS 라벨 맵과 정합은 테스트로 가드. */
export const KNOWN_CAPS: string[] = [
  "admin.users", "admin.permissions", "admin.roles", "admin.security", "admin.audit",
  "res.read", "res.edit", "res.create", "res.delete", "res.export", "res.share",
];

/** 라벨 미정의 act는 원문을 보여주되 "미등록"을 붙인다 — 관리자가 정상 라벨로 오독하지 않도록.
    (근본 방어는 backendSync.test.ts의 백엔드 소스 스캔 — 여기 도달하면 이미 목록이 밀린 것.) */
export function actLabel(act: string): string {
  const def = auditActDef(act);
  return def ? def.label : act + " (미등록 행위)";
}

/** 감사 필터 드롭다운용 act 전수 — auditActs.ts 단일 출처에서 파생(백엔드 스캔으로 정합 강제). */
export const KNOWN_ACTS: string[] = AUDIT_ACTS.map((d) => d.act);

/** Audit 화면 배지 색 분류 — 클래스(login/grant/approve/reset/revoke/loginfail) + 폴백 "etc".
    등록된 act는 auditActs.ts의 tone을 그대로 쓰고, 미등록 act만 명명 규칙으로 추정한다. */
export function actType(act: string): string {
  const def = auditActDef(act);
  if (def) return def.tone ?? "etc";
  if (act.endsWith(".fail")) return "loginfail";
  return "etc";
}

export function roleName(roleId: string, roles: ApiRole[]): string {
  return roles.find((r) => r.id === roleId)?.name ?? roleId;
}

const REDMINE_STATUS: Record<string, string> = {
  "New": "신규", "In Progress": "진행 중", "Resolved": "해결", "Closed": "완료", "Rejected": "거부",
};
export function redmineStatusLabel(s: string): string { return REDMINE_STATUS[s] ?? s; }
