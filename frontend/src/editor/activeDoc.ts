/* 현재 열려 있는 에디터의 본문 텍스트 접근점.
   에디터 밖 UI(첨부 삭제 확인 등)가 "이 파일이 본문에서 몇 번 쓰이는지"를 물어보려면 본문이 필요한데,
   그 UI는 CodeMirror를 몰라야 한다. cm.create가 살아 있는 동안만 소스를 등록하고 destroy 때 해제한다.
   CodeMirror에 의존하지 않는 모듈로 둔다 — share 번들이 이 파일 때문에 에디터를 끌어오면 안 된다. */

let source: (() => string) | null = null;

/** 살아 있는 에디터가 자기 본문 getter를 등록한다(cm.ts 전용). */
export function setActiveDocSource(fn: () => string): void {
  source = fn;
}

/** 등록했던 getter만 해제한다 — 새 에디터가 등록한 뒤 옛 에디터가 destroy돼도 덮어쓰지 않도록. */
export function clearActiveDocSource(fn: () => string): void {
  if (source === fn) source = null;
}

/** 현재 열린 노트 본문. 에디터가 없으면(공유 열람 화면 등) null. */
export function activeDocText(): string | null {
  if (!source) return null;
  try {
    return source();
  } catch {
    return null;
  }
}
