// Body of the 테크뇽 학습 도우미 panel for a server material (study room `doc=srv-<fileId>` or the
// preview `n=srv-<fileId>`) while the backend is not `off`: the published file overview, the
// study chat with polling, citations that turn the page viewer to the cited page, older
// questions, my earlier chats on the file (paged by `nextBefore`), and deleting the chat.
// Returns null elsewhere so the mock panel stays as it was.
// Answers are model Markdown rendered with page-summary.js `markdown()` (text nodes only).
import { el } from "../dom.js";
import { btn, field, loadingState, errorState } from "../ui/components.js";
import { backendPanel } from "./backend-prompt.js";
import { backendStatus, courseIdFor, subscribe } from "./backend.js";
import { markdown } from "./page-summary.js";
import {
  fileOverview, listChats, openChat, deleteChat, listExchanges, ask, awaitAnswer, isWaiting,
  exchangeFailure, studyMessage,
} from "./study-chat-remote.js";

// replaceChildren() would turn a null child into the text "null"; skip absent parts.
function fill(node, ...children) {
  node.replaceChildren(...children.filter((child) => child != null && child !== false));
}

const QUESTION_MAX = 2000;

const threads = new Map(); // `${courseId}:${fileId}` -> thread state
const mounts = new Map(); // same key -> repaint of the panel now on screen
let generation = 0;
subscribe(() => {
  generation += 1;
  threads.clear();
});

function serverFileOf(ctx) {
  const params = ctx.params || new URLSearchParams();
  const screen = ctx.screen || {};
  const raw = screen.kind === "preview" ? params.get("n") : params.get("doc");
  const match = /^srv-([1-9][0-9]{0,17})$/.exec(String(raw || ""));
  return match ? { fileId: match[1], kj: params.get("kj") } : null;
}

function thread(key) {
  if (!threads.has(key)) {
    threads.set(key, { chatId: null, chats: [], chatsNext: null, items: [], nextBefore: null, loaded: false, loading: false, error: null, draft: "", notice: "", alert: false, overview: undefined });
  }
  return threads.get(key);
}

function repaint(key) {
  const paint = mounts.get(key);
  if (paint) paint();
}

/** Turns the page viewer on this screen to `page` (the viewer's own jump field). */
function showPage(page) {
  const jump = document.querySelector(".doc-viewer__jump");
  if (!jump) return false;
  jump.value = String(page);
  jump.dispatchEvent(new Event("change"));
  return true;
}

function upsert(state, exchange) {
  const index = state.items.findIndex((item) => item.id === exchange.id);
  // The 202 receipt has no question text; keep the one already shown.
  const merged = index >= 0 ? { ...state.items[index], ...Object.fromEntries(Object.entries(exchange).filter(([, v]) => v != null)) } : exchange;
  if (index >= 0) state.items[index] = merged;
  else state.items.push(merged);
  state.items.sort((a, b) => a.id - b.id);
}

function follow(key, courseId, state, exchange) {
  const gen = generation;
  const chatId = state.chatId;
  // Choosing another chat stops following this one; reopening it reads its exchanges again.
  const alive = () => gen === generation && threads.get(key) === state && state.chatId === chatId;
  awaitAnswer(courseId, chatId, exchange, {
    alive,
    onState: (next) => {
      if (!alive()) return;
      upsert(state, next);
      repaint(key);
    },
  }).catch((error) => {
    if (!alive()) return;
    state.notice = studyMessage(error);
    state.alert = true;
    repaint(key);
  });
}

async function loadExchanges(key, courseId, state, chatId) {
  const page = await listExchanges(courseId, chatId);
  if (threads.get(key) !== state) return;
  state.chatId = chatId;
  state.items = (Array.isArray(page && page.items) ? page.items : []).slice().sort((a, b) => a.id - b.id);
  state.nextBefore = (page && page.nextBefore) || null;
  for (const item of state.items) if (isWaiting(item)) follow(key, courseId, state, item);
}

async function loadThread(key, courseId, fileId, state) {
  if (state.loading) return;
  state.loading = true;
  state.error = null;
  repaint(key);
  try {
    const page = await listChats(courseId, fileId);
    state.chats = page.items;
    state.chatsNext = page.nextBefore;
    // Keep the chat on screen when it is still on the first page; otherwise the newest one.
    const chosen = page.items.find((chat) => chat.id === state.chatId) || page.items[0] || null;
    if (chosen) await loadExchanges(key, courseId, state, chosen.id);
    else Object.assign(state, { chatId: null, items: [], nextBefore: null });
    state.loaded = true;
  } catch (error) {
    state.error = error;
  } finally {
    state.loading = false;
    repaint(key);
  }
}

/** Panel body for a server file, or null when this screen has none or the backend is off. */
export function serverTutorPanel(ctx) {
  const file = serverFileOf(ctx);
  const status = backendStatus();
  if (!file || status === "off") return null;
  if (status !== "ready") return el("div", { class: "chat-panel" }, backendPanel({ compact: true, text: "ASTRA 서버에 로그인하면 이 자료로 테크뇽에게 물어볼 수 있습니다." }));
  const courseId = courseIdFor(file.kj);
  if (!courseId) return el("p", { class: "note", text: "이 과목은 ASTRA 서버에 없어 학습 대화를 쓸 수 없습니다." });
  const key = `${courseId}:${file.fileId}`;
  const state = thread(key);
  const root = el("div", { class: "chat-panel", "aria-live": "polite" });

  const overview = el("details", { class: "chat-overview" }, el("summary", { text: "파일 요약" }));
  const overviewBody = el("div");
  overview.append(overviewBody);
  const paintOverview = () => {
    if (state.overview === undefined) fill(overviewBody, el("p", { class: "tiny", text: "불러오는 중입니다." }));
    else if (state.overview === null) fill(overviewBody, el("p", { class: "tiny", text: "게시된 요약이 없습니다." }));
    else if (state.overview instanceof Error) fill(overviewBody, el("p", { class: "tiny", role: "alert", text: studyMessage(state.overview) }));
    else fill(overviewBody, markdown(state.overview.markdown || ""));
  };
  overview.addEventListener("toggle", () => {
    if (!overview.open || state.overview !== undefined) return;
    paintOverview();
    fileOverview(courseId, file.fileId).then((value) => { state.overview = value; }, (error) => { state.overview = error; })
      .finally(() => { if (overview.isConnected) paintOverview(); });
  });
  paintOverview();

  const list = el("ol", { class: "chat-thread" });
  const line = el("p", { class: "acct-status", role: "status", "aria-live": "polite" });
  const input = el("textarea", { rows: "3", maxlength: String(QUESTION_MAX), placeholder: "이 자료에 대해 물어보기", value: state.draft });
  input.value = state.draft;
  input.addEventListener("input", () => { state.draft = input.value; });
  const send = btn("보내기", {}, "primary");
  const remove = btn("대화 지우기", {}, "ghost");
  const older = btn("이전 질문 더 보기", {}, "ghost");
  const olderChats = btn("이전 대화 더 보기", {}, "ghost");
  const top = el("div", { class: "btn-row" });
  const history = el("details", { class: "chat-history" });
  history.addEventListener("toggle", () => { state.historyOpen = history.open; });

  // My chats on this file, newest first. Choosing one shows its questions; a new question goes to it.
  function chatList() {
    history.open = Boolean(state.historyOpen);
    const rows = state.chats.map((chat, index) => {
      const current = chat.id === state.chatId;
      const label = `${index === 0 ? "최근 대화" : `이전 대화 ${index}`} · 질문 ${chat.exchangeCount ?? 0}개`;
      const pick = btn(label, { "aria-current": current ? "true" : null }, current ? "secondary" : "ghost");
      pick.disabled = current || pick.getAttribute("aria-busy") === "true";
      pick.addEventListener("click", async () => {
        if (pick.disabled) return;
        pick.disabled = true;
        pick.setAttribute("aria-busy", "true");
        state.notice = "";
        state.alert = false;
        try {
          await loadExchanges(key, courseId, state, chat.id);
        } catch (error) {
          state.notice = studyMessage(error);
          state.alert = true;
        } finally {
          repaint(key);
        }
      });
      return el("li", {}, pick);
    });
    olderChats.disabled = olderChats.getAttribute("aria-busy") === "true";
    fill(history,
      el("summary", { text: `내 대화 ${state.chats.length}${state.chatsNext ? "개 이상" : "개"}` }),
      el("ul", { class: "chat-history__list", "aria-label": "이 자료로 나눈 대화" }, ...rows),
      state.chatsNext ? el("div", { class: "btn-row" }, olderChats) : null,
    );
    return history;
  }

  olderChats.addEventListener("click", async () => {
    if (olderChats.disabled || !state.chatsNext) return;
    olderChats.setAttribute("aria-busy", "true");
    olderChats.disabled = true;
    try {
      const page = await listChats(courseId, file.fileId, state.chatsNext);
      const known = new Set(state.chats.map((chat) => chat.id));
      state.chats.push(...page.items.filter((chat) => !known.has(chat.id)));
      state.chatsNext = page.nextBefore;
    } catch (error) {
      state.notice = studyMessage(error);
      state.alert = true;
    } finally {
      olderChats.removeAttribute("aria-busy");
      repaint(key);
    }
  });

  function cite(citation) {
    const label = `p.${citation.page}${citation.title ? ` ${citation.title}` : ""}`;
    const go = btn(label, { class: "btn btn-ghost chat-cite", "aria-label": `${citation.page}쪽 보기` }, "ghost");
    go.addEventListener("click", () => {
      if (!showPage(citation.page)) {
        state.notice = `${citation.page}쪽은 문서 뷰어에서 확인하세요.`;
        state.alert = false;
        paint();
      }
    });
    return el("li", {}, go, citation.quote ? el("q", { class: "tiny", text: citation.quote }) : null);
  }

  function exchangeView(item) {
    const answer = item.state === "answered"
      ? el("div", { class: "chat-answer" }, markdown(item.answer || ""),
        Array.isArray(item.citations) && item.citations.length ? el("ul", { class: "chat-cites", "aria-label": "근거 쪽" }, ...item.citations.map(cite)) : null)
      : isWaiting(item)
        ? el("p", { class: "tiny", role: "status" }, el("span", { class: "spinner", "aria-hidden": "true" }), " 답을 만드는 중입니다.")
        : el("p", { class: "tiny chat-failed", role: "alert", text: exchangeFailure(item) });
    return el("li", { class: "chat-exchange" },
      el("p", { class: "chat-q" }, el("b", { text: "나 " }), item.question || "(질문)"),
      el("div", { class: "chat-a" }, el("b", { text: "테크뇽" }), answer),
    );
  }

  function paint() {
    if (!root.isConnected && root.parentNode) return;
    if (!state.loaded && state.error) {
      const again = btn("다시 불러오기", {}, "ghost");
      again.addEventListener("click", () => loadThread(key, courseId, file.fileId, state));
      fill(root, overview, errorState("학습 대화를 불러오지 못했습니다", studyMessage(state.error), again));
      return;
    }
    if (!state.loaded) {
      fill(root, overview, loadingState("학습 대화를 불러오는 중입니다"));
      return;
    }
    const waiting = state.items.some(isWaiting);
    send.disabled = waiting || send.getAttribute("aria-busy") === "true";
    remove.disabled = !state.chatId || remove.getAttribute("aria-busy") === "true";
    older.disabled = older.getAttribute("aria-busy") === "true";
    fill(top, state.nextBefore ? older : "", state.chatId ? remove : "");
    fill(list, ...state.items.map(exchangeView));
    line.setAttribute("role", state.alert ? "alert" : "status");
    line.textContent = state.notice || (waiting ? "답이 오면 여기에 보입니다." : "");
    fill(root,
      overview,
      el("p", { class: "tiny", text: "질문은 이 자료의 쪽 내용으로만 답합니다. 질문과 답은 ASTRA 서버에 저장되고, 교수님은 볼 수 없습니다." }),
      state.chats.length > 1 || state.chatsNext ? chatList() : null,
      top,
      state.items.length ? list : el("p", { class: "note", text: "아직 질문이 없습니다. 자료에서 궁금한 점을 적어 주세요." }),
      field("질문", input),
      el("div", { class: "btn-row" }, send),
      line,
    );
  }

  send.addEventListener("click", async () => {
    const question = input.value.trim();
    if (!question) {
      state.notice = "질문을 입력하세요.";
      state.alert = true;
      return paint();
    }
    if (send.disabled) return;
    send.setAttribute("aria-busy", "true");
    send.disabled = true;
    state.notice = "";
    state.alert = false;
    try {
      if (!state.chatId) {
        const opened = await openChat(courseId, file.fileId);
        state.chatId = opened.id;
        if (!state.chats.some((chat) => chat.id === opened.id)) state.chats.unshift(opened);
      }
      const receipt = await ask(courseId, state.chatId, question);
      const isNew = !state.items.some((item) => item.id === receipt.id);
      upsert(state, { ...receipt, question });
      const chat = state.chats.find((entry) => entry.id === state.chatId);
      if (chat && isNew) chat.exchangeCount = (chat.exchangeCount || 0) + 1;
      state.draft = "";
      input.value = "";
      follow(key, courseId, state, receipt);
    } catch (error) {
      state.notice = studyMessage(error);
      state.alert = true;
      // Another tab's question is still waiting: show it and wait for its answer.
      if (error && error.code === "study_chat_busy") loadThread(key, courseId, file.fileId, state);
    } finally {
      send.removeAttribute("aria-busy");
      repaint(key);
    }
  });

  older.addEventListener("click", async () => {
    older.setAttribute("aria-busy", "true");
    older.disabled = true;
    try {
      const page = await listExchanges(courseId, state.chatId, state.nextBefore);
      for (const item of Array.isArray(page && page.items) ? page.items : []) upsert(state, item);
      state.nextBefore = (page && page.nextBefore) || null;
    } catch (error) {
      state.notice = studyMessage(error);
      state.alert = true;
    } finally {
      older.removeAttribute("aria-busy");
      repaint(key);
    }
  });

  remove.addEventListener("click", async () => {
    if (remove.dataset.armed !== "1") {
      remove.dataset.armed = "1";
      remove.textContent = "한 번 더 누르면 지우기";
      return;
    }
    remove.setAttribute("aria-busy", "true");
    remove.disabled = true;
    try {
      const gone = state.chatId;
      await deleteChat(courseId, gone);
      state.chats = state.chats.filter((chat) => chat.id !== gone);
      Object.assign(state, { chatId: null, items: [], nextBefore: null, notice: "대화를 지웠습니다.", alert: false });
    } catch (error) {
      state.notice = studyMessage(error);
      state.alert = true;
    } finally {
      remove.removeAttribute("aria-busy");
      remove.dataset.armed = "";
      remove.textContent = "대화 지우기";
      repaint(key);
    }
  });

  mounts.set(key, () => {
    if (root.isConnected) paint();
  });
  paint();
  queueMicrotask(() => {
    if (root.isConnected && !state.loaded && !state.loading) loadThread(key, courseId, file.fileId, state);
  });
  return root;
}
