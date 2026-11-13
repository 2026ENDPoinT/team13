import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type * as Backend from "../../src/shared/backend.ts";

const auth = vi.hoisted(() => ({ status: "anon", passwordLogin: vi.fn(() => Promise.resolve({})), methods: ["eclassPassword"] }));
vi.mock("../../src/shared/backend.ts", async (importOriginal) => ({
  ...(await importOriginal<typeof Backend>()),
  backendState: () => ({ status: auth.status, me: { userId: 1, name: "서버 사용자", role: "STUDENT" }, methods: auth.methods }),
  offers: (method: string) => auth.methods.includes(method),
  loginWithEclass: auth.passwordLogin,
  connect: vi.fn(), loginAs: vi.fn(), personaFor: vi.fn(), startSchoolLogin: vi.fn(), onSignOut: vi.fn(),
}));
import { backendPanel } from "../../src/shared/backend-prompt.tsx";

(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
let host: HTMLDivElement;
let root: Root;
beforeEach(() => {
  auth.status = "anon";
  auth.passwordLogin.mockClear();
  host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => { act(() => root.unmount()); host.remove(); });

describe("본문의 공통 인증 안내", () => {
  it.each(["off", "ready"])("%s에서는 목업에 없는 계정/로그아웃 안내 행을 추가하지 않는다", async (status) => {
    auth.status = status;
    await act(async () => root.render(backendPanel({ compact: true })));
    expect(host.querySelector(".backend-login")).toBeNull();
  });

  it("익명 세션의 e-class 로그인 폼과 비밀번호 요청·비우기는 유지한다", async () => {
    await act(async () => root.render(backendPanel()));
    const id = host.querySelector<HTMLInputElement>('input[name="studentId"]')!;
    const password = host.querySelector<HTMLInputElement>('input[name="password"]')!;
    expect(id.autocomplete).toBe("username");
    expect(password.autocomplete).toBe("current-password");
    id.value = "9901";
    password.value = "preview-password";
    await act(async () => host.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })));
    expect(auth.passwordLogin).toHaveBeenCalledWith("9901", "preview-password");
    expect(password.value).toBe("");
  });
});
