/* RedmineImportPanel — Redmine 이슈 검색·임포트 도킹 분할 패널.
   T7(api) · T8(마크다운) · T9(분할) 의존. JSX 절대 미사용 — h = createElement 관례. */
import React, { useState, useEffect, useCallback, useRef } from "react";
import { RedmineApi, type RedmineIssueSummary, type RedmineIssueDetail, type RedmineComment } from "../api/redmine";
import { ApiError } from "../api/http";
import { metaTableMd, bodyMd, commentMd } from "../editor/redmineMarkdown";
import { splitDirection } from "./redmineSplit";
import { redmineStatusLabel } from "../admin/mappers";
import { renderMarkdown, enhanceMermaid, setMermaidTheme } from "../lib/markdown";
import { Icon } from "./Icon";

const h = React.createElement;

interface Props {
  onInsert: (md: string) => boolean; // 노트에 삽입 — 성공(열린 노트 있음) 여부 반환
  onClose: () => void;
  toast?: (m: string, i?: string) => void;
  theme?: "dark" | "light"; // 전체 미리보기 마크다운/mermaid 렌더 테마
}

export function RedmineImportPanel({ onInsert, onClose, toast, theme }: Props) {
  const [dir, setDir] = useState<"row" | "column">(() => splitDirection(window.innerWidth));
  const [q, setQ] = useState("");
  const [mine, setMine] = useState(true);
  const [list, setList] = useState<RedmineIssueSummary[]>([]);
  const [detail, setDetail] = useState<RedmineIssueDetail | null>(null);
  const [busy, setBusy] = useState(false);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [full, setFull] = useState(false); // 상세: 요약(false) ↔ 전체 미리보기(true)
  const previewRef = useRef<HTMLDivElement>(null);

  /* resize → 분할 방향 갱신 */
  useEffect(() => {
    const onResize = () => setDir(splitDirection(window.innerWidth));
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);

  /* Escape → 전체 미리보기면 요약 복귀, 아니면 모달 닫기(2단계) */
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      if (full) setFull(false);
      else onClose();
    };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  }, [onClose, full]);

  /* 전체 미리보기 렌더 후 mermaid SVG 치환(테마 반영) — SharePage와 동일 패턴 */
  useEffect(() => {
    if (!full || !detail || !previewRef.current) return;
    setMermaidTheme(theme === "dark");
    enhanceMermaid(previewRef.current);
  }, [full, detail, theme]);

  /* 에러 처리 분기 */
  const fail = useCallback((e: unknown) => {
    const msg = e instanceof ApiError ? e.message : "오류가 발생했습니다";
    if (msg === "redmine_token_missing") toast?.("프로필에서 Redmine 키를 먼저 등록하세요");
    else if (msg === "redmine_token_invalid") toast?.("Redmine 키가 유효하지 않습니다. 프로필에서 갱신하세요");
    else toast?.(msg);
  }, [toast]);

  /* 노트에 삽입 + 피드백 토스트(모달이 에디터를 가리므로 삽입됐는지 사용자가 알 수 있게).
     토스트는 z-index 3000 > 모달 2100 이라 모달 위에 표시된다. emptyMsg 지정 시 내용이 비면 가드. */
  const doInsert = (md: string, emptyMsg?: string) => {
    if (emptyMsg && !md.trim()) { toast?.(emptyMsg); return; }
    const ok = onInsert(md);
    toast?.(ok ? "노트에 삽입했습니다" : "열린 노트가 없습니다", ok ? "check" : undefined);
    if (ok) onClose(); // 삽입 후 모달 닫기 — 삽입 위치(scrollIntoView)가 바로 보인다
  };

  /* 검색 */
  const search = useCallback(async () => {
    setBusy(true);
    setDetail(null);
    setSelectedId(null);
    try {
      const res = await RedmineApi.search({ query: q || undefined, assignedToMe: mine });
      setList(res.issues);
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  }, [q, mine, fail]);

  /* 마운트 시 최초 1회 (내 이슈) */
  useEffect(() => { void search(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  /* 이슈 상세 조회 */
  const open = async (id: number) => {
    if (busy) return;
    setBusy(true);
    setFull(false);
    setSelectedId(id);
    try {
      setDetail(await RedmineApi.get(id));
    } catch (e) {
      fail(e);
      setSelectedId(null);
    } finally {
      setBusy(false);
    }
  };

  /* Enter 키 검색 */
  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter") void search();
  };

  /* ── 검색 패널 ── */
  const panelLeft = h("div", { className: "rm-panel", style: { flex: "0 0 320px" } },
    h("div", { className: "rm-panel-head" },
      h("div", { style: { display: "flex", alignItems: "center", gap: 6, flex: 1, minWidth: 0 } },
        h(Icon, { name: "search" }),
        h("input", {
          type: "text",
          placeholder: "이슈 검색…",
          value: q,
          onChange: (e: React.ChangeEvent<HTMLInputElement>) => setQ(e.target.value),
          onKeyDown,
          style: { flex: 1, border: "none", background: "transparent", outline: "none", fontSize: 13 },
        }),
      ),
      h("label", { style: { display: "flex", alignItems: "center", gap: 4, fontSize: 12, whiteSpace: "nowrap", cursor: "pointer" } },
        h("input", {
          type: "checkbox",
          checked: mine,
          onChange: (e: React.ChangeEvent<HTMLInputElement>) => setMine(e.target.checked),
        }),
        "내 이슈",
      ),
      h("button", { className: "pf-btn primary", onClick: () => void search(), disabled: busy, title: "검색" }, "검색"),
    ),
    h("div", { className: "rm-issue-list" },
      busy && list.length === 0
        ? h("div", { style: { padding: "14px", color: "var(--text-3)", fontSize: 13 } }, "불러오는 중…")
        : list.length === 0
          ? h("div", { style: { padding: "14px", color: "var(--text-3)", fontSize: 13 } }, "검색 결과가 없습니다")
          : list.map((issue) =>
              h("div", {
                key: issue.id,
                className: "rm-issue" + (selectedId === issue.id ? " rm-issue--active" : ""),
                onClick: () => void open(issue.id),
                style: selectedId === issue.id ? { background: "var(--bg-active)" } : undefined,
              },
                h("div", { className: "rm-id" }, `#${issue.id} · ${issue.projectName}`),
                h("div", { style: { fontSize: 13, marginTop: 2 } }, issue.subject),
                h("div", { style: { fontSize: 12, color: "var(--text-3)", marginTop: 2, display: "flex", gap: 8 } },
                  h("span", null, redmineStatusLabel(issue.statusName)),
                  issue.assignedToName && h("span", null, issue.assignedToName),
                ),
              )
            ),
    ),
  );

  /* ── 상세 패널 ── */
  const detailEmpty = h("div", { className: "rm-detail", style: { color: "var(--text-3)", fontSize: 13, display: "flex", alignItems: "center", justifyContent: "center" } },
    "이슈를 선택하면 상세 내용이 표시됩니다",
  );

  const renderDetail = (d: RedmineIssueDetail) =>
    h("div", { className: "rm-detail" },
      /* 제목 + 전체 보기 */
      h("div", { style: { marginBottom: 12, display: "flex", alignItems: "flex-start", gap: 8 } },
        h("div", { style: { flex: 1, minWidth: 0 } },
          h("div", { style: { fontSize: 12, color: "var(--text-3)", fontFamily: "var(--font-mono)" } }, `#${d.id} · ${d.projectName}`),
          h("div", { style: { fontWeight: 600, fontSize: 15, marginTop: 2 } }, d.subject),
          h("div", { style: { fontSize: 12, color: "var(--text-3)", marginTop: 4, display: "flex", gap: 8 } },
            h("span", null, redmineStatusLabel(d.statusName)),
            d.assignedToName && h("span", null, d.assignedToName),
            d.dueDate && h("span", null, `마감: ${d.dueDate}`),
          ),
        ),
        h("button", { className: "pf-btn", onClick: () => setFull(true), title: "전체 미리보기", style: { flex: "none" } }, "전체 보기"),
      ),
      /* 메타 블록 */
      h("div", { className: "rm-block" },
        h("div", { className: "rm-block-head" },
          h("span", { style: { fontSize: 12 } }, "메타 정보"),
          h("button", { className: "pf-btn", onClick: () => doInsert(metaTableMd(d)), title: "메타 정보 삽입" }, "삽입"),
        ),
        h("div", { style: { padding: "8px 10px", fontSize: 12, color: "var(--text-3)" } },
          `상태: ${redmineStatusLabel(d.statusName)} · 담당: ${d.assignedToName ?? "-"} · 우선순위: ${d.priorityName ?? "-"}`,
        ),
      ),
      /* 본문 블록 */
      h("div", { className: "rm-block" },
        h("div", { className: "rm-block-head" },
          h("span", { style: { fontSize: 12 } }, "본문"),
          h("button", { className: "pf-btn", onClick: () => doInsert(bodyMd(d), "본문이 비어 있습니다"), title: "본문 삽입" }, "삽입"),
        ),
        h("div", { style: { padding: "8px 10px", fontSize: 12, color: "var(--text-3)", whiteSpace: "pre-wrap", maxHeight: 120, overflow: "auto" } },
          d.description ? d.description.slice(0, 300) + (d.description.length > 300 ? "…" : "") : "(본문 없음)",
        ),
      ),
      /* 댓글 블록 */
      ...d.comments.map((c: RedmineComment) =>
        h("div", { className: "rm-block", key: `${c.userName}-${c.createdOn}` },
          h("div", { className: "rm-block-head" },
            h("span", { style: { fontSize: 12 } }, `댓글 — ${c.userName} ${c.createdOn ? c.createdOn.slice(0, 10) : ""}`),
            h("button", { className: "pf-btn", onClick: () => doInsert(commentMd(c), "댓글이 비어 있습니다"), title: "댓글 삽입" }, "삽입"),
          ),
          h("div", { style: { padding: "8px 10px", fontSize: 12, color: "var(--text-3)", whiteSpace: "pre-wrap", maxHeight: 80, overflow: "auto" } },
            c.notes ? c.notes.slice(0, 200) + (c.notes.length > 200 ? "…" : "") : "(내용 없음)",
          ),
        )
      ),
    );

  /* ── 전체 미리보기 (본문 + 전체 댓글 마크다운 렌더 · lib/markdown 재사용) ──
     보이는 그대로(WYSIWYG) 삽입 — 렌더/삽입 모두 동일한 combined 마크다운을 사용. */
  const renderFullPreview = (d: RedmineIssueDetail) => {
    const bodyStr = bodyMd(d).trim();
    const hasComments = d.comments.length > 0;
    const empty = !bodyStr && !hasComments;
    // 삽입용 combined은 렌더와 동일 내용(WYSIWYG) — 본문 + 전체 댓글
    const parts = [bodyStr, ...d.comments.map((c) => commentMd(c).trim())].filter(Boolean);
    const combined = parts.length ? `\n${parts.join("\n\n")}\n` : "";
    return h("div", { className: "rm-preview-wrap" },
      h("div", { className: "rm-preview-bar" },
        h("div", { style: { minWidth: 0, flex: 1 } },
          h("div", { style: { fontSize: 12, color: "var(--text-3)", fontFamily: "var(--font-mono)" } }, `#${d.id} · ${d.projectName}`),
          h("div", { style: { fontWeight: 600, fontSize: 15, marginTop: 2 } }, d.subject),
        ),
        h("div", { style: { display: "flex", alignItems: "center", gap: 6, flex: "none" } },
          h("button", { className: "pf-btn primary", disabled: empty, onClick: () => doInsert(combined, "내용이 비어 있습니다"), title: "본문·댓글 삽입" }, "삽입"),
          h("button", { className: "icon-btn", onClick: () => setFull(false), title: "미리보기 닫기" }, h(Icon, { name: "x" })),
        ),
      ),
      empty
        ? h("div", { className: "rm-preview-empty" }, "(내용 없음)")
        : h("div", { className: "rm-preview", ref: previewRef },
            // 본문과 댓글을 별도 .md 블록으로 — 댓글은 한 사이즈 작게(.rm-preview-comments)
            bodyStr && h("div", { className: "md", dangerouslySetInnerHTML: { __html: renderMarkdown(bodyStr) } }),
            hasComments && h("div", { className: "md rm-preview-comments", dangerouslySetInnerHTML: { __html: renderMarkdown(d.comments.map(commentMd).join("\n")) } }),
          ),
    );
  };

  const panelRight = h("div", { style: { flex: 1, display: "flex", flexDirection: "column", minWidth: 0, minHeight: 0, overflow: "hidden" } },
    detail ? (full ? renderFullPreview(detail) : renderDetail(detail)) : detailEmpty,
  );

  /* ── 루트 레이아웃 (중앙 모달 — 오버레이 클릭 시 닫힘) ── */
  return h("div", { className: "pf-overlay", onMouseDown: onClose },
    h("div", {
      className: "rm-modal",
      onMouseDown: (e: React.MouseEvent) => e.stopPropagation(),
    },
      /* 헤더 */
      h("div", { className: "rm-modal-head" },
        h(Icon, { name: "download" }),
        h("span", { className: "rm-modal-title" }, "Redmine 이슈 임포트"),
        busy && h("span", { className: "rm-modal-busy" }, "로딩 중…"),
        h("button", { className: "icon-btn pf-x", onClick: onClose, title: "닫기" }, h(Icon, { name: "x" })),
      ),
      /* 분할 패널 — dir(row|column)을 rm- 접두 클래스로 매핑(전역 .row 충돌 방지) */
      h("div", { className: `rm-split ${dir === "row" ? "rm-row" : "rm-col"}` },
        panelLeft,
        panelRight,
      ),
    ),
  );
}
