import { act, createElement, Fragment } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Entry } from "../../src/shared/remote-cache.ts";
import type { HomeData } from "../../src/shared/home-remote.ts";
import type { MyCourse } from "../../src/shared/backend.ts";
import type { CourseWeeks } from "../../src/shared/lectures-remote.ts";
import type { Timetable } from "../../src/shared/calendar-remote.ts";
import type * as Backend from "../../src/shared/backend.ts";
import type * as LecturesRemote from "../../src/shared/lectures-remote.ts";
import type * as CalendarRemote from "../../src/shared/calendar-remote.ts";
import type * as HomeRemote from "../../src/shared/home-remote.ts";
import type * as AccountRemote from "../../src/shared/account-remote.ts";
import type * as UnreadBadges from "../../src/shared/unread-badges.tsx";

const server = vi.hoisted(() => ({
  status: "ready",
  courses: [] as MyCourse[],
  weeks: null as Entry<CourseWeeks> | null,
  home: null as Entry<HomeData> | null,
  timetable: null as Entry<Timetable> | null,
}));
vi.mock("../../src/shared/backend.ts", async (original) => ({
  ...await original<typeof Backend>(),
  backendStatus: () => server.status,
  backendState: () => ({ status: server.status, me: { userId: 901, name: "서버학생" }, courses: server.courses }),
  serverCourseOf: (kj: string) => server.status === "ready" ? server.courses.find((course) => `c${course.courseId}` === kj) || null : null,
  serverCourses: () => server.courses.map((remote) => ({ kj: `c${remote.courseId}`, remote })),
}));
vi.mock("../../src/shared/lectures-remote.ts", async (original) => ({
  ...await original<typeof LecturesRemote>(),
  courseWeeks: { peek: () => server.weeks, visit: vi.fn(), subscribe: () => () => {}, version: () => 0, watch: () => () => {} },
}));
vi.mock("../../src/shared/calendar-remote.ts", async (original) => ({
  ...await original<typeof CalendarRemote>(),
  timetables: { peek: () => server.timetable, visit: vi.fn(), load: vi.fn(), subscribe: () => () => {}, version: () => 0, watch: () => () => {} },
}));
vi.mock("../../src/shared/home-remote.ts", async (original) => ({
  ...await original<typeof HomeRemote>(),
  useHome: () => server.home,
}));
vi.mock("../../src/shared/account-remote.ts", async (original) => ({
  ...await original<typeof AccountRemote>(),
  peekAccount: () => ({ data: { userId: 901, name: "서버학생", departmentName: "서버학과", grade: 3 } }),
}));
vi.mock("../../src/shared/unread-badges.tsx", async (original) => ({
  ...await original<typeof UnreadBadges>(), useBadge: () => null,
}));
vi.mock("../../src/shared/assistant-tray.tsx", () => ({ AssistantTray: () => null }));
vi.mock("../../src/areas/home/chat-dock.tsx", () => ({ ChatDock: () => null }));

import { Shell } from "../../src/shell/shell.tsx";
import { homeAside } from "../../src/areas/home/home.tsx";
import { find } from "../../src/manifest.ts";
import { getState } from "../../src/store.ts";

(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
const entry = <T,>(data: T): Entry<T> => ({ data, error: null, previous: null, loading: null });
let host: HTMLDivElement;
let root: Root;
const ctx = (kj = "c902") => ({ state: getState(), screen: find("40_room_submain"), params: new URLSearchParams({ kj }) });
const mount = async (kj = "c902") => {
  const context = ctx(kj);
  await act(async () => root.render(createElement(Shell, { ctx: context, aside: () => homeAside(context), children: "강의실" })));
};
beforeEach(() => {
  server.status = "ready";
  server.courses = [
    { courseId: 901, name: "첫 서버 과목", term: { name: "현재 서버 학기", current: true } },
    { courseId: 902, name: "선택한 서버 과목", code: "SERVER902", unreadNotices: 7, term: { name: "선택한 서버 학기" } },
  ];
  server.weeks = entry({ weeks: [{ weekNo: 1 }, { weekNo: 2 }, { weekNo: 3 }] });
  server.home = entry({ todo: { total: 27, items: Array.from({ length: 7 }, (_, i) => ({ activityId: i + 1, courseId: 902, courseName: "선택한 서버 과목", type: "report", title: `서버 할일 ${i + 1}`, closeAt: "2026-11-18T23:59:00+09:00" })) } });
  server.timetable = entry({ today: "2026-11-14", weekStart: "2026-11-09", weekEnd: "2026-11-15", slots: [{ date: "2026-11-14", courseName: "오늘 서버 수업", courseCode: "SERVER902", startTime: "09:00", endTime: "10:00", room: "서버 강의실" }] });
  getState().courseId = "c901";
  host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => { act(() => root.unmount()); host.remove(); });

describe("로그인 공통 셸", () => {
  it("주소의 과목을 머리말·메뉴·학기에 반영하고 서버 수만 같은 배지에 표시한다", async () => {
    await mount();
    expect(host.querySelector(".course-switch")?.textContent).toContain("선택한 서버 과목");
    expect([...host.querySelectorAll(".sidenav .nav-count")].map((node) => node.textContent)).toEqual(["3", "7"]);
    expect([...host.querySelectorAll<HTMLAnchorElement>(".sidenav a.nav-link")].every((link) => link.getAttribute("href")?.includes("kj=c902"))).toBe(true);
    expect(host.querySelector(".rail-term")?.textContent).toContain("선택한 서버 학기");
    expect(host.querySelector(".rail-term")?.textContent).toContain("서버학과 3학년");
    await mount("c901");
    expect(host.querySelector(".course-switch")?.textContent).toContain("첫 서버 과목");
    expect(host.querySelector(".rail-term")?.textContent).toContain("현재 서버 학기");
    expect([...host.querySelectorAll(".sidenav .nav-count")].map((node) => node.textContent)).toEqual(["3"]);
  });

  it("목업 모드의 기존 메뉴 배지는 유지한다", async () => {
    server.status = "off";
    await mount("bi");
    expect([...host.querySelectorAll(".sidenav .nav-count")].map((node) => node.textContent)).toEqual(["14", "2"]);
  });

  it("없는 서버 값은 샘플 배지나 다른 과목의 학기로 채우지 않는다", async () => {
    server.weeks = null;
    delete server.courses[1]!.unreadNotices;
    delete server.courses[1]!.term;
    await mount();
    expect(host.querySelectorAll(".sidenav .nav-count")).toHaveLength(0);
    expect(host.querySelector(".rail-term strong")).toBeNull();
  });

  it("우측을 목업과 같은 직접 자식 카드로 그리고 할일은 4개만 미리 보여 준다", async () => {
    await mount();
    const aside = host.querySelector(".aside")!;
    expect(aside.querySelectorAll(":scope > .aside-h")).toHaveLength(2);
    expect(aside.querySelectorAll(":scope > .aside-card.today-class")).toHaveLength(1);
    expect(aside.querySelectorAll(":scope > .aside-card.todo-card")).toHaveLength(4);
    expect(aside.querySelector(".count-num")?.textContent).toBe("27");
    expect(aside.querySelector(".more-link")?.getAttribute("href")).toContain("todo");
    expect(aside.textContent).not.toContain("undefined");
    expect(aside.textContent).not.toContain("로그인");
    expect(aside.querySelector(".home-server-today, .home-server-todo")).toBeNull();
  });

  it.each([
    [{}, "시간 미정"],
    [{ startTime: "09:00" }, "09:00"],
    [{ periodStart: 1, periodEnd: 3 }, "1~3교시"],
  ])("시간표의 누락 필드로 깨진 시간 문자열을 만들지 않는다: %j", async (time, label) => {
    server.timetable = entry({ today: "2026-11-14", weekStart: "2026-11-09", weekEnd: "2026-11-15", slots: [{ date: "2026-11-14", courseName: "서버 수업", startTime: "", endTime: "", ...time }] });
    await mount();
    expect(host.querySelector(".today-class .time-row")?.textContent).toBe(label);
    expect(host.querySelector(".today-class")?.textContent).not.toContain("undefined");
  });

  it("로딩·오류·빈 목록을 샘플 없이 표시한다", async () => {
    server.weeks = null;
    server.home = null;
    server.timetable = null;
    await mount();
    expect(host.querySelectorAll(".aside [role=status]")).toHaveLength(2);
    server.home = { ...entry<HomeData>({}), data: null, error: new Error("offline") };
    server.timetable = { ...entry<Timetable>({} as Timetable), data: null, error: new Error("offline") };
    await mount();
    expect(host.querySelectorAll(".aside [role=alert]")).toHaveLength(2);
    expect(host.querySelectorAll(".aside .aside-card")).toHaveLength(0);
    server.home = entry({ todo: { total: 0, items: [] } });
    server.timetable = entry({ today: "2026-11-14", weekStart: "2026-11-09", weekEnd: "2026-11-15", slots: [] });
    await act(async () => root.render(createElement(Fragment, null, homeAside(ctx()))));
    expect(host.textContent).toContain("오늘은 수업이 없습니다");
    expect(host.textContent).toContain("마감이 남은 할 일이 없습니다");
  });
});
