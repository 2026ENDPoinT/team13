// 학교 e-Class에서 가져온 자료(`GET …/materials`의 `origin: "school"`, `GET …/activities/school-<n>`)가 강의실·학습활동 목록·
// 파일모음에서 한 자료 한 행으로 보이고, STack 자료 상세에서 열리며, 학교 쪽 오류마다 상태가 있고, 목록이 학교 상세를
// 스스로 읽지 않는지. 응답은 백엔드 설계 노트 §5의 모양으로 쓴 견본이다.
import { act, createElement, Fragment } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Course } from "../../src/data.ts";
import type * as Backend from "../../src/shared/backend.ts";
import type * as Api from "../../src/shared/api.ts";

const server = vi.hoisted(() => ({
  courses: [] as Course[],
  replies: new Map<string, unknown>(),
  errors: new Map<string, unknown>(),
  posts: new Map<string, unknown[]>(),
  get: vi.fn(),
}));
vi.mock("../../src/shared/backend.ts", async (original) => ({
  ...await original<typeof Backend>(),
  backendState: () => ({ status: "ready", methods: ["eclassPassword"], settled: true }),
  backendStatus: () => "ready",
  backendSettled: () => true,
  courseIdFor: (kj: string) => server.courses.find((course) => course.id === kj)?.courseId ?? null,
  roleIn: () => "STUDENT",
  offers: (method: string) => method === "eclassPassword",
}));
vi.mock("../../src/shared/api.ts", async (original) => ({
  ...await original<typeof Api>(),
  apiGet: async (path: string) => {
    server.get(path);
    if (server.errors.has(path)) throw server.errors.get(path);
    if (!server.replies.has(path)) throw new Error(`Unexpected request: ${path}`);
    return server.replies.get(path);
  },
  apiBlob: async (path: string) => {
    server.get(path);
    const queue = server.posts.get(path) || [];
    const next = queue.shift();
    if (next instanceof Error) throw next;
    return new Blob(["x"]);
  },
  apiPostStatus: async (path: string) => {
    server.get(`POST ${path}`);
    const queue = server.posts.get(path) || [];
    const next = queue.shift();
    if (next instanceof Error) throw next;
    return next;
  },
}));

import { resolveCoursesWith } from "../../src/data.ts";
import { render as renderCourse } from "../../src/areas/course/course.tsx";
import { render as renderLearning } from "../../src/areas/learning/learning.tsx";
import { resolveScreen } from "../../src/router.tsx";
import { ApiError } from "../../src/shared/api.ts";
import { activityParts, activitySnapshot, passServerFilter } from "../../src/shared/course-activities-remote.ts";
import { materialLists, materialKey, materialsOf } from "../../src/shared/materials-remote.ts";
import { listRemotePdfs } from "../../src/shared/course-pdfs-remote.ts";
import { awaitPreview } from "../../src/shared/preview-remote.tsx";
import { useEntryLogin, EntryLoginDialog } from "../../src/shared/entry-login.tsx";
import {
  schoolMaterialDetails, schoolDetailKey, schoolMaterialsOf, schoolFileRef, schoolProblemOf, whileScanning, scanWaitMs, SCAN_TRIES,
} from "../../src/shared/school-content.tsx";
import { getState } from "../../src/store.ts";
import { find } from "../../src/manifest.ts";
import { course } from "../helpers/fixtures.ts";

const reactGlobals = globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean };
reactGlobals.IS_REACT_ACT_ENVIRONMENT = true;
let host: HTMLDivElement;
let root: Root;
const path = (part: string) => `/api/courses/901/${part}`;
const ECLASS = "https://eclass.seoultech.ac.kr/ilos/mp/todo_list_connect.acl?SEQ=812&gubun=lecture_material&KJKEY=A2026";
const problem = (status: number, code: string, retryAfter: number | null = null) => new ApiError(status, { code, retryAfter });

const schoolFile = (n: number, extra: Record<string, unknown> = {}) => ({
  fileId: `school-file-${n}`, name: `Week${n}.pptx`, sizeBytes: 1153433, extension: "pptx", pdf: false, summaryPublished: false,
  origin: "school", copied: false, ...extra,
});
const schoolMaterial = (n: number, weekNo: number, files: unknown[], extra: Record<string, unknown> = {}) => ({
  activityId: `school-${n}`, origin: "school", weekNo, title: `학교 자료 ${n}`, description: null, openAt: "2026-09-15T00:00:00",
  published: true, phase: "open", createdAt: null, version: null, schoolUrl: ECLASS, files, ...extra,
});

function fixture() {
  server.replies.set(path("weeks"), { weeks: [
    { weekNo: 1, title: "1주", startsOn: "2026-09-01", endsOn: "2026-09-07", activities: [
      { activityId: 11, type: "lecture_weeks", title: "서버 영상", phase: "open" },
      { activityId: "school-812", origin: "school", type: "material", schoolType: "lecture_material", title: "학교 자료 812", phase: "open", done: true, schoolUrl: ECLASS },
      { activityId: "school-900", origin: "school", type: "test", schoolType: "test", title: "학교 시험", phase: "open", done: false, schoolUrl: ECLASS },
    ] },
    { weekNo: 2, title: "2주", startsOn: "2026-09-08", endsOn: "2026-09-14", activities: [] },
  ] });
  server.replies.set(path("lecture-progress"), []);
  server.replies.set(path("materials"), { serverTime: "2026-11-14T10:00:00", items: [
    { activityId: 13, weekNo: 1, title: "서버 자료", published: true, version: 1, files: [{ fileId: 713, name: "자료.pdf", pdf: true }] },
    schoolMaterial(812, 1, [schoolFile(55), schoolFile(56, { fileId: 7001, copied: true, name: "Copied.pdf", pdf: true })]),
    schoolMaterial(813, 2, [schoolFile(57, { name: "Notes.hwp", extension: "hwp" })], { title: "주차 목록에 없는 학교 자료" }),
  ] });
  server.replies.set(path("assignments"), []);
  server.replies.set(path("quizzes"), []);
  server.replies.set(path("surveys"), []);
  server.replies.set(`${path("files")}?${new URLSearchParams({ size: "50" })}`, { total: 1, nextCursor: null, items: [
    { weekNo: 1, activityId: 13, activityType: "lecture_material", activityTitle: "서버 자료", file: { fileId: 713, name: "자료.pdf", pdf: true } },
    // A copied school file the backend may also list natively: shown once, natively.
    { weekNo: 1, activityId: 13, activityType: "lecture_material", activityTitle: "서버 자료", file: { fileId: 7001, name: "Copied.pdf", pdf: true } },
  ] });
  server.replies.set(path("activities/school-812"), {
    activityId: "school-812", origin: "school", type: "lecture_material", weekNo: 1, title: "학교 자료 812",
    description: "첫 문단 <b>굵게 아님</b>\n둘째 줄\n\n둘째 문단", openAt: "2026-09-15T00:00:00", closeAt: null, published: true, phase: "open",
    files: [schoolFile(55)], lectureItems: [], version: null, author: "담당 교수", postedAt: "2026-10-30T16:11:00", schoolUrl: ECLASS,
  });
}

async function show(node: () => unknown) {
  await act(async () => root.render(createElement(Fragment, null, node() as never)));
}

async function mountCourse(screenId: string) {
  const screen = find(screenId);
  const params = new URLSearchParams({ kj: "c901" });
  await show(() => renderCourse(screen, { screen, state: getState(), params }));
}

async function mountAddress(pathname: string, query: Record<string, string>) {
  const params = new URLSearchParams(query);
  const screen = resolveScreen(pathname, params)!;
  await show(() => renderLearning(screen, { screen, state: getState(), params }));
  return screen;
}

const detailAddress = { m: "lecture_material", n: "school-812", kj: "c901" };
const ACTIVITY_PATH = "/ilos/cls/st/activity/activity_form.acl";
const schoolDetailCalls = () => server.get.mock.calls.filter(([url]) => /activities\/school-|school-file-/.test(String(url)));

beforeEach(() => {
  server.replies.clear();
  server.errors.clear();
  server.posts.clear();
  server.get.mockClear();
  server.courses = [course({ id: "c901", courseId: 901, ko: "학교 자료 강의실", en: "", server: true })];
  resolveCoursesWith((id) => server.courses.find((entry) => entry.id === id));
  for (const part of activityParts(901)) part.resource.clear();
  schoolMaterialDetails.clear();
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

describe("자료 목록 매핑", () => {
  it("학교 자료는 한 자료 한 행: 주차 목록 행이 자리·상태를, 자료 목록이 파일을 준다", async () => {
    await act(async () => { await Promise.all(activityParts(901).map((entry) => entry.resource.load(entry.key, 901))); });
    const data = materialLists.peek(materialKey(901))!.data;
    expect(materialsOf(data).map((item) => item.activityId)).toEqual([13]);
    expect(schoolMaterialsOf(data).map((item) => item.activityId)).toEqual(["school-812", "school-813"]);
    const { rows } = activitySnapshot(activityParts(901), "c901");
    // Week 1 in the week list's order (native material 13 is not in it, so it ends week 1), then week 2.
    expect(rows.map((row) => row.key)).toEqual(["lecture-11", "school-812", "school-900", "material-13", "school-813"]);
    expect(new Set(rows.map((row) => row.key)).size).toBe(rows.length);
    const fromWeeks = rows.find((row) => row.key === "school-812")!;
    expect(fromWeeks).toMatchObject({ type: "material", school: true, external: false, done: true, weekNo: 1 });
    expect(new URL(fromWeeks.href!, location.origin).searchParams.get("n")).toBe("school-812");
    expect(fromWeeks.files).toHaveLength(2);
    const onlyListed = rows.find((row) => row.key === "school-813")!;
    expect(onlyListed).toMatchObject({ type: "material", school: true, weekNo: 2, missing: false });
    expect(new URL(onlyListed.href!, location.origin).searchParams.get("n")).toBe("school-813");
    // Other imported rows still open the school.
    expect(rows.find((row) => row.key === "school-900")).toMatchObject({ external: true, href: ECLASS });
    expect(rows.filter((row) => passServerFilter(row, "material")).map((row) => row.key)).toEqual(["school-812", "material-13", "school-813"]);
    expect(schoolDetailCalls()).toEqual([]);
  });

  it("학습실 기록은 학교 자료를 만들지 않는다", async () => {
    const records = await listRemotePdfs("c901");
    expect(records.map((record) => record.fileId)).toEqual([713]);
  });

  it("주소: 학교 자료와 학교 파일 미리보기는 문자열 ID 그대로다", () => {
    const material = resolveScreen(ACTIVITY_PATH, new URLSearchParams(detailAddress))!;
    expect(material).toMatchObject({ serverSchoolMaterial: "school-812", serverMaterial: null, kind: "activity" });
    expect(resolveScreen(ACTIVITY_PATH, new URLSearchParams({ m: "lecture_material", n: "school-0", kj: "c901" }))?.serverSchoolMaterial ?? null).toBeNull();
    expect(resolveScreen("/ilos/cls/st/files/preview_form.acl", new URLSearchParams({ n: "school-file-55", kj: "c901" }))?.kind).toBe("preview");
    expect(schoolFileRef({ fileId: "school-file-55" })).toBe("school-file-55");
    expect(schoolFileRef({ fileId: 7001 })).toBe("srv-7001");
  });
});

describe("강의실·학습활동 목록·파일모음", () => {
  it("강의실의 학교 자료 행은 STack 자료 상세로 가는 같은 창 링크이고, 목록은 학교 상세를 읽지 않는다", async () => {
    await mountCourse("40_room_submain");
    const row = host.querySelector<HTMLAnchorElement>('[data-row="school-812"]')!;
    expect(row.tagName).toBe("A");
    expect(row.hasAttribute("target")).toBe(false);
    expect(new URL(row.getAttribute("href")!, location.origin).searchParams.get("n")).toBe("school-812");
    expect(row.getAttribute("title")).toContain("파일 2개");
    expect(host.querySelector('[data-row="school-813"]')).not.toBeNull();
    expect(host.querySelector('[data-row="school-900"]')?.getAttribute("target")).toBe("_blank");
    expect(host.querySelector("img")).toBeNull();
    expect(schoolDetailCalls()).toEqual([]);
  });

  it("학습활동 목록 자료 필터에 학교 자료가 한 번씩 들어간다", async () => {
    await mountCourse("52_activity_filter_material");
    expect([...host.querySelectorAll(".activity")].map((node) => node.getAttribute("data-row"))).toEqual(["school-812", "material-13", "school-813"]);
  });

  it("파일모음은 학교 자료 파일을 보이고 이미 보인 복사본은 다시 넣지 않으며, 학교에 묻지 않는다", async () => {
    await mountCourse("46_room_files_list");
    await act(async () => {});
    const section = host.querySelector('[aria-label="학교 e-Class 자료 파일"]')!;
    expect([...section.querySelectorAll(".crs-file")].map((node) => node.getAttribute("data-file"))).toEqual(["school-file-55", "school-file-57"]);
    const preview = section.querySelector<HTMLAnchorElement>('[data-file="school-file-55"] a.icon-btn')!;
    expect(new URL(preview.getAttribute("href")!, location.origin).searchParams.get("n")).toBe("school-file-55");
    expect(section.querySelector('[data-file="school-file-55"] .crs-file-activity')?.getAttribute("href")).toContain("n=school-812");
    expect(section.textContent).toContain("약 1.1MB");
    expect(host.querySelectorAll('[data-file="7001"]')).toHaveLength(1);
    expect(schoolDetailCalls()).toEqual([]);
  });
});

describe("학교 자료 상세", () => {
  it("본문은 글자 그대로, 작성자·게시일, 파일 미리보기·내려받기, e-Class 링크가 있고 학습 도구는 없다", async () => {
    await mountAddress(ACTIVITY_PATH, detailAddress);
    await act(async () => {});
    expect(server.get.mock.calls.filter(([url]) => url === path("activities/school-812"))).toHaveLength(1);
    const body = host.querySelector('[data-school-material="school-812"]')!;
    expect(body.querySelector("h1")?.textContent).toBe("학교 자료 812");
    expect(body.querySelector(".prose b")).toBeNull();
    expect(body.querySelector(".prose")?.textContent).toContain("<b>굵게 아님</b>");
    expect(body.querySelectorAll(".prose p")).toHaveLength(2);
    expect(body.textContent).toContain("담당 교수");
    expect(body.textContent).toContain("게시일");
    const file = body.querySelector('[data-file="school-file-55"]')!;
    expect(new URL(file.querySelector("a")!.getAttribute("href")!, location.origin).searchParams.get("n")).toBe("school-file-55");
    expect(file.querySelector('button[aria-label="Week55.pptx 내려받기"]')).not.toBeNull();
    expect(body.textContent).not.toContain("학습실");
    expect(body.textContent).not.toContain("요약 보기");
    const link = body.querySelector<HTMLAnchorElement>("a.school-link")!;
    expect(link.getAttribute("href")).toBe(ECLASS);
    expect(link.getAttribute("target")).toBe("_blank");
    expect(link.getAttribute("rel")).toBe("noopener noreferrer");
    // A re-render of the same visit does not read the school again.
    await mountAddress(ACTIVITY_PATH, detailAddress);
    expect(server.get.mock.calls.filter(([url]) => url === path("activities/school-812"))).toHaveLength(1);
  });

  it("409 school_signin_required: 다시 로그인 안내가 로그인 창을 열고, 닫기로 남는다", async () => {
    server.errors.set(path("activities/school-812"), problem(409, "school_signin_required"));
    await mountAddress(ACTIVITY_PATH, detailAddress);
    await act(async () => {});
    expect(host.querySelector('[data-school-problem="signin"]')).not.toBeNull();
    const probe = document.createElement("div");
    document.body.append(probe);
    const probeRoot = createRoot(probe);
    function Modal() {
      return useEntryLogin() ? createElement(EntryLoginDialog) : null;
    }
    await act(async () => probeRoot.render(createElement(Modal)));
    expect(probe.querySelector('[role="dialog"]')).toBeNull();
    await act(async () => host.querySelector<HTMLButtonElement>('[data-school-problem="signin"] button')!.click());
    expect(probe.querySelector('[role="dialog"]')?.getAttribute("aria-label")).toBe("e-Class 다시 로그인");
    expect(probe.querySelector('input[name="studentId"]')).not.toBeNull();
    await act(async () => [...probe.querySelectorAll<HTMLButtonElement>("button")].find((button) => button.textContent === "닫기")!.click());
    expect(probe.querySelector('[role="dialog"]')).toBeNull();
    act(() => probeRoot.unmount());
    probe.remove();
  });

  it("502 school_unavailable: 다시 시도가 같은 자료를 다시 읽는다", async () => {
    server.errors.set(path("activities/school-812"), problem(502, "school_unavailable"));
    await mountAddress(ACTIVITY_PATH, detailAddress);
    await act(async () => {});
    expect(host.querySelector('[data-school-problem="unavailable"]')?.textContent).toContain("학교 e-Class 서버에서 답이 없습니다");
    server.errors.delete(path("activities/school-812"));
    await act(async () => [...host.querySelectorAll<HTMLButtonElement>("button")].find((button) => button.textContent === "다시 시도")!.click());
    await mountAddress(ACTIVITY_PATH, detailAddress);
    expect(host.querySelector('[data-school-material="school-812"]')).not.toBeNull();
  });

  it("내려받기: 422는 e-Class 링크를, 409 school_signin_required는 다시 로그인을 보인다", async () => {
    await mountAddress(ACTIVITY_PATH, detailAddress);
    await act(async () => {});
    const content = path("files/school-file-55/content");
    server.posts.set(content, [problem(422, "school_file_too_large")]);
    await act(async () => host.querySelector<HTMLButtonElement>('button[aria-label="Week55.pptx 내려받기"]')!.click());
    const note = host.querySelector('[data-file="school-file-55"] [data-school-problem="too_large"]')!;
    expect(note.textContent).toContain("너무 커서");
    expect(note.querySelector("a.school-link")?.getAttribute("href")).toBe(ECLASS);
    server.posts.set(content, [problem(409, "school_signin_required")]);
    await act(async () => host.querySelector<HTMLButtonElement>('button[aria-label="Week55.pptx 내려받기"]')!.click());
    expect(host.querySelector('[data-file="school-file-55"] [data-school-problem="signin"] button')?.textContent).toContain("다시 로그인");
  });
});

describe("file_scanning: Retry-After만큼 기다렸다 다시, 횟수는 정해져 있다", () => {
  it("whileScanning은 Retry-After를 지키고 다른 오류는 바로 던진다", async () => {
    const waits: number[] = [];
    const wait = async (ms: number) => { waits.push(ms); };
    let calls = 0;
    const value = await whileScanning(async () => {
      calls += 1;
      if (calls < 3) throw problem(409, "file_scanning", 5);
      return "ok";
    }, { wait });
    expect(value).toBe("ok");
    expect(waits).toEqual([5000, 5000]);
    await expect(whileScanning(async () => { throw problem(502, "school_unavailable"); }, { wait })).rejects.toMatchObject({ code: "school_unavailable" });
    let tries = 0;
    await expect(whileScanning(async () => { tries += 1; throw problem(409, "file_scanning", 2); }, { wait })).rejects.toMatchObject({ code: "file_scanning" });
    expect(tries).toBe(SCAN_TRIES + 1);
    expect(scanWaitMs(problem(409, "file_scanning", 600))).toBe(30000);
    expect(scanWaitMs(problem(409, "file_scanning"))).toBe(5000);
    expect(schoolProblemOf(problem(409, "version_conflict"))).toBeNull();
  });

  it("미리보기는 검사 중이면 기다렸다 같은 학교 파일 ID로 다시 연다", async () => {
    vi.useFakeTimers();
    const sessions = path("files/school-file-55/preview-sessions");
    server.posts.set(sessions, [problem(409, "file_scanning", 1), { status: 200, retryAfter: null, body: { state: "ready", pageCount: 3, pageImageBase: "/doc/k/" } }]);
    const scans: number[] = [];
    const done = awaitPreview(901, "school-file-55", { onScan: () => scans.push(1) });
    await vi.advanceTimersByTimeAsync(1000);
    const view = await done;
    vi.useRealTimers();
    expect(view).toMatchObject({ state: "ready", pageCount: 3 });
    expect(scans).toHaveLength(1);
    expect(server.get.mock.calls.filter(([url]) => url === `POST ${sessions}`)).toHaveLength(2);
  });
});

describe("숫자 경로에 문자열 ID가 들어가지 않는다", () => {
  it("상세 캐시 키와 화면에 NaN이 없다", async () => {
    expect(schoolDetailKey(901, "school-812")).toBe("901|school-812");
    await mountAddress(ACTIVITY_PATH, detailAddress);
    await act(async () => {});
    expect(host.textContent).not.toContain("NaN");
    expect(host.querySelector('[data-item-open="school-812"]')).not.toBeNull();
  });
});
