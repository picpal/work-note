/* emptyVaultPolicy — 서버가 빈 트리를 줬을 때 화면이 무엇을 말해야 하는가(순수).

   빈 트리는 정상 상태다. 다만 사유가 둘이고, 프런트가 응답만으로는 구분할 수 없다:
     (a) 권한 없음 — ACL deny로 읽을 수 있는 노드가 0개
     (b) 최초 기동 — 서버에 활성 노드 자체가 0개
   빈 응답(wasEmpty)만으로 (b)라고 단정해 시드를 서버에 써버린 것이 D-1의 원인이다.

   구분 근거는 백엔드 계약 하나뿐이다: VaultGuard.readableIds()는 관리자(와 local 모드)에게
   무필터(null)를 반환한다(backend/src/main/java/com/worknote/vault/VaultGuard.java) —
   즉 GET /tree의 결과가 권한으로 깎이지 않는다. 따라서
     · 관리자의 빈 트리 = "서버에 활성 노드 0개"라는 사실 (b로 확정 가능)
     · 비관리자의 빈 트리 = 판별 불가 (a일 수 있으므로 시드 생성 금지)
   또한 시드는 루트 노드를 만드는데 루트 생성은 이미 관리자 전용이다(App.canCreateAtRoot).
   그래서 seedable이어도 자동 실행하지 않고 관리자가 버튼으로 확인했을 때만 업로드한다. */

export type EmptyVaultView =
  | "none"        // 빈 상태 안내 불필요
  | "seedable"    // 서버가 비어 있음이 확정 — 관리자에게 예제 노트 생성 제안
  | "noAccess";   // 열람 가능한 노트가 없음 — 권한 요청 안내

export interface EmptyVaultInput {
  mode: "http" | "local";
  ready: boolean;
  loadError: boolean;
  treeEmpty: boolean;
  isAdmin: boolean;
}

export function emptyVaultView(i: EmptyVaultInput): EmptyVaultView {
  if (!i.ready || i.loadError) return "none";   // 로드 전·백엔드 다운(차단 화면)은 판단 보류
  if (!i.treeEmpty) return "none";
  if (i.mode !== "http") return "none";         // local 모드는 시드가 정상 동작 — 기존 화면 유지
  return i.isAdmin ? "seedable" : "noAccess";
}
