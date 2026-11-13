// 강의실 게시판 목록 조각. 학생 질의응답(course/boards-server.js)과 교수 `pf_qna`(professor/professor.js)가
// 질의응답 목록(`qnaSection`)을 함께 쓰고, 열린게시판도 글 카드(`postCard`)와 상태 표시를 같이 쓴다.
// 목록은 서버 데이터만 그린다. 서버 문자열은 텍스트 노드로만 넣는다.
import { el, icon } from "../dom.js";
import { btn, go, emptyState, loadingState, errorState, pagination } from "../ui/components.js";
import { hrefFor, find } from "../manifest.js";
import { readQuery } from "./query.js";
import { ApiError } from "./api.js";
import { backendStatus, courseIdFor } from "./backend.js";
import { backendPanel } from "./backend-prompt.js";
import { freshVisit } from "./remote-cache.js";
import { koDate } from "./home-remote.js";
import { QNA_PAGE, QNA_STATUS, qnaListKey, qnaLists, boardMessage } from "./boards-remote.js";

export const QNA_LOGIN = "ASTRA 서버에 로그인하면 이 과목의 질의응답을 서버에서 불러옵니다.";
const SEARCH_DELAY = 300;
const STATUS_LABEL = { all: "전체", faq: "FAQ", solved: "해결", unsolved: "미해결" };
const COUNT_FIELD = { all: "total", faq: "faq", solved: "solved", unsolved: "unsolved" };

const missing = (error) => error instanceof ApiError && error.status === 404;

function retry(reload) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    reload();
  });
  return button;
}

export const kindTag = (text, extra = "") => el("span", { class: `crs-kind${extra}`, text });

export function statusTag(post) {
  if (post.faq) return kindTag("FAQ", " crs-kind-faq");
  return post.solved ? kindTag("해결", " crs-kind-ok") : kindTag("미해결");
}

/** The author line: the name only when the server sent it; otherwise "익명" for anonymous posts. */
export function authorText(post) {
  if (post.authorName) return post.anonymous ? `${post.authorName} (익명)` : post.authorName;
  return post.anonymous ? "익명" : "";
}

function counts(post) {
  return [
    el("span", { text: `조회 ${post.viewCount ?? 0}` }),
    el("span", { text: `추천 ${post.likeCount ?? 0}` }),
    post.answerCount != null ? el("span", { text: `답글 ${post.answerCount}` }) : null,
    post.commentCount != null ? el("span", { text: `댓글 ${post.commentCount}` }) : null,
  ];
}

export function postCard(post, href, qna) {
  return el("article", { class: `crs-post${post.read ? " is-read" : ""}` },
    el("a", { class: "crs-post-link", href, "data-go": href },
      el("div", {},
        el("h2", { text: post.title }),
        post.excerpt ? el("p", { text: post.excerpt }) : null,
        el("div", { class: "crs-post-meta" },
          qna ? statusTag(post) : null,
          post.secret ? kindTag("비공개") : null,
          post.mine ? kindTag("내 글") : null,
          post.read ? null : kindTag("안 읽음", " crs-kind-new"),
          authorText(post) ? el("span", { text: authorText(post) }) : null,
          el("span", { text: koDate(post.createdAt) }),
          ...counts(post),
        ),
      ),
    ),
  );
}

function searchField(label, ctx, scope) {
  const value = readQuery(ctx.state, scope);
  return el("div", { class: "crs-search" },
    icon("search"),
    el("input", {
      type: "text",
      "data-name": "surfaceQuery",
      "data-scope": scope,
      "data-focus": scope,
      value,
      placeholder: "검색",
      "aria-label": label,
      autocomplete: "off",
    }),
    value ? el("button", { type: "button", class: "crs-search-clear", "data-act": "clear-query", "data-scope": scope, "aria-label": "검색어 지우기", text: "×" }) : null,
  );
}

/** A node to show instead of server data (`anon`, unlinked course), or null. */
export function gate(course, loginText) {
  if (backendStatus() === "anon") return backendPanel({ text: loginText });
  if (!courseIdFor(course.id)) return emptyState("이 과목은 ASTRA 서버에 없습니다", "서버에 등록된 과목만 게시판을 볼 수 있습니다.");
  return null;
}

/** Loads on a new history entry, a new key (search typing waits for a pause), or an empty cache. */
export function visitList(resource, scope, key, load) {
  const fresh = freshVisit(scope, key);
  if (fresh === "entry") load();
  else if (!resource.peek(key)) {
    if (fresh === "key") setTimeout(() => { if (!resource.peek(key)) load(); }, SEARCH_DELAY);
    else load();
  }
}

function qnaFilter(ctx, scope) {
  const params = ctx.params || new URLSearchParams();
  const status = QNA_STATUS.includes(params.get("status")) ? params.get("status") : "all";
  return { status, mine: params.get("mine") === "1", answered: params.get("answered") === "1", q: readQuery(ctx.state, scope).trim().slice(0, 100) };
}

function filterParams(f, page) {
  return {
    status: f.status !== "all" ? f.status : null,
    mine: f.mine ? "1" : null,
    answered: f.answered ? "1" : null,
    page: page > 1 ? String(page) : null,
  };
}

/**
 * Filters, search, cards and page links of the course Q&A. `screenId` is the list screen
 * (`45_room_qna_list` for students, `pf_qna` for the instructor); the detail opens over it.
 */
export function qnaSection(ctx, course, screenId) {
  const root = el("div", { class: "crs-remote-board" });
  const blocked = gate(course, QNA_LOGIN);
  if (blocked) {
    root.append(blocked);
    return root;
  }
  const courseId = courseIdFor(course.id);
  const scope = "course:course-qna";
  const f = qnaFilter(ctx, scope);
  const page = Math.max(1, Math.floor(Number(ctx.params && ctx.params.get("page"))) || 1);
  const key = qnaListKey(courseId, f, page);
  const load = () => qnaLists.load(key, courseId, f, page);
  const listHref = (next, n = 1) => hrefFor(find(screenId), { kj: course.id, ...filterParams(next, n) });
  const itemHref = (id) => hrefFor(find(screenId), { kj: course.id, ...filterParams(f, page), pop: "qna", id: String(id) });
  const chips = el("div", { class: "crs-filter-row", role: "toolbar", "aria-label": "질문 상태" });
  const toggles = el("div", { class: "crs-filter-row", role: "toolbar", "aria-label": "내 질문과 답변" },
    ...[["mine", "나의 질문"], ["answered", "나의 답변"]].map(([name, label]) => el("button", {
      type: "button",
      class: `chip${f[name] ? " on" : ""}`,
      "aria-pressed": f[name] ? "true" : "false",
      "data-go": listHref({ ...f, [name]: !f[name] }),
      text: label,
    })),
  );
  const paintChips = (countsOf) => chips.replaceChildren(...QNA_STATUS.map((status) => el("button", {
    type: "button",
    class: `chip${f.status === status ? " on" : ""}`,
    "aria-pressed": f.status === status ? "true" : "false",
    "data-go": listHref({ ...f, status }),
    text: countsOf ? `${STATUS_LABEL[status]} ${countsOf[COUNT_FIELD[status]] ?? 0}` : STATUS_LABEL[status],
  })));
  const body = el("div", { class: "crs-remote", "aria-live": "polite" });
  root.append(
    el("div", { class: "crs-toolbar" }, el("div", { class: "crs-filters" }, chips, toggles), searchField("질의응답 제목 검색", ctx, scope)),
    body,
  );

  const paint = (entry) => {
    const data = entry && entry.data;
    paintChips(data ? data.counts : null);
    if (!data) {
      if (!entry || !entry.error) body.replaceChildren(loadingState("질문을 불러오는 중입니다"));
      else if (missing(entry.error)) body.replaceChildren(emptyState("질의응답을 볼 수 없습니다", "이 과목의 구성원이 아니거나 과목을 찾을 수 없습니다."));
      else body.replaceChildren(errorState("질문을 불러오지 못했습니다", boardMessage(entry.error, "질문"), retry(load)));
      return;
    }
    const items = Array.isArray(data.items) ? data.items : [];
    const pages = Math.max(1, Math.ceil(Number(data.total) / (Number(data.size) || QNA_PAGE)));
    const stale = entry.error ? el("p", { class: "err", role: "alert", text: boardMessage(entry.error, "질문") }) : "";
    if (!items.length) {
      const filtered = f.q || f.status !== "all" || f.mine || f.answered;
      body.replaceChildren(stale, page > 1 && data.total > 0
        ? emptyState("이 쪽에는 질문이 없습니다", "", go(listHref(f), "첫 쪽으로", { class: "btn btn-secondary" }))
        : filtered ? emptyState("조건에 맞는 질문이 없습니다", "다른 상태를 고르거나 검색어를 지워 보세요.")
          : emptyState("조회할 자료가 없습니다", "첫 질문을 남기면 이 목록에 표시됩니다."));
      return;
    }
    body.replaceChildren(
      stale,
      el("div", { class: "crs-posts" }, ...items.map((post) => postCard(post, itemHref(post.postId), true))),
      pagination({ page, pages, hrefForPage: (n) => listHref(f, n) }) || "",
    );
  };
  paint(qnaLists.peek(key));
  queueMicrotask(() => {
    if (!root.isConnected) return;
    qnaLists.watch(root, key, paint, load);
    visitList(qnaLists, "course-qna", key, load);
  });
  return root;
}
