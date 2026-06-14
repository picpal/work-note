/* Admin screen: Uploads — 첨부 허용 확장자(chip add/remove) + 파일당 최대 용량(MB) 정책 편집.
   정책은 app_setting 기반(GET/PUT /admin/settings/upload). Roles.tsx의 run() 패턴 계승. */
import React from "react";
import { AdminApi } from "../api";
import { ApiError } from "../../api/http";
import { SecHead } from "../common";
import { Icon } from "../../components/Icon";

const { useState, useEffect } = React;
const h = React.createElement;

export function Uploads({ toast }: { toast: (msg: string, icon?: string) => void }) {
  const [exts, setExts] = useState<string[]>([]);
  const [maxMb, setMaxMb] = useState(25);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    void AdminApi.getUploadPolicy()
      .then((p) => { setExts(p.allowedExt); setMaxMb(Math.max(1, Math.round(p.maxBytes / 1024 / 1024))); setLoaded(true); })
      .catch(() => toast("정책을 불러오지 못했습니다"));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const addExt = (v: string) => {
    const e = v.trim().toLowerCase().replace(/^\./, "");
    if (e && !exts.includes(e)) setExts((xs) => [...xs, e]);
    setDraft("");
  };
  const removeExt = (e: string) => setExts((xs) => xs.filter((x) => x !== e));

  const save = async () => {
    if (busy) return;
    if (exts.length === 0) { toast("허용 확장자를 1개 이상 지정하세요"); return; }
    if (!(maxMb >= 1)) { toast("최대 용량은 1MB 이상이어야 합니다"); return; }
    setBusy(true);
    try {
      await AdminApi.setUploadPolicy(exts, maxMb * 1024 * 1024);
      toast("저장했습니다", "check");
    } catch (e) {
      toast(e instanceof ApiError ? e.message : "저장 실패");
    } finally {
      setBusy(false);
    }
  };

  const hasSvg = exts.includes("svg");

  return h("div", { className: "apage" },
    h(SecHead, { title: "업로드 정책", hint: "첨부 허용 확장자·파일당 용량",
      right: h("button", { className: "btn primary", disabled: busy || !loaded, onClick: () => void save() },
        h(Icon, { name: "check" }), "저장") }),

    hasSvg && h("div", { className: "changebar", style: { position: "static", marginTop: 0, marginBottom: 16 } },
      h(Icon, { name: "info" }),
      h("span", { className: "txt" },
        h("b", null, "SVG"), " 는 스크립트를 포함할 수 있어 위험합니다. 신뢰할 수 있는 출처만 허용하세요.")),

    h("div", { className: "panel" },
      h("div", { className: "panel-head" }, h(Icon, { name: "image" }), "허용 확장자"),
      h("div", { className: "panel-body" },
        h("div", { style: { display: "flex", flexWrap: "wrap", gap: 8, marginBottom: 12 } },
          exts.length === 0
            ? h("span", { style: { fontSize: 12.5, color: "var(--text-3)" } }, "허용된 확장자가 없습니다 — 추가하세요.")
            : exts.map((e) => h("span", {
                key: e, className: "chip",
                style: { display: "inline-flex", alignItems: "center", gap: 6 },
              },
                h("span", { className: "mono" }, "." + e),
                h("button", {
                  title: "삭제", onClick: () => removeExt(e),
                  style: { border: "none", background: "none", cursor: "pointer", color: "var(--text-3)", fontSize: 14, lineHeight: 1, padding: 0 },
                }, "×")))),
        h("div", { style: { display: "flex", gap: 8, alignItems: "center", maxWidth: 320 } },
          h("input", {
            className: "tinput mono", value: draft, placeholder: "예: png (Enter로 추가)",
            onChange: (ev: React.ChangeEvent<HTMLInputElement>) => setDraft(ev.target.value),
            onKeyDown: (ev: React.KeyboardEvent<HTMLInputElement>) => {
              if (ev.key === "Enter" || ev.key === ",") { ev.preventDefault(); addExt(draft); }
            },
          }),
          h("button", { className: "btn", onClick: () => addExt(draft) }, h(Icon, { name: "plus" }), "추가")),
        h("div", { style: { fontSize: 12, color: "var(--text-3)", marginTop: 8 } },
          "점(.) 없이 소문자로 입력합니다. 마지막 확장자 기준으로 검사하며, 위반 시 업로드가 거부됩니다."))),

    h("div", { className: "panel", style: { marginTop: 16 } },
      h("div", { className: "panel-head" }, h(Icon, { name: "settings" }), "파일당 최대 용량"),
      h("div", { className: "panel-body" },
        h("div", { className: "frow", style: { paddingTop: 0 } },
          h("div", { className: "fmeta" },
            h("div", { className: "ft" }, "최대 용량 (MB)"),
            h("div", { className: "fd" }, "이 값을 초과하는 파일은 업로드 시 거부됩니다.")),
          h("div", { className: "fctl" },
            h("input", {
              className: "tinput", type: "number", min: 1, value: maxMb,
              style: { width: 100, textAlign: "right" },
              onChange: (ev: React.ChangeEvent<HTMLInputElement>) => setMaxMb(Math.max(1, Math.floor(Number(ev.target.value) || 0))),
            }),
            h("span", { style: { fontSize: 13, color: "var(--text-2)" } }, "MB"))))));
}
