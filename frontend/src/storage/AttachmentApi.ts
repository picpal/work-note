/* AttachmentApi — 노트 첨부 업로드/삭제. 업로드는 multipart(reqForm), 삭제는 JSON 코어(req). */
import { req, reqForm } from "../api/http";

export interface UploadedAttachment { id: string; filename: string; size: number; url: string; }

export const AttachmentApi = {
  upload: (nodeId: string, file: File) => {
    const form = new FormData();
    form.append("file", file);
    return reqForm<UploadedAttachment>(`/nodes/${encodeURIComponent(nodeId)}/attachments`, form);
  },
  url: (id: string) => `/api/attachments/${encodeURIComponent(id)}`,
  remove: (id: string) => req<void>(`/attachments/${encodeURIComponent(id)}`, { method: "DELETE" }),
};
