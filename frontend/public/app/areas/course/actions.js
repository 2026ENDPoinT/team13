import { boardRows } from "../../shared/format.js";
import { writeQuery } from "../../shared/query.js";
import { confirmNotice, noticeMessage } from "../../shared/notices-remote.js";
import { runBoardCommand } from "./boards-server.js";

export function courseRows(screen, state) {
  const draft = state.draft || {};
  const removed = new Set(draft.removedPostIds || []);
  const overrides = draft.postOverrides || {};
  return boardRows(screen, state)
    .filter((row) => !removed.has(row.id))
    .map((row) => (overrides[row.id] ? { ...row, ...overrides[row.id] } : row));
}

function postErrors(data) {
  const errors = {};
  if (!String(data.title || "").trim()) errors.title = "제목을 입력하세요";
  if (!String(data.body || "").trim() || String(data.body).trim().length < 4) errors.body = "내용을 4자 이상 입력하세요";
  return errors;
}

export const actions = {
  /** Backend mode: every Q&A and open board command (like, status, save, answer, comment, delete). */
  "srv-board"(node, api) {
    runBoardCommand(node, api);
  },
  /** Backend mode: "공지를 확인했습니다" in the notice dialog (POST …/confirm, idempotent). */
  "notice-confirm"(node, api) {
    if (node.disabled) return;
    const label = node.textContent;
    node.disabled = true;
    node.textContent = "확인하는 중…";
    confirmNotice(Number(node.dataset.course), Number(node.dataset.id)).then(() => {
      api.getState().toast = "공지를 확인했습니다";
      api.closeDialog();
    }, (error) => {
      if (node.isConnected) {
        node.disabled = false;
        node.textContent = label;
      }
      api.getState().toast = noticeMessage(error);
      api.paintToast();
    });
  },
  week(node, api) {
    const state = api.getState();
    state.week = Number(node.dataset.week) || 1;
    api.save();
    api.render();
  },
  "room-filter"(node, api) {
    const state = api.getState();
    const key = node.dataset.kind === "status" ? "roomStatus" : "roomType";
    const value = node.dataset.value || "";
    state.draft[key] = state.draft[key] === value ? "" : value;
    api.save();
    api.render();
  },
  "toggle-week"(node, api) {
    const state = api.getState();
    const week = Number(node.dataset.week) || 0;
    const closed = new Set(state.draft.closedWeeks || []);
    if (closed.has(week)) closed.delete(week);
    else closed.add(week);
    state.draft.closedWeeks = [...closed];
    api.save();
    api.render();
  },
  "grade-filter"(node, api) {
    const state = api.getState();
    const value = node.dataset.value || "all";
    state.draft.gradeKind = state.draft.gradeKind === value ? "all" : value;
    api.save();
    api.render();
  },
  /** Backend grades: server `type` filter (all, assignment, quiz, manual) kept apart from the sample filter. */
  "srv-grade-filter"(node, api) {
    const state = api.getState();
    state.draft.srvGradeType = node.dataset.value || "all";
    api.save();
    api.render();
  },
  "clear-query"(node, api) {
    const state = api.getState();
    writeQuery(state, node.dataset.scope || "course:course", "");
    api.save();
    api.render();
  },
  "update-post"(node, api) {
    const state = api.getState();
    const data = api.readForm();
    const errors = postErrors(data);
    if (Object.keys(errors).length) return api.showErrors(errors);
    const id = node.dataset.id;
    const next = { title: data.title.trim(), body: data.body.trim() };
    const post = state.posts.find((item) => item.id === id);
    if (post) Object.assign(post, next);
    else state.draft.postOverrides = { ...(state.draft.postOverrides || {}), [id]: next };
    state.toast = "수정했습니다";
    api.save();
    api.closeDialog();
  },
  "delete-post"(node, api) {
    const state = api.getState();
    const id = node.dataset.id;
    state.posts = state.posts.filter((item) => item.id !== id);
    const removed = new Set(state.draft.removedPostIds || []);
    removed.add(id);
    state.draft.removedPostIds = [...removed];
    if (state.draft.postOverrides) delete state.draft.postOverrides[id];
    state.toast = "삭제했습니다";
    api.save();
    if (node.dataset.dialog === "1") api.closeDialog();
    else api.render();
  },
  "file-sample"(node, api) {
    api.getState().toast = "샘플 파일은 이 브라우저 밖으로 내려받지 않습니다";
    api.save();
    api.render();
  },
  "course-reload"(node, api) {
    const state = api.getState();
    const kind = node.dataset.kind || "board";
    state.draft.loading = kind;
    api.save();
    api.render();
    setTimeout(() => {
      const next = api.getState();
      if (!next.draft || next.draft.loading !== kind) return;
      next.draft.loading = "";
      api.save();
      api.render();
    }, 420);
  },
  "open-team-ask"(node, api) {
    api.getState().draft.teamOpen = true;
    api.save();
    api.render();
  },
  "save-team-ask"(node, api) {
    const state = api.getState();
    const data = api.readForm();
    if (!String(data.teamAsk || "").trim() || String(data.teamAsk).trim().length < 4) return api.showErrors({ teamAsk: "요청을 4자 이상 적어 주세요" });
    state.teamAsk = data.teamAsk.trim();
    state.draft.teamOpen = false;
    state.toast = "구성 요청을 저장했습니다";
    api.save();
    api.render();
  },
  "save-reply"(node, api) {
    const state = api.getState();
    const data = api.readForm();
    if (!String(data.reply || "").trim()) return api.showErrors({ reply: "답글을 입력하세요" });
    const post = state.posts.find((p) => p.id === node.dataset.id);
    if (post) post.reply = data.reply.trim();
    else state.draft.replies = { ...(state.draft.replies || {}), [node.dataset.id]: data.reply.trim() };
    state.toast = "답글을 저장했습니다";
    api.save();
    api.closeDialog();
  },
};
