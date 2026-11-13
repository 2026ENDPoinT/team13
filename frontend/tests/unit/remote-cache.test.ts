import { beforeEach, describe, expect, it, vi } from "vitest";

// 경계 대체물: 실제 backend.ts 는 네트워크·세션 상태를 가진다. 세션 변경 알림만 직접 일으킬 수 있게 바꾼다.
const backend = vi.hoisted(() => {
  type Snapshot = { status: string; me: { userId: unknown } | null };
  const listeners = new Set<(next: Snapshot) => void>();
  return { listeners, emit: (next: Snapshot) => [...listeners].forEach((listener) => listener(next)) };
});
vi.mock("../../src/shared/backend.ts", () => ({
  subscribe: (fn: (next: { status: string; me: { userId: unknown } | null }) => void) => {
    backend.listeners.add(fn);
    return () => backend.listeners.delete(fn);
  },
}));

import { createResource, freshVisit, replaceVisit, retryLater } from "../../src/shared/remote-cache.ts";

/** A promise the test resolves by hand, to control when a fetch lands. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

let scopeId = 0;
const scope = () => `scope-${(scopeId += 1)}`; // freshVisit 은 모듈 전역 맵을 쓰므로 테스트마다 새 범위

beforeEach(() => {
  history.replaceState(null, "", "/");
});

describe("createResource.load", () => {
  it("불러온 데이터를 저장하고 시작·끝에 한 번씩 알린다", async () => {
    const resource = createResource(async (n: number) => n * 2);
    const keys: string[] = [];
    resource.subscribe((key) => keys.push(key));

    const loading = resource.load("a", 21);
    expect(resource.peek("a")?.data).toBeNull();
    expect(resource.peek("a")?.loading).toBeInstanceOf(Promise);
    await loading;

    expect(resource.peek("a")).toEqual({ data: 42, error: null, loading: null });
    expect(keys).toEqual(["a", "a"]);
    expect(resource.version()).toBe(2);
  });

  it("같은 키를 동시에 불러도 요청은 하나이고 같은 약속을 돌려준다", async () => {
    const gate = deferred<string>();
    const fetcher = vi.fn(() => gate.promise);
    const resource = createResource(fetcher);

    const first = resource.load("a");
    const second = resource.load("a");
    expect(second).toBe(first);
    gate.resolve("done");
    await first;

    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(resource.peek("a")?.data).toBe("done");
  });

  it("키가 다르면 서로 독립이다", async () => {
    const fetcher = vi.fn(async (value: string) => value);
    const resource = createResource(fetcher);
    await Promise.all([resource.load("a", "A"), resource.load("b", "B")]);
    expect(resource.peek("a")?.data).toBe("A");
    expect(resource.peek("b")?.data).toBe("B");
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it("끝난 뒤 다시 불러오면 새로 요청한다", async () => {
    const fetcher = vi.fn(async () => "x");
    const resource = createResource(fetcher);
    await resource.load("a");
    await resource.load("a");
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it("실패하면 error 에 담고 던지지 않는다", async () => {
    const failure = new Error("boom");
    const resource = createResource(async () => {
      throw failure;
    });
    await expect(resource.load("a")).resolves.toBeUndefined();
    expect(resource.peek("a")).toEqual({ data: null, error: failure, loading: null });
  });

  it("fetcher 가 동기적으로 던져도 같은 방식으로 처리한다", async () => {
    const failure = new Error("sync");
    const resource = createResource((): Promise<string> => {
      throw failure;
    });
    await resource.load("a");
    expect(resource.peek("a")?.error).toBe(failure);
  });

  it("다시 불러오다 실패하면 이전 데이터는 남기고 오류만 기록한다", async () => {
    let calls = 0;
    const resource = createResource(async () => {
      calls += 1;
      if (calls === 2) throw new Error("later");
      return "old";
    });
    await resource.load("a");
    await resource.load("a");
    const entry = resource.peek("a");
    expect(entry?.data).toBe("old");
    expect(entry?.error).toBeInstanceOf(Error);
  });

  it("다시 불러오는 동안에도 이전 데이터가 보인다(깜빡임 방지)", async () => {
    const gate = deferred<string>();
    let calls = 0;
    const resource = createResource(() => (calls++ === 0 ? Promise.resolve("old") : gate.promise));
    await resource.load("a");
    const reloading = resource.load("a");
    expect(resource.peek("a")?.data).toBe("old");
    expect(resource.peek("a")?.loading).not.toBeNull();
    gate.resolve("new");
    await reloading;
    expect(resource.peek("a")?.data).toBe("new");
  });
});

describe("createResource.clear / 세션 변경", () => {
  it("비우는 중에 도착한 이전 세션의 응답은 버린다", async () => {
    const gate = deferred<string>();
    const resource = createResource(() => gate.promise);
    const loading = resource.load("a");
    resource.clear();
    gate.resolve("이전 사용자 데이터");
    await loading;
    expect(resource.peek("a")).toBeNull();
  });

  it("비운 뒤 같은 키를 다시 불러오면 새 응답을 쓴다", async () => {
    const stale = deferred<string>();
    let calls = 0;
    const resource = createResource(() => (calls++ === 0 ? stale.promise : Promise.resolve("fresh")));
    const old = resource.load("a");
    resource.clear();
    await resource.load("a");
    stale.resolve("stale");
    await old;
    expect(resource.peek("a")?.data).toBe("fresh");
  });

  it("clear 는 구독자에게 빈 키로 알린다", () => {
    const resource = createResource(async () => 1);
    const keys: string[] = [];
    resource.subscribe((key) => keys.push(key));
    resource.clear();
    expect(keys).toEqual([""]);
  });

  it("로그인 세션(상태:사용자)이 바뀔 때만 캐시를 비운다", async () => {
    const resource = createResource(async () => "data");
    backend.emit({ status: "ready", me: { userId: 1 } }); // 첫 알림은 기준 세션을 정한다
    await resource.load("a");

    backend.emit({ status: "ready", me: { userId: 1 } }); // 같은 세션: 유지
    expect(resource.peek("a")?.data).toBe("data");

    backend.emit({ status: "ready", me: { userId: 2 } }); // 다른 사용자: 비움
    expect(resource.peek("a")).toBeNull();

    await resource.load("a");
    backend.emit({ status: "anon", me: null }); // 로그아웃: 비움
    expect(resource.peek("a")).toBeNull();
  });
});

describe("createResource.patch / drop / subscribe", () => {
  it("patch 는 일치하는 키의 데이터를 제자리에서 바꾸고 알린다", async () => {
    const resource = createResource(async (n: number) => ({ n }));
    await resource.load("list:1", 1);
    await resource.load("list:2", 2);
    await resource.load("other", 3);
    const keys: string[] = [];
    resource.subscribe((key) => keys.push(key));

    resource.patch((key) => key.startsWith("list:"), (data) => {
      data.n += 100;
    });

    expect(resource.peek("list:1")?.data).toEqual({ n: 101 });
    expect(resource.peek("list:2")?.data).toEqual({ n: 102 });
    expect(resource.peek("other")?.data).toEqual({ n: 3 });
    expect(keys).toEqual(["list:1", "list:2"]);
  });

  it("patch 는 아직 데이터가 없는(불러오는 중·실패) 항목은 건너뛴다", async () => {
    const gate = deferred<{ n: number }>();
    const resource = createResource(() => gate.promise);
    void resource.load("pending");
    const change = vi.fn();
    resource.patch(() => true, change);
    expect(change).not.toHaveBeenCalled();
    gate.resolve({ n: 1 });
  });

  it("drop 은 일치하는 키만 잊고, 다음 방문 때 다시 불러온다(쓰기 뒤 목록 갱신)", async () => {
    const fetcher = vi.fn(async (value: string) => value);
    const resource = createResource(fetcher);
    await resource.load("keep", "k");
    await resource.load("gone", "g");

    resource.drop((key) => key === "gone");

    expect(resource.peek("gone")).toBeNull();
    expect(resource.peek("keep")?.data).toBe("k");
    await resource.visit(scope(), "gone", "g2");
    expect(fetcher).toHaveBeenCalledTimes(3);
    expect(resource.peek("gone")?.data).toBe("g2");
  });

  it("구독 해제 뒤에는 알리지 않는다", async () => {
    const resource = createResource(async () => 1);
    const listener = vi.fn();
    const unsubscribe = resource.subscribe(listener);
    unsubscribe();
    await resource.load("a");
    expect(listener).not.toHaveBeenCalled();
  });

  it("알리는 도중 구독을 해제해도 다른 구독자는 모두 호출된다", async () => {
    const resource = createResource(async () => 1);
    const seen: string[] = [];
    const stop = resource.subscribe(() => {
      seen.push("first");
      stop();
    });
    resource.subscribe(() => seen.push("second"));
    await resource.load("a");
    expect(seen.slice(0, 2)).toEqual(["first", "second"]);
  });
});

describe("freshVisit / visit — 언제 다시 불러오는가", () => {
  it("처음 방문은 'entry'", () => {
    expect(freshVisit(scope(), "q")).toBe("entry");
  });

  it("같은 기록 항목에서 같은 키로 다시 그리면 false", () => {
    const s = scope();
    freshVisit(s, "q");
    expect(freshVisit(s, "q")).toBe(false);
  });

  it("같은 기록 항목에서 키(검색어·쪽)만 바뀌면 'key'", () => {
    const s = scope();
    freshVisit(s, "a");
    expect(freshVisit(s, "b")).toBe("key");
  });

  it("새 기록 항목(이동·뒤로가기)이면 키가 같아도 'entry'", () => {
    const s = scope();
    freshVisit(s, "a");
    history.pushState({}, "", "/next");
    expect(freshVisit(s, "a")).toBe("entry");
  });

  it("범위가 다르면 서로 영향을 주지 않는다", () => {
    const first = scope();
    const second = scope();
    freshVisit(first, "a");
    expect(freshVisit(second, "a")).toBe("entry");
  });

  it("replaceVisit 으로 제자리에서 바꾼 주소는 새 방문으로 세지 않는다", () => {
    const s = scope();
    history.pushState({}, "", "/list");
    freshVisit(s, "a");
    replaceVisit("/list?pop=notice", { open: true });
    expect(freshVisit(s, "a")).toBe(false);
  });

  it("replaceVisit 없이 history.replaceState 만 쓰면 새 방문으로 센다(대조군)", () => {
    const s = scope();
    history.pushState({}, "", "/list");
    freshVisit(s, "a");
    history.replaceState({}, "", "/list?pop=notice");
    expect(freshVisit(s, "a")).toBe("entry");
  });

  it("replaceVisit 을 연달아 써도 원래 기록 항목과의 동일성이 이어진다", () => {
    const s = scope();
    history.pushState({}, "", "/list");
    freshVisit(s, "a");
    replaceVisit("/list?pop=1");
    replaceVisit("/list?pop=2");
    expect(freshVisit(s, "a")).toBe(false);
  });

  it("visit: 처음엔 불러오고, 같은 항목에서 다시 그리면 캐시를 쓴다", async () => {
    const fetcher = vi.fn(async () => "x");
    const resource = createResource(fetcher);
    const s = scope();
    await resource.visit(s, "k");
    await resource.visit(s, "k");
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it("visit: 새 기록 항목이면 캐시가 있어도 다시 불러온다", async () => {
    const fetcher = vi.fn(async () => "x");
    const resource = createResource(fetcher);
    const s = scope();
    await resource.visit(s, "k");
    history.pushState({}, "", "/other");
    await resource.visit(s, "k");
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it("visit: 키가 바뀌었는데 그 키의 캐시가 없으면 불러온다", async () => {
    const fetcher = vi.fn(async (key: string) => key);
    const resource = createResource(fetcher);
    const s = scope();
    await resource.visit(s, "page-1", "page-1");
    await resource.visit(s, "page-2", "page-2");
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(resource.peek("page-2")?.data).toBe("page-2");
  });

  it("visit: 이미 불러오는 중이면 같은 요청을 기다린다", async () => {
    const gate = deferred<string>();
    const fetcher = vi.fn(() => gate.promise);
    const resource = createResource(fetcher);
    const s = scope();
    const first = resource.visit(s, "k");
    const second = resource.visit(s, "k");
    gate.resolve("x");
    await Promise.all([first, second]);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
});

describe("retryLater — 429 안내 문구", () => {
  it.each([
    [{ retryAfter: 30 }, "요청이 많습니다. 30초 뒤 다시 시도하세요."],
    [{ retryAfter: 1 }, "요청이 많습니다. 1초 뒤 다시 시도하세요."],
    [{ retryAfter: 0 }, "요청이 많습니다. 잠시 후 다시 시도하세요."], // 0 은 '알 수 없음'
    [{ retryAfter: null }, "요청이 많습니다. 잠시 후 다시 시도하세요."],
    [{}, "요청이 많습니다. 잠시 후 다시 시도하세요."],
    [null, "요청이 많습니다. 잠시 후 다시 시도하세요."],
    [undefined, "요청이 많습니다. 잠시 후 다시 시도하세요."],
  ])("%j", (error, expected) => {
    expect(retryLater(error)).toBe(expected);
  });
});

describe("createResource — 알림과 대기의 세부", () => {
  it("비운 뒤에 끝난 이전 요청은 구독자에게 알리지 않는다", async () => {
    const gate = deferred<string>();
    const resource = createResource(() => gate.promise);
    const keys: string[] = [];
    resource.subscribe((key) => keys.push(key));
    const loading = resource.load("a");
    resource.clear();
    gate.resolve("stale");
    await loading;
    expect(keys).toEqual(["a", ""]);
  });

  it("visit 는 이미 불러오는 중이면 그 요청이 끝날 때까지 기다린다", async () => {
    const gate = deferred<string>();
    const resource = createResource(() => gate.promise);
    const s = scope();
    void resource.visit(s, "k");
    let settled = false;
    const second = resource.visit(s, "k").then(() => {
      settled = true;
    });
    await Promise.resolve();
    await Promise.resolve();
    expect(settled).toBe(false);
    gate.resolve("x");
    await second;
    expect(settled).toBe(true);
  });

  it("visit 는 이미 캐시가 있고 불러오는 중이 아니면 기다리지 않고 바로 끝난다", async () => {
    const resource = createResource(async () => "x");
    const s = scope();
    await resource.visit(s, "k");
    await expect(resource.visit(s, "k")).resolves.toBeUndefined();
  });
});
