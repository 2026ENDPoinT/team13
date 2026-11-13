import { btn, field } from "../ui/components.js";
import { el, icon } from "../dom.js";
import { brandLogo, fabArt } from "../ui/assets.js";
import { courseById, student, professor } from "../data.js";
import { hrefFor, find } from "../manifest.js";
import { routeFor } from "../shared/routes.js";
import { openTodos } from "../shared/format.js";
import { homeNav, infoNav } from "../areas/home/screens.js";
import { courseNav, specialCourseNav } from "../areas/course/screens.js";
import { professorHomeNav, professorCourseNav } from "../areas/professor/screens.js";
import { buildTray } from "../shared/assistant-tray.js";
import { observeScreen } from "../shared/assistant/plan.js";
import { bindUnread } from "../shared/unread-badges.js";
import { serverTutorPanel } from "../shared/study-chat-panel.js";
import { backendState, backendStatus, serverCourses } from "../shared/backend.js";
// Also puts the server course views behind `courseById` (data.js) before the first render.
import "../shared/course-view.js";
import { profiles, peekAccount, reloadAccount } from "../shared/account-remote.js";
import { personAvatar } from "../shared/avatar-remote.js";

const copy = {
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

function t(lang, key) {
  return (copy[lang] || copy.ko)[key] || key;
}

function countBadge(n) {
  const count = Number(n) || 0;
  if (count <= 0) return null;
  return el("span", { class: "badge-count", text: count > 99 ? "99" : String(count) });
}

export function technyonMode(screen) {
  if (!screen) return "tutor";
  if (screen.layout === "mobile" || screen.kind === "home" || screen.kind === "course-list" || screen.kind === "catalog" || screen.kind === "sitemap") return "programs";
  return "tutor";
}

function navLink(screen, item, lang, course, role) {
  const target = find(item.screenId);
  const extra = { screenId: item.screenId };
  if (item.courseId) extra.kj = item.courseId;
  else if (course && (target.course || target.layout === "course" || target.layout === "prof")) extra.kj = course.id;
  const href = routeFor(role, "screen", extra);
  const label = item.label || t(lang, item.labelKey || item.nav);
  const active = screen.nav === item.nav;
  const link = el("a", {
    class: `nav-link${active ? " active" : ""}${screen.hoverNav === item.nav ? " hover-demo" : ""}`,
    href,
    "data-go": href,
    "aria-current": active ? "page" : null,
  }, el("span", { class: "nav-text", text: label }));
  if (item.count) link.append(el("span", { class: "nav-count", text: item.count }));
  return link;
}

const SCHOOL_LINKS = [
  ["e-class", "https://eclass.seoultech.ac.kr"],
  ["학생포털 · 통합정보", "https://stportal.seoultech.ac.kr"],
  ["중앙도서관", "https://library.seoultech.ac.kr"],
  ["학교 홈페이지", "https://www.seoultech.ac.kr"],
];

/** External school services (open in a new tab), followed by a one-line notice. */
function schoolLinks() {
  return [
    ...SCHOOL_LINKS.map(([label, href]) => el("a", {
      class: "nav-link ext", href, target: "_blank", rel: "noopener noreferrer",
      "aria-label": `${label} (새 탭)`,
    }, el("span", { class: "nav-text", text: label }), icon("ext"))),
    el("p", { class: "nav-note", text: "학교 서비스는 새 탭에서 열립니다." }),
  ];
}

/**
 * 머리말의 내 아바타. 서버에 로그인했고 내 프로필을 읽었으면 그 사진(없으면 서버 색과 약칭)이고, 그 밖에는
 * 견본 글자 그대로다. 크기는 같은 `avatar` 상자가 정한다.
 */
function headerAvatar(state) {
  const entry = backendStatus() === "ready" ? peekAccount(profiles) : null;
  const profile = entry && entry.data;
  const me = backendState().me;
  if (!profile || !me || String(profile.userId) !== String(me.userId)) {
    if (backendStatus() === "ready" && me) {
      return personAvatar({ userId: me.userId, name: me.name, className: "avatar", photo: false });
    }
    return el("span", { class: "avatar", text: (state.draft.avatar || (state.role === "professor" ? professor.avatar : student.avatar)).slice(0, 2) });
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
 * 프로필을 아직 읽지 않았으면 세션마다 한 번 읽고, 읽히면 단추 안의 아바타만 바꾼다. 실패하면 견본 글자를 둔 채
 * 다시 묻지 않는다(캐시는 세션이 바뀌면 비워진다).
 */
function loadHeaderAvatar(button, state, rail, chromeRole) {
  if (backendStatus() !== "ready") return;
  const entry = peekAccount(profiles);
  if (entry && !entry.loading) return;
  reloadAccount(profiles).then(() => {
    const current = button.querySelector(".avatar");
    if (button.isConnected && current) current.replaceWith(headerAvatar(state));
    if (rail.isConnected) paintRailTerm(rail, chromeRole);
  });
}

/**
 * 왼쪽 메뉴 아래의 학기와 소속. 서버에 로그인했으면 학기는 내 서버 과목의 이번 학기(`term.current`, 없으면 첫 과목의
 * 학기)이고 소속·학년은 내 프로필(`GET /api/me/profile`)이다. 읽기 전이나 없는 값은 비워 둔다. 그 밖에는 견본 그대로다.
 */
function paintRailTerm(rail, chromeRole) {
  if (backendStatus() !== "ready") {
    const person = chromeRole === "professor" ? professor : student;
    rail.replaceChildren(
      el("strong", { text: "2026학년도 2학기" }),
      el("span", { text: chromeRole === "professor" ? person.dept : `${person.dept} ${person.year}` }),
    );
    return;
  }
  const mine = serverCourses().map(({ remote }) => remote);
  const term = (mine.find((remote) => remote.term && remote.term.current) || mine.find((remote) => remote.term) || {}).term;
  const entry = peekAccount(profiles);
  const profile = entry && entry.data;
  const me = backendState().me;
  const own = profile && me && String(profile.userId) === String(me.userId) ? profile : null;
  const grade = own && Number.isInteger(own.grade) && chromeRole !== "professor" ? `${own.grade}학년` : "";
  rail.replaceChildren(
    term && term.name ? el("strong", { text: term.name }) : "",
    el("span", { text: [own && own.departmentName, grade].filter(Boolean).join(" ") }),
  );
}

export function buildChrome(screen, ctx) {
  const { state } = ctx;
  const lang = state.lang === "en" ? "en" : "ko";
  const chromeRole = screen.role === "any" ? state.role : screen.role;
  const course = courseById(state.courseId);
  const slot = el("div", { class: "slot" });
  const dialogs = el("div", { class: "dialog-root" });
  const showAside = screen.kind === "home" || screen.kind === "room" || screen.kind === "mobile";
  const collapsed = screen.aside === "0";
  const isCourse = screen.layout === "course" || screen.layout === "prof";
  const navOpen = state.navOpen || screen.mobileMenu === "nav";
  // Student home opens with a full-width hero above the menu, main column, and aside.
  const hero = screen.kind === "home" ? el("div", { class: "hero-slot" }) : null;
  const bodyClass = [
    "body",
    isCourse ? (screen.layout === "prof" ? "prof" : "course") : "",
    showAside && !collapsed ? "" : "no-aside",
    hero ? "has-hero" : "",
    navOpen ? "nav-open" : "",
    screen.mobileMenu === "aside" ? "show-aside" : "",
  ].filter(Boolean).join(" ");

  const homeHref = routeFor(chromeRole, "home");
  const brand = el("a", { class: "brand", href: homeHref, "data-go": homeHref, "aria-label": "STack 홈" },
    brandLogo(),
    el("span", { class: "word" }, el("span", { class: "brand-name", text: "STack" })),
  );
  const header = el("header", { class: "topbar" },
    el("div", { class: "top-start" },
      el("button", {
        type: "button", class: "icon-btn menu-toggle", "data-act": "nav",
        "aria-label": navOpen ? "메뉴 닫기" : "메뉴", "aria-expanded": navOpen ? "true" : "false",
      }, icon("menu")),
      brand,
    ),
  );
  if (isCourse) {
    header.append(el("button", {
      type: "button", class: "course-switch",
      "data-go": routeFor(chromeRole, screen.layout === "prof" ? "classroom" : "switch", { kj: course.id }),
    }, el("span", { text: course.code ? `${course.en || course.ko} (${course.code})` : course.en || course.ko }), icon("chev")));
  } else header.append(el("span", { class: "course-switch", "aria-hidden": "true" }));
  const todoCount = openTodos().length;
  const profileBtn = el("button", { type: "button", class: "profile-btn", "data-go": routeFor(chromeRole, "profile"), "aria-label": "내 메뉴" },
    headerAvatar(state),
    icon("caret"));
  const railTerm = el("div", { class: "rail-term" });
  paintRailTerm(railTerm, chromeRole);
  loadHeaderAvatar(profileBtn, state, railTerm, chromeRole);
  header.append(el("div", { class: "top-actions" },
    bindUnread(el("button", { type: "button", class: "icon-btn", "data-go": routeFor(chromeRole, "todo"), "aria-label": `할 일 ${todoCount}건` }, icon("calendar"), countBadge(todoCount)), "todo", "할 일", todoCount),
    bindUnread(el("button", { type: "button", class: "icon-btn", "data-go": routeFor(chromeRole, "message"), "aria-label": "쪽지 3건" }, icon("mail"), countBadge(3)), "messages", "쪽지", 3),
    bindUnread(el("button", { type: "button", class: "icon-btn", "data-go": routeFor(chromeRole, "notify"), "aria-label": "알림 99건" }, icon("bell"), countBadge(99)), "notifications", "알림", 99),
    profileBtn,
  ));

  const links = el("div", { class: "nav-links" });
  const homeish = ["home", "contents", "bank", "regular", "info", "study", "calendar"].includes(screen.nav);
  const profCourse = chromeRole === "professor" && screen.layout === "prof" && screen.kind !== "pf-main" && !homeish;
  let shortcuts = null;
  if (profCourse) {
    links.append(el("div", { class: "nav-label", text: "강의실" }));
    for (const item of professorCourseNav) links.append(navLink(screen, item, lang, course, chromeRole));
  } else if (chromeRole === "professor" && (screen.kind === "pf-main" || homeish || screen.layout === "prof")) {
    links.append(el("div", { class: "nav-label", text: "교수" }));
    for (const item of professorHomeNav) links.append(navLink(screen, item, lang, course, chromeRole));
  } else if (isCourse && screen.layout === "course") {
    const items = course.kind === "special" ? specialCourseNav : courseNav;
    links.append(el("div", { class: "nav-label", text: "강의실" }));
    for (const item of items) links.append(navLink(screen, item, lang, course, "student"));
  } else {
    links.append(el("div", { class: "nav-label", text: "메뉴" }));
    for (const item of homeNav) links.append(navLink(screen, item, lang, course, "student"));
    shortcuts = el("div", { class: "nav-links nav-shortcuts", role: "group", "aria-label": "바로가기" },
      el("div", { class: "nav-label", text: "바로가기" }),
    );
    links.append(shortcuts);
    if (screen.nav === "info") {
      const infoBoard = {
        "30_community_notice_list": "global",
        "31_community_qna_list": "qna",
        "32_community_material_list": "material",
        "33_community_survey_list": "survey",
      };
      for (const item of infoNav) {
        const href = hrefFor(find(item.screenId));
        const active = screen.kind === "board" && screen.board === infoBoard[item.screenId];
        shortcuts.append(el("a", {
          class: `nav-link sub${active ? " active" : ""}`,
          href,
          "data-go": href,
          "aria-current": active ? "page" : null,
        }, el("span", { class: "nav-text", text: item.label })));
      }
    }
    shortcuts.append(...schoolLinks());
  }
  const sidenav = el("nav", { class: "sidenav", "aria-label": "메뉴" }, links);
  sidenav.append(el("div", { class: "quick-menu" }, railTerm));

  const aside = el("aside", { class: "aside" });
  const scrim = el("button", { type: "button", class: "scrim", "data-act": "nav", "aria-label": "메뉴 닫기" });
  // 계획 중인 단계와 다른 화면(다른 활동·다른 페이지)이면 계획이 스스로 일시정지한다. 트레이를 만들기 전에 해야 상태가 맞다.
  observeScreen((ctx.params && ctx.params.get("n")) || null);
  // Main keeps its Plan tray; the server-material tutor remains reachable only in backend mode.
  const serverFile = backendStatus() !== "off" && /^srv-[1-9][0-9]{0,17}$/.test(ctx.params?.get(screen.kind === "preview" ? "n" : "doc") || "");
  const tutorOpen = serverFile && Boolean(state.technyonOpen);
  const page = el("div", { class: "chrome" },
    header,
    el("div", { class: bodyClass }, hero, sidenav, scrim, slot, showAside && !collapsed ? aside : null),
    buildTray(screen),
    tutorOpen ? techPanel("tutor", ctx) : null,
    serverFile ? el("button", {
      type: "button", class: `fab${tutorOpen ? " open" : ""}`, "data-act": "technyon",
      "aria-label": tutorOpen ? "테크뇽 닫기" : "테크뇽", "aria-expanded": tutorOpen ? "true" : "false",
    }, tutorOpen ? el("span", { text: "×" }) : fabArt()) : null,
    dialogs,
  );
  if (screen.mobileMenu === "aside") page.querySelector(".body").append(aside);
  return { page, slot, aside, hero, dialogs, course, lang, chromeRole };
}

function techPanel(mode, ctx) {
  const title = mode === "programs" ? "프로그램 추천" : "학습 도우미";
  const sub = mode === "programs" ? "학생 프로그램 추천" : "자료 질문을 이 브라우저 안에서만 남깁니다";
  const body = el("div", { class: "tech-b" });
  // Backend mode on a server material: the real study chat (shared/study-chat-panel.js); null keeps the mock.
  const server = mode === "programs" ? null : serverTutorPanel(ctx);
  if (mode === "programs") {
    const programs = [
      ["IT 기업 채용 설명회", "학과·학년과 신청 기간을 기준으로 골랐습니다.", "모집 내용 보기"],
      ["백엔드 실무 프로젝트", "비교과 과정. 샘플 안내이며 실제 신청은 되지 않습니다.", "과정 안내 보기"],
      ["학생 소프트웨어 지원", "에디터와 클라우드 도구 안내. 이 샘플은 설치 파일을 받지 않습니다.", "지원 조건 보기"],
    ];
    for (const [name, text, cta] of programs) {
      body.append(el("article", { class: "tech-card" },
        el("h3", { text: name }),
        el("p", { text }),
        btn(cta, { "data-act": "technyon-note", "data-note": name }, "secondary"),
      ));
    }
  } else if (server) {
    body.append(server);
  } else {
    const thread = (ctx.state.draft && ctx.state.draft.tutor) || [
      { from: "테크뇽", text: "자료에서 궁금한 점을 적어 주세요. 답은 샘플이고 학교 서버로 보내지 않습니다." },
    ];
    for (const line of thread) body.append(el("p", {}, el("b", { text: line.from }), " ", line.text));
    body.append(field("질문", el("input", { "data-name": "tutor", placeholder: "이 자료에 대해 물어보기" })));
    body.append(btn("보내기", { "data-act": "tutor-ask" }, "primary"));
  }
  return el("aside", { class: "tech-panel", "aria-label": title },
    el("div", { class: "tech-h" },
      el("div", {}, el("strong", { text: `테크뇽 · ${title}` }), el("span", { class: "tiny", text: server ? "이 자료로 묻고 답합니다. 대화는 ASTRA 서버에 남습니다" : sub })),
      el("button", { type: "button", class: "x", "data-act": "technyon", "aria-label": "닫기", text: "×" }),
    ),
    body,
  );
}

export function summaryPanel(state) {
  const tab = state.summaryTab || "summary";
  const tabs = [
    ["summary", "요약"],
    ["concepts", "핵심 개념"],
    ["quiz", "퀴즈"],
    ["cards", "플래시카드"],
  ];
  const copy = {
    summary: "이 문서는 샘플 자리표시자입니다. 원본 교과서 문장은 넣지 않았습니다. 오른쪽 탭에서 요약, 개념, 퀴즈, 카드를 바꿔 볼 수 있습니다.",
    concepts: "저장 장치와 접근 경로를 구분해서 적는 칸입니다. 실제 강의 슬라이드의 문장을 옮기지 않았습니다.",
    quiz: "샘플 문항: 자주 읽는 데이터와 가끔 읽는 데이터를 같은 장치에 두는 것이 항상 유리한가요? 정답은 이 브라우저에 저장되지 않는 안내입니다.",
    cards: "앞면: 페이지 캐시. 뒷면: 같은 내용을 다시 읽을 때 저장 장치까지 가지 않게 하는 임시 저장.",
  };
  return el("aside", { class: "summary-panel", "aria-label": "테크뇽 요약" },
    el("div", { class: "tech-h" }, el("strong", { text: "테크뇽 요약" }), el("span", { class: "tiny", text: "샘플 문서" })),
    el("div", { class: "tabs", role: "tablist" }, ...tabs.map(([id, label]) => el("button", {
      type: "button",
      class: tab === id ? "on" : "",
      role: "tab",
      "aria-selected": tab === id ? "true" : "false",
      "data-act": "summary-tab",
      "data-tab": id,
      text: label,
    }))),
    el("div", { class: "tech-b" }, el("p", { text: copy[tab] || copy.summary })),
  );
}
