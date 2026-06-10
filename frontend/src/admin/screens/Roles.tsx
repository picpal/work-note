/* Admin screen 5: Roles */
import React from "react";
import { ADMIN_ROLES, Role } from "../data";
import { SecHead } from "../common";
import { Icon } from "../../components/Icon";

const { useState } = React;
const h = React.createElement;

function RoleEditor({ initial, isNew, existingNames, onSave, onClose }: {
  initial: Partial<Role> & { __new?: boolean };
  isNew: boolean;
  existingNames: string[];
  onSave: (role: Partial<Role> & { __new?: boolean }) => void;
  onClose: () => void;
}) {
  const [name, setName] = useState(initial.name || "");
  const [desc, setDesc] = useState(initial.desc || "");
  const [policy, setPolicy] = useState(initial.policy ? [...initial.policy] : []);
  const [draft, setDraft] = useState("");
  const [err, setErr] = useState("");
  const locked = initial.system; // system role name can't be renamed

  const addChip = () => {
    const v = draft.trim();
    if (!v) return;
    if (!policy.includes(v)) setPolicy((p) => [...p, v]);
    setDraft("");
  };
  const save = () => {
    const nm = name.trim();
    if (!nm) { setErr("역할 이름을 입력하세요."); return; }
    if (existingNames.filter((x) => x !== initial.name).includes(nm)) { setErr("이미 존재하는 역할 이름입니다."); return; }
    onSave({ ...initial, name: nm, desc: desc.trim(), policy });
  };

  return h("div", { className: "modal-ov", onMouseDown: onClose },
    h("div", { className: "modal", style: { width: "min(540px, 94vw)" }, onMouseDown: (e: React.MouseEvent) => e.stopPropagation() },
      h("div", { className: "modal-head" },
        h("div", { className: "micon" }, h(Icon, { name: "roles" })),
        h("h3", null, isNew ? "역할 추가" : "역할 편집")),
      h("div", { className: "modal-body", style: { paddingLeft: 20, paddingRight: 20 } },
        h("div", { className: "field" },
          h("label", { className: "flabel" }, "역할 이름", locked ? " (시스템 역할 — 변경 불가)" : ""),
          h("input", { className: "tinput", value: name, disabled: locked, placeholder: "예: 검토자",
            onChange: (e: React.ChangeEvent<HTMLInputElement>) => { setName(e.target.value); setErr(""); } })),
        h("div", { className: "field" },
          h("label", { className: "flabel" }, "설명"),
          h("textarea", { className: "tarea", value: desc, placeholder: "이 역할의 기본 정책을 설명하세요",
            onChange: (e: React.ChangeEvent<HTMLTextAreaElement>) => setDesc(e.target.value) })),
        h("div", { className: "field" },
          h("label", { className: "flabel" }, "정책 (권한 항목)"),
          h("div", { className: "chip-wrap" },
            policy.map((p, i) => h("span", { className: "chip chip-edit", key: i }, p,
              h("button", { title: "삭제", onClick: () => setPolicy((arr) => arr.filter((_, j) => j !== i)) }, "×"))),
            h("input", { className: "chip-add", value: draft, placeholder: "+ 항목 추가",
              onChange: (e: React.ChangeEvent<HTMLInputElement>) => setDraft(e.target.value),
              onKeyDown: (e: React.KeyboardEvent<HTMLInputElement>) => { if (e.key === "Enter" || e.key === ",") { e.preventDefault(); addChip(); } },
              onBlur: addChip }))),
        err && h("div", { style: { color: "#b3261e", fontSize: 12.5, marginTop: 12 } }, err)),
      h("div", { className: "modal-foot" },
        h("button", { className: "btn", onClick: onClose }, "취소"),
        h("button", { className: "btn primary", onClick: save }, isNew ? "추가" : "저장"))));
}

export function Roles({ toast }: { toast: (msg: string, icon?: string) => void }) {
  const [roles, setRoles] = useState(() => ADMIN_ROLES.map((r) => ({ ...r, policy: [...r.policy] })));
  const [editing, setEditing] = useState<(Partial<Role> & { __new?: boolean }) | null>(null); // role obj | {__new:true} | null

  const onSave = (role: Partial<Role> & { __new?: boolean }) => {
    if (role.__new) {
      const id = "role-" + Date.now().toString(36);
      setRoles((rs) => [...rs, { id, name: role.name!, desc: role.desc!, policy: role.policy!, system: false, count: 0 }]);
      toast("역할 \"" + role.name + "\" 을(를) 추가했습니다", "check");
    } else {
      setRoles((rs) => rs.map((r) => r.id === role.id ? { ...r, name: role.name!, desc: role.desc!, policy: role.policy! } : r));
      toast("역할 \"" + role.name + "\" 을(를) 저장했습니다", "check");
    }
    setEditing(null);
  };

  return h("div", { className: "apage" },
    h(SecHead, { title: "역할 관리", hint: "역할별 기본 정책",
      right: h("button", { className: "btn primary", onClick: () => setEditing({ __new: true, policy: [] }) },
        h(Icon, { name: "plus" }), "역할 추가") }),
    h("div", { className: "role-list" },
      roles.map((r) => h("div", { className: "role-card", key: r.id },
        h("div", { className: "rc-head" },
          h("span", { className: "rc-name" }, r.name),
          r.system && h("span", { className: "badge role" }, "시스템"),
          h("span", { className: "badge role" }, r.count + "명"),
          h("span", { style: { marginLeft: "auto" } },
            h("button", { className: "btn sm", onClick: () => setEditing(r) }, h(Icon, { name: "edit" }), "편집"))),
        h("div", { className: "rc-desc" }, r.desc),
        h("div", { className: "rc-policy" }, r.policy.map((p, i) => h("span", { className: "chip", key: i }, p)))))),
    editing && h(RoleEditor, {
      initial: editing.__new ? { __new: true, name: "", desc: "", policy: [], system: false } : editing,
      isNew: !!editing.__new,
      existingNames: roles.map((r) => r.name),
      onSave, onClose: () => setEditing(null),
    })
  );
}
