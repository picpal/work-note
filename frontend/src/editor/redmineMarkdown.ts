import type { RedmineIssueDetail, RedmineComment } from "../api/redmine";

const dash = (s: string | null | undefined) => (s && s.trim() ? s : "-");
const day = (iso: string) => (iso ? iso.slice(0, 10) : "");

export function metaTableMd(d: RedmineIssueDetail): string {
  return (
    `| 상태 | 담당 | 우선순위 | 마감 |\n` +
    `|---|---|---|---|\n` +
    `| ${dash(d.statusName)} | ${dash(d.assignedToName)} | ${dash(d.priorityName)} | ${dash(d.dueDate)} |\n` +
    `\n> 🔗 redmine #${d.id} · ${d.subject}\n`
  );
}

export function bodyMd(d: RedmineIssueDetail): string {
  return `${(d.description ?? "").trim()}\n`;
}

export function commentMd(c: RedmineComment): string {
  const quoted = (c.notes ?? "").split("\n").map((l) => `> ${l}`).join("\n");
  return `> **${c.userName ?? "?"}** ${day(c.createdOn)}\n>\n${quoted}\n`;
}
