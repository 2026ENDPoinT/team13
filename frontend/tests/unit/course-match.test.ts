import { describe, expect, it } from "vitest";
import { abbreviationMatch, subsequence } from "../../src/shared/assistant/course-match.ts";

const web = { ko: "고급웹프로그래밍", en: "Advanced Web Programming" };

describe("subsequence", () => {
  it.each([
    ["빈 문자열은 항상 부분열", "", "abc", true],
    ["전체 일치", "abc", "abc", true],
    ["글자를 건너뛰어도 순서가 같으면 일치", "ac", "abc", true],
    ["순서가 바뀌면 불일치", "ca", "abc", false],
    ["이름에 없는 글자가 있으면 불일치", "abz", "abc", false],
    ["검색어가 이름보다 길면 불일치(경계: 길이+1)", "abcd", "abc", false],
    ["같은 글자를 이름보다 많이 요구하면 불일치", "aa", "abc", false],
  ])("%s", (_name, text, name, expected) => {
    expect(subsequence(text, name)).toBe(expected);
  });
});

describe("abbreviationMatch — 한글 음절 생략", () => {
  it.each([
    ["고웹프", true],
    ["웹프", true],
    ["고급웹프로그래밍", true],
    ["프웹", false], // 순서 뒤바뀜
    ["고웹프라", false], // 이름에 없는 음절
    ["웹", false], // 길이 1 미만 경계
    ["", false],
  ])("%s -> %s", (query, expected) => {
    expect(abbreviationMatch(query, web)).toBe(expected);
  });

  it("공백과 영문 섞임은 무시하고 한글만으로 비교한다", () => {
    expect(abbreviationMatch("고 웹 프", web)).toBe(true);
    expect(abbreviationMatch("고웹프1", web)).toBe(true);
  });
});

describe("abbreviationMatch — 초성", () => {
  it.each([
    ["ㄱㅇㅍ", true], // 고(ㄱ) 웹(ㅇ) 프(ㅍ)
    ["ㄱㄱㅇㅍㄹㄱㄹㅁ", true], // 전체 초성
    ["ㅍㅇ", false], // 순서 뒤바뀜
    ["ㄱ", false], // 길이 1
    ["ㅋㅋ", false],
  ])("%s -> %s", (query, expected) => {
    expect(abbreviationMatch(query, web)).toBe(expected);
  });
});

describe("abbreviationMatch — 영문", () => {
  it.each([
    ["awp", true], // 단어 첫 글자
    ["AWP", true], // 대소문자 무시
    ["adv", true], // 접두
    ["web prog", true], // 공백 무시한 부분 문자열
    ["wp", true], // 단어 첫 글자열 "awp" 의 부분 문자열
    ["pw", false], // 순서가 뒤바뀐 약어
    ["xyz", false],
    ["w", false], // 길이 1
  ])("%s -> %s", (query, expected) => {
    expect(abbreviationMatch(query, web)).toBe(expected);
  });

  it("숫자가 섞인 영문 검색어는 약어로 보지 않는다", () => {
    expect(abbreviationMatch("aw1", web)).toBe(false);
  });
});

describe("abbreviationMatch — 한글 아닌 글자가 섞인 과목명", () => {
  const ux = { ko: "UX 디자인", en: "UX Design" };
  it("초성 검색은 한글 아닌 글자를 건너뛰고 맞춘다", () => {
    expect(abbreviationMatch("ㄷㅈㅇ", ux)).toBe(true);
    expect(abbreviationMatch("ㅇㅌㅇ", ux)).toBe(false);
  });

  it("한글 검색은 이름의 한글 부분만 본다", () => {
    expect(abbreviationMatch("디인", ux)).toBe(true);
    expect(abbreviationMatch("인디", ux)).toBe(false);
  });
});
