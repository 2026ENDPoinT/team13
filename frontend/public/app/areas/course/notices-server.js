// Course notice list and detail from the ASTRA backend (status `anon` or `ready`).
// `anon` shows only the login strip: sample notices are not mixed into a connected course.
// Opening a notice sends `read` once; `confirm` comes only from the confirm button.
import { el } from "../../dom.js";
import { btn, go, tabs, badge, emptyState, loadingState, errorState, pagination, dialogShell } from "../../ui/components.js";
import { hrefFor, find } from "../../manifest.js";
import { readQuery } from "../../shared/query.js";
import { ApiError } from "../../shared/api.js";
import { backendStatus, courseIdFor, roleIn } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { freshVisit } from "../../shared/remote-cache.js";
import { koDate, koDateTime } from "../../shared/home-remote.js";
import {
  NOTICE_PAGE, listKey, noticeKey, noticeLists, noticeDetails, markNoticeRead, noticeMessage, noticeFlags,
} from "../../shared/notices-remote.js";

const LOGIN_TEXT = "ASTRA 서버에 로그인하면 이 과목의 공지를 서버에서 불러옵니다.";
const SEARCH_DELAY = 300;

const missing = (error) => error instanceof ApiError && error.status === 404;

function retry(reload) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    reload();
  });
  return button;
}

function kinds(notice) {
  const marks = noticeFlags(notice).map((flag) => el("span", { class: "crs-kind", text: flag }));
  if (notice.published && !notice.read) marks.push(el("span", { class: "crs-kind crs-kind-new", text: "안 읽음" }));
  return marks;
}

function counts(notice) {
  if (notice.readCount == null && notice.confirmCount == null) return null;
  return el("span", { text: `열람 ${notice.readCount ?? 0} · 확인 ${notice.confirmCount ?? 0}` });
}

function noticeCard(notice, href) {
  return el("article", { class: `crs-post${notice.read ? " is-read" : ""}` },
    el("a", { class: "crs-post-link", href, "data-go": href },
      el("div", {},
        el("h2", { text: notice.title }),
        notice.excerpt ? el("p", { text: notice.excerpt }) : null,
        el("div", { class: "crs-post-meta" },
          ...kinds(notice),
          el("span", { text: notice.publishedAt ? koDate(notice.publishedAt) : "게시 전" }),
          el("span", { text: `조회 ${notice.viewCount}` }),
          counts(notice),
        ),
      ),
    ),
  );
}

/** The course notice board in backend mode. `parts` are course.js's head/searchBox builders. */
export function remoteNoticeBoard(screen, ctx, course, parts) {
  const title = "공지사항";
  const header = parts.head(title, "info", null);
  const root = el("section", { class: "crs" }, header);
  if (backendStatus() === "anon") {
    root.append(backendPanel({ text: LOGIN_TEXT }));
    return root;
  }
  const courseId = courseIdFor(course.id);
  if (!courseId) {
    root.append(emptyState("이 과목은 ASTRA 서버에 없습니다", "서버에 등록된 과목만 공지를 볼 수 있습니다."));
    return root;
  }
  const q = readQuery(ctx.state, parts.scope).trim().slice(0, 100);
  const page = Math.max(1, Math.floor(Number(ctx.params && ctx.params.get("page"))) || 1);
  const key = listKey(courseId, q, page);
  const load = () => noticeLists.load(key, courseId, q, page);
  const pageHref = (n) => hrefFor(find(screen.id), n > 1 ? { kj: course.id, page: String(n) } : { kj: course.id });
  const total = badge("", "neutral");
  const totalSlot = el("span", {});
  header.querySelector(".crs-head-title").append(totalSlot);
  const body = el("div", { class: "crs-remote", "aria-live": "polite" });
  root.append(
    el("div", { class: "crs-toolbar" }, tabs([{ label: "전체", on: true }]), parts.searchBox(`${title} 검색`, ctx, "course")),
    body,
  );

  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      totalSlot.replaceChildren();
      if (!entry || !entry.error) body.replaceChildren(loadingState("공지를 불러오는 중입니다"));
      else if (missing(entry.error)) body.replaceChildren(emptyState("공지를 볼 수 없습니다", "이 과목의 구성원이 아니거나 과목을 찾을 수 없습니다."));
      else body.replaceChildren(errorState("공지를 불러오지 못했습니다", noticeMessage(entry.error), retry(load)));
      return;
    }
    total.textContent = String(data.total);
    totalSlot.replaceChildren(total);
    const items = Array.isArray(data.items) ? data.items : [];
    const pages = Math.max(1, Math.ceil(Number(data.total) / (Number(data.size) || NOTICE_PAGE)));
    const stale = entry.error ? el("p", { class: "err", role: "alert", text: noticeMessage(entry.error) }) : null;
    if (!items.length) {
      body.replaceChildren(stale || "", page > 1 && data.total > 0
        ? emptyState("이 쪽에는 공지가 없습니다", "", go(pageHref(1), "첫 쪽으로", { class: "btn btn-secondary" }))
        : q
          ? emptyState("검색 결과가 없습니다", "제목에 들어 있는 다른 단어로 찾아 보세요.")
          : emptyState("게시글이 없습니다.", "등록된 공지가 없습니다."));
      return;
    }
    body.replaceChildren(
      stale || "",
      el("div", { class: "crs-posts" }, ...items.map((notice) => noticeCard(notice,
        hrefFor(find("44d_room_notice_view_pop"), { kj: course.id, id: String(notice.noticeId), ...(page > 1 ? { page: String(page) } : {}) })))),
      pagination({ page, pages, hrefForPage: pageHref }) || "",
    );
  };
  paint(noticeLists.peek(key));
  queueMicrotask(() => {
    if (!root.isConnected) return;
    noticeLists.watch(root, key, paint, load);
    const fresh = freshVisit("course-notices", key);
    if (fresh === "entry") load();
    else if (!noticeLists.peek(key)) {
      // A new search word: wait until typing pauses (every keystroke re-renders this board).
      if (fresh === "key") setTimeout(() => { if (root.isConnected && !noticeLists.peek(key)) load(); }, SEARCH_DELAY);
      else load();
    }
  });
  return root;
}

function meta(pairs) {
  return el("dl", { class: "crs-meta pairs" }, ...pairs.filter(Boolean).flatMap(([label, value]) => [
    el("dt", { text: label }),
    el("dd", { text: value || "—" }),
  ]));
}

function prose(text) {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  if (!blocks.length) return el("div", { class: "crs-prose" }, el("p", { class: "crs-note", text: "본문이 없습니다." }));
  // Single line breaks inside a block stay as <br>; every piece is a text node.
  return el("div", { class: "crs-prose" }, ...blocks.map((block) => {
    const p = el("p", {});
    block.split("\n").forEach((line, index) => {
      if (index) p.append(el("br", {}));
      p.append(line);
    });
    return p;
  }));
}

function shownAt(notice) {
  if (!notice.publishedAt) return "임시 저장";
  return `${koDateTime(notice.publishedAt)}${notice.published ? "" : " 예약"}`;
}

/** The notice dialog (`?pop=notice&id=`) in backend mode. */
export function remoteNoticeDialog(ctx, course) {
  const shell = (body) => dialogShell("공지사항", body, { className: "crs-modal" });
  if (backendStatus() === "anon") {
    return shell(el("div", { class: "crs-dialog" }, backendPanel({ text: LOGIN_TEXT })));
  }
  const courseId = courseIdFor(course.id);
  const noticeId = Number(ctx.params && ctx.params.get("id"));
  if (!courseId || !Number.isInteger(noticeId) || noticeId < 1) {
    return shell(el("div", { class: "crs-dialog" }, emptyState("공지를 찾을 수 없습니다", "목록으로 돌아가 다시 선택해 주세요.")));
  }
  const key = noticeKey(courseId, noticeId);
  const load = () => noticeDetails.load(key, courseId, noticeId);
  const body = el("div", { class: "crs-dialog", "aria-live": "polite" });
  const readNote = el("p", { class: "crs-note", role: "status" });

  const sendRead = (notice) => {
    if (!body.isConnected || !notice || notice.read) return;
    markNoticeRead(courseId, noticeId).catch((error) => {
      if (readNote.isConnected) readNote.textContent = `읽음 표시를 남기지 못했습니다. ${noticeMessage(error)}`;
    });
  };

  const paint = (entry) => {
    const notice = entry && entry.data;
    if (!notice) {
      if (!entry || !entry.error) body.replaceChildren(loadingState("공지를 불러오는 중입니다"));
      else if (missing(entry.error)) body.replaceChildren(emptyState("공지를 찾을 수 없습니다", "삭제되었거나 볼 수 있는 권한이 없습니다."));
      else body.replaceChildren(errorState("공지를 불러오지 못했습니다", noticeMessage(entry.error), retry(load)));
      return;
    }
    const instructor = roleIn(course.id) === "INSTRUCTOR";
    body.replaceChildren(
      el("h3", { class: "crs-dialog-title", text: notice.title }),
      noticeFlags(notice).length ? el("div", { class: "crs-post-meta" }, ...noticeFlags(notice).map((flag) => el("span", { class: "crs-kind", text: flag }))) : "",
      meta([
        ["작성자", notice.authorName || "담당 교수"],
        ["게시일", shownAt(notice)],
        ["조회", String(notice.viewCount)],
        instructor ? ["열람·확인", `열람 ${notice.readCount ?? 0} · 확인 ${notice.confirmCount ?? 0}`] : null,
      ]),
      prose(notice.body),
      readNote,
      el("div", { class: "crs-form-actions" }, notice.confirmed
        ? btn("확인한 공지입니다 · 닫기", { "data-act": "close" }, "secondary")
        : btn("공지를 확인했습니다", { "data-act": "notice-confirm", "data-course": String(courseId), "data-id": String(noticeId) }, "primary")),
    );
    sendRead(notice);
  };
  paint(noticeDetails.peek(key));
  queueMicrotask(() => {
    if (!body.isConnected) return;
    noticeDetails.watch(body, key, paint, null);
    const cached = noticeDetails.peek(key);
    if (freshVisit("notice-detail", key) === "entry" || !cached) load();
    else sendRead(cached.data);
  });
  return shell(body);
}
