import type { VaultTree } from "../types";
/** 추후 SQLite(1단계)·HTTP API(2단계) 구현체로 교체되는 지점. async 고정. */
export interface VaultRepository {
  // null = 저장본 자체가 없음(local 모드 첫 실행 → 시드 사용). 빈 배열([])은 "비어 있다"는 유효한 상태이며
  // 시드로 대체하지 않는다 — http 모드의 빈 트리는 권한 없음일 수도, 빈 vault일 수도 있다(D-1).
  load(): Promise<VaultTree | null>;
  save(tree: VaultTree): Promise<void>;
}
