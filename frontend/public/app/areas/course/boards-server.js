// Course Q&A (질의응답) and open board (열린게시판) from the ASTRA backend (status `anon` or
// `ready`). Lists, details, answers and comments are server data only; sample posts are never
// mixed in. The server decides visibility: a private question is simply absent for other
// students (404 on its detail), and an author name appears only when the response carries it.
// Commands go through `data-act="srv-board"`; typed text and picked attachments live in memory
// per form until the save succeeds or the session changes. Editing a question, an answer or an
// open-board post keeps or removes its files and adds new ones (`attachments: { keep, add }`);
// a comment's author edits its text in place. The Q&A list and the post cards are shared with the
// professor `pf_qna` (shared/board-list.js).
import { el, icon } from "../../dom.js";
import { btn, go, badge, tabs, emptyState, loadingState, errorState, dialogShell } from "../../ui/components.js";
import { hrefFor, find } from "../../manifest.js";
import { readQuery } from "../../shared/query.js";
import { ApiError } from "../../shared/api.js";
import { courseIdFor, roleIn, subscribe } from "../../shared/backend.js";
import { freshVisit } from "../../shared/remote-cache.js";
import { koDateTime } from "../../shared/home-remote.js";
import { downloadControl, sizeText } from "../../shared/uploads-remote.js";
import { attachPicker } from "../../shared/attach-picker.js";
import { QNA_LOGIN, kindTag, statusTag, authorText, postCard, gate, visitList, qnaSection } from "../../shared/board-list.js";
import {
  MAX_UPLOADS, MAX_TITLE, MAX_BODY, COMMENT_BYTES,
  openListKey, postKey, qnaDetails, openLists, openDetails, answerLists, commentLists,
  moreOpenPosts, moreAnswers, moreComments, isAppending,
  createQuestion, createOpenPost, updatePost, deletePost, setLike, markPostRead, setQuestionStatus,
  createAnswer, updateAnswer, deleteAnswer, createComment, updateComment, deleteComment, createUncertain,
  isCode, boardMessage, legacyBytes,
} from "../../shared/boards-remote.js";

const OPEN_LOGIN = "ASTRA 서버에 로그인하면 이 과목의 열린게시판을 서버에서 불러옵니다.";

const missing = (error) => error instanceof ApiError && error.status === 404;

// ---------- memory per form (cleared when the session changes) ----------

const memos = new Map();
const editing = new Map(); // postKey -> answerId being edited inline
const commentEditing = new Map(); // postKey -> commentId being edited inline
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  memos.clear();
  editing.clear();
  commentEditing.clear();
});

function memoOf(key) {
  if (!memos.has(key)) memos.set(key, { title: "", body: "", anonymous: false, secret: false, uploads: [], parent: null, loaded: false, repaint: null });
  return memos.get(key);
}

// ---------- commands through data-act="srv-board" ----------

const commands = new WeakMap(); // button -> { run, op }
const inflight = new Set(); // operation keys being sent; a repainted button stays disabled

function cmd(label, kind, run, { op = null, attrs = {} } = {}) {
  const node = btn(label, { ...attrs, "data-act": "srv-board" }, kind);
  commands.set(node, { run, op });
  if (op && inflight.has(op)) {
    node.disabled = true;
    node.setAttribute("aria-busy", "true");
  }
  return node;
}

/** `srv-board` (course/actions.js). */
export function runBoardCommand(node, api) {
  const entry = commands.get(node);
  if (!entry || node.disabled) return;
  void entry.run(node, api, entry.op);
}

/** Disables `node` with `aria-busy` while `work` runs; the same operation never runs twice at once. */
async function sending(node, text, op, work) {
  if (node.disabled || (op && inflight.has(op))) return undefined;
  const label = node.textContent;
  if (op) inflight.add(op);
  node.disabled = true;
  node.setAttribute("aria-busy", "true");
  if (text) node.textContent = text;
  try {
    return await work();
  } finally {
    if (op) inflight.delete(op);
    if (node.isConnected) {
      node.disabled = false;
      node.removeAttribute("aria-busy");
      node.textContent = label;
    }
    // A repaint while the command ran drew a fresh button for the same operation, disabled; free it.
    if (op) {
      for (const other of document.querySelectorAll('[data-act="srv-board"][aria-busy="true"]')) {
        const entry = commands.get(other);
        if (other !== node && entry && entry.op === op) {
          other.disabled = false;
          other.removeAttribute("aria-busy");
        }
      }
    }
  }
}

/** Two presses: the first arms the button, the second runs `work`. */
function deleteButton(label, op, work) {
  return cmd("삭제", "ghost", (node, api) => {
    if (node.dataset.armed !== "1") {
      node.dataset.armed = "1";
      node.textContent = "한 번 더 누르면 삭제";
      node.setAttribute("aria-label", `${label} 삭제, 한 번 더 누르면 삭제`);
      return undefined;
    }
    return sending(node, "삭제하는 중…", op, () => work(api));
  }, { op, attrs: { "aria-label": `${label} 삭제` } });
}

function toast(api, text) {
  api.getState().toast = text;
  api.paintToast();
}

// ---------- small views ----------

function retry(reload) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    reload();
  });
  return button;
}

/** Plain text in paragraphs; every piece is a text node. */
function prose(text, empty = "본문이 없습니다.") {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  if (!blocks.length) return empty ? el("div", { class: "crs-prose" }, el("p", { class: "crs-note", text: empty })) : null;
  return el("div", { class: "crs-prose" }, ...blocks.map((block) => {
    const p = el("p", {});
    block.split("\n").forEach((line, index) => {
      if (index) p.append(el("br", {}));
      p.append(line);
    });
    return p;
  }));
}

function fileLine(courseId, file) {
  const { button, note } = downloadControl(courseId, file);
  return el("div", { class: "file-row" }, icon("file"),
    el("span", {}, el("b", { text: file.name }), el("span", { class: "tiny", text: sizeText(file.sizeBytes) }), note),
    button);
}

function files(courseId, list) {
  const items = Array.isArray(list) ? list : [];
  return items.length ? el("div", { class: "crs-files", "aria-label": "첨부 파일" }, ...items.map((file) => fileLine(courseId, file))) : null;
}

/** The current address with `changes` applied (null removes a parameter). */
function here(ctx, changes) {
  const params = new URLSearchParams(ctx.params ? ctx.params.toString() : "");
  for (const [name, value] of Object.entries(changes)) {
    if (value == null || value === "") params.delete(name);
    else params.set(name, String(value));
  }
  const q = params.toString();
  return `${location.pathname}${q ? `?${q}` : ""}`;
}

function idParam(ctx) {
  const id = Number(ctx.params && ctx.params.get("id"));
  return Number.isInteger(id) && id > 0 ? id : null;
}

// ---------- attachments ----------

/**
 * Keeps the form's keep/remove choices in step with the files the server now lists: a file that is
 * gone leaves the choice, a file someone else attached since starts kept.
 */
function syncKeep(memo, existing) {
  const ids = existing.map((file) => file.fileId);
  if (memo.keep instanceof Set && memo.seen instanceof Set) {
    memo.keep = new Set([...memo.keep].filter((fileId) => ids.includes(fileId)));
    for (const fileId of ids) if (!memo.seen.has(fileId)) memo.keep.add(fileId);
  }
  memo.seen = new Set(ids);
}

/** `post_attachment` picker of one form; `existing` is the post's or answer's current files (edit). */
function boardPicker(memo, courseId, existing, onChange) {
  const files = Array.isArray(existing) ? existing : [];
  syncKeep(memo, files);
  const picker = attachPicker({ memo, courseId, purpose: "post_attachment", max: MAX_UPLOADS, existing: files, onChange });
  picker.node = el("div", { class: "crs-attach" }, picker.node);
  return picker;
}

/** The edit body's `attachments`, only when the user changed the files (leaving it out keeps them). */
function attachmentChange(picker) {
  return picker.changed() ? { attachments: picker.attachments() } : {};
}

function textInput(memo, field, attrs) {
  const input = el(attrs.multiline ? "textarea" : "input", {
    ...(attrs.multiline ? { rows: attrs.rows || "8" } : { type: "text" }),
    maxlength: attrs.max ? String(attrs.max) : null,
    placeholder: attrs.placeholder || "",
    "aria-label": attrs.label,
  });
  input.value = memo[field] || "";
  input.addEventListener("input", () => { memo[field] = input.value; if (attrs.onInput) attrs.onInput(); });
  return el("label", { class: "field" }, el("span", { class: "field-label", text: attrs.label }), input);
}

function checkbox(memo, field, label, hint) {
  const input = el("input", { type: "checkbox" });
  input.checked = Boolean(memo[field]);
  input.addEventListener("change", () => { memo[field] = input.checked; });
  return el("label", { class: "crs-check" }, input, el("span", {}, el("b", { text: label }), hint ? el("span", { class: "tiny", text: hint }) : null));
}

function sayLine() {
  const node = el("p", { class: "tiny", role: "status", "aria-live": "polite" });
  node.say = (text, alert) => {
    node.setAttribute("role", alert ? "alert" : "status");
    node.className = alert ? "err" : "tiny";
    node.textContent = text || "";
  };
  return node;
}

// ---------- Q&A list ----------

/** The course Q&A screen (`45_room_qna_list`) in backend mode. `parts` are course.js builders. */
export function remoteQnaBoard(screen, ctx, course, parts) {
  const student = roleIn(course.id) === "STUDENT";
  const write = student ? go(hrefFor(find("45b_room_qna_insert_pop"), { kj: course.id }), "질문 작성", { class: "btn btn-primary" }) : null;
  return el("section", { class: "crs" }, parts.head("질의응답", "chat", null, ...(write ? [write] : [])), qnaSection(ctx, course, screen.id));
}

// ---------- Q&A detail, answers, answer form ----------

/** `?pop=qna&id=` (45d) and `?pop=qna-reply&id=` (45e, the answer form open) in backend mode. */
export function remoteQnaDialog(ctx, course, name) {
  const shell = (node) => dialogShell("질의응답", node, { className: "crs-modal", wide: true });
  const blocked = gate(course, QNA_LOGIN);
  const id = idParam(ctx);
  if (blocked || !id) return shell(el("div", { class: "crs-dialog" }, blocked || emptyState("질문을 찾을 수 없습니다", "목록으로 돌아가 다시 선택해 주세요.")));
  const courseId = courseIdFor(course.id);
  const key = postKey(courseId, "qna", id);
  const load = () => qnaDetails.load(key, courseId, id);
  const loadAnswers = () => answerLists.load(key, courseId, id);
  const instructor = roleIn(course.id) === "INSTRUCTOR";
  const head = el("div", { "aria-live": "polite" });
  const thread = el("section", { class: "crs-thread", "aria-label": "답글" });
  const formSlot = el("div", {});
  const readNote = el("p", { class: "crs-note", role: "status" });
  const body = el("div", { class: "crs-dialog" }, head, readNote, thread, formSlot);

  const sendRead = (post) => {
    if (!head.isConnected || !post || post.read) return;
    markPostRead("qna", courseId, id).catch((error) => {
      if (readNote.isConnected) readNote.textContent = `읽음 표시를 남기지 못했습니다. ${boardMessage(error, "질문")}`;
    });
  };

  const paintPost = (entry) => {
    const post = entry && entry.data;
    if (!post) {
      thread.hidden = true;
      formSlot.replaceChildren();
      if (!entry || !entry.error) head.replaceChildren(loadingState("질문을 불러오는 중입니다"));
      else if (missing(entry.error)) head.replaceChildren(emptyState("질문을 찾을 수 없습니다", "삭제되었거나 볼 수 있는 권한이 없습니다."));
      else head.replaceChildren(errorState("질문을 불러오지 못했습니다", boardMessage(entry.error, "질문"), retry(load)));
      return;
    }
    thread.hidden = false;
    head.replaceChildren(questionView(post, courseId, id, ctx, entry.error));
    if (name === "qna-reply" && !formSlot.firstChild) formSlot.replaceChildren(answerForm(courseId, id, ctx, instructor));
    if (name !== "qna-reply") formSlot.replaceChildren(el("div", { class: "crs-form-actions" }, go(here(ctx, { pop: "qna-reply" }), "답글 쓰기", { class: "btn btn-primary" })));
    sendRead(post);
  };
  const paintAnswers = (entry) => thread.replaceChildren(...answersView(entry, courseId, id, key, loadAnswers, paintAnswers));

  paintPost(qnaDetails.peek(key));
  paintAnswers(answerLists.peek(key));
  queueMicrotask(() => {
    if (!body.isConnected) return;
    qnaDetails.watch(head, key, paintPost, null);
    answerLists.watch(thread, key, paintAnswers, null);
    const fresh = freshVisit("qna-detail", key) === "entry";
    if (fresh || !qnaDetails.peek(key)) load();
    else sendRead(qnaDetails.peek(key).data);
    if (fresh || !answerLists.peek(key)) loadAnswers();
  });
  return shell(body);
}

function questionView(post, courseId, id, ctx, staleError) {
  const like = cmd(post.liked ? `추천 취소 · ${post.likeCount}` : `추천 · ${post.likeCount}`, "secondary", (node, api, op) => sending(node, "", op, async () => {
    try {
      await setLike("qna", courseId, id, !post.liked);
    } catch (error) {
      toast(api, boardMessage(error, "질문"));
    }
  }), { op: `like:${courseId}:qna:${id}`, attrs: { "aria-pressed": post.liked ? "true" : "false" } });
  const actions = [like];
  if (post.canSetSolved) {
    actions.push(cmd(post.solved ? "미해결로 되돌리기" : "해결로 표시", "secondary", (node, api, op) => sending(node, "바꾸는 중…", op, async () => {
      try {
        await setQuestionStatus(courseId, id, { solved: !post.solved });
        toast(api, post.solved ? "미해결로 되돌렸습니다" : "해결로 표시했습니다");
      } catch (error) {
        toast(api, boardMessage(error, "질문"));
      }
    }), { op: `status:${courseId}:${id}` }));
  }
  if (post.canSetFaq) {
    actions.push(cmd(post.faq ? "FAQ 해제" : "FAQ로 지정", "secondary", (node, api, op) => sending(node, "바꾸는 중…", op, async () => {
      try {
        await setQuestionStatus(courseId, id, { faq: !post.faq });
        toast(api, post.faq ? "FAQ에서 뺐습니다" : "FAQ로 지정했습니다");
      } catch (error) {
        toast(api, boardMessage(error, "질문"));
      }
    }), { op: `status:${courseId}:${id}` }));
  }
  if (post.canEdit) actions.push(go(here(ctx, { pop: "qna-write" }), "수정", { class: "btn btn-secondary" }));
  if (post.canDelete) {
    actions.push(deleteButton(post.title, `delete:${courseId}:qna:${id}`, async (api) => {
      try {
        await deletePost("qna", courseId, id);
        api.getState().toast = "질문을 삭제했습니다";
        api.closeDialog();
      } catch (error) {
        if (missing(error)) {
          api.getState().toast = "이미 삭제되었거나 찾을 수 없는 질문입니다";
          api.closeDialog();
          return;
        }
        toast(api, boardMessage(error, "질문"));
        if (isCode(error, 409, "already_answered")) qnaDetails.load(postKey(courseId, "qna", id), courseId, id);
      }
    }));
  }
  const locked = post.mine && !post.canEdit && post.answerCount > 0;
  return el("article", { class: "crs-question" },
    el("h3", { class: "crs-dialog-title", text: post.title }),
    el("div", { class: "crs-post-meta" }, statusTag(post), post.secret ? kindTag("비공개") : null, post.anonymous ? kindTag("익명") : null),
    staleError ? el("p", { class: "err", role: "alert", text: boardMessage(staleError, "질문") }) : null,
    el("dl", { class: "crs-meta pairs" }, ...[
      ["작성자", authorText(post) || "—"],
      ["작성일", koDateTime(post.createdAt)],
      post.edited === true ? ["수정", "수정됨"] : null,
      ["조회 · 추천 · 답글", `${post.viewCount} · ${post.likeCount} · ${post.answerCount ?? 0}`],
    ].filter(Boolean).flatMap(([label, value]) => [el("dt", { text: label }), el("dd", { text: value })])),
    post.secret ? el("p", { class: "crs-note", text: "비공개 질문입니다. 작성자와 담당 교수만 봅니다." }) : null,
    prose(post.body),
    files(courseId, post.attachments),
    el("div", { class: "crs-post-actions" }, ...actions),
    locked ? el("p", { class: "crs-note", text: "답글이 달린 질문은 고치거나 지울 수 없습니다." }) : null,
  );
}

function answersView(entry, courseId, id, key, reload, repaint) {
  const data = entry && entry.data;
  const title = el("h3", { text: data ? `답글 ${data.items.length}${data.nextCursor != null ? "+" : ""}` : "답글" });
  if (!data) {
    if (!entry || !entry.error) return [title, loadingState("답글을 불러오는 중입니다")];
    if (missing(entry.error)) return [];
    return [title, errorState("답글을 불러오지 못했습니다", boardMessage(entry.error, "답글"), retry(reload))];
  }
  const nodes = [title];
  if (entry.error) nodes.push(el("p", { class: "err", role: "alert", text: boardMessage(entry.error, "답글") }));
  if (!data.items.length) nodes.push(el("p", { class: "crs-note", text: "아직 답글이 없습니다." }));
  for (const answer of data.items) nodes.push(answerView(answer, courseId, id, key, repaint));
  if (data.nextCursor != null) {
    nodes.push(cmd(isAppending(key) ? "불러오는 중…" : "답글 더 보기", "ghost", (node, api, op) => sending(node, "불러오는 중…", op, async () => {
      try {
        await moreAnswers(courseId, id);
      } catch (error) {
        toast(api, boardMessage(error, "답글"));
      }
    }), { op: `more:${key}` }));
  }
  return nodes;
}

function answerView(answer, courseId, id, key, repaint) {
  const author = answer.authorName ? (answer.anonymous ? `${answer.authorName} (익명)` : answer.authorName) : answer.anonymous ? "익명" : "";
  const editingThis = editing.get(key) === answer.answerId && answer.canEdit;
  const actions = [];
  if (answer.canEdit && !editingThis) {
    const edit = btn("수정", { "aria-label": "답글 수정" }, "secondary");
    edit.addEventListener("click", () => {
      editing.set(key, answer.answerId);
      memos.delete(`answer-edit:${key}:${answer.answerId}`);
      const memo = memoOf(`answer-edit:${key}:${answer.answerId}`);
      memo.body = answer.body || "";
      memo.loaded = true;
      repaint(answerLists.peek(key));
    });
    actions.push(edit);
  }
  if (answer.canDelete) {
    actions.push(deleteButton("답글", `delete-answer:${key}:${answer.answerId}`, async (api) => {
      try {
        await deleteAnswer(courseId, id, answer.answerId);
        toast(api, "답글을 삭제했습니다");
      } catch (error) {
        toast(api, missing(error) ? "이미 삭제되었거나 찾을 수 없는 답글입니다" : boardMessage(error, "답글"));
      }
    }));
  }
  return el("article", { class: `crs-answer${answer.instructor ? " is-instructor" : ""}` },
    el("div", { class: "crs-post-meta" },
      answer.instructor ? kindTag("담당 교수", " crs-kind-faq") : null,
      author ? el("b", { text: author }) : null,
      el("span", { text: koDateTime(answer.createdAt) }),
      answer.edited === true ? el("span", { text: "수정됨" }) : null,
    ),
    editingThis ? answerEditForm(answer, courseId, id, key, repaint) : prose(answer.body, ""),
    editingThis ? null : files(courseId, answer.attachments),
    actions.length ? el("div", { class: "crs-post-actions" }, ...actions) : null,
  );
}

/** Inline answer edit: text plus keep/remove of its files and new ones. Rebuilt on every thread repaint. */
function answerEditForm(answer, courseId, id, key, repaint) {
  const memoKey = `answer-edit:${key}:${answer.answerId}`;
  const memo = memoOf(memoKey);
  const say = sayLine();
  // The message survives the repaint that a 409 reload causes.
  const tell = (text) => { memo.notice = text; say.say(text, true); };
  if (memo.notice) say.say(memo.notice, true);
  const cancel = btn("취소", {}, "ghost");
  cancel.addEventListener("click", () => {
    editing.delete(key);
    memos.delete(memoKey);
    repaint(answerLists.peek(key));
  });
  let save = null;
  const picker = boardPicker(memo, courseId, answer.attachments, () => { if (save && !save.getAttribute("aria-busy")) save.disabled = picker.pending(); });
  save = cmd("저장", "primary", (node, api, op) => sending(node, "저장하는 중…", op, async () => {
    memo.notice = "";
    if (!memo.body.trim()) return tell("답글 내용을 입력하세요.");
    if (picker.pending()) return tell("첨부 파일 검사가 끝난 뒤 저장하세요.");
    try {
      await updateAnswer(courseId, id, answer.answerId, { body: memo.body, anonymous: Boolean(answer.anonymous), version: answer.version, ...attachmentChange(picker) });
      editing.delete(key);
      memos.delete(memoKey);
      toast(api, "답글을 고쳤습니다");
      repaint(answerLists.peek(key));
    } catch (error) {
      tell(boardMessage(error, "답글"));
      if (isCode(error, 409, "attachment_not_ready")) picker.recheck();
      if (isCode(error, 409) || missing(error)) answerLists.load(key, courseId, id);
    }
    return undefined;
  }), { op: memoKey });
  save.disabled = save.disabled || picker.pending();
  return el("div", { class: "crs-form" },
    textInput(memo, "body", { label: "답글", multiline: true, rows: "5", max: MAX_BODY }),
    picker.node,
    say,
    el("div", { class: "crs-post-actions" }, cancel, save),
  );
}

function answerForm(courseId, id, ctx, instructor) {
  const key = `answer:${postKey(courseId, "qna", id)}`;
  const memo = memoOf(key);
  const say = sayLine();
  let submit = null;
  const picker = boardPicker(memo, courseId, [], () => { if (submit && !submit.getAttribute("aria-busy")) submit.disabled = picker.pending(); });
  submit = cmd("답글 등록", "primary", (node, api, op) => sending(node, "등록하는 중…", op, async () => {
    if (!memo.body.trim()) return say.say("답글 내용을 입력하세요.", true);
    if (picker.pending()) return say.say("첨부 파일 검사가 끝난 뒤 등록하세요.", true);
    try {
      await createAnswer(courseId, id, { body: memo.body, anonymous: instructor ? false : Boolean(memo.anonymous), uploadIds: picker.uploadIds() });
      memos.delete(key);
      api.getState().toast = "답글을 등록했습니다";
      api.navigate(here(ctx, { pop: "qna" }));
    } catch (error) {
      if (isCode(error, 409, "attachment_not_ready")) picker.recheck();
      if (createUncertain(`answer:${courseId}:${id}`)) say.say("등록 결과를 확인하지 못했습니다. 네트워크를 확인한 뒤 같은 내용으로 다시 등록하세요. 이미 등록되었다면 같은 답글을 돌려받습니다.", true);
      else say.say(boardMessage(error, "답글"), true);
    }
    return undefined;
  }), { op: `create:${key}` });
  return el("section", { class: "crs-form", "aria-label": "답글 쓰기" },
    el("h3", { text: "답글 쓰기" }),
    textInput(memo, "body", { label: "답글", multiline: true, rows: "6", max: MAX_BODY, placeholder: "답글을 입력하세요" }),
    instructor ? null : checkbox(memo, "anonymous", "익명", "다른 학생에게 이름을 숨깁니다. 담당 교수에게는 보입니다."),
    picker.node,
    say,
    el("div", { class: "crs-post-actions" }, go(here(ctx, { pop: "qna" }), "취소", { class: "btn btn-ghost" }), submit),
  );
}

// ---------- question write / edit (45b) ----------

/** `?pop=qna-write` (new) and `?pop=qna-write&id=` (edit) in backend mode. */
export function remoteQnaWriteDialog(ctx, course) {
  const id = idParam(ctx);
  const shell = (node) => dialogShell(id ? "질문 수정" : "질문 작성", node, { className: "crs-modal", wide: true });
  const blocked = gate(course, QNA_LOGIN);
  if (blocked) return shell(el("div", { class: "crs-dialog" }, blocked));
  const courseId = courseIdFor(course.id);
  if (!id && roleIn(course.id) !== "STUDENT") {
    return shell(el("div", { class: "crs-dialog" }, emptyState("질문은 수강생만 쓸 수 있습니다", "담당 교수는 질문 상세에서 답글을 씁니다.")));
  }
  return shell(postForm({ ctx, courseId, kind: "qna", id, noun: "질문" }));
}

/** `?pop=board-write` (new) and `?pop=board-write&id=` (edit) in backend mode. */
export function remoteOpenWriteDialog(ctx, course) {
  const id = idParam(ctx);
  const shell = (node) => dialogShell(id ? "글 수정" : "글쓰기", node, { className: "crs-modal", wide: true });
  const blocked = gate(course, OPEN_LOGIN);
  if (blocked) return shell(el("div", { class: "crs-dialog" }, blocked));
  return shell(postForm({ ctx, courseId: courseIdFor(course.id), kind: "open", id, noun: "글" }));
}

function postForm({ ctx, courseId, kind, id, noun }) {
  const qna = kind === "qna";
  const details = qna ? qnaDetails : openDetails;
  const memoKey = `${kind}-form:${courseId}:${id || "new"}`;
  const memo = memoOf(memoKey);
  const frame = el("div", { class: "crs-dialog crs-form" });
  const say = sayLine();
  const viewPop = qna ? "qna" : "board-post";
  const key = id ? postKey(courseId, kind, id) : null;
  let picker = null;
  let current = null; // the post being edited, as last read
  let submit = null;
  let mounted = null; // file ids the picker shows (edit), to rebuild it when a reload changes them
  const pickerSlot = el("div", {});
  const refresh = () => {
    if (!submit || submit.getAttribute("aria-busy")) return;
    submit.disabled = Boolean((picker && picker.pending()) || (id && (!current || !current.canEdit)));
  };
  const filesOf = (post) => (post && Array.isArray(post.attachments) ? post.attachments : []);
  const mountPicker = () => {
    const existing = id ? filesOf(current) : [];
    mounted = existing.map((file) => file.fileId).join(",");
    picker = boardPicker(memo, courseId, existing, refresh);
    pickerSlot.replaceChildren(picker.node);
  };

  const build = () => {
    submit = cmd("저장", "primary", (node, api, op) => sending(node, "저장하는 중…", op, () => save(api)), { op: `save:${memoKey}` });
    mountPicker();
    frame.replaceChildren(
      textInput(memo, "title", { label: "제목", max: MAX_TITLE, placeholder: "제목을 입력하세요" }),
      textInput(memo, "body", { label: "내용", multiline: true, rows: "10", max: MAX_BODY, placeholder: "내용을 입력하세요" }),
      qna ? checkbox(memo, "anonymous", "익명", "다른 학생에게 이름을 숨깁니다. 담당 교수에게는 보입니다.") : null,
      qna ? checkbox(memo, "secret", "비공개", "작성자와 담당 교수만 이 질문을 봅니다. 다른 학생의 목록과 검색에도 나오지 않습니다.") : null,
      pickerSlot,
      say,
      el("div", { class: "crs-post-actions" }, btn("취소", { "data-act": "close" }, "ghost"), submit),
    );
    refresh();
  };

  async function save(api) {
    const title = memo.title.trim();
    if (!title) return say.say("제목을 입력하세요.", true);
    if (picker.pending()) return say.say("첨부 파일 검사가 끝난 뒤 저장하세요.", true);
    const text = { title, body: memo.body.trim() ? memo.body : null };
    if (qna) Object.assign(text, { anonymous: Boolean(memo.anonymous), secret: Boolean(memo.secret) });
    try {
      let post;
      if (id) {
        if (!current) return say.say(`${noun}을 불러온 뒤 저장하세요.`, true);
        post = await updatePost(kind, courseId, id, { ...text, version: current.version, ...attachmentChange(picker) });
      } else {
        const payload = { ...text, uploadIds: picker.uploadIds() };
        post = qna ? await createQuestion(courseId, payload) : await createOpenPost(courseId, payload);
      }
      memos.delete(memoKey);
      api.getState().toast = id ? `${noun}을 고쳤습니다` : `${noun}을 등록했습니다`;
      api.navigate(here(ctx, { pop: viewPop, id: String(post.postId) }));
    } catch (error) {
      if (id && (isCode(error, 409) || missing(error))) details.load(key, courseId, id);
      if (isCode(error, 409, "attachment_not_ready")) picker.recheck();
      const op = qna ? `qna:${courseId}` : `open:${courseId}`;
      if (!id && createUncertain(op)) say.say("저장 결과를 확인하지 못했습니다. 네트워크를 확인한 뒤 같은 내용으로 다시 저장하세요. 이미 등록되었다면 같은 글을 돌려받습니다.", true);
      else say.say(boardMessage(error, noun), true);
    }
    return undefined;
  }

  if (!id) {
    build();
    return frame;
  }
  const load = () => details.load(key, courseId, id);
  const paint = (entry) => {
    const post = entry && entry.data;
    if (!post) {
      if (current) {
        if (entry && entry.error) say.say(boardMessage(entry.error, noun), true);
        return;
      }
      if (!entry || !entry.error) frame.replaceChildren(loadingState(`${noun}을 불러오는 중입니다`));
      else if (missing(entry.error)) frame.replaceChildren(emptyState(`${noun}을 찾을 수 없습니다`, "삭제되었거나 고칠 수 있는 권한이 없습니다."));
      else frame.replaceChildren(errorState(`${noun}을 불러오지 못했습니다`, boardMessage(entry.error, noun), retry(load)));
      return;
    }
    const first = !current;
    current = post;
    if (!memo.loaded) {
      // Typed text survives re-renders and 409 reloads; the server copy only fills an untouched form.
      Object.assign(memo, { title: post.title || "", body: post.body || "", anonymous: Boolean(post.anonymous), secret: Boolean(post.secret), loaded: true });
    }
    if (first) build();
    // A reload after 409 may list other files: show them, keeping the choices that still apply.
    else if (filesOf(post).map((file) => file.fileId).join(",") !== mounted) mountPicker();
    if (!post.canEdit) {
      say.say(qna && post.answerCount > 0 ? "답글이 달린 질문은 고칠 수 없습니다." : `이 ${noun}을 고칠 수 없습니다.`, true);
    }
    refresh();
  };
  paint(details.peek(key));
  queueMicrotask(() => {
    if (!frame.isConnected) return;
    details.watch(frame, key, paint, null);
    // The edit form always starts from the latest version.
    if (freshVisit(`${kind}-edit`, key) === "entry" || !details.peek(key)) load();
  });
  return frame;
}

// ---------- open board list ----------

/** The open board screen (`47_room_open_board_list`) in backend mode. */
export function remoteOpenBoard(screen, ctx, course, parts) {
  const header = parts.head("열린게시판", "layers", null);
  const root = el("section", { class: "crs" }, header);
  const blocked = gate(course, OPEN_LOGIN);
  if (blocked) {
    root.append(blocked);
    return root;
  }
  const courseId = courseIdFor(course.id);
  const scope = "course:open";
  const q = readQuery(ctx.state, scope).trim().slice(0, 100);
  const key = openListKey(courseId, q);
  const load = () => openLists.load(key, courseId, q);
  const totalSlot = el("span", {});
  header.querySelector(".crs-head-title").append(totalSlot);
  header.append(el("div", { class: "crs-head-actions" }, go(hrefFor(find("47b_room_open_board_insert_pop"), { kj: course.id }), "글쓰기", { class: "btn btn-primary" })));
  const body = el("div", { class: "crs-remote", "aria-live": "polite" });
  root.append(el("div", { class: "crs-toolbar" }, tabs([{ label: "전체", on: true }]), parts.searchBox("열린게시판 제목 검색", ctx, "open")), body);
  const itemHref = (postId) => hrefFor(find(screen.id), { kj: course.id, pop: "board-post", id: String(postId) });

  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      totalSlot.replaceChildren();
      if (!entry || !entry.error) body.replaceChildren(loadingState("글을 불러오는 중입니다"));
      else if (missing(entry.error)) body.replaceChildren(emptyState("열린게시판을 볼 수 없습니다", "이 과목의 구성원이 아니거나 과목을 찾을 수 없습니다."));
      else body.replaceChildren(errorState("글을 불러오지 못했습니다", boardMessage(entry.error), retry(load)));
      return;
    }
    totalSlot.replaceChildren(badge(String(data.total ?? data.items.length), "neutral"));
    const stale = entry.error ? el("p", { class: "err", role: "alert", text: boardMessage(entry.error) }) : "";
    if (!data.items.length) {
      body.replaceChildren(stale, q ? emptyState("검색 결과가 없습니다", "제목에 들어 있는 다른 단어로 찾아 보세요.") : emptyState("조회된 자료가 없습니다.", "첫 글을 남기면 이 목록에 표시됩니다."));
      return;
    }
    body.replaceChildren(
      stale,
      el("div", { class: "crs-posts" }, ...data.items.map((post) => postCard(post, itemHref(post.postId), false))),
      data.nextCursor != null ? el("div", { class: "crs-form-actions" }, cmd(isAppending(key) ? "불러오는 중…" : "더 보기", "secondary", (node, api, op) => sending(node, "불러오는 중…", op, async () => {
        try {
          await moreOpenPosts(courseId, q);
        } catch (error) {
          toast(api, boardMessage(error));
        }
      }), { op: `more:${key}` })) : "",
    );
  };
  paint(openLists.peek(key));
  queueMicrotask(() => {
    if (!root.isConnected) return;
    // On focus, re-read only a list still on its first page; a longer one keeps what was read.
    openLists.watch(root, key, paint, () => { const entry = openLists.peek(key); if (!entry || !entry.data || entry.data.pages === 1) load(); });
    visitList(openLists, "course-open", key, load);
  });
  return root;
}

// ---------- open board post, comments ----------

/** `?pop=board-post&id=` over the open board in backend mode. */
export function remoteOpenPostDialog(ctx, course) {
  const shell = (node) => dialogShell("열린게시판", node, { className: "crs-modal", wide: true });
  const blocked = gate(course, OPEN_LOGIN);
  const id = idParam(ctx);
  if (blocked || !id) return shell(el("div", { class: "crs-dialog" }, blocked || emptyState("글을 찾을 수 없습니다", "목록으로 돌아가 다시 선택해 주세요.")));
  const courseId = courseIdFor(course.id);
  const key = postKey(courseId, "open", id);
  const load = () => openDetails.load(key, courseId, id);
  const loadComments = () => commentLists.load(key, courseId, id);
  const head = el("div", { "aria-live": "polite" });
  const thread = el("section", { class: "crs-thread", "aria-label": "댓글" });
  const readNote = el("p", { class: "crs-note", role: "status" });
  const body = el("div", { class: "crs-dialog" }, head, readNote, thread);

  const sendRead = (post) => {
    if (!head.isConnected || !post || post.read) return;
    markPostRead("open", courseId, id).catch((error) => {
      if (readNote.isConnected) readNote.textContent = `읽음 표시를 남기지 못했습니다. ${boardMessage(error)}`;
    });
  };
  const paintPost = (entry) => {
    const post = entry && entry.data;
    if (!post) {
      thread.hidden = true;
      if (!entry || !entry.error) head.replaceChildren(loadingState("글을 불러오는 중입니다"));
      else if (missing(entry.error)) head.replaceChildren(emptyState("글을 찾을 수 없습니다", "삭제되었거나 볼 수 있는 권한이 없습니다."));
      else head.replaceChildren(errorState("글을 불러오지 못했습니다", boardMessage(entry.error), retry(load)));
      return;
    }
    thread.hidden = false;
    head.replaceChildren(openPostView(post, courseId, id, ctx, entry.error));
    sendRead(post);
  };
  const paintComments = (entry) => thread.replaceChildren(...commentsView(entry, courseId, id, key, loadComments, paintComments));
  paintPost(openDetails.peek(key));
  paintComments(commentLists.peek(key));
  queueMicrotask(() => {
    if (!body.isConnected) return;
    openDetails.watch(head, key, paintPost, null);
    commentLists.watch(thread, key, paintComments, null);
    const fresh = freshVisit("open-detail", key) === "entry";
    if (fresh || !openDetails.peek(key)) load();
    else sendRead(openDetails.peek(key).data);
    if (fresh || !commentLists.peek(key)) loadComments();
  });
  return shell(body);
}

function openPostView(post, courseId, id, ctx, staleError) {
  const actions = [cmd(post.liked ? `추천 취소 · ${post.likeCount}` : `추천 · ${post.likeCount}`, "secondary", (node, api, op) => sending(node, "", op, async () => {
    try {
      await setLike("open", courseId, id, !post.liked);
    } catch (error) {
      toast(api, boardMessage(error));
    }
  }), { op: `like:${courseId}:open:${id}`, attrs: { "aria-pressed": post.liked ? "true" : "false" } })];
  if (post.canEdit) actions.push(go(here(ctx, { pop: "board-write" }), "수정", { class: "btn btn-secondary" }));
  if (post.canDelete) {
    actions.push(deleteButton(post.title, `delete:${courseId}:open:${id}`, async (api) => {
      try {
        await deletePost("open", courseId, id);
        api.getState().toast = "글을 삭제했습니다";
        api.closeDialog();
      } catch (error) {
        if (missing(error)) {
          api.getState().toast = "이미 삭제되었거나 찾을 수 없는 글입니다";
          api.closeDialog();
          return;
        }
        toast(api, boardMessage(error));
      }
    }));
  }
  return el("article", { class: "crs-question" },
    el("h3", { class: "crs-dialog-title", text: post.title }),
    staleError ? el("p", { class: "err", role: "alert", text: boardMessage(staleError) }) : null,
    el("dl", { class: "crs-meta pairs" }, ...[
      ["작성자", post.authorName || "—"],
      ["작성일", koDateTime(post.createdAt)],
      post.edited === true ? ["수정", "수정됨"] : null,
      ["조회 · 추천 · 댓글", `${post.viewCount} · ${post.likeCount} · ${post.commentCount ?? 0}`],
    ].filter(Boolean).flatMap(([label, value]) => [el("dt", { text: label }), el("dd", { text: value })])),
    prose(post.body),
    files(courseId, post.attachments),
    el("div", { class: "crs-post-actions" }, ...actions),
  );
}

function commentsView(entry, courseId, id, key, reload, repaint) {
  const data = entry && entry.data;
  const nodes = [el("h3", { text: "댓글" })];
  if (!data) {
    if (!entry || !entry.error) return [...nodes, loadingState("댓글을 불러오는 중입니다")];
    if (missing(entry.error)) return [];
    return [...nodes, errorState("댓글을 불러오지 못했습니다", boardMessage(entry.error, "댓글"), retry(reload))];
  }
  if (entry.error) nodes.push(el("p", { class: "err", role: "alert", text: boardMessage(entry.error, "댓글") }));
  const memo = memoOf(`comment:${key}`);
  const tops = data.items.filter((item) => item.parentCommentId == null);
  const topIds = new Set(tops.map((item) => item.commentId));
  const replies = new Map();
  const orphans = [];
  for (const item of data.items) {
    if (item.parentCommentId == null) continue;
    if (!topIds.has(item.parentCommentId)) orphans.push(item);
    else replies.set(item.parentCommentId, [...(replies.get(item.parentCommentId) || []), item]);
  }
  const textarea = { node: null };
  const rerender = () => repaint(commentLists.peek(key));
  const list = el("ul", { class: "crs-comments" });
  for (const top of tops) {
    const children = replies.get(top.commentId) || [];
    list.append(el("li", {},
      commentView(top, courseId, id, key, memo, rerender, textarea),
      children.length ? el("ul", { class: "crs-comments crs-replies" }, ...children.map((reply) => el("li", {}, commentView(reply, courseId, id, key, memo, rerender, textarea)))) : null,
    ));
  }
  for (const orphan of orphans) list.append(el("li", {}, commentView(orphan, courseId, id, key, memo, rerender, textarea)));
  nodes.push(data.items.length ? list : el("p", { class: "crs-note", text: "아직 댓글이 없습니다." }));
  if (data.nextCursor != null) {
    nodes.push(cmd(isAppending(key) ? "불러오는 중…" : "댓글 더 보기", "ghost", (node, api, op) => sending(node, "불러오는 중…", op, async () => {
      try {
        await moreComments(courseId, id);
      } catch (error) {
        toast(api, boardMessage(error, "댓글"));
      }
    }), { op: `more:${key}` }));
  }
  nodes.push(commentForm(courseId, id, key, memo, data.items, rerender, textarea));
  return nodes;
}

function commentView(comment, courseId, id, key, memo, rerender, textarea) {
  if (comment.deleted) {
    return el("div", { class: "crs-comment is-deleted" }, el("p", { class: "crs-note", text: "삭제된 댓글입니다." }));
  }
  const editingThis = Boolean(comment.canEdit) && commentEditing.get(key) === comment.commentId;
  const actions = [];
  if (comment.parentCommentId == null && !editingThis) {
    const reply = btn("답글", { "aria-label": "이 댓글에 답글 달기" }, "ghost");
    reply.addEventListener("click", () => {
      memo.parent = comment.commentId;
      rerender();
      if (textarea.node && textarea.node.isConnected) textarea.node.focus();
    });
    actions.push(reply);
  }
  // Only the author edits a comment (`canEdit`); an instructor may delete it but not change it.
  if (comment.canEdit && !editingThis) {
    const edit = btn("수정", { "aria-label": "댓글 수정", "data-comment-edit": String(comment.commentId) }, "ghost");
    edit.addEventListener("click", () => {
      const memoKey = `comment-edit:${key}:${comment.commentId}`;
      memos.delete(memoKey);
      Object.assign(memoOf(memoKey), { body: comment.body || "", loaded: true, focus: true });
      commentEditing.set(key, comment.commentId);
      rerender();
    });
    actions.push(edit);
  }
  if (comment.canDelete && !editingThis) {
    actions.push(deleteButton("댓글", `delete-comment:${key}:${comment.commentId}`, async (api) => {
      try {
        await deleteComment(courseId, id, comment.commentId);
        if (memo.parent === comment.commentId) memo.parent = null;
        toast(api, "댓글을 삭제했습니다");
      } catch (error) {
        toast(api, missing(error) ? "이미 삭제되었거나 찾을 수 없는 댓글입니다" : boardMessage(error, "댓글"));
      }
    }));
  }
  // 서버가 준 `edited`(작성 뒤 본문을 고친 적이 있는지)로만 정한다. 지운 댓글에는 없다.
  const edited = comment.edited === true;
  return el("div", { class: "crs-comment" },
    el("div", { class: "crs-post-meta" },
      comment.authorName ? el("b", { text: comment.authorName }) : null,
      comment.mine ? kindTag("내 댓글") : null,
      el("span", { text: koDateTime(comment.createdAt) }),
      edited ? el("span", { class: "crs-edited", text: comment.updatedAt ? `수정됨 ${koDateTime(comment.updatedAt)}` : "수정됨" }) : null,
    ),
    editingThis ? commentEditForm(comment, courseId, id, key, rerender) : prose(comment.body, ""),
    actions.length ? el("div", { class: "crs-post-actions" }, ...actions) : null,
  );
}

/** Inline comment edit by its author: `{ body, version }`, the same byte rule as a new comment. */
function commentEditForm(comment, courseId, id, key, rerender) {
  const memoKey = `comment-edit:${key}:${comment.commentId}`;
  const memo = memoOf(memoKey);
  const say = sayLine();
  const tell = (text) => { memo.notice = text; say.say(text, true); };
  if (memo.notice) say.say(memo.notice, true);
  const bytes = el("span", { class: "tiny", "aria-live": "polite" });
  const showBytes = () => { bytes.textContent = `${legacyBytes(memo.body.trim())} / ${COMMENT_BYTES} byte`; };
  const field = textInput(memo, "body", { label: "댓글 수정", multiline: true, rows: "3", onInput: showBytes });
  showBytes();
  const close = () => {
    commentEditing.delete(key);
    memos.delete(memoKey);
    rerender();
    const back = document.querySelector(`[data-comment-edit="${comment.commentId}"]`);
    if (back) back.focus();
  };
  const cancel = btn("취소", {}, "ghost");
  cancel.addEventListener("click", close);
  const save = cmd("저장", "primary", (node, api, op) => sending(node, "저장하는 중…", op, async () => {
    memo.notice = "";
    const text = memo.body.trim();
    if (!text) return tell("댓글 내용을 입력하세요.");
    if (legacyBytes(text) > COMMENT_BYTES) return tell(`댓글은 ${COMMENT_BYTES} byte까지 쓸 수 있습니다.`);
    try {
      await updateComment(courseId, id, comment.commentId, { body: text, version: comment.version });
      toast(api, "댓글을 고쳤습니다");
      close();
    } catch (error) {
      tell(boardMessage(error, "댓글"));
      // Stale or gone: read the thread again; the typed text stays for the next save.
      if (isCode(error, 409, "version_conflict") || missing(error)) commentLists.load(key, courseId, id);
    }
    return undefined;
  }), { op: memoKey });
  if (memo.focus) {
    memo.focus = false;
    queueMicrotask(() => { const input = field.querySelector("textarea"); if (input && input.isConnected) input.focus(); });
  }
  return el("div", { class: "crs-form" }, field, bytes, say, el("div", { class: "crs-post-actions" }, cancel, save));
}

function commentForm(courseId, id, key, memo, items, rerender, textarea) {
  const say = sayLine();
  const bytes = el("span", { class: "tiny", "aria-live": "polite" });
  const showBytes = () => { bytes.textContent = `${legacyBytes(memo.body.trim())} / ${COMMENT_BYTES} byte`; };
  const field = textInput(memo, "body", { label: memo.parent ? "답글" : "댓글", multiline: true, rows: "3", onInput: showBytes });
  textarea.node = field.querySelector("textarea");
  showBytes();
  const parent = memo.parent != null ? items.find((item) => item.commentId === memo.parent && !item.deleted) : null;
  if (memo.parent != null && !parent) memo.parent = null;
  let replyLine = null;
  if (parent) {
    const cancel = btn("답글 취소", {}, "ghost");
    cancel.addEventListener("click", () => { memo.parent = null; rerender(); });
    replyLine = el("p", { class: "crs-note" }, `${parent.authorName || "댓글"}에게 답글을 답니다. `, cancel);
  }
  const submit = cmd("등록", "primary", (node, api, op) => sending(node, "등록하는 중…", op, async () => {
    const text = memo.body.trim();
    if (!text) return say.say("댓글 내용을 입력하세요.", true);
    if (legacyBytes(text) > COMMENT_BYTES) return say.say(`댓글은 ${COMMENT_BYTES} byte까지 쓸 수 있습니다.`, true);
    try {
      await createComment(courseId, id, { body: text, parentCommentId: memo.parent != null ? memo.parent : null });
      memo.body = "";
      memo.parent = null;
      toast(api, "댓글을 등록했습니다");
    } catch (error) {
      if (createUncertain(`comment:${courseId}:${id}`)) say.say("등록 결과를 확인하지 못했습니다. 같은 내용으로 다시 등록하면 이미 등록된 댓글을 돌려받습니다.", true);
      else say.say(boardMessage(error, "댓글"), true);
    }
    return undefined;
  }), { op: `create:comment:${key}` });
  return el("section", { class: "crs-form", "aria-label": "댓글 쓰기" }, replyLine, field, bytes, say, el("div", { class: "crs-post-actions" }, submit));
}
