import { act, Fragment, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Course } from "../../src/data.ts";
import type { Entry } from "../../src/shared/remote-cache.ts";
import type { HomeData, TodoList } from "../../src/shared/home-remote.ts";
import type * as Backend from "../../src/shared/backend.ts";
import type * as HomeRemote from "../../src/shared/home-remote.ts";

const server = vi.hoisted(() => ({
  status: "ready",
  todo: null as Entry<TodoList> | null,
  home: null as Entry<HomeData> | null,
  courses: [] as Course[],
}));
vi.mock("../../src/shared/backend.ts", async (original) => ({
  ...await original<typeof Backend>(),
  backendStatus: () => server.status,
  backendState: () => ({ status: server.status, settled: true, me: { userId: 901, name: "서버학생" }, methods: [],
    courses: [{ courseId: 901, role: "STUDENT", name: "실제 서버 과목" }] }),
}));
vi.mock("../../src/shared/home-remote.ts", async (original) => ({
  ...await original<typeof HomeRemote>(),
  useHome: () => server.home,
  homeEntry: () => server.home,
  useTodo: () => server.todo,
  todoEntry: () => server.todo,
}));
vi.mock("../../src/shared/course-view.ts", () => ({
  serverCourseViews: () => server.courses.map((course) => ({ course, remote: { courseId: 901, term: { name: course.term } } })),
}));

import { collapseHero, homeHero, render } from "../../src/areas/home/home.tsx";
import { serverTodoList } from "../../src/areas/home/home-server.tsx";
import { find } from "../../src/manifest.ts";
import { getState } from "../../src/store.ts";
import { course } from "../helpers/fixtures.ts";

(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
let host: HTMLDivElement;
let root: Root;
const items = [
  { activityId: 1, type: "report", title: "지난 마감", closeAt: "2026-11-13T23:00:00Z" },
  { activityId: 2, type: "report", title: "가까운 과제", closeAt: "2026-11-14T01:00:00Z" },
  { activityId: 3, type: "lecture_weeks", title: "토요일 영상", closeAt: "2026-11-14T10:00:00+09:00" },
  { activityId: 4, type: "lecture_material", title: "다음 주 자료", closeAt: "2026-11-15T00:00:00+09:00" },
  { activityId: 5, type: "report", title: "사흘 뒤 과제", closeAt: "2026-11-17T23:59:00+09:00" },
  { activityId: 6, type: "lecture_weeks", title: "나흘 뒤 영상", closeAt: "2026-11-18T00:00:00+09:00" },
].map((item) => ({ ...item, courseId: 901, courseName: "실제 서버 과목" }));
const entry = <T,>(data: T): Entry<T> => ({ data, error: null, previous: null, loading: null });
const mount = async () => {
  const ctx = { state: getState(), screen: find("00_main_home"), params: new URLSearchParams("cal=month&month=2026-11") };
  await act(async () => root.render(createElement(Fragment, null, homeHero(ctx), render(ctx.screen, ctx))));
};
const value = (kind: string) => host.querySelector(`.summary-${kind} .summary-number`)?.textContent;

beforeEach(() => {
  server.status = "ready";
  server.todo = entry({ serverTime: "2026-11-14T00:30:00Z", items, total: items.length, nextCursor: null });
  server.home = entry({ courses: [], notices: [], communityNotices: [], noticesForMe: [] });
  server.courses = [
    course({ id: "c901", ko: "실제 서버 과목", en: "Server Course", server: true }),
    course({ id: "c902", ko: "지난 학기 서버 과목", en: "Past Course", term: "2026-1", server: true }),
  ];
  host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => { act(() => root.unmount()); host.remove(); });

describe("e-class 로그인 홈", () => {
  it("월간 주소로 열어도 요약 홈을 유지하고 서버 과목·학습실 링크를 보존한다", async () => {
    await mount();
    expect(host.querySelector('[aria-label="월간 달력"]')).toBeNull();
    expect(host.querySelector("#assistant-input")).not.toBeNull();
    expect(host.querySelector('[aria-label="전체 화면으로 보기"]')).not.toBeNull();
    expect(host.querySelector(".hero-greet")?.textContent).toContain("서버학생");
    expect(host.querySelector(".hero-greet")?.textContent).toContain("2026년 11월 14일 토요일");
    expect(value("due")).toBe("2");
    expect(value("report")).toBe("3");
    expect(value("week")).toBe("1");
    expect(host.querySelector(".hero-count")?.textContent).toBe("4개");
    expect([...host.querySelectorAll(".cover b")].map((node) => node.textContent)).toEqual(["실제 서버 과목", "지난 학기 서버 과목"]);
    expect(host.querySelector('a[aria-label="실제 서버 과목 학습실 입장"]')?.getAttribute("href")).toContain("kj=c901");
    expect(host.querySelector('a[aria-label="지난 학기 서버 과목 학습실 입장"]')?.getAttribute("href")).toContain("kj=c902");
    expect(host.textContent).not.toContain("박민준");
    expect(host.querySelector(".course-card")?.textContent).not.toContain("10 / 16 주");
  });

  it("다음 쪽이 남으면 부분 개수를 전체 개수로 표시하지 않는다", async () => {
    server.todo!.data!.nextCursor = "next-page";
    await mount();
    expect(value("report")).toBe("3+");
    expect(host.querySelector(".summary-report")?.textContent).toContain("불러온 목록 기준");
    await act(async () => root.render(serverTodoList("report")));
    expect([...host.querySelectorAll(".task-title b")].map((node) => node.textContent)).toEqual(["지난 마감", "가까운 과제", "사흘 뒤 과제"]);
    expect(host.textContent).not.toContain("토요일 영상");
    expect(host.textContent).toContain("다음 목록이 있습니다");
    expect([...host.querySelectorAll("button")].find((node) => node.textContent === "더 보기")?.disabled).toBe(false);
  });

  it("실패·시각 미제공을 0건이나 샘플 데이터로 바꾸지 않는다", async () => {
    server.todo = { ...entry<TodoList>(null as unknown as TodoList), data: null, error: new Error("offline") };
    await mount();
    expect(value("due")).toBe("—");
    expect(value("report")).toBe("—");
    expect(host.querySelector('[role="alert"]')?.textContent).toContain("정보를 받지 못했습니다");
    expect(host.querySelector(".hero-greet")?.textContent).not.toContain("급한 할 일은 없네요");
    server.todo = entry({ items, total: items.length, nextCursor: null });
    await mount();
    expect(value("due")).toBe("—");
    expect(value("week")).toBe("—");
    expect(value("report")).toBe("3");
  });

  it("오프라인 목업은 기존 이름·요약 카드·과목을 그대로 표시한다", async () => {
    server.status = "off";
    await mount();
    expect(host.querySelector(".hero-greet")?.textContent).toContain("박민준");
    expect(host.querySelectorAll(".summary-card")).toHaveLength(3);
    expect(host.querySelector(".cards")?.textContent).toContain("고급웹프로그래밍");
    expect(host.querySelector(".cards")?.textContent).not.toContain("실제 서버 과목");
  });

  it("로그인 홈 전체화면에서 로고를 누르면 홈 이동 대신 일반 모드로 접는다", async () => {
    await mount();
    await act(async () => (host.querySelector('[aria-label="전체 화면으로 보기"]') as HTMLButtonElement).click());
    await mount();
    expect(host.querySelector(".home-hero")?.classList.contains("is-full")).toBe(true);
    const event = { preventDefault: vi.fn() };
    await act(async () => collapseHero(event, true));
    await mount();
    expect(event.preventDefault).toHaveBeenCalledOnce();
    expect(host.querySelector(".home-hero")?.classList.contains("is-full")).toBe(false);
    expect(localStorage.getItem("astra-hero-mode")).toBe("normal");
  });
});
