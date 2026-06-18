/* App — composition root. Wires vault (reducer), settings, context menu, search,
   editor toolbar, export commands, toasts. */
import { useState, useEffect, useRef, useMemo, useCallback, createElement, Fragment } from "react";
import React from "react";
import { Icon } from "./components/Icon";
import { Sidebar } from "./components/Sidebar";
import { Editor } from "./components/Editor";
import { SearchModal } from "./components/SearchModal";
import { ContextMenu } from "./components/ContextMenu";
import { Outline } from "./components/Outline";
import { ProfileModal } from "./components/ProfileModal";
import { SettingsModal } from "./components/SettingsModal";
import { TrashModal } from "./components/TrashModal";
import { ShareModal } from "./components/ShareModal";
import { MoveModal } from "./components/MoveModal";
import { MoveWarnDialog } from "./components/MoveWarnDialog";
import { LinkWarnDialog } from "./components/LinkWarnDialog";
import { PiiNoticeModal } from "./components/PiiNoticeModal";
import { ConnectionLost } from "./components/ConnectionLost";
import { canDropOn } from "./lib/dnd";
import { VaultApi } from "./storage/VaultApi";
import type { MovePreview } from "./storage/VaultApi";
import { shouldWarn } from "./components/moveWarning";
import { ApiError } from "./api/http";
import { useVault } from "./state/useVault";
import { useVaultSync, bootstrapIfEmpty } from "./state/useVaultSync";
import { loadPending, clearAllPending } from "./state/pendingStore";
import { useSession } from "./state/useSession";
import { repository, storageMode } from "./storage";
import { usePersist } from "./state/usePersist";
import { useContextMenu } from "./state/useContextMenu";
import { useSettings } from "./state/useSettings";
import { findNode, flattenNotes, crumbPath, firstNoteIn } from "./lib/tree";
import { Backlinks } from "./components/Backlinks";
import { buildBacklinks } from "./lib/linkIndex";
import { setMermaidTheme } from "./lib/markdown";
import { newId } from "./lib/id";
import { exportCommands } from "./commands/exportCommands";
import { SEED_DEFAULT_TITLE } from "./seed";
import type { ToolbarHandlers } from "./components/Editor";

// editor toolbar definition (velog base + diagrams/table/checklist)
const TB_GROUPS: Array<Array<{ k: string; cap?: string; icon?: string; title?: string; fn: (h: ToolbarHandlers) => void }>> = [
  [{ k: "h1", cap: "H1", fn: (h) => h.h(1) }, { k: "h2", cap: "H2", fn: (h) => h.h(2) },
   { k: "h3", cap: "H3", fn: (h) => h.h(3) }, { k: "h4", cap: "H4", fn: (h) => h.h(4) }],
  [{ k: "bold", icon: "bold", title: "굵게", fn: (h) => h.bold() },
   { k: "italic", icon: "italic", title: "기울임", fn: (h) => h.italic() },
   { k: "strike", icon: "strike", title: "취소선", fn: (h) => h.strike() }],
  [{ k: "quote", icon: "quote", title: "인용", fn: (h) => h.quote() },
   { k: "list", icon: "list", title: "목록", fn: (h) => h.list() },
   { k: "checklist", icon: "checklist", title: "체크리스트", fn: (h) => h.checklist() },
   { k: "table", icon: "table", title: "표", fn: (h) => h.table() }],
  [{ k: "link", icon: "link", title: "링크", fn: (h) => h.link() },
   { k: "image", icon: "image", title: "이미지", fn: (h) => h.image() },
   { k: "attach", icon: "paperclip", title: "파일 첨부", fn: (h) => h.attach() },
   { k: "code", icon: "code", title: "코드 블록", fn: (h) => h.code() }],
  [{ k: "mermaid", icon: "mermaid", title: "Mermaid 다이어그램", fn: (h) => h.mermaid() },
   { k: "sequence", icon: "sequence", title: "시퀀스 다이어그램", fn: (h) => h.sequence() }],
];

export function App() {
  const { tree, actions: rawActions, savedTick, ready, loadError, saveNow: flushLocal } = useVault(repository);
  const { settings, set } = useSettings();
  const [activeId, setActiveId] = usePersist<string | null>("wn.activeId", null);
  const [collapsed, setCollapsed] = usePersist<boolean>("wn.sbCollapsed", false);
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [searchOpen, setSearchOpen] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [trashOpen, setTrashOpen] = useState(false);
  const [shareNote, setShareNote] = useState<{ id: string; name: string } | null>(null);
  const [moveTarget, setMoveTarget] = useState<{ id: string; name: string } | null>(null);
  const [draggingId, setDraggingId] = useState<string | null>(null);
  const [dragOverId, setDragOverId] = useState<string | null>(null);
  const [pendingWarn, setPendingWarn] = useState<{ id: string; parentId: string | null; preview: MovePreview } | null>(null);
  const [linkWarn, setLinkWarn] = useState<{ id: string; name: string; count: number } | null>(null);
  const [toasts, setToasts] = useState<Array<{ id: string; msg: string; icon?: string }>>([]);
  const [dirty, setDirty] = useState(false); // 열린 노트에 미저장 편집이 있는지 — 우측 하단 저장 버튼 상태
  const { menu, openMenu, closeMenu } = useContextMenu();
  const toolbarRef = useRef<ToolbarHandlers>({} as ToolbarHandlers);
  const editorViewRef = useRef<any>(null);
  const currentEmp = (function () { try { return sessionStorage.getItem("wn.session") || "S2019-0007"; } catch (e) { return "S2019-0007"; } })();

  // ---- session (http 모드 전용 — local 모드는 me=null 고정, 기존 mock 표시 유지) ----
  const { me, setMe, isAdmin, logout } = useSession();
  const meLabel = me ? me.name + " (" + me.emp + ")" : currentEmp;

  // ---- theme ----
  useEffect(() => {
    const mode = settings.dark ? "dark" : "light";
    document.documentElement.setAttribute("data-theme", mode);
    try { localStorage.setItem("wn.theme", mode); } catch (e) {}
    setMermaidTheme(!!settings.dark);
  }, [settings.dark]);

  // ---- resolve active note + breadcrumbs ----
  const found = useMemo(() => (activeId ? findNode(tree, activeId) : { node: null, path: [] }), [tree, activeId]);
  const activeNote = found.node && found.node.type === "note" ? found.node : null;
  // 브레드크럼 세그먼트(조상 폴더 id+name) — 링크로 렌더해 클릭 시 해당 폴더로 이동.
  const crumbs = useMemo(() => (activeId ? crumbPath(tree, activeId) : []), [tree, activeId]);

  // 백링크 역인덱스(클라 파생). 링크 후보·해석 콜백.
  const backlinks = useMemo(() => buildBacklinks(tree), [tree]);
  const wikiCandidates = useCallback(
    () => flattenNotes(tree).map(({ note, path }) => ({ id: note.id, title: note.title || "제목 없음", path: path.join(" / ") })),
    [tree],
  );
  const resolveLink = useCallback((id: string) => {
    const { node } = findNode(tree, id);
    return node && node.type === "note" ? (node.title || "제목 없음") : null;
  }, [tree]);

  // open default note on first load (after ready — seed→saved replacement may change nodes)
  useEffect(() => {
    if (!ready) return;
    if (activeId && findNode(tree, activeId).node) return;
    const all = flattenNotes(tree);
    const def = all.find((n) => n.note.title === SEED_DEFAULT_TITLE) || all[0];
    if (def) setActiveId(def.note.id);
  }, [ready]);

  // ---- toasts ----
  // 같은 메시지를 연타하면 위로 누적되던 문제 → throttle: 표시 시간(1.5초) 안에 동일 메시지가
  // 다시 오면 무시(연타 동안 억제 갱신). 한 위치에 하나만 뜨고, 1.5초 지나면 다시 복사 시 정상 표시.
  const TOAST_MS = 1500;
  const lastToastRef = useRef<{ key: string; at: number }>({ key: "", at: 0 });
  const toast = useCallback((msg: string, icon?: string) => {
    const key = msg + "" + (icon ?? "");
    const now = Date.now();
    const last = lastToastRef.current;
    if (last.key === key && now - last.at < TOAST_MS) {
      last.at = now; // 연타가 이어지는 동안 억제 유지
      return;
    }
    lastToastRef.current = { key, at: now };
    const id = newId();
    setToasts((ts) => [...ts, { id, msg, icon }]);
    setTimeout(() => setToasts((ts) => ts.filter((x) => x.id !== id)), TOAST_MS);
  }, []);

  // ---- server sync (HTTP 모드: 액션 단위 동기화, local 모드: rawActions 그대로) ----
  const { actions, flush: flushHttp } = useVaultSync(rawActions, toast);

  // 수동 저장 — 디바운스 대기 없이 즉시 persist(local localStorage / http PATCH 둘 다 호출, 반대 모드는 no-op).
  const saveNow = () => {
    if (!dirty) return;
    flushHttp();
    flushLocal();
    setDirty(false);
    toast("저장되었습니다", "check");
  };
  // 전역 단축키(⌘/Ctrl+S)는 []-deps useEffect라 첫 렌더 클로저를 잡는다 → ref로 최신 saveNow 유지(stale dirty 방지).
  const saveNowRef = useRef(saveNow);
  saveNowRef.current = saveNow;

  // 빈 서버였으면 시드 1회 업로드 (HTTP 모드 한정 — 내부 가드)
  useEffect(() => {
    if (!ready || loadError) return; // 차단(다운) 상태에선 어떤 동기화도 돌지 않는다
    void bootstrapIfEmpty(tree, toast);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready]);

  // ---- 미저장 편집 복구 (HTTP 모드: 401/크래시로 유실된 디바운스 편집을 재로그인 후 재적용·재전송) ----
  const recoveredRef = useRef(false);
  useEffect(() => {
    if (storageMode !== "http" || !ready || loadError || recoveredRef.current) return; // 차단 시 헛 PATCH 방지

    recoveredRef.current = true;
    const pending = loadPending();
    const ids = Object.keys(pending);
    if (ids.length === 0) return;
    clearAllPending(); // 존재 노트만 재적용이 다시 미러링 — 사라진 노트는 자연 정리
    let n = 0;
    for (const id of ids) {
      if (!findNode(tree, id).node) continue; // 그 사이 삭제된 노트는 건너뜀
      actions.updateNote(id, pending[id]);    // synced.updateNote → 재미러링 + 디바운스 재전송
      n++;
    }
    if (n > 0) toast("미저장 변경 " + n + "건을 복구해 다시 저장합니다", "check");
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready]);

  // ---- auto-save landed: 디바운스 저장(1분)이 떨어지면 dirty만 해제(조용히). 명시적 토스트는 수동 저장에서만. ----
  useEffect(() => {
    if (savedTick > 0) setDirty(false);
  }, [savedTick]);

  // 노트 전환 시 저장 상태 초기화 — 버튼은 현재 열린 노트 기준. 이전 노트의 미전송분은 각자 타이머로 전송됨.
  useEffect(() => { setDirty(false); }, [activeId]);

  // 노트 진입 시(검색·브레드크럼·복원 등) 조상 폴더를 펼쳐 사이드바에서 위치가 보이도록 한다.
  // 이미 열린 폴더는 건너뜀(불필요한 트리 churn 방지). activeId 변경 시에만 — 사용자의 수동 접기는 보존.
  useEffect(() => {
    if (!ready || !activeId) return;
    for (const seg of crumbPath(tree, activeId)) {
      const { node } = findNode(tree, seg.id);
      if (node && node.type === "folder" && !node.open) actions.open(seg.id);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeId, ready]);

  // ---- note ops ----
  const openNote = useCallback((note: { id: string }) => { setActiveId(note.id); }, []);
  // 브레드크럼 폴더 클릭 → 사이드바에서 펼치고(open), 그 폴더의 첫 노트를 연다.
  const goToFolder = useCallback((folderId: string) => {
    const { node } = findNode(tree, folderId);
    if (!node || node.type !== "folder") return;
    actions.open(folderId);                 // 사이드바 펼침 (서버 무관 UI 액션)
    const first = firstNoteIn(node);
    if (first) setActiveId(first.id);
    else toast("이 폴더에는 노트가 없습니다");
  }, [tree, actions, toast, setActiveId]);
  const newNoteIn = (folderId: string | null) => { const n = actions.addNote(folderId); setActiveId(n.id); setRenamingId(n.id); };
  const newFolderIn = (folderId: string | null) => { const n = actions.addFolder(folderId); setRenamingId(n.id); };
  const onRenameCommit = (id: string, value: string | null) => {
    if (value != null && value.trim() !== "") actions.rename(id, value.trim());
    setRenamingId(null);
  };
  const doRemove = (id: string) => {
    actions.remove(id);
    if (id === activeId) setActiveId(null);
    const { node } = findNode(tree, id);
    toast((node && node.type === "folder" ? "폴더" : "노트") + "를 삭제했습니다", "trash");
  };
  const removeNode = (id: string) => {
    const { node } = findNode(tree, id);
    const refs = node && node.type === "note" ? (backlinks.get(id) || []) : [];
    if (refs.length) { setLinkWarn({ id, name: node!.type === "note" ? (node!.title || "제목 없음") : "", count: refs.length }); return; }
    doRemove(id);
  };

  // ---- context menu builders ----
  // 내보내기 감사 핑 — http 모드만, fire-and-forget(실패해도 내보내기는 진행). local 모드는 백엔드 없음.
  const logExport = storageMode === "http"
    ? (note: any, format: "pdf" | "md" | "copy") => { void VaultApi.logExport(note.id, format).catch(() => {}); }
    : undefined;
  const exportSub = (note: any) => exportCommands.map((c) => ({
    icon: c.icon, label: c.label, onClick: () => c.run(note, { openNote, toast, logExport }),
  }));
  const onContext = (x: number, y: number, node: any) => {
    const canCreateAtRoot = storageMode === "local" || isAdmin;
    let items;
    if (!node) {
      if (!canCreateAtRoot) return;   // 일반 사용자: 빈 영역(루트) 우클릭 무반응
      items = [
        { icon: "newNote", label: "새 노트", onClick: () => newNoteIn(null) },
        { icon: "folderPlus", label: "새 폴더", onClick: () => newFolderIn(null) },
      ];
    } else if (node.type === "folder") {
      const isTopFolder = findNode(tree, node.id).parentNode === null;
      items = [
        { icon: "newNote", label: "새 노트", onClick: () => newNoteIn(node.id) },
        { icon: "folderPlus", label: "새 폴더", onClick: () => newFolderIn(node.id) },
        { sep: true },
        ...(isTopFolder ? [] : [{ icon: "move", label: "이동", onClick: () => setMoveTarget({ id: node.id, name: node.name }) }]),
        { icon: "edit", label: "이름 변경", onClick: () => setRenamingId(node.id) },
        { icon: "trash", label: "삭제", danger: true, onClick: () => removeNode(node.id) },
      ];
    } else {
      items = [
        { icon: "export", label: "내보내기", submenu: exportSub(node) },
        ...(storageMode === "http"
          ? [{ icon: "link", label: "공유 링크", onClick: () => setShareNote({ id: node.id, name: node.title || "제목 없음" }) }]
          : []),
        { sep: true },
        { icon: "move", label: "이동", onClick: () => setMoveTarget({ id: node.id, name: node.title || "제목 없음" }) },
        { icon: "edit", label: "이름 변경", onClick: () => setRenamingId(node.id) },
        { icon: "trash", label: "삭제", danger: true, onClick: () => removeNode(node.id) },
      ];
    }
    openMenu(x, y, items);
  };

  // ---- 트리 노드 드래그앤드롭 이동 ----
  const nodeName = (id: string) => {
    const { node } = findNode(tree, id);
    if (!node) return "";
    return node.type === "folder" ? node.name : (node.title || "제목 없음");
  };
  const attemptDnDMove = async (id: string, parentId: string | null) => {
    if (storageMode !== "http") { actions.move(id, parentId); toast("이동했습니다", "check"); return; }
    try {
      const p = await VaultApi.movePreview(id, parentId);
      if (!shouldWarn(p).warn) { actions.move(id, parentId); toast("이동했습니다", "check"); return; }
      setPendingWarn({ id, parentId, preview: p });
    } catch (e) {
      toast(e instanceof ApiError ? e.message : "이동할 수 없습니다");
    }
  };
  const onNodeDragStart = (id: string, e: React.DragEvent) => {
    setDraggingId(id);
    try { e.dataTransfer.setData("text/plain", id); e.dataTransfer.effectAllowed = "move"; } catch (err) {}
  };
  const onNodeDragEnd = () => { setDraggingId(null); setDragOverId(null); };
  const onNodeDragOver = (targetId: string | null, e: React.DragEvent) => {
    if (!draggingId || !canDropOn(tree, draggingId, targetId)) return;
    e.preventDefault();
    try { e.dataTransfer.dropEffect = "move"; } catch (err) {}
    setDragOverId(targetId);
  };
  const onNodeDragLeave = (targetId: string | null) => {
    setDragOverId((cur) => (cur === targetId ? null : cur));
  };
  const onNodeDrop = (targetId: string | null, e: React.DragEvent) => {
    e.preventDefault();
    const id = draggingId;
    setDraggingId(null); setDragOverId(null);
    if (!id || !canDropOn(tree, id, targetId)) return;
    void attemptDnDMove(id, targetId);
  };

  // ---- global shortcuts ----
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") { e.preventDefault(); setSearchOpen((o) => !o); }
      if ((e.metaKey || e.ctrlKey) && e.key === "\\") { e.preventDefault(); setCollapsed((c) => !c); }
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "s") { e.preventDefault(); saveNowRef.current(); } // ⌘/Ctrl+S 저장 (브라우저 기본 저장 차단)
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  // ---- settings-driven CSS vars + classes ----
  const dens = settings.density === "compact" ? { h: 26, fs: 13 } : settings.density === "spacious" ? { h: 34, fs: 14.5 } : { h: 30, fs: 13.5 };
  const appStyle = {
    "--sidebar-w": (settings.sidebarWidth || 264) + "px",
    "--reading-size": (settings.fontSize || 16) + "px",
    "--row-h": dens.h + "px",
    "--row-fs": dens.fs + "px",
  } as React.CSSProperties;
  const appClass = "app" + (collapsed ? " sb-collapsed" : "") + (settings.guides ? " guides" : "") + (settings.showIcons ? "" : " no-icons");

  const callTb = (fn: (h: ToolbarHandlers) => void) => fn(toolbarRef.current);

  if (loadError) return createElement(ConnectionLost, { onRetry: () => location.reload() });
  if (!ready) return null;

  return createElement(
    "div", { className: appClass, style: appStyle },
    createElement(Sidebar, {
      tree, brand: "WorkNote", activeId, renamingId,
      onOpenSearch: () => setSearchOpen(true),
      onCollapseAll: actions.collapseAll,
      onToggleSidebar: () => setCollapsed((c) => !c),
      onToggle: actions.toggle, onOpen: openNote, onContext,
      onRename: setRenamingId, onRenameCommit,
      onSettings: () => setSettingsOpen(true),
      showAdmin: storageMode === "local" || isAdmin,    // local 모드는 기존 동작 보존, http 모드는 관리자만
      showLogout: storageMode === "http" && me != null, // 세션이 있을 때만
      onLogout: logout,
      showTrash: storageMode === "http" && me != null,  // 휴지통은 server 모드 전용
      onTrash: () => setTrashOpen(true),
      draggingId, dragOverId,
      onNodeDragStart, onNodeDragOver, onNodeDragLeave, onNodeDrop, onNodeDragEnd,
    }),
    createElement(
      "div", { className: "main" },
      // topbar
      createElement(
        "div", { className: "topbar" },
        collapsed && createElement("button", { className: "icon-btn", title: "사이드바 펼치기", onClick: () => setCollapsed(false) },
          createElement(Icon, { name: "panelLeft" })),
        createElement(
          "div", { className: "crumbs" },
          activeNote && createElement("span", { className: "crumb-ic" }, createElement(Icon, { name: crumbs.length ? "folder" : "fileLines" })),
          crumbs.map((seg) =>
            createElement(Fragment, { key: seg.id },
              createElement("span", {
                className: "seg link", title: seg.name + " 폴더 열기",
                onClick: () => goToFolder(seg.id),
              }, seg.name),
              createElement("span", { className: "sep" }, "/"))),
          activeNote && createElement("span", { className: "seg cur" }, activeNote.title || "제목 없음")
        ),
        createElement(
          "div", { className: "right" },
          createElement("button", { className: "topbar-me", title: "프로필", onClick: () => setProfileOpen(true) },
            createElement("span", { className: "tm-ic" }, createElement(Icon, { name: "user" })),
            createElement("span", { className: "tm-emp" }, meLabel)),
          createElement("button", { className: "icon-btn", title: settings.dark ? "라이트 모드" : "다크 모드", onClick: () => set("dark", !settings.dark) },
            createElement(Icon, { name: settings.dark ? "sun" : "moon" })),
          activeNote && createElement("button", {
            className: "icon-btn", title: "내보내기",
            onClick: (e: React.MouseEvent<HTMLButtonElement>) => { const r = e.currentTarget.getBoundingClientRect(); openMenu(r.right - 200, r.bottom + 6, exportSub(activeNote)); },
          }, createElement(Icon, { name: "export" }))
        )
      ),
      // editor toolbar
      activeNote && createElement(
        "div", { className: "etoolbar" },
        TB_GROUPS.map((g, gi) =>
          createElement(Fragment, { key: gi },
            gi > 0 && createElement("span", { className: "div" }),
            g.map((b) =>
              createElement("button", {
                key: b.k, className: "tb", title: b.title,
                onMouseDown: (e: React.MouseEvent) => e.preventDefault(),
                onClick: () => callTb(b.fn),
              }, b.cap
                ? createElement("span", { className: "hcap" }, b.cap[0], createElement("sub", null, b.cap.slice(1)))
                : createElement(Icon, { name: b.icon! })))
          ))
      ),
      // document
      createElement(
        "div", { className: "doc-scroll" },
        activeNote
          ? createElement(Editor, {
              key: activeNote.id, note: activeNote, theme: settings.dark ? "dark" : "light",
              onChange: (patch) => { actions.updateNote(activeNote.id, patch); setDirty(true); },
              registerToolbar: (h) => { toolbarRef.current = h; },
              onView: (v) => { editorViewRef.current = v; },
              toast, canUpload: storageMode === "http",
              onSetPii: (id, pii) => actions.setNotePii(id, pii),
              wikiCandidates, resolveLink, onNavigate: (id: string) => setActiveId(id),
            })
          : createElement(
              "div", { className: "empty-state" },
              createElement("div", { className: "es-inner" },
                createElement("div", { className: "es-icon" }, createElement(Icon, { name: "fileLines" })),
                createElement("h2", null, "열린 노트가 없습니다"),
                createElement("p", null, "사이드바에서 노트를 선택하거나 ⌘K 로 검색하세요")))
        , activeNote && createElement(Backlinks, {
            key: "bl-" + activeNote.id,
            items: backlinks.get(activeNote.id) || [],
            onOpen: openNote,
          })
      ),
      activeNote && createElement(Outline, {
        key: "ol-" + activeNote.id, content: activeNote.content, title: activeNote.title, viewRef: editorViewRef,
      }),
      // 우측 하단 수동 저장 버튼 — 미저장 편집이 있을 때 활성, 저장 후/자동저장 후 '저장됨'.
      activeNote && createElement("button", {
        className: "doc-save" + (dirty ? " dirty" : ""),
        title: dirty ? "지금 저장" : "저장됨",
        disabled: !dirty,
        onClick: saveNow,
      },
        createElement(Icon, { name: dirty ? "save" : "check" }),
        createElement("span", null, dirty ? "저장" : "저장됨"))
    ),
    // overlays
    searchOpen && createElement(SearchModal, {
      notes: flattenNotes(tree),
      onClose: () => setSearchOpen(false),
      onOpen: openNote,
    }),
    profileOpen && createElement(ProfileModal, {
      emp: me ? me.emp : currentEmp, role: me ? me.roleId : "운영자", name: me?.name, email: me?.email,
      onSaved: setMe,
      onClose: () => setProfileOpen(false),
      toast,
    }),
    settingsOpen && createElement(SettingsModal, { settings, onSet: set, onClose: () => setSettingsOpen(false) }),
    trashOpen && createElement(TrashModal, { onClose: () => setTrashOpen(false), toast, onRestored: rawActions.reload }),
    shareNote && createElement(ShareModal, { note: shareNote, onClose: () => setShareNote(null), toast }),
    moveTarget && createElement(MoveModal, { node: moveTarget, tree, onMove: actions.move, onClose: () => setMoveTarget(null), toast }),
    pendingWarn && createElement(MoveWarnDialog, {
      name: nodeName(pendingWarn.id),
      preview: pendingWarn.preview,
      onConfirm: () => { actions.move(pendingWarn.id, pendingWarn.parentId); toast("이동했습니다", "check"); setPendingWarn(null); },
      onCancel: () => setPendingWarn(null),
    }),
    linkWarn && createElement(LinkWarnDialog, {
      name: linkWarn.name, count: linkWarn.count,
      onConfirm: () => { doRemove(linkWarn.id); setLinkWarn(null); },
      onCancel: () => setLinkWarn(null),
    }),
    storageMode === "http" && me != null && createElement(PiiNoticeModal, { key: "pii-notice-" + me.emp }),
    menu && createElement(ContextMenu, { x: menu.x, y: menu.y, items: menu.items, onClose: closeMenu }),
    // toasts
    createElement(
      "div", { className: "toast-wrap" },
      toasts.map((t2) =>
        createElement("div", { className: "toast", key: t2.id },
          t2.icon && createElement(Icon, { name: t2.icon }),
          createElement("span", null, t2.msg)))
    )
  );
}
