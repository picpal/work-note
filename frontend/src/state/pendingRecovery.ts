/* pendingRecovery — 복구 대상 판별(순수). localStorage 미러가 로드된 노트와 실제로 다른지 결정한다.
   flush()의 clearPending은 비동기라 풀 네비게이션(관리자↔노트) 시 실행 못 되고 미러가 잔존할 수 있다.
   그 미러가 서버에 이미 반영된 내용과 같으면(스테일) 복구/재PATCH/토스트 없이 폐기해야 한다. */
import type { PendingPatch } from "./pendingStore";

/** 비교용 최소 형태 — 노트의 현재(서버 로드) 값. NoteNode가 구조적으로 대입된다. */
export interface RecoverTarget {
  title?: string;
  content?: string;
  tags?: string[];
}

function sameTags(a: string[], b: string[]): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

/** pending 미러가 로드된 노트와 실제로 다른가 = 아직 서버 미반영.
 *  false면 스테일 미러(이미 저장됨) → 복구하지 않고 조용히 폐기. */
export function pendingDiffers(target: RecoverTarget, patch: PendingPatch): boolean {
  if (patch.content !== undefined && patch.content !== (target.content ?? "")) return true;
  if (patch.title !== undefined && patch.title !== (target.title ?? "")) return true;
  if (patch.tags !== undefined && !sameTags(patch.tags, target.tags ?? [])) return true;
  return false;
}
