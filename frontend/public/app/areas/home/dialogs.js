import { el, icon } from "../../dom.js";
import { btn, field, dialogShell as baseDialog, go } from "../../ui/components.js";

function dialogShell(title, body, options) {
  const node = baseDialog(title, body, options);
  node.classList.add("home-dialog");
  return node;
}
import { notices, activities, courseById, student, professor } from "../../data.js";
import { boardRows, dday, typeLabel, typeIcon, activityHref, openTodos, myDayGroups } from "../../shared/format.js";
import { currentRole, routeFor } from "../../shared/routes.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { backendState, backendStatus } from "../../shared/backend.js";
import { profiles, peekAccount, watchAccount } from "../../shared/account-remote.js";
import { serverTodoList, homeLoginPanel } from "./home-server.js";
import { serverApplyBody } from "./catalog-server.js";
import { serverNotifications } from "./notifications-server.js";
import { remoteMaterialDialog, remoteInquiryDialog, remoteInquiryWriteDialog } from "./community-server.js";
import { remoteMaterialWriteDialog } from "./community-server.js";
import { communitySurveyDialog } from "./surveys-server.js";
import { serverAccountDialog } from "./account-server.js";
import { serverFileBox } from "./filebox-server.js";
import { serverSiteNoticeDialog, serverSiteNoticeWriteDialog, serverEntryNotices } from "./site-notices-server.js";

const MINE = new Set(["welcome", "settings", "display", "profile", "myinfo", "avatar", "files", "history", "history-login", "notify", "notify-unread", "todo", "todo-more", "external", "related", "apply", "chat", "qna-write"]);

const DEVICE = {
  "Campus laptop": "캠퍼스 노트북",
  "Mobile browser": "모바일 브라우저",
};

function koWhen(value) {
  if (!value) return "기간 없음";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  const p = (n) => String(n).padStart(2, "0");
  return `${date.getMonth() + 1}월 ${date.getDate()}일 ${p(date.getHours())}:${p(date.getMinutes())}`;
}

function koDue(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  const h = date.getHours();
  const m = String(date.getMinutes()).padStart(2, "0");
  return `${date.getMonth() + 1}월 ${date.getDate()}일 (${"일월화수목금토"[date.getDay()]}) ${h < 12 ? "오전" : "오후"} ${h % 12 || 12}:${m}`;
}

const NOTIFY = [
  { title: "Project 마감이 가까워졌습니다", read: false, activityId: "rep-project" },
  { title: "11주 1차시가 열렸습니다", read: false, activityId: "lec-11-1" },
  { title: "텍스트마이닝 미션 2 마감이 가까워졌습니다", read: false, activityId: "r-open" },
  { title: "주간 일정이 갱신되었습니다", read: true, kind: "일정", source: "주간 일정", when: "11월 2일 (월)" },
];

function notifyRow(item) {
  const activity = item.activityId ? activities.find((row) => row.id === item.activityId) : null;
  const course = activity ? courseById(activity.courseId) : null;
  const href = activity ? activityHref(activity) : "";
  const kind = activity ? typeLabel(activity.type) : item.kind;
  const due = activity ? dday(activity.due) : "";
  const when = activity ? activity.whenLabel || koDue(activity.due) : item.when;
  return el("li", {},
    el(href ? "a" : "div", {
      class: `ntf-row${item.read ? " read" : ""}`,
      href: href || null,
      "data-go": href || null,
      "data-item": activity ? activity.id : null,
      "data-todo": activity ? activity.type : null,
    },
    item.read
      ? el("span", { class: "ntf-dot", "aria-hidden": "true" })
      : el("span", { class: "ntf-dot", role: "img", "aria-label": "새 알림" }),
    el("span", { class: "ntf-copy" },
      el("b", { text: `[${kind}] ${item.title}` }),
      el("span", { class: "ntf-src", text: course ? `${course.cardTitle || course.ko}(${course.code})` : item.source }),
      el("span", { class: "ntf-when" },
        due ? el("em", { text: due }) : null,
        el("span", { text: when }),
      ),
    ),
    ),
  );
}

function notifyChip(href, label, count, on) {
  return el("a", { class: `chip all${on ? " on" : ""}`, href, "data-go": href, "aria-current": on ? "page" : null },
    el("span", { text: label }),
    el("span", { class: "ntf-count", text: String(count) }),
  );
}

function person(role) {
  const session = backendState();
  if (session.status !== "ready" || !session.me) return role === "professor" ? professor : student;
  const entry = peekAccount(profiles);
  const profile = entry && entry.data;
  const own = profile && String(profile.userId) === String(session.me.userId) ? profile : null;
  return { name: session.me.name || "", dept: own && own.departmentName || "", real: true };
}

function taskCard(item) {
  const course = courseById(item.courseId);
  const href = activityHref(item);
  return el("a", {
    class: "task-row todo-link",
    href,
    "data-go": href,
    "data-item": item.id,
    "data-todo": item.type,
  },
  el("div", { class: "task-title" },
    el("span", { class: `task-kind ${item.type}`, role: "img", "aria-label": typeLabel(item.type), title: typeLabel(item.type) }, icon(typeIcon(item.type))),
    el("b", { text: item.title }),
  ),
  el("div", { class: "task-course" },
    el("span", { text: course.cardTitle || course.ko }),
    el("span", { class: "task-code", text: `(${course.code})` }),
  ),
  el("div", { class: "task-when" },
    item.due ? el("strong", { class: "task-due", text: dday(item.due) }) : null,
    el("span", { text: item.whenLabel || (item.due ? `마감 ${koDue(item.due)}` : item.when ? koWhen(item.when) : "진행 중 · 마감일 없음") }),
  ),
  );
}

function menuLink(href, label, hint) {
  return el("a", { class: "menu-item", href, "data-go": href },
    el("span", { text: label }),
    hint ? el("span", { class: "hint", text: hint }) : null,
  );
}

export function renderDialog(name, ctx) {
  // A server 알림마당 survey (`?pop=survey&id=srv-<id>`, backend mode).
  if (name === "survey") {
    const server = communitySurveyDialog(ctx);
    if (server) return server;
  }
  if (name === "notice") {
    if (ctx.screen.board === "course") return null;
    // Backend mode: 알림마당 공지 (`?pop=notice&id=srv-<noticeId>`, areas/home/site-notices-server.js).
    if (backendStatus() !== "off") return serverSiteNoticeDialog(ctx);
    return notice(ctx);
  }
  if (name === "qna-write" && ctx.screen.board !== "qna") return null;
  // Backend mode: 알림마당 자료실·1:1 문의 dialogs (areas/home/community-server.js).
  if (backendStatus() !== "off" && ctx.screen.board === "material" && name === "material") return remoteMaterialDialog(ctx);
  if (backendStatus() !== "off" && ctx.screen.board === "qna" && name === "inquiry") return remoteInquiryDialog(ctx);
  if (backendStatus() !== "off" && ctx.screen.board === "material" && name === "material-write") return remoteMaterialWriteDialog(ctx);
  if (backendStatus() !== "off" && ctx.screen.board === "qna" && name === "qna-write") return remoteInquiryWriteDialog();
  if (backendStatus() !== "off" && ctx.screen.board === "global" && name === "notice-write") return serverSiteNoticeWriteDialog(ctx);
  if (!MINE.has(name)) return null;
  const err = ctx.state.formError || {};
  const draft = ctx.state.draft || {};
  const who = person(ctx.state.role);
  // Backend mode: account dialogs and the file box read the server (account-server.js, filebox-server.js).
  const server = name === "files" ? serverFileBox(ctx) : serverAccountDialog(name, ctx);
  if (server) return server;
  // Backend `ready`: the entry popup lists the unread pinned 알림마당 notices (`noticesForMe`).
  if (name === "welcome" && backendStatus() === "ready") return serverEntryNotices();
  if (name === "welcome") {
    return dialogShell("e-Class 안내", el("div", {},
      el("p", { text: "2026학년도 2학기 샘플 화면입니다. 제출과 설정은 이 브라우저에만 남고, 학교 서버로는 전송되지 않습니다." }),
      btn("확인", { "data-act": "close" }, "primary"),
    ));
  }
  if (name === "settings") {
    return dialogShell("설정", el("div", {},
      el("div", { class: "menu-list" },
        el("button", { type: "button", class: "set-row", "data-act": "lang" },
          el("span", { text: "언어" }),
          el("span", { class: "hint", text: ctx.state.lang === "ko" ? "한국어" : "English" }),
        ),
      ),
      el("p", { class: "note", text: "언어만 이 브라우저에 저장됩니다. 밝은 화면은 기기 설정이 라이트일 때만 열리고, 테마 단추는 없습니다." }),
    ));
  }
  if (name === "display") {
    return dialogShell("화면 모드", el("p", { class: "note", text: "밝은 화면은 기기가 라이트일 때만 열립니다. 따로 고르는 단추는 없습니다." }));
  }
  if (name === "profile") {
    const nameNode = el("h2", { text: who.name });
    const deptNode = el("p", { class: "tiny center", text: who.dept || "", hidden: !who.dept });
    const card = el("div", {
      class: "profile-pop home-dialog",
      role: "dialog",
      "aria-modal": "true",
      "aria-label": "내 메뉴",
    },
      nameNode,
      deptNode,
      el("div", { class: "menu-list" },
        menuLink(routeFor(currentRole(), "pop", { pop: "myinfo" }), "내 정보"),
        menuLink(routeFor(currentRole(), "pop", { pop: "avatar" }), "아바타"),
        menuLink(routeFor(currentRole(), "pop", { pop: "files" }), "파일함"),
        menuLink(routeFor(currentRole(), "pop", { pop: "history" }), "접속 이력"),
        menuLink(routeFor(currentRole(), "pop", { pop: "settings" }), "설정"),
        el("a", { class: "menu-item", href: "/ilos/lo/logout.acl", "data-go": "/ilos/lo/logout.acl", "data-exit": "logout" }, el("span", { text: "로그아웃" })),
      ),
      el("p", { class: "tiny", text: "샘플 역할" }),
      el("div", { class: "btn-row" },
        el("button", { type: "button", class: "btn btn-secondary", "data-act": "role", "data-role": "student", "data-role-switch": "student", text: "학생" }),
        el("button", { type: "button", class: "btn btn-secondary", "data-act": "role", "data-role": "professor", "data-role-switch": "professor", text: "교수" }),
        currentRole() === "professor" ? el("button", { type: "button", class: "btn btn-secondary", "data-act": "role", "data-role": "student", "data-role-switch": "student", text: "학생 화면으로 보기" }) : null,
      ),
      backendPanel({ compact: true, text: "ASTRA 서버에 로그인하면 서버에 등록된 자료와 쪽별 요약을 사용할 수 있습니다." }),
      el("button", { type: "button", class: "menu-item", "data-act": "reset", text: "샘플 데이터 되돌리기" }),
    );
    if (who.real) queueMicrotask(() => watchAccount(profiles, "profile-menu", card, () => {
      const current = person(ctx.state.role);
      nameNode.textContent = current.name;
      deptNode.textContent = current.dept || "";
      deptNode.hidden = !current.dept;
    }));
    return el("div", { class: "modal-back menu-back", "data-act": "close" }, card);
  }
  if (name === "myinfo") {
    return dialogShell("내 정보", el("div", { class: "who" },
      el("span", { class: "avatar lg", text: (ctx.state.draft.avatar || who.avatar).slice(0, 2) }),
      el("b", { text: who.name }),
      who.no ? el("span", { class: "tiny", text: who.no }) : null,
      who.dept ? el("p", { text: who.dept }) : null,
      el("p", { class: "tiny", text: ctx.state.role === "professor" ? "교수" : [who.year, "학생"].filter(Boolean).join(" · ") }),
      el("p", { class: "note", text: "전화번호와 메일은 이 샘플에 두지 않습니다. 학사 시스템에서 확인하세요." }),
      go(routeFor(currentRole(), "pop", { pop: "avatar" }), "아바타 바꾸기", { class: "btn btn-secondary" }),
    ));
  }
  if (name === "avatar") {
    const letters = draft.avatar || who.avatar;
    return dialogShell("아바타", el("div", { class: "who" },
      el("span", { class: "avatar lg", text: String(letters).slice(0, 1) }),
      el("p", { class: "note", text: "이니셜만 바꿉니다. 사진은 올리지 않습니다." }),
      field("표시 글자", el("input", { "data-name": "avatar", maxlength: "2", value: letters }), err.avatar),
      btn("저장", { "data-act": "save-avatar" }, "primary"),
    ));
  }
  if (name === "files") {
    return dialogShell("파일함", ctx.state.fileBox.length
      ? el("div", { class: "board-list" }, ...ctx.state.fileBox.map((file) => el("div", { class: "board-card" },
        el("b", { text: file.name }),
        el("span", { class: "tiny", text: file.at }),
      )))
      : el("p", { class: "note", text: "올린 파일이 없습니다." }));
  }
  if (name === "history" || name === "history-login") {
    const login = name === "history-login";
    return dialogShell(login ? "접속 이력" : "접속 기기", el("div", {},
      el("div", { class: "filters" },
        go(routeFor(currentRole(), "pop", { pop: "history" }), "접속 기기", { class: `chip${!login ? " on all" : ""}` }),
        go(routeFor(currentRole(), "pop", { pop: "history-login" }), "접속 이력", { class: `chip${login ? " on all" : ""}` }),
      ),
      ...ctx.state.devices.map((device) => el("article", { class: "aside-card todo-card" },
        el("div", {},
          el("b", { text: DEVICE[device.name] || device.name }),
          el("div", { class: "tiny", text: `${device.where} · ${device.at}` }),
        ),
        device.current ? el("span", { class: "status on", text: "현재" }) : null,
      )),
    ));
  }
  if (name === "notify" || name === "notify-unread") {
    if (backendStatus() !== "off") return dialogShell("알림", serverNotifications(name), { className: "ntf-modal" });
    const unread = NOTIFY.filter((item) => !item.read);
    const items = name === "notify" ? NOTIFY : unread;
    return dialogShell("알림", el("div", { class: "ntf" },
      el("nav", { class: "filters ntf-filters", "aria-label": "알림 보기" },
        notifyChip(routeFor(currentRole(), "pop", { pop: "notify" }), "전체", NOTIFY.length, name === "notify"),
        notifyChip(routeFor(currentRole(), "notify", { pop: "notify-unread" }), "안 읽음", unread.length, name === "notify-unread"),
      ),
      items.length
        ? el("ul", { class: "ntf-list" }, ...items.map(notifyRow))
        : el("p", { class: "note", text: "읽지 않은 알림이 없습니다." }),
    ), { className: "ntf-modal" });
  }
  if (name === "todo" || name === "todo-more") {
    if (backendStatus() === "ready") return dialogShell("? ?", serverTodoList(), { className: "todo-modal" });
    // `todo-view` comes from a 나의 하루 card or the hero's soon count: list exactly the items it counted.
    const groups = myDayGroups(ctx.state);
    const viewKey = ctx.params.get("todo-view") || "";
    const view = Object.hasOwn(groups, viewKey) ? groups[viewKey] : null;
    const list = view ? view.items : openTodos();
    const allParams = new URLSearchParams(ctx.params);
    allParams.delete("todo-view");
    const allHref = `${location.pathname}?${allParams}`;
    return dialogShell("할 일", el("div", { class: "tasks" },
      backendStatus() === "anon" ? homeLoginPanel(true) : null,
      view ? el("div", { class: "task-scope" },
        el("p", {}, el("b", { text: view.label }), el("span", { class: "task-count", text: ` ${list.length}` })),
        el("a", { class: "task-scope-all", href: allHref, "data-go": allHref, text: `전체 할 일 ${openTodos().length}` }),
      ) : null,
      list.length
        ? el("ul", { class: "task-list" }, ...list.map((item) => el("li", {}, taskCard(item))))
        : el("p", { class: "task-empty", role: "status", text: "남은 할 일이 없습니다." }),
    ), { className: "todo-modal" });
  }
  if (name === "chat") {
    const thread = ctx.state.draft.chat || [{ from: "서포터즈", text: "샘플 상담입니다. 계정 비밀번호는 받지 않습니다." }];
    return dialogShell("서포터즈", el("div", {},
      ...thread.map((message) => el("div", { class: `bubble${message.from === "박민준" ? " mine" : ""}` },
        el("b", { text: message.from }),
        message.text,
      )),
      field("메시지", el("input", { "data-name": "chat", placeholder: "메시지를 입력하세요" }), err.chat),
      btn("보내기", { "data-act": "send-chat" }, "primary"),
    ));
  }
  // Backend `ready`: the server application preview and the apply command (Idempotency-Key).
  if (name === "apply" && backendStatus() === "ready") return dialogShell("수강 신청", serverApplyBody(ctx));
  if (name === "apply") {
    const course = courseById((ctx.params && ctx.params.get("id")) || "lit");
    return dialogShell("수강 신청", el("div", {},
      el("p", { text: `${course.ko} (${course.code})에 신청합니다. 정원과 별도로 이 브라우저에만 기록됩니다.` }),
      field("한 줄 각오", el("input", { "data-name": "motive", placeholder: "신청 이유를 한 줄로" }), err.motive),
      btn("신청", { "data-act": "apply-course", "data-id": course.id }, "primary"),
    ));
  }
  if (name === "qna-write") {
    return dialogShell("1:1 질문하기", el("div", {},
      field("제목", el("input", { "data-name": "title", value: draft.title || "" }), err.title),
      field("내용", el("textarea", { "data-name": "body", placeholder: "질문 내용을 적어 주세요" }), err.body),
      field("공개", el("select", { "data-name": "open" }, el("option", { value: "open", text: "공개" }), el("option", { value: "private", text: "비공개" }))),
      btn("등록", { "data-act": "save-post", "data-board": "community" }, "primary"),
    ));
  }
  if (name === "external") return dialogShell("학력증진", el("p", { class: "note", text: "역량 강화와 외부 사이트는 이 샘플에 연결하지 않습니다." }));
  if (name === "related") return dialogShell("관련 사이트", el("p", { class: "note", text: "포털, 도서관, 학사 시스템은 이 샘플 밖에 있습니다." }));
  return null;
}

function notice(ctx) {
  const id = ctx.params && ctx.params.get("id");
  const row = boardRows({ board: ctx.screen.board || "global", course: ctx.state.courseId, audience: ctx.screen.audience }, ctx.state).find((item) => item.id === id)
    || notices.find((item) => item.id === id)
    || notices[0];
  return dialogShell(row.title, el("div", {},
    el("p", { class: "tiny", text: [row.author, row.date || row.at].filter(Boolean).join(" · ") }),
    el("p", { text: row.body || "본문이 없습니다." }),
  ), { wide: true });
}
