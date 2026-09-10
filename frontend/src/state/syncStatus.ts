/* syncStatus — "서버에 저장되지 않은 변경이 있다"를 화면 문구로 옮기는 결정(순수).

   낙관적 UI라 로컬 트리는 이미 바뀌어 있다. 전송이 실패한 사실은 1.5초짜리 토스트 한 번이
   아니라 저장 표시·배너처럼 지속되는 자리에 남아야 한다(D-2). 공유 링크는 그 상태에서
   생성하면 받는 사람이 빈 노트를 보므로 사용자에게 묻는다(D-3). */

/** flush(수동 저장/공유 전 강제 저장) 결과. unsynced = 아직 서버에 못 보낸 연산 수. */
export interface FlushResult {
  ok: boolean;
  unsynced: number;
  error?: string;
}

export interface SaveButtonState {
  label: string;
  icon: string;
  title: string;
  disabled: boolean;
  danger: boolean;
}

export function saveButtonState(dirty: boolean, unsynced: number): SaveButtonState {
  if (unsynced > 0) {
    return {
      label: "저장 실패",
      icon: "alert",
      title: `서버에 저장되지 않은 변경 ${unsynced}건 — 연결이 복구되면 다시 보냅니다. 눌러서 지금 재시도`,
      disabled: false,
      danger: true,
    };
  }
  if (dirty) return { label: "저장", icon: "save", title: "지금 저장", disabled: false, danger: false };
  return { label: "저장됨", icon: "check", title: "저장됨", disabled: true, danger: false };
}

/** 상단 상시 배너 문구. offline = 마지막 실패가 fetch 자체 실패(서버 다운·네트워크 단절)였는가. */
export function syncBanner(unsynced: number, offline: boolean): string | null {
  if (unsynced <= 0) return null;
  return offline
    ? `서버에 연결할 수 없습니다 — 저장되지 않은 변경 ${unsynced}건이 대기 중입니다. 연결이 복구되면 자동으로 다시 보냅니다.`
    : `서버 저장에 실패한 변경 ${unsynced}건이 대기 중입니다. 자동으로 다시 시도합니다.`;
}

/** 공유 링크 생성 전 flush 결과 판정 — 실패면 생성 여부를 사용자에게 묻는다. */
export function shareFlushGate(r: FlushResult): { proceed: boolean; message?: string } {
  if (r.ok) return { proceed: true };
  const reason = r.error ? ` (${r.error})` : "";
  return {
    proceed: false,
    message:
      `저장되지 않은 변경 ${r.unsynced}건이 서버에 반영되지 않았습니다${reason}. ` +
      "지금 링크를 만들면 받는 사람이 이전 내용(또는 빈 노트)을 보게 됩니다.",
  };
}
