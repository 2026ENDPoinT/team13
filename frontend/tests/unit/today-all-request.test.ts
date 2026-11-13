import { describe, expect, it } from "vitest";
import { isTodayAllRequest } from "../../src/shared/assistant/aliases.ts";
import { todayDeadlinePlan } from "../../src/shared/assistant/plan.ts";

describe("isTodayAllRequest — 목업의 '오늘 할 일 전부' 요청", () => {
  it.each([
    ["오늘 할 일 전부 해줘", true],
    ["오늘 할일 다 해줘!", true],
    ["오늘 할 일 모두 해", true],
    ["할 일 전부", true],
    ["데베 강의 전부 해줘", false], // 과목·유형이 좁히면 AI/로컬 해석기에 맡긴다
    ["오늘 과제 전부 해줘", false],
    ["전부 해줘", false],
    ["오늘 할 일 보여줘", false],
  ])("%s -> %s", (text, expected) => {
    expect(isTodayAllRequest(text)).toBe(expected);
  });
});

describe("todayDeadlinePlan — 샘플 데이터", () => {
  it("사흘 안 마감 2건을 기계학습 영상 → 고급웹 과제 순으로 쌓는다", () => {
    expect(todayDeadlinePlan()).toEqual(["lec-11-1", "rep-web"]);
  });
});
