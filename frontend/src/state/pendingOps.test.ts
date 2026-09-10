import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { opKey, opTargetId, mergeQueue, shouldRetryStatus, isIdempotentSuccess, loadOps, saveOps, enqueueOp, dropOp, purgeOpsFor, hasUnsentCreate, clearOps } from "./pendingOps";
import type { QueuedOp } from "./pendingOps";
import type { SyncOp } from "./useVaultSync";

// node 환경 localStorage 스텁 (pendingStore.test.ts와 동일 관례)
function stubStorage() {
  const data = new Map<string, string>();
  vi.stubGlobal("localStorage", {
    getItem: (k: string) => (data.has(k) ? data.get(k)! : null),
    setItem: (k: string, v: string) => { data.set(k, v); },
    removeItem: (k: string) => { data.delete(k); },
  });
}

const create = (id: string, parentId: string | null = null): SyncOp =>
  ({ kind: "create", node: { id, parentId, type: "note", name: "제목 없는 노트", content: "" } });
const move = (id: string, parentId: string | null): SyncOp => ({ kind: "move", id, parentId });
const rename = (id: string, name: string): SyncOp => ({ kind: "rename", id, name });
const remove = (id: string): SyncOp => ({ kind: "remove", id });
const update = (id: string, content: string): SyncOp => ({ kind: "update", id, content });

const q = (...ops: SyncOp[]): QueuedOp[] => ops.reduce<QueuedOp[]>((acc, op) => mergeQueue(acc, op), []);

describe("opKey / opTargetId", () => {
  it("대상 + 연산 종류로 큐 키를 만든다", () => {
    expect(opKey(create("n1"))).toBe("create:n1");
    expect(opKey(move("n1", "f1"))).toBe("move:n1");
    expect(opKey(update("n1", "x"))).toBe("update:n1");
  });
  it("create는 node.id, 나머지는 id가 대상", () => {
    expect(opTargetId(create("n1"))).toBe("n1");
    expect(opTargetId(remove("n1"))).toBe("n1");
  });
});

describe("mergeQueue", () => {
  it("서로 다른 연산은 발생 순서대로 쌓인다", () => {
    expect(q(create("n1"), move("n1", "f1")).map((x) => x.key)).toEqual(["create:n1", "move:n1"]);
  });

  it("같은 대상의 같은 연산은 최신 것만 남기되 순서(위치)는 보존한다 — create가 항상 앞", () => {
    const queue = q(create("n1"), move("n1", "f1"), move("n1", "f2"));
    expect(queue.map((x) => x.key)).toEqual(["create:n1", "move:n1"]);
    expect(queue[1].op).toEqual(move("n1", "f2"));
  });

  it("본문 수정은 마지막 내용으로 합쳐진다(재전송 폭주 방지)", () => {
    const queue = q(update("n1", "a"), update("n1", "ab"), update("n1", "abc"));
    expect(queue).toHaveLength(1);
    expect(queue[0].op).toEqual(update("n1", "abc"));
  });

  it("서버에 만들지 못한 노드를 지우면 create·삭제 둘 다 큐에서 사라진다", () => {
    const queue = q(create("n1"), update("n1", "a"), remove("n1"));
    expect(queue).toEqual([]);
  });

  it("서버에 이미 있는 노드의 삭제는 남기고, 그 노드의 대기 연산은 정리한다", () => {
    const queue = q(update("n1", "a"), move("n1", "f1"), remove("n1"));
    expect(queue.map((x) => x.key)).toEqual(["remove:n1"]);
  });

  it("다른 노드의 대기 연산은 건드리지 않는다", () => {
    const queue = q(create("n1"), create("n2"), remove("n1"));
    expect(queue.map((x) => x.key)).toEqual(["create:n2"]);
  });
});

describe("shouldRetryStatus", () => {
  it("네트워크 실패(status 없음)는 재전송 대상 — 백엔드 재기동이 잦은 폐쇄망", () => {
    expect(shouldRetryStatus(null)).toBe(true);
  });
  it("세션 만료(401)는 재로그인 후 재전송", () => {
    expect(shouldRetryStatus(401)).toBe(true);
  });
  it("서버 오류·혼잡은 재전송", () => {
    expect(shouldRetryStatus(500)).toBe(true);
    expect(shouldRetryStatus(503)).toBe(true);
    expect(shouldRetryStatus(429)).toBe(true);
    expect(shouldRetryStatus(408)).toBe(true);
  });
  it("영구 실패(권한 없음·대상 없음·중복)는 큐에 남기지 않는다 — 무한 재시도 방지", () => {
    expect(shouldRetryStatus(403)).toBe(false);
    expect(shouldRetryStatus(404)).toBe(false);
    expect(shouldRetryStatus(409)).toBe(false);
    expect(shouldRetryStatus(422)).toBe(false);
  });
});

describe("isIdempotentSuccess", () => {
  it("create 409(이미 존재)는 성공으로 간주 — 재전송이 목적을 이미 달성했다", () => {
    expect(isIdempotentSuccess(create("n1"), 409)).toBe(true);
  });
  it("다른 연산의 409는 성공이 아니다", () => {
    expect(isIdempotentSuccess(move("n1", "f1"), 409)).toBe(false);
    expect(isIdempotentSuccess(update("n1", "a"), 409)).toBe(false);
  });
  it("409가 아닌 실패는 성공이 아니다", () => {
    expect(isIdempotentSuccess(create("n1"), 403)).toBe(false);
    expect(isIdempotentSuccess(create("n1"), null)).toBe(false);
  });
});

describe("pendingOps 저장소", () => {
  beforeEach(() => { stubStorage(); });
  afterEach(() => { vi.unstubAllGlobals(); });

  it("빈 저장소는 빈 큐", () => {
    expect(loadOps()).toEqual([]);
  });

  it("enqueue 한 op는 다음 세션(load)에서 그대로 회수된다 — 새로고침·크래시 후 재전송", () => {
    enqueueOp(create("n1"));
    enqueueOp(move("n1", "f1"));
    expect(loadOps().map((x) => x.key)).toEqual(["create:n1", "move:n1"]);
  });

  it("enqueue는 병합 규칙을 그대로 적용한다", () => {
    enqueueOp(create("n1"));
    enqueueOp(remove("n1"));
    expect(loadOps()).toEqual([]);
  });

  it("전송에 성공한 op만 큐에서 빠진다", () => {
    enqueueOp(create("n1"));
    enqueueOp(rename("n2", "새 이름"));
    dropOp("create:n1");
    expect(loadOps().map((x) => x.key)).toEqual(["rename:n2"]);
  });

  it("노드를 지우면 그 노드의 대기 연산이 전부 사라진다", () => {
    enqueueOp(update("n1", "a"));
    enqueueOp(move("n1", "f1"));
    enqueueOp(create("n2"));
    expect(purgeOpsFor("n1").map((x) => x.key)).toEqual(["create:n2"]);
    expect(loadOps().map((x) => x.key)).toEqual(["create:n2"]);
  });

  it("hasUnsentCreate — 서버에 만들어지지 못한 노드 판별", () => {
    const queue = q(create("n1"), update("n2", "a"));
    expect(hasUnsentCreate(queue, "n1")).toBe(true);
    expect(hasUnsentCreate(queue, "n2")).toBe(false);
  });

  it("깨진 JSON은 빈 큐로 취급(앱을 못 쓰게 만들지 않는다)", () => {
    localStorage.setItem("wn.pendingops.v1", "{not json");
    expect(loadOps()).toEqual([]);
  });

  it("saveOps/clearOps 왕복", () => {
    saveOps(q(create("n1")));
    expect(loadOps()).toHaveLength(1);
    clearOps();
    expect(loadOps()).toEqual([]);
  });
});
