// Home pieces read from the ASTRA backend while the connection is `ready`: course cards with
// unread notice counts, the latest notices, the to-do column and the full to-do dialog.
// Server text is only ever set as text nodes.
import { el, icon } from "../../dom.js";
import { btn, emptyState, loadingState, errorState } from "../../ui/components.js";
import { hrefFor, find } from "../../manifest.js";
import { typeIcon } from "../../shared/format.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import {
  homeEntry, watchHome, reloadHome, todoEntry, watchTodo, reloadTodo, loadMoreTodo,
  memberCourses, keyOfCourse, todoKind, todoLabel, todoHref, ddayAt, koDateTime, koDate, homeMessage,
} from "../../shared/home-remote.js";
import { courseCards } from "./course-cards.js";
import { serverCourseViews } from "../../shared/course-view.js";
import { siteNoticeStrip, offerEntryNotices } from "./site-notices-server.js";

/** Runs `fn` once `node` is in the document (screens are attached right after render). */
function attached(node, fn) {
  queueMicrotask(() => {
    if (node.isConnected) fn();
  });
}

function retry(reload) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    reload();
  });
  return button;
}

/** Login strip shown on home screens while the backend is `anon`. */
export function homeLoginPanel(compact) {
  return backendPanel({ compact, text: "ASTRA 서버에 로그인하면 수강 과목, 새 공지, 할 일을 서버에서 불러옵니다." });
}

function waiting(entry) {
  return !entry || (!entry.data && !entry.error);
}

/** Course cards of every server course the user belongs to (server facts only) and the latest notices. */
export function serverHomeCourses() {
  const slot = el("div", { class: "home-server" });
  // `/api/me/courses` is the membership source of truth: the home aggregate may omit older school terms.
  const groups = new Map();
  for (const { course, remote } of serverCourseViews()) {
    const key = course.kind === "special" ? "special" : `term:${remote.term?.termId ?? remote.term?.name ?? ""}`;
    if (!groups.has(key)) groups.set(key, { label: course.kind === "special" ? "비정규과목" : course.term || "정규과목", courses: [] });
    groups.get(key).courses.push(course);
  }
  const paint = (entry) => {
    const data = entry && entry.data;
    const { matched } = memberCourses(data);
    const unread = new Map(matched.map(({ local, remote }) => [local.id, Number(remote.unreadNotices) || 0]));
    const stats = new Map(matched.map(({ local, remote }) => [local.id, remote]));
    slot.replaceChildren(
      ...(groups.size
        ? [...groups.values()].flatMap((group) => [
          el("p", { class: "term", text: group.label }),
          courseCards(group.courses, "home", { unread, stats }),
        ])
        : [emptyState("수강 중인 과목이 없습니다", "ASTRA 서버에 이 계정의 과목이 없습니다.")]),
      ...(data
        ? [latestNotices(data), siteNoticeStrip(data)]
        : [waiting(entry)
          ? loadingState("공지와 학습 현황을 불러오는 중입니다")
          : errorState("학습 현황을 불러오지 못했습니다", homeMessage(entry.error), retry(reloadHome))]),
    );
    if (data) offerEntryNotices(data, slot);
  };
  paint(homeEntry());
  attached(slot, () => watchHome(slot, paint));
  return slot;
}

function noticeHref(notice, data) {
  const kj = keyOfCourse(data, notice.courseId);
  return kj ? hrefFor(find("44d_room_notice_view_pop"), { kj, id: String(notice.noticeId) }) : null;
}

function latestNotices(data) {
  const items = Array.isArray(data.notices) ? data.notices : [];
  return el("section", { class: "home-notices", "aria-label": "최근 공지" },
    el("h2", { class: "section-label", text: "최근 공지" }),
    items.length
      ? el("ul", { class: "ntf-list" }, ...items.map((notice) => {
        const href = noticeHref(notice, data);
        return el("li", {},
          el(href ? "a" : "div", { class: `ntf-row${notice.read ? " read" : ""}`, href, "data-go": href },
            notice.read
              ? el("span", { class: "ntf-dot", "aria-hidden": "true" })
              : el("span", { class: "ntf-dot", role: "img", "aria-label": "읽지 않은 공지" }),
            el("span", { class: "ntf-copy" },
              el("b", { text: notice.pinned ? `[고정] ${notice.title}` : notice.title }),
              el("span", { class: "ntf-src", text: notice.courseName }),
              el("span", { class: "ntf-when" }, el("span", { text: koDate(notice.publishedAt) })),
            ),
          ),
        );
      }))
      : el("p", { class: "note", text: "게시된 공지가 없습니다." }),
  );
}

function todoCard(item, data, serverTime) {
  const href = todoHref(item, data);
  const kind = todoKind(item.type);
  return el(href ? "a" : "div", {
    class: `aside-card todo-card${href ? " todo-link" : ""}`,
    href,
    "data-go": href,
    "data-todo": kind,
  },
  el("div", {},
    el("div", { class: "todo-k", text: todoLabel(item.type) }),
    el("b", { text: item.title }),
    el("div", { class: "tiny", text: `${item.courseName} (${item.courseCode})` }),
    el("div", { class: "tiny", text: `마감 ${koDateTime(item.closeAt)}` }),
  ),
  el("span", { class: "due", text: ddayAt(item.closeAt, serverTime) }),
  );
}

/** The "할일" part of the right column (nodes to append): count and the first items from the home aggregate. */
export function serverAsideTodo(moreHref) {
  const count = el("span", { class: "count-num", text: "" });
  const list = el("div", { class: "home-server-todo" });
  const nodes = [
    el("div", { class: "aside-h" }, el("h2", { text: "할일" }), count),
    list,
    el("a", { class: "more-link", href: moreHref, "data-go": moreHref, text: "모두 보기" }),
  ];
  const paint = (entry) => {
    if (waiting(entry)) {
      count.textContent = "";
      list.replaceChildren(el("p", { class: "tiny", role: "status", text: "할 일을 불러오는 중입니다." }));
      return;
    }
    if (!entry.data) {
      count.textContent = "";
      list.replaceChildren(el("p", { class: "tiny", role: "alert", text: homeMessage(entry.error) }));
      return;
    }
    const data = entry.data;
    const todo = data.todo || {};
    const items = Array.isArray(todo.items) ? todo.items : [];
    count.textContent = String(Number(todo.total) || 0);
    list.replaceChildren(...(items.length
      ? items.map((item) => todoCard(item, data, todo.serverTime || data.serverTime))
      : [el("p", { class: "tiny", text: "마감이 남은 할 일이 없습니다." })]));
  };
  paint(homeEntry());
  attached(list, () => watchHome(list, paint));
  return nodes;
}

function taskRow(item, serverTime) {
  const href = todoHref(item, homeEntry() && homeEntry().data);
  const kind = todoKind(item.type);
  return el(href ? "a" : "div", { class: `task-row${href ? " todo-link" : ""}`, href, "data-go": href, "data-todo": kind },
    el("div", { class: "task-title" },
      el("span", { class: `task-kind ${kind}`, role: "img", "aria-label": todoLabel(item.type), title: todoLabel(item.type) }, icon(typeIcon(kind))),
      el("b", { text: item.title }),
    ),
    el("div", { class: "task-course" },
      el("span", { text: item.courseName }),
      el("span", { class: "task-code", text: `(${item.courseCode})` }),
    ),
    el("div", { class: "task-when" },
      el("strong", { class: "task-due", text: ddayAt(item.closeAt, serverTime) }),
      el("span", { text: `마감 ${koDateTime(item.closeAt)}` }),
    ),
  );
}

/** Body of the to-do dialog: every open item, nearest deadline first, 20 at a time. */
export function serverTodoList() {
  const status = el("p", { class: "task-empty", role: "status", "aria-live": "polite" });
  const list = el("ul", { class: "task-list" });
  const more = btn("더 보기", {}, "secondary");
  const actions = el("div", { class: "btn-row" });
  const box = el("div", { class: "tasks" }, status, list, actions);
  more.addEventListener("click", () => {
    more.disabled = true;
    loadMoreTodo();
  });
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      list.replaceChildren();
      actions.replaceChildren();
      if (entry && entry.error) {
        status.setAttribute("role", "alert");
        status.replaceChildren(homeMessage(entry.error), " ", retry(reloadTodo));
      } else {
        status.setAttribute("role", "status");
        status.textContent = "할 일을 불러오는 중입니다.";
      }
      return;
    }
    list.replaceChildren(...data.items.map((item) => el("li", {}, taskRow(item, data.serverTime))));
    status.setAttribute("role", entry.error ? "alert" : "status");
    status.textContent = entry.error
      ? homeMessage(entry.error)
      : data.items.length ? `마감이 남은 할 일 ${data.total}개 중 ${data.items.length}개` : "마감이 남은 할 일이 없습니다.";
    actions.replaceChildren(data.nextCursor ? more : "");
    more.disabled = Boolean(entry.loading);
    more.textContent = entry.loading ? "불러오는 중…" : "더 보기";
  };
  paint(todoEntry());
  attached(box, () => watchTodo(box, paint));
  return box;
}
