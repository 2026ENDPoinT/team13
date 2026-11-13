// 학교 과목의 읽기 전용 화면: 학교 영상 상세(`GET …/activities/school-<n>`, 재생 없음), 강의계획서·출석·성적(같은 서버 주소의
// `origin: "school"` 응답)과 그 오류(409 `school_signin_required` 다시 로그인 뒤 다시 읽기, 502 다시 시도, 404 `syllabus_not_provided`).
// 응답은 백엔드 설계 노트 §5d·§5e의 모양으로 쓴 견본이다. 네이티브 과목과 이 읽기가 없는 백엔드의 응답은 예전처럼 그려지는지도 본다.
import { act, createElement, Fragment } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Course } from "../../src/data.ts";
import type * as Backend from "../../src/shared/backend.ts";
import type * as Api from "../../src/shared/api.ts";
import type * as EntryLogin from "../../src/shared/entry-login.tsx";

const server = vi.hoisted(() => ({
  courses: [] as Course[],
  replies: new Map<string, unknown>(),
  errors: new Map<string, unknown>(),
  get: vi.fn(),
  send: vi.fn(),
  signins: 0,
}));
vi.mock("../../src/shared/backend.ts", async (original) => ({
  ...await original<typeof Backend>(),
  backendState: () => ({ status: "ready", methods: ["eclassPassword"], settled: true }),
  backendStatus: () => "ready",
  backendSettled: () => true,
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
  apiSend: async (method: string, path: string) => {
    server.send(method, path);
    throw new Error(`Unexpected write: ${method} ${path}`);
  },
}));
// The e-Class sign-in itself is the login modal's job (entry-login tests); here it succeeds at once so the screen's `done` runs.
vi.mock("../../src/shared/entry-login.tsx", async (original) => ({
  ...await original<typeof EntryLogin>(),
  requestSchoolSignin: (done: () => void) => {
    server.signins += 1;
    server.errors.clear();
    done();
  },
}));

import { resolveCoursesWith } from "../../src/data.ts";
import { render as renderCourse } from "../../src/areas/course/course.tsx";
import { render as renderLearning } from "../../src/areas/learning/learning.tsx";
import { remoteSyllabusDialog } from "../../src/areas/course/syllabus-server.tsx";
import { resolveScreen } from "../../src/router.tsx";
import { ApiError } from "../../src/shared/api.ts";
import { schoolVideoDetails, schoolDuration, schoolPercent } from "../../src/shared/school-content.tsx";
import { syllabi } from "../../src/shared/syllabus-remote.ts";
import { attendanceViews, activeRounds } from "../../src/shared/attendance-remote.ts";
import { myGrades } from "../../src/shared/grades-remote.ts";
import { getState } from "../../src/store.ts";
import { find } from "../../src/manifest.ts";
import { course } from "../helpers/fixtures.ts";

const reactGlobals = globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean };
reactGlobals.IS_REACT_ACT_ENVIRONMENT = true;
let host: HTMLDivElement;
let root: Root;
const path = (part: string) => `/api/courses/901/${part}`;
const ACTIVITY_PATH = "/ilos/cls/st/activity/activity_form.acl";
const ECLASS = "https://eclass.seoultech.ac.kr/ilos/mp/todo_list_connect.acl?SEQ=801&gubun=lecture_weeks&KJKEY=A2026";
const VIDEO = path("activities/school-801");
const problem = (status: number, code: string) => new ApiError(status, { code });
const theCourse = () => course({ id: "c901", courseId: 901, ko: "학교 과목", en: "", server: true });

function videoReply(extra: Record<string, unknown> = {}) {
  return {
    activityId: "school-801", origin: "school", type: "lecture_weeks", weekNo: 1, title: "1주차 강의", description: null,
    openAt: "2026-09-01T00:00:00", closeAt: "2026-09-07T23:59:00", published: true, phase: "closed", serverTime: "2026-11-14T10:00:00",
    files: [], lectureItems: [], version: null, attendance: "1 주 1차시", playable: false, schoolUrl: ECLASS,
    video: {
      attendanceWeek: "1 주 1차시", periodStartAt: "2026-09-01T00:00:00", periodEndAt: "2026-09-07T23:59:00", attendanceInSchoolOnly: true,
      contents: [
        { no: 1, title: "도입", percent: 100, inPeriodPercent: 100.0, outOfPeriodPercent: 12.5, learningTimeSec: 1819, attendanceTimeSec: 1694, attendanceInSchoolOnly: true },
        { no: 2, title: "정리", percent: 40, inPeriodPercent: 40.0, outOfPeriodPercent: 0, learningTimeSec: 65, attendanceTimeSec: 0, attendanceInSchoolOnly: false },
      ],
    },
    ...extra,
  };
}

const SYLLABUS = {
  courseId: 901, origin: "school", courseName: "자료구조", courseCode: "100123", section: "01", termName: "2026-2", completionType: "전공",
  credit: 3, classTimeRoom: "화 14:00", overview: "개요", objectives: "목표", outcomeRelevance: null, textbooks: "교재", equipment: null,
  gradingPolicy: "중간 30", instructors: [
    { name: "김교수", department: "컴퓨터공학과", email: "prof@example.ac.kr", officeHours: null, phone: null, office: "미래관 101", homepage: null },
    { name: "이교수", department: "컴퓨터공학과" },
  ],
  weeks: [{ weekNo: 1, content: "소개", note: null }, { weekNo: 2, content: "배열", note: null }],
  schoolTermName: "2026년 2학기", schoolCourseName: "자료구조", schoolCodeSection: "100123/01", schoolCompletionType: "전공", schoolCredit: "3",
};

function attendanceReply(origin: "school" | null) {
  const session = (n: number, status: string, extra: Record<string, unknown> = {}) => ({
    sessionId: origin ? `school-${n}` : n, weekNo: 1, sessionNo: n, classDate: "2026-09-03", startTime: "14:00:00", endTime: "17:00:00",
    method: origin ? null : "online", ...(origin ? { schoolMethod: "Smart" } : {}), cancelled: false, makeup: false, makeupOfSessionId: null,
    record: { status, source: null, decidedAt: null, ...(origin ? { statusLabel: status === "absent" ? "결석 (0%)" : null } : {}) },
    ...extra,
  });
  return {
    role: "STUDENT", ...(origin ? { origin } : {}), serverTime: "2026-11-14T10:00:00", settings: null,
    summary: { sessions: 3, present: 1, late: 0, absent: 1, excused: 0, unmarked: 0 },
    weeks: [{ weekNo: 1, sessions: [session(1, "present"), session(2, "absent"), session(3, "other")] }],
  };
}

const GRADES = {
  courseId: 901, origin: "school", serverTime: "2026-11-14T10:00:00", activitiesVisible: true,
  items: [
    { itemId: "school-31", origin: "school", name: "과제 1", type: "assignment", schoolType: "report", activityId: "school-900", closeAt: "2026-10-01T23:59:00", submitted: true, offline: false, maxScore: 10, released: true, score: 8.5 },
    { itemId: "school-32", origin: "school", name: "중간고사", type: "quiz", schoolType: "test", closeAt: null, submitted: false, offline: true, maxScore: 30, released: false },
  ],
  total: { score: 8.5, maxScore: 10, itemCount: 1 },
};

async function show(node: () => unknown) {
  await act(async () => root.render(createElement(Fragment, null, node() as never)));
  await act(async () => {});
}

async function openVideo(n = "school-801") {
  const params = new URLSearchParams({ m: "lecture_weeks", n, kj: "c901" });
  const screen = resolveScreen(ACTIVITY_PATH, params)!;
  await show(() => renderLearning(screen, { screen, state: getState(), params }));
}

async function openSyllabus() {
  await show(() => remoteSyllabusDialog(theCourse()));
}

async function openAttendance() {
  const screen = find("41_room_attendance");
  const params = new URLSearchParams({ kj: "c901" });
  await show(() => renderLearning(screen, { screen, state: getState(), params }));
}

async function openGrades() {
  const screen = find("43_room_eval_result");
  const params = new URLSearchParams({ kj: "c901" });
  await show(() => renderCourse(screen, { screen, state: getState(), params }));
}

const calls = (url: string) => server.get.mock.calls.filter(([called]) => called === url).length;
const button = (text: string) => [...host.querySelectorAll<HTMLButtonElement>("button")].find((node) => node.textContent?.includes(text));

beforeEach(() => {
  server.replies.clear();
  server.errors.clear();
  server.get.mockClear();
  server.send.mockClear();
  server.signins = 0;
  server.courses = [theCourse()];
  resolveCoursesWith((id) => server.courses.find((entry) => entry.id === id));
  for (const resource of [schoolVideoDetails, syllabi, attendanceViews, activeRounds, myGrades]) resource.clear();
  const state = getState();
  state.draft = {};
  state.courseId = "c901";
  server.replies.set(VIDEO, videoReply());
  server.replies.set(path("syllabus"), SYLLABUS);
  server.replies.set(path("attendance"), attendanceReply("school"));
  server.replies.set(path("smart-rounds/active"), { mine: [], pollAfterSec: 30 });
  server.replies.set(path("grades/me"), GRADES);
  history.replaceState({}, "", "/");
  host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
  resolveCoursesWith(() => undefined);
  vi.restoreAllMocks();
});

describe("학교 영상 상세", () => {
  it("주소 m=lecture_weeks&n=school-<n>은 학교 영상 상세이고, 네이티브 영상 주소는 그대로다", () => {
    expect(resolveScreen(ACTIVITY_PATH, new URLSearchParams({ m: "lecture_weeks", n: "school-801", kj: "c901" }))).toMatchObject({ serverSchoolLecture: "school-801", kind: "activity" });
    expect(resolveScreen(ACTIVITY_PATH, new URLSearchParams({ m: "lecture_weeks", n: "srv-11", kj: "c901" }))).toMatchObject({ serverLecture: 11 });
    expect(resolveScreen(ACTIVITY_PATH, new URLSearchParams({ m: "lecture_weeks", n: "school-0", kj: "c901" }))?.serverSchoolLecture ?? null).toBeNull();
    expect(schoolDuration(1819)).toBe("30분 19초");
    expect(schoolDuration(3700)).toBe("1시간 1분");
    expect(schoolPercent(12.5)).toBe("12.5%");
    expect(schoolPercent(null)).toBe("");
  });

  it("열 때 한 번 읽고, 내용마다 기간 안·밖 진도·학습시간·출석인정, 출석 주차·기간, 학교 학습창 안내와 e-Class 시청 링크를 보인다. 재생·쓰기는 없다", async () => {
    await openVideo();
    const body = host.querySelector('[data-school-video="school-801"]')!;
    expect(body).not.toBeNull();
    expect(body.querySelector("h1")?.textContent).toBe("1주차 강의");
    expect(body.textContent).toContain("1 주 1차시");
    expect(body.textContent).toContain("9월 1일 (화) 오전 12:00 – 9월 7일 (월) 오후 11:59");
    const rows = [...body.querySelectorAll("tbody tr")].map((row) => [...row.querySelectorAll("td")].map((cell) => cell.textContent));
    expect(rows).toEqual([
      ["1", "도입", "100%", "100%", "12.5%", "30분 19초", "28분 14초"],
      ["2", "정리", "40%", "40%", "0%", "1분 5초", "없음"],
    ]);
    expect(body.querySelector("[data-school-only]")?.textContent).toContain("학교 e-Class 학습창에서 시청할 때만 인정");
    const link = body.querySelector<HTMLAnchorElement>("a.school-link")!;
    expect(link.textContent).toContain("e-Class에서 시청");
    expect(link.className).toContain("btn-primary");
    expect(link.getAttribute("href")).toBe(ECLASS);
    expect(link.getAttribute("target")).toBe("_blank");
    expect(link.getAttribute("rel")).toBe("noopener noreferrer");
    expect(host.querySelector("video, audio, iframe")).toBeNull();
    expect(server.send).not.toHaveBeenCalled();
    // The left activity list reads the course's lists; the only detail read is this video's, and nothing about playback.
    expect(server.get.mock.calls.map(([url]) => String(url)).filter((url) => /activities\/|learning-sessions|heartbeats|lecture-items/.test(url))).toEqual([VIDEO]);
    await openVideo();
    expect(calls(VIDEO)).toBe(1);
  });

  it("출석인정 시간이 없는 영상은 학교 학습창 안내 대신 재생하지 않는다는 문장만 둔다", async () => {
    server.replies.set(VIDEO, videoReply({ video: { ...videoReply().video, attendanceInSchoolOnly: false } }));
    await openVideo();
    expect(host.querySelector("[data-school-only]")).toBeNull();
    expect(host.textContent).toContain("STack에서는 이 영상을 재생하지 않습니다");
  });

  it("이 읽기가 없는 백엔드(`video` 없음)는 받은 행 정보와 e-Class 시청 링크를 보인다", async () => {
    const { video: _video, playable: _playable, ...old } = videoReply();
    server.replies.set(VIDEO, old);
    await openVideo();
    const body = host.querySelector('[data-school-video="school-801"]')!;
    expect(body.textContent).toContain("1 주 1차시");
    expect(body.querySelector('[data-school-video-facts="none"]')).not.toBeNull();
    expect(body.querySelector("a.school-link")?.getAttribute("href")).toBe(ECLASS);
    expect(body.textContent).not.toContain("undefined");
  });

  it("409 school_signin_required는 다시 로그인, 로그인 뒤 다시 읽는다. 502는 다시 시도", async () => {
    server.errors.set(VIDEO, problem(409, "school_signin_required"));
    await openVideo();
    await act(async () => host.querySelector<HTMLButtonElement>('[data-school-problem="signin"] button')!.click());
    await openVideo();
    expect(server.signins).toBe(1);
    expect(calls(VIDEO)).toBe(2);
    expect(host.querySelector('[data-school-video="school-801"]')).not.toBeNull();

    schoolVideoDetails.clear();
    server.errors.set(VIDEO, problem(502, "school_unavailable"));
    await openVideo();
    expect(host.querySelector('[data-school-problem="unavailable"]')).not.toBeNull();
    server.errors.delete(VIDEO);
    await act(async () => button("다시 시도")!.click());
    await openVideo();
    expect(host.querySelector('[data-school-video="school-801"]')).not.toBeNull();
  });
});

describe("학교 강의계획서", () => {
  it("userId 없는 담당자: key 경고 없이 이름 첫 글자, 사진을 찾지 않는다", async () => {
    const errors = vi.spyOn(console, "error").mockImplementation(() => {});
    await openSyllabus();
    expect(host.querySelector('[data-syllabus-origin="school"]')).not.toBeNull();
    const cards = [...host.querySelectorAll(".crs-prof")];
    expect(cards.map((card) => card.querySelector("strong")?.textContent)).toEqual(["김교수", "이교수"]);
    expect(cards.map((card) => card.querySelector(".crs-avatar")?.textContent)).toEqual(["김", "이"]);
    expect(host.querySelector(".crs-prof img")).toBeNull();
    expect(host.querySelectorAll("tbody tr")).toHaveLength(2);
    expect(errors.mock.calls.some((args) => String(args[0]).includes("key"))).toBe(false);
    expect(server.get.mock.calls.some(([url]) => String(url).includes("/api/users/"))).toBe(false);
  });

  it("404 syllabus_not_provided는 학교에 계획서가 없다고 말한다", async () => {
    server.errors.set(path("syllabus"), problem(404, "syllabus_not_provided"));
    await openSyllabus();
    expect(host.textContent).toContain("이 과목은 학교 e-Class에 강의계획서가 없습니다.");
  });

  it("409는 다시 로그인 뒤 다시 읽고, 502는 다시 시도한다", async () => {
    server.errors.set(path("syllabus"), problem(409, "school_signin_required"));
    await openSyllabus();
    await act(async () => host.querySelector<HTMLButtonElement>('[data-school-problem="signin"] button')!.click());
    await openSyllabus();
    expect(calls(path("syllabus"))).toBe(2);
    expect(host.querySelectorAll(".crs-prof")).toHaveLength(2);

    syllabi.clear();
    server.errors.set(path("syllabus"), problem(502, "school_unavailable"));
    await openSyllabus();
    expect(host.querySelector('[data-school-problem="unavailable"]')).not.toBeNull();
    server.errors.delete(path("syllabus"));
    await act(async () => button("다시 시도")!.click());
    await openSyllabus();
    expect(host.querySelectorAll(".crs-prof")).toHaveLength(2);
  });

  it("네이티브 계획서의 담당자는 예전처럼 userId로 사진을 찾는다", async () => {
    const { origin: _origin, ...native } = SYLLABUS;
    server.replies.set(path("syllabus"), { ...native, version: 3, instructors: [{ userId: 77, name: "박교수" }] });
    await openSyllabus();
    expect(host.querySelector("[data-syllabus-origin]")).toBeNull();
    expect(host.querySelectorAll(".crs-prof")).toHaveLength(1);
  });
});

describe("학교 출석", () => {
  it("학교 출석 방식·학교 상태 글·기타 상태를 보인다", async () => {
    await openAttendance();
    const sessions = [...host.querySelectorAll(".att-session")];
    expect(sessions).toHaveLength(3);
    expect(sessions[0]!.textContent).toContain("Smart");
    expect(sessions[1]!.textContent).toContain("e-Class 결석 (0%)");
    expect(sessions[1]!.querySelector(".att-status")?.textContent).toBe("결석");
    expect(sessions[2]!.querySelector(".att-status")?.textContent).toBe("기타");
    expect(sessions[2]!.querySelector(".att-status")?.className).toContain("st-empty");
    expect(host.textContent).not.toContain("undefined");
  });

  it("네이티브 출석은 예전처럼 방식 이름을 쓰고 학교 글이 없다", async () => {
    server.replies.set(path("attendance"), attendanceReply(null));
    await openAttendance();
    const sessions = [...host.querySelectorAll(".att-session")];
    expect(sessions[0]!.textContent).toContain("온라인");
    expect(host.textContent).not.toContain("e-Class");
  });

  it("409는 다시 로그인 뒤 다시 읽고, 502는 다시 시도한다", async () => {
    server.errors.set(path("attendance"), problem(409, "school_signin_required"));
    await openAttendance();
    expect(host.querySelector(".att-body [data-school-problem=\"signin\"]")).not.toBeNull();
    await act(async () => host.querySelector<HTMLButtonElement>('[data-school-problem="signin"] button')!.click());
    await openAttendance();
    expect(host.querySelectorAll(".att-session")).toHaveLength(3);

    attendanceViews.clear();
    server.errors.set(path("attendance"), problem(502, "school_unavailable"));
    await openAttendance();
    expect(host.querySelector('[data-school-problem="unavailable"]')).not.toBeNull();
    server.errors.delete(path("attendance"));
    await act(async () => button("다시 시도")!.click());
    await openAttendance();
    expect(host.querySelectorAll(".att-session")).toHaveLength(3);
  });
});

describe("학교 성적", () => {
  it("학교 항목을 네이티브 모양 그대로 그리고 오프라인을 표시한다", async () => {
    await openGrades();
    const rows = [...host.querySelectorAll("[data-grade-item]")];
    expect(rows.map((row) => row.getAttribute("data-grade-item"))).toEqual(["school-31", "school-32"]);
    expect(rows[0]!.textContent).toContain("8.5 / 10점");
    expect(rows[1]!.textContent).toContain("미공개");
    expect(rows[1]!.textContent).toContain("오프라인");
    expect(host.textContent).toContain("합계");
  });

  it("성적과 출석이 409이면 다시 로그인 한 번으로 둘 다 다시 읽는다", async () => {
    server.errors.set(path("grades/me"), problem(409, "school_signin_required"));
    server.errors.set(path("attendance"), problem(409, "school_signin_required"));
    await openGrades();
    expect(host.querySelectorAll('[data-school-problem="signin"]')).toHaveLength(2);
    await act(async () => host.querySelector<HTMLButtonElement>('.crs-remote [data-school-problem="signin"] button')!.click());
    await openGrades();
    expect(server.signins).toBe(1);
    expect(calls(path("grades/me"))).toBe(2);
    expect(calls(path("attendance"))).toBe(2);
    expect(host.querySelectorAll("[data-grade-item]")).toHaveLength(2);
    expect(host.querySelector("[data-school-problem]")).toBeNull();
  });

  it("502는 다시 시도한다", async () => {
    server.errors.set(path("grades/me"), problem(502, "school_unavailable"));
    await openGrades();
    expect(host.querySelector('.crs-remote [data-school-problem="unavailable"]')).not.toBeNull();
    server.errors.delete(path("grades/me"));
    await act(async () => host.querySelector<HTMLButtonElement>('.crs-remote [data-school-problem="unavailable"] button')!.click());
    await openGrades();
    expect(host.querySelectorAll("[data-grade-item]")).toHaveLength(2);
  });
});
