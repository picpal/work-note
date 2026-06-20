/* Admin screen 3: User management — 실 API(useAdminData + AdminApi) 배선 */
import React from "react";
import { AdminApi, ApiUser } from "../api";
import { statusLabel, roleName } from "../mappers";
import { ApiError } from "../../api/http";
import { useAdminData } from "../useAdminData";
import { SecHead, Empty, Modal, StatusBadge, RoleBadge } from "../common";
import { Icon } from "../../components/Icon";
import { MIN_PASSWORD_LENGTH } from "../../lib/passwordPolicy";

const { useState, useMemo } = React;
const h = React.createElement;

type ModalState =
  | { kind: "deactivate"; user: ApiUser }
  | { kind: "resetPw"; user: ApiUser }
  | { kind: "role"; user: ApiUser }
  | { kind: "resetTotp"; user: ApiUser }
  | { kind: "create" }
  | null;

const EMPTY_FORM = { emp: "", name: "", email: "", roleId: "", password: "" };

export function Users({ toast }: { toast: (msg: string, icon?: string) => void }) {
  const { me, users, roles, reload } = useAdminData();
  const [q, setQ] = useState("");
  const [role, setRole] = useState("all");
  const [status, setStatus] = useState("all");
  const [selId, setSelId] = useState<string | null>(null);    // selected user for detail panel
  const [modal, setModal] = useState<ModalState>(null);
  const [busy, setBusy] = useState(false);
  const [pw, setPw] = useState("");                           // resetPw modal input
  const [pickRole, setPickRole] = useState("");               // role modal select
  const [form, setForm] = useState(EMPTY_FORM);               // create modal form

  const sel = useMemo(() => users.find((u) => u.id === selId) ?? null, [users, selId]);
  const isMe = (u: ApiUser) => me?.id === u.id;

  const filtered = useMemo(() => users.filter((u) => {
    const needle = q.toLowerCase();
    if (q && !(u.emp.toLowerCase().includes(needle) || u.name.toLowerCase().includes(needle) || (u.email ?? "").toLowerCase().includes(needle))) return false;
    if (role !== "all" && u.roleId !== role) return false;
    if (status !== "all" && u.status !== status) return false;
    return true;
  }), [users, q, role, status]);

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

  const deactivate = async (u: ApiUser) => {
    setModal(null);
    await run(() => AdminApi.updateUser(u.id, { status: "disabled" }), u.emp + " 계정을 비활성화했습니다", "ban");
  };
  const activate = (u: ApiUser) =>
    run(() => AdminApi.updateUser(u.id, { status: "active" }), u.emp + " 계정을 활성화했습니다", "userCheck");
  const applyRole = async (u: ApiUser) => {
    if (await run(() => AdminApi.updateUser(u.id, { roleId: pickRole }), u.emp + " 역할을 변경했습니다", "roles")) setModal(null);
  };
  const applyResetPw = async (u: ApiUser) => {
    if (pw.length < MIN_PASSWORD_LENGTH) { toast("비밀번호는 " + MIN_PASSWORD_LENGTH + "자 이상이어야 합니다"); return; }
    if (await run(() => AdminApi.resetPassword(u.id, pw), "비밀번호를 초기화했습니다 — 해당 사용자의 기존 세션은 무효화됩니다", "refresh")) setModal(null);
  };
  const applyResetTotp = async (u: ApiUser) => {
    if (await run(() => AdminApi.resetTotp(u.id), u.emp + "의 2FA가 초기화되었습니다 — 재등록이 필요합니다", "refresh")) setModal(null);
  };
  const applyCreate = async () => {
    if (!form.emp.trim() || !form.name.trim()) { toast("사번과 이름을 입력하세요"); return; }
    if (form.password.length < MIN_PASSWORD_LENGTH) { toast("비밀번호는 " + MIN_PASSWORD_LENGTH + "자 이상이어야 합니다"); return; }
    const body = { emp: form.emp.trim(), name: form.name.trim(), roleId: form.roleId, password: form.password,
      ...(form.email.trim() ? { email: form.email.trim() } : {}) };
    if (await run(() => AdminApi.createUser(body), form.emp.trim() + " 계정을 생성했습니다", "userCheck")) setModal(null);
  };

  const openResetPw = (u: ApiUser) => { setPw(""); setModal({ kind: "resetPw", user: u }); };
  const openRole = (u: ApiUser) => { setPickRole(u.roleId); setModal({ kind: "role", user: u }); };
  const openCreate = () => {
    setForm({ ...EMPTY_FORM, roleId: roles.find((r) => !r.caps.includes("admin.users"))?.id ?? roles[0]?.id ?? "" });
    setModal({ kind: "create" });
  };

  const fld = (label: string, input: React.ReactNode) =>
    h("div", { style: { marginBottom: 10 } }, h("label", { className: "flabel" }, label), input);

  return h("div", { className: "apage wide" },
    h(SecHead, { title: "사용자 관리", hint: filtered.length + "명",
      right: h("button", { className: "btn sm primary", disabled: busy, onClick: openCreate }, "사용자 추가") }),
    h("div", { className: "atoolbar" },
      h("div", { className: "afield" }, h(Icon, { name: "search" }),
        h("input", { placeholder: "사번·이름·이메일 검색", value: q, onChange: (e: React.ChangeEvent<HTMLInputElement>) => setQ(e.target.value) })),
      h("select", { className: "aselect", value: role, onChange: (e: React.ChangeEvent<HTMLSelectElement>) => setRole(e.target.value) },
        h("option", { value: "all" }, "전체 역할"),
        roles.map((r) => h("option", { key: r.id, value: r.id }, r.name))),
      h("select", { className: "aselect", value: status, onChange: (e: React.ChangeEvent<HTMLSelectElement>) => setStatus(e.target.value) },
        h("option", { value: "all" }, "전체 상태"),
        (["active", "disabled", "pending"] as const).map((s) => h("option", { key: s, value: s }, statusLabel(s))))),
    h("div", { style: { display: "grid", gridTemplateColumns: sel ? "1fr 340px" : "1fr", gap: 16, alignItems: "start" } },
      filtered.length === 0
        ? h(Empty, { icon: "users", title: "조건에 맞는 사용자가 없습니다", desc: "검색어나 필터를 조정해 보세요." })
        : h("div", { className: "table-wrap" },
            h("table", { className: "atable" },
              h("thead", null, h("tr", null,
                h("th", null, "사번"), h("th", null, "이메일"), h("th", null, "역할"),
                h("th", null, "상태"), h("th", null, "2FA"), h("th", null, "마지막 로그인"), h("th", { className: "center" }, "작업"))),
              h("tbody", null,
                filtered.map((u) => h("tr", { key: u.id, style: { cursor: "default" } },
                  h("td", { className: "mono" }, u.emp),
                  h("td", null, u.email ?? "—"),
                  h("td", null, h(RoleBadge, { role: roleName(u.roleId, roles) })),
                  h("td", null, h(StatusBadge, { status: statusLabel(u.status) })),
                  h("td", null, u.totpEnabled
                    ? h("span", { className: "sec-badge sec-badge--on", style: { fontSize: 11 } }, "2FA")
                    : h("span", { style: { color: "var(--text-3)" } }, "—")),
                  h("td", { className: "muted mono" }, u.lastLogin ? u.lastLogin.slice(0, 10) : "—"),
                  h("td", { className: "center" },
                    h("div", { className: "actions" },
                      h("button", { className: "lact", onClick: () => setSelId(u.id) }, "상세"),
                      h("button", { className: "lact", disabled: busy, onClick: () => openResetPw(u) }, "비번 초기화"),
                      u.totpEnabled && h("button", { className: "lact", disabled: busy, onClick: () => setModal({ kind: "resetTotp", user: u }) }, "2FA 초기화"),
                      u.status === "active" && h("button", { className: "lact danger", disabled: busy || isMe(u), title: isMe(u) ? "본인 계정은 변경할 수 없습니다" : undefined, onClick: () => setModal({ kind: "deactivate", user: u }) }, "비활성화"),
                      u.status === "disabled" && h("button", { className: "lact", disabled: busy || isMe(u), onClick: () => void activate(u) }, "활성화"))))))) ),
      sel && h("div", { className: "panel" },
        h("div", { className: "panel-head" }, h(Icon, { name: "user" }), "사용자 상세",
          h("button", { className: "lact", style: { marginLeft: "auto" }, onClick: () => setSelId(null) }, h(Icon, { name: "x" }))),
        h("div", { className: "panel-body" },
          h("div", { className: "kv" },
            h("div", { className: "row" }, h("span", { className: "k" }, "사번"), h("span", { className: "v mono" }, sel.emp)),
            h("div", { className: "row" }, h("span", { className: "k" }, "이름"), h("span", { className: "v" }, sel.name)),
            h("div", { className: "row" }, h("span", { className: "k" }, "이메일"), h("span", { className: "v" }, sel.email ?? "—")),
            h("div", { className: "row" }, h("span", { className: "k" }, "역할"), h("span", { className: "v" }, h(RoleBadge, { role: roleName(sel.roleId, roles) }))),
            h("div", { className: "row" }, h("span", { className: "k" }, "상태"), h("span", { className: "v" }, h(StatusBadge, { status: statusLabel(sel.status) }))),
            h("div", { className: "row" }, h("span", { className: "k" }, "마지막 로그인"), h("span", { className: "v mono muted" }, sel.lastLogin ?? "—")),
            h("div", { className: "row" }, h("span", { className: "k" }, "2FA"),
              h("span", { className: "v" }, sel.totpEnabled
                ? h("span", { className: "sec-badge sec-badge--on", style: { fontSize: 11 } }, "활성")
                : h("span", { style: { color: "var(--text-3)" } }, "미등록")))),
          h("div", { style: { marginTop: 18, fontSize: 12.5, color: "var(--text-3)", lineHeight: 1.6 } },
            "리소스 단위 접근 권한은 권한 관리 화면에서 부여·회수합니다."),
          h("div", { className: "btn-row", style: { marginTop: 18 } },
            h("button", { className: "btn sm", disabled: busy || isMe(sel), title: isMe(sel) ? "본인 계정은 변경할 수 없습니다" : undefined, onClick: () => openRole(sel) }, "역할 변경"),
            h("button", { className: "btn sm", disabled: busy, onClick: () => openResetPw(sel) }, "비밀번호 초기화"),
            sel.totpEnabled && h("button", { className: "btn sm danger", disabled: busy, onClick: () => setModal({ kind: "resetTotp", user: sel }) }, "2FA 초기화"))))),
    modal?.kind === "deactivate" && h(Modal, {
      icon: "ban", iconWarn: true, title: "계정 비활성화", confirmLabel: "비활성화", confirmDanger: true,
      onConfirm: () => void deactivate(modal.user), onClose: () => setModal(null),
    }, h("span", null, h("b", { className: "mono", style: { color: "var(--ink)" } }, modal.user.emp), " 계정을 비활성화합니다. 해당 사용자는 로그인할 수 없게 됩니다. 계속할까요?")),
    modal?.kind === "role" && h(Modal, {
      icon: "roles", title: "역할 변경", confirmLabel: "변경",
      onConfirm: () => void applyRole(modal.user), onClose: () => setModal(null),
    },
      h("div", { style: { marginBottom: 10 } },
        h("b", { className: "mono", style: { color: "var(--ink)" } }, modal.user.emp), " 계정의 역할을 변경합니다."),
      fld("역할", h("select", { className: "aselect", style: { width: "100%" }, value: pickRole,
        onChange: (e: React.ChangeEvent<HTMLSelectElement>) => setPickRole(e.target.value) },
        roles.map((r) => h("option", { key: r.id, value: r.id }, r.name))))),
    modal?.kind === "resetPw" && h(Modal, {
      icon: "refresh", title: "비밀번호 초기화", confirmLabel: "초기화",
      onConfirm: () => void applyResetPw(modal.user), onClose: () => setModal(null),
    },
      h("div", { style: { marginBottom: 10 } },
        h("b", { className: "mono", style: { color: "var(--ink)" } }, modal.user.emp), " 계정의 비밀번호를 새로 설정합니다. 초기화 시 해당 사용자의 기존 세션은 모두 무효화됩니다.",
        modal.user.id === me?.id ? " 본인 계정이므로 초기화 직후 다시 로그인해야 합니다." : null),
      fld("새 비밀번호 (10자 이상)", h("input", { className: "tinput", type: "password", value: pw, autoFocus: true,
        onChange: (e: React.ChangeEvent<HTMLInputElement>) => setPw(e.target.value) }))),
    modal?.kind === "resetTotp" && h(Modal, {
      icon: "refresh", iconWarn: true, title: "2FA 초기화", confirmLabel: "초기화", confirmDanger: true,
      onConfirm: () => void applyResetTotp(modal.user), onClose: () => setModal(null),
    }, h("span", null,
      h("b", { className: "mono", style: { color: "var(--ink)" } }, modal.user.emp),
      " 계정의 2FA(TOTP)를 초기화합니다. 해당 사용자는 다음 로그인 시 2FA를 재등록해야 합니다. 계속할까요?")),
    modal?.kind === "create" && h(Modal, {
      icon: "userCheck", title: "사용자 추가", confirmLabel: "생성",
      onConfirm: () => void applyCreate(), onClose: () => setModal(null),
    },
      fld("사번", h("input", { className: "tinput", value: form.emp, autoFocus: true, placeholder: "예: 24011",
        onChange: (e: React.ChangeEvent<HTMLInputElement>) => setForm((f) => ({ ...f, emp: e.target.value })) })),
      fld("이름", h("input", { className: "tinput", value: form.name,
        onChange: (e: React.ChangeEvent<HTMLInputElement>) => setForm((f) => ({ ...f, name: e.target.value })) })),
      fld("이메일 (선택)", h("input", { className: "tinput", type: "email", value: form.email,
        onChange: (e: React.ChangeEvent<HTMLInputElement>) => setForm((f) => ({ ...f, email: e.target.value })) })),
      fld("역할", h("select", { className: "aselect", style: { width: "100%" }, value: form.roleId,
        onChange: (e: React.ChangeEvent<HTMLSelectElement>) => setForm((f) => ({ ...f, roleId: e.target.value })) },
        roles.map((r) => h("option", { key: r.id, value: r.id }, r.name)))),
      fld("비밀번호 (10자 이상)", h("input", { className: "tinput", type: "password", value: form.password,
        onChange: (e: React.ChangeEvent<HTMLInputElement>) => setForm((f) => ({ ...f, password: e.target.value })) })))
  );
}
