/* 공유 링크 API — 노트 앱(ShareModal)·share 앱(SharePage) 공용 단일 출처. */
import { req } from "./http";

export interface ShareLink {
  id: string;
  token: string;
  expiresAt: string;
  maxViews: number | null;
  viewCount: number;
  pinEmps: string[] | null;
  createdBy: string;
  createdAt: string;
}

export interface ShareView {
  name: string;
  content: string | null;   // 백엔드가 node.content를 그대로 반환 — 빈 노트는 null 가능
  updatedAt: string | null;
}

export interface CreateShareBody {
  days?: number;
  maxViews?: number;
  pinEmps?: string[];
}

const JSON_HEADERS = { "Content-Type": "application/json" };

export const ShareApi = {
  create: (nodeId: string, body: CreateShareBody) =>
    req<{ id: string; token: string; expiresAt: string }>(
      `/nodes/${encodeURIComponent(nodeId)}/share`,
      { method: "POST", body: JSON.stringify(body), headers: JSON_HEADERS }),
  listForNode: (nodeId: string) =>
    req<ShareLink[]>(`/nodes/${encodeURIComponent(nodeId)}/shares`),
  revoke: (id: string) =>
    req<void>(`/shares/${encodeURIComponent(id)}`, { method: "DELETE" }),
  view: (token: string) =>
    req<ShareView>(`/share/${encodeURIComponent(token)}`),
};

/** 링크 URL 조립 — 백엔드는 token만 반환(결정 S8). base "./" 배포라 현재 디렉토리 기준. */
export function shareUrl(token: string, origin = location.origin, pathname = location.pathname): string {
  return origin + pathname.replace(/[^/]*$/, "") + "share.html?token=" + encodeURIComponent(token);
}
