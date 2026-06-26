import type { RedmineIssueDetail, RedmineComment } from "../api/redmine";

const dash = (s: string | null | undefined) => (s && s.trim() ? s : "-");
const day = (iso: string) => (iso ? iso.slice(0, 10) : "");

// 모든 블록은 선행 개행으로 시작 — 커서 앞 내용과 분리(특히 본문 뒤 메타 표 앞 빈 줄 보장: GFM 표는 앞에 빈 줄 필요).
export function metaTableMd(d: RedmineIssueDetail): string {
  return (
    `\n| 상태 | 담당 | 우선순위 | 마감 |\n` +
    `|---|---|---|---|\n` +
    `| ${dash(d.statusName)} | ${dash(d.assignedToName)} | ${dash(d.priorityName)} | ${dash(d.dueDate)} |\n` +
    `\n> 🔗 redmine #${d.id} · ${d.subject}\n`
  );
}

export function bodyMd(d: RedmineIssueDetail): string {
  return `\n${(d.description ?? "").trim()}\n`;
}

export function commentMd(c: RedmineComment): string {
  const quoted = (c.notes ?? "").split("\n").map((l) => `> ${l}`).join("\n");
  return `\n> **${c.userName ?? "?"}** ${day(c.createdOn)}\n>\n${quoted}\n`;
}
