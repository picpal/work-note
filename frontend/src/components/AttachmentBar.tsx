/* AttachmentBar — 타이틀·태그 아래 첨부파일 영역.
   attachment 테이블(노트 종속)이 출처이므로 본문 마크다운과 무관하게 모든 첨부를 나열한다.
   다운로드는 실제 <a download>(서버 Content-Disposition과 함께 동작), 삭제는 write 모드에서만.
   editor/share 양쪽이 재사용하도록 fetcher(load)를 주입받는다. */
import React, { useEffect, useState } from "react";
import type { AttachmentMeta } from "../storage/AttachmentApi";
import { ConfirmDialog } from "./ConfirmDialog";
import { activeDocText } from "../editor/activeDoc";

const h = React.createElement;

/** 바이트 → 사람이 읽는 단위. 1KB 미만 B, 1MB 미만 KB, 그 이상 MB. */
export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

/** 본문에서 이 첨부 url이 몇 번 쓰이는지. ![](url) · [x](url) · <img src="url"> 모두 해당.
   /api/attachments/att-1 이 att-12 안에 부분 매칭되지 않도록 뒤 경계를 본다. */
export function countAttachmentRefs(doc: string, url: string): number {
  if (!doc || !url) return 0;
  const esc = url.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return (doc.match(new RegExp(esc + "(?![A-Za-z0-9._~%-])", "g")) || []).length;
}

/** 첨부 삭제 확인 문구. refs=null이면 본문을 알 수 없는 맥락(에디터 없음)이라 참조 줄을 빼고 말한다. */
export function attachmentDeleteMessage(filename: string, refs: number | null): string[] {
  const lead = `'${filename}' 첨부를 삭제합니다. 되돌릴 수 없습니다.`;
  if (refs == null) return [lead];
  if (refs === 0) return [lead, "본문에서 사용하고 있지 않습니다."];
  return [lead, `본문 ${refs}곳에서 이 파일을 쓰고 있습니다. 삭제하면 그 자리는 깨진 이미지·링크가 됩니다.`];
}

interface AttachmentBarProps {
  load: () => Promise<AttachmentMeta[]>;
  reloadKey?: number | string;
  removable?: boolean;
  onRemove?: (id: string) => Promise<void>;
  toast?: (msg: string, icon?: string) => void;
  /** 삭제 확인에 "본문 N곳" 을 넣기 위한 본문 소스. 생략하면 열려 있는 에디터의 본문을 쓴다. */
  docText?: () => string | null;
}

export function AttachmentBar(props: AttachmentBarProps) {
  const { load, reloadKey, removable, onRemove, toast } = props;
  const [items, setItems] = useState<AttachmentMeta[]>([]);
  // 삭제 확인 — 네이티브 window.confirm 대신 앱 모달(P-1: 파괴적 동작 확인 방식 통일).
  const [pending, setPending] = useState<{ item: AttachmentMeta; refs: number | null } | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let alive = true;
    load()
      .then((rows) => { if (alive) setItems(rows); })
      .catch(() => { if (alive) setItems([]); });
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reloadKey]);

  if (items.length === 0) return null;

  const askRemove = (it: AttachmentMeta) => {
    const doc = props.docText ? props.docText() : activeDocText();
    setPending({ item: it, refs: doc == null ? null : countAttachmentRefs(doc, it.url) });
  };

  const confirmRemove = async () => {
    if (!onRemove || !pending || busy) return;
    const it = pending.item;
    setBusy(true);
    try {
      await onRemove(it.id);
      setItems((xs) => xs.filter((x) => x.id !== it.id));
      toast && toast("첨부를 삭제했습니다", "check");
      setPending(null);
    } catch {
      toast && toast("삭제 실패");
    } finally {
      setBusy(false);
    }
  };

  return h(
    React.Fragment,
    null,
    h(
      "div", { className: "attach-bar", "aria-label": "첨부파일" },
      h("div", { className: "attach-bar-head" }, `첨부파일 ${items.length}`),
      h(
        "ul", { className: "attach-list" },
        items.map((it) =>
          h(
            "li", { className: "attach-item", key: it.id },
            h(
              "a",
              { className: "attach-dl", href: it.url, download: it.filename, title: `${it.filename} 다운로드` },
              h("span", { className: "attach-ic", "aria-hidden": true }, it.image ? "🖼" : "📎"),
              h("span", { className: "attach-name" }, it.filename),
              h("span", { className: "attach-size" }, formatBytes(it.size))
            ),
            removable
              ? h("button", { className: "attach-del", title: "삭제", onClick: () => askRemove(it) }, "×")
              : null
          )
        )
      )
    ),
    pending &&
      h(ConfirmDialog, {
        name: pending.item.filename,
        action: "첨부 삭제",
        message: attachmentDeleteMessage(pending.item.filename, pending.refs),
        danger: true,
        icon: "trash",
        confirmLabel: "삭제",
        busy,
        onConfirm: () => void confirmRemove(),
        onCancel: () => { if (!busy) setPending(null); },
      })
  );
}
