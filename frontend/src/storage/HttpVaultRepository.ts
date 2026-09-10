/* HTTP 모드 저장소 — load만 실사용. 쓰기는 노드 단위 액션 동기화(Task 7)가 담당. */
import type { VaultRepository } from "./VaultRepository";
import type { VaultTree } from "../types";
import { VaultApi } from "./VaultApi";

export class HttpVaultRepository implements VaultRepository {
  /** 서버 응답을 그대로 돌려준다 — 빈 배열도 유효한 상태(권한 없음 또는 빈 vault)다.
      예전에는 빈 트리를 null(=저장본 없음)로 바꿔 시드가 살아남았고, 권한 없는 사용자에게
      시드 vault가 자기 노트처럼 보였다(D-1). 빈 트리의 사유 판별과 시드 생성 여부는
      state/emptyVaultPolicy가 관리자 신호로 결정한다 — 저장소는 사실만 전달한다. */
  async load(): Promise<VaultTree | null> {
    return await VaultApi.tree();
  }

  async save(): Promise<void> {
    /* no-op — 노드 단위 동기화가 담당 */
  }
}
