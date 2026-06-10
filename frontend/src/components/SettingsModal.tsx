/* SettingsModal — app settings: dark mode, sidebar width, density, icons, guides, font size. */
import { useEffect } from "react";
import React from "react";
import { Icon } from "./Icon";
import type { Settings } from "../types";

const h = React.createElement;

interface Props {
  settings: Settings;
  onSet: <K extends keyof Settings>(k: K, v: Settings[K]) => void;
  onClose: () => void;
}

const DENSITY_OPTIONS: { value: Settings["density"]; label: string }[] = [
  { value: "compact", label: "좁게" },
  { value: "comfortable", label: "보통" },
  { value: "spacious", label: "넓게" },
];

export function SettingsModal({ settings, onSet, onClose }: Props) {
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape") { e.preventDefault(); onClose(); }
    };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  }, [onClose]);

  return h("div", { className: "st-overlay", onMouseDown: onClose },
    h("div", { className: "st-card", onMouseDown: (e: React.MouseEvent) => e.stopPropagation() },

      h("div", { className: "st-head" },
        h("span", { className: "st-title" }, "환경설정"),
        h("button", { className: "icon-btn st-x", onClick: onClose, title: "닫기" },
          h(Icon, { name: "x" }))),

      h("div", { className: "st-body" },

        h("div", { className: "st-row" },
          h("label", { className: "st-label" }, "다크 모드"),
          h("label", { className: "st-switch" },
            h("input", {
              type: "checkbox",
              checked: settings.dark,
              onChange: (e: React.ChangeEvent<HTMLInputElement>) => onSet("dark", e.target.checked),
            }),
            h("span", { className: "st-thumb" }))),

        h("div", { className: "st-row" },
          h("label", { className: "st-label" }, "사이드바 너비"),
          h("div", { className: "st-range-wrap" },
            h("input", {
              type: "range",
              min: 220, max: 360, step: 4,
              value: settings.sidebarWidth,
              onChange: (e: React.ChangeEvent<HTMLInputElement>) => onSet("sidebarWidth", Number(e.target.value)),
            }),
            h("span", { className: "st-range-val" }, settings.sidebarWidth + "px"))),

        h("div", { className: "st-row" },
          h("label", { className: "st-label" }, "밀도"),
          h("div", { className: "st-seg" },
            ...DENSITY_OPTIONS.map(({ value, label }) =>
              h("button", {
                key: value,
                className: "st-seg-btn" + (settings.density === value ? " active" : ""),
                onClick: () => onSet("density", value),
              }, label)))),

        h("div", { className: "st-row" },
          h("label", { className: "st-label" }, "파일 아이콘"),
          h("label", { className: "st-switch" },
            h("input", {
              type: "checkbox",
              checked: settings.showIcons,
              onChange: (e: React.ChangeEvent<HTMLInputElement>) => onSet("showIcons", e.target.checked),
            }),
            h("span", { className: "st-thumb" }))),

        h("div", { className: "st-row" },
          h("label", { className: "st-label" }, "계층 안내선"),
          h("label", { className: "st-switch" },
            h("input", {
              type: "checkbox",
              checked: settings.guides,
              onChange: (e: React.ChangeEvent<HTMLInputElement>) => onSet("guides", e.target.checked),
            }),
            h("span", { className: "st-thumb" }))),

        h("div", { className: "st-row" },
          h("label", { className: "st-label" }, "본문 글자 크기"),
          h("div", { className: "st-range-wrap" },
            h("input", {
              type: "range",
              min: 14, max: 20, step: 1,
              value: settings.fontSize,
              onChange: (e: React.ChangeEvent<HTMLInputElement>) => onSet("fontSize", Number(e.target.value)),
            }),
            h("span", { className: "st-range-val" }, settings.fontSize + "px")))

      )
    )
  );
}
