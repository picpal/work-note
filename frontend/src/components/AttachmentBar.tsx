/* AttachmentBar — 타이틀·태그 아래 첨부파일 영역.
   attachment 테이블(노트 종속)이 출처이므로 본문 마크다운과 무관하게 모든 첨부를 나열한다.
   다운로드는 실제 <a download>(서버 Content-Disposition과 함께 동작), 삭제는 write 모드에서만.
   editor/share 양쪽이 재사용하도록 fetcher(load)를 주입받는다. */
import React, { useEffect, useState } from "react";
import type { AttachmentMeta } from "../storage/AttachmentApi";

const h = React.createElement;

/** 바이트 → 사람이 읽는 단위. 1KB 미만 B, 1MB 미만 KB, 그 이상 MB. */
export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

interface AttachmentBarProps {
  load: () => Promise<AttachmentMeta[]>;
  reloadKey?: number | string;
  removable?: boolean;
  onRemove?: (id: string) => Promise<void>;
  toast?: (msg: string, icon?: string) => void;
}

export function AttachmentBar(props: AttachmentBarProps) {
  const { load, reloadKey, removable, onRemove, toast } = props;
  const [items, setItems] = useState<AttachmentMeta[]>([]);

  useEffect(() => {
    let alive = true;
    load()
      .then((rows) => { if (alive) setItems(rows); })
      .catch(() => { if (alive) setItems([]); });
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reloadKey]);

  if (items.length === 0) return null;

  const remove = async (it: AttachmentMeta) => {
    if (!onRemove) return;
    if (!window.confirm(`'${it.filename}' 첨부를 삭제할까요? 되돌릴 수 없습니다.`)) return;
    try {
      await onRemove(it.id);
      setItems((xs) => xs.filter((x) => x.id !== it.id));
      toast && toast("첨부를 삭제했습니다", "check");
    } catch {
      toast && toast("삭제 실패");
    }
  };

  return h(
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
            ? h("button", { className: "attach-del", title: "삭제", onClick: () => void remove(it) }, "×")
            : null
        )
      )
    )
  );
}
