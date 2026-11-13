// 학교 e-Class에서 가져온 과제(`GET …/assignments`의 `origin: "school"`, 상세 `GET …/assignments/school-<n>`)와 학교 할 일
// (`/api/me/todo`·홈 집계의 `origin: "school"`): 목록의 한 과제 한 행과 STack 상세 링크, 읽기 전용 상세(설명·기간·첨부·내 제출과
// 내 파일, "e-Class에서 제출"), 학교 오류(409 다시 로그인, 502 다시 시도, 409 school_submit_at_school), 할 일 링크(과제는 STack 상세,
// 시험·설문은 새 탭의 e-Class). 응답은 백엔드 설계 노트 §5c의 모양으로 쓴 견본이다. 네이티브 과제·할 일은 예전 그대로인지도 본다.
import { act, createElement, Fragment } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Course } from "../../src/data.ts";
import type { Entry } from "../../src/shared/remote-cache.ts";
import type { HomeData, TodoList } from "../../src/shared/home-remote.ts";
import type * as Backend from "../../src/shared/backend.ts";
import type * as Api from "../../src/shared/api.ts";
import type * as HomeRemote from "../../src/shared/home-remote.ts";
import type * as EntryLogin from "../../src/shared/entry-login.tsx";

const server = vi.hoisted(() => ({
  courses: [] as Course[],
  replies: new Map<string, unknown>(),
  errors: new Map<string, unknown>(),
  get: vi.fn(),
  send: vi.fn(),
  signins: 0,
  home: null as Entry<HomeData> | null,
  todo: null as Entry<TodoList> | null,
}));
vi.mock("../../src/shared/backend.ts", async (original) => ({
  ...await original<typeof Backend>(),
  backendState: () => ({ status: "ready", methods: ["eclassPassword"], settled: true, me: { userId: 5 },
    courses: [{ courseId: 901, role: "STUDENT", name: "학교 과제 강의실" }] }),
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
vi.mock("../../src/shared/home-remote.ts", async (original) => ({
  ...await original<typeof HomeRemote>(),
  useHome: () => server.home,
  homeEntry: () => server.home,
  useTodo: () => server.todo,
  todoEntry: () => server.todo,
}));
// The sign-in itself is the login modal's job; here it succeeds at once so the screen's `done` runs.
vi.mock("../../src/shared/entry-login.tsx", async (original) => ({
  ...await original<typeof EntryLogin>(),
  requestSchoolSignin: (done: () => void) => {
    server.signins += 1;
    server.errors.clear();
    done();
  },
}));

import { resolveCoursesWith } from "../../src/data.ts";
import { render as renderLearning } from "../../src/areas/learning/learning.tsx";
import { renderDialog } from "../../src/areas/learning/dialogs.tsx";
import { serverAsideTodo, serverTodoList } from "../../src/areas/home/home-server.tsx";
import { resolveScreen } from "../../src/router.tsx";
import { ApiError } from "../../src/shared/api.ts";
import { activityParts, activitySnapshot } from "../../src/shared/course-activities-remote.ts";
import { assignmentMessage, schoolAssignmentDetails, studentAssignmentHref } from "../../src/shared/assignments-remote.ts";
import { quizMessage } from "../../src/shared/quizzes-remote.ts";
import { surveyMessage } from "../../src/shared/surveys-remote.ts";
import { schoolProblemOf, SCHOOL_PROBLEM_TEXT } from "../../src/shared/school-content.tsx";
import { getState } from "../../src/store.ts";
import { course } from "../helpers/fixtures.ts";

const reactGlobals = globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean };
reactGlobals.IS_REACT_ACT_ENVIRONMENT = true;
let host: HTMLDivElement;
let root: Root;
const path = (part: string) => `/api/courses/901/${part}`;
const ACTIVITY_PATH = "/ilos/cls/st/activity/activity_form.acl";
const ECLASS = "https://eclass.seoultech.ac.kr/ilos/mp/todo_list_connect.acl?SEQ=812&gubun=report&KJKEY=A2026";
const DETAIL = path("assignments/school-812");
const problem = (status: number, code: string) => new ApiError(status, { code });
const theCourse = () => course({ id: "c901", courseId: 901, ko: "학교 과제 강의실", en: "", server: true });
const schoolFile = (n: number, extra: Record<string, unknown> = {}) => ({
  fileId: `school-file-${n}`, name: `File${n}.pdf`, sizeBytes: 89470, extension: "pdf", pdf: true, origin: "school", copied: false, ...extra,
});

function lists() {
  server.replies.set(path("weeks"), { weeks: [
    { weekNo: 9, title: "9주", startsOn: "2026-10-27", endsOn: "2026-11-02", activities: [
      { activityId: 21, type: "report", assignmentId: 701, title: "서버 과제", phase: "open" },
      { activityId: "school-812", origin: "school", type: "report", schoolType: "report", title: "학교 과제 812", phase: "closed", done: true, schoolUrl: ECLASS },
    ] },
  ] });
  server.replies.set(path("lecture-progress"), []);
  server.replies.set(path("materials"), { items: [] });
  server.replies.set(path("assignments"), [
    { assignmentId: 701, activityId: 21, weekNo: 9, title: "서버 과제", openAt: null, closeAt: "2026-11-20T23:59:00", phase: "open", status: "not_submitted" },
    { assignmentId: "school-812", activityId: "school-812", origin: "school", weekNo: 9, title: "학교 과제 812", openAt: null, closeAt: "2026-11-01T23:59:00", phase: "closed", status: "submitted", schoolUrl: ECLASS },
    // Only the assignment list has this one (the week list was read before it was imported).
    { assignmentId: "school-813", activityId: "school-813", origin: "school", weekNo: 9, title: "학교 과제 813", openAt: null, closeAt: "2026-11-30T23:59:00", phase: "open", status: "not_submitted", schoolUrl: ECLASS },
  ]);
  server.replies.set(path("quizzes"), []);
  server.replies.set(path("surveys"), []);
}

function detailReply(extra: Record<string, unknown> = {}) {
  return {
    assignment: {
      assignmentId: "school-812", activityId: "school-812", origin: "school", weekNo: 9, title: "학교 과제 812",
      description: "첫 문단 <b>굵게 아님</b>\n\n둘째 문단", openAt: "2026-10-27T00:00:00", closeAt: "2026-11-01T23:59:00", published: true,
      latePolicy: null, lateUntil: null, allowText: null, allowFile: null, maxScore: null, scoresReleased: false,
      files: [schoolFile(55, { name: "과제안내.pdf" })], schoolUrl: ECLASS,
    },
    phase: "closed", serverTime: "2026-11-14T10:00:00",
    submission: {
      status: "submitted", version: null, firstSubmittedAt: null, submittedAt: "2026-10-31T12:01:00", body: "제가 쓴 글",
      files: [schoolFile(90, { name: "내제출.pdf" })], draft: null, statusLabel: "정상제출",
    },
    origin: "school", schoolUrl: ECLASS,
    ...extra,
  };
}

const TODO_ITEMS = [
  { activityId: "school-812", courseId: 901, type: "report", title: "학교 과제", closeAt: "2026-11-15T23:59:00", origin: "school", schoolUrl: ECLASS, courseName: "학교 과제 강의실", courseCode: "A1" },
  { activityId: "school-77", courseId: 901, type: "test", title: "학교 시험", closeAt: "2026-11-16T23:59:00", origin: "school", schoolUrl: `${ECLASS}&t=77`, courseName: "학교 과제 강의실", courseCode: "A1" },
  { activityId: "school-78", courseId: 901, type: "survey", title: "학교 설문", closeAt: "2026-11-17T23:59:00", origin: "school", schoolUrl: "https://evil.example/x", courseName: "학교 과제 강의실", courseCode: "A1" },
  { activityId: 21, courseId: 901, type: "report", title: "서버 과제", assignmentId: 701, closeAt: "2026-11-20T23:59:00", courseName: "학교 과제 강의실", courseCode: "A1" },
];

function entry<T>(data: T): Entry<T> {
  return { data, error: null, loading: false } as unknown as Entry<T>;
}

async function show(node: () => unknown) {
  await act(async () => root.render(createElement(Fragment, null, node() as never)));
  await act(async () => {});
}

async function openDetail(query: Record<string, string> = {}) {
  const params = new URLSearchParams({ m: "report", n: "school-812", kj: "c901", ...query });
  const screen = resolveScreen(ACTIVITY_PATH, params)!;
  await show(() => renderLearning(screen, { screen, state: getState(), params }));
  return screen;
}

const detailCalls = () => server.get.mock.calls.filter(([url]) => url === DETAIL).length;
const button = (text: string) => [...host.querySelectorAll<HTMLButtonElement>("button")].find((node) => node.textContent?.includes(text));

beforeEach(() => {
  server.replies.clear();
  server.errors.clear();
  server.get.mockClear();
  server.send.mockClear();
  server.signins = 0;
  server.courses = [theCourse()];
  resolveCoursesWith((id) => server.courses.find((entry) => entry.id === id));
  for (const part of activityParts(901)) part.resource.clear();
  schoolAssignmentDetails.clear();
  const state = getState();
  state.draft = {};
  state.courseId = "c901";
  lists();
  server.replies.set(DETAIL, detailReply());
  server.home = entry<HomeData>({ serverTime: "2026-11-14T10:00:00", courses: [{ courseId: 901, name: "학교 과제 강의실", role: "STUDENT" }],
    todo: { total: TODO_ITEMS.length, serverTime: "2026-11-14T10:00:00", items: TODO_ITEMS } } as unknown as HomeData);
  server.todo = entry<TodoList>({ serverTime: "2026-11-14T10:00:00", total: TODO_ITEMS.length, nextCursor: null, items: TODO_ITEMS } as unknown as TodoList);
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

describe("목록: 한 학교 과제 한 행, STack 상세 링크", () => {
  it("주차 목록에 있는 학교 과제는 한 번, 과제 목록에만 있는 것도 행이 되고, 둘 다 n=school-<n> 상세로 간다. 학교 상세는 읽지 않는다", async () => {
    await act(async () => { await Promise.all(activityParts(901).map((part) => part.resource.load(part.key, 901))); });
    const { rows } = activitySnapshot(activityParts(901), "c901");
    const reports = rows.filter((row) => row.type === "report");
    expect(reports.map((row) => row.key)).toEqual(["report-701", "school-812", "school-813"]);
    const fromWeeks = reports[1]!;
    expect(fromWeeks).toMatchObject({ school: true, external: false, done: true });
    expect(fromWeeks.href).toBe(studentAssignmentHref("c901", "school-812"));
    expect(new URL(fromWeeks.href!, location.origin).searchParams.get("n")).toBe("school-812");
    expect(reports[2]).toMatchObject({ school: true, done: false, missing: true, href: studentAssignmentHref("c901", "school-813") });
    // The native assignment keeps its `srv-` detail.
    expect(new URL(reports[0]!.href!, location.origin).searchParams.get("n")).toBe("srv-701");
    expect(server.get.mock.calls.some(([url]) => String(url).includes("/assignments/school-"))).toBe(false);
  });

  it("주소 m=report&n=school-<n>은 학교 과제 상세이고 네이티브 주소는 그대로다", () => {
    expect(resolveScreen(ACTIVITY_PATH, new URLSearchParams({ m: "report", n: "school-812", kj: "c901" }))).toMatchObject({ serverSchoolAssignment: "school-812", kind: "activity" });
    expect(resolveScreen(ACTIVITY_PATH, new URLSearchParams({ m: "report", n: "srv-701", kj: "c901" }))).toMatchObject({ serverAssignment: 701 });
    expect(resolveScreen(ACTIVITY_PATH, new URLSearchParams({ m: "report", n: "school-0", kj: "c901" }))?.serverSchoolAssignment ?? null).toBeNull();
  });
});

describe("학교 과제 상세(읽기 전용)", () => {
  it("열 때 한 번 읽고 설명·기간·첨부·내 제출(시각·학교 표시·글·내 파일)과 e-Class 제출 링크를 보인다. 제출 폼과 쓰기는 없다", async () => {
    await openDetail();
    const body = host.querySelector('[data-school-assignment="school-812"]')!;
    expect(body).not.toBeNull();
    expect(body.querySelector("h1")?.textContent).toBe("학교 과제 812");
    expect(body.querySelector(".prose b")).toBeNull();
    expect(body.textContent).toContain("<b>굵게 아님</b>");
    expect(body.textContent).toContain("마감");
    expect(body.textContent).toContain("정상제출");
    expect(body.textContent).toContain("제가 쓴 글");
    expect(body.textContent).toContain("제출함");
    const attachment = body.querySelector('[data-file="school-file-55"]')!;
    expect(attachment.textContent).toContain("과제안내.pdf");
    expect(new URL(attachment.querySelector("a")!.getAttribute("href")!, location.origin).searchParams.get("n")).toBe("school-file-55");
    const own = body.querySelector('[data-school-own-files] [data-file="school-file-90"]')!;
    expect(own.textContent).toContain("내제출.pdf");
    expect(own.querySelector('button[aria-label="내제출.pdf 내려받기"]')).not.toBeNull();
    const link = body.querySelector<HTMLAnchorElement>("a.school-link")!;
    expect(link.textContent).toContain("e-Class에서 제출");
    expect(link.getAttribute("href")).toBe(ECLASS);
    expect(link.getAttribute("target")).toBe("_blank");
    expect(link.getAttribute("rel")).toBe("noopener noreferrer");
    expect(body.querySelector("textarea, input[type=file]")).toBeNull();
    expect([...body.querySelectorAll("a, button")].some((node) => /^(다시 )?제출$/.test(node.textContent || ""))).toBe(false);
    expect(host.textContent).not.toContain("NaN");
    expect(host.textContent).not.toContain("undefined");
    expect(server.send).not.toHaveBeenCalled();
    expect(detailCalls()).toBe(1);
    await openDetail();
    expect(detailCalls()).toBe(1);
  });

  it("미제출 과제는 내 파일 없이 상태만, e-Class 제출 링크는 그대로다", async () => {
    server.replies.set(DETAIL, detailReply({ phase: "open", submission: { status: "not_submitted", version: null, submittedAt: null, body: null, files: [], draft: null, statusLabel: null } }));
    await openDetail();
    const body = host.querySelector('[data-school-assignment="school-812"]')!;
    expect(body.textContent).toContain("미제출");
    expect(body.querySelectorAll("[data-school-own-files] [data-file]")).toHaveLength(0);
    expect(body.querySelector("a.school-link")?.getAttribute("href")).toBe(ECLASS);
  });

  it("409 school_signin_required는 다시 로그인 뒤 다시 읽고, 502는 다시 시도한다", async () => {
    server.errors.set(DETAIL, problem(409, "school_signin_required"));
    await openDetail();
    await act(async () => host.querySelector<HTMLButtonElement>('[data-school-problem="signin"] button')!.click());
    await openDetail();
    expect(server.signins).toBe(1);
    expect(detailCalls()).toBe(2);
    expect(host.querySelector('[data-school-assignment="school-812"]')).not.toBeNull();

    schoolAssignmentDetails.clear();
    server.errors.set(DETAIL, problem(502, "school_unavailable"));
    await openDetail();
    expect(host.querySelector('[data-school-problem="unavailable"]')).not.toBeNull();
    server.errors.delete(DETAIL);
    await act(async () => button("다시 시도")!.click());
    await openDetail();
    expect(host.querySelector('[data-school-assignment="school-812"]')).not.toBeNull();
  });

  it("pop=submit은 제출 폼 대신 e-Class에서 제출 안내만 보이고 아무것도 보내지 않는다", async () => {
    const screen = await openDetail({ pop: "submit" });
    const calls = server.get.mock.calls.length;
    await show(() => renderDialog("submit", { screen, state: getState(), params: new URLSearchParams({ m: "report", n: "school-812", kj: "c901", pop: "submit" }) }));
    const note = host.querySelector('[data-school-problem="at_school"]')!;
    expect(note.textContent).toContain(SCHOOL_PROBLEM_TEXT.at_school);
    expect(note.querySelector("a.school-link")?.getAttribute("href")).toBe(ECLASS);
    expect(host.querySelector("textarea, input[type=file]")).toBeNull();
    expect(server.get.mock.calls.length).toBe(calls);
    expect(server.send).not.toHaveBeenCalled();
  });

  it("409 school_submit_at_school는 과제·시험·설문 모두 같은 문장이다", () => {
    const refused = problem(409, "school_submit_at_school");
    expect(schoolProblemOf(refused)).toBe("at_school");
    for (const message of [assignmentMessage(refused), quizMessage(refused), surveyMessage(refused)]) expect(message).toBe(SCHOOL_PROBLEM_TEXT.at_school);
    expect(assignmentMessage(problem(409, "version_conflict"))).not.toBe(SCHOOL_PROBLEM_TEXT.at_school);
  });
});

describe("학교 할 일 링크", () => {
  function check(rows: HTMLElement[]) {
    const [report, test, survey, native] = rows;
    expect(report!.tagName).toBe("A");
    expect(report!.hasAttribute("target")).toBe(false);
    expect(new URL(report!.getAttribute("href")!, location.origin).searchParams.get("n")).toBe("school-812");
    expect(new URL(report!.getAttribute("href")!, location.origin).searchParams.get("m")).toBe("report");
    expect(test!.getAttribute("href")).toBe(`${ECLASS}&t=77`);
    expect(test!.getAttribute("target")).toBe("_blank");
    expect(test!.getAttribute("rel")).toBe("noopener noreferrer");
    expect(test!.getAttribute("aria-label")).toContain("학교 e-Class에서 열기(새 탭)");
    // A school address on another host is not a link.
    expect(survey!.tagName).toBe("DIV");
    expect(survey!.hasAttribute("href")).toBe(false);
    expect(new URL(native!.getAttribute("href")!, location.origin).searchParams.get("n")).toBe("srv-701");
    expect(native!.hasAttribute("target")).toBe(false);
  }

  it("홈 할일 카드: 과제는 STack 상세, 시험은 새 탭 e-Class, 다른 호스트는 링크가 아니다, 네이티브는 그대로", async () => {
    await show(() => serverAsideTodo("/todo"));
    const cards = [...host.querySelectorAll<HTMLElement>(".todo-card")];
    expect(cards).toHaveLength(4);
    check(cards);
  });

  it("할 일 창도 같다", async () => {
    await show(() => serverTodoList());
    check([...host.querySelectorAll<HTMLElement>(".task-row")]);
    expect(server.get).not.toHaveBeenCalled();
  });
});
