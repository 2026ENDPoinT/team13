import { act, createElement, Fragment } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Course } from "../../src/data.ts";
import type * as Backend from "../../src/shared/backend.ts";
import type * as Api from "../../src/shared/api.ts";

const server = vi.hoisted(() => ({
  status: "ready",
  courses: [] as Course[],
  replies: new Map<string, unknown>(),
  errors: new Map<string, Error>(),
  get: vi.fn(),
}));
vi.mock("../../src/shared/backend.ts", async (original) => ({
  ...await original<typeof Backend>(),
  backendState: () => ({ status: server.status, methods: [], settled: true }),
  backendStatus: () => server.status,
  courseIdFor: (kj: string) => server.courses.find((course) => course.id === kj)?.courseId ?? null,
  roleIn: () => "STUDENT",
}));
vi.mock("../../src/shared/api.ts", async (original) => ({
  ...await original<typeof Api>(),
  apiGet: async (path: string) => {
    server.get(path);
    if (server.errors.has(path)) throw server.errors.get(path);
    if (!server.replies.has(path)) throw new Error(`Unexpected request: ${path}`);
    return server.replies.get(path);
  },
}));

import { resolveCoursesWith } from "../../src/data.ts";
import { render } from "../../src/areas/course/course.tsx";
import { activityParts } from "../../src/shared/course-activities-remote.ts";
import { ApiError } from "../../src/shared/api.ts";
import { getState } from "../../src/store.ts";
import { find } from "../../src/manifest.ts";
import { course } from "../helpers/fixtures.ts";

// React's act environment flag is not included in the DOM globals type.
const reactGlobals = globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean };
reactGlobals.IS_REACT_ACT_ENVIRONMENT = true;
let host: HTMLDivElement;
let root: Root;
const path = (part: string, id = 901) => `/api/courses/${id}/${part}`;

function fixture(id = 901, count = 20) {
  server.replies.set(path("weeks", id), { weeks: Array.from({ length: count }, (_, index) => ({
    weekNo: index + 1, title: `서버 주제 ${index + 1}`, startsOn: "2026-11-02", endsOn: "2026-11-08",
    activities: index === 0 ? [
      { activityId: 11, type: "lecture_weeks", title: "서버 영상", phase: "open" },
      { activityId: 12, type: "report", assignmentId: 812, title: "서버 과제", phase: "open" },
      { activityId: 15, type: "zoom", title: "실시간 서버 수업", phase: "open" },
      { activityId: 16, type: "discuss", title: "서버 토론", phase: "open" },
    ] : [],
  })) });
  server.replies.set(path("lecture-progress", id), [{ weekNo: 1, items: [{ activityId: 11, itemId: 111, progress: { status: "met", progressPercent: 100 } }] }]);
  server.replies.set(path("materials", id), { items: [{ activityId: 13, weekNo: 2, title: "서버 자료", files: [{ fileId: 713, name: "자료.pdf" }] }] });
  server.replies.set(path("assignments", id), [{ assignmentId: 812, activityId: 12, weekNo: 1, title: "서버 과제", phase: "open", status: "not_submitted", openAt: "2026-11-01T00:00:00Z", closeAt: "2026-11-30T00:00:00Z" }]);
  server.replies.set(path("quizzes", id), [{ quizId: 814, activityId: 14, weekNo: 2, title: "서버 시험", phase: "open", status: "in_progress" }]);
  server.replies.set(path("surveys", id), [{ surveyId: 817, activityId: 17, weekNo: 2, title: "서버 설문", phase: "open", state: "completed" }]);
}

async function mount(screenId = "40_room_submain", kj = "c901", query = "") {
  const screen = find(screenId);
  const params = new URLSearchParams(query);
  params.set("kj", kj);
  await act(async () => root.render(createElement(Fragment, null, render(screen, { screen, state: getState(), params }))));
}

async function click(selector: string, screenId = "40_room_submain") {
  const button = host.querySelector<HTMLButtonElement>(selector);
  expect(button).not.toBeNull();
  await act(async () => button!.click());
  await mount(screenId);
}

beforeEach(() => {
  server.status = "ready";
  server.replies.clear();
  server.errors.clear();
  server.get.mockClear();
  server.courses = [course({ id: "c901", courseId: 901, ko: "실제 서버 강의실", en: "", instructor: "서버 교수", term: "2026학년도 2학기", time: "월 09:00–10:30", room: "강의동 101", server: true })];
  resolveCoursesWith((id) => server.status === "ready" ? server.courses.find((entry) => entry.id === id) : undefined);
  for (const part of activityParts(901)) part.resource.clear();
  for (const part of activityParts(902)) part.resource.clear();
  const state = getState();
  state.draft = {};
  state.week = 1;
  state.courseId = "c901";
  state.submissions = {};
  fixture();
  history.replaceState({}, "", "/");
  host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
  resolveCoursesWith(() => undefined);
});

describe("목업 양식의 서버 강의실", () => {
  it("목업과 같은 평면 hero·계획서·한 줄 필터·주차·활동 구조에 서버 값만 넣는다", async () => {
    server.status = "off";
    await mount("40_room_submain", "bi");
    const mockChildren = [...host.querySelector(".crs")!.children].slice(0, 4).map((node) => node.className);
    const mockHero = [...host.querySelectorAll(".crs-hero *")].map((node) => `${node.tagName}.${node.className}`);
    server.status = "ready";
    await mount();
    expect([...host.querySelector(".crs")!.children].slice(0, 4).map((node) => node.className)).toEqual(mockChildren);
    expect([...host.querySelectorAll(".crs-hero *")].map((node) => `${node.tagName}.${node.className}`)).toEqual(mockHero);
    expect(host.querySelector(".crs-hero")?.className).toBe("crs-hero");
    expect(host.querySelector(".crs-hero img, .crs-srv-hero, .crs-strips, .btn-ondark")).toBeNull();
    expect(host.querySelector(".course-title")?.textContent).toBe("실제 서버 강의실");
    expect(host.querySelector(".course-prof")?.textContent).toBe("서버 교수");
    expect(host.querySelector(".crs-hero-meta")?.textContent).toBe("월 09:00–10:30 · code-c901");
    expect(host.querySelector(".crs-hero .btn-primary")?.getAttribute("href")).toContain("n=srv-812");
    expect(server.get.mock.calls.every(([url]) => !/notices|todo/.test(String(url)))).toBe(true);
  });

  it("실제 20주만 표시하고 본인 완료값·유형별 상세 URL을 보존한다", async () => {
    await mount();
    expect(host.querySelectorAll(".crs-wk")).toHaveLength(20);
    expect(host.querySelector(".crs-wk-mark")?.textContent).toBe("1/2");
    expect(host.querySelector('[data-row="lecture-11"] .check')).not.toBeNull();
    expect(host.querySelector('[data-row="report-812"] .activity-status')?.textContent).toBe("미제출");
    for (const [key, mode, id] of [["lecture-11", "lecture_weeks", 11], ["material-13", "lecture_material", 13], ["report-812", "report", 812], ["test-814", "test", 814], ["survey-817", "survey", 817]]) {
      const row = host.querySelector(`[data-row="${key}"]`)!;
      const url = new URL(row.getAttribute("href")!, location.origin);
      expect(url.searchParams.get("kj")).toBe("c901");
      expect(url.searchParams.get("m")).toBe(mode);
      expect(url.searchParams.get("n")).toBe(`srv-${id}`);
      expect(row.children).toHaveLength(3);
    }
  });

  it("미지원 Zoom·토론은 비활성 실제 행으로 남기며 종류·상태·주차·접기를 유지한다", async () => {
    await mount();
    for (const id of [15, 16]) {
      const row = host.querySelector(`[data-row="other-${id}"]`)!;
      expect(row.getAttribute("aria-disabled")).toBe("true");
      expect(row.hasAttribute("href")).toBe(false);
      expect(row.textContent).toContain("열 수 없음");
    }
    await click('button.chip.zoom');
    expect(host.querySelectorAll(".activity")).toHaveLength(1);
    expect(host.querySelector(".activity b")?.textContent).toBe("실시간 서버 수업");
    await click('button.chip.zoom');
    await click('[aria-label="진행 상태"] button:last-child');
    expect([...host.querySelectorAll(".activity b")].map((node) => node.textContent)).toEqual(["서버 과제", "서버 시험"]);
    await click('[aria-label="진행 상태"] button:last-child');
    await click('.crs-wk[aria-label="16주 서버 주제 16"]');
    expect(getState().week).toBe(16);
    expect(host.querySelector(".crs-wk.on")?.getAttribute("aria-label")).toBe("16주 서버 주제 16");
    await click('.crs-week-h');
    expect(host.querySelector(".crs-week-h")?.getAttribute("aria-expanded")).toBe("false");
    expect(host.querySelector('[data-row="lecture-11"]')).toBeNull();
    await click('.crs-week-h');
    expect(host.querySelector('[data-row="lecture-11"]')).not.toBeNull();
  });

  it("긴 서버 제목과 상태 설명을 유실하지 않고 동일한 세 컬럼 안에 둔다", async () => {
    const title = "서버에서 파싱된 긴 학습활동 제목 ".repeat(12);
    const status = "서버에서 제공한 상세 처리 상태 ".repeat(8);
    server.courses[0]!.ko = "매우 긴 서버 과목명 ".repeat(10);
    server.replies.set(path("assignments"), [{ assignmentId: 812, activityId: 12, weekNo: 1, title, phase: "closed", status, closeAt: "2026-11-30T00:00:00Z" }]);
    await mount();
    const row = host.querySelector('[data-row="report-812"]')!;
    expect(row.children).toHaveLength(3);
    expect(row.querySelector("b")?.textContent).toBe(title);
    expect(row.querySelector("b")?.getAttribute("title")).toBe(title);
    expect(row.getAttribute("title")).toContain(status);
    expect(row.querySelector(".activity-status")?.getAttribute("title")).toContain(status);
    expect(host.querySelector(".course-title")?.textContent).toBe(server.courses[0]!.ko);
  });

  it("응답 대기 중에는 샘플이나 빈 결과를 먼저 표시하지 않는다", async () => {
    // This project's ES2023 library does not include Promise.withResolvers.
    let finish!: (value: unknown) => void;
    const pending = new Promise<unknown>((resolve) => { finish = resolve; });
    for (const part of ["weeks", "lecture-progress", "materials", "assignments", "quizzes", "surveys"]) server.replies.set(path(part), pending);
    await mount();
    expect(host.textContent).toContain("주차와 활동을 불러오는 중입니다");
    expect(host.querySelector(".activity, .crs-wk, .crs-empty")).toBeNull();
    await act(async () => { finish({}); });
    expect(host.querySelector(".crs-empty strong")?.textContent).toBe("등록된 활동이 없습니다");
  });

  it("빈 서버 데이터에 목업 주차·완료·활동을 만들지 않고 같은 empty와 재시도를 제공한다", async () => {
    for (const part of ["weeks", "lecture-progress", "materials", "assignments", "quizzes", "surveys"]) server.replies.set(path(part), part === "weeks" ? {} : part === "materials" ? {} : []);
    await mount();
    expect(host.querySelectorAll(".crs-wk, .activity, .check")).toHaveLength(0);
    expect(host.querySelector(".crs-empty-art")).not.toBeNull();
    expect(host.querySelector(".crs-empty strong")?.textContent).toBe("등록된 활동이 없습니다");
    expect(host.querySelector(".crs-hero .btn")).toBeNull();
    const calls = server.get.mock.calls.length;
    await click('.crs-empty button');
    expect(server.get.mock.calls.length).toBe(calls + 6);
  });

  it("한 부분 실패 시 다른 실제 행은 유지하고 실패 부분만 다시 받는다", async () => {
    server.errors.set(path("assignments"), new ApiError(500));
    await mount();
    expect(host.querySelector('[role="alert"]')?.textContent).toContain("과제");
    expect(host.querySelector('[data-row="lecture-11"]')).not.toBeNull();
    expect(host.querySelector('[data-row="report-812"]')).toBeNull();
    expect(host.querySelector(".crs-wk-mark")?.textContent).toBe("✓");
    server.errors.delete(path("assignments"));
    server.get.mockClear();
    await click('[role="alert"] button');
    expect(server.get.mock.calls).toEqual([[path("assignments")]]);
    expect(host.querySelector('[data-row="report-812"]')).not.toBeNull();
    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(host.querySelector(".crs-wk-mark")?.textContent).toBe("1/2");
  });

  it("과목을 바꾸면 새 서버 주차·제목·링크만 남고 이전 선택을 샘플로 채우지 않는다", async () => {
    await mount();
    getState().week = 20;
    getState().draft.roomType = "report";
    server.courses.push(course({ id: "c902", courseId: 902, ko: "다른 서버 수강과목", en: "", instructor: "", time: "", room: "", credit: "", term: "", server: true }));
    fixture(902, 3);
    server.replies.set(path("assignments", 902), []);
    await mount("40_room_submain", "c902");
    expect(host.querySelector(".course-title")?.textContent).toBe("다른 서버 수강과목");
    expect(host.querySelectorAll(".crs-wk")).toHaveLength(3);
    expect(host.querySelector(".crs-wk.on")?.textContent).toContain("1");
    expect(host.querySelector(".crs-kicker")?.textContent).toBe("");
    expect(host.querySelector(".course-prof")?.textContent).toBe("");
    expect(host.querySelector('[data-row="report-812"]')).toBeNull();
    expect(host.querySelector("button.chip.report.on")).not.toBeNull();
    expect(host.querySelector(".crs-empty")).not.toBeNull();
    await act(async () => host.querySelector<HTMLButtonElement>("button.chip.report")!.click());
    await mount("40_room_submain", "c902");
    expect(host.querySelector(".crs-empty")).toBeNull();
    expect([...host.querySelectorAll<HTMLAnchorElement>(".activity[href]")].every((row) => row.href.includes("kj=c902"))).toBe(true);
  });

  it("활동 목록도 같은 3열 행·empty를 쓰고 주소의 필터를 실제 상태로 적용한다", async () => {
    await mount("56_activity_filter_not_submit");
    expect([...host.querySelectorAll(".activity b")].map((node) => node.textContent)).toEqual(["서버 과제", "서버 시험"]);
    expect([...host.querySelectorAll(".activity")].every((row) => row.children.length === 3)).toBe(true);
    server.replies.set(path("assignments"), []);
    server.replies.set(path("quizzes"), []);
    await act(async () => {
      await Promise.all(activityParts(901).filter((entry) => ["assignments", "quizzes"].includes(entry.name)).map((entry) => entry.resource.load(entry.key, 901)));
    });
    expect(host.querySelector(".crs-empty-art")).not.toBeNull();
    expect(host.querySelector(".crs-empty strong")?.textContent).toBe("해당하는 활동이 없습니다");
  });

  it("로그인 대기와 수강 권한 실패에서는 활동을 노출하지 않는다", async () => {
    server.status = "anon";
    await mount("40_room_submain", "bi");
    expect(host.textContent).toContain("ASTRA 서버에 로그인하면");
    expect(host.querySelector(".activity")).toBeNull();
    expect(server.get).not.toHaveBeenCalled();
    server.status = "ready";
    server.errors.set(path("weeks"), new ApiError(404));
    await mount();
    expect(host.textContent).toContain("이 과목의 활동을 볼 수 없습니다");
    expect(host.querySelector(".activity")).toBeNull();
  });
});
