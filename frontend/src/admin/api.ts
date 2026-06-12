/* 관리자 API 클라이언트 — /api/admin 하위 전체 엔드포인트. 공유 fetch 코어(req) 사용. */
import { req } from "../api/http";

export interface ApiUser { id: string; emp: string; email: string | null; name: string; roleId: string; status: "pending" | "active" | "disabled"; lastLogin: string | null; }
export interface ApiRole { id: string; name: string; system: boolean; caps: string[]; userCount: number; }
export interface ApiTeam { id: string; name: string; members: ApiUser[]; }
export interface ApiSpace { nodeId: string; teamId: string | null; }
export interface ApiAclEntry { principalType: "user" | "team" | "all"; principalId: string; grantType: "read" | "edit" | "deny"; }
export interface ApiAclRow extends ApiAclEntry { nodeId: string; }
export interface ApiPublicFlag { nodeId: string; mode: "public" | "exclude"; }
export interface ApiAudit { id: number; at: string; who: string; act: string; target: string | null; ip: string; }
export interface ApiShare {
  id: string;
  token: string;
  nodeId: string;
  nodeName: string;
  suspended: boolean;
  createdBy: string;
  createdAt: string;
  expiresAt: string;
  maxViews: number | null;
  viewCount: number;
  pinEmps: string[] | null;
}
export interface AuditQuery { who?: string; act?: string; from?: string; to?: string; limit?: number; offset?: number; }

/** 빈 문자열/undefined 필터는 쿼리에서 생략 — 백엔드가 빈 값을 필터로 오해하지 않게. */
function qs(params: Record<string, string | number | undefined>): string {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== "") p.set(k, String(v));
  }
  const s = p.toString();
  return s ? "?" + s : "";
}

export const AdminApi = {
  users: () => req<ApiUser[]>("/admin/users"),
  createUser: (b: { emp: string; name: string; email?: string; roleId: string; password: string }) =>
    req<ApiUser>("/admin/users", { method: "POST", body: JSON.stringify(b) }),
  updateUser: (id: string, patch: { name?: string; email?: string; roleId?: string; status?: "active" | "disabled" }) =>
    req<ApiUser>(`/admin/users/${id}`, { method: "PATCH", body: JSON.stringify(patch) }),
  approveUser: (id: string) => req<ApiUser>(`/admin/users/${id}/approve`, { method: "POST" }),
  resetPassword: (id: string, password: string) =>
    req<void>(`/admin/users/${id}/reset-password`, { method: "POST", body: JSON.stringify({ password }) }),

  roles: () => req<ApiRole[]>("/admin/roles"),
  createRole: (b: { id: string; name: string; caps: string[] }) =>
    req<ApiRole>("/admin/roles", { method: "POST", body: JSON.stringify(b) }),
  updateRole: (id: string, patch: { name?: string; caps?: string[] }) =>
    req<ApiRole>(`/admin/roles/${id}`, { method: "PATCH", body: JSON.stringify(patch) }),
  deleteRole: (id: string) => req<void>(`/admin/roles/${id}`, { method: "DELETE" }),

  teams: () => req<ApiTeam[]>("/admin/teams"),
  createTeam: (name: string) => req<{ id: string; name: string }>("/admin/teams", { method: "POST", body: JSON.stringify({ name }) }),
  renameTeam: (id: string, name: string) => req<void>(`/admin/teams/${id}`, { method: "PATCH", body: JSON.stringify({ name }) }),
  deleteTeam: (id: string) => req<void>(`/admin/teams/${id}`, { method: "DELETE" }),
  addMember: (teamId: string, userId: string) =>
    req<void>(`/admin/teams/${teamId}/members`, { method: "POST", body: JSON.stringify({ userId }) }),
  removeMember: (teamId: string, userId: string) =>
    req<void>(`/admin/teams/${teamId}/members/${userId}`, { method: "DELETE" }),

  spaces: () => req<ApiSpace[]>("/admin/spaces"),
  setSpace: (nodeId: string, teamId: string | null) =>
    req<void>(`/admin/spaces/${nodeId}`, { method: "PUT", body: JSON.stringify({ teamId }) }),
  unsetSpace: (nodeId: string) => req<void>(`/admin/spaces/${nodeId}`, { method: "DELETE" }),

  aclAll: () => req<ApiAclRow[]>("/admin/acl"),
  aclForNode: (nodeId: string) => req<ApiAclRow[]>(`/admin/nodes/${nodeId}/acl`),
  setAcl: (nodeId: string, entries: ApiAclEntry[]) =>
    req<void>(`/admin/nodes/${nodeId}/acl`, { method: "PUT", body: JSON.stringify({ entries }) }),
  publicFlags: () => req<ApiPublicFlag[]>("/admin/public"),
  setPublic: (nodeId: string, mode: "public" | "exclude") =>
    req<void>(`/admin/nodes/${nodeId}/public`, { method: "PUT", body: JSON.stringify({ mode }) }),
  unsetPublic: (nodeId: string) => req<void>(`/admin/nodes/${nodeId}/public`, { method: "DELETE" }),

  shares: () => req<ApiShare[]>("/admin/shares"),
  revokeShare: (id: string) => req<void>(`/shares/${encodeURIComponent(id)}`, { method: "DELETE" }),

  audit: (q: AuditQuery) => req<{ total: number; rows: ApiAudit[] }>("/admin/audit" + qs(q as Record<string, string | number | undefined>)),
};
