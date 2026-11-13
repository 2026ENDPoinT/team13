import { StrictMode, act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type { BackendSnapshot, BackendStatus } from "../../src/shared/backend.ts";

const auth = vi.hoisted(() => ({
  status: "off" as BackendStatus,
  listeners: new Set<(next: BackendSnapshot) => void>(),
  login: vi.fn<(id: string, password: string) => Promise<unknown>>(),
}));
vi.mock("../../src/shared/backend.ts", () => ({
  backendStatus: () => auth.status,
  backendState: () => ({ status: auth.status, methods: ["eclassPassword"] }),
  subscribe: (listener: (next: BackendSnapshot) => void) => { auth.listeners.add(listener); return () => auth.listeners.delete(listener); },
  offers: (method: string) => method === "eclassPassword",
  loginWithEclass: auth.login,
  connect: vi.fn(), loginAs: vi.fn(), personaFor: vi.fn(), startSchoolLogin: vi.fn(), onSignOut: vi.fn(),
}));
import { EntryLoginDialog, useEntryLogin } from "../../src/shared/entry-login.tsx";

(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
let host: HTMLDivElement;
let root: Root;
let trigger: HTMLButtonElement;
function Harness() {
  const login = useEntryLogin();
  return login.open ? createElement(EntryLoginDialog, { onClose: login.dismiss }) : null;
}
function notify() {
  const next: BackendSnapshot = { status: auth.status, settled: true, me: null, courses: [], methods: ["eclassPassword"] };
  for (const listener of auth.listeners) listener(next);
}
async function status(next: BackendStatus) {
  await act(async () => { auth.status = next; notify(); });
}
async function mount() {
  root = createRoot(host);
  await act(async () => root.render(createElement(StrictMode, null, createElement(Harness))));
}
function key(value: string, shiftKey = false) {
  act(() => document.activeElement!.dispatchEvent(new KeyboardEvent("keydown", { key: value, shiftKey, bubbles: true, cancelable: true })));
}
beforeEach(async () => {
  sessionStorage.clear();
  auth.status = "off";
  auth.login.mockReset();
  auth.login.mockResolvedValue({});
  trigger = document.createElement("button");
  document.body.append(trigger);
  trigger.focus();
  host = document.createElement("div");
  document.body.append(host);
  await mount();
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
  trigger.remove();
  sessionStorage.clear();
});

it("서버 익명 응답 뒤 한 번 열리고 Escape·재렌더·같은 탭 재접속 뒤 다시 열리지 않는다", async () => {
  expect(host.querySelector('[role="dialog"]')).toBeNull();
  await status("anon");
  expect(document.activeElement).toBe(host.querySelector('[name="studentId"]'));
  expect(document.body.style.overflow).toBe("hidden");
  key("Escape");
  expect(host.querySelector('[role="dialog"]')).toBeNull();
  expect(document.activeElement).toBe(trigger);
  expect(document.body.style.overflow).toBe("");
  await act(async () => root.render(createElement(StrictMode, null, createElement(Harness))));
  act(() => root.unmount());
  await mount();
  expect(host.querySelector('[role="dialog"]')).toBeNull();
});

it("복원된 로그인 세션과 이후 세션 만료에는 자동 로그인 창을 열지 않는다", async () => {
  await status("ready");
  await status("anon");
  expect(host.querySelector('[role="dialog"]')).toBeNull();
});

it("실패 시 입력을 재시도할 수 있고 성공하면 창을 닫는다", async () => {
  await status("anon");
  auth.login.mockRejectedValueOnce(new Error("학번 또는 e-class 비밀번호가 맞지 않습니다."));
  const id = host.querySelector<HTMLInputElement>('[name="studentId"]')!;
  const password = host.querySelector<HTMLInputElement>('[name="password"]')!;
  id.value = "9901";
  password.value = "wrong-password";
  await act(async () => host.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })));
  expect(host.querySelector('[role="alert"]')?.textContent).toContain("비밀번호가 맞지 않습니다");
  expect(password.value).toBe("");
  expect(document.activeElement).toBe(password);
  auth.login.mockImplementationOnce(async () => {
    auth.status = "ready";
    notify();
  });
  password.value = "correct-password";
  await act(async () => host.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })));
  expect(auth.login).toHaveBeenLastCalledWith("9901", "correct-password");
  expect(host.querySelector('[role="dialog"]')).toBeNull();
});

it("Tab 포커스가 모달 안을 순환하고 배경 클릭으로 닫을 수 있다", async () => {
  await status("anon");
  const close = host.querySelector<HTMLButtonElement>('[aria-label="닫기"]')!;
  const submit = host.querySelector<HTMLButtonElement>('[type="submit"]')!;
  close.focus();
  key("Tab", true);
  expect(document.activeElement).toBe(submit);
  key("Tab");
  expect(document.activeElement).toBe(close);
  trigger.focus();
  expect(host.contains(document.activeElement)).toBe(true);
  act(() => host.querySelector<HTMLDivElement>(".modal-back")!.click());
  expect(host.querySelector('[role="dialog"]')).toBeNull();
});
