import { describe, expect, it } from "vitest";
import { resolve } from "../../src/shared/assistant/resolve.ts";
import { buildPlan, orderPlan } from "../../src/shared/assistant/plan.ts";
import type { AssistantCtx } from "../../src/shared/assistant/activity-context.ts";
import { activity, course } from "../helpers/fixtures.ts";

// 샘플 데이터(data.ts)에 기대지 않도록 오늘 날짜·과목·활동을 직접 주입한다.
const TODAY = "2026-11-12";
const ctx = (overrides: Partial<AssistantCtx> = {}): AssistantCtx => ({
  today: TODAY,
  positions: {},
  courses: [
    course({ id: "bi", ko: "데이터베이스", en: "Database", code: "DB-1" }),
    course({ id: "algo", ko: "고급웹프로그래밍", en: "Advanced Web Programming" }),
    course({ id: "ai", ko: "기계학습", en: "Machine Learning" }),
    course({ id: "web1", ko: "웹프로그래밍", en: "Web Programming", closed: true }), // 지난 학기: 제외
    course({ id: "ux", ko: "UX 디자인", en: "UX Design", enrolled: false }), // 수강 안 함: 제외
  ],
  activities: [
    activity({ id: "v-bi", courseId: "bi", week: 11, due: "2026-11-13T23:59" }),
    activity({ id: "v-ai-1", courseId: "ai", week: 11, due: "2026-11-12T23:59" }),
    activity({ id: "v-ai-2", courseId: "ai", week: 10, due: "2026-11-14T23:59" }),
    activity({ id: "v-old", courseId: "bi", week: 9, due: "2026-11-10T23:59" }), // 마감 지남: 오늘 강의 아님
    activity({ id: "r-algo", courseId: "algo", week: 11, type: "report", status: "missing", due: "2026-11-13T23:59" }),
    activity({ id: "s-bi", courseId: "bi", week: 10, type: "survey", status: "missing", due: "2026-11-20T23:59" }),
    activity({ id: "t-ai", courseId: "ai", week: 12, type: "test", status: "scheduled", due: "2026-11-14T10:00" }),
    activity({ id: "v-done", courseId: "ai", week: 8, status: "done" }),
    activity({ id: "m-ai", courseId: "ai", week: 9, type: "material", status: "open" }),
    activity({ id: "m-done", courseId: "ai", week: 7, type: "material", status: "done" }),
    activity({ id: "z-bi", courseId: "bi", week: 9, type: "zoom", status: "ongoing" }),
  ],
  ...overrides,
});

describe("resolve — 과목 지정", () => {
  it("과목 이름만 말하면 그 강의실로 이동(go, 대상 없음)", () => {
    const result = resolve("데이터베이스", ctx());
    expect(result).toMatchObject({ kind: "go", intent: "course", courseId: "bi", targets: [], say: "데이터베이스 강의실로 이동" });
    expect(result.matched?.course).toEqual({ text: "데이터베이스", name: "데이터베이스" });
  });

  it("한글 줄임말(고웹프)·초성(ㄱㄱㅇ…)·영문 약어로 과목을 찾는다", () => {
    expect(resolve("고웹프", ctx()).courseId).toBe("algo");
    expect(resolve("ㄱㅇㅍ", ctx()).courseId).toBe("algo");
    expect(resolve("awp", ctx()).courseId).toBe("algo");
  });

  it("등록된 별칭(DB)은 대소문자와 상관없이 과목을 찾는다", () => {
    expect(resolve("DB 영상", ctx())).toMatchObject({ courseId: "bi", intent: "video" });
    expect(resolve("디비", ctx()).courseId).toBe("bi");
  });

  it("지난 학기 과목·수강하지 않는 과목은 찾지 못한다", () => {
    expect(resolve("UX 디자인", ctx()).kind).toBe("none");
    // 닫힌 '웹프로그래밍'은 제외되지만 '고급웹프로그래밍'의 줄임말로는 읽힌다
    expect(resolve("웹프로그래밍", ctx()).courseId).toBe("algo");
  });

  it("같은 점수로 여러 과목이 맞으면 과목을 하나로 정하지 않고 활동을 고르게 한다", () => {
    const both = ctx({
      courses: [
        course({ id: "se", ko: "사이버보안", en: "Cyber Security" }),
        course({ id: "secu", ko: "컴퓨터보안", en: "Computer Security" }),
      ],
      activities: [
        activity({ id: "se-v", courseId: "se", due: "2026-11-13T00:00" }),
        activity({ id: "secu-v", courseId: "secu", due: "2026-11-14T00:00" }),
      ],
    });
    const result = resolve("보안", both);
    expect(result.courseId).toBeNull();
    expect(result).toMatchObject({ kind: "choose", intent: "course", targets: ["se-v", "secu-v"] });
  });
});

describe("resolve — 활동 종류와 대상", () => {
  it("오늘 들을 강의: 마감이 지나지 않은 진행 중 영상을 마감 순으로", () => {
    const result = resolve("오늘 들을 강의", ctx());
    expect(result).toMatchObject({ kind: "choose", intent: "video", courseId: null, say: "오늘 들을 강의 3개" });
    expect(result.targets).toEqual(["v-ai-1", "v-bi", "v-ai-2"]);
  });

  it("과목 + 종류: 그 과목의 해당 활동만. 하나면 바로 이동(go)", () => {
    const result = resolve("데이터베이스 강의 틀어줘", ctx());
    expect(result).toMatchObject({ kind: "go", intent: "video", courseId: "bi", targets: ["v-bi"] });
    expect(result.say).toBe("데이터베이스 11주차 title-v-bi 재생");
  });

  it("과목 + 종류가 여럿이면 고르게 한다(choose)", () => {
    const result = resolve("기계학습 강의", ctx());
    expect(result).toMatchObject({ kind: "choose", targets: ["v-ai-1", "v-ai-2"], say: "기계학습 영상 2개" });
  });

  it("'N주' 만 말해도 영상으로 읽고 그 주차만 찾는다", () => {
    const result = resolve("10주차", ctx());
    expect(result.targets).toEqual(["v-ai-2"]);
    expect(result.matched?.intent?.text).toBe("10주");
  });

  it("과제는 제출 화면으로, 문구에 과제 이름이 들어간다", () => {
    const result = resolve("과제 제출", ctx());
    expect(result).toMatchObject({ kind: "go", intent: "report", targets: ["r-algo"] });
    expect(result.say).toBe("고급웹프로그래밍 title-r-algo 제출 화면으로 이동");
  });

  it("설문·시험도 각자 유형으로만 찾는다", () => {
    expect(resolve("설문", ctx()).targets).toEqual(["s-bi"]);
    expect(resolve("시험", ctx()).targets).toEqual(["t-ai"]);
  });

  it("끝난 활동(done)과 마감 지난 영상은 대상이 아니다", () => {
    const targets = resolve("기계학습 강의", ctx()).targets;
    expect(targets).not.toContain("v-done");
    expect(resolve("데이터베이스 강의", ctx()).targets).not.toContain("v-old");
  });

  it("이해 못 하는 말은 none 과 안내 문구", () => {
    const result = resolve("asdfgh", ctx());
    expect(result).toMatchObject({ kind: "none", courseId: null, targets: [], say: "'asdfgh'와 맞는 항목이 없습니다" });
  });

  it("종류는 알겠는데 대상이 하나도 없으면 none", () => {
    expect(resolve("기계학습 과제", ctx()).kind).toBe("none");
  });

  it("입력 ctx 를 바꾸지 않는다", () => {
    const input = ctx();
    const snapshot = JSON.stringify(input);
    resolve("오늘 들을 강의", input);
    resolve("전부 해줘", input);
    expect(JSON.stringify(input)).toBe(snapshot);
  });
});

describe("resolve — 오늘 계획(plan)은 명시적 요청에만", () => {
  it("'전부 해줘' 는 계획: 영상 → 과제 순, 같은 묶음은 마감 순, 시험은 제외", () => {
    const result = resolve("전부 해줘", ctx());
    expect(result).toMatchObject({ kind: "plan", intent: "plan", say: "오늘 계획 4단계" });
    expect(result.targets).toEqual(["v-ai-1", "v-bi", "v-ai-2", "r-algo"]);
  });

  it("과목을 함께 말하면 그 과목으로 좁힌다", () => {
    const result = resolve("기계학습 전부", ctx());
    expect(result).toMatchObject({ kind: "plan", courseId: "ai", say: "오늘 계획 2단계" });
    expect(result.targets).toEqual(["v-ai-1", "v-ai-2"]);
  });

  it("활동 종류를 함께 말하면 그 종류로 좁힌다", () => {
    expect(resolve("과제 전부", ctx()).targets).toEqual(["r-algo"]);
  });

  it("'시작' 한 마디도 계획 요청이다", () => {
    expect(resolve("시작", ctx()).kind).toBe("plan");
  });

  it("계획에 넣을 일이 없으면 none", () => {
    const result = resolve("전부 해줘", ctx({ activities: [] }));
    expect(result).toMatchObject({ kind: "none", intent: "plan", targets: [], say: "오늘 계획에 넣을 일이 없습니다" });
  });

  it("'시작합니다' 처럼 명시적이지 않은 말은 계획으로 읽지 않는다", () => {
    expect(resolve("시작합니다", ctx()).kind).not.toBe("plan");
  });
});

describe("buildPlan / orderPlan", () => {
  it("D-3 경계: 오늘+3일(11/15) 마감은 포함하고 +4일은 제외한다", () => {
    const edge = ctx({
      activities: [
        activity({ id: "in", courseId: "bi", type: "report", status: "missing", due: "2026-11-15T23:59" }),
        activity({ id: "out", courseId: "bi", type: "report", status: "missing", due: "2026-11-16T00:00" }),
        activity({ id: "yesterday", courseId: "bi", type: "report", status: "missing", due: "2026-11-11T23:59" }),
      ],
    });
    expect(buildPlan(edge)).toEqual(["in"]);
  });

  it("오늘 강의와 곧 마감인 영상이 겹쳐도 한 번만 넣는다", () => {
    const plan = buildPlan(ctx());
    expect(new Set(plan).size).toBe(plan.length);
  });

  it("orderPlan: 영상 → 과제 → 기타, 같은 묶음은 마감 순(마감 없는 것은 뒤)", () => {
    const list = [
      activity({ id: "s", courseId: "bi", type: "survey", due: "2026-11-12T00:00" }),
      activity({ id: "r2", courseId: "bi", type: "report", due: "2026-11-14T00:00" }),
      activity({ id: "v-none", courseId: "bi", type: "video" }),
      activity({ id: "r1", courseId: "bi", type: "report", due: "2026-11-13T00:00" }),
      activity({ id: "v2", courseId: "bi", type: "video", due: "2026-11-13T00:00" }),
      activity({ id: "v1", courseId: "bi", type: "video", due: "2026-11-12T00:00" }),
    ];
    expect(orderPlan(["s", "r2", "v-none", "r1", "v2", "v1"], list)).toEqual(["v1", "v2", "v-none", "r1", "r2", "s"]);
  });
});

describe("resolve — 사전(별칭·키워드)과 문장 정리", () => {
  it("과목 별칭 '머신러닝' 은 기계학습 과목", () => {
    expect(resolve("머신러닝", ctx()).courseId).toBe("ai");
  });

  it.each([
    ["자료", "material"],
    ["pdf", "material"],
    ["줌", "zoom"],
    ["화상", "zoom"],
    ["멘토링", "zoom"],
    ["퀴즈", "test"],
    ["리포트", "report"],
  ])("키워드 %s -> %s", (word, intent) => {
    expect(resolve(word, ctx()).intent).toBe(intent);
  });

  it("진행 중인 비영상(줌)과 열린 자료는 대상이고, 끝난 자료는 아니다", () => {
    expect(resolve("줌", ctx()).targets).toEqual(["z-bi"]);
    expect(resolve("자료", ctx()).targets).toEqual(["m-ai"]);
  });

  it("명령 꼬리(보여줘·틀어줘 …)는 과목 이름으로 읽지 않는다", () => {
    expect(resolve("데이터베이스 보여줘", ctx())).toMatchObject({ kind: "go", courseId: "bi" });
    // 고급웹프로그래밍에는 들을 영상이 없어 결과는 none 이지만, 과목과 종류는 제대로 읽었다.
    const result = resolve("고웹프 틀어줘", ctx());
    expect(result.intent).toBe("video");
    expect(result.matched?.course).toEqual({ text: "고웹프", name: "고급웹프로그래밍" });
  });

  it("끝의 조사(를·은·에 …)는 떼고 과목을 찾는다", () => {
    expect(resolve("데이터베이스를", ctx()).courseId).toBe("bi");
    expect(resolve("기계학습에", ctx()).courseId).toBe("ai");
  });

  it("'오늘·들을·N주차' 같은 시간 말은 과목 이름에 섞지 않는다", () => {
    expect(resolve("오늘 기계학습 들을 것", ctx()).courseId).toBeNull(); // '것' 은 모르는 말: 과목을 정하지 못한다
    expect(resolve("오늘 기계학습 10주차", ctx())).toMatchObject({ courseId: "ai", targets: ["v-ai-2"] });
  });

  it("종류를 알았지만 대상이 없으면 none 이되, 읽어 낸 과목·종류는 matched 에 남긴다", () => {
    const result = resolve("기계학습 과제", ctx());
    expect(result).toMatchObject({ kind: "none", intent: "report", courseId: null, targets: [] });
    expect(result.matched).toEqual({ course: { text: "기계학습", name: "기계학습" }, intent: { text: "과제", label: "과제" } });
  });

  it("matched 에는 어떤 말이 과목·종류로 읽혔는지 남는다", () => {
    expect(resolve("기계학습 강의", ctx()).matched).toEqual({
      course: { text: "기계학습", name: "기계학습" },
      intent: { text: "강의", label: "영상" },
    });
    expect(resolve("10주차", ctx()).matched?.intent).toEqual({ text: "10주", label: "영상" });
  });
});

describe("resolve — 과목 점수 우선순위(별칭 > 이름 접두 > 줄임말 > 영문·코드)", () => {
  const rank = (...courses: ReturnType<typeof course>[]) => ctx({ courses, activities: [] });

  it("이름이 그 말로 시작하는 과목이 줄임말로만 맞는 과목보다 우선한다", () => {
    const result = resolve("데이터", rank(
      course({ id: "a", ko: "데이터베이스", en: "Database" }),
      course({ id: "b", ko: "분석 데이터 사이언스", en: "Data Science" }),
    ));
    expect(result.courseId).toBe("a");
  });

  it("줄임말로 맞는 과목이 영문 이름·코드에 포함되기만 하는 과목보다 우선한다", () => {
    const result = resolve("고웹프", rank(
      course({ id: "a", ko: "고급웹프로그래밍", en: "Advanced Web Programming" }),
      course({ id: "b", ko: "다른 과목", en: "고웹프 special", code: "고웹프" }),
    ));
    expect(result.courseId).toBe("a");
  });

  it("과목 코드로도 찾는다(영문·코드 포함 일치)", () => {
    const result = resolve("cs-204", rank(
      course({ id: "cs", ko: "컴퓨터 구조", en: "Computer Architecture", code: "CS-204" }),
      course({ id: "x", ko: "다른 과목", en: "Other" }),
    ));
    expect(result.courseId).toBe("cs");
  });

  it("영문 이름의 일부로도 찾는다(한 글자 검색은 제외)", () => {
    const set = rank(course({ id: "ml", ko: "기계학습", en: "Machine Learning" }));
    expect(resolve("machine", set).courseId).toBe("ml");
    expect(resolve("m", set).courseId).toBeNull();
  });

  it("별칭이 있으면 이름 접두 일치보다 우선한다", () => {
    const result = resolve("디비", rank(
      course({ id: "bi", ko: "데이터베이스", en: "Database" }),
      course({ id: "x", ko: "디비전 경영", en: "Division" }),
    ));
    expect(result.courseId).toBe("bi");
  });
});

describe("resolve — 모호한 과목에서의 종류·계획", () => {
  const ambiguous = () => ctx({
    courses: [course({ id: "se", ko: "사이버보안", en: "Cyber Security" }), course({ id: "secu", ko: "컴퓨터보안", en: "Computer Security" })],
    activities: [
      activity({ id: "se-v", courseId: "se", due: "2026-11-13T00:00" }),
      activity({ id: "secu-v", courseId: "secu", due: "2026-11-14T00:00" }),
      activity({ id: "se-r", courseId: "se", type: "report", status: "missing", due: "2026-11-13T00:00" }),
    ],
  });

  it("과목이 모호해도 말한 종류는 지킨다", () => {
    const result = resolve("보안 강의", ambiguous());
    expect(result).toMatchObject({ kind: "choose", intent: "video", courseId: null });
    expect(result.targets).toEqual(["se-v", "secu-v"]);
  });

  it("과목이 모호하면 계획으로 좁히지 않고 대상 없음으로 처리한다", () => {
    expect(resolve("보안 전부", ambiguous()).kind).toBe("none");
  });
});
