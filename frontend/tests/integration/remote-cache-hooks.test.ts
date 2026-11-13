import { act, createElement, type ReactNode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("../../src/shared/backend.ts", () => ({ subscribe: () => () => true }));

import { createResource, useRefreshOnFocus, useResource } from "../../src/shared/remote-cache.ts";

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLElement;
let root: Root;

beforeEach(() => {
  host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
});

const render = (node: ReactNode) => act(() => root.render(node));
const visibility = (state: DocumentVisibilityState) => vi.spyOn(document, "visibilityState", "get").mockReturnValue(state);

describe("useResource", () => {
  it("캐시 항목이 바뀌면 다시 그린다", async () => {
    const resource = createResource(async () => "서버 데이터");
    const View = () => createElement("p", null, useResource(resource, "k")?.data ?? "비어 있음");

    render(createElement(View));
    expect(host.textContent).toBe("비어 있음");

    await act(async () => {
      await resource.load("k");
    });
    expect(host.textContent).toBe("서버 데이터");
  });

  it("다른 곳에서 patch 로 바꿔도 다시 그린다", async () => {
    const resource = createResource(async () => ({ read: false }));
    await resource.load("k");
    const View = () => createElement("p", null, String(useResource(resource, "k")?.data?.read));

    render(createElement(View));
    expect(host.textContent).toBe("false");
    act(() => resource.patch(() => true, (data) => {
      data.read = true;
    }));
    expect(host.textContent).toBe("true");
  });

  it("키가 null 이면 항목이 없다", () => {
    const resource = createResource(async () => "x");
    const peek = vi.spyOn(resource, "peek");
    const View = () => createElement("p", null, String(useResource(resource, null)));
    render(createElement(View));
    expect(host.textContent).toBe("null");
    expect(peek).not.toHaveBeenCalled();
  });
});

describe("useRefreshOnFocus", () => {
  const View = ({ refresh }: { refresh?: () => void }) => {
    useRefreshOnFocus(refresh);
    return null;
  };

  it("탭이 보일 때 창 포커스·가시성 변화에 refresh 를 부른다", () => {
    const refresh = vi.fn();
    visibility("visible");
    render(createElement(View, { refresh }));
    window.dispatchEvent(new Event("focus"));
    document.dispatchEvent(new Event("visibilitychange"));
    expect(refresh).toHaveBeenCalledTimes(2);
  });

  it("탭이 숨겨져 있으면 부르지 않는다", () => {
    const refresh = vi.fn();
    visibility("hidden");
    render(createElement(View, { refresh }));
    window.dispatchEvent(new Event("focus"));
    document.dispatchEvent(new Event("visibilitychange"));
    expect(refresh).not.toHaveBeenCalled();
  });

  it("다시 그릴 때 바뀐 refresh 를 쓴다(오래된 클로저를 부르지 않는다)", () => {
    const first = vi.fn();
    const second = vi.fn();
    visibility("visible");
    render(createElement(View, { refresh: first }));
    render(createElement(View, { refresh: second }));
    window.dispatchEvent(new Event("focus"));
    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledTimes(1);
  });

  it("refresh 가 없으면 듣지 않고, 사라지면 듣기를 멈춘다", () => {
    const add = vi.spyOn(window, "addEventListener");
    render(createElement(View, {}));
    expect(add.mock.calls.filter(([type]) => type === "focus")).toHaveLength(0);

    const refresh = vi.fn();
    visibility("visible");
    render(createElement(View, { refresh }));
    act(() => root.render(null));
    window.dispatchEvent(new Event("focus"));
    expect(refresh).not.toHaveBeenCalled();
  });
});
