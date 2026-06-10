/* Admin seed data — users, pending signups, roles, audit log, permission tree. */

export interface AdminTreeNode {
  id: string;
  type: "folder" | "note";
  name: string;
  children?: AdminTreeNode[];
  public?: boolean;
}

export interface Grant {
  read?: boolean;
  edit?: boolean;
  override?: boolean;
  inheritedRead?: boolean;
  inheritedEdit?: boolean;
}

export interface PendingRow {
  id: string;
  emp: string;
  email: string;
  at: string;
  status: string;
}

export interface AdminUser {
  id: string;
  emp: string;
  email: string;
  role: string;
  status: string;
  last: string;
}

export interface Role {
  id: string;
  name: string;
  system: boolean;
  count: number;
  desc: string;
  policy: string[];
}

export interface AuditRow {
  at: string;
  who: string;
  act: string;
  actType: string;
  target: string;
  ip: string;
}

export interface Security {
  pwMinLen: number;
  pwComplexity: boolean;
  pwRotateDays: number;
  lockAttempts: number;
  sessionTimeout: number;
  requireApproval: boolean;
}

export interface Me {
  emp: string;
  role: string;
}

// pending signups (awaiting admin approval; closed-network: approval activates account)
export const ADMIN_PENDING: PendingRow[] = [
  { id: "p1", emp: "S2026-0142", email: "kim.jh@corp.local", at: "2026-06-09 09:12", status: "대기" },
  { id: "p2", emp: "S2026-0143", email: "park.sy@corp.local", at: "2026-06-09 08:47", status: "대기" },
  { id: "p3", emp: "S2026-0144", email: "lee.dh@corp.local", at: "2026-06-08 17:30", status: "대기" },
];

// users
export const ADMIN_USERS: AdminUser[] = [
  { id: "u1", emp: "S2019-0007", email: "admin@corp.local", role: "관리자", status: "활성", last: "2026-06-09 09:40" },
  { id: "u2", emp: "S2021-0231", email: "oh.운영@corp.local", role: "운영자", status: "활성", last: "2026-06-09 08:55" },
  { id: "u3", emp: "S2022-0588", email: "jung.mk@corp.local", role: "운영자", status: "활성", last: "2026-06-08 18:21" },
  { id: "u4", emp: "S2023-0912", email: "choi.ys@corp.local", role: "방문자", status: "활성", last: "2026-06-07 11:03" },
  { id: "u5", emp: "S2024-0410", email: "han.jw@corp.local", role: "방문자", status: "비활성", last: "2026-05-30 14:12" },
  { id: "u6", emp: "S2025-0077", email: "seo.hy@corp.local", role: "방문자", status: "대기", last: "—" },
  { id: "u7", emp: "S2020-0150", email: "yoon.ts@corp.local", role: "운영자", status: "활성", last: "2026-06-09 07:30" },
];

export const ADMIN_ROLES: Role[] = [
  { id: "visitor", name: "방문자", system: true, count: 3,
    desc: "기본 역할. 공개(Public)로 지정된 노트만 열람할 수 있습니다. 별도 권한이 부여되기 전까지 비공개 리소스에 접근할 수 없습니다.",
    policy: ["공개 노트 열람", "본인 프로필 조회"] },
  { id: "operator", name: "운영자", system: false, count: 3,
    desc: "부여된 폴더·노트에 대해 읽기 또는 읽기+편집 권한으로 작업합니다. 권한이 없는 리소스는 보이지 않습니다.",
    policy: ["부여된 노트 열람/편집", "다이어그램·코드 작성", "내보내기"] },
  { id: "admin", name: "관리자", system: true, count: 1,
    desc: "사용자·권한·역할·보안 설정을 관리하고 감사 로그를 조회합니다. 모든 리소스에 접근할 수 있습니다.",
    policy: ["전체 리소스 접근", "사용자·권한 관리", "감사 로그 조회", "보안 정책 설정"] },
];

export const ADMIN_AUDIT: AuditRow[] = [
  { at: "2026-06-09 09:40:11", who: "S2019-0007", act: "로그인", actType: "login", target: "—", ip: "10.12.4.21" },
  { at: "2026-06-09 09:38:02", who: "S2019-0007", act: "권한 부여", actType: "grant", target: "S2022-0588 · 아키텍처/ (읽기+편집)", ip: "10.12.4.21" },
  { at: "2026-06-09 09:21:55", who: "S2019-0007", act: "계정 승인", actType: "approve", target: "S2025-0077", ip: "10.12.4.21" },
  { at: "2026-06-09 08:55:13", who: "S2021-0231", act: "로그인", actType: "login", target: "—", ip: "10.12.4.55" },
  { at: "2026-06-08 18:40:09", who: "S2019-0007", act: "비밀번호 초기화", actType: "reset", target: "S2024-0410", ip: "10.12.4.21" },
  { at: "2026-06-08 18:22:31", who: "S2019-0007", act: "권한 회수", actType: "revoke", target: "S2023-0912 · 운영 가이드/ (편집)", ip: "10.12.4.21" },
  { at: "2026-06-08 17:31:48", who: "S2019-0007", act: "계정 비활성화", actType: "deactivate", target: "S2024-0410", ip: "10.12.4.21" },
  { at: "2026-06-08 14:02:17", who: "S2020-0150", act: "로그인 실패", actType: "loginfail", target: "—", ip: "10.12.7.13" },
];

// permission tree (mirrors the note vault); perms keyed by userId → nodeId → {read, edit, override}
export const ADMIN_TREE: AdminTreeNode[] = [
  { id: "f-start", type: "folder", name: "시작하기", children: [
    { id: "n-onboard", type: "note", name: "온보딩 가이드" },
  ]},
  { id: "f-arch", type: "folder", name: "아키텍처", children: [
    { id: "n-pipe", type: "note", name: "결제 파이프라인" },
    { id: "n-approve", type: "note", name: "승인 연동 시퀀스" },
  ]},
  { id: "f-ops", type: "folder", name: "운영 가이드", children: [
    { id: "n-codes", type: "note", name: "응답코드" },
    { id: "f-incident", type: "folder", name: "장애 대응", children: [] },
  ]},
  { id: "f-meet", type: "folder", name: "회의록", children: [
    { id: "n-week", type: "note", name: "2026-06-08 주간 회의" },
  ]},
  { id: "n-readme", type: "note", name: "README", public: true },
];

// example grants for the operator we preselect (S2022-0588)
export const ADMIN_GRANTS: Record<string, Record<string, Grant>> = {
  u3: {
    "f-arch": { read: true, edit: true },          // folder grant → inherited
    "n-approve": { read: true, edit: false, override: true }, // note-level override
    "f-ops": { read: true, edit: false },
  },
};

export const ADMIN_PUBLIC: Record<string, boolean> = { "n-readme": true };

export const ADMIN_SECURITY: Security = {
  pwMinLen: 10,
  pwComplexity: true,
  pwRotateDays: 90,
  lockAttempts: 5,
  sessionTimeout: 30,
  requireApproval: true,
};

export const ADMIN_ME: Me = { emp: "S2019-0007", role: "관리자" };
