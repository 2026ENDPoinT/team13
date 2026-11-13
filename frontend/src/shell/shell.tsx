import { useEffect, useReducer, type ReactNode } from "react";
import { Icon } from "../ui/icon.tsx";
import { BrandLogo } from "../ui/assets.tsx";
import { courseById, student, professor, type AppState, type Course } from "../data.ts";
import { hrefFor, find } from "../manifest.ts";
import { GoButton, Link } from "../router.tsx";
import { routeFor } from "../shared/routes.ts";
import { homeNav, infoNav } from "../areas/home/screens.ts";
import { courseNav, specialCourseNav } from "../areas/course/screens.ts";
import { professorHomeNav, professorCourseNav } from "../areas/professor/screens.ts";
import { AssistantTray } from "../shared/assistant-tray.tsx";
import { ChatDock } from "../areas/home/chat-dock.tsx";
import { collapseHero } from "../areas/home/home.tsx";
import { useBadge, unreadLabel, type UnreadKind } from "../shared/unread-badges.tsx";
import { backendState, backendStatus, serverCourses, serverCourseOf } from "../shared/backend.ts";
// Also puts the server course views behind `courseById` (data.ts) before the first render.
import "../shared/course-view.ts";
import { profiles, peekAccount, reloadAccount } from "../shared/account-remote.ts";
import { personAvatar } from "../shared/avatar-remote.tsx";
import { getState, render } from "../store.ts";
import type { NavItem, Role, Screen, ScreenCtx } from "../types.ts";
import { courseWeeks, courseKey } from "../shared/lectures-remote.ts";
import { useResource } from "../shared/remote-cache.ts";

const copy: Record<"ko" | "en", Record<string, string>> = {
  en: {
    home: "Home", regular: "Courses", special: "Special", info: "Notices", study: "Study",
    classroom: "Classroom", attendance: "Attendance", groups: "Groups", grades: "Grades",
    announce: "Notices", qna: "Q&A", files: "Files", board: "Board", activity: "Activities",
    plan: "Syllabus",
  },
  ko: {
    home: "홈", regular: "정규과목", special: "비정규과목", info: "알림마당", study: "학습실",
    classroom: "강의실", attendance: "출석", groups: "그룹활동", grades: "성적",
    announce: "공지사항", qna: "질의응답", files: "파일모음", board: "열린게시판", activity: "학습활동",
    plan: "강의계획서",
  },
};

function CountBadge({ count }: { count: number | null }) {
  const shown = Number(count) || 0;
  return shown > 0 ? <span className="badge-count">{shown > 99 ? "99" : String(shown)}</span> : null;
}

function NavLink({ screen, item, lang, course, role }: { screen: Screen; item: NavItem; lang: "ko" | "en"; course: Course; role: Role }) {
  const target = find(item.screenId);
  const extra: Record<string, string> = { screenId: item.screenId };
  if (target.course || target.layout === "course" || target.layout === "prof") extra.kj = course.id;
  const href = routeFor(role, "screen", extra);
  const key = item.labelKey || item.nav || "";
  const label = item.label || copy[lang][key] || key;
  const active = screen.nav === item.nav;
  const ready = backendStatus() === "ready";
  const remote = ready ? serverCourseOf(course.id) : null;
  const weeksKey = remote && item.nav === "classroom" ? courseKey(remote.courseId) : null;
  const weeks = useResource(courseWeeks, weeksKey);
  useEffect(() => {
    if (weeksKey && remote) void courseWeeks.visit("shell:weeks", weeksKey, remote.courseId);
  }, [weeksKey, remote?.courseId]);
  const count = !ready ? item.count
    : item.nav === "classroom" ? weeks?.data?.weeks?.length
      : item.nav === "announce" ? remote?.unreadNotices : null;
  return (
    <Link
      className={`nav-link${active ? " active" : ""}${screen.hoverNav === item.nav ? " hover-demo" : ""}`}
      href={href}
      resetScroll
      aria-current={active ? "page" : undefined}
    >
      <span className="nav-text">{label}</span>
      {count != null && Number(count) > 0 ? <span className="nav-count">{count}</span> : null}
    </Link>
  );
}

const SCHOOL_LINKS = [
  ["e-class", "https://eclass.seoultech.ac.kr"],
  ["학생포털 · 통합정보", "https://stportal.seoultech.ac.kr"],
  ["중앙도서관", "https://library.seoultech.ac.kr"],
  ["학교 홈페이지", "https://www.seoultech.ac.kr"],
] as const;

/** External school services; each opens in a new tab, which its accessible name says. */
function SchoolLinks() {
  return (
    <>
      {SCHOOL_LINKS.map(([label, href]) => (
        <a key={href} className="nav-link ext" href={href} target="_blank" rel="noopener noreferrer" aria-label={`${label} (새 탭)`}>
          <span className="nav-text">{label}</span>
          <Icon name="ext" />
        </a>
      ))}
    </>
  );
}

/** My profile when the backend is `ready` and it was read for the signed-in user, else null. */
function ownProfile() {
  const entry = backendStatus() === "ready" ? peekAccount(profiles) : null;
  const profile = entry && entry.data;
  const me = backendState().me;
  return profile && me && String(profile.userId) === String(me.userId) ? profile : null;
}

/**
 * 머리말의 내 아바타. 서버에 로그인했고 내 프로필을 읽었으면 그 사진(없으면 서버 색과 약칭)이고, 그 밖에는
 * 견본 글자 그대로다. 크기는 같은 `avatar` 상자가 정한다.
 */
function HeaderAvatar({ state }: { state: AppState }) {
  const profile = ownProfile();
  const me = backendState().me;
  if (!profile) {
    if (backendStatus() === "ready" && me) {
      return personAvatar({ userId: me.userId, name: me.name, className: "avatar", photo: false });
    }
    return <span className="avatar">{(state.draft.avatar || (state.role === "professor" ? professor.avatar : student.avatar)).slice(0, 2)}</span>;
  }
  const avatar = profile.avatar || {};
  return personAvatar({
    userId: profile.userId,
    name: profile.name,
    color: avatar.color,
    shortName: avatar.shortName,
    className: "avatar",
    version: avatar.version,
    photo: avatar.type === "image",
  });
}

/**
 * 프로필을 아직 읽지 않았으면 세션마다 한 번 읽고, 읽히면 머리말을 다시 그린다. 실패하면 서버 계정의 약칭을 둔다.
 * 캐시는 세션이 바뀌면 비워진다.
 */
function useProfileLoad() {
  const [, repaint] = useReducer((n: number) => n + 1, 0);
  const ready = backendStatus() === "ready";
  useEffect(() => {
    if (!ready) return;
    const entry = peekAccount(profiles);
    if (entry && !entry.loading) return;
    let live = true;
    reloadAccount(profiles).then(() => {
      if (live) repaint();
    });
    return () => {
      live = false;
    };
  }, [ready]);
}

/**
 * 왼쪽 메뉴 아래의 학기와 소속. 서버에 로그인했으면 학기는 내 서버 과목의 이번 학기(`term.current`, 없으면 첫 과목의
 * 학기)이고 소속·학년은 내 프로필(`GET /api/me/profile`)이다. 읽기 전이나 없는 값은 비워 둔다. 그 밖에는 견본 그대로다.
 */
function RailTerm({ chromeRole, course }: { chromeRole: Role; course: Course | null }) {
  if (backendStatus() !== "ready") {
    return (
      <div className="rail-term">
        <strong>2026학년도 2학기</strong>
        <span>{chromeRole === "professor" ? professor.dept : `${student.dept} ${student.year}`}</span>
      </div>
    );
  }
  const mine = serverCourses().map(({ remote }) => remote);
  const term = course ? serverCourseOf(course.id)?.term
    : (mine.find((remote) => remote.term && remote.term.current) || mine.find((remote) => remote.term))?.term;
  const own = ownProfile();
  const grade = own && Number.isInteger(own.grade) && chromeRole !== "professor" ? `${own.grade}학년` : "";
  return (
    <div className="rail-term">
      {term && term.name ? <strong>{term.name}</strong> : null}
      <span>{[own && own.departmentName, grade].filter(Boolean).join(" ")}</span>
    </div>
  );
}

function HeaderCount({ href, kind, label, icon }: { href: string; kind: UnreadKind; label: string; icon: string }) {
  const count = useBadge(kind);
  return (
    <GoButton className="icon-btn" href={href} aria-label={unreadLabel(label, count)}>
      <Icon name={icon} />
      <CountBadge count={count} />
    </GoButton>
  );
}

function toggleNav() {
  const state = getState();
  state.navOpen = !state.navOpen;
  render();
}

const INFO_BOARD: Record<string, string> = {
  "30_community_notice_list": "global",
  "31_community_qna_list": "qna",
  "32_community_material_list": "material",
  "33_community_survey_list": "survey",
};

function SideNav({ screen, chromeRole, course, lang }: { screen: Screen; chromeRole: Role; course: Course; lang: "ko" | "en" }) {
  const homeish = ["home", "contents", "bank", "regular", "info", "study", "calendar"].includes(screen.nav);
  const isCourse = screen.layout === "course" || screen.layout === "prof";
  const link = (item: NavItem, role: Role) => <NavLink key={item.screenId} screen={screen} item={item} lang={lang} course={course} role={role} />;
  let links: ReactNode;
  if (chromeRole === "professor" && screen.layout === "prof" && screen.kind !== "pf-main" && !homeish) {
    links = <><div className="nav-label">강의실</div>{professorCourseNav.map((item) => link(item, chromeRole))}</>;
  } else if (chromeRole === "professor" && (screen.kind === "pf-main" || homeish || screen.layout === "prof")) {
    links = <><div className="nav-label">교수</div>{professorHomeNav.map((item) => link(item, chromeRole))}</>;
  } else if (isCourse && screen.layout === "course") {
    const items = course.kind === "special" ? specialCourseNav : courseNav;
    links = <><div className="nav-label">강의실</div>{items.map((item) => link(item, "student"))}</>;
  } else {
    links = (
      <>
        <div className="nav-label">메뉴</div>
        {homeNav.map((item) => link(item, "student"))}
        <div className="nav-links nav-shortcuts" role="group" aria-label="바로가기">
          <div className="nav-label">바로가기</div>
          {screen.nav === "info" ? infoNav.map((item) => {
            const href = hrefFor(find(item.screenId));
            const active = screen.kind === "board" && screen.board === INFO_BOARD[item.screenId];
            return (
              <Link key={item.screenId} className={`nav-link sub${active ? " active" : ""}`} href={href} resetScroll aria-current={active ? "page" : undefined}>
                <span className="nav-text">{item.label}</span>
              </Link>
            );
          }) : null}
          <SchoolLinks />
        </div>
      </>
    );
  }
  return (
    <nav className="sidenav" aria-label="메뉴">
      <div className="nav-links">{links}</div>
      <div className="quick-menu"><RailTerm chromeRole={chromeRole} course={isCourse ? course : null} /></div>
    </nav>
  );
}

export interface ShellProps {
  ctx: ScreenCtx;
  /** Full-width block above the columns (student home). */
  hero?: ReactNode;
  /** Right column (home, classroom, mobile); called only when the layout shows one. */
  aside?: () => ReactNode;
  /** Windows over the page (`?pop=`). */
  dialog?: ReactNode;
  children: ReactNode;
}

/** The page frame: top bar, side menu, main slot, aside, and the assistant tray. */
export function Shell({ ctx, hero, aside, dialog, children }: ShellProps) {
  const { state, screen } = ctx;
  const lang = state.lang === "en" ? "en" : "ko";
  const chromeRole: Role = screen.role === "any" ? state.role : screen.role;
  const course = courseById(ctx.params.get("kj") || state.courseId);
  const showAside = screen.kind === "home" || screen.kind === "room" || screen.kind === "mobile";
  const collapsed = screen.aside === "0";
  // 녹음 화면과 학습실 자료 요약(LessonViewer)은 좌측 메뉴 없이 본문만 쓴다.
  const noNav = screen.kind === "lecture" || (screen.kind === "study" && screen.mode === "summary");
  const isCourse = screen.layout === "course" || screen.layout === "prof";
  const navOpen = state.navOpen || screen.mobileMenu === "nav";
  // Student home opens with a full-width hero above the menu, main column, and aside.
  const hasHero = screen.kind === "home";
  useProfileLoad();
  const bodyClass = [
    "body",
    isCourse ? (screen.layout === "prof" ? "prof" : "course") : "",
    showAside && !collapsed ? "" : "no-aside",
    hasHero ? "has-hero" : "",
    navOpen ? "nav-open" : "",
    screen.mobileMenu === "aside" ? "show-aside" : "",
  ].filter(Boolean).join(" ");
  return (
    <div className={noNav ? "chrome no-nav" : "chrome"}>
      <header className="topbar">
        <div className="top-start">
          <button type="button" className="icon-btn menu-toggle" aria-label={navOpen ? "메뉴 닫기" : "메뉴"} aria-expanded={navOpen ? "true" : "false"} onClick={toggleNav}>
            <Icon name="menu" />
          </button>
          <Link className="brand" href={routeFor(chromeRole, "home")} aria-label="STack 홈" onClick={(event) => collapseHero(event, hasHero)}>
            <BrandLogo />
            <span className="word"><span className="brand-name">STack</span></span>
          </Link>
        </div>
        {isCourse ? (
          <GoButton className="course-switch" href={routeFor(chromeRole, screen.layout === "prof" ? "classroom" : "switch", { kj: course.id })}>
            <span>{course.code ? `${course.en || course.ko} (${course.code})` : course.en || course.ko}</span>
            <Icon name="chev" />
          </GoButton>
        ) : <span className="course-switch" aria-hidden="true" />}
        <div className="top-actions">
          <HeaderCount href={routeFor(chromeRole, "todo")} kind="todo" label="할 일" icon="calendar" />
          <HeaderCount href={routeFor(chromeRole, "message")} kind="messages" label="쪽지" icon="mail" />
          <HeaderCount href={routeFor(chromeRole, "notify")} kind="notifications" label="알림" icon="bell" />
          <GoButton className="profile-btn" href={routeFor(chromeRole, "profile")} aria-label="내 메뉴">
            <HeaderAvatar state={state} />
            <Icon name="caret" />
          </GoButton>
        </div>
      </header>
      <div className={bodyClass}>
        {hasHero ? <div className="hero-slot">{hero}</div> : null}
        {noNav ? null : <SideNav screen={screen} chromeRole={chromeRole} course={course} lang={lang} />}
        {noNav ? null : <button type="button" className="scrim" aria-label="메뉴 닫기" onClick={toggleNav} />}
        <div className="slot">{children}</div>
        {(showAside && !collapsed) || screen.mobileMenu === "aside" ? <aside className="aside">{aside?.()}</aside> : null}
      </div>
      <AssistantTray screen={screen} dock={<ChatDock />} />
      <div className="dialog-root">{dialog}</div>
    </div>
  );
}

