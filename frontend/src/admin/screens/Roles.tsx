/* Admin screen 5: Roles — 실 API(useAdminData + AdminApi) 배선 */
import React from "react";
import { AdminApi, ApiRole } from "../api";
import { capLabel, KNOWN_CAPS } from "../mappers";
import { ApiError } from "../../api/http";
import { useAdminData } from "../useAdminData";
import { SecHead, Modal } from "../common";
import { roleMode, roleActionLabel, roleActionIcon } from "../roleActions";
import { Icon } from "../../components/Icon";

const { useState } = React;
const h = React.createElement;

/* cap 11종 단일 출처 = mappers.KNOWN_CAPS — admin.* / res.* 섹션은 prefix로 구분. */
const ADMIN_CAPS = KNOWN_CAPS.filter((c) => c.startsWith("admin."));
const RES_CAPS = KNOWN_CAPS.filter((c) => c.startsWith("res."));

const ID_RE = /^[a-z][a-z0-9-]*$/;
const SYSTEM_TIP = "시스템 역할은 변경할 수 없습니다";

type ModalState =
  | { kind: "create" }
  | { kind: "edit"; role: ApiRole }
  | { kind: "view"; role: ApiRole }
  | { kind: "delete"; role: ApiRole }
  | null;

/** desc 자리 대체 — caps 라벨 요약 한 줄. */
function capSummary(r: ApiRole): string {
  if (r.caps.length === 0) return "부여된 권한이 없습니다 — 편집에서 권한을 추가하세요.";
  const a = r.caps.filter((c) => c.startsWith("admin.")).length;
  const s = r.caps.length - a;
  const parts = [];
  if (a > 0) parts.push("관리 권한 " + a + "개");
  if (s > 0) parts.push("리소스 권한 " + s + "개");
  return parts.join(" · ") + " 보유 — 유효 권한은 ACL 범위와의 교집합으로 결정됩니다.";
}

export function Roles({ toast }: { toast: (msg: string, icon?: string) => void }) {
  const { roles, reload } = useAdminData();
  const [modal, setModal] = useState<ModalState>(null);
  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState({ id: "", name: "", caps: [] as string[] });

  /** 공통 변이 실행 — 성공 시 reload+toast 후 true, 실패 시 서버 메시지 토스트 후 false(모달 유지 판단용). */
  const run = async (fn: () => Promise<unknown>, okMsg: string, icon?: string): Promise<boolean> => {
    if (busy) return false;
    setBusy(true);
    try {
      await fn();
      await reload();
      toast(okMsg, icon);
      return true;
    } catch (e) {
      toast(e instanceof ApiError ? e.message : "요청 실패");
      return false;
    } finally {
      setBusy(false);
    }
  };

  const openCreate = () => { setForm({ id: "", name: "", caps: [] }); setModal({ kind: "create" }); };
  const openEdit = (r: ApiRole) => { setForm({ id: r.id, name: r.name, caps: [...r.caps] }); setModal({ kind: "edit", role: r }); };
  const openView = (r: ApiRole) => { setForm({ id: r.id, name: r.name, caps: [...r.caps] }); setModal({ kind: "view", role: r }); };
  const openAction = (r: ApiRole) => (roleMode(r) === "view" ? openView(r) : openEdit(r));

  const orderedCaps = () => KNOWN_CAPS.filter((c) => form.caps.includes(c));

  const applyCreate = async () => {
    const id = form.id.trim(), name = form.name.trim();
    if (!ID_RE.test(id)) { toast("역할 ID는 소문자로 시작하고 소문자·숫자·하이픈만 사용할 수 있습니다"); return; }
    if (!name) { toast("역할 이름을 입력하세요"); return; }
    if (await run(() => AdminApi.createRole({ id, name, caps: orderedCaps() }), "역할 \"" + name + "\" 을(를) 추가했습니다", "roles")) setModal(null);
  };
  const applyEdit = async (r: ApiRole) => {
    const name = form.name.trim();
    if (!name) { toast("역할 이름을 입력하세요"); return; }
    if (await run(() => AdminApi.updateRole(r.id, { name, caps: orderedCaps() }), "역할 \"" + name + "\" 을(를) 저장했습니다", "check")) setModal(null);
  };
  const applyDelete = async (r: ApiRole) => {
    setModal(null);
    await run(() => AdminApi.deleteRole(r.id), "역할 \"" + r.name + "\" 을(를) 삭제했습니다", "check");
  };

  const toggleCap = (c: string) =>
    setForm((f) => ({ ...f, caps: f.caps.includes(c) ? f.caps.filter((x) => x !== c) : [...f.caps, c] }));

  const capSection = (label: string, keys: string[], readOnly = false) =>
    h("div", { className: "field" },
      h("label", { className: "flabel" }, label),
      h("div", { style: { display: "grid", gridTemplateColumns: "1fr 1fr", gap: "7px 14px" } },
        keys.map((c) => h("label", { key: c, style: { display: "flex", alignItems: "center", gap: 7, fontSize: 13, color: "var(--text)", cursor: readOnly ? "default" : "pointer" } },
          h("input", { type: "checkbox", style: { accentColor: "var(--ink)" }, checked: form.caps.includes(c), disabled: readOnly, onChange: () => { if (!readOnly) toggleCap(c); } }),
          h("span", null, capLabel(c)),
          h("span", { className: "mono", style: { fontSize: 11, color: "var(--text-3)" } }, c)))));

  const nameField = (readOnly = false) => h("div", { className: "field" },
    h("label", { className: "flabel" }, "역할 이름"),
    h("input", { className: "tinput", value: form.name, placeholder: "예: 검토자", disabled: readOnly,
      onChange: (e: React.ChangeEvent<HTMLInputElement>) => setForm((f) => ({ ...f, name: e.target.value })) }));

  return h("div", { className: "apage" },
    h(SecHead, { title: "역할 관리", hint: "역할별 기본 정책",
      right: h("button", { className: "btn primary", disabled: busy, onClick: openCreate },
        h(Icon, { name: "plus" }), "역할 추가") }),
    h("div", { className: "role-list" },
      roles.map((r) => h("div", { className: "role-card", key: r.id },
        h("div", { className: "rc-head" },
          h("span", { className: "rc-name" }, r.name),
          r.system && h("span", { className: "badge role" }, "시스템"),
          h("span", { className: "badge role" }, r.userCount + "명"),
          h("span", { style: { marginLeft: "auto", display: "flex", gap: 8 } },
            h("button", { className: "btn sm", disabled: busy,
              onClick: () => openAction(r) }, h(Icon, { name: roleActionIcon(roleMode(r)) }), roleActionLabel(roleMode(r))),
            h("button", { className: "btn sm danger", disabled: busy || r.system, title: r.system ? SYSTEM_TIP : undefined,
              onClick: () => setModal({ kind: "delete", role: r }) }, "삭제"))),
        h("div", { className: "rc-desc" }, capSummary(r)),
        h("div", { className: "rc-policy" }, r.caps.map((c) => h("span", { className: "chip", key: c }, capLabel(c))))))),
    modal?.kind === "create" && h(Modal, {
      icon: "roles", title: "역할 추가", confirmLabel: "추가", wide: true,
      onConfirm: () => void applyCreate(), onClose: () => setModal(null),
    },
      h("div", { className: "field" },
        h("label", { className: "flabel" }, "역할 ID"),
        h("input", { className: "tinput mono", value: form.id, autoFocus: true, placeholder: "예: reviewer",
          onChange: (e: React.ChangeEvent<HTMLInputElement>) => setForm((f) => ({ ...f, id: e.target.value })) }),
        h("div", { style: { fontSize: 12, color: "var(--text-3)", marginTop: 5 } },
          "소문자로 시작, 소문자·숫자·하이픈(-)만 사용. 생성 후 변경할 수 없습니다.")),
      nameField(),
      capSection("관리 권한 (admin.*)", ADMIN_CAPS),
      capSection("리소스 권한 (res.*)", RES_CAPS)),
    modal?.kind === "edit" && h(Modal, {
      icon: "roles", title: "역할 편집", confirmLabel: "저장", wide: true,
      onConfirm: () => void applyEdit(modal.role), onClose: () => setModal(null),
    },
      h("div", { className: "field" },
        h("label", { className: "flabel" }, "역할 ID"),
        h("input", { className: "tinput mono", value: form.id, disabled: true })),
      nameField(),
      capSection("관리 권한 (admin.*)", ADMIN_CAPS),
      capSection("리소스 권한 (res.*)", RES_CAPS)),
    modal?.kind === "view" && h(Modal, {
      icon: "roles", title: "역할 보기", wide: true, onClose: () => setModal(null),
    },
      h("div", { className: "field" },
        h("label", { className: "flabel" }, "역할 ID"),
        h("input", { className: "tinput mono", value: form.id, disabled: true }),
        h("div", { style: { fontSize: 12, color: "var(--text-3)", marginTop: 6 } },
          "시스템 역할은 변경할 수 없습니다 — 부여된 권한만 확인할 수 있습니다.")),
      nameField(true),
      capSection("관리 권한 (admin.*)", ADMIN_CAPS, true),
      capSection("리소스 권한 (res.*)", RES_CAPS, true)),
    modal?.kind === "delete" && h(Modal, {
      icon: "roles", iconWarn: true, title: "역할 삭제", confirmLabel: "삭제", confirmDanger: true,
      onConfirm: () => void applyDelete(modal.role), onClose: () => setModal(null),
    }, h("span", null, h("b", { style: { color: "var(--ink)" } }, modal.role.name), " 역할을 삭제합니다. 사용 중인 역할은 삭제할 수 없습니다. 계속할까요?"))
  );
}
