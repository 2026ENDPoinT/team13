import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// store.ts 는 import 되는 순간 localStorage 를 읽는다. 테스트마다 저장소를 준비한 뒤 모듈을 새로 가져온다.
const backend = vi.hoisted(() => ({ canStore: true, signOut: [] as (() => void)[] }));
vi.mock("../../src/shared/backend.ts", () => ({
  mayStoreUserData: () => backend.canStore,
  onSignOut: (fn: () => void) => backend.signOut.push(fn),
}));

type Store = typeof import("../../src/store.ts");
const loadStore = async (): Promise<Store> => {
  vi.resetModules();
  return import("../../src/store.ts");
};

beforeEach(() => {
  localStorage.clear();
  document.documentElement.removeAttribute("data-theme");
  backend.canStore = true;
  backend.signOut = [];
});
afterEach(() => {
  localStorage.clear();
  document.documentElement.removeAttribute("data-theme");
});

describe("저장된 상태 불러오기", () => {
  it("저장된 것이 없으면 샘플 초기 상태", async () => {
    const { getState } = await loadStore();
    const { seedState } = await import("../../src/data.ts");
    // 불러오면서 syncActivities 가 authoredActivities 를 빈 목록으로 정규화한다.
    expect(getState()).toEqual({ ...seedState(), authoredActivities: [], theme: "dark", themeMode: "system" });
  });

  it("저장된 값은 초기 상태 위에 덮어쓰되, toast 는 비우고 테마는 저장값이 아니라 현재 설정을 따른다", async () => {
    localStorage.setItem("astra-state", JSON.stringify({ week: 5, toast: "지난 알림", theme: "light", themeMode: "light" }));
    const { getState } = await loadStore();
    expect(getState().week).toBe(5);
    expect(getState().toast).toBe("");
    expect(getState().theme).toBe("dark");
    expect(getState().themeMode).toBe("system");
  });

  it("astra-theme 가 light/dark 면 그 값을 고정 모드로 쓴다", async () => {
    localStorage.setItem("astra-theme", "light");
    const { getState } = await loadStore();
    expect(getState()).toMatchObject({ theme: "light", themeMode: "light" });
  });

  it("astra-theme 가 알 수 없는 값이면 시스템 설정을 따르고 문서의 data-theme 을 읽는다", async () => {
    localStorage.setItem("astra-theme", "sepia");
    document.documentElement.setAttribute("data-theme", "light");
    const { getState } = await loadStore();
    expect(getState()).toMatchObject({ theme: "light", themeMode: "system" });
  });

  it("data-theme 이 light/dark 가 아니면 dark", async () => {
    document.documentElement.setAttribute("data-theme", "purple");
    const { getState } = await loadStore();
    expect(getState().theme).toBe("dark");
  });

  it("저장된 JSON 이 깨졌으면 던지지 않고 초기 상태로 시작한다", async () => {
    localStorage.setItem("astra-state", "{not json");
    const { getState } = await loadStore();
    expect(getState().week).toBeTypeOf("number");
    expect(getState().toast).toBe("");
  });
});

describe("save", () => {
  it("상태를 저장하되 toast 는 저장하지 않는다", async () => {
    const { getState, save } = await loadStore();
    getState().week = 7;
    getState().toast = "곧 사라질 알림";
    save();
    const saved = JSON.parse(localStorage.getItem("astra-state") as string);
    expect(saved.week).toBe(7);
    expect(saved.toast).toBe("");
  });

  it("테마가 고정(light/dark)이면 astra-theme 도 저장하고, 시스템이면 지운다", async () => {
    const { getState, save } = await loadStore();
    getState().themeMode = "light";
    save();
    expect(localStorage.getItem("astra-theme")).toBe("light");
    getState().themeMode = "system";
    save();
    expect(localStorage.getItem("astra-theme")).toBeNull();
  });

  it("다른 탭에서 로그아웃된 사용자의 입력은 저장하지 않는다", async () => {
    const { getState, save } = await loadStore();
    backend.canStore = false;
    getState().week = 9;
    save();
    expect(localStorage.getItem("astra-state")).toBeNull();
  });

  it("저장소가 가득 차거나 막혀 있어도 던지지 않고 메모리 상태를 유지한다", async () => {
    const { getState, save } = await loadStore();
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("full", "QuotaExceededError");
    });
    getState().week = 3;
    expect(() => save()).not.toThrow();
    expect(getState().week).toBe(3);
  });

  it("저장 후 다시 불러오면 같은 값이 돌아온다(왕복)", async () => {
    const first = await loadStore();
    first.getState().week = 6;
    first.getState().courseId = "ai";
    first.save();
    const second = await loadStore();
    expect(second.getState()).toMatchObject({ week: 6, courseId: "ai" });
  });
});

describe("update / subscribe / toast", () => {
  it("update 는 변경을 적용하고 저장하고 구독자에게 알린다", async () => {
    const { getState, subscribe, update } = await loadStore();
    const listener = vi.fn();
    subscribe(listener);
    update((state) => {
      state.week = 4;
    });
    expect(getState().week).toBe(4);
    expect(JSON.parse(localStorage.getItem("astra-state") as string).week).toBe(4);
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it("구독 해제 뒤에는 알리지 않는다", async () => {
    const { render, subscribe } = await loadStore();
    const listener = vi.fn();
    const unsubscribe = subscribe(listener);
    render();
    unsubscribe();
    render();
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it("toast 는 메시지를 넣고 다시 그린다(저장하지는 않는다)", async () => {
    const { getState, subscribe, toast } = await loadStore();
    const listener = vi.fn();
    subscribe(listener);
    toast("저장했습니다");
    expect(getState().toast).toBe("저장했습니다");
    expect(listener).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem("astra-state")).toBeNull();
  });
});

describe("resetState", () => {
  it("초기 상태로 되돌리되 현재 문서 테마는 유지하고 저장한다", async () => {
    const { getState, resetState } = await loadStore();
    getState().week = 12;
    document.documentElement.setAttribute("data-theme", "light");
    resetState();
    expect(getState().week).not.toBe(12);
    expect(getState().theme).toBe("light");
    expect(JSON.parse(localStorage.getItem("astra-state") as string).theme).toBe("light");
  });
});

describe("로그아웃 시 사용자 입력 정리", () => {
  it("검색어·입력 초안·폼 오류는 비우고 나머지 상태는 유지한다", async () => {
    const { getState } = await loadStore();
    getState().queries = { board: "비밀 검색어" };
    getState().draft = { title: "쓰던 글" };
    getState().formError = { title: "오류" };
    getState().week = 8;

    expect(backend.signOut).toHaveLength(1);
    backend.signOut[0]();

    expect(getState().queries).toEqual({});
    expect(getState().draft).toEqual({});
    expect(getState().formError).toEqual({});
    expect(getState().week).toBe(8);
  });
});
