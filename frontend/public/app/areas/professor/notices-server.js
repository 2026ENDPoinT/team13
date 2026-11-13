// Professor screens backed by the ASTRA backend: the home course cards and latest notices
// (`GET /api/me/home`), and the course notice form with the server notice list
// (create, edit with `version`, delete). Saving lives in actions.js (`pf-notice-*`).
import { el } from "../../dom.js";
import { btn, field, go, badge, emptyState, loadingState, errorState, pagination, listRow, tabs } from "../../ui/components.js";
import { ApiError } from "../../shared/api.js";
import { backendState, backendStatus, courseIdFor, roleIn } from "../../shared/backend.js";
import { courseById } from "../../data.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { freshVisit } from "../../shared/remote-cache.js";
import { homeEntry, watchHome, reloadHome, memberCourses, koDate, koDateTime, homeMessage } from "../../shared/home-remote.js";
import { routeFor } from "../../shared/routes.js";
import {
  NOTICE_PAGE, listKey, noticeKey, noticeLists, noticeDetails, noticeMessage, noticeFlags,
} from "../../shared/notices-remote.js";

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

const editHref = (kj, noticeId) => routeFor("professor", "screen", { screenId: "pf_notice_write", kj, edit: String(noticeId) });

/** Professor home in backend mode: member courses as sample cards, then the latest notices. */
export function serverProfessorHome(card, countNode) {
  const slot = el("div", {});
  const paint = (entry) => {
    if (!entry || (!entry.data && !entry.error)) {
      countNode.textContent = "";
      slot.replaceChildren(loadingState("담당 과목을 불러오는 중입니다"));
      return;
    }
    if (!entry.data) {
      countNode.textContent = "";
      slot.replaceChildren(errorState("담당 과목을 불러오지 못했습니다", homeMessage(entry.error), retry(reloadHome)));
      return;
    }
    const data = entry.data;
    const { matched, unmatched } = memberCourses(data);
    const teaching = matched.filter(({ remote }) => remote.role === "INSTRUCTOR");
    countNode.textContent = String(teaching.length);
    const roles = new Map((Array.isArray(data.courses) ? data.courses : []).map((course) => [course.courseId, course]));
    const notices = Array.isArray(data.notices) ? data.notices : [];
    slot.replaceChildren(
      matched.length
        ? el("div", { class: "cards" }, ...matched.map(({ local, remote }) => card(local, Number(remote.unreadNotices) || 0)))
        : emptyState("담당 과목이 없습니다", "ASTRA 서버에 이 계정의 과목이 없습니다."),
      unmatched.length
        ? el("p", { class: "pf-sub", text: `이 샘플에 화면이 없는 서버 과목 ${unmatched.length}개는 표시하지 않습니다: ${unmatched.map((course) => course.name).join(", ")}` })
        : null,
      el("h2", { class: "section-label", text: "최근 공지" }),
      notices.length
        ? el("div", {}, ...notices.map((notice) => {
          const course = roles.get(notice.courseId);
          const own = course && course.role === "INSTRUCTOR" && matched.some(({ remote }) => remote.courseId === notice.courseId);
          const row = listRow({
            href: own ? editHref(course.key, notice.noticeId) : null,
            title: notice.pinned ? `[고정] ${notice.title}` : notice.title,
            detail: `${notice.courseName} · ${koDate(notice.publishedAt)}`,
            meta: notice.read ? "읽음" : "안 읽음",
          });
          return row;
        }))
        : emptyState("게시된 공지가 없습니다", "담당 과목에 공지를 쓰면 여기에 모입니다."),
    );
  };
  paint(homeEntry());
  attached(slot, () => watchHome(slot, paint));
  return slot;
}

/** The notice screen (`pf_notice_write`) in backend mode. */
export function serverNoticeFields(ctx, course, parts) {
  const stack = (...nodes) => el("div", { class: "stack" }, ...nodes);
  if (backendStatus() === "anon") {
    // The local sample form is hidden: a notice saved only in this browser would look like a server notice.
    return stack(backendPanel({ text: "ASTRA 서버에 로그인하면 이 과목의 공지를 서버에 쓰고 고칠 수 있습니다." }));
  }
  const courseId = courseIdFor(course.id);
  if (!courseId) return stack(backendPanel(), emptyState("이 과목은 ASTRA 서버에 없습니다", "서버에 등록된 과목에만 공지를 쓸 수 있습니다."));
  if (roleIn(course.id) !== "INSTRUCTOR") {
    return stack(backendPanel(), el("p", { class: "pf-sub", text: "이 과목을 담당하는 교수 계정으로 로그인해야 공지를 쓰고 고칠 수 있습니다." }));
  }
  const editId = Number((ctx.params && ctx.params.get("edit")) || "");
  const page = Math.max(1, Math.floor(Number(ctx.params && ctx.params.get("page"))) || 1);
  return stack(
    backendPanel(),
    noticeForm(course, courseId, Number.isInteger(editId) && editId > 0 ? editId : null, parts),
    noticeList(course, courseId, page, parts),
  );
}

function noticeForm(course, courseId, editId, parts) {
  const { formSheet, span, pf, linkBtn } = parts;
  const title = el("input", { "data-name": "title", maxlength: "300" });
  const body = el("textarea", { "data-name": "body", rows: "8", maxlength: "20000" });
  const pin = el("input", { type: "checkbox", "data-name": "pin" });
  const draft = el("input", { type: "checkbox", "data-name": "draft" });
  const status = el("p", { class: "pf-sub", role: "status", "aria-live": "polite", "data-notice-status": "" });
  const save = btn(editId ? "수정 저장" : "공지 저장", { "data-act": "pf-notice-save" }, "primary");
  const sheet = formSheet(editId ? "공지 수정" : "공지",
    span(field("제목", title)),
    span(field("본문", body)),
    span(el("label", { class: "choice pf-check" }, pin, el("span", { text: "상단 고정" }))),
    span(el("label", { class: "choice pf-check" }, draft, el("span", { text: "임시 저장 (학생에게 보이지 않습니다)" }))),
    span(status),
    span(el("div", { "data-notice-latest": "" })),
    span(el("div", { class: "pf-actions" }, save, editId ? linkBtn(pf("pf_notice_write", { kj: course.id }), "새 공지 쓰기") : null)),
  );
  sheet.dataset.noticeForm = "";
  sheet.dataset.course = String(courseId);
  sheet.dataset.kj = course.id;
  sheet.dataset.edit = editId ? String(editId) : "";
  if (!editId) return sheet;

  // Edit: fill the form once from the server copy; later loads (a conflict reload) leave typed text alone.
  const key = noticeKey(courseId, editId);
  const controls = [title, body, pin, draft, save];
  let filled = false;
  const paint = (entry) => {
    const notice = entry && entry.data;
    if (filled) return;
    if (!notice) {
      controls.forEach((control) => { control.disabled = true; });
      status.setAttribute("role", entry && entry.error ? "alert" : "status");
      status.textContent = !entry || !entry.error ? "공지를 불러오는 중입니다."
        : entry.error instanceof ApiError && entry.error.status === 404 ? "공지를 찾을 수 없습니다. 삭제되었거나 이 과목의 공지가 아닙니다." : noticeMessage(entry.error);
      return;
    }
    filled = true;
    title.value = notice.title;
    body.value = notice.body || "";
    pin.checked = Boolean(notice.pinned);
    draft.checked = !notice.publishedAt;
    adoptVersion(sheet, notice);
    controls.forEach((control) => { control.disabled = false; });
    status.setAttribute("role", "status");
    status.textContent = `${koDateTime(notice.updatedAt)}에 마지막으로 수정한 공지입니다.`;
  };
  paint(noticeDetails.peek(key));
  attached(sheet, () => {
    noticeDetails.watch(sheet, key, paint, null);
    if (freshVisit("pf-notice-edit", key) === "entry" || !noticeDetails.peek(key)) noticeDetails.load(key, courseId, editId);
  });
  return sheet;
}

/** Remembers the version and schedule an edit must send back (see actions.js). */
export function adoptVersion(sheet, notice) {
  sheet.dataset.version = String(notice.version);
  // A scheduled notice keeps its time; a published one keeps its original time when publishAt is omitted.
  sheet.dataset.publishAt = notice.publishedAt && !notice.published ? notice.publishedAt : "";
}

function noticeList(course, courseId, page, parts, screenId = "pf_notice_write") {
  const { pf } = parts;
  const key = listKey(courseId, "", page);
  const load = () => noticeLists.load(key, courseId, "", page);
  const list = el("div", { class: "stack" });
  const section = el("section", { class: "sheet", "aria-label": "ASTRA 서버에 등록된 공지" },
    el("h2", { text: "서버에 등록된 공지" }),
    el("p", { class: "pf-sub", text: "임시 저장과 예약 공지는 담당 교수에게만 보입니다. 열람·확인 수에는 작성자가 빠집니다." }),
    list,
  );
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      list.replaceChildren(!entry || !entry.error
        ? el("p", { class: "pf-sub", role: "status", text: "서버에 등록된 공지를 불러오는 중입니다." })
        : el("div", {}, el("p", { class: "err", role: "alert", text: noticeMessage(entry.error) }), retry(load)));
      return;
    }
    const items = Array.isArray(data.items) ? data.items : [];
    if (!items.length) {
      list.replaceChildren(el("p", { class: "pf-sub", text: page > 1 ? "이 쪽에는 공지가 없습니다." : "아직 서버에 등록된 공지가 없습니다." }));
      return;
    }
    const after = pf(screenId, page > 1 ? { kj: course.id, page: String(page) } : { kj: course.id });
    list.replaceChildren(
      entry.error ? el("p", { class: "err", role: "alert", text: noticeMessage(entry.error) }) : "",
      ...items.map((notice) => {
        const href = editHref(course.id, notice.noticeId);
        const flags = noticeFlags(notice);
        return el("div", { class: "pf-activity" },
          el("a", { class: "pf-activity-main", href, "data-go": href },
            badge(flags.length ? flags.join(" · ") : "게시", flags.includes("임시") || flags.includes("예약") ? "neutral" : "success"),
            el("span", { class: "pf-activity-copy" },
              el("b", { text: notice.title }),
              el("span", { class: "pf-meta", text: [
                notice.publishedAt ? koDate(notice.publishedAt) : "게시 전",
                `조회 ${notice.viewCount}`,
                `열람 ${notice.readCount ?? 0}`,
                `확인 ${notice.confirmCount ?? 0}`,
              ].join(" · ") }),
            ),
          ),
          el("div", { class: "pf-activity-actions" },
            go(href, "수정", { class: "btn btn-ghost", "aria-label": `${notice.title} 수정` }),
            btn("삭제", {
              "data-act": "pf-notice-delete",
              "data-course": String(courseId),
              "data-id": String(notice.noticeId),
              "data-after": after,
              "aria-label": `${notice.title} 삭제`,
            }, "ghost"),
          ),
        );
      }),
      pagination({
        page,
        pages: Math.max(1, Math.ceil(Number(data.total) / (Number(data.size) || NOTICE_PAGE))),
        hrefForPage: (n) => pf(screenId, n > 1 ? { kj: course.id, page: String(n) } : { kj: course.id }),
      }) || "",
    );
  };
  paint(noticeLists.peek(key));
  attached(section, () => {
    noticeLists.watch(section, key, paint, load);
    if (freshVisit("pf-notices", key) === "entry" || !noticeLists.peek(key)) load();
  });
  return section;
}

/**
 * `pf_notice`(알림마당) 백엔드 화면. 교수용 기관 공지 목록 API가 없어 담당 과목의 공지를 고르고
 * 수정·삭제한다. 주소 `kj`가 담당 과목이 아니면 첫 담당 과목, `page`는 그 과목 공지 목록의 쪽이다.
 */
export function serverCourseNotices(ctx) {
  const stack = (...nodes) => el("div", { class: "stack" }, ...nodes);
  if (backendStatus() === "anon") {
    return stack(backendPanel({ text: "ASTRA 서버에 로그인하면 담당 과목의 공지를 보고 고칠 수 있습니다." }));
  }
  const param = (name) => (ctx && ctx.params && ctx.params.get(name)) || "";
  const { courses } = backendState();
  const taught = (Array.isArray(courses) ? courses : [])
    .filter((remote) => remote.role === "INSTRUCTOR" && remote.key && courseById(remote.key).id === remote.key);
  if (!taught.length) {
    return stack(backendPanel(), emptyState("담당 과목이 없습니다", "ASTRA 서버에 이 계정이 담당하는 과목이 없어 공지를 보여 줄 수 없습니다."));
  }
  const chosen = taught.find((remote) => remote.key === param("kj")) || taught[0];
  const course = courseById(chosen.key);
  const page = Math.max(1, Math.floor(Number(param("page"))) || 1);
  const pf = (screenId, extra) => routeFor("professor", "screen", { screenId, ...(extra || {}) });
  const write = pf("pf_notice_write", { kj: course.id });
  return stack(
    backendPanel(),
    el("p", { class: "pf-sub", text: "ASTRA 서버에는 교수용 기관 공지가 아직 없어 담당 과목의 공지를 모아 보여 줍니다." }),
    el("nav", { "aria-label": "담당 과목" },
      tabs(taught.map((remote) => ({
        label: remote.name || courseById(remote.key).ko || remote.key,
        href: pf("pf_notice", { kj: remote.key }),
        on: remote.key === chosen.key,
      }))),
    ),
    el("div", { class: "pf-actions" }, go(write, "새 공지 쓰기", { class: "btn btn-primary" })),
    noticeList(course, chosen.courseId, page, { pf }, "pf_notice"),
  );
}
