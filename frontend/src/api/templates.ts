/* 노트 템플릿 API — 시스템(읽기 전용) + 개인 템플릿. 공유 fetch 코어(req) 사용. */
import { req } from "./http";

export interface ApiTemplate {
  id: string;
  name: string;
  body: string;
  /** true = 시스템 템플릿(관리자만 편집). 사용자 모달에서는 읽기 전용. */
  system: boolean;
}

export const TemplateApi = {
  list: () => req<ApiTemplate[]>("/templates"),
  create: (name: string, body: string) =>
    req<ApiTemplate>("/templates", { method: "POST", body: JSON.stringify({ name, body }) }),
  update: (id: string, name: string, body: string) =>
    req<ApiTemplate>(`/templates/${id}`, { method: "PUT", body: JSON.stringify({ name, body }) }),
  remove: (id: string) => req<void>(`/templates/${id}`, { method: "DELETE" }),
};
