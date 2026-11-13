import { afterEach, describe, expect, it } from "vitest";
import { activities, seedState, type Activity, type Notice } from "../../src/data.ts";
import type { Screen } from "../../src/types.ts";
import {
  SOON_UNTIL, TODAY, TYPE_COLOR, activityById, activityHref, attendStatus, boardRows, dday, fmt, myDayGroups, openTodos, ownsRow, passFilter,
  playerHref, statusLabel, typeIcon, typeLabel,
} from "../../src/shared/format.ts";
import { activity } from "../helpers/fixtures.ts";

const day = (offset: number): string => new Date(Date.parse(`${TODAY}T00:00:00Z`) + offset * 86400000).toISOString().slice(0, 10);

describe("dday — 경계: 어제 / 오늘 / 내일", () => {
  it.each([
    [`${day(-1)}T23:59`, "마감"],
    [`${day(0)}T00:00`, "D-day"],
    [`${day(0)}T23:59`, "D-day"], // 시각은 무시하고 날짜만 비교
    [`${day(1)}`, "D-1"],
    [`${day(30)}T10:00`, "D-30"],
  ])("%s -> %s", (due, expected) => {
    expect(dday(due)).toBe(expected);
  });

  it.each([[null], [undefined], [""], ["not-a-date"], ["2026-13-45"]])("해석할 수 없으면 빈 문자열: %j", (due) => {
    expect(dday(due)).toBe("");
  });
});

describe("라벨 조회 — 알려진 값과 알 수 없는 값", () => {
  it("알려진 유형은 한국어 라벨/아이콘", () => {
    expect(typeLabel("report")).toBe("과제");
    expect(typeIcon("video")).toBe("play");
    expect(statusLabel("late")).toBe("지각");
  });

  it("알 수 없는 값은 원문(라벨/상태) 또는 기본 아이콘으로 대체", () => {
    expect(typeLabel("hologram")).toBe("hologram");
    expect(typeIcon("hologram")).toBe("file");
    expect(statusLabel("???")).toBe("???");
  });

  it("글(post)은 라벨은 있으나 아이콘이 없어 기본 아이콘을 쓴다", () => {
    expect(typeLabel("post")).toBe("글");
    expect(typeIcon("post")).toBe("file");
  });
});

describe("fmt", () => {
  it("값이 없으면 '—'", () => {
    expect(fmt(null)).toBe("—");
    expect(fmt(undefined)).toBe("—");
    expect(fmt("")).toBe("—");
  });

  it("날짜로 읽을 수 없으면 원문을 그대로 돌려준다", () => {
    expect(fmt("다음 주 월요일")).toBe("다음 주 월요일");
  });

  it("시간대가 없는 날짜시각은 지역 시각 그대로 표시한다", () => {
    expect(fmt("2026-11-12T19:00")).toBe("Nov 12, 07:00 PM");
  });
});

describe("attendStatus — 주차 경계(3=지각, 11=결석, 12까지 출석, 13부터 빈칸)", () => {
  const state = seedState();
  it.each([
    [1, "present"], [2, "present"], [3, "late"], [4, "present"], [10, "present"],
    [11, "absent"], [12, "present"], [13, "empty"], [14, "empty"],
  ])("%i주차 -> %s", (week, expected) => {
    expect(attendStatus(state, "민준", week)).toBe(expected);
  });

  it("저장된 값이 샘플 규칙보다 우선한다", () => {
    const saved = seedState();
    saved.attendance["민준:main:3"] = "excused";
    expect(attendStatus(saved, "민준", 3)).toBe("excused");
  });

  it("사람·분반이 다르면 다른 기록이다", () => {
    const saved = seedState();
    saved.attendance["민준:main:3"] = "excused";
    expect(attendStatus(saved, "서연", 3)).toBe("late");
    expect(attendStatus(saved, "민준", 3, "B")).toBe("late");
    saved.attendance["민준:B:3"] = "present";
    expect(attendStatus(saved, "민준", 3, "B")).toBe("present");
  });
});

describe("passFilter — 결정 테이블", () => {
  const state = () => seedState();
  const video = activity({ id: "a-video", courseId: "bi", type: "video", status: "done" });
  const report = activity({ id: "a-report", courseId: "bi", type: "report", status: "missing" });
  const survey = activity({ id: "a-survey", courseId: "bi", type: "survey", status: "missing" });
  const test = activity({ id: "a-test", courseId: "bi", type: "test", status: "scheduled" });

  it.each([[null], [undefined], [""], ["all"], ["unknown-filter"]])("필터 %j 는 모두 통과", (filter) => {
    expect(passFilter(video, filter, state())).toBe(true);
  });

  it("유형 필터는 해당 유형만 통과", () => {
    expect(passFilter(video, "video", state())).toBe(true);
    expect(passFilter(report, "video", state())).toBe(false);
    expect(passFilter(report, "report", state())).toBe(true);
    expect(passFilter(test, "test", state())).toBe(true);
    expect(passFilter(test, "material", state())).toBe(false);
  });

  it("ongoing 필터는 진행/열림/예정 상태", () => {
    for (const status of ["ongoing", "open", "scheduled"]) {
      expect(passFilter(activity({ id: "x", courseId: "bi", status }), "ongoing", state())).toBe(true);
    }
    for (const status of ["done", "missing", "expired", "submitted"]) {
      expect(passFilter(activity({ id: "x", courseId: "bi", status }), "ongoing", state())).toBe(false);
    }
  });

  it("missing 필터: 과제는 제출 기록이 없을 때만", () => {
    const s = state();
    expect(passFilter(report, "missing", s)).toBe(true);
    s.submissions["a-report"] = { who: "민준", title: "t", body: "b", at: "2026-11-12" };
    expect(passFilter(report, "missing", s)).toBe(false);
  });

  it("missing 필터: 설문은 done 응답이 없을 때만", () => {
    const s = state();
    expect(passFilter(survey, "missing", s)).toBe(true);
    s.surveys["a-survey"] = { done: false };
    expect(passFilter(survey, "missing", s)).toBe(true);
    s.surveys["a-survey"] = { done: true };
    expect(passFilter(survey, "missing", s)).toBe(false);
  });

  it("missing 필터: 그 밖의 유형은 상태가 missing 일 때만", () => {
    expect(passFilter(activity({ id: "m", courseId: "bi", type: "material", status: "missing" }), "missing", state())).toBe(true);
    expect(passFilter(activity({ id: "m", courseId: "bi", type: "material", status: "open" }), "missing", state())).toBe(false);
  });
});

describe("playerHref", () => {
  it("활동 ID와 과목 ID를 URL 인코딩한다", () => {
    expect(playerHref({ id: "a b&c", courseId: "x/y" })).toBe("/ilos/cls/st/activity/player_form.acl?n=a%20b%26c&kj=x%2Fy");
  });

  it("courseId 인자가 활동의 과목보다 우선한다", () => {
    expect(playerHref({ id: "v1", courseId: "bi" }, "ai")).toContain("kj=ai");
    expect(playerHref({ id: "v1", courseId: "bi" }, null)).toContain("kj=bi");
  });
});

describe("나의 하루 — 카드 숫자와 할 일 목록의 일관성(문서화된 불변식)", () => {
  const OPEN = ["missing", "ongoing", "scheduled"];

  it("SOON_UNTIL 은 오늘 + 3일(D-3)", () => {
    expect(SOON_UNTIL).toBe(day(3));
  });

  it("openTodos: 열린 상태만, 고정 항목이 맨 앞, 나머지는 마감 순(마감 없는 것은 맨 뒤)", () => {
    const todos = openTodos();
    expect(todos.every((item) => OPEN.includes(item.status))).toBe(true);
    expect(todos.slice(0, 4).map((item) => item.id)).toEqual(["lec-11-1", "lec-11-2", "rep-project", "quiz-final"]);
    const tail = todos.slice(4).map((item) => item.due || "9999");
    expect(tail).toEqual([...tail].sort());
    expect(new Set(todos.map((item) => item.id)).size).toBe(todos.length);
  });

  it("due: 가장 가까운 마감 날짜(오늘 이후)의 항목만 모은다", () => {
    const { due } = myDayGroups(seedState());
    expect(due.items.length).toBeGreaterThan(0);
    expect(due.items.every((item) => item.due === due.at)).toBe(true);
    expect(due.at.slice(0, 10) >= TODAY).toBe(true);
    for (const item of openTodos()) {
      if (item.due && item.due.slice(0, 10) >= TODAY) expect(item.due >= due.at).toBe(true);
    }
  });

  it("soon: 마감이 오늘~D-3 사이인 열린 항목 전부", () => {
    const { soon } = myDayGroups(seedState());
    const expected = openTodos().filter((item) => item.due && item.due.slice(0, 10) >= TODAY && item.due.slice(0, 10) <= SOON_UNTIL);
    expect(soon.items.map((item) => item.id).sort()).toEqual(expected.map((item) => item.id).sort());
    expect(soon.items.length).toBe(2); // 홈 히어로 문구 "마감이 얼마 남지 않은 일이 2개"
  });

  it("report: 제출 안 한 과제만, 마감 순. 제출하면 목록에서 빠진다", () => {
    const state = seedState();
    const before = myDayGroups(state).report.items;
    expect(before.every((item: Activity) => item.type === "report")).toBe(true);
    const dues = before.map((item) => item.due || "");
    expect(dues).toEqual([...dues].sort());

    state.submissions[before[0].id] = { who: "민준", title: "t", body: "b", at: TODAY };
    const after = myDayGroups(state).report.items;
    expect(after.map((item) => item.id)).toEqual(before.slice(1).map((item) => item.id));
  });

  it("state 가 없어도(null) 제출 기록 없음으로 계산한다", () => {
    expect(myDayGroups(null).report.items.length).toBe(myDayGroups(seedState()).report.items.length);
    expect(() => myDayGroups(undefined)).not.toThrow();
  });

  it("week: 학기 11주차(8/31 시작, 11/12 = 73일째)의 영상·자료만", () => {
    const { week } = myDayGroups(seedState());
    expect(week.week).toBe(11);
    expect(week.items.length).toBeGreaterThan(0);
    expect(week.items.every((item) => item.week === 11 && (item.type === "video" || item.type === "material"))).toBe(true);
  });
});

describe("activityById / ownsRow", () => {
  it("활동 ID 로 찾고, 없거나 비어 있으면 undefined", () => {
    expect(activityById("lec-11-1")?.id).toBe("lec-11-1");
    expect(activityById("nope")).toBeUndefined();
    expect(activityById(null)).toBeUndefined();
    expect(activityById(undefined)).toBeUndefined();
  });

  it("글쓴이만 자기 글의 주인: 학생은 샘플 학생 이름, 교수는 샘플 교수 이름", () => {
    const student = seedState();
    const professor = { ...seedState(), role: "professor" as const };
    expect(ownsRow({ id: "1", title: "t", author: "박민준" }, student)).toBe(true);
    expect(ownsRow({ id: "1", title: "t", author: "박은주" }, student)).toBe(false);
    expect(ownsRow({ id: "1", title: "t", author: "박은주" }, professor)).toBe(true);
    expect(ownsRow({ id: "1", title: "t", author: "박민준" }, professor)).toBe(false);
  });

  it("글쓴이가 없는 글은 아무도 주인이 아니다", () => {
    expect(ownsRow({ id: "1", title: "t" }, seedState())).toBe(false);
    expect(ownsRow({ id: "1", title: "t", author: "" }, seedState())).toBe(false);
  });
});

describe("boardRows — 게시판 종류별", () => {
  const screen = (patch: Partial<Screen>): Screen => ({ id: "t", role: "student", title: "t", path: "/", nav: "x", kind: "board", ...patch });
  const extra = (patch: Partial<Notice> & Pick<Notice, "id" | "scope">): Notice => ({ title: "t", date: "2026-11-12", author: "박은주", body: "b", audience: "all", ...patch });

  it("전체 공지: 전체 범위 공지만, 과목 공지는 섞이지 않는다", () => {
    const state = seedState();
    state.noticesExtra = [extra({ id: "course-notice", scope: "bi" })]; // 교수가 과목 공지를 쓰면 이렇게 저장된다
    const rows = boardRows(screen({ board: "global" }), state);
    expect(rows.length).toBeGreaterThan(0);
    expect(rows.map((row) => row.id)).not.toContain("course-notice");
    expect(rows.every((row) => row.id.startsWith("n"))).toBe(true);
  });

  it("전체 공지: audience 필터는 해당 대상 + 전체(all) 대상만", () => {
    const state = seedState();
    const rows = boardRows(screen({ board: "global", audience: "student" }), state);
    expect(rows.map((row) => row.audience)).toEqual(expect.arrayContaining(["student"]));
    expect(rows.every((row) => row.audience === "student" || row.audience === "all")).toBe(true);
    expect(rows.map((row) => row.id)).not.toContain("n3"); // 교수 대상 공지
  });

  it("과목 공지: 그 과목의 샘플 공지 + 저장한 공지, 다른 과목은 제외", () => {
    const state = seedState();
    state.noticesExtra = [extra({ id: "mine", scope: "bi" }), extra({ id: "other", scope: "ai" })];
    const ids = boardRows(screen({ board: "course", course: "bi" }), state).map((row) => row.id);
    expect(ids).toEqual(expect.arrayContaining(["cn1", "cn2", "mine"]));
    expect(ids).not.toContain("other");
    expect(ids).not.toContain("cn-ai-1");
  });

  it("과목이 지정되지 않으면 bi 과목으로 본다", () => {
    const state = seedState();
    expect(boardRows(screen({ board: "course" }), state).map((row) => row.id)).toContain("cn1");
  });

  it("과목 Q&A/자유게시판: 저장한 글이 앞, 그 게시판·그 과목의 글만, courseId 없는 옛 글은 bi", () => {
    const state = seedState();
    state.posts = [
      { id: "saved", board: "course-qna", courseId: "ai", title: "t" },
      { id: "legacy", board: "course-qna", title: "t" }, // courseId 없음 -> bi
      { id: "other-board", board: "open", courseId: "ai", title: "t" },
    ];
    const ai = boardRows(screen({ board: "course-qna", course: "ai" }), state).map((row) => row.id);
    expect(ai[0]).toBe("saved");
    expect(ai).not.toContain("other-board");
    expect(ai).not.toContain("legacy");
    expect(ai).toContain("q-ai-1");
    const bi = boardRows(screen({ board: "course-qna", course: "bi" }), state).map((row) => row.id);
    expect(bi).toContain("legacy");
    expect(bi).toEqual(expect.arrayContaining(["seed-q"]));
  });

  it("커뮤니티 Q&A 는 저장된 질문 목록 그대로", () => {
    const state = seedState();
    state.communityQna = [{ id: "q1", title: "질문" }];
    expect(boardRows(screen({ board: "qna" }), state)).toEqual([{ id: "q1", title: "질문" }]);
  });

  it("자료실은 비어 있고, 설문은 샘플 한 건, 모르는 게시판은 빈 목록", () => {
    const state = seedState();
    expect(boardRows(screen({ board: "material" }), state)).toEqual([]);
    expect(boardRows(screen({ board: "survey" }), state).map((row) => row.id)).toEqual(["campus"]);
    expect(boardRows(screen({ board: "zzz" }), state)).toEqual([]);
    expect(boardRows(screen({}), state)).toEqual([]);
  });
});

describe("표시용 표(색·라벨·아이콘)", () => {
  const TYPES = ["video", "zoom", "material", "report", "test", "survey", "post"] as const;

  it("활동 종류마다 색 변수가 하나씩 있고 서로 다르다", () => {
    const colors = TYPES.map((type) => TYPE_COLOR[type]);
    expect(colors.every((color) => /^var\(--[a-z-]+\)$/.test(color))).toBe(true);
    expect(new Set(colors).size).toBe(TYPES.length);
  });

  it("종류별 한국어 라벨", () => {
    expect(TYPES.map((type) => typeLabel(type))).toEqual(["영상", "Zoom", "자료", "과제", "시험", "설문", "글"]);
  });

  it("종류별 아이콘(글 제외)은 서로 다른 이름", () => {
    const icons = TYPES.filter((type) => type !== "post").map((type) => typeIcon(type));
    expect(icons).toEqual(["play", "users", "file", "pen", "check", "chat"]);
  });

  it("출석 상태별 라벨", () => {
    expect(["present", "late", "absent", "excused", "empty"].map(statusLabel)).toEqual(["출석", "지각", "결석", "유고", "—"]);
  });
});

describe("passFilter — 필터마다 다른 종류는 걸러낸다", () => {
  const video = activity({ id: "a", courseId: "bi", type: "video", status: "done" });
  it.each(["material", "report", "test"])("%s 필터는 영상을 통과시키지 않는다", (filter) => {
    expect(passFilter(video, filter, seedState())).toBe(false);
  });

  it("all 과 빈 필터는 아무 활동이나 통과", () => {
    expect(passFilter(video, "all", seedState())).toBe(true);
  });
});

describe("activityHref", () => {
  afterEach(() => history.replaceState(null, "", "/"));

  it("학생 영역에서는 학생 화면 주소, 과목 인자가 활동의 과목보다 우선", () => {
    history.replaceState(null, "", "/");
    const item = activityById("lec-11-1") as Activity;
    expect(activityHref(item)).toBe("/ilos/cls/st/activity/player_form.acl?n=lec-11-1&kj=ai");
    expect(activityHref(item, "bi")).toBe("/ilos/cls/st/activity/player_form.acl?n=lec-11-1&kj=bi");
  });

  it("교수 영역(?as=pf)에서는 교수 화면 주소", () => {
    history.replaceState(null, "", "/?as=pf");
    expect(activityHref(activityById("lec-11-1") as Activity)).toBe("/ilos/cls/pf/activity/lecture_form.acl?kj=ai&n=lec-11-1&as=pf");
  });
});

describe("나의 하루 — 마감 날짜 경계(샘플 데이터에 없는 날짜를 주입)", () => {
  const original = activities.map((item) => ({ ...item }));
  afterEach(() => {
    activities.splice(0, activities.length, ...original.map((item) => ({ ...item })));
  });
  const inject = (id: string, due: string, patch: Partial<Activity> = {}) =>
    activities.push(activity({ id, courseId: "bi", type: "report", status: "missing", due, ...patch }));

  it("soon: 오늘(D-0)과 D-3 는 포함, 어제와 D-4 는 제외", () => {
    inject("y", `${day(-1)}T23:59`);
    inject("d0", `${day(0)}T00:00`);
    inject("d3", `${day(3)}T23:59`);
    inject("d4", `${day(4)}T00:00`);
    const ids = myDayGroups(seedState()).soon.items.map((item) => item.id);
    expect(ids).toEqual(expect.arrayContaining(["d0", "d3"]));
    expect(ids).not.toContain("y");
    expect(ids).not.toContain("d4");
  });

  it("due: 오늘 마감도 '가장 가까운 마감' 후보이고, 가장 이른 시각의 항목만 묶는다", () => {
    inject("early", `${day(0)}T08:00`);
    inject("same", `${day(0)}T08:00`);
    inject("later", `${day(0)}T09:00`);
    const { due } = myDayGroups(seedState());
    expect(due.at).toBe(`${day(0)}T08:00`);
    expect(due.items.map((item) => item.id).sort()).toEqual(["early", "same"]);
  });

  it("due: 오늘 이전에 마감된 열린 활동은 후보가 아니다", () => {
    inject("past", `${day(-5)}T08:00`);
    expect(myDayGroups(seedState()).due.items.map((item) => item.id)).not.toContain("past");
  });

  it("마감 없는 열린 활동은 soon·due 에 들어가지 않지만 openTodos 에는 있다", () => {
    inject("nodue", "", { due: undefined });
    expect(myDayGroups(seedState()).soon.items.map((item) => item.id)).not.toContain("nodue");
    expect(openTodos().map((item) => item.id)).toContain("nodue");
  });

  it("openTodos: 고정 항목이 이미 끝났으면 목록에서 빠지고 빈 칸이 생기지 않는다", () => {
    const pinned = activities.find((item) => item.id === "rep-project") as Activity;
    pinned.status = "submitted";
    const todos = openTodos();
    expect(todos.every(Boolean)).toBe(true);
    expect(todos.map((item) => item.id)).not.toContain("rep-project");
    expect(todos.slice(0, 3).map((item) => item.id)).toEqual(["lec-11-1", "lec-11-2", "quiz-final"]);
  });

  it("레이블: 사용자에게 보이는 카드 이름", () => {
    const groups = myDayGroups(seedState());
    expect([groups.due.label, groups.soon.label, groups.report.label, groups.week.label]).toEqual([
      "가장 가까운 마감", "사흘 안 마감", "제출할 과제", "이번 주 학습 자료",
    ]);
  });
});

describe("boardRows — 남은 분기", () => {
  const screen = (patch: Partial<Screen>): Screen => ({ id: "t", role: "student", title: "t", path: "/", nav: "x", kind: "board", ...patch });

  it("전체 공지 + 교수 대상: 교수용과 전체용만", () => {
    const ids = boardRows(screen({ board: "global", audience: "professor" }), seedState()).map((row) => row.id);
    expect(ids).toContain("n3");
    expect(ids).toContain("n1");
    expect(ids).not.toContain("n2");
  });

  it("자유게시판(open)은 그 과목의 샘플 글만", () => {
    const ids = boardRows(screen({ board: "open", course: "ai" }), seedState()).map((row) => row.id);
    expect(ids).toEqual(["o-ai-1"]);
  });

  it("과목 Q&A 는 다른 과목의 샘플 글을 섞지 않는다", () => {
    const ids = boardRows(screen({ board: "course-qna", course: "ai" }), seedState()).map((row) => row.id);
    expect(ids).toEqual(["q-ai-1", "q-ai-2"]);
  });

  it("설문 게시판의 샘플 한 건", () => {
    expect(boardRows(screen({ board: "survey" }), seedState())).toEqual([
      { id: "campus", title: "캠퍼스 학습 환경 설문", at: "2026-11-01", body: "샘플 설문입니다." },
    ]);
  });
});
