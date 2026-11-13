import { el, icon } from "../../dom.js";
import { go, emptyState, table, mark } from "../../ui/components.js";
import { coverImage } from "../../ui/assets.js";
import { courses, courseById, activities, student, COURSE_ORDER } from "../../data.js";
import { hrefFor, screens, find } from "../../manifest.js";
import { routeFor } from "../../shared/routes.js";
import { dday, typeLabel, boardRows, activityHref, openTodos, myDayGroups, TODAY } from "../../shared/format.js";
import { readQuery, pageSearch } from "../../shared/query.js";
import { renderCourseStudy } from "./course-study.js";
import { studyRoot, sampleSummary } from "./study-files.js";
import { renderRecording } from "./recording.js";
import { commandBar, planPreview } from "./command-bar.js";
import { backendStatus, backendState } from "../../shared/backend.js";
import { profiles, peekAccount } from "../../shared/account-remote.js";
import { courseCards, specialCard, termLabel } from "./course-cards.js";
import { serverHomeCourses, serverAsideTodo, homeLoginPanel } from "./home-server.js";
import { serverCatalog, serverCourseList, serverCatalogDetail, catalogLoginPanel } from "./catalog-server.js";
import { serverHomeCalendar, serverTodayClasses } from "./calendar-server.js";
import { remoteCommunityBoard } from "./community-server.js";
import { serverCommunitySurveys } from "./surveys-server.js";
import { serverSiteNotices } from "./site-notices-server.js";

const DOW = ["일", "월", "화", "수", "목", "금", "토"];


function parseISO(iso) {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d);
}


function shortDate(iso) {
  const date = parseISO(iso);
  return `${date.getMonth() + 1}월 ${date.getDate()}일`;
}

function longDate(iso) {
  return `${shortDate(iso)} ${DOW[parseISO(iso).getDay()]}요일`;
}

// The greeting rises once per page load; later renders (dialogs, toasts) show it in place.
let heroPlayed = false;

/** Full-width hero row the shell places above the menu, main column, and aside on student home. */
export function fillHero(hero) {
  const enter = !heroPlayed;
  heroPlayed = true;
  if (backendStatus() === "ready") {
    const me = backendState().me;
    const profile = peekAccount(profiles)?.data;
    const ownProfile = profile && me && String(profile.userId) === String(me.userId) ? profile : null;
    const name = (me && me.name) || (ownProfile && ownProfile.name) || "사용자";
    hero.replaceChildren(el("div", { class: `home-hero${enter ? " enter" : ""}` },
      el("div", { class: "home-hero-copy" },
        el("h1", { text: `${name}님, 안녕하세요.` }),
        el("p", { text: "수강과목과 서버에 등록된 학습 일정을 확인하세요." }),
      ),
    ));
    return;
  }
  const soon = myDayGroups().soon.items.length;
  const soonHref = hrefFor(find("01_pop_todo"), { "todo-view": "soon" });
  hero.replaceChildren(el("div", { class: `home-hero${enter ? " enter" : ""}` },
    el("div", { class: "home-hero-copy" },
      soon
        ? el("h1", null,
          `${student.name}님. 마감이 얼마 남지 않은 일이 `,
          el("button", { type: "button", class: "hero-count", "data-go": soonHref, title: "사흘 안 마감 목록 열기", text: `${soon}개` }),
          " 있습니다.",
        )
        : el("h1", { text: `${student.name}님, 급한 할 일은 없네요!` }),
      el("p", { text: `${parseISO(TODAY).getFullYear()}년 ${longDate(TODAY)}` }),
      commandBar(),
    ),
    planPreview(),
  ));
}

function summaryCard(kind, sub, group, unit, linkText) {
  const href = hrefFor(find("01_pop_todo"), { "todo-view": kind });
  return el("article", { class: `summary-card summary-${kind}` },
    el("div", { class: "summary-card-heading" },
      el("h3", { text: group.label }),
      el("span", { class: "summary-date", text: sub }),
    ),
    el("div", { class: "summary-card-content" },
      el("p", { class: "summary-value" },
        el("span", { class: "summary-number", text: String(group.items.length) }),
        el("span", { class: "summary-unit", text: unit }),
      ),
      el("a", { class: "summary-link", href, "data-go": href },
        el("span", { text: linkText }),
        el("span", { class: "summary-chevron", "aria-hidden": "true" }, icon("chev")),
      ),
    ),
  );
}

function myDay(state) {
  const { due, report, week } = myDayGroups(state);
  return el("section", { class: "my-day", "aria-labelledby": "my-day-title" },
    el("h2", { class: "section-label", id: "my-day-title", text: "나의 하루" }),
    el("div", { class: "summary-strip" },
      summaryCard("due", due.at ? `${longDate(due.at.slice(0, 10))} ${due.at.slice(11, 16)}까지` : "예정된 마감 없음", due, "건", "할 일 보기"),
      summaryCard("report", report.items.length ? `가장 빠른 마감 ${shortDate(report.items[0].due.slice(0, 10))}` : "남은 과제 없음", report, "건", "과제 보기"),
      summaryCard("week", `${week.week}주차`, week, "개", "자료 보기"),
    ),
  );
}

function renderHome(screen, ctx) {
  const params = (ctx && ctx.params) || new URLSearchParams();
  // Backend `ready`: keep the server calendar and every imported course, including past terms.
  if (backendStatus() === "ready") {
    return el("section", { class: "home-main" },
      serverHomeCalendar(params),
      el("h2", { class: "section-label", text: "수강과목" }),
      serverHomeCourses(),
    );
  }
  const wrap = el("section", { class: "home-main" }, myDay(ctx.state));
  const enrolled = courses
    .filter((c) => c.kind === "regular" && c.enrolled && c.term === "2026-2")
    .sort((a, b) => COURSE_ORDER.indexOf(a.id) - COURSE_ORDER.indexOf(b.id));
  wrap.append(el("h2", { class: "section-label", text: "수강과목" }));
  wrap.append(el("p", { class: "term", text: "2학기" }));
  const backend = backendStatus();
  // Before sign-in, the login strip remains above the sample cards.
  if (backend === "anon") wrap.append(homeLoginPanel(false));
  wrap.append(courseCards(enrolled, "home"));
  return wrap;
}

function renderCourseList(screen, ctx) {
  // Backend `ready`: GET /api/me/courses (confirmed courses only); `anon`: login strip above the sample.
  if (backendStatus() === "ready") return serverCourseList(screen, ctx);
  const term = screen.term;
  const scope = `home:list:${term || "current"}`;
  const q = readQuery(ctx.state, scope).trim();
  let list = courses.filter((c) => c.kind === "regular" && c.enrolled);
  let title = "수강과목";
  let label = "2026년 2학기";
  if (term === "2026-1") {
    list = list.filter((c) => c.term === "2026-1");
    label = "2026년 1학기";
  } else if (term === "special-enrolled") {
    list = courses.filter((c) => c.kind === "special" && c.enrolled);
    title = "수강과목";
    label = "비정규 · 수강 중";
  } else list = list.filter((c) => c.term === "2026-2");
  if (q) list = list.filter((c) => `${c.ko} ${c.en} ${c.instructor} ${c.code}`.includes(q));
  return el("section", {},
    el("div", { class: "page-head" },
      el("div", {}, el("h1", { text: title }), el("p", { class: "term tight", text: label })),
      pageSearch(ctx, scope),
    ),
    backendStatus() === "anon" ? catalogLoginPanel() : null,
    term === "special-enrolled"
      ? el("div", { class: "cards" }, ...list.map(specialCard))
      : courseCards(list, "enrolled"),
    term === "2026-2" ? el("p", { class: "term-link" }, go(hrefFor(find("21b_regular_register_prev_semester")), "2026년 1학기")) : null,
    term === "special-enrolled" ? el("p", { class: "term-link" }, go(hrefFor(find("22_nonregular_list")), "비정규과목 전체")) : null,
  );
}

function renderCatalog(screen, ctx) {
  if (backendStatus() === "ready") return serverCatalog(screen, ctx);
  const kind = screen.catalog;
  let list = courses.filter((c) => c.kind === kind && !c.closed);
  if (screen.openOnly) list = list.filter((c) => c.open);
  if (screen.dept) list = list.filter((c) => /데이터|알고리즘|정보|UX|Data|Algorithm/.test(`${c.ko} ${c.en}`));
  const scope = `home:catalog:${kind}`;
  const q = readQuery(ctx.state, scope).trim();
  if (q) list = list.filter((c) => `${c.ko} ${c.en} ${c.instructor} ${c.code}`.includes(q));
  const title = kind === "special" ? "비정규과목 전체" : "정규과목 전체";
  return el("section", {},
    el("div", { class: "page-head" },
      el("h1", { text: title }),
      pageSearch(ctx, scope),
    ),
    backendStatus() === "anon" ? catalogLoginPanel() : null,
    el("div", { class: "filters" },
      go(hrefFor(find(kind === "special" ? "22_nonregular_list" : "20_regular_list")), "전체", { class: `chip${!screen.openOnly && !screen.dept ? " on all" : ""}` }),
      kind === "special"
        ? go(hrefFor(find("22b_nonregular_list_filter")), "모집 중", { class: `chip${screen.openOnly ? " on all" : ""}` })
        : go(hrefFor(find("20_regular_list_filter")), "산업정보", { class: `chip${screen.dept ? " on all" : ""}` }),
      el("span", { class: "tiny filter-count", text: `${list.length}과목` }),
    ),
    list.length
      ? (kind === "special" ? el("div", { class: "cards" }, ...list.map(specialCard)) : courseCards(list, "catalog"))
      : emptyState("조건에 맞는 과목이 없습니다", "필터를 바꾸거나 검색어를 지워 보세요."),
  );
}

function renderDetail(screen, ctx) {
  if (backendStatus() === "ready") return serverCatalogDetail(screen, ctx);
  const picked = (ctx.params && ctx.params.get("id")) || screen.course || ctx.state.courseId;
  const course = courseById(picked);
  if (course.missing || course.id !== picked) return emptyState("과목을 찾을 수 없습니다", "과목 목록에서 다시 선택해 주세요.");
  const applied = Boolean(ctx.state.applications[course.id]) || course.enrolled;
  const recruiting = course.kind === "special" && (course.open || screen.id === "24b_nonregular_view_open");
  return el("section", { class: "detail" },
    backendStatus() === "anon" ? catalogLoginPanel() : null,
    el("div", { class: `detail-hero ${course.gradient}` },
      coverImage(course, 960, 220),
      el("p", { class: "k", text: course.kind === "special" ? "비정규과목" : termLabel(course.term) }),
      el("h1", { text: course.ko }),
      el("p", { text: `${course.code} · ${course.instructor}` }),
      el("div", { class: "hero-pills" },
        el("span", { text: course.kind === "special" ? "비교과" : "정규" }),
        recruiting ? el("span", { text: "모집 중" }) : null,
        applied ? el("span", { text: "수강 중" }) : null,
      ),
    ),
    el("div", { class: "detail-grid" },
      el("div", { class: "sheet" },
        el("h2", { text: "강의 소개" }),
        el("p", { text: `${course.instructor} 담당 과목입니다. 강의계획 원문은 넣지 않았고, 출석과 과제는 이 브라우저 안에서만 바뀝니다.` }),
        el("h2", { text: "이수 정보" }),
        table(["항목", "내용"], [
          ["담당", course.instructor],
          ["학점", `${course.credit}학점`],
          ["시간", course.time],
          ["강의실", course.room],
          ["정원", `${course.learners} / ${course.capacity || course.learners}`],
        ]),
      ),
      el("div", { class: "sheet side" },
        el("h2", { text: course.ko }),
        el("p", { class: "tiny", text: course.code }),
        el("dl", { class: "kv" },
          el("div", {}, el("dt", { text: "운영" }), el("dd", { text: course.kind === "special" ? "온라인" : "학기 과목" })),
          el("div", {}, el("dt", { text: "기간" }), el("dd", { text: course.kind === "special" ? "2026.09.01 – 2026.12.19" : termLabel(course.term) })),
          el("div", {}, el("dt", { text: "상태" }), el("dd", { text: applied ? "수강 중" : recruiting ? "모집 중" : "신청 마감" })),
        ),
        el("div", { class: "btn-row" },
          applied
            ? go(hrefFor(find(course.kind === "special" ? "70_nr_room_submain" : "40_room_submain"), { kj: course.id }), "강의실 입장", { class: "btn btn-primary" })
            : course.kind === "special"
              ? go(hrefFor(find("24c_nonregular_apply_pop"), { id: course.id }), "수강 신청", { class: "btn btn-primary" })
              : el("p", { class: "tiny", text: "이 학기 수강 과목이 아닙니다." }),
          go(hrefFor(find(course.kind === "special" ? "22_nonregular_list" : "20_regular_list")), "목록", { class: "btn btn-secondary" }),
        ),
        applied && !course.enrolled ? el("p", { class: "tiny", text: "신청이 이 브라우저에 저장되었습니다." }) : null,
      ),
    ),
  );
}

function boardAudience(screen, ctx) {
  return screen.audience || ((ctx.params && ctx.params.get("audience")) || "");
}

function renderCommunity(screen, ctx) {
  // Backend mode: 알림마당 설문 comes from `GET /api/surveys` (areas/home/surveys-server.js).
  const serverSurveys = screen.board === "survey" ? serverCommunitySurveys(ctx) : null;
  if (serverSurveys) return serverSurveys;
  const audience = boardAudience(screen, ctx);
  let rows = boardRows({ ...screen, audience: audience || undefined }, ctx.state);
  const scope = `home:board:${screen.board || "global"}`;
  const q = readQuery(ctx.state, scope).trim();
  if (q) rows = rows.filter((row) => `${row.title} ${row.body || ""} ${row.author || ""}`.includes(q));
  const titles = { global: "공지사항", qna: "질의응답", material: "자료실", survey: "설문" };
  const title = titles[screen.board] || screen.title;
  const head = el("div", { class: "page-head" }, el("h1", { text: title }));
  if (screen.board === "qna") {
    head.append(el("div", { class: "board-tools" },
      go(hrefFor(find("31c_supporters_pop")), "서포터즈", { class: "btn btn-secondary" }),
      go(hrefFor(find("31b_qna_insert_pop")), "1:1 질문하기", { class: "btn btn-primary" }),
    ));
  }
  // Backend mode: 자료실 and 1:1 문의 come from the server (areas/home/community-server.js).
  if ((screen.board === "material" || screen.board === "qna") && backendStatus() !== "off") return remoteCommunityBoard(screen, ctx, head);
  // Backend mode: 알림마당 공지사항 comes from the server (areas/home/site-notices-server.js).
  if (screen.board === "global" && backendStatus() !== "off") return serverSiteNotices(screen, ctx, head);
  const body = el("section", { class: "board" }, head);
  if (screen.board === "global") {
    const all = boardRows({ ...screen, audience: undefined }, ctx.state);
    const studentN = all.filter((row) => row.audience === "student" || row.audience === "all").length;
    const profN = all.filter((row) => row.audience === "professor" || row.audience === "all").length;
    body.append(el("div", { class: "filters" },
      go(hrefFor(find("30_community_notice_list")), `전체 ${all.length}`, { class: `chip${!audience ? " on all" : ""}` }),
      go(`${hrefFor(find("30_community_notice_list"))}?audience=student`, `학생 ${studentN}`, { class: `chip${audience === "student" ? " on all" : ""}` }),
      go(`${hrefFor(find("30_community_notice_list"))}?audience=professor`, `교수 ${profN}`, { class: `chip${audience === "professor" ? " on all" : ""}` }),
    ));
  } else {
    body.append(el("p", { class: "board-count", text: `전체 ${rows.length}` }));
  }
  body.append(el("label", { class: "search board-search" },
    icon("search"),
    el("input", {
      "data-name": "surfaceQuery",
      "data-scope": scope,
      "data-focus": scope,
      value: readQuery(ctx.state, scope),
      placeholder: "검색",
      "aria-label": `${title} 검색`,
    }),
  ));
  if (!rows.length) {
    body.append(emptyState("조회된 자료가 없습니다", screen.board === "qna" ? "1:1 질문하기를 누르면 이 목록에 남습니다." : "등록된 글이 없습니다."));
    return body;
  }
  const list = el("div", { class: "board-list" });
  for (const row of rows) {
    let href = hrefFor(find("47_room_open_board_list"));
    if (screen.board === "global") href = hrefFor(find("06_pop_notice_view"), { id: row.id });
    else if (screen.board === "survey") href = hrefFor(find("33b_survey_view_pop"), { id: row.id });
    else if (screen.board === "qna") href = hrefFor(find("31b_qna_insert_pop"));
    list.append(el("a", { class: "board-card", href, "data-go": href },
      el("b", { text: row.title }),
      el("span", { class: "tiny", text: [row.author, row.date || row.at].filter(Boolean).join(" · ") }),
      row.body ? el("p", { text: row.body }) : null,
    ));
  }
  body.append(list);
  return body;
}

function renderSitemap(ctx) {
  const professor = ctx.state.role === "professor";
  const groups = professor
    ? [["교수", screens.filter((s) => s.role === "professor")]]
    : [
      ["학생", screens.filter((s) => s.role === "student" && s.layout !== "mobile")],
      ["모바일", screens.filter((s) => s.layout === "mobile")],
    ];
  return el("section", {},
    el("h1", { text: "전체 화면" }),
    el("p", { class: "subhead", text: "화면 이름으로 이동합니다. 밝은 화면은 기기 설정이 라이트일 때만 열립니다." }),
    ...groups.map(([title, list]) => el("div", {},
      el("h2", { class: "section-label", text: title }),
      el("div", { class: "sitemap" }, ...list.map((s) => {
        const href = professor ? routeFor("professor", "screen", { screenId: s.id, ...(s.course ? { kj: s.course } : {}) }) : hrefFor(s);
        return el("a", { href, "data-go": href }, el("b", { text: s.title }), el("small", { text: s.id }));
      })),
    )),
  );
}

function logoutView() {
  return el("section", {},
    el("h1", { text: "로그아웃" }),
    el("p", { class: "subhead", text: "샘플 세션을 닫았습니다. 역할 화면으로 다시 들어가지 않습니다." }),
  );
}

function koDate(iso) {
  const date = parseISO(iso);
  return `${date.getMonth() + 1}월 ${date.getDate()}일 (${DOW[date.getDay()]})`;
}

const ASIDE_TODOS = ["lec-11-1", "lec-11-2", "rep-project", "quiz-final"];

function todoLink(item) {
  const course = courseById(item.courseId);
  const href = activityHref(item);
  return el("a", {
    class: "aside-card todo-card todo-link",
    href,
    "data-go": href,
    "data-item": item.id,
    "data-todo": item.type,
  },
  el("div", {},
    el("div", { class: "todo-k", text: typeLabel(item.type) }),
    el("b", { text: item.title }),
    el("div", { class: "tiny", text: `${course.cardTitle || course.ko} (${course.code})` }),
    el("div", { class: "tiny", text: item.whenLabel || (item.due ? `마감 ${item.due}` : "진행 중") }),
  ),
  el("span", { class: "due", text: dday(item.due) || "진행" }),
  );
}

export function fillAside(aside) {
  aside.replaceChildren();
  // Backend `ready`: today's classes and to-dos are server facts, never school sample activities.
  if (backendStatus() === "ready") {
    aside.append(...serverTodayClasses(), go(hrefFor(find("05_pop_lectime")), "수업시간표", { class: "btn btn-secondary block" }));
    aside.append(...serverAsideTodo(hrefFor(find("09_home_todo_more"))));
    return;
  }
  const web = courseById("algo");
  aside.append(el("div", { class: "aside-h" },
    el("h2", { text: "오늘 수업" }),
    el("span", { class: "tiny", text: koDate(TODAY) }),
  ));
  aside.append(el("article", { class: "aside-card today-class" },
    el("b", { text: web.ko }),
    el("div", { class: "tiny", text: `(${web.code})` }),
    el("div", { class: "time-row" }, el("i", { class: "dot" }), el("span", { text: web.clock || web.time })),
    el("div", { class: "tiny", text: web.room || "-" }),
  ));
  aside.append(go(hrefFor(find("05_pop_lectime")), "수업시간표", { class: "btn btn-secondary block" }));
  aside.append(el("div", { class: "aside-h" },
    el("h2", { text: "할일" }),
    el("span", { class: "count-num", text: String(openTodos().length) }),
  ));
  for (const id of ASIDE_TODOS) {
    const item = activities.find((row) => row.id === id);
    if (item) aside.append(todoLink(item));
  }
  aside.append(go(hrefFor(find("09_home_todo_more")), "모두 보기", { class: "more-link" }));
}

export function render(screen, ctx) {
  switch (screen.kind) {
    case "home": return mark("home", renderHome(screen, ctx));
    case "course-list": return mark("home", renderCourseList(screen, ctx));
    case "catalog": return mark("home", renderCatalog(screen, ctx));
    case "detail": return mark("home", renderDetail(screen, ctx));
    case "board": return mark("home", renderCommunity(screen, ctx));
    case "study":
      if (screen.mode === "summary") return mark("home", sampleSummary(ctx));
      return mark("home", ctx.params && ctx.params.has("kj") ? renderCourseStudy(ctx) : studyRoot(ctx));
    case "lecture": return mark("home", renderRecording(ctx));
    case "sitemap": return mark("home", renderSitemap(ctx));
    case "logout": return mark("home", logoutView());
    default: return mark("home", emptyState("이 화면은 홈 영역이 아닙니다", screen.id));
  }
}
