import { describe, expect, it } from "vitest";
import { allowsPlan, type PlanHistoryTurn } from "../../src/shared/assistant/aliases.ts";

const plan = (text: string): PlanHistoryTurn => ({ text, result: { kind: "plan" } });
const go = (text: string): PlanHistoryTurn => ({ text, result: { kind: "go" } });

// 결정 테이블: (명시적 요청?) (확인 응답?) (직전 대화가 plan/clarify 이고 그 근원이 명시적 요청?)
describe("allowsPlan — 명시적 요청", () => {
  it.each([
    ["시작", true],
    ["시작해", true],
    ["시작하자", true],
    ["오늘 시작해", true],
    ["시작!", true], // 구두점 무시
    ["전부 해줘", true],
    ["남은거 다해", true],
    ["오늘 계획", true],
    ["시작합니다", false], // 정확히 일치하는 시작 표현만 허용
    ["강의 틀어줘", false],
    ["", false],
  ])("%s -> %s", (text, expected) => {
    expect(allowsPlan(text)).toBe(expected);
  });
});

describe("allowsPlan — 확인 응답은 직전 맥락이 있어야 한다", () => {
  it("이력이 없으면 '응'만으로는 허용하지 않는다", () => {
    expect(allowsPlan("응")).toBe(false);
  });

  it("직전이 명시적 계획 요청이면 확인 응답을 허용한다", () => {
    expect(allowsPlan("응", [plan("전부 해줘")])).toBe(true);
    expect(allowsPlan("응?", [plan("전부 해줘")])).toBe(true);
  });

  it("직전 결과가 plan/clarify 가 아니면 거부한다(기존 계획은 새 요청의 허락이 아님)", () => {
    expect(allowsPlan("응", [go("전부 해줘")])).toBe(false);
  });

  it("직전 결과가 plan 이어도 그 문장이 명시적 요청이 아니면 거부한다", () => {
    expect(allowsPlan("응", [plan("뭐 있어?")])).toBe(false);
  });

  it("확인 응답이 연속돼도 근원이 명시적 요청이면 거슬러 올라가 허용한다", () => {
    expect(allowsPlan("그래", [plan("전부 해줘"), plan("응")])).toBe(true);
  });

  it("연쇄 중간에 plan/clarify 가 아닌 턴이 끼면 끊긴다", () => {
    expect(allowsPlan("그래", [plan("전부 해줘"), go("응")])).toBe(false);
  });

  it("clarify 결과도 맥락으로 인정한다", () => {
    expect(allowsPlan("응", [{ text: "시작", result: { kind: "clarify" } }])).toBe(true);
  });

  it("결과가 없는 턴은 맥락이 아니다", () => {
    expect(allowsPlan("응", [{ text: "전부 해줘" }])).toBe(false);
  });

  it("확인 응답 목록 밖의 말은 이력과 무관하게 거부한다", () => {
    expect(allowsPlan("글쎄", [plan("전부 해줘")])).toBe(false);
  });
});

describe("allowsPlan — 문장 경계(앞뒤에 다른 말이 붙으면 아니다)", () => {
  it("'시작해' 는 문장 끝이 맞아야 한다", () => {
    expect(allowsPlan("방금 시작해")).toBe(false);
    expect(allowsPlan("지금 시작하자")).toBe(false);
  });

  it("확인 응답은 문장 전체가 그 말이어야 한다", () => {
    expect(allowsPlan("응답해줘", [plan("전부 해줘")])).toBe(false);
    expect(allowsPlan("아니 그래", [plan("전부 해줘")])).toBe(false);
  });

  it("거슬러 올라가다 확인 응답도 명시적 요청도 아닌 턴을 만나면 더 이상 보지 않는다", () => {
    expect(allowsPlan("응", [plan("전부 해줘"), plan("뭐 있어?")])).toBe(false);
  });
});
