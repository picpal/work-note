export interface NoteNode {
  id: string;
  type: "note";
  title: string;
  tags: string[];
  updated: string; // YYYY-MM-DD
  content: string;
}
export interface FolderNode {
  id: string;
  type: "folder";
  name: string;
  open?: boolean;
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
