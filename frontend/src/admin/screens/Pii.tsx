/* Admin screen: 개인정보 점검 — 예외 요청 대기 + 전체 플래그 노트 */
import React from "react";
import { AdminApi, type ApiPiiNote, type ApiPiiRequest, type ApiPiiContent } from "../api";
import { PiiNoteViewer } from "../../components/PiiNoteViewer";
import { ApiError } from "../../api/http";
import { SecHead, Empty, Modal } from "../common";
import { piiTypeLabel, piiStatusLabel } from "../../lib/pii";

const { useState, useEffect, useCallback } = React;
const h = React.createElement;

const typeChips = (csv: string) =>
  (csv ? csv.split(",") : []).map((t) => h("span", { key: t, className: "chip" }, piiTypeLabel(t)));

export function Pii({ toast }: { toast: (msg: string, icon?: string) => void }) {
  const [reqs, setReqs] = useState<ApiPiiRequest[]>([]);
  const [notes, setNotes] = useState<ApiPiiNote[]>([]);
  const [reject, setReject] = useState<{ nodeId: string; title: string } | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [viewing, setViewing] = useState<{ data: ApiPiiContent; source: "request" | "note" | "exempted" } | null>(null);

  const openViewer = async (nodeId: string, source: "request" | "note" | "exempted") => {
    try {
      const data = await AdminApi.piiNoteContent(nodeId);
      setViewing({ data, source });
    } catch (e) { toast(e instanceof ApiError ? e.message : "본문을 불러올 수 없습니다"); }
  };

  const load = useCallback(async () => {
    try {
      const [r, n] = await Promise.all([AdminApi.piiRequests(), AdminApi.piiNotes()]);
      setReqs(r); setNotes(n);
    } catch (e) { toast(e instanceof ApiError ? e.message : "불러오기 실패"); }
  }, [toast]);
  useEffect(() => { void load(); }, [load]);

  const approve = async (nodeId: string) => {
    setBusy(nodeId);
    try { await AdminApi.piiApprove(nodeId); await load(); toast("예외를 허용했습니다", "check"); }
    catch (e) { toast(e instanceof ApiError ? e.message : "실패"); }
    finally { setBusy(null); }
  };
  const doReject = async () => {
    const { nodeId } = reject!; setReject(null);
    setBusy(nodeId);
    try { await AdminApi.piiReject(nodeId, reason); setReason(""); await load(); toast("예외를 반려했습니다", "ban"); }
    catch (e) { toast(e instanceof ApiError ? e.message : "실패"); }
    finally { setBusy(null); }
  };
  const notify = async (nodeId: string) => {
    setBusy(nodeId);
    try { await AdminApi.piiNotice(nodeId); toast("최종 수정자에게 알림을 보냈습니다", "check"); }
    catch (e) { toast(e instanceof ApiError ? e.message : "실패"); }
    finally { setBusy(null); }
  };

  const shown = notes.filter((n) => n.status !== "exempted");
  const exempted = notes.filter((n) => n.status === "exempted");

  return h("div", { className: "apage" },
    h(SecHead, { title: "예외 요청 대기", hint: "사용자가 올린 개인정보 탐지 예외 요청을 검토합니다" }),
    reqs.length === 0
      ? h(Empty, { icon: "userCheck", title: "대기 중인 예외 요청이 없습니다", desc: "요청이 들어오면 이곳에 표시됩니다." })
      : h("div", { className: "table-wrap" }, h("table", { className: "atable" },
          h("thead", null, h("tr", null,
            h("th", null, "노트"), h("th", null, "최종 수정자"), h("th", null, "탐지 유형"),
            h("th", null, "사유"), h("th", { className: "right" }, "처리"))),
          h("tbody", null, reqs.map((r) => h("tr", { key: r.nodeId, className: "click-row", onClick: () => void openViewer(r.nodeId, "request") },
            h("td", null, r.title),
            h("td", { className: "mono" }, r.updatedBy ?? "—"),
            h("td", null, h("div", { className: "chips" }, typeChips(r.types))),
            h("td", null, r.requestReason || "—"),
            h("td", { className: "right" }, h("div", { className: "actions" },
              h("button", { className: "btn sm primary", disabled: busy === r.nodeId, onClick: (e: React.MouseEvent) => { e.stopPropagation(); void approve(r.nodeId); } }, "허용"),
              h("button", { className: "btn sm danger", disabled: busy === r.nodeId, onClick: (e: React.MouseEvent) => { e.stopPropagation(); setReject({ nodeId: r.nodeId, title: r.title }); } }, "반려")))))))),

    h("div", { style: { height: 22 } }),
    h(SecHead, { title: "전체 개인정보 노트", hint: "탐지된 모든 노트(허용 제외). 능동 알림 발송 가능" }),
    shown.length === 0
      ? h(Empty, { icon: "shield", title: "표시할 노트가 없습니다", desc: "탐지된 노트가 이곳에 나열됩니다." })
      : h("div", { className: "table-wrap" }, h("table", { className: "atable pii-center-table" },
          h("thead", null, h("tr", { className: "pii-head-center" },
            h("th", null, "노트"), h("th", null, "최종 수정자"), h("th", null, "탐지 유형"),
            h("th", null, "상태"), h("th", null, "탐지 시각"), h("th", { className: "right" }, "알림"))),
          h("tbody", null, shown.map((n) => h("tr", { key: n.nodeId, className: "click-row", onClick: () => void openViewer(n.nodeId, "note") },
            h("td", null, n.title),
            h("td", { className: "mono" }, n.updatedBy ?? "—"),
            h("td", null, h("div", { className: "chips" }, typeChips(n.types))),
            h("td", null, piiStatusLabel(n.status)),
            h("td", { className: "mono" }, n.detectedAt?.slice(0, 16).replace("T", " ")),
            h("td", null,
              h("button", { className: "btn sm", disabled: busy === n.nodeId || !n.updatedBy, onClick: (e: React.MouseEvent) => { e.stopPropagation(); void notify(n.nodeId); } }, "알림 보내기"))))))),

    h("div", { style: { height: 22 } }),
    h(SecHead, { title: "예외 처리된 노트", hint: "관리자가 허용한 개인정보 예외 노트. 값이 바뀌면 자동으로 다시 탐지됩니다" }),
    exempted.length === 0
      ? h(Empty, { icon: "shieldCheck", title: "예외 처리된 노트가 없습니다", desc: "예외를 허용하면 이곳에 모입니다." })
      : h("div", { className: "table-wrap" }, h("table", { className: "atable" },
          h("thead", null, h("tr", null,
            h("th", null, "노트"), h("th", null, "최종 수정자"), h("th", null, "탐지 유형"),
            h("th", null, "탐지 시각"))),
          h("tbody", null, exempted.map((n) => h("tr", { key: n.nodeId, className: "click-row", onClick: () => void openViewer(n.nodeId, "exempted") },
            h("td", null, n.title),
            h("td", { className: "mono" }, n.updatedBy ?? "—"),
            h("td", null, h("div", { className: "chips" }, typeChips(n.types))),
            h("td", { className: "mono" }, n.detectedAt?.slice(0, 16).replace("T", " "))))))),

    viewing && h(PiiNoteViewer, {
      data: viewing.data,
      source: viewing.source,
      busy: busy === viewing.data.nodeId,
      onApprove: () => { const id = viewing.data.nodeId; setViewing(null); void approve(id); },
      onReject: () => { const { nodeId, title } = viewing.data; setViewing(null); setReject({ nodeId, title }); },
      onNotice: () => { const id = viewing.data.nodeId; setViewing(null); void notify(id); },
      onClose: () => setViewing(null),
    }),
    reject && h(Modal, {
      icon: "ban", iconWarn: true, title: "예외 반려", confirmLabel: "반려", confirmDanger: true,
      onConfirm: () => { void doReject(); }, onClose: () => { setReject(null); setReason(""); },
    },
      h("div", null,
        h("p", { style: { marginBottom: 8 } }, h("b", null, reject.title), " 의 예외 요청을 반려합니다. 사유를 남기면 요청자에게 전달됩니다."),
        h("input", { className: "tinput", placeholder: "반려 사유(선택)", value: reason,
          onChange: (e: React.ChangeEvent<HTMLInputElement>) => setReason(e.target.value) })))
  );
}
