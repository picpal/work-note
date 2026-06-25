import { req } from "./http";

export interface RedmineStatus { enabled: boolean; tokenPresent: boolean; redmineLogin: string | null; lastVerifiedAt: string | null; }
export interface RedmineIssueSummary { id: number; subject: string; statusName: string; assignedToName: string | null; projectName: string; updatedOn: string; }
export interface RedmineComment { userName: string; createdOn: string; notes: string; }
export interface RedmineIssueDetail extends RedmineIssueSummary { description: string; priorityName: string | null; dueDate: string | null; comments: RedmineComment[]; }

export interface RedmineSearchParams { query?: string; assignedToMe?: boolean; statusId?: string; projectId?: string; offset?: number; limit?: number; }

function qs(p: RedmineSearchParams): string {
  const s = new URLSearchParams();
  if (p.query) s.set("query", p.query);
  if (p.assignedToMe) s.set("assignedToMe", "true");
  if (p.statusId) s.set("statusId", p.statusId);
  if (p.projectId) s.set("projectId", p.projectId);
  if (p.offset) s.set("offset", String(p.offset));
  if (p.limit) s.set("limit", String(p.limit));
  return s.toString();
}

export const RedmineApi = {
  status: () => req<RedmineStatus>("/me/redmine"),
  setToken: (token: string) => req<RedmineStatus>("/me/redmine/token", { method: "PUT", body: JSON.stringify({ token }) }),
  deleteToken: () => req<void>("/me/redmine/token", { method: "DELETE" }),
  search: (p: RedmineSearchParams) => req<{ issues: RedmineIssueSummary[] }>(`/redmine/issues?${qs(p)}`),
  get: (id: number) => req<RedmineIssueDetail>(`/redmine/issues/${id}`),
};
