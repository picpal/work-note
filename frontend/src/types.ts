export type PiiStatus = "none" | "suspected" | "requested" | "exempted" | "rejected";
export interface NotePii { status: PiiStatus; types: string[]; }

export interface NoteNode {
  id: string;
  type: "note";
  title: string;
  tags: string[];
  updated: string; // YYYY-MM-DD
  updatedBy?: string | null; // "사번(이름)" 라벨 — server 모드만, local/미해석 시 없음
  created?: string | null; // ISO 생성일시 — 정렬 전용(표시 안 함)
  content: string;
  pii?: NotePii | null;
}
export interface FolderNode {
  id: string;
  type: "folder";
  name: string;
  open?: boolean;
  created?: string | null; // ISO 생성일시 — 정렬 전용
  children: VaultNode[];
}
export type VaultNode = NoteNode | FolderNode;
export type VaultTree = VaultNode[];

export interface Settings {
  dark: boolean;
  sidebarWidth: number;
  density: "compact" | "comfortable" | "spacious";
  showIcons: boolean;
  guides: boolean;
  fontSize: number;
}
