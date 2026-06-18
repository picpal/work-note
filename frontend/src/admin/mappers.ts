/* 백엔드 enum/코드 → 한국어 라벨 매퍼. 미지 값은 원문 그대로 노출(데이터 손실 방지). */
import type { ApiRole, ApiUser } from "./api";

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

const ACTS: Record<string, string> = {
  "login.success": "로그인", "login.fail": "로그인 실패", logout: "로그아웃",
  signup: "가입 신청", "signup.fail": "가입 실패", "auth.password.change": "비밀번호 변경", "auth.profile.update": "프로필 수정",
  "user.create": "사용자 생성", "user.update": "사용자 변경", "user.approve": "계정 승인", "user.reset": "비밀번호 초기화",
  "role.create": "역할 생성", "role.update": "역할 변경", "role.delete": "역할 삭제",
  "team.create": "팀 생성", "team.update": "팀 변경", "team.delete": "팀 삭제",
  "team.member.add": "팀원 추가", "team.member.remove": "팀원 제외",
  "acl.set": "권한 설정", "public.set": "공개 설정", "public.unset": "공개 해제",
  "space.set": "스페이스 지정", "space.unset": "스페이스 해제",
  "node.create": "노드 생성", "node.move": "노드 이동", "node.trash": "휴지통 이동",
  "node.restore": "복구", "node.purge": "영구 삭제",
  "note.export": "내보내기", "attachment.download": "첨부 다운로드",
  "share.create": "공유 링크 생성", "share.view": "공유 링크 열람", "share.revoke": "공유 링크 취소",
};
export function actLabel(act: string): string { return ACTS[act] ?? act; }

/** 감사 필터 드롭다운용 act 34종 — canonical 순서(인증 → user → role → team → acl/public/space → node → 다운로드 → share). ACTS 라벨 맵과 정합은 테스트로 가드. */
export const KNOWN_ACTS: string[] = [
  "login.success", "login.fail", "logout", "signup", "signup.fail", "auth.password.change", "auth.profile.update",
  "user.create", "user.update", "user.approve", "user.reset",
  "role.create", "role.update", "role.delete",
  "team.create", "team.update", "team.delete", "team.member.add", "team.member.remove",
  "acl.set", "public.set", "public.unset", "space.set", "space.unset",
  "node.create", "node.move", "node.trash", "node.restore", "node.purge",
  "note.export", "attachment.download",
  "share.create", "share.view", "share.revoke",
];

/** Audit 화면 배지 색 분류 — 기존 mock 클래스(login/grant/approve/reset/revoke/loginfail) + 폴백 "etc"(Audit.tsx는 loginfail만 색 특수처리라 미지 클래스 무해). */
export function actType(act: string): string {
  if (act.endsWith(".fail")) return "loginfail";
  if (act === "login.success") return "login";
  if (act === "user.approve") return "approve";
  if (act === "user.reset") return "reset";
  if (act === "acl.set" || act.startsWith("public.") || act.startsWith("space.") || act === "share.create") return "grant";
  if (act === "role.delete" || act === "team.member.remove" || act === "share.revoke") return "revoke";
  return "etc";
}

export function roleName(roleId: string, roles: ApiRole[]): string {
  return roles.find((r) => r.id === roleId)?.name ?? roleId;
}
