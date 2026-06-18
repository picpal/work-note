import { createElement, useState } from "react";
import { Icon } from "./Icon";
import type { Backlink } from "../lib/linkIndex";

interface BacklinksProps {
  items: Backlink[];
  onOpen: (note: { id: string }) => void;
}

// 활성 노트 하단 접이식 "이 노트를 참조하는 문서" 패널. 항목이 없으면 렌더 안 함.
export function Backlinks(props: BacklinksProps) {
  const { items, onOpen } = props;
  const [open, setOpen] = useState(true);
  if (!items.length) return null;
  return createElement(
    "div", { className: "backlinks" },
    createElement("button", { className: "bl-head", onClick: () => setOpen((v) => !v) },
      createElement("span", { className: "bl-twirl" + (open ? " open" : "") }, createElement(Icon, { name: "chevron" })),
      createElement("span", { className: "bl-title" }, "이 노트를 참조하는 문서"),
      createElement("span", { className: "bl-count" }, items.length)),
    open && createElement("div", { className: "bl-list" },
      items.map((b) =>
        createElement("button", { className: "bl-item", key: b.sourceId, onClick: () => onOpen({ id: b.sourceId }) },
          createElement(Icon, { name: "fileLines" }),
          createElement("span", null, b.sourceTitle))))
  );
}
