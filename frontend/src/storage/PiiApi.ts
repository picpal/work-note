/* PiiApi — 노트 예외 요청 + 내 PII 알림. 공유 fetch 코어(req) 사용. */
import { req } from "../api/http";

export interface PiiNotice { id: number; kind: "flagged" | "approved" | "rejected"; message: string | null; noteId: string; noteTitle: string; }

export const PiiApi = {
  requestException: (nodeId: string, reason?: string) =>
    req<void>(`/nodes/${encodeURIComponent(nodeId)}/pii/exception`, { method: "POST", body: JSON.stringify({ reason: reason ?? null }) }),
  myNotices: () => req<PiiNotice[]>("/me/pii-notices"),
  ackNotices: (ids?: number[]) =>
    req<void>("/me/pii-notices/ack", { method: "POST", body: JSON.stringify(ids ? { ids } : {}) }),
};
