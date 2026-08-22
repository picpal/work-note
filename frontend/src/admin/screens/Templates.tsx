/* Admin screen: Templates — 시스템 템플릿(owner_id NULL) CRUD. 편집 폼 옆에 마크다운 미리보기.
   시스템 템플릿은 전 사용자가 보므로 저장 전에 결과를 확인할 수 있어야 한다. Uploads.tsx의 run 패턴 계승. */
import React from "react";
import { AdminApi, type ApiSystemTemplate } from "../api";
import { ApiError } from "../../api/http";
import { SecHead } from "../common";
import { Icon } from "../../components/Icon";
import { renderMarkdown } from "../../lib/markdown";
import { validateTemplateName, validateTemplateBody } from "../../components/templateValidation";

const { useState, useEffect, useCallback } = React;
const h = React.createElement;

type Draft = { id: string | null; name: string; body: string } | null;

export function Templates({ toast }: { toast: (msg: string, icon?: string) => void }) {
  const [items, setItems] = useState<ApiSystemTemplate[]>([]);
  const [draft, setDraft] = useState<Draft>(null);
  const [busy, setBusy] = useState(false);

  const fail = useCallback((e: unknown) => {
    toast(e instanceof ApiError ? e.message : "오류가 발생했습니다");
  }, [toast]);

  const load = useCallback(async () => {
    try { setItems(await AdminApi.listTemplates()); } catch (e) { fail(e); }
  }, [fail]);

  useEffect(() => { void load(); }, [load]);

  const save = async () => {
    if (!draft || busy) return;
    const nameErr = validateTemplateName(draft.name);
    if (nameErr) { toast(nameErr); return; }
    const bodyErr = validateTemplateBody(draft.body);
    if (bodyErr) { toast(bodyErr); return; }
    setBusy(true);
    try {
      if (draft.id) await AdminApi.updateTemplate(draft.id, draft.name.trim(), draft.body);
      else await AdminApi.createTemplate(draft.name.trim(), draft.body);
      setDraft(null);
      await load();
      toast("저장했습니다", "check");
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const remove = async (t: ApiSystemTemplate) => {
    if (busy) return;
    if (!window.confirm(`'${t.name}' 시스템 템플릿을 삭제할까요? 모든 사용자에게서 사라집니다.`)) return;
    setBusy(true);
    try {
      await AdminApi.deleteTemplate(t.id);
      if (draft?.id === t.id) setDraft(null);
      await load();
      toast("삭제했습니다", "check");
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  return h("div", { className: "apage" },
    h(SecHead, { title: "템플릿", hint: "모든 사용자가 쓰는 시스템 양식",
      right: h("button", {
        className: "btn primary", disabled: busy,
        onClick: () => setDraft({ id: null, name: "", body: "## 제목\n- \n" }),
      }, h(Icon, { name: "plus" }), "추가") }),

    h("div", { className: "changebar", style: { position: "static", marginTop: 0, marginBottom: 16 } },
      h(Icon, { name: "info" }),
      h("span", { className: "txt" },
        "시스템 템플릿은 ", h("b", null, "로그인한 모든 사용자"), "에게 보입니다. 기밀 정보를 넣지 마세요.")),

    h("div", { className: "panel" },
      h("div", { className: "panel-head" }, h(Icon, { name: "clipboard" }), "시스템 템플릿"),
      h("div", { className: "panel-body" },
        items.length === 0
          ? h("div", { style: { fontSize: 12.5, color: "var(--text-3)" } }, "등록된 시스템 템플릿이 없습니다.")
          : items.map((t) => h("div", {
              key: t.id,
              style: { display: "flex", alignItems: "center", gap: 8, padding: "7px 0",
                       borderBottom: "1px solid var(--border)" },
            },
              h("span", { style: { flex: 1, fontSize: 13 } }, t.name),
              h("button", { className: "btn", onClick: () => setDraft({ id: t.id, name: t.name, body: t.body }) }, "편집"),
              h("button", { className: "btn", onClick: () => void remove(t) }, "삭제"))))),

    draft && h("div", { className: "panel", style: { marginTop: 16 } },
      h("div", { className: "panel-head" },
        h(Icon, { name: "edit" }), draft.id ? "템플릿 편집" : "새 템플릿"),
      h("div", { className: "panel-body" },
        h("input", {
          className: "tinput", value: draft.name, placeholder: "템플릿 이름 (예: 회의록)",
          style: { maxWidth: 320, marginBottom: 12 },
          onChange: (e: React.ChangeEvent<HTMLInputElement>) => setDraft({ ...draft, name: e.target.value }),
        }),
        h("div", { style: { display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12, alignItems: "stretch" } },
          h("textarea", {
            className: "tinput", value: draft.body, rows: 18,
            style: { resize: "vertical", lineHeight: 1.6, fontFamily: "var(--font-mono)" },
            onChange: (e: React.ChangeEvent<HTMLTextAreaElement>) => setDraft({ ...draft, body: e.target.value }),
          }),
          h("div", {
            className: "md",
            style: { border: "1px solid var(--border)", borderRadius: "var(--radius)", padding: 12, overflow: "auto" },
            dangerouslySetInnerHTML: { __html: renderMarkdown(draft.body) },
          })),
        h("div", { style: { display: "flex", gap: 8, marginTop: 12 } },
          h("button", { className: "btn primary", disabled: busy, onClick: () => void save() },
            h(Icon, { name: "check" }), "저장"),
          h("button", { className: "btn", onClick: () => setDraft(null) }, "취소")))));
}
