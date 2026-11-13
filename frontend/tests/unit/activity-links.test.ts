import { describe, expect, it } from "vitest";
import {
  activityLinkOf,
  materialHref,
  serverActivityHref,
  targetIdOf,
} from "../../src/shared/activity-links.ts";

const parse = (href: string | null) => {
  expect(href).not.toBeNull();
  const url = new URL(href as string, "http://localhost");
  return { path: url.pathname, query: Object.fromEntries(url.searchParams) };
};
const ACTIVITY_PATH = "/ilos/cls/st/activity/activity_form.acl";

describe("targetIdOf — ID 경계값(1~16자리 양의 정수)", () => {
  it.each([
    ["1", 1],
    [7, 7],
    ["1234567890123456", 1234567890123456], // 16자리(안전 정수 범위 안): 허용 최대 자릿수
    ["10000000000000000", null], // 17자리
    ["0", null],
    ["01", null], // 앞자리 0
    ["-1", null],
    ["1.5", null],
    ["abc", null],
    ["", null],
    [null, null],
  ])("quizId=%j -> %j", (id, expected) => {
    expect(targetIdOf("test", { quizId: id })).toBe(expected);
  });

  it("유형마다 자기 필드만 읽는다", () => {
    const row = { assignmentId: 1, quizId: 2, surveyId: 3 };
    expect(targetIdOf("report", row)).toBe(1);
    expect(targetIdOf("test", row)).toBe(2);
    expect(targetIdOf("survey", row)).toBe(3);
  });

  it("평가 유형이 아니거나 행이 없으면 null", () => {
    expect(targetIdOf("lecture_weeks", { assignmentId: 1 })).toBeNull();
    expect(targetIdOf("report", null)).toBeNull();
    expect(targetIdOf("report", undefined)).toBeNull();
  });
});

describe("serverActivityHref", () => {
  it("영상은 활동 ID를 srv- 접두로 쓰는 lecture_weeks 상세", () => {
    expect(parse(serverActivityHref("c3", "lecture_weeks", 5))).toEqual({
      path: ACTIVITY_PATH, query: { m: "lecture_weeks", n: "srv-5", kj: "c3" },
    });
  });

  it("자료는 materialHref 와 같은 주소", () => {
    expect(serverActivityHref("c3", "lecture_material", 5)).toBe(materialHref("c3", 5));
    expect(parse(materialHref(3, 5)).query).toEqual({ m: "lecture_material", n: "srv-5", kj: "3" });
  });

  it("과제·시험·설문은 행이 ID를 주면 그 화면으로 바로 간다", () => {
    expect(parse(serverActivityHref("c3", "report", 5, { assignmentId: 7 })).query).toEqual({ m: "report", n: "srv-7", kj: "c3" });
    expect(parse(serverActivityHref("c3", "test", 5, { quizId: 8 })).query).toEqual({ m: "test", n: "srv-8", kj: "c3" });
    expect(parse(serverActivityHref("c3", "survey", 5, { surveyId: 9 })).query).toEqual({ m: "survey", n: "srv-9", kj: "c3" });
  });

  it("행이 없거나 ID가 유효하지 않으면 연결 화면(act=)으로 간다", () => {
    expect(parse(serverActivityHref("c3", "report", 5)).query).toEqual({ m: "report", act: "5", kj: "c3" });
    expect(parse(serverActivityHref("c3", "report", 5, { assignmentId: 0 })).query).toEqual({ m: "report", act: "5", kj: "c3" });
    // 다른 유형의 ID 필드는 쓰지 않는다
    expect(parse(serverActivityHref("c3", "report", 5, { quizId: 8 })).query).toEqual({ m: "report", act: "5", kj: "c3" });
  });

  it.each([
    ["과목 없음(null)", null, "report", 5],
    ["과목 없음(빈 문자열)", "", "report", 5],
    ["활동 ID 0", "c3", "report", 0],
    ["활동 ID 17자리", "c3", "report", "10000000000000000"],
    ["볼 화면이 없는 유형", "c3", "zoom", 5],
    ["알 수 없는 유형", "c3", "nope", 5],
  ])("null: %s", (_name, kj, type, id) => {
    expect(serverActivityHref(kj, type, id)).toBeNull();
  });

  it("kj 의 특수문자는 URL 인코딩된다", () => {
    expect(parse(serverActivityHref("a b&c", "lecture_weeks", 1)).query.kj).toBe("a b&c");
  });
});

describe("activityLinkOf", () => {
  it("연결 화면 주소에서 { type, activityId } 를 읽는다", () => {
    expect(activityLinkOf(new URLSearchParams("m=report&act=12"))).toEqual({ type: "report", activityId: 12 });
  });

  it.each([
    ["m=lecture_weeks&act=1", "평가 유형이 아님"],
    ["m=report", "act 없음"],
    ["m=report&act=0", "act 0"],
    ["m=report&act=abc", "act 숫자 아님"],
    ["act=3", "m 없음"],
  ])("null: %s (%s)", (query) => {
    expect(activityLinkOf(new URLSearchParams(query))).toBeNull();
  });

  it("왕복 속성: 연결 화면 주소를 만들고 다시 읽으면 원래 값이 나온다", () => {
    for (const type of ["report", "test", "survey"] as const) {
      const href = serverActivityHref("c1", type, 42);
      const { query } = parse(href);
      expect(activityLinkOf(new URLSearchParams(query))).toEqual({ type, activityId: 42 });
    }
  });
});
