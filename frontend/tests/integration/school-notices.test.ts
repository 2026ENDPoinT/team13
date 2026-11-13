// 학교 e-Class에서 가져온 공지(`GET …/notices`의 `origin: "school"`, `noticeId: "school-<n>"`, 상세 `GET …/notices/school-<n>`)가
// 과목 공지 목록에 보이고, 연 공지만 학교에서 읽으며, STack이 `read`·`confirm`을 보내지 않는지. 응답은 백엔드 담당이 알려 준 모양의 견본이다.
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
  get: vi.fn(),
  send: vi.fn(),
}));
vi.mock("../../src/shared/backend.ts", async (original) => ({
  ...await original<typeof Backend>(),
  backendState: () => ({ status: "ready", methods: ["eclassPassword"], settled: true }),
  backendStatus: () => "ready",
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
    return { noticeId: 7, read: true, confirmed: false };
  },
}));

import { resolveCoursesWith } from "../../src/data.ts";
import { render as renderCourse } from "../../src/areas/course/course.tsx";
import { remoteNoticeDialog } from "../../src/areas/course/notices-server.tsx";
import { ApiError } from "../../src/shared/api.ts";
import { noticeLists, noticeDetails, isSchoolNotice, isNativeNotice, markNoticeRead, confirmNotice, noticeFlags } from "../../src/shared/notices-remote.ts";
import { getState } from "../../src/store.ts";
import { find } from "../../src/manifest.ts";
import { course } from "../helpers/fixtures.ts";

const reactGlobals = globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean };
reactGlobals.IS_REACT_ACT_ENVIRONMENT = true;
let host: HTMLDivElement;
let root: Root;
const ECLASS = "https://eclass.seoultech.ac.kr/ilos/mp/todo_list_connect.acl?SEQ=31&gubun=notice&KJKEY=A2026";
const LIST = `/api/courses/901/notices?${new URLSearchParams({ page: "1", size: "8" })}`;
const DETAIL = "/api/courses/901/notices/school-31";
const fixtureCourse = () => course({ id: "c901", courseId: 901, ko: "학교 공지 강의실", en: "", server: true });

function fixture() {
  server.replies.set(LIST, { page: 1, size: 8, total: 2, items: [
    { noticeId: 7, title: "STack 공지", excerpt: "본문", pinned: false, published: true, publishedAt: "2026-11-10T09:00:00", read: false, viewCount: 3 },
    { noticeId: "school-31", origin: "school", title: "학교 공지", excerpt: "학교 본문", pinned: false, published: true, publishedAt: "2026-11-09T09:00:00", read: true, expired: true, schoolUrl: ECLASS },
  ] });
  server.replies.set(DETAIL, {
    noticeId: "school-31", origin: "school", title: "학교 공지", body: "첫 줄 <script>x</script>\n\n둘째 문단", authorName: "담당 교수",
    published: true, publishedAt: "2026-11-09T09:00:00", read: true, expired: true, schoolUrl: ECLASS,
    files: [{ fileId: "school-file-90", name: "공지첨부.pdf", sizeBytes: 20480, extension: "pdf", pdf: true, origin: "school", copied: false }],
  });
  server.replies.set("/api/courses/901/notices/7", { noticeId: 7, title: "STack 공지", body: "본문", published: true, publishedAt: "2026-11-10T09:00:00", read: false, viewCount: 3 });
}

async function show(node: () => unknown) {
  await act(async () => root.render(createElement(Fragment, null, node() as never)));
}

async function openDialog(id: string) {
  const params = new URLSearchParams({ kj: "c901", pop: "notice", id });
  const screen = find("44d_room_notice_view_pop");
  await show(() => remoteNoticeDialog({ screen, state: getState(), params }, fixtureCourse()));
  await act(async () => {});
}

beforeEach(() => {
  server.replies.clear();
  server.errors.clear();
  server.get.mockClear();
  server.send.mockClear();
  server.courses = [fixtureCourse()];
  resolveCoursesWith((id) => server.courses.find((entry) => entry.id === id));
  noticeLists.clear();
  noticeDetails.clear();
  const state = getState();
  state.draft = {};
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

describe("학교 공지", () => {
  it("목록에 학교 표시와 문자열 ID 링크로 보이고, 목록은 학교 상세를 읽지 않는다", async () => {
    const screen = find("44_room_notice_list");
    await show(() => renderCourse(screen, { screen, state: getState(), params: new URLSearchParams({ kj: "c901" }) }));
    await act(async () => {});
    const row = host.querySelector('[data-notice="school-31"]')!;
    expect(row.textContent).toContain("학교 e-Class");
    expect(row.textContent).toContain("기간 지남");
    expect(row.textContent).not.toContain("undefined");
    expect(new URL(row.querySelector("a")!.getAttribute("href")!, location.origin).searchParams.get("id")).toBe("school-31");
    expect(host.querySelector('[data-notice="7"]')).not.toBeNull();
    expect(server.get.mock.calls.map(([url]) => url)).toEqual([LIST]);
  });

  it("상세는 학교에서 한 번 읽고, 읽음·확인을 보내지 않으며, 첨부와 e-Class 링크와 닫기만 있다", async () => {
    await openDialog("school-31");
    expect(server.get.mock.calls.filter(([url]) => url === DETAIL)).toHaveLength(1);
    expect(server.send).not.toHaveBeenCalled();
    const body = host.querySelector('[data-school-notice="school-31"]')!;
    expect(body.querySelector("script")).toBeNull();
    expect(body.textContent).toContain("<script>x</script>");
    expect(body.textContent).toContain("담당 교수");
    expect(body.textContent).not.toContain("공지를 확인했습니다");
    const file = body.querySelector('[data-file="school-file-90"]')!;
    expect(new URL(file.querySelector("a")!.getAttribute("href")!, location.origin).searchParams.get("n")).toBe("school-file-90");
    expect(file.querySelector('button[aria-label="공지첨부.pdf 내려받기"]')).not.toBeNull();
    const link = body.querySelector<HTMLAnchorElement>("a.school-link")!;
    expect(link.getAttribute("href")).toBe(ECLASS);
    expect(link.getAttribute("target")).toBe("_blank");
    expect([...body.querySelectorAll("button")].some((button) => button.textContent === "닫기")).toBe(true);
  });

  it("409 school_signin_required는 다시 로그인을, 502는 다시 시도를 보인다", async () => {
    server.errors.set(DETAIL, new ApiError(409, { code: "school_signin_required" }));
    await openDialog("school-31");
    expect(host.querySelector('[data-school-problem="signin"] button')?.textContent).toContain("다시 로그인");
    noticeDetails.clear();
    server.errors.set(DETAIL, new ApiError(502, { code: "school_unavailable" }));
    await openDialog("school-31");
    expect(host.querySelector('[data-school-problem="unavailable"]')).not.toBeNull();
    server.errors.delete(DETAIL);
    await act(async () => [...host.querySelectorAll<HTMLButtonElement>("button")].find((button) => button.textContent === "다시 시도")!.click());
    await openDialog("school-31");
    expect(host.querySelector('[data-school-notice="school-31"]')).not.toBeNull();
  });

  it("STack 공지는 예전처럼 열면 읽음을 보낸다", async () => {
    await openDialog("7");
    expect(server.send).toHaveBeenCalledWith("POST", "/api/courses/901/notices/7/read");
    expect(host.textContent).toContain("공지를 확인했습니다");
  });

  it("학교 공지에는 읽음·확인 요청을 만들지 않고, 교수 목록은 STack 공지만 쓴다", async () => {
    expect(await markNoticeRead(901, "school-31")).toBeNull();
    await expect(confirmNotice(901, "school-31")).rejects.toThrow();
    expect(server.send).not.toHaveBeenCalled();
    expect(isSchoolNotice("school-31")).toBe(true);
    expect(isSchoolNotice("school-0")).toBe(false);
    expect(isSchoolNotice(31)).toBe(false);
    expect(isNativeNotice({ noticeId: 7, title: "" })).toBe(true);
    expect(isNativeNotice({ noticeId: "school-31", title: "" })).toBe(false);
    expect(noticeFlags({ pinned: false, published: true, publishedAt: "2026-11-01", expired: true })).toEqual(["기간 지남"]);
  });
});
