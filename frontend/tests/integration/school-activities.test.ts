// 학교 e-Class에서 가져온 활동(`GET …/weeks`의 `origin: "school"` 행)이 학생 강의실·학습활동 목록에서 그 화면 유형의
// 행이 되고, 허용한 학교 주소만 새 탭 링크가 되는지. 응답은 백엔드 계약(학교 활동 가져오기)에 맞춰 쓴 견본이다.
import { act, createElement, Fragment } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Course } from "../../src/data.ts";
import type * as Backend from "../../src/shared/backend.ts";
import type * as Api from "../../src/shared/api.ts";

const server = vi.hoisted(() => ({
  courses: [] as Course[],
  replies: new Map<string, unknown>(),
  get: vi.fn(),
}));
vi.mock("../../src/shared/backend.ts", async (original) => ({
  ...await original<typeof Backend>(),
  backendState: () => ({ status: "ready", methods: [], settled: true }),
  backendStatus: () => "ready",
  courseIdFor: (kj: string) => server.courses.find((course) => course.id === kj)?.courseId ?? null,
  roleIn: () => "STUDENT",
}));
vi.mock("../../src/shared/api.ts", async (original) => ({
  ...await original<typeof Api>(),
  apiGet: async (path: string) => {
    server.get(path);
    if (!server.replies.has(path)) throw new Error(`Unexpected request: ${path}`);
    return server.replies.get(path);
  },
}));

import { resolveCoursesWith } from "../../src/data.ts";
import { render } from "../../src/areas/course/course.tsx";
import { activityParts, activitySnapshot, passServerFilter } from "../../src/shared/course-activities-remote.ts";
import { schoolHref } from "../../src/shared/school-content.tsx";
import { lectureRows, weeksOf } from "../../src/shared/lectures-remote.ts";
import { reloadRoom, roomEntry } from "../../src/shared/course-room-remote.ts";
import { getState } from "../../src/store.ts";
import { find } from "../../src/manifest.ts";
import { course } from "../helpers/fixtures.ts";

const reactGlobals = globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean };
reactGlobals.IS_REACT_ACT_ENVIRONMENT = true;
let host: HTMLDivElement;
let root: Root;
const path = (part: string, id = 901) => `/api/courses/${id}/${part}`;
const ECLASS = "https://eclass.seoultech.ac.kr/ilos/mp/todo_list_connect.acl";
const url = (seq: number, gubun: string) => `${ECLASS}?SEQ=${seq}&gubun=${gubun}&KJKEY=A2026`;
/** STack's own detail of an imported video (no player): `m=lecture_weeks&n=school-<n>`. */
const VIDEO_801 = `/ilos/cls/st/activity/activity_form.acl?${new URLSearchParams({ m: "lecture_weeks", n: "school-801", kj: "c901" })}`;

/** One imported row as the contract describes it; override only what a test is about. */
function school(id: number, type: string, overrides: Record<string, unknown> = {}) {
  return {
    activityId: `school-${id}`, origin: "school", type, schoolType: type, schoolKind: null, title: `학교 ${type} ${id}`,
    openAt: "2026-11-02T00:00:00", closeAt: "2026-11-30T23:59:00", published: true, phase: "open", done: false,
    attendance: null, learningTimeSec: null, offline: false, schoolUrl: url(id, type), ...overrides,
  };
}

const IMPORTED_WEEK_1 = [
  school(801, "video", { schoolType: "lecture_weeks", attendance: "1 주 1차시", learningTimeSec: 1505, schoolUrl: url(801, "lecture_weeks") }),
  school(802, "report", { done: true, schoolKind: "report1" }),
  school(803, "material", { schoolType: "lecture_material" }),
  school(804, "test", { offline: true, schoolKind: "test2", schoolUrl: "http://eclass.seoultech.ac.kr/ilos/mp/todo_list_connect.acl?SEQ=804" }),
  school(806, "zoom", { phase: null, done: null, openAt: null, closeAt: null }),
  school(807, "clicker", { schoolUrl: "javascript:alert(1)" }),
];

function fixture({ native = true }: { native?: boolean } = {}) {
  const weeks = Array.from({ length: 20 }, (_, index) => ({
    weekNo: index + 1, title: `서버 주제 ${index + 1}`, startsOn: "2026-11-02", endsOn: "2026-11-08",
    activities: index === 0 ? [
      ...(native ? [
        { activityId: 11, type: "lecture_weeks", title: "서버 영상", phase: "open" },
        { activityId: 12, type: "report", assignmentId: 812, title: "서버 과제", phase: "open" },
        { activityId: 15, type: "zoom", title: "실시간 서버 수업", phase: "open" },
      ] : []),
      ...IMPORTED_WEEK_1,
    ] : [],
  }));
  // A week only the school page has.
  weeks.push({ weekNo: 21, title: null as unknown as string, startsOn: "2026-12-14", endsOn: "2026-12-20", activities: [
    school(805, "survey", { schoolUrl: "https://eclass.seoultech.ac.kr.evil.example/ilos/mp/todo_list_connect.acl?SEQ=805" }),
  ] });
  server.replies.set(path("weeks"), { serverTime: "2026-11-14T10:00:00", weeks });
  server.replies.set(path("lecture-progress"), native ? [{ weekNo: 1, items: [{ activityId: 11, itemId: 111, progress: { status: "met", progressPercent: 100 } }] }] : []);
  server.replies.set(path("materials"), native ? { items: [{ activityId: 13, weekNo: 2, title: "서버 자료", files: [] }] } : { items: [] });
  server.replies.set(path("assignments"), native ? [{ assignmentId: 812, activityId: 12, weekNo: 1, title: "서버 과제", phase: "open", status: "not_submitted" }] : []);
  server.replies.set(path("quizzes"), native ? [{ quizId: 814, activityId: 14, weekNo: 2, title: "서버 시험", phase: "open", status: "in_progress" }] : []);
  server.replies.set(path("surveys"), []);
  server.replies.set(`${path("lectures")}?size=100`, { items: [], nextCursor: null });
}

async function mount(screenId = "40_room_submain") {
  const screen = find(screenId);
  const params = new URLSearchParams({ kj: "c901" });
  await act(async () => root.render(createElement(Fragment, null, render(screen, { screen, state: getState(), params }))));
}

async function click(selector: string) {
  const button = host.querySelector<HTMLButtonElement>(selector);
  expect(button).not.toBeNull();
  await act(async () => button!.click());
  await mount();
}

async function load() {
  await act(async () => { await Promise.all(activityParts(901).map((entry) => entry.resource.load(entry.key, 901))); });
}

const titles = () => [...host.querySelectorAll(".activity b")].map((node) => node.textContent);

beforeEach(() => {
  server.replies.clear();
  server.get.mockClear();
  server.courses = [course({ id: "c901", courseId: 901, ko: "학교 활동 강의실", en: "", server: true })];
  resolveCoursesWith((id) => server.courses.find((entry) => entry.id === id));
  for (const part of activityParts(901)) part.resource.clear();
  const state = getState();
  state.draft = {};
  state.week = 1;
  state.courseId = "c901";
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

describe("schoolHref", () => {
  it("https://eclass.seoultech.ac.kr 주소만 링크로 남긴다", () => {
    expect(schoolHref(url(1, "report"))).toBe(url(1, "report"));
    expect(schoolHref("https://ECLASS.seoultech.ac.kr:443/ilos/main/main_form.acl")).toBe("https://eclass.seoultech.ac.kr/ilos/main/main_form.acl");
    for (const bad of [
      "http://eclass.seoultech.ac.kr/ilos/main/main_form.acl", "https://eclass.seoultech.ac.kr:8443/x", "https://eclass.seoultech.ac.kr.evil.example/x",
      "https://evil.example/?u=https://eclass.seoultech.ac.kr/", "https://user:pw@eclass.seoultech.ac.kr/x", "https://www.seoultech.ac.kr/",
      "javascript:alert(1)", "/ilos/mp/todo_list_connect.acl", "", null, undefined, 42,
    ]) expect(schoolHref(bad)).toBeNull();
  });
});

describe("activitySnapshot: 가져온 학교 활동", () => {
  it("화면 유형의 행이 되고 본인 done·phase로 진행 중·미제출·완료를 정한다", async () => {
    await load();
    const { rows, weeks } = activitySnapshot(activityParts(901), "c901");
    const imported = rows.filter((row) => row.school);
    expect(imported.map((row) => [row.key, row.type, row.kind, row.weekNo, row.done, row.ongoing, row.missing, row.href !== null])).toEqual([
      ["school-801", "video", "lecture_weeks", 1, false, true, true, true],
      ["school-802", "report", "report", 1, true, true, false, true],
      ["school-803", "material", "lecture_material", 1, false, true, false, true],
      ["school-804", "test", "test", 1, false, true, false, false],
      ["school-806", "zoom", "zoom", 1, false, false, false, true],
      ["school-807", "clicker", "clicker", 1, false, true, true, false],
      ["school-805", "survey", "survey", 21, false, true, true, false],
    ]);
    expect(imported[0]!.href).toBe(VIDEO_801);
    expect(imported[0]!.external).toBe(false);
    expect(imported.find((row) => row.key === "school-802")!.external).toBe(false);
    expect(imported.find((row) => row.key === "school-806")!.external).toBe(true);
    // Native rows keep their own mapping, and no imported row turns into an unopenable "other" row.
    expect(rows.filter((row) => !row.school).map((row) => row.key)).toEqual(["lecture-11", "report-812", "other-15", "material-13", "test-814"]);
    expect(rows.some((row) => row.type === "other" && String(row.activityId).startsWith("school-"))).toBe(false);
    expect(weeks.map((week) => week.weekNo)).toContain(21);
    // Filters of the activity list compare the display type directly.
    expect(rows.filter((row) => passServerFilter(row, "video")).map((row) => row.key)).toEqual(["lecture-11", "school-801"]);
    expect(rows.filter((row) => passServerFilter(row, "report")).map((row) => row.key)).toEqual(["report-812", "school-802"]);
    expect(rows.filter((row) => passServerFilter(row, "missing")).map((row) => row.key)).toEqual(["report-812", "school-801", "school-807", "test-814", "school-805"]);
    expect(rows.filter((row) => passServerFilter(row, "ongoing")).map((row) => row.key)).not.toContain("school-806");
  });

  it("다른 주차 목록 독자는 가져온 행을 네이티브 영상·담당자 명령 대상으로 보지 않는다", async () => {
    await load();
    const weeks = weeksOf(activityParts(901, { only: ["weeks"] }).map((entry) => entry.resource.peek(entry.key)?.data)[0] as never);
    expect(lectureRows(weeks).map((row) => row.activityId)).toEqual([11]);
    await act(async () => { await reloadRoom(901); });
    const room = roomEntry(901);
    expect(room.rows.map((row) => row.activityId)).not.toContain("school-801");
    expect(room.rows.every((row) => typeof row.activityId === "number")).toBe(true);
  });
});

describe("학생 강의실의 학교 활동 행", () => {
  it("유형·상태·기간을 보이고, 학교 영상은 STack 상세로, 그 밖은 허용한 학교 주소만 새 탭 링크로 연다", async () => {
    await mount();
    const video = host.querySelector<HTMLAnchorElement>('[data-row="school-801"]')!;
    expect(video.tagName).toBe("A");
    expect(video.getAttribute("href")).toBe(VIDEO_801);
    expect(video.hasAttribute("target")).toBe(false);
    expect(video.querySelector(".activity-ext")).toBeNull();
    expect(video.querySelector(".activity-status")?.textContent).toBe("미완료");
    expect(video.getAttribute("title")).toContain("출석 1 주 1차시");
    expect(video.getAttribute("title")).toContain("학습시간 25분 5초");
    expect(video.getAttribute("title")).toContain("학교 영상");
    expect(video.getAttribute("title")).toContain("1주");
    expect(video.children).toHaveLength(3);

    // A school assignment opens STack's read-only detail in this tab.
    const report = host.querySelector('[data-row="school-802"]')!;
    expect(report.querySelector(".check")?.getAttribute("aria-label")).toBe("완료");
    expect(report.hasAttribute("target")).toBe(false);
    expect(new URL(report.getAttribute("href")!, location.origin).searchParams.get("n")).toBe("school-802");
    expect(new URL(report.getAttribute("href")!, location.origin).searchParams.get("m")).toBe("report");
    expect(report.getAttribute("title")).toContain("학교 과제");

    for (const id of [804, 807]) {
      const row = host.querySelector(`[data-row="school-${id}"]`)!;
      expect(row.tagName).toBe("DIV");
      expect(row.getAttribute("aria-disabled")).toBe("true");
      expect(row.hasAttribute("href")).toBe(false);
      expect(row.getAttribute("title")).toContain("e-Class 주소 없음");
    }
    expect(host.querySelector('[data-row="school-804"]')?.getAttribute("title")).toContain("오프라인");
    expect(host.querySelector('[data-row="school-806"] .activity-status')?.textContent).toBe("e-Class");
    // Any other imported row with a school address stays a new-tab link to e-Class.
    const linkOut = host.querySelector<HTMLAnchorElement>('[data-row="school-806"]')!;
    expect(linkOut.getAttribute("href")).toBe(url(806, "zoom"));
    expect(linkOut.getAttribute("target")).toBe("_blank");
    expect(linkOut.getAttribute("rel")).toBe("noopener noreferrer");
    expect(linkOut.getAttribute("aria-label")).toContain("학교 e-Class에서 열기(새 탭)");
    // A display type the room has no label for falls back to the school type label; no NaN anywhere.
    expect(host.querySelector('[data-row="school-807"] .tiny')?.textContent).toBe("클리커");
    expect(host.textContent).not.toContain("NaN");
  });

  it("주차 표시에 학교 행을 세고 학교에만 있는 주차를 보인다", async () => {
    await mount();
    expect(host.querySelectorAll(".crs-wk")).toHaveLength(21);
    // Week 1: native video (done) and report, school video, report (done) and test.
    expect(host.querySelector(".crs-wk .crs-wk-mark")?.textContent).toBe("2/5");
    expect(host.querySelector('.crs-wk[aria-label="21주"]')).not.toBeNull();
    expect(host.querySelector('[data-row="school-805"]')?.getAttribute("aria-disabled")).toBe("true");
  });

  it("종류·진행 상태 필터가 학교 행을 포함한다", async () => {
    await mount();
    await click("button.chip.video");
    expect(titles()).toEqual(["서버 영상", "학교 video 801"]);
    await click("button.chip.video");
    await click('[aria-label="진행 상태"] button:last-child');
    expect(titles()).toEqual(["서버 과제", "학교 video 801", "학교 clicker 807", "서버 시험", "학교 survey 805"]);
    await click('[aria-label="진행 상태"] button:last-child');
    await click('[aria-label="진행 상태"] button:first-child');
    expect(titles()).not.toContain("학교 zoom 806");
    expect(titles()).toContain("학교 report 802");
  });

  it("이어서 학습하기는 이 서비스의 행이 먼저다", async () => {
    await mount();
    const next = host.querySelector(".crs-hero .btn-primary")!;
    expect(next.getAttribute("href")).toContain("n=srv-812");
    expect(next.hasAttribute("target")).toBe(false);
  });

  it("학교 행만 있는 과목은 다음 학교 행을 연다: 영상이면 STack의 학교 영상 상세", async () => {
    fixture({ native: false });
    await mount();
    const next = host.querySelector<HTMLAnchorElement>(".crs-hero .btn-primary")!;
    expect(next.tagName).toBe("A");
    expect(next.getAttribute("href")).toBe(VIDEO_801);
    expect(next.hasAttribute("target")).toBe(false);
    // Opening a list never reads a school detail.
    expect(server.get.mock.calls.map(([path]) => String(path)).some((path) => path.includes("/activities/"))).toBe(false);
  });
});

describe("학습활동 목록의 학교 활동 행", () => {
  it("영상·미제출 필터가 학교 행을 포함한다", async () => {
    await mount("51_activity_filter_video");
    expect(titles()).toEqual(["서버 영상", "학교 video 801"]);
    expect(host.querySelector('[data-row="school-801"]')?.getAttribute("href")).toBe(VIDEO_801);
    expect(host.querySelector('[data-row="school-801"]')?.hasAttribute("target")).toBe(false);
    await mount("56_activity_filter_not_submit");
    expect(titles()).toEqual(["서버 과제", "학교 video 801", "학교 clicker 807", "서버 시험", "학교 survey 805"]);
  });
});
