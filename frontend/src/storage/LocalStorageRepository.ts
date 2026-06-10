import type { VaultRepository } from "./VaultRepository";
import type { VaultTree } from "../types";

export class LocalStorageRepository implements VaultRepository {
  constructor(private key = "wn.vault.v1") {}
  async load(): Promise<VaultTree | null> {
    try { const v = localStorage.getItem(this.key); return v ? (JSON.parse(v) as VaultTree) : null; }
    catch { return null; }
  }
  async save(tree: VaultTree): Promise<void> {
    this.saveSync(tree);
  }
  /** beforeunload용 동기 플러시 — async가 보장되지 않는 마지막 순간 */
  saveSync(tree: VaultTree): void {
    try { localStorage.setItem(this.key, JSON.stringify(tree)); } catch { /* quota — 프로토타입 동작 유지 */ }
  }
}
