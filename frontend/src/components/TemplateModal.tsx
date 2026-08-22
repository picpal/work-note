/* TemplateModal — 노트 템플릿 목록·미리보기·삽입. RedmineImportPanel의 분할 모달을 축소 재사용.
   JSX 절대 미사용 — h = createElement 관례. */
import React, { useState, useEffect, useCallback } from "react";
import { TemplateApi, type ApiTemplate } from "../api/templates";
import { ApiError } from "../api/http";
import { groupTemplates, canEdit, wrapForInsert } from "./templateList";
import { validateTemplateName, validateTemplateBody } from "./templateValidation";
import { renderMarkdown } from "../lib/markdown";
import { useEscClose } from "../state/useEscClose";
import { Icon } from "./Icon";

const h = React.createElement;

interface Props {
  /** 노트에 삽입 — 열린 노트가 있으면 true. App이 cm.insertAtCursor로 연결한다. */
  onInsert: (md: string) => boolean;
  /** 현재 열린 노트의 본문 — '현재 노트로 새 템플릿'의 원본. 열린 노트가 없으면 null. */
  currentBody: string | null;
  onClose: () => void;
  toast?: (m: string, i?: string) => void;
}

type Draft = { id: string | null; name: string; body: string } | null;

export function TemplateModal({ onInsert, currentBody, onClose, toast }: Props) {
  const [items, setItems] = useState<ApiTemplate[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [draft, setDraft] = useState<Draft>(null); // null이면 목록·미리보기 모드

  useEscClose(() => { if (draft) setDraft(null); else onClose(); });

  const fail = useCallback((e: unknown) => {
    toast?.(e instanceof ApiError ? e.message : "오류가 발생했습니다");
  }, [toast]);

  const load = useCallback(async () => {
    setBusy(true);
    try {
      const list = await TemplateApi.list();
      setItems(list);
      setSelectedId((cur) => (cur && list.some((t) => t.id === cur) ? cur : list[0]?.id ?? null));
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  }, [fail]);

  useEffect(() => { void load(); }, [load]);

  const selected = items.find((t) => t.id === selectedId) ?? null;

  const doInsert = () => {
    if (!selected) return;
    const ok = onInsert(wrapForInsert(selected.body));
    toast?.(ok ? "템플릿을 삽입했습니다" : "열린 노트가 없습니다", ok ? "check" : undefined);
    if (ok) onClose();
  };

  const saveDraft = async () => {
    if (!draft || busy) return;
    const nameErr = validateTemplateName(draft.name);
    if (nameErr) { toast?.(nameErr); return; }
    const bodyErr = validateTemplateBody(draft.body);
    if (bodyErr) { toast?.(bodyErr); return; }
    setBusy(true);
    try {
      const saved = draft.id
        ? await TemplateApi.update(draft.id, draft.name.trim(), draft.body)
        : await TemplateApi.create(draft.name.trim(), draft.body);
      setDraft(null);
      await load();
      setSelectedId(saved.id);
      toast?.("저장했습니다", "check");
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const remove = async (t: ApiTemplate) => {
    if (busy) return;
    if (!window.confirm(`'${t.name}' 템플릿을 삭제할까요?`)) return;
    setBusy(true);
    try {
      await TemplateApi.remove(t.id);
      await load();
      toast?.("삭제했습니다", "check");
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const startFromCurrentNote = () => {
    if (currentBody == null) { toast?.("열린 노트가 없습니다"); return; }
    if (!currentBody.trim()) { toast?.("현재 노트가 비어 있습니다"); return; }
    setDraft({ id: null, name: "", body: currentBody });
  };

  const groups = groupTemplates(items);

  const row = (t: ApiTemplate) =>
    h("div", {
      key: t.id,
      className: "tpl-item" + (selectedId === t.id ? " tpl-item--active" : ""),
      onClick: () => setSelectedId(t.id),
    },
      h("span", { className: "tpl-item-name" }, t.name),
      canEdit(t) && h("span", { className: "tpl-item-acts" },
        h("button", {
          className: "icon-btn", title: "수정",
          onClick: (e: React.MouseEvent) => { e.stopPropagation(); setDraft({ id: t.id, name: t.name, body: t.body }); },
        }, h(Icon, { name: "edit" })),
        h("button", {
          className: "icon-btn", title: "삭제",
          onClick: (e: React.MouseEvent) => { e.stopPropagation(); void remove(t); },
        }, h(Icon, { name: "trash" }))),
    );

  /* ── 좌측: 2그룹 목록 ── */
  const listPanel = h("div", { className: "tpl-panel" },
    h("div", { className: "tpl-list" },
      h("div", { className: "tpl-group" }, "시스템"),
      groups.system.length === 0
        ? h("div", { className: "tpl-empty" }, "시스템 템플릿이 없습니다")
        : groups.system.map(row),
      h("div", { className: "tpl-group" }, "내 템플릿"),
      groups.mine.length === 0
        ? h("div", { className: "tpl-empty" }, "아직 만든 템플릿이 없습니다")
        : groups.mine.map(row),
    ),
    h("div", { className: "tpl-panel-foot" },
      h("button", {
        className: "pf-btn", disabled: busy || currentBody == null,
        title: currentBody == null ? "열린 노트가 없습니다" : "현재 노트 본문으로 템플릿 만들기",
        onClick: startFromCurrentNote,
      }, h(Icon, { name: "plus" }), "현재 노트로 새 템플릿")),
  );

  /* ── 우측: 미리보기 또는 편집 폼 ── */
  const previewPanel = selected
    ? h("div", { className: "tpl-detail" },
        h("div", { className: "tpl-detail-bar" },
          h("span", { className: "tpl-detail-name" }, selected.name),
          h("button", { className: "pf-btn primary", onClick: doInsert, title: "커서 위치에 삽입" }, "삽입")),
        h("div", { className: "tpl-preview md", dangerouslySetInnerHTML: { __html: renderMarkdown(selected.body) } }))
    : h("div", { className: "tpl-detail tpl-detail--empty" }, "템플릿을 선택하세요");

  const editPanel = draft && h("div", { className: "tpl-detail" },
    h("div", { className: "tpl-detail-bar" },
      h("input", {
        className: "pf-input", value: draft.name, placeholder: "템플릿 이름",
        autoFocus: true,
        onChange: (e: React.ChangeEvent<HTMLInputElement>) => setDraft({ ...draft, name: e.target.value }),
      }),
      h("button", { className: "pf-btn primary", disabled: busy, onClick: () => void saveDraft() }, "저장"),
      h("button", { className: "pf-btn", onClick: () => setDraft(null) }, "취소")),
    h("textarea", {
      className: "tpl-edit-body", value: draft.body,
      onChange: (e: React.ChangeEvent<HTMLTextAreaElement>) => setDraft({ ...draft, body: e.target.value }),
    }));

  return h("div", { className: "pf-overlay", onMouseDown: onClose },
    h("div", { className: "tpl-modal", onMouseDown: (e: React.MouseEvent) => e.stopPropagation() },
      h("div", { className: "tpl-modal-head" },
        h(Icon, { name: "clipboard" }),
        h("span", { className: "tpl-modal-title" }, "템플릿"),
        busy && h("span", { className: "tpl-modal-busy" }, "로딩 중…"),
        h("button", { className: "icon-btn pf-x", onClick: onClose, title: "닫기" }, h(Icon, { name: "x" }))),
      h("div", { className: "tpl-split" }, listPanel, draft ? editPanel : previewPanel)));
}
