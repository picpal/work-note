/* Admin screen: Redmine — 이슈 임포트 연동 설정(활성화·서버 주소).
   app_setting 기반(GET/PUT /admin/settings/redmine). Uploads.tsx 패턴 계승. */
import React from "react";
import { AdminApi } from "../api";
import { ApiError } from "../../api/http";
import { SecHead } from "../common";
import { Icon } from "../../components/Icon";

const { useState, useEffect } = React;
const h = React.createElement;

export function Redmine({ toast }: { toast: (msg: string, icon?: string) => void }) {
  const [enabled, setEnabled] = useState(false);
  const [baseUrl, setBaseUrl] = useState("");
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    void AdminApi.getRedmineConfig()
      .then((c) => { setEnabled(c.enabled); setBaseUrl(c.baseUrl || ""); setLoaded(true); })
      .catch(() => toast("설정을 불러오지 못했습니다"));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const save = async () => {
    if (busy) return;
    if (enabled && !baseUrl.trim()) { toast("활성화하려면 서버 주소를 입력하세요"); return; }
    setBusy(true);
    try {
      const c = await AdminApi.setRedmineConfig({ enabled, baseUrl: baseUrl.trim() });
      setEnabled(c.enabled); setBaseUrl(c.baseUrl || "");
      toast("저장했습니다", "check");
    } catch (e) {
      toast(e instanceof ApiError ? e.message : "저장 실패");
    } finally {
      setBusy(false);
    }
  };

  return h("div", { className: "apage" },
    h(SecHead, { title: "Redmine 연동", hint: "이슈 임포트 활성화·서버 주소",
      right: h("button", { className: "btn primary", disabled: busy || !loaded, onClick: () => void save() },
        h(Icon, { name: "check" }), "저장") }),

    h("div", { className: "panel" },
      h("div", { className: "panel-head" }, h(Icon, { name: "link" }), "연동 설정"),
      h("div", { className: "panel-body" },
        h("div", { className: "frow", style: { paddingTop: 0 } },
          h("div", { className: "fmeta" },
            h("div", { className: "ft" }, "이슈 임포트 활성화"),
            h("div", { className: "fd" }, "끄면 사용자 키 등록·이슈 가져오기가 모두 비활성화됩니다.")),
          h("div", { className: "fctl" },
            h("label", { style: { display: "inline-flex", alignItems: "center", gap: 8, cursor: "pointer" } },
              h("input", { type: "checkbox", checked: enabled,
                onChange: (e: React.ChangeEvent<HTMLInputElement>) => setEnabled(e.target.checked) }),
              h("span", { style: { fontSize: 13, color: "var(--text-2)" } }, enabled ? "켜짐" : "꺼짐")))),
        h("div", { className: "frow" },
          h("div", { className: "fmeta" },
            h("div", { className: "ft" }, "Redmine 서버 주소"),
            h("div", { className: "fd" }, "사내 Redmine 베이스 URL. 예: http://redmine.intra")),
          h("div", { className: "fctl" },
            h("input", { className: "tinput", value: baseUrl, placeholder: "http://redmine.intra",
              style: { width: 280 },
              onChange: (e: React.ChangeEvent<HTMLInputElement>) => setBaseUrl(e.target.value) }))),
        h("div", { style: { fontSize: 12, color: "var(--text-3)", marginTop: 8 } },
          "사용자는 프로필 > Redmine 연동에서 본인 API 키를 등록하며, 이슈는 각자 권한 범위에서만 조회됩니다."))));
}
