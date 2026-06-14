/* AttachmentApi — 노트 첨부 업로드/삭제. 업로드는 multipart(reqForm), 삭제는 JSON 코어(req). */
import { req, reqForm } from "../api/http";

export interface UploadedAttachment { id: string; filename: string; size: number; url: string; }
/** 첨부 목록 메타 — 다운로드 url은 백엔드가 맥락(일반/공유)에 맞춰 내려준다. */
export interface AttachmentMeta { id: string; filename: string; size: number; mime: string; image: boolean; url: string; }

export const AttachmentApi = {
  upload: (nodeId: string, file: File) => {
    const form = new FormData();
    form.append("file", file);
    return reqForm<UploadedAttachment>(`/nodes/${encodeURIComponent(nodeId)}/attachments`, form);
  },
  list: (nodeId: string) => req<AttachmentMeta[]>(`/nodes/${encodeURIComponent(nodeId)}/attachments`),
  listShare: (token: string) => req<AttachmentMeta[]>(`/share/${encodeURIComponent(token)}/attachments`),
  url: (id: string) => `/api/attachments/${encodeURIComponent(id)}`,
  remove: (id: string) => req<void>(`/attachments/${encodeURIComponent(id)}`, { method: "DELETE" }),
};
