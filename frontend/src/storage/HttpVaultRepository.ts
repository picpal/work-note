/* HTTP 모드 저장소 — load만 실사용. 쓰기는 노드 단위 액션 동기화(Task 7)가 담당. */
import type { VaultRepository } from "./VaultRepository";
import type { VaultTree } from "../types";
import { VaultApi } from "./VaultApi";

export class HttpVaultRepository implements VaultRepository {
  wasEmpty = false; // 최초 load가 빈 서버였는지 — 시드 부트스트랩 판단용 (useVaultSync.bootstrapIfEmpty)

  async load(): Promise<VaultTree | null> {
    const tree = await VaultApi.tree();
    this.wasEmpty = tree.length === 0;
    return tree.length ? tree : null; // 빈 서버 = 시드 부트스트랩 대상 (Task 7)
  }

  async save(): Promise<void> {
    /* no-op — 노드 단위 동기화가 담당 */
  }
}
