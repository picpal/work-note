import type { VaultTree } from "../types";
/** 추후 SQLite(1단계)·HTTP API(2단계) 구현체로 교체되는 지점. async 고정. */
export interface VaultRepository {
  load(): Promise<VaultTree | null>;   // null = 저장본 없음(시드 사용)
  save(tree: VaultTree): Promise<void>;
}
