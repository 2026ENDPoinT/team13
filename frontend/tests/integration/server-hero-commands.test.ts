import { act, createElement, type ReactNode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from "vitest";
import type { Course } from "../../src/data.ts";
import type { BackendSnapshot, MyCourse } from "../../src/shared/backend.ts";
import type { HomeData, TodoItem, TodoList } from "../../src/shared/home-remote.ts";
import { serverActivityHref } from "../../src/shared/activity-links.ts";

const control = vi.hoisted(() => ({
  status: "ready" as "ready" | "off" | "anon", userId: 1,
  courses: [] as { course: Course; remote: MyCourse }[],
  home: null as HomeData | null, todo: null as TodoList | null, error: null as unknown,
  listeners: [] as ((next: BackendSnapshot) => void)[],
  repaint: () => {}, navigate: vi.fn(), ask: vi.fn(), execute: vi.fn(), loadMore: vi.fn(),
  run: null as { status: string } | null,
}));
vi.mock("../../src/shared/backend.ts", () => ({
  backendStatus: () => control.status,
  backendState: () => ({ status: control.status, me: { userId: control.userId } }),
  serverCourses: () => control.status === "ready" ? control.courses.map(({ course, remote }) => ({ kj: course.id, remote })) : [],
  subscribe: (listener: (next: BackendSnapshot) => void) => { control.listeners.push(listener); return () => true; },
}));
vi.mock("../../src/shared/course-view.ts", () => ({ serverCourseViews: () => control.courses }));
vi.mock("../../src/shared/home-remote.ts", () => {
  const homeEntry = () => ({ data: control.home, error: null, loading: null });
  const todoEntry = () => ({ data: control.todo, error: control.error, loading: null });
  return {
    homeEntry, todoEntry, useHome: homeEntry, useTodo: todoEntry,
    todoHref: (item: TodoItem) => {
      const course = control.courses.find(({ remote }) => remote.courseId === item.courseId);
      return course ? serverActivityHref(course.course.id, item.type, item.activityId, item) : null;
    },
    todoSchoolHref: (item: TodoItem) => (item.origin === "school" && item.type !== "report" ? item.schoolUrl ?? null : null),
    todoKind: (type: string) => type === "lecture_weeks" ? "video" : type,
    todoLabel: (type: string) => type,
    koDateTime: (value: string) => value,
    wallMs: (value: string) => Date.parse(value),
    homeMessage: () => "서버 할 일을 불러오지 못했어요.",
    loadMoreTodo: control.loadMore,
  };
});
vi.mock("../../src/store.ts", () => ({ render: () => control.repaint() }));
vi.mock("../../src/router.tsx", () => ({
  navigate: control.navigate,
  Link: ({ children, href, ...props }: { children: ReactNode; href: string }) => createElement("a", { ...props, href, onClick: (event: { preventDefault(): void }) => { event.preventDefault(); control.navigate(href); } }, children),
}));
vi.mock("../../src/shared/routes.ts", () => ({
  currentRole: () => "student",
  routeFor: (_role: string, kind: string, extra?: { kj: string }) => kind === "classroom" ? `/classroom?kj=${extra?.kj}` : "/home",
}));
vi.mock("../../src/shared/format.ts", () => ({ activityById: () => undefined, typeLabel: (type: string) => type, typeIcon: () => "file", TODAY: "2026-11-12" }));
vi.mock("../../src/shared/assistant/resolve.ts", () => ({
  nextActions: () => [{ id: "sample", kind: "deadline", title: "샘플 과제", meta: "샘플", cta: "열기", href: "/sample" }],
  describe: () => ({ title: "샘플 계획", meta: "샘플" }),
}));
vi.mock("../../src/shared/assistant/run.ts", () => ({ ask: control.ask, execute: control.execute }));
vi.mock("../../src/shared/assistant/plan.ts", () => ({
  runState: () => control.run, planGroups: () => [], startRun: vi.fn(), stopRun: vi.fn(),
}));
vi.mock("../../src/shared/assistant-tray.tsx", () => ({ foldTray: vi.fn(), focusTray: vi.fn(), openTray: vi.fn(), refreshTray: vi.fn() }));

import { commandBar, dockStatus, planPreview, SAY_MS } from "../../src/areas/home/command-bar.tsx";

const reactGlobal = globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean };
reactGlobal.IS_REACT_ACT_ENVIRONMENT = true;
let root: Root;
let host: HTMLElement;
let activate: Mock;

function paint() {
  root.render(createElement("div", null, commandBar(activate), planPreview()));
}
function input() {
  return host.querySelector("input")!;
}
function type(text: string) {
  act(() => {
    input().focus();
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!.call(input(), text);
    input().dispatchEvent(new Event("input", { bubbles: true }));
  });
}
function key(value: string) {
  act(() => { input().dispatchEvent(new KeyboardEvent("keydown", { key: value, bubbles: true })); });
}
function clickText(text: string) {
  const button = [...host.querySelectorAll("button")].find((node) => node.textContent === text)!;
  act(() => button.click());
}

beforeEach(() => {
  vi.clearAllMocks();
  control.status = "ready";
  control.userId = 1;
  control.run = null;
  control.error = null;
  control.courses = [{
    course: { id: "c731", ko: "서버 보안설계", en: "", code: "SEC731", enrolled: true, closed: false, kind: "regular" } as Course,
    remote: { courseId: 731, name: "서버 보안설계", code: "SEC731", role: "STUDENT" } as MyCourse,
  }];
  control.home = { courses: [{ courseId: 731, name: "서버 보안설계", role: "STUDENT" }], serverTime: "2026-11-12T09:00:00" };
  control.todo = {
    serverTime: "2026-11-12T09:00:00", total: 2, nextCursor: null,
    items: [
      { activityId: 9201, courseId: 731, type: "report", title: "위협 모델 제출", assignmentId: 8401, closeAt: "2026-11-12T23:59:00" },
      { activityId: 9202, courseId: 731, type: "lecture_weeks", title: "서버 암호 강의" },
    ],
  };
  // Exercise the same subscription contract as logout/login, clearing prior command state.
  control.listeners.forEach((listener) => listener({ status: "anon", me: null } as BackendSnapshot));
  control.listeners.forEach((listener) => listener({ status: "ready", me: { userId: 1 } } as BackendSnapshot));
  host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
  activate = vi.fn();
  control.repaint = paint;
  act(paint);
});
afterEach(() => {
  control.repaint = () => {};
  act(() => root.unmount());
  host.remove();
});

describe("로그인 서버 히어로 명령", () => {
  it("서버 다음 행동과 추천만 노출하고 샘플 계획을 숨긴다", () => {
    control.run = { status: "ready" };
    act(paint);
    expect(host.textContent).toContain("위협 모델 제출");
    expect(host.textContent).not.toContain("샘플");
    expect(host.textContent).not.toContain("오늘 할 일 전부 해줘");
    expect(host.querySelector(".hero-plan")?.hasAttribute("hidden")).toBe(true);
    const link = host.querySelector<HTMLAnchorElement>(".hero-action")!;
    expect(link.getAttribute("href")).toBe("/ilos/cls/st/activity/activity_form.acl?m=report&n=srv-8401&kj=c731");
    act(() => link.click());
    expect(control.navigate).toHaveBeenCalledWith(link.getAttribute("href"));
    expect(host.querySelector(".hero-bar")).toBeNull();
  });

  it("실제 과목 검색·Tab 자동완성·Enter 이동과 확대 콜백을 유지한다", () => {
    type("서버 보안");
    expect(activate).toHaveBeenCalled();
    expect(host.querySelector('[role="option"]')?.textContent).toContain("서버 보안설계 강의실로 이동");
    key("Tab");
    expect(input().value).toBe("서버 보안설계 강의실로 이동");
    key("Enter");
    expect(control.navigate).toHaveBeenCalledWith("/classroom?kj=c731");
    expect(control.ask).not.toHaveBeenCalled();
    expect(control.execute).not.toHaveBeenCalled();
    expect(dockStatus().say).toBe("");
  });

  it("실제 활동 추천과 직접 입력이 다음 행동과 동일한 서버 상세로 이동한다", () => {
    clickText("서버 보안설계 위협 모델 제출 열어줘");
    expect(control.navigate).toHaveBeenLastCalledWith("/ilos/cls/st/activity/activity_form.acl?m=report&n=srv-8401&kj=c731");
    type("서버 보안설계 서버 암호 강의 열어줘");
    key("Enter");
    expect(control.navigate).toHaveBeenLastCalledWith("/ilos/cls/st/activity/activity_form.acl?m=lecture_weeks&n=srv-9202&kj=c731");
    type("마감 임박한 과제 열어줘");
    key("Enter");
    expect(control.navigate).toHaveBeenLastCalledWith("/ilos/cls/st/activity/activity_form.acl?m=report&n=srv-8401&kj=c731");
    expect(control.ask).not.toHaveBeenCalled();
    expect(dockStatus().say).toBe("");
  });

  it("할 일 추천은 자동 실행 대신 실제 목록·더 보기·활동 이동을 제공한다", () => {
    control.todo!.nextCursor = "page-2";
    clickText("할 일 목록 보여줘");
    const preview = host.querySelector(".hero-plan")!;
    expect(preview.hasAttribute("hidden")).toBe(false);
    expect(preview.textContent).toContain("서버 할 일 목록");
    expect(preview.textContent).not.toContain("Accept");
    expect(preview.querySelectorAll(".hero-action")).toHaveLength(2);
    clickText("할 일 더 보기");
    expect(control.loadMore).toHaveBeenCalledOnce();
    act(() => preview.querySelector<HTMLAnchorElement>(".hero-action")!.click());
    expect(control.navigate).toHaveBeenCalledWith("/ilos/cls/st/activity/activity_form.acl?m=report&n=srv-8401&kj=c731");
  });

  it("안내 문장은 잠시 보인 뒤 사라지고 목록은 그대로 둔다", () => {
    vi.useFakeTimers();
    try {
      clickText("할 일 목록 보여줘");
      expect(host.querySelector("#assistant-say")?.textContent).toContain("서버 할 일 목록에서 활동을 선택해 주세요");
      act(() => { vi.advanceTimersByTime(SAY_MS - 1); });
      expect(host.querySelector("#assistant-say")?.textContent).not.toBe("");
      act(() => { vi.advanceTimersByTime(1); });
      expect(host.querySelector("#assistant-say")?.textContent).toBe("");
      expect(host.querySelector(".hero-plan")?.hasAttribute("hidden")).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });

  it("미수신·오류·열 수 없는 활동은 샘플이나 가짜 행동으로 대체하지 않는다", () => {
    control.todo = null;
    act(paint);
    expect(host.querySelector(".hero-actions")).toBeNull();
    expect(host.textContent).toContain("불러오는 중");
    control.error = new Error("failed");
    act(paint);
    expect(host.textContent).toContain("불러오지 못했어요");
    expect(host.textContent).not.toContain("샘플");
    type("오늘 할 일 전부 해줘");
    key("Enter");
    expect(control.ask).not.toHaveBeenCalled();
    expect(control.navigate).not.toHaveBeenCalled();
    expect(host.textContent).toContain("자동 실행이나 제출은 지원하지 않아요");
  });

  it("사용자를 바꾸면 이전 입력·안내·할 일 목록을 지운다", () => {
    clickText("할 일 목록 보여줘");
    type("이전 사용자 개인 입력");
    act(() => {
      control.userId = 2;
      control.courses = [];
      control.todo = null;
      control.home = null;
      control.listeners.forEach((listener) => listener({ status: "ready", me: { userId: 2 } } as BackendSnapshot));
      paint();
    });
    expect(input().value).toBe("");
    expect(host.textContent).not.toContain("보안설계");
    expect(host.textContent).not.toContain("위협 모델");
    expect(host.querySelector(".hero-plan")?.hasAttribute("hidden")).toBe(true);
  });

  it("서버 과목 키가 샘플과 같아도 샘플 이름·별칭을 검색하지 않는다", () => {
    control.courses[0].course.id = "ml";
    act(() => control.listeners.forEach((listener) => listener({ status: "ready", me: { userId: 1 } } as BackendSnapshot)));
    type("기계학습");
    expect(host.querySelector('[role="option"]')).toBeNull();
    type("서버 보안");
    expect(host.querySelector('[role="option"]')?.getAttribute("data-course")).toBe("ml");
  });

  it("같은 이름의 서버 과목도 추천·화살표 선택으로 정확한 강의실에 이동한다", () => {
    control.courses.push({
      course: { ...control.courses[0].course, id: "c732" },
      remote: { ...control.courses[0].remote, courseId: 732 },
    });
    act(() => {
      control.listeners.forEach((listener) => listener({ status: "ready", me: { userId: 1 } } as BackendSnapshot));
      paint();
    });
    clickText("서버 보안설계 (c731) 강의실로 이동");
    expect(control.navigate).toHaveBeenLastCalledWith("/classroom?kj=c731");
    type("서버 보안");
    key("ArrowDown");
    key("ArrowDown");
    key("Enter");
    expect(control.navigate).toHaveBeenLastCalledWith("/classroom?kj=c732");
  });

  it("상세가 없는 활동과 중복 명령을 실행 추천으로 노출하지 않는다", () => {
    control.todo!.items.push(
      { activityId: 9203, courseId: 731, type: "zoom", title: "열 수 없는 회의" },
      { activityId: 9204, courseId: 731, type: "report", title: "위협 모델 제출", assignmentId: 8402 },
    );
    act(paint);
    expect(host.textContent).not.toContain("열 수 없는 회의");
    expect([...host.querySelectorAll(".hero-prompt")].map((node) => node.textContent)).not.toContain("서버 보안설계 위협 모델 제출 열어줘");
    type("위협 모델 제출 열어줘");
    key("Enter");
    expect(control.navigate).not.toHaveBeenCalled();
  });

  it("학교 시험·설문 할 일은 새 탭의 e-Class 링크이고, 입력 명령은 그것을 열지 않는다", () => {
    const eclass = "https://eclass.seoultech.ac.kr/ilos/mp/todo_list_connect.acl?SEQ=77&gubun=survey&KJKEY=A2026";
    control.todo!.items.unshift({ activityId: "school-77", courseId: 731, type: "survey", title: "학교 설문", origin: "school", schoolUrl: eclass, closeAt: "2026-11-12T20:00:00" });
    act(paint);
    const rows = [...host.querySelectorAll<HTMLAnchorElement>(".hero-action")];
    expect(rows[0]!.getAttribute("href")).toBe(eclass);
    expect(rows[0]!.getAttribute("target")).toBe("_blank");
    expect(rows[0]!.getAttribute("rel")).toBe("noopener noreferrer");
    expect(rows[0]!.getAttribute("aria-label")).toContain("학교 e-Class에서 열기(새 탭)");
    expect([...host.querySelectorAll(".hero-prompt")].map((node) => node.textContent)).not.toContain("서버 보안설계 학교 설문 열어줘");
    type("다음 할 일 열어줘");
    key("Enter");
    expect(control.navigate).toHaveBeenCalledWith("/ilos/cls/st/activity/activity_form.acl?m=report&n=srv-8401&kj=c731");
  });

  it("off에서는 기존 목업 추천과 다음 행동을 유지한다", () => {
    control.status = "off";
    act(paint);
    expect(host.textContent).toContain("샘플 과제");
    expect(host.textContent).toContain("오늘 할 일 전부 해줘");
    expect(host.textContent).toContain("데베 강의 틀어줘");
    type("기계학습");
    expect(host.querySelector('[role="option"]')?.textContent).toContain("기계학습 강의실로 이동");
  });
});
