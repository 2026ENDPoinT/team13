import { beforeEach, describe, expect, it, vi } from "vitest";
import type * as Api from "../../src/shared/api.ts";
import { backendState, connect, subscribe } from "../../src/shared/backend.ts";
import { applyCourseCounts, homeEntry, reloadHome } from "../../src/shared/home-remote.ts";
import { markNoticeRead } from "../../src/shared/notices-remote.ts";

// The server: `unreadNotices` on the course records drops once the notice is read.
const server = vi.hoisted(() => ({ unread: 3, read: new Set<number>(), coursesReads: 0 }));
vi.mock("../../src/shared/api.ts", async (original) => {
  const real = await original<typeof Api>();
  const course = () => ({ courseId: 1, key: "bi", role: "STUDENT", name: "데이터베이스", unreadNotices: server.unread });
  return {
    ...real,
    apiGet: vi.fn(async (path: string) => {
      if (path === "/api/auth/methods") return { methods: ["devLogin"] };
      if (path === "/api/me") return { userId: 7, name: "학생" };
      if (path === "/api/me/courses") {
        server.coursesReads += 1;
        return [course()];
      }
      if (path === "/api/me/home") {
        return { courses: [course()], notices: [{ noticeId: 22, courseId: 1, title: "공지", read: server.read.has(22) }] };
      }
      throw new Error(`unexpected GET ${path}`);
    }),
    apiSend: vi.fn(async (method: string, path: string) => {
      const match = /^\/api\/courses\/1\/notices\/(\d+)\/read$/.exec(path);
      if (method !== "POST" || !match) throw new Error(`unexpected ${method} ${path}`);
      const noticeId = Number(match[1]);
      if (!server.read.has(noticeId)) {
        server.read.add(noticeId);
        server.unread -= 1;
      }
      return { noticeId, read: true, confirmed: false };
    }),
  };
});


describe("applyCourseCounts", () => {
  it("copies unreadNotices onto the home aggregate's same courses and leaves the rest", () => {
    const data = { courses: [{ courseId: 1, role: "STUDENT", unreadNotices: 3 }, { courseId: 2, role: "STUDENT", unreadNotices: 4 }] };
    applyCourseCounts(data, [{ courseId: "1", unreadNotices: 2 }, { courseId: 3, unreadNotices: 9 }, { courseId: 2, unreadNotices: null }]);
    expect(data.courses.map((course) => course.unreadNotices)).toEqual([2, 4]);
  });

  it("does nothing without courses", () => {
    const data = {};
    applyCourseCounts(data, [{ courseId: 1, unreadNotices: 1 }]);
    expect(data).toEqual({});
  });
});

describe("markNoticeRead → course unread counts", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ enabled: true }), { status: 200 })));
  });

  it("re-reads the course records and the cached home aggregate without a reload", async () => {
    await connect();
    expect(backendState().status).toBe("ready");
    expect(backendState().courses[0].unreadNotices).toBe(3);
    reloadHome();
    await vi.waitFor(() => expect(homeEntry()?.data?.courses?.[0].unreadNotices).toBe(3));
    const seen: (number | null | undefined)[] = [];
    const stop = subscribe((next) => seen.push(next.courses[0]?.unreadNotices));
    const readsBefore = server.coursesReads;

    await markNoticeRead(1, 22);

    await vi.waitFor(() => expect(backendState().courses[0].unreadNotices).toBe(2));
    expect(seen).toContain(2);
    expect(server.coursesReads).toBe(readsBefore + 1);
    await vi.waitFor(() => expect(homeEntry()?.data?.courses?.[0].unreadNotices).toBe(2));
    expect(homeEntry()?.data?.notices?.[0].read).toBe(true);
    stop();
  });

  it("sends nothing again for a notice already marked in this session", async () => {
    const reads = server.coursesReads;
    expect(await markNoticeRead(1, 22)).toBeNull();
    expect(server.coursesReads).toBe(reads);
  });
});
