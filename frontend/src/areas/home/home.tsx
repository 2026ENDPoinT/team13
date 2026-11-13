import { useSyncExternalStore, type ReactNode } from "react";
import { flushSync } from "react-dom";
import { Link, GoButton, serverLogoutConfirmed } from "../../router.tsx";
import { render as repaint } from "../../store.ts";
import { Icon } from "../../ui/icon.tsx";
import { EmptyState, Table } from "../../ui/kit.tsx";
import { CoverImage } from "../../ui/assets.tsx";
import { courses, courseById, activities, student, COURSE_ORDER } from "../../data.ts";
import type { Activity, AppState } from "../../data.ts";
import { hrefFor, screens, find } from "../../manifest.ts";
import { routeFor } from "../../shared/routes.ts";
import { dday, typeLabel, boardRows, activityHref, openTodos, myDayGroups, TODAY } from "../../shared/format.ts";
import type { MyDayGroup } from "../../shared/format.ts";
import { readQuery, writeQuery, pageSearch } from "../../shared/query.tsx";
import type { Screen, ScreenCtx } from "../../types.ts";
import { renderCourseStudy } from "./course-study.tsx";
import { studyRoot, sampleSummary } from "./study-files.tsx";
import { renderRecording } from "./recording.tsx";
import { commandBar, focusCommand, planPreview } from "./command-bar.tsx";
import { backendStatus, backendState, subscribe as onBackendChange } from "../../shared/backend.ts";
import { profiles, peekAccount } from "../../shared/account-remote.ts";
import { courseCards, specialCard, termLabel } from "./course-cards.tsx";
import { serverHomeCourses, serverAsideTodo, homeLoginPanel } from "./home-server.tsx";
import { serverCatalog, serverCourseList, serverCatalogDetail, catalogLoginPanel } from "./catalog-server.tsx";
import { serverHomeCalendar, serverTodayClasses } from "./calendar-server.tsx";
import { remoteCommunityBoard } from "./community-server.tsx";
import { serverCommunitySurveys } from "./surveys-server.tsx";
import { serverSiteNotices } from "./site-notices-server.tsx";

const DOW = ["일", "월", "화", "수", "목", "금", "토"];

function parseISO(iso: string): Date {
  const [y = 0, m = 1, d = 1] = iso.split("-").map(Number);
  return new Date(y, m - 1, d);
}

function shortDate(iso: string): string {
  const date = parseISO(iso);
  return `${date.getMonth() + 1}월 ${date.getDate()}일`;
}

function longDate(iso: string): string {
  return `${shortDate(iso)} ${DOW[parseISO(iso).getDay()]}요일`;
}

// The greeting rises once per page load; later renders (dialogs, toasts) show it in place.
let heroPlayed = false;

// 히어로 전체화면(상단 바 아래 화면 전체를 명령 입력에 쓴다) ↔ 일반 모드. 마지막 선택을 브라우저에 남긴다.
const HERO_MODE_KEY = "astra-hero-mode";
let heroFull = readHeroMode();

function readHeroMode(): boolean {
  try {
    return localStorage.getItem(HERO_MODE_KEY) === "full";
  } catch {
    return false;
  }
}

/**
 * 전환은 View Transition으로 그린다: 히어로가 상단 바 아래에서 펼쳐지고 접히며 나머지 화면은 짧게 겹쳐 바뀐다.
 * 지원하지 않는 브라우저나 동작 줄이기 설정에서는 바로 바꾼다.
 */
function toggleHeroMode(): void {
  heroFull = !heroFull;
  try {
    localStorage.setItem(HERO_MODE_KEY, heroFull ? "full" : "normal");
  } catch {
    /* private mode or full storage: keep the choice for this page */
  }
  const apply = () => {
    flushSync(repaint);
    if (heroFull) focusCommand();
  };
  if (!("startViewTransition" in document) || matchMedia("(prefers-reduced-motion: reduce)").matches) {
    apply();
    return;
  }
  const root = document.documentElement;
  root.classList.add("hero-morph");
  document.startViewTransition(apply).finished.finally(() => root.classList.remove("hero-morph"));
}

/** 일반 모드에서 명령 입력창을 누르거나 포커스하면 전체화면으로 바꾼다(이미 전체화면이면 아무 일도 하지 않는다). */
function expandHero(): void {
  if (!heroFull) toggleHeroMode();
}

/** `<>` tilted 45°: outward chevrons expand to full screen, inward ones return to the normal layout. */
function HeroModeToggle() {
  const label = heroFull ? "일반 화면으로 보기" : "전체 화면으로 보기";
  return (
    <button type="button" className="hero-mode-toggle" aria-label={label} title={label} aria-pressed={heroFull ? "true" : "false"} onClick={toggleHeroMode}>
      <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        {heroFull ? <path d="M4 7l5 5-5 5M20 7l-5 5 5 5" /> : <path d="M9 7l-5 5 5 5M15 7l5 5-5 5" />}
      </svg>
    </button>
  );
}

/** Full-width hero row the shell places above the menu, main column, and aside on student home. */
export function homeHero(ctx: ScreenCtx): ReactNode {
  const enter = !heroPlayed;
  heroPlayed = true;
  if (backendStatus() === "ready") {
    const me = backendState().me;
    const profile = peekAccount(profiles)?.data;
    const ownProfile = profile && me && String(profile.userId) === String(me.userId) ? profile : null;
    const name = (me && me.name) || (ownProfile && ownProfile.name) || "사용자";
    return (
      <div className={`home-hero${enter ? " enter" : ""}`}>
        <div className="home-hero-copy">
          <div className="hero-greet">
            <h1>{`${name}님, 안녕하세요.`}</h1>
            <p>수강과목과 서버에 등록된 학습 일정을 확인하세요.</p>
          </div>
        </div>
      </div>
    );
  }
  const soon = myDayGroups(ctx.state).soon.items.length;
  const soonHref = hrefFor(find("01_pop_todo"), { "todo-view": "soon" });
  return (
    <div className={`home-hero${enter ? " enter" : ""}${heroFull ? " is-full" : ""}`}>
      <div className="home-hero-copy">
        <div className="hero-greet">
          {soon
            ? (
              <h1>
                {`${student.name}님. 마감이 얼마 남지 않은 일이 `}
                <GoButton className="hero-count" href={soonHref} title="사흘 안 마감 목록 열기">{`${soon}개`}</GoButton>
                {" 있습니다."}
              </h1>
            )
            : <h1>{`${student.name}님, 급한 할 일은 없네요!`}</h1>}
          <p>{`${parseISO(TODAY).getFullYear()}년 ${longDate(TODAY)}`}</p>
        </div>
        {commandBar(expandHero)}
      </div>
      {planPreview()}
      <HeroModeToggle />
    </div>
  );
}

function SummaryCard({ kind, sub, group, unit, linkText }: { kind: string; sub: string; group: MyDayGroup; unit: string; linkText: string }) {
  const href = hrefFor(find("01_pop_todo"), { "todo-view": kind });
  return (
    <article className={`summary-card summary-${kind}`}>
      <div className="summary-card-heading">
        <h3>{group.label}</h3>
        <span className="summary-date">{sub}</span>
      </div>
      <div className="summary-card-content">
        <p className="summary-value">
          <span className="summary-number">{String(group.items.length)}</span>
          <span className="summary-unit">{unit}</span>
        </p>
        <Link className="summary-link" href={href}>
          <span>{linkText}</span>
          <span className="summary-chevron" aria-hidden="true"><Icon name="chev" /></span>
        </Link>
      </div>
    </article>
  );
}

function myDay(state: AppState): ReactNode {
  const { due, report, week } = myDayGroups(state);
  const firstReport = report.items[0];
  return (
    <section className="my-day" aria-labelledby="my-day-title">
      <h2 className="section-label" id="my-day-title">나의 하루</h2>
      <div className="summary-strip">
        <SummaryCard kind="due" sub={due.at ? `${longDate(due.at.slice(0, 10))} ${due.at.slice(11, 16)}까지` : "예정된 마감 없음"} group={due} unit="건" linkText="할 일 보기" />
        <SummaryCard kind="report" sub={firstReport ? `가장 빠른 마감 ${shortDate((firstReport.due || "").slice(0, 10))}` : "남은 과제 없음"} group={report} unit="건" linkText="과제 보기" />
        <SummaryCard kind="week" sub={`${week.week}주차`} group={week} unit="개" linkText="자료 보기" />
      </div>
    </section>
  );
}

function renderHome(ctx: ScreenCtx): ReactNode {
  const params = (ctx && ctx.params) || new URLSearchParams();
  // Backend `ready`: keep the server calendar and every imported course, including past terms.
  if (backendStatus() === "ready") {
    return (
      <section className="home-main" data-area="home">
        {serverHomeCalendar(params)}
        <h2 className="section-label">수강과목</h2>
        {serverHomeCourses()}
      </section>
    );
  }
  const enrolled = courses
    .filter((c) => c.kind === "regular" && c.enrolled && c.term === "2026-2")
    .sort((a, b) => COURSE_ORDER.indexOf(a.id) - COURSE_ORDER.indexOf(b.id));
  return (
    <section className="home-main" data-area="home">
      {myDay(ctx.state)}
      <h2 className="section-label">수강과목</h2>
      <p className="term">2학기</p>
      {/* Before sign-in, the login strip remains above the sample cards. */}
      {backendStatus() === "anon" ? homeLoginPanel(false) : null}
      {courseCards(enrolled, "home")}
    </section>
  );
}

function renderCourseList(screen: Screen, ctx: ScreenCtx): ReactNode {
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
  return (
    <section data-area="home">
      <div className="page-head">
        <div><h1>{title}</h1><p className="term tight">{label}</p></div>
        {pageSearch(ctx, scope)}
      </div>
      {backendStatus() === "anon" ? catalogLoginPanel() : null}
      {term === "special-enrolled"
        ? <div className="cards">{list.map(specialCard)}</div>
        : courseCards(list, "enrolled")}
      {term === "2026-2" ? <p className="term-link"><Link href={hrefFor(find("21b_regular_register_prev_semester"))}>2026년 1학기</Link></p> : null}
      {term === "special-enrolled" ? <p className="term-link"><Link href={hrefFor(find("22_nonregular_list"))}>비정규과목 전체</Link></p> : null}
    </section>
  );
}

function renderCatalog(screen: Screen, ctx: ScreenCtx): ReactNode {
  if (backendStatus() === "ready") return serverCatalog(screen, ctx);
  const kind = screen.catalog;
  let list = courses.filter((c) => c.kind === kind && !c.closed);
  if (screen.openOnly) list = list.filter((c) => c.open);
  if (screen.dept) list = list.filter((c) => /데이터|알고리즘|정보|UX|Data|Algorithm/.test(`${c.ko} ${c.en}`));
  const scope = `home:catalog:${kind}`;
  const q = readQuery(ctx.state, scope).trim();
  if (q) list = list.filter((c) => `${c.ko} ${c.en} ${c.instructor} ${c.code}`.includes(q));
  const title = kind === "special" ? "비정규과목 전체" : "정규과목 전체";
  return (
    <section data-area="home">
      <div className="page-head">
        <h1>{title}</h1>
        {pageSearch(ctx, scope)}
      </div>
      {backendStatus() === "anon" ? catalogLoginPanel() : null}
      <div className="filters">
        <Link href={hrefFor(find(kind === "special" ? "22_nonregular_list" : "20_regular_list"))} className={`chip${!screen.openOnly && !screen.dept ? " on all" : ""}`}>전체</Link>
        {kind === "special"
          ? <Link href={hrefFor(find("22b_nonregular_list_filter"))} className={`chip${screen.openOnly ? " on all" : ""}`}>모집 중</Link>
          : <Link href={hrefFor(find("20_regular_list_filter"))} className={`chip${screen.dept ? " on all" : ""}`}>산업정보</Link>}
        <span className="tiny filter-count">{`${list.length}과목`}</span>
      </div>
      {list.length
        ? (kind === "special" ? <div className="cards">{list.map(specialCard)}</div> : courseCards(list, "catalog"))
        : <EmptyState title="조건에 맞는 과목이 없습니다" body="필터를 바꾸거나 검색어를 지워 보세요." />}
    </section>
  );
}

function renderDetail(screen: Screen, ctx: ScreenCtx): ReactNode {
  if (backendStatus() === "ready") return serverCatalogDetail(screen, ctx);
  const picked = (ctx.params && ctx.params.get("id")) || screen.course || ctx.state.courseId;
  const course = courseById(picked);
  if (course.missing || course.id !== picked) {
    return <section data-area="home"><EmptyState title="과목을 찾을 수 없습니다" body="과목 목록에서 다시 선택해 주세요." /></section>;
  }
  const applied = Boolean(ctx.state.applications[course.id]) || course.enrolled;
  const recruiting = course.kind === "special" && (course.open || screen.id === "24b_nonregular_view_open");
  return (
    <section className="detail" data-area="home">
      {backendStatus() === "anon" ? catalogLoginPanel() : null}
      <div className={`detail-hero ${course.gradient}`}>
        <CoverImage course={course} width={960} height={220} />
        <p className="k">{course.kind === "special" ? "비정규과목" : termLabel(course.term)}</p>
        <h1>{course.ko}</h1>
        <p>{`${course.code} · ${course.instructor}`}</p>
        <div className="hero-pills">
          <span>{course.kind === "special" ? "비교과" : "정규"}</span>
          {recruiting ? <span>모집 중</span> : null}
          {applied ? <span>수강 중</span> : null}
        </div>
      </div>
      <div className="detail-grid">
        <div className="sheet">
          <h2>강의 소개</h2>
          <p>{`${course.instructor} 담당 과목입니다. 강의계획 원문은 넣지 않았고, 출석과 과제는 이 브라우저 안에서만 바뀝니다.`}</p>
          <h2>이수 정보</h2>
          <Table
            headers={["항목", "내용"]}
            rows={[
              ["담당", course.instructor],
              ["학점", `${course.credit}학점`],
              ["시간", course.time],
              ["강의실", course.room],
              ["정원", `${course.learners} / ${course.capacity || course.learners}`],
            ]}
          />
        </div>
        <div className="sheet side">
          <h2>{course.ko}</h2>
          <p className="tiny">{course.code}</p>
          <dl className="kv">
            <div><dt>운영</dt><dd>{course.kind === "special" ? "온라인" : "학기 과목"}</dd></div>
            <div><dt>기간</dt><dd>{course.kind === "special" ? "2026.09.01 – 2026.12.19" : termLabel(course.term)}</dd></div>
            <div><dt>상태</dt><dd>{applied ? "수강 중" : recruiting ? "모집 중" : "신청 마감"}</dd></div>
          </dl>
          <div className="btn-row">
            {applied
              ? <Link href={hrefFor(find(course.kind === "special" ? "70_nr_room_submain" : "40_room_submain"), { kj: course.id })} className="btn btn-primary">강의실 입장</Link>
              : course.kind === "special"
                ? <Link href={hrefFor(find("24c_nonregular_apply_pop"), { id: course.id })} className="btn btn-primary">수강 신청</Link>
                : <p className="tiny">이 학기 수강 과목이 아닙니다.</p>}
            <Link href={hrefFor(find(course.kind === "special" ? "22_nonregular_list" : "20_regular_list"))} className="btn btn-secondary">목록</Link>
          </div>
          {applied && !course.enrolled ? <p className="tiny">신청이 이 브라우저에 저장되었습니다.</p> : null}
        </div>
      </div>
    </section>
  );
}

const BOARD_TITLES: Record<string, string> = { global: "공지사항", qna: "질의응답", material: "자료실", survey: "설문" };

function renderCommunity(screen: Screen, ctx: ScreenCtx): ReactNode {
  // Backend mode: 알림마당 설문 comes from `GET /api/surveys` (areas/home/surveys-server.tsx).
  const serverSurveys = screen.board === "survey" ? serverCommunitySurveys(ctx) : null;
  if (serverSurveys) return serverSurveys;
  const audience = screen.audience || ((ctx.params && ctx.params.get("audience")) || "");
  let rows = boardRows({ ...screen, audience: audience || undefined }, ctx.state);
  const scope = `home:board:${screen.board || "global"}`;
  const q = readQuery(ctx.state, scope).trim();
  if (q) rows = rows.filter((row) => `${row.title} ${row.body || ""} ${row.author || ""}`.includes(q));
  const title = (screen.board && BOARD_TITLES[screen.board]) || screen.title;
  const head = (
    <div className="page-head">
      <h1>{title}</h1>
      {screen.board === "qna"
        ? (
          <div className="board-tools">
            <Link href={hrefFor(find("31c_supporters_pop"))} className="btn btn-secondary">서포터즈</Link>
            <Link href={hrefFor(find("31b_qna_insert_pop"))} className="btn btn-primary">1:1 질문하기</Link>
          </div>
        )
        : null}
    </div>
  );
  // Backend mode: 자료실 and 1:1 문의 come from the server (areas/home/community-server.tsx).
  if ((screen.board === "material" || screen.board === "qna") && backendStatus() !== "off") return remoteCommunityBoard(screen, ctx, head);
  // Backend mode: 알림마당 공지사항 comes from the server (areas/home/site-notices-server.tsx).
  if (screen.board === "global" && backendStatus() !== "off") return serverSiteNotices(screen, ctx, head);
  let counts: ReactNode;
  if (screen.board === "global") {
    const all = boardRows({ ...screen, audience: undefined }, ctx.state);
    const studentN = all.filter((row) => row.audience === "student" || row.audience === "all").length;
    const profN = all.filter((row) => row.audience === "professor" || row.audience === "all").length;
    const listHref = hrefFor(find("30_community_notice_list"));
    counts = (
      <div className="filters">
        <Link href={listHref} className={`chip${!audience ? " on all" : ""}`}>{`전체 ${all.length}`}</Link>
        <Link href={`${listHref}?audience=student`} className={`chip${audience === "student" ? " on all" : ""}`}>{`학생 ${studentN}`}</Link>
        <Link href={`${listHref}?audience=professor`} className={`chip${audience === "professor" ? " on all" : ""}`}>{`교수 ${profN}`}</Link>
      </div>
    );
  } else {
    counts = <p className="board-count">{`전체 ${rows.length}`}</p>;
  }
  return (
    <section className="board" data-area="home">
      {head}
      {counts}
      <label className="search board-search">
        <Icon name="search" />
        <input
          value={readQuery(ctx.state, scope)}
          onChange={(event) => {
            writeQuery(ctx.state, scope, event.target.value);
            repaint();
          }}
          placeholder="검색"
          aria-label={`${title} 검색`}
        />
      </label>
      {rows.length
        ? (
          <div className="board-list">
            {rows.map((row) => {
              let href = hrefFor(find("47_room_open_board_list"));
              if (screen.board === "global") href = hrefFor(find("06_pop_notice_view"), { id: row.id });
              else if (screen.board === "survey") href = hrefFor(find("33b_survey_view_pop"), { id: row.id });
              else if (screen.board === "qna") href = hrefFor(find("31b_qna_insert_pop"));
              return (
                <Link key={row.id} className="board-card" href={href}>
                  <b>{row.title}</b>
                  <span className="tiny">{[row.author, row.date || row.at].filter(Boolean).join(" · ")}</span>
                  {row.body ? <p>{row.body}</p> : null}
                </Link>
              );
            })}
          </div>
        )
        : <EmptyState title="조회된 자료가 없습니다" body={screen.board === "qna" ? "1:1 질문하기를 누르면 이 목록에 남습니다." : "등록된 글이 없습니다."} />}
    </section>
  );
}

function renderSitemap(ctx: ScreenCtx): ReactNode {
  const professor = ctx.state.role === "professor";
  const groups: [string, Screen[]][] = professor
    ? [["교수", screens.filter((s) => s.role === "professor")]]
    : [
      ["학생", screens.filter((s) => s.role === "student" && s.layout !== "mobile")],
      ["모바일", screens.filter((s) => s.layout === "mobile")],
    ];
  return (
    <section data-area="home">
      <h1>전체 화면</h1>
      <p className="subhead">화면 이름으로 이동합니다. 밝은 화면은 기기 설정이 라이트일 때만 열립니다.</p>
      {groups.map(([title, list]) => (
        <div key={title}>
          <h2 className="section-label">{title}</h2>
          <div className="sitemap">
            {list.map((s) => {
              const href = professor ? routeFor("professor", "screen", { screenId: s.id, ...(s.course ? { kj: s.course } : {}) }) : hrefFor(s);
              return <Link key={s.id} href={href}><b>{s.title}</b><small>{s.id}</small></Link>;
            })}
          </div>
        </div>
      ))}
    </section>
  );
}

function LogoutView() {
  const status = useSyncExternalStore(onBackendChange, backendStatus);
  // Claim a server logout only on the entry pushed after one the server confirmed, and never while signed in
  // (back to this entry after signing in again, or the address opened directly).
  const text = status === "ready" ? "ASTRA 서버에 로그인되어 있습니다. 로그아웃하려면 내 메뉴의 로그아웃을 누르세요."
    : serverLogoutConfirmed() ? "ASTRA 서버에서 로그아웃했습니다. 다시 쓰려면 홈에서 로그인하세요."
    : status === "off" ? "샘플 세션을 닫았습니다. 역할 화면으로 다시 들어가지 않습니다."
    : "ASTRA 서버에 로그인되어 있지 않습니다.";
  return (
    <section data-area="home">
      <h1>로그아웃</h1>
      <p className="subhead">{text}</p>
    </section>
  );
}

function koDate(iso: string): string {
  const date = parseISO(iso);
  return `${date.getMonth() + 1}월 ${date.getDate()}일 (${DOW[date.getDay()]})`;
}

const ASIDE_TODOS = ["lec-11-1", "lec-11-2", "rep-project", "quiz-final"];

function TodoLink({ item }: { item: Activity }) {
  const course = courseById(item.courseId);
  return (
    <Link className="aside-card todo-card todo-link" href={activityHref(item)} data-item={item.id} data-todo={item.type}>
      <div>
        <div className="todo-k">{typeLabel(item.type)}</div>
        <b>{item.title}</b>
        <div className="tiny">{`${course.cardTitle || course.ko} (${course.code})`}</div>
        <div className="tiny">{item.whenLabel || (item.due ? `마감 ${item.due}` : "진행 중")}</div>
      </div>
      <span className="due">{dday(item.due) || "진행"}</span>
    </Link>
  );
}

/** The student home's right column: today's classes and to-dos. */
export function homeAside(_ctx: ScreenCtx): ReactNode {
  // Backend `ready`: today's classes and to-dos are server facts, never school sample activities.
  if (backendStatus() === "ready") {
    return (
      <>
        {serverTodayClasses()}
        <Link href={hrefFor(find("05_pop_lectime"))} className="btn btn-secondary block">수업시간표</Link>
        {serverAsideTodo(hrefFor(find("09_home_todo_more")))}
      </>
    );
  }
  const web = courseById("algo");
  const todos = ASIDE_TODOS.map((id) => activities.find((row) => row.id === id)).filter((item): item is Activity => Boolean(item));
  return (
    <>
      <div className="aside-h">
        <h2>오늘 수업</h2>
        <span className="tiny">{koDate(TODAY)}</span>
      </div>
      <article className="aside-card today-class">
        <b>{web.ko}</b>
        <div className="tiny">{`(${web.code})`}</div>
        <div className="time-row"><i className="dot" /><span>{web.clock || web.time}</span></div>
        <div className="tiny">{web.room || "-"}</div>
      </article>
      <Link href={hrefFor(find("05_pop_lectime"))} className="btn btn-secondary block">수업시간표</Link>
      <div className="aside-h">
        <h2>할일</h2>
        <span className="count-num">{String(openTodos().length)}</span>
      </div>
      {todos.map((item) => <TodoLink key={item.id} item={item} />)}
      <Link href={hrefFor(find("09_home_todo_more"))} className="more-link">모두 보기</Link>
    </>
  );
}

/** Home-area screens. Each screen root carries `data-area="home"` (other modules' views set it on theirs). */
export function render(screen: Screen, ctx: ScreenCtx): ReactNode {
  switch (screen.kind) {
    case "home": return renderHome(ctx);
    case "course-list": return renderCourseList(screen, ctx);
    case "catalog": return renderCatalog(screen, ctx);
    case "detail": return renderDetail(screen, ctx);
    case "board": return renderCommunity(screen, ctx);
    case "study":
      if (screen.mode === "summary") return sampleSummary(ctx);
      return ctx.params && ctx.params.has("kj") ? renderCourseStudy(ctx) : studyRoot(ctx);
    case "lecture": return renderRecording(ctx);
    case "sitemap": return renderSitemap(ctx);
    case "logout": return <LogoutView />;
    default: return <section data-area="home"><EmptyState title="이 화면은 홈 영역이 아닙니다" body={screen.id} /></section>;
  }
}
