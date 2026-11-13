import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

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

import {
  bindDraft, fillFields, keepDraft, readFields, sessionDrafts, type Draft,
} from "../../src/shared/session-drafts.ts";

function formOf(html: string): HTMLFormElement {
  document.body.innerHTML = `<form>${html}</form>`;
  return document.querySelector("form") as HTMLFormElement;
}

const FORM = `
  <input data-field="title" value="초기 제목">
  <textarea data-field="body">초기 본문</textarea>
  <select data-field="kind"><option value="a">A</option><option value="b">B</option></select>
  <input data-field="notify" type="checkbox">
  <input data-field="locked" value="읽기 전용" disabled>
  <input name="plain" value="data-field 없음">
`;

beforeEach(() => {
  document.body.innerHTML = "";
});
afterEach(() => {
  document.body.innerHTML = "";
});

describe("readFields", () => {
  it("켜진 [data-field] 칸의 값만 읽고, 체크박스는 checked(boolean)를 읽는다", () => {
    const form = formOf(FORM);
    (form.querySelector('[data-field="notify"]') as HTMLInputElement).checked = true;
    expect(readFields(form)).toEqual({ title: "초기 제목", body: "초기 본문", kind: "a", notify: true });
  });

  it("꺼진 칸과 data-field 없는 칸은 뺀다", () => {
    const values = readFields(formOf(FORM));
    expect(values).not.toHaveProperty("locked");
    expect(values).not.toHaveProperty("plain");
  });

  it("칸이 없으면 빈 객체", () => {
    expect(readFields(formOf(""))).toEqual({});
  });
});

describe("fillFields", () => {
  it("값을 칸 종류에 맞게 채운다", () => {
    const form = formOf(FORM);
    fillFields(form, { title: "새 제목", body: "새 본문", kind: "b", notify: true });
    expect(readFields(form)).toEqual({ title: "새 제목", body: "새 본문", kind: "b", notify: true });
  });

  it("select 에 없는 option 값이면 현재 선택을 그대로 둔다", () => {
    const form = formOf(FORM);
    fillFields(form, { kind: "zzz" });
    expect((form.querySelector("select") as HTMLSelectElement).value).toBe("a");
  });

  it("꺼진 칸은 채우지 않는다", () => {
    const form = formOf(FORM);
    fillFields(form, { locked: "바꾸면 안 됨" });
    expect((form.querySelector("[disabled]") as HTMLInputElement).value).toBe("읽기 전용");
  });

  it("값 맵에 없는 칸은 건드리지 않는다", () => {
    const form = formOf(FORM);
    fillFields(form, { title: "만" });
    expect((form.querySelector('[data-field="body"]') as HTMLTextAreaElement).value).toBe("초기 본문");
  });

  it("빈 문자열과 false 도 '있는 값'으로 채운다(입력을 지운 초안)", () => {
    const form = formOf(FORM);
    const box = form.querySelector('[data-field="notify"]') as HTMLInputElement;
    box.checked = true;
    fillFields(form, { title: "", notify: false });
    expect((form.querySelector('[data-field="title"]') as HTMLInputElement).value).toBe("");
    expect(box.checked).toBe(false);
  });

  it("객체 기본 속성 이름(toString)은 값으로 취급하지 않는다", () => {
    const form = formOf('<input data-field="toString" value="유지">');
    fillFields(form, {});
    expect((form.querySelector("input") as HTMLInputElement).value).toBe("유지");
  });

  it("값이 null/undefined 면 아무 일도 하지 않는다", () => {
    const form = formOf(FORM);
    expect(() => fillFields(form, null)).not.toThrow();
    expect(() => fillFields(form, undefined)).not.toThrow();
    expect((form.querySelector('[data-field="title"]') as HTMLInputElement).value).toBe("초기 제목");
  });
});

describe("keepDraft — 기준 버전은 처음 고치기 시작할 때의 것을 유지한다(If-Match)", () => {
  it("없는 초안은 현재 칸 값과 넘겨준 버전으로 새로 만든다", () => {
    const drafts = new Map<string, Draft<number>>();
    const draft = keepDraft(drafts, "k", formOf(FORM), 3);
    expect(draft).toEqual({ values: { title: "초기 제목", body: "초기 본문", kind: "a", notify: false }, version: 3, note: null });
    expect(drafts.get("k")).toBe(draft);
  });

  it("이미 있는 초안은 값만 갱신하고 버전·안내는 그대로 둔다", () => {
    const drafts = new Map<string, Draft<number>>();
    const form = formOf(FORM);
    keepDraft(drafts, "k", form, 3).note = { text: "충돌", alert: true };
    (form.querySelector('[data-field="title"]') as HTMLInputElement).value = "고친 제목";

    const again = keepDraft(drafts, "k", form, 9);

    expect(again.version).toBe(3);
    expect(again.note).toEqual({ text: "충돌", alert: true });
    expect(again.values?.title).toBe("고친 제목");
  });

  it("키가 다르면 초안도 따로다", () => {
    const drafts = new Map<string, Draft<number>>();
    const form = formOf(FORM);
    keepDraft(drafts, "a", form, 1);
    keepDraft(drafts, "b", form, 2);
    expect([drafts.get("a")?.version, drafts.get("b")?.version]).toEqual([1, 2]);
  });
});

describe("bindDraft — 다시 그린 폼 복원", () => {
  it("초안이 없으면 null 을 돌려주고 폼은 그대로 둔다", () => {
    const drafts = new Map<string, Draft<number>>();
    const form = formOf(FORM);
    expect(bindDraft(drafts, "k", form, 1)).toBeNull();
    expect((form.querySelector('[data-field="title"]') as HTMLInputElement).value).toBe("초기 제목");
  });

  it("초안이 있으면 그 값으로 폼을 채우고 초안을 돌려준다", () => {
    const drafts = new Map<string, Draft<number>>();
    drafts.set("k", { values: { title: "복원된 제목" }, version: 5, note: null });
    const form = formOf(FORM);
    const draft = bindDraft(drafts, "k", form, 7);
    expect(draft?.version).toBe(5);
    expect((form.querySelector('[data-field="title"]') as HTMLInputElement).value).toBe("복원된 제목");
  });

  it("입력(input)·변경(change)이 일어날 때마다 초안에 담는다", () => {
    const drafts = new Map<string, Draft<number>>();
    const form = formOf(FORM);
    bindDraft(drafts, "k", form, 4);
    expect(drafts.has("k")).toBe(false); // 아직 고치지 않았다

    const title = form.querySelector('[data-field="title"]') as HTMLInputElement;
    title.value = "타이핑";
    title.dispatchEvent(new Event("input", { bubbles: true }));
    expect(drafts.get("k")?.values?.title).toBe("타이핑");
    expect(drafts.get("k")?.version).toBe(4);

    const kind = form.querySelector("select") as HTMLSelectElement;
    kind.value = "b";
    kind.dispatchEvent(new Event("change", { bubbles: true }));
    expect(drafts.get("k")?.values?.kind).toBe("b");
  });
});

describe("sessionDrafts — 로그인 세션이 바뀌면 비운다", () => {
  it("같은 세션 알림에서는 유지하고, 다른 사용자·상태로 바뀌면 비운다", () => {
    const drafts = sessionDrafts<number>();
    backend.emit({ status: "ready", me: { userId: 1 } });
    drafts.set("k", { values: { a: "1" }, version: 1, note: null });

    backend.emit({ status: "ready", me: { userId: 1 } });
    expect(drafts.size).toBe(1);

    backend.emit({ status: "ready", me: { userId: 2 } });
    expect(drafts.size).toBe(0);

    drafts.set("k", { values: null, version: 1, note: null });
    backend.emit({ status: "anon", me: null });
    expect(drafts.size).toBe(0);
  });

  it("각 맵은 독립적으로 비워진다", () => {
    const first = sessionDrafts<number>();
    const second = sessionDrafts<number>();
    backend.emit({ status: "ready", me: { userId: 10 } });
    first.set("k", { values: null, version: 1, note: null });
    second.set("k", { values: null, version: 1, note: null });
    first.clear();
    expect(second.size).toBe(1);
  });
});
