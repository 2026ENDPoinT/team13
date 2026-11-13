// Body of the 테크뇽 학습 도우미 panel for a server material (study room `doc=srv-<fileId>` or the
// preview `n=srv-<fileId>`) while the backend is not `off`: the published file overview, the
// study chat with polling, citations that turn the page viewer to the cited page, older
// questions, my earlier chats on the file (paged by `nextBefore`), and deleting the chat.
// Returns null elsewhere so the mock panel stays as it was.
// Answers are model Markdown rendered with page-summary `markdown()` (React elements only).
import { useEffect, useReducer, useState, type ReactNode } from "react";
import { Button, ErrorState, Field, LoadingState } from "../ui/kit.tsx";
import type { ScreenCtx } from "../types.ts";
import { ApiError } from "./api.ts";
import { backendPanel } from "./backend-prompt.tsx";
import { backendStatus, courseIdFor, subscribe } from "./backend.ts";
import { markdown } from "./page-summary.tsx";
import {
  fileOverview, listChats, openChat, deleteChat, listExchanges, ask, awaitAnswer, isWaiting,
  exchangeFailure, studyMessage,
  type ChatPage, type Exchange, type ExchangePage, type FileOverview, type StudyChat,
} from "./study-chat-remote.ts";

const QUESTION_MAX = 2000;

type CourseId = number | string;

/** One chat thread on a file, kept across redraws and screens until the backend session changes. */
interface ThreadState {
  chatId: StudyChat["id"] | null;
  chats: StudyChat[];
  chatsNext: ChatPage["nextBefore"];
  items: Exchange[];
  nextBefore: ExchangePage["nextBefore"] | null;
  loaded: boolean;
  loading: boolean;
  error: unknown;
  draft: string;
  notice: string;
  alert: boolean;
  /** undefined until the overview was asked for; null when nothing is published. */
  overview: FileOverview | null | undefined;
  overviewError: unknown;
  historyOpen: boolean;
}

const threads = new Map<string, ThreadState>(); // `${courseId}:${fileId}` -> thread state
const mounts = new Map<string, () => void>(); // same key -> repaint of the panel now on screen
let generation = 0;
subscribe(() => {
  generation += 1;
  threads.clear();
});

function serverFileOf(ctx: ScreenCtx): { fileId: string; kj: string | null } | null {
  const raw = ctx.screen.kind === "preview" ? ctx.params.get("n") : ctx.params.get("doc");
  const match = /^srv-([1-9][0-9]{0,17})$/.exec(String(raw || ""));
  return match ? { fileId: match[1], kj: ctx.params.get("kj") } : null;
}

function thread(key: string): ThreadState {
  let state = threads.get(key);
  if (!state) {
    state = {
      chatId: null, chats: [], chatsNext: null, items: [], nextBefore: null, loaded: false, loading: false, error: null,
      draft: "", notice: "", alert: false, overview: undefined, overviewError: undefined, historyOpen: false,
    };
    threads.set(key, state);
  }
  return state;
}

function repaint(key: string): void {
  mounts.get(key)?.();
}

/** Turns the page viewer on this screen to `page` (the viewer's own jump field, read on its native `change`). */
function showPage(page: number): boolean {
  const jump = document.querySelector<HTMLInputElement>(".doc-viewer__jump");
  if (!jump) return false;
  jump.value = String(page);
  jump.dispatchEvent(new Event("change"));
  return true;
}

function upsert(state: ThreadState, exchange: Exchange): void {
  const index = state.items.findIndex((item) => item.id === exchange.id);
  if (index >= 0) {
    // The 202 receipt has no question text; keep the one already shown.
    const merged: Exchange = { ...state.items[index] };
    for (const [field, value] of Object.entries(exchange)) if (value != null) Object.assign(merged, { [field]: value });
    state.items[index] = merged;
  } else state.items.push(exchange);
  state.items.sort((a, b) => a.id - b.id);
}

function follow(key: string, courseId: CourseId, state: ThreadState, exchange: Exchange): void {
  const gen = generation;
  const chatId = state.chatId;
  if (chatId == null) return;
  // Choosing another chat stops following this one; reopening it reads its exchanges again.
  const alive = (): boolean => gen === generation && threads.get(key) === state && state.chatId === chatId;
  awaitAnswer(courseId, chatId, exchange, {
    alive,
    onState: (next: Exchange) => {
      if (!alive()) return;
      upsert(state, next);
      repaint(key);
    },
  }).catch((error: unknown) => {
    if (!alive()) return;
    state.notice = studyMessage(error);
    state.alert = true;
    repaint(key);
  });
}

async function loadExchanges(key: string, courseId: CourseId, state: ThreadState, chatId: StudyChat["id"]): Promise<void> {
  const page = await listExchanges(courseId, chatId);
  if (threads.get(key) !== state) return;
  state.chatId = chatId;
  state.items = (Array.isArray(page?.items) ? page.items : []).slice().sort((a, b) => a.id - b.id);
  state.nextBefore = page?.nextBefore || null;
  for (const item of state.items) if (isWaiting(item)) follow(key, courseId, state, item);
}

async function loadThread(key: string, courseId: CourseId, fileId: string, state: ThreadState): Promise<void> {
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

function OverviewBody({ state }: { state: ThreadState }): ReactNode {
  if (state.overviewError !== undefined) return <p className="tiny" role="alert">{studyMessage(state.overviewError)}</p>;
  if (state.overview === undefined) return <p className="tiny">불러오는 중입니다.</p>;
  if (state.overview === null) return <p className="tiny">게시된 요약이 없습니다.</p>;
  return markdown(state.overview.markdown || "");
}

function ExchangeView({ item, onCite }: { item: Exchange; onCite: (page: number) => void }): ReactNode {
  const citations = Array.isArray(item.citations) ? item.citations : [];
  const answer = item.state === "answered" ? (
    <div className="chat-answer">
      {markdown(item.answer || "")}
      {citations.length ? (
        <ul className="chat-cites" aria-label="근거 쪽">
          {citations.map((citation, index) => (
            <li key={index}>
              <Button kind="ghost" className="chat-cite" aria-label={`${citation.page}쪽 보기`} onClick={() => onCite(citation.page)}>
                {`p.${citation.page}${citation.title ? ` ${citation.title}` : ""}`}
              </Button>
              {citation.quote ? <q className="tiny">{citation.quote}</q> : null}
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  ) : isWaiting(item) ? (
    <p className="tiny" role="status"><span className="spinner" aria-hidden="true" /> 답을 만드는 중입니다.</p>
  ) : <p className="tiny chat-failed" role="alert">{exchangeFailure(item)}</p>;
  return (
    <li className="chat-exchange">
      <p className="chat-q"><b>나 </b>{item.question || "(질문)"}</p>
      <div className="chat-a"><b>테크뇽</b>{answer}</div>
    </li>
  );
}

function TutorChat({ courseId, fileId }: { courseId: CourseId; fileId: string }): ReactNode {
  const key = `${courseId}:${fileId}`;
  const state = thread(key);
  const [, paint] = useReducer((n: number) => n + 1, 0);
  const [question, setQuestion] = useState(state.draft);
  const [sending, setSending] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [armed, setArmed] = useState(false);
  const [olderBusy, setOlderBusy] = useState(false);
  const [olderChatsBusy, setOlderChatsBusy] = useState(false);
  const [pickingChat, setPickingChat] = useState<StudyChat["id"] | null>(null);

  useEffect(() => {
    const repaintThis = (): void => paint();
    mounts.set(key, repaintThis);
    return () => {
      if (mounts.get(key) === repaintThis) mounts.delete(key);
    };
  }, [key]);

  useEffect(() => {
    if (!state.loaded && !state.loading) void loadThread(key, courseId, fileId, state);
  }, [key, courseId, fileId, state]);

  const onOverviewToggle = (open: boolean): void => {
    if (!open || state.overview !== undefined || state.overviewError !== undefined) return;
    fileOverview(courseId, fileId).then((value) => { state.overview = value; }, (error: unknown) => { state.overviewError = error; })
      .finally(() => paint());
  };

  const overview = (
    <details className="chat-overview" onToggle={(event) => onOverviewToggle(event.currentTarget.open)}>
      <summary>파일 요약</summary>
      <div><OverviewBody state={state} /></div>
    </details>
  );

  if (!state.loaded && state.error) {
    return (
      <div className="chat-panel" aria-live="polite">
        {overview}
        <ErrorState
          title="학습 대화를 불러오지 못했습니다"
          body={studyMessage(state.error)}
          actions={<Button kind="ghost" onClick={() => void loadThread(key, courseId, fileId, state)}>다시 불러오기</Button>}
        />
      </div>
    );
  }
  if (!state.loaded) {
    return (
      <div className="chat-panel" aria-live="polite">
        {overview}
        <LoadingState title="학습 대화를 불러오는 중입니다" />
      </div>
    );
  }

  const waiting = state.items.some(isWaiting);

  const pickChat = async (chatId: StudyChat["id"]): Promise<void> => {
    setPickingChat(chatId);
    state.notice = "";
    state.alert = false;
    try {
      await loadExchanges(key, courseId, state, chatId);
    } catch (error) {
      state.notice = studyMessage(error);
      state.alert = true;
    } finally {
      setPickingChat(null);
      repaint(key);
    }
  };

  const loadOlderChats = async (): Promise<void> => {
    if (olderChatsBusy || !state.chatsNext) return;
    setOlderChatsBusy(true);
    try {
      const page = await listChats(courseId, fileId, state.chatsNext);
      const known = new Set(state.chats.map((chat) => chat.id));
      state.chats.push(...page.items.filter((chat) => !known.has(chat.id)));
      state.chatsNext = page.nextBefore;
    } catch (error) {
      state.notice = studyMessage(error);
      state.alert = true;
    } finally {
      setOlderChatsBusy(false);
      repaint(key);
    }
  };

  const cite = (page: number): void => {
    if (showPage(page)) return;
    state.notice = `${page}쪽은 문서 뷰어에서 확인하세요.`;
    state.alert = false;
    paint();
  };

  const send = async (): Promise<void> => {
    const text = question.trim();
    if (!text) {
      state.notice = "질문을 입력하세요.";
      state.alert = true;
      paint();
      return;
    }
    if (waiting || sending) return;
    setSending(true);
    state.notice = "";
    state.alert = false;
    try {
      if (!state.chatId) {
        const opened = await openChat(courseId, fileId);
        state.chatId = opened.id;
        if (!state.chats.some((chat) => chat.id === opened.id)) state.chats.unshift(opened);
      }
      const receipt = await ask(courseId, state.chatId, text);
      const isNew = !state.items.some((item) => item.id === receipt.id);
      upsert(state, { ...receipt, question: text });
      const chat = state.chats.find((entry) => entry.id === state.chatId);
      if (chat && isNew) chat.exchangeCount = (chat.exchangeCount || 0) + 1;
      state.draft = "";
      setQuestion("");
      follow(key, courseId, state, receipt);
    } catch (error) {
      state.notice = studyMessage(error);
      state.alert = true;
      // Another tab's question is still waiting: show it and wait for its answer.
      if (error instanceof ApiError && error.code === "study_chat_busy") void loadThread(key, courseId, fileId, state);
    } finally {
      setSending(false);
      repaint(key);
    }
  };

  const loadOlder = async (): Promise<void> => {
    if (state.chatId == null) return;
    setOlderBusy(true);
    try {
      const page = await listExchanges(courseId, state.chatId, state.nextBefore);
      for (const item of Array.isArray(page?.items) ? page.items : []) upsert(state, item);
      state.nextBefore = page?.nextBefore || null;
    } catch (error) {
      state.notice = studyMessage(error);
      state.alert = true;
    } finally {
      setOlderBusy(false);
      repaint(key);
    }
  };

  const remove = async (): Promise<void> => {
    if (!armed) {
      setArmed(true);
      return;
    }
    const gone = state.chatId;
    if (gone == null) return;
    setRemoving(true);
    try {
      await deleteChat(courseId, gone);
      state.chats = state.chats.filter((chat) => chat.id !== gone);
      Object.assign(state, { chatId: null, items: [], nextBefore: null, notice: "대화를 지웠습니다.", alert: false });
    } catch (error) {
      state.notice = studyMessage(error);
      state.alert = true;
    } finally {
      setRemoving(false);
      setArmed(false);
      repaint(key);
    }
  };

  return (
    <div className="chat-panel" aria-live="polite">
      {overview}
      <p className="tiny">질문은 이 자료의 쪽 내용으로만 답합니다. 질문과 답은 ASTRA 서버에 저장되고, 교수님은 볼 수 없습니다.</p>
      {state.chats.length > 1 || state.chatsNext ? (
        // My chats on this file, newest first. Choosing one shows its questions; a new question goes to it.
        <details className="chat-history" open={state.historyOpen} onToggle={(event) => { state.historyOpen = event.currentTarget.open; }}>
          <summary>{`내 대화 ${state.chats.length}${state.chatsNext ? "개 이상" : "개"}`}</summary>
          <ul className="chat-history__list" aria-label="이 자료로 나눈 대화">
            {state.chats.map((chat, index) => {
              const current = chat.id === state.chatId;
              const busy = pickingChat === chat.id;
              return (
                <li key={chat.id}>
                  <Button
                    kind={current ? "secondary" : "ghost"}
                    aria-current={current ? "true" : undefined}
                    aria-busy={busy ? "true" : undefined}
                    disabled={current || busy}
                    onClick={() => void pickChat(chat.id)}
                  >
                    {`${index === 0 ? "최근 대화" : `이전 대화 ${index}`} · 질문 ${chat.exchangeCount ?? 0}개`}
                  </Button>
                </li>
              );
            })}
          </ul>
          {state.chatsNext ? (
            <div className="btn-row">
              <Button kind="ghost" aria-busy={olderChatsBusy ? "true" : undefined} disabled={olderChatsBusy} onClick={() => void loadOlderChats()}>
                이전 대화 더 보기
              </Button>
            </div>
          ) : null}
        </details>
      ) : null}
      <div className="btn-row">
        {state.nextBefore ? (
          <Button kind="ghost" aria-busy={olderBusy ? "true" : undefined} disabled={olderBusy} onClick={() => void loadOlder()}>
            이전 질문 더 보기
          </Button>
        ) : null}
        {state.chatId ? (
          <Button kind="ghost" aria-busy={removing ? "true" : undefined} disabled={removing} onClick={() => void remove()}>
            {armed ? "한 번 더 누르면 지우기" : "대화 지우기"}
          </Button>
        ) : null}
      </div>
      {state.items.length ? (
        <ol className="chat-thread">
          {state.items.map((item) => <ExchangeView key={item.id} item={item} onCite={cite} />)}
        </ol>
      ) : <p className="note">아직 질문이 없습니다. 자료에서 궁금한 점을 적어 주세요.</p>}
      <Field label="질문">
        <textarea
          rows={3}
          maxLength={QUESTION_MAX}
          placeholder="이 자료에 대해 물어보기"
          value={question}
          onChange={(event) => {
            state.draft = event.target.value;
            setQuestion(event.target.value);
          }}
        />
      </Field>
      <div className="btn-row">
        <Button kind="primary" aria-busy={sending ? "true" : undefined} disabled={waiting || sending} onClick={() => void send()}>보내기</Button>
      </div>
      <p className="acct-status" role={state.alert ? "alert" : "status"} aria-live="polite">
        {state.notice || (waiting ? "답이 오면 여기에 보입니다." : "")}
      </p>
    </div>
  );
}

/** Panel body for a server file, or null when this screen has none or the backend is off. */
export function serverTutorPanel(ctx: ScreenCtx): ReactNode {
  const file = serverFileOf(ctx);
  const status = backendStatus();
  if (!file || status === "off") return null;
  if (status !== "ready") {
    return <div className="chat-panel">{backendPanel({ compact: true, text: "ASTRA 서버에 로그인하면 이 자료로 테크뇽에게 물어볼 수 있습니다." })}</div>;
  }
  const courseId = courseIdFor(file.kj);
  if (!courseId) return <p className="note">이 과목은 ASTRA 서버에 없어 학습 대화를 쓸 수 없습니다.</p>;
  return <TutorChat key={`${courseId}:${file.fileId}`} courseId={courseId} fileId={file.fileId} />;
}
