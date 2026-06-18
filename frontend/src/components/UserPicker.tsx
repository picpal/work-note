import React, { useState, useRef } from "react";
import type { DirectoryUser } from "../api/users";
import { formatUser, filterDirectory } from "../lib/userDirectory";

const h = React.createElement;
const MAX = 8; // 드롭다운 최대 표시 수

interface UserPickerProps {
  value: string[];                    // 선택된 emp 배열
  onChange: (emps: string[]) => void;
  directory: DirectoryUser[] | null;  // null = 로딩 전
  loadError?: boolean;                // true면 디렉토리 로드 실패(칩 추가 불가)
}

export function UserPicker({ value, onChange, directory, loadError }: UserPickerProps) {
  const [text, setText] = useState("");
  const [active, setActive] = useState(0);     // 드롭다운 하이라이트 인덱스
  const inputRef = useRef<HTMLInputElement>(null);
  const dir = directory ?? [];

  // 드롭다운 오픈 = 입력에 '@' 존재. 쿼리 = 마지막 '@' 이후 텍스트.
  const at = text.lastIndexOf("@");
  const open = at >= 0 && !loadError;
  const query = open ? text.slice(at + 1) : "";
  const matches = open ? filterDirectory(dir, query, value, MAX) : [];

  const nameOf = (emp: string) => dir.find((u) => u.emp === emp)?.name;
  const chipLabel = (emp: string) => { const n = nameOf(emp); return n ? formatUser({ emp, name: n }) : emp; };

  const add = (emp: string) => {
    if (!value.includes(emp)) onChange([...value, emp]);
    setText("");
    setActive(0);
    inputRef.current?.focus();
  };
  const removeAt = (i: number) => onChange(value.filter((_, idx) => idx !== i));

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (open && matches.length) {
      if (e.key === "ArrowDown") { e.preventDefault(); setActive((a) => (a + 1) % matches.length); return; }
      if (e.key === "ArrowUp") { e.preventDefault(); setActive((a) => (a - 1 + matches.length) % matches.length); return; }
      if (e.key === "Enter") { e.preventDefault(); add(matches[active].emp); return; }
    }
    if (e.key === "Escape" && open) { e.preventDefault(); setText(text.slice(0, at)); return; }
    if (e.key === "Backspace" && text === "" && value.length) { removeAt(value.length - 1); }
  };

  return h("div", { className: "upick" },
    h("div", { className: "upick-box", onClick: () => inputRef.current?.focus() },
      value.map((emp, i) =>
        h("span", { className: "upick-chip", key: emp },
          chipLabel(emp),
          h("button", {
            type: "button", title: "제거",
            onClick: (e: React.MouseEvent) => { e.stopPropagation(); removeAt(i); },
          }, "×"))),
      h("input", {
        className: "upick-input", ref: inputRef, value: text,
        placeholder: value.length ? "" : "@로 사번·이름 검색 (비우면 전 직원)",
        onChange: (e: React.ChangeEvent<HTMLInputElement>) => { setText(e.target.value); setActive(0); },
        onKeyDown,
      })),
    loadError
      ? h("div", { className: "upick-msg" }, "사용자 목록을 불러오지 못했습니다")
      : open
        ? h("div", { className: "upick-menu" },
            matches.length === 0
              ? h("div", { className: "upick-empty" }, "일치하는 사용자가 없습니다")
              : matches.map((u, i) =>
                  h("div", {
                    className: "upick-item" + (i === active ? " active" : ""), key: u.emp,
                    onMouseDown: (e: React.MouseEvent) => { e.preventDefault(); add(u.emp); },
                    onMouseEnter: () => setActive(i),
                  }, formatUser(u))))
        : null);
}
