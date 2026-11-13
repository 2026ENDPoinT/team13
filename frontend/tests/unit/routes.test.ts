import { afterEach, describe, expect, it, vi } from "vitest";
import { getState } from "../../src/store.ts";
import {
  classifyRoute, currentRole, equivalentHref, routeFor, stampRole, type RouteExtra, type RouteKind,
} from "../../src/shared/routes.ts";

describe("stampRole", () => {
  it.each([
    ["/a", "professor", "/a?as=pf"],
    ["/a", "student", "/a?as=st"],
    ["/a?x=1", "professor", "/a?x=1&as=pf"],
    ["/a?as=st&x=1", "professor", "/a?as=pf&x=1"], // 기존 as 를 교체
    ["/a?x=1#frag", "professor", "/a?x=1&as=pf"], // 조각은 버린다
  ])("%s + %s -> %s", (href, role, expected) => {
    expect(stampRole(href, role)).toBe(expected);
  });

  it.each([["admin"], [null], [undefined], [""]])("알 수 없는 역할 %j 는 주소를 바꾸지 않는다", (role) => {
    expect(stampRole("/a?x=1", role)).toBe("/a?x=1");
  });
});

describe("classifyRoute — 동치 분할", () => {
  it.each([
    ["/ilos/lo/logout.acl", "logout"],
    ["/ilos/pf/main/main_form.acl", "professor"],
    ["/ilos/cls/pf/report/x.acl", "professor"],
    ["/ilos/mp/anything", "professor"],
    ["/ilos/cls/st/activity/x.acl", "student"],
    ["/ilos/m/anything", "student"],
    ["/ilos/message/anything", "student"],
    ["/ilos/unknown/page", "student"], // /ilos/ 아래 알 수 없는 주소는 학생
    ["/elsewhere", "other"],
  ])("%s -> %s", (path, expected) => {
    expect(classifyRoute(path, "")).toBe(expected);
  });

  it("접두사 경계: 슬래시 없는 비슷한 접두사는 교수 영역이 아니다", () => {
    expect(classifyRoute("/ilos/pfx/a", "")).toBe("student");
    expect(classifyRoute("/ilos/cls/pf", "")).toBe("student");
  });

  it("/ 와 /index.html 은 메인 화면과 같은 분류", () => {
    const main = classifyRoute("/ilos/main/main_form.acl", "");
    expect(classifyRoute("/", "")).toBe(main);
    expect(classifyRoute("/index.html", "")).toBe(main);
  });

  it("params 는 URLSearchParams 와 문자열 모두 받는다", () => {
    expect(classifyRoute("/ilos/main/main_form.acl", new URLSearchParams("a=1"))).toBe(classifyRoute("/ilos/main/main_form.acl", "a=1"));
    expect(classifyRoute("/ilos/main/main_form.acl", null)).toBe("student");
  });
});

describe("currentRole", () => {
  const original = getState().role;
  afterEach(() => {
    history.replaceState(null, "", "/");
    getState().role = original;
  });
  const at = (url: string, role: "student" | "professor" = "student") => {
    getState().role = role;
    history.replaceState(null, "", url);
    return currentRole();
  };

  it("?as= 가 가장 우선한다(주소 분류·저장된 역할보다)", () => {
    expect(at("/?as=pf")).toBe("professor");
    expect(at("/ilos/pf/main/main_form.acl?as=st", "professor")).toBe("student");
  });

  it("?as= 가 없으면 주소 영역으로 판단한다", () => {
    expect(at("/ilos/pf/main/main_form.acl")).toBe("professor");
    expect(at("/ilos/cls/st/activity/player_form.acl", "professor")).toBe("student");
  });

  it("주소만으로 알 수 없으면 저장된 역할을 쓴다", () => {
    expect(at("/elsewhere", "professor")).toBe("professor");
    expect(at("/elsewhere", "student")).toBe("student");
  });

  it("?as= 값이 알 수 없는 값이면 무시한다", () => {
    expect(at("/elsewhere?as=zz", "professor")).toBe("professor");
  });
});

describe("routeFor — 역할·종류별 주소", () => {
  it("logout 은 역할과 무관하게 같은 주소", () => {
    expect(routeFor("student", "logout")).toBe("/ilos/lo/logout.acl");
    expect(routeFor("professor", "logout")).toBe("/ilos/lo/logout.acl");
  });

  it("home: 교수는 교수 영역 + as=pf, 학생은 학생 메인", () => {
    expect(routeFor("student", "home")).toBe("/ilos/main/main_form.acl");
    expect(routeFor("professor", "home")).toBe("/ilos/pf/main/main_form.acl?as=pf");
  });

  it("종류가 없거나 모르면 home 으로 대체한다", () => {
    expect(routeFor("student", null)).toBe(routeFor("student", "home"));
    expect(routeFor("student", undefined)).toBe(routeFor("student", "home"));
    expect(routeFor("student", "bogus" as never)).toBe(routeFor("student", "home"));
    expect(routeFor("professor", "bogus" as never)).toBe(routeFor("professor", "home"));
  });

  it("객체 형태 대상({ kind })도 문자열과 같다", () => {
    expect(routeFor("student", { kind: "courses" })).toBe(routeFor("student", "courses"));
  });

  it("학생 영상 활동은 플레이어, 과목은 활동의 과목", () => {
    expect(routeFor("student", "activity", { n: "lec-11-1" })).toBe("/ilos/cls/st/activity/player_form.acl?n=lec-11-1&kj=ai");
  });

  it("종료된 영상은 플레이어가 아니라 상세 화면", () => {
    expect(routeFor("student", "activity", { n: "v-expired" })).toBe("/ilos/cls/st/activity/activity_form.acl?m=lecture_weeks&n=v-expired&kj=bi");
  });

  it("교수의 과제·영상 활동은 각각 제출 현황·강의 화면(+as=pf)", () => {
    expect(routeFor("professor", "assignment", { n: "r-open" })).toBe("/ilos/cls/pf/report/report_submit_form.acl?kj=bi&n=r-open&as=pf");
    expect(routeFor("professor", "lecture", { n: "lec-11-1" })).toBe("/ilos/cls/pf/activity/lecture_form.acl?kj=ai&n=lec-11-1&as=pf");
  });

  it("교실/특강 교실은 경로가 다르다", () => {
    expect(routeFor("student", "classroom", { kj: "ai" })).toBe("/ilos/cls/st/submain/submain_form.acl?kj=ai");
    expect(routeFor("student", "classroom", { kj: "sec", special: true })).toBe("/ilos/cls/st/nr/submain_form.acl?kj=sec");
  });

  it("pop 은 팝업 이름을 메인 화면의 pop 쿼리로 전달한다", () => {
    expect(routeFor("student", "pop", { pop: "todo" })).toBe("/ilos/main/main_form.acl?pop=todo");
  });
});

describe("routeFor — 서버 ID(srv-<1~16자리>) 경계", () => {
  it("유효한 서버 과제 ID 는 학생 과제 상세로 간다", () => {
    expect(routeFor("student", "assignment", { n: "srv-12", kj: "c3" })).toBe("/ilos/cls/st/activity/activity_form.acl?m=report&n=srv-12&kj=c3");
    expect(routeFor("student", "quiz", { n: "srv-12", kj: "c3" })).toBe("/ilos/cls/st/activity/activity_form.acl?m=test&n=srv-12&kj=c3");
    expect(routeFor("student", "survey", { n: "srv-12", kj: "c3" })).toBe("/ilos/cls/st/activity/activity_form.acl?m=survey&n=srv-12&kj=c3");
  });

  it.each([["srv-0"], ["srv-012"], ["srv-12345678901234567"], ["srv-"], ["srv-1a"], ["x-12"]])(
    "유효하지 않은 서버 ID %s 는 상세로 가지 않고 교실로 대체한다",
    (n) => {
      expect(routeFor("student", "assignment", { n, kj: "c3" })).toBe("/ilos/cls/st/submain/submain_form.acl?kj=c3");
    },
  );

  it("교수의 서버 강의는 콘텐츠 관리로 간다", () => {
    expect(routeFor("professor", "lecture", { n: "srv-9", kj: "c3" })).toBe("/ilos/pf/contents/contents_list_form.acl?kj=c3&as=pf");
  });
});

describe("역할 전환 — equivalentHref", () => {
  it("학생 영상 -> 교수 -> 학생으로 돌아오면 원래 주소(왕복)", () => {
    const student = routeFor("student", "activity", { n: "lec-11-1" });
    const professor = equivalentHref("professor", student);
    expect(classifyRoute(professor.split("?")[0], new URLSearchParams(professor.split("?")[1]))).toBe("professor");
    expect(professor).toContain("as=pf");
    expect(equivalentHref("student", professor)).toBe(student);
  });

  it("과제는 역할을 오가도 같은 과제 ID 를 유지한다", () => {
    const student = routeFor("student", "assignment", { n: "r-open" });
    const professor = equivalentHref("professor", student);
    expect(professor).toContain("n=r-open");
    expect(equivalentHref("student", professor)).toContain("n=r-open");
  });

  it("이미 그 역할의 주소면 그대로(교수는 as=pf 만 보장)", () => {
    const student = routeFor("student", "classroom", { kj: "ai" });
    expect(equivalentHref("student", student)).toBe(student);
    const professor = routeFor("professor", "home");
    expect(equivalentHref("professor", professor)).toBe(professor);
    expect(equivalentHref("professor", "/ilos/pf/main/main_form.acl")).toBe("/ilos/pf/main/main_form.acl?as=pf");
  });

  it("로그아웃·앱 밖 주소는 바꾸지 않는다", () => {
    expect(equivalentHref("professor", "/ilos/lo/logout.acl")).toBe("/ilos/lo/logout.acl");
    expect(equivalentHref("professor", "/foo?x=1")).toBe("/foo?x=1");
  });
});

// 속성 테스트: 정확한 경로를 하나하나 고정하지 않고, 모든 (종류 × 입력 × 역할) 조합이 만족해야 하는 규칙을 검사한다.
describe("routeFor / equivalentHref — 모든 종류에 대한 불변식", () => {
  const KINDS: RouteKind[] = [
    "home", "logout", "survey", "activity", "lecture", "assignment", "quiz", "pdf", "courses", "study", "classroom",
    "notice", "qna", "message", "calendar", "weekcal", "timetable", "sitemap", "attend", "grades", "switch", "pop",
    "todo", "notify", "profile",
  ];
  const INPUTS: RouteExtra[] = [
    { kj: "ai", n: "lec-11-1" }, // 영상
    { kj: "bi", n: "r-open" }, // 과제
    { kj: "oss", n: "t-on" }, // 시험
    { kj: "bi", n: "m-1" }, // 자료
    { kj: "ai" }, // 활동 없음
    { kj: "c3", n: "srv-12" }, // 서버 ID
  ];
  const cases = KINDS.flatMap((kind) => INPUTS.map((extra) => ({ kind, extra, label: `${kind} ${JSON.stringify(extra)}` })));
  const classOf = (href: string) => {
    const [path, query = ""] = href.split("?");
    return classifyRoute(path, new URLSearchParams(query));
  };

  it.each(cases)("학생 주소는 학생·공용 영역: $label", ({ kind, extra }) => {
    const href = routeFor("student", kind, extra);
    expect(["student", "any", "logout"]).toContain(classOf(href));
    expect(href).not.toContain("as=pf");
  });

  it.each(cases)("교수 주소는 교수·공용 영역이고 as=pf 가 붙는다(로그아웃 제외): $label", ({ kind, extra }) => {
    const href = routeFor("professor", kind, extra);
    expect(["professor", "any", "logout"]).toContain(classOf(href));
    if (kind !== "logout" && classOf(href) !== "logout") expect(href).toContain("as=pf");
  });

  it.each(cases)("역할을 바꾼 주소도 바꾼 역할의 영역에 속한다: $label", ({ kind, extra }) => {
    const toProfessor = equivalentHref("professor", routeFor("student", kind, extra));
    const toStudent = equivalentHref("student", routeFor("professor", kind, extra));
    expect(["professor", "any", "logout"]).toContain(classOf(toProfessor));
    expect(["student", "any", "logout"]).toContain(classOf(toStudent));
  });

  it.each(cases)("역할 변환은 멱등: 이미 그 역할이면 한 번 더 변환해도 같은 주소: $label", ({ kind, extra }) => {
    const once = equivalentHref("professor", routeFor("student", kind, extra));
    expect(equivalentHref("professor", once)).toBe(once);
    const back = equivalentHref("student", routeFor("professor", kind, extra));
    expect(equivalentHref("student", back)).toBe(back);
  });
});

describe("routeFor — screen 종류", () => {
  it("화면 ID 로 주소를 만들고, 값이 있는 입력만 쿼리로 넘긴다(불리언은 문자열로, 객체·null 은 제외)", () => {
    const href = routeFor("student", "screen", {
      screenId: "40_room_submain", kj: "ai", pop: "x", flag: true, count: 3, skip: null,
      activity: { id: "a", courseId: "bi", type: "video" },
    });
    expect(href).toBe("/ilos/cls/st/submain/submain_form.acl?kj=ai&pop=x&flag=true&count=3");
  });

  it("교수에게는 교수 영역 + as=pf", () => {
    expect(routeFor("professor", "screen", { screenId: "pf_room_week", kj: "ai", flag: false }))
      .toBe("/ilos/cls/pf/submain/submain_form.acl?mode=week&kj=ai&flag=false&as=pf");
  });

  it("역할이 맞지 않는 화면 ID 는 상대 역할의 대응 화면으로 바꿔 준다", () => {
    expect(routeFor("student", "screen", { screenId: "pf_room_week", kj: "ai" })).toBe("/ilos/cls/st/submain/submain_form.acl?kj=ai");
    expect(routeFor("student", "screen", { screenId: "pf_gradebook", kj: "ai" })).toBe("/ilos/cls/st/eval/eval3_result_view_form.acl?kj=ai");
    expect(routeFor("professor", "screen", { screenId: "40_room_submain", kj: "ai" })).toBe("/ilos/cls/pf/submain/submain_form.acl?mode=week&kj=ai&as=pf");
    expect(routeFor("professor", "screen", { screenId: "43_room_eval_result", kj: "ai" })).toBe("/ilos/cls/pf/eval/gradebook_form.acl?kj=ai&as=pf");
  });

  it("공용 화면(sitemap)은 학생에게는 그대로, 교수에게는 as=pf 만 붙인다", () => {
    expect(routeFor("student", "screen", { screenId: "sitemap" })).toBe("/ilos/main/sitemap_form.acl");
    expect(routeFor("professor", "screen", { screenId: "sitemap" })).toBe("/ilos/main/sitemap_form.acl?as=pf");
  });

  it("교수가 학생 홈 화면 ID 를 요청하면 교수 홈으로", () => {
    expect(routeFor("professor", "screen", { screenId: "00_main_home" })).toBe("/ilos/pf/main/main_form.acl?as=pf");
  });

  it("표에 없는 화면 ID 는 프로그램 오류로 던진다", () => {
    expect(() => routeFor("student", "screen", { screenId: "no-such-screen" })).toThrow(/Unknown screen id/);
  });
});

describe("classifyRoute — 공용 화면과 currentRole 의 방어", () => {
  it("사이트맵은 어느 역할에도 속하지 않는 공용(any)", () => {
    expect(classifyRoute("/ilos/main/sitemap_form.acl", "")).toBe("any");
  });

  it("location 이 없는 환경(서버 렌더 등)에서는 저장된 역할을 쓴다", () => {
    getState().role = "professor";
    vi.stubGlobal("location", undefined);
    expect(currentRole()).toBe("professor");
    getState().role = "student";
    expect(currentRole()).toBe("student");
  });

  it("location.pathname 이 비어 있으면 주소를 보지 않고 저장된 역할을 쓴다(as 도 무시)", () => {
    getState().role = "professor";
    vi.stubGlobal("location", { pathname: "", search: "?as=st" });
    expect(currentRole()).toBe("professor");
    getState().role = "student";
  });
});
