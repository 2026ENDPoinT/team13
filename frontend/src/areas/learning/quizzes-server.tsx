// 학생의 서버 퀴즈 상세와 응시 화면 (`?m=test&n=srv-<quizId>`, 응시는 `&attempt=<attemptId>`).
// 시작 가능 여부, 응시 구간, 응시 마감 일시는 서버가 정한다. 남은 시간은 응답의 `deadlineAt - serverTime`에서
// 도착 뒤 흐른 시간만 빼서 센다. 답은 문항마다 읽은 `revision`과 함께 바로 저장하고, 제출은 `Idempotency-Key`로
// 한 번만 접수된다. 점수·정답·해설·문항 점수는 응답에 그 속성이 있을 때만 그린다.
import { Fragment, useEffect, useReducer, useRef, useState, type ReactNode } from "react";
import { Badge, Button, EmptyState, ErrorState, LoadingState } from "../../ui/kit.tsx";
import { Link, navigate } from "../../router.tsx";
import { getState, render, toast } from "../../store.ts";
import { ApiError } from "../../shared/api.ts";
import { backendStatus, backendSettled, connect as connectBackend, courseIdFor, subscribe } from "../../shared/backend.ts";
import { backendPanel } from "../../shared/backend-prompt.tsx";
import { useResource } from "../../shared/remote-cache.ts";
import { koDateTime, ddayAt } from "../../shared/home-remote.ts";
import {
  quizDetails, attemptDetails, quizKey, attemptKey, startAttempt, saveAnswer, submitAttempt, submitUncertain,
  remainingMs, clockText, quizMessage, isConflict, scoreText, studentQuizHref, PHASE, ATTEMPT_STATUS, FINISH, QUESTION_TYPE,
} from "../../shared/quizzes-remote.ts";
import type { AttemptDetail, AttemptSummary, QuizAnswer, QuizDetail, QuizInfo, QuizQuestion, SubmitReceipt } from "../../shared/quizzes-remote.ts";
import type { Screen, ScreenCtx } from "../../types.ts";

const LOGIN_TEXT = "ASTRA 서버에 로그인하면 이 과목의 시험을 보고 응시할 수 있습니다.";
const TEXT_DELAY = 1200;
const MAX_TEXT = 2000;
// An empty short answer is sent as `text: null` (the server stores "no answer"); `saveAnswer`'s type omits null.
const NO_TEXT = null as unknown as string;

const missing = (error: unknown): boolean => error instanceof ApiError && error.status === 404;

/** learning.tsx 도우미. */
interface DetailParts {
  typeLine(type: string): ReactNode;
  metaTable(rows: [string, ReactNode][]): ReactNode;
}

/** 응시 응답에서 이 화면이 더 읽는 속성(`quizzes-remote.ts`의 타입에는 아직 없다). */
interface LiveAttempt extends AttemptDetail {
  acceptingAnswers?: boolean;
}
interface SavedAnswer extends QuizAnswer {
  savedAt?: string | null;
}

/** 선택 문항은 고른 optionId 목록, 단답형은 글이다. */
type AnswerValue = number[] | string;

interface AnswerState {
  value: AnswerValue | null;
  revision: number;
  dirty: boolean;
  inflight: Promise<void> | null;
  savedAt: string | null;
  error: string;
  adopt?: boolean;
}

interface AttemptMemo {
  answers: Map<number, AnswerState>;
  moved: boolean;
  ended: boolean;
  expired: boolean;
  finishing: boolean;
  submitting: boolean;
  receipt: SubmitReceipt | null;
  receiptEntry: unknown;
  /** 응시 화면이 떠 있는 동안 그 화면을 다시 그린다. */
  paint: (() => void) | null;
}

// attemptKey -> 응시 중 상태. 다시 그리기와 재조회 뒤에도 고른 답과 저장 상태가 남는다. 세션이 바뀌면 버린다.
const sessions = new Map<string, AttemptMemo>();
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  sessions.clear();
});

function memoOf(key: string): AttemptMemo {
  let memo = sessions.get(key);
  if (!memo) {
    memo = { answers: new Map(), moved: false, ended: false, expired: false, finishing: false, submitting: false, receipt: null, receiptEntry: null, paint: null };
    sessions.set(key, memo);
  }
  return memo;
}

function RetryButton({ reload }: { reload: () => void }) {
  const [busy, setBusy] = useState(false);
  return <Button kind="ghost" disabled={busy} onClick={() => { setBusy(true); reload(); }}>다시 불러오기</Button>;
}

/** 일반 텍스트 문단. 모두 텍스트다. */
function Prose({ text, empty }: { text: string | null | undefined; empty?: string }) {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  if (!blocks.length) return empty ? <div className="prose"><p className="tiny">{empty}</p></div> : null;
  return (
    <div className="prose">
      {blocks.map((block, index) => (
        <p key={index}>{block.split("\n").map((line, at) => <Fragment key={at}>{at ? <br /> : null}{line}</Fragment>)}</p>
      ))}
    </div>
  );
}

type Gate = { view: ReactNode; off: boolean } | null;

/** 서버 데이터 대신 보일 것, 준비되었으면 null. 기다린 첫 연결 확인이 꺼진 채로 끝나면 `off`를 켠다. */
function useGate(kj: string | undefined): Gate {
  const status = backendStatus();
  const probing = status === "off" && !backendSettled();
  const [off, setOff] = useState(false);
  useEffect(() => {
    if (!probing) return;
    let live = true;
    void connectBackend().then((next) => {
      if (live && next.status === "off") setOff(true);
    });
    return () => {
      live = false;
    };
  }, [probing]);
  if (status === "anon") return { view: backendPanel({ text: LOGIN_TEXT }), off: false };
  if (status === "off") {
    if (off) return { view: null, off: true };
    if (probing) return { view: <LoadingState title="ASTRA 서버에 연결하는 중입니다" />, off: false };
    return { view: <EmptyState title="활동을 찾을 수 없습니다" body="ASTRA 서버에 연결되어 있지 않습니다. 목록에서 다른 활동을 선택해 보세요." />, off: false };
  }
  if (!courseIdFor(kj)) return { view: <EmptyState title="이 과목은 ASTRA 서버에 없습니다" body="서버에 등록된 과목의 시험만 볼 수 있습니다." />, off: false };
  return null;
}

function periodText(info: QuizInfo): string {
  const close = koDateTime(info.closeAt);
  return info.openAt ? `${koDateTime(info.openAt)} ~ ${close}` : `~ ${close}`;
}

function PhaseBadge({ phase }: { phase: string | undefined }) {
  return <Badge tone={phase === "open" ? "success" : phase === "closed" ? "danger" : "neutral"}>{PHASE[phase || ""] || phase}</Badge>;
}

const releaseText = (info: QuizInfo): string => (info.resultReleaseAt ? `${koDateTime(info.resultReleaseAt)} ~` : "비공개");

/** 서버 퀴즈의 상세 또는 응시 화면 (`screen.serverQuiz`, `screen.serverAttempt`). `parts`는 learning.tsx 도우미다. */
export function remoteQuizDetail(screen: Screen, ctx: ScreenCtx, parts: DetailParts): ReactNode {
  return <QuizScreen screen={screen} parts={parts} />;
}

function QuizScreen({ screen, parts }: { screen: Screen; parts: DetailParts }) {
  const kj = screen.course || "";
  const quizId = Number(screen.serverQuiz);
  const gate = useGate(kj);
  const courseId = gate ? null : courseIdFor(kj);
  let main: ReactNode;
  if (gate) {
    main = gate.off
      ? <EmptyState title="활동을 찾을 수 없습니다" body="ASTRA 서버에 연결되어 있지 않습니다." />
      : <div className="act-body">{parts.typeLine("test")}{gate.view}</div>;
  } else if (courseId != null && screen.serverAttempt) {
    const attemptId = screen.serverAttempt;
    main = <AttemptView key={attemptKey(courseId, quizId, attemptId)} courseId={courseId} kj={kj} quizId={quizId} attemptId={attemptId} parts={parts} />;
  } else if (courseId != null) {
    main = <QuizDetailView courseId={courseId} kj={kj} quizId={quizId} parts={parts} />;
  }
  return (
    <section className="bleed act-layout is-open" data-item-open={`srv-${quizId}`}>
      <div className="act-main">{main}</div>
    </section>
  );
}

interface QuizRef {
  courseId: number | string;
  kj: string;
  quizId: number;
  parts: DetailParts;
}

function QuizDetailView({ courseId, kj, quizId, parts }: QuizRef) {
  const key = quizKey(courseId, quizId);
  const load = () => void quizDetails.load(key, courseId, quizId);
  const entry = useResource(quizDetails, key, load);
  useEffect(() => {
    void quizDetails.visit("quiz-detail", key, courseId, quizId);
  });
  const listHref = `/ilos/cls/st/activity/activity_list_form.acl?kj=${encodeURIComponent(kj)}`;
  const data = entry && entry.data;
  if (!data) {
    if (!entry || !entry.error) return <LoadingState title="시험을 불러오는 중입니다" />;
    if (missing(entry.error)) return <EmptyState title="시험을 찾을 수 없습니다" body="아직 공개되지 않았거나 볼 수 있는 권한이 없습니다." actions={<Link href={listHref} className="btn btn-secondary">학습활동 목록</Link>} />;
    return <ErrorState title="시험을 불러오지 못했습니다" body={quizMessage(entry.error)} actions={<RetryButton reload={load} />} />;
  }
  const info: Partial<QuizInfo> = data.quiz || {};
  const online = info.examType !== "offline";
  const dday = data.phase === "open" ? ddayAt(info.closeAt, data.serverTime) : "";
  const rows: ([string, ReactNode] | null)[] = [
    ["시험형태", online ? "온라인 시험" : "오프라인 시험"],
    ["응시기간", <span className="due-line"><span>{periodText(data.quiz)}</span>{dday ? <span className="dday">{dday}</span> : null}</span>],
    ["응시 구간", <PhaseBadge phase={data.phase} />],
    ["배점", `${scoreText(info.maxScore)}점`],
    online ? ["문항", `${info.questionCount}문항`] : null,
    online ? ["제한시간", info.timeLimitMinutes ? `${info.timeLimitMinutes}분 (응시 마감을 넘지 않습니다)` : "응시 마감까지"] : null,
    online ? ["응시 횟수", info.attemptLimit ? `${data.attemptsUsed ?? 0} / ${info.attemptLimit}회` : `${data.attemptsUsed ?? 0}회 (제한 없음)`] : null,
    ["결과공개", releaseText(data.quiz)],
  ];
  const head = (
    <>
      {parts.typeLine("test")}
      <h1>{info.title}</h1>
      {entry.error ? <p className="err" role="alert">{quizMessage(entry.error)}</p> : null}
      {parts.metaTable(rows.filter((row): row is [string, ReactNode] => row !== null))}
      <Prose text={info.description} empty="시험 안내가 없습니다." />
    </>
  );
  if (data.revision != null) {
    return (
      <div className="act-body">
        {head}
        <p className="note">담당 교수 화면입니다. 응시 현황과 채점은 교수 화면에서 봅니다.</p>
      </div>
    );
  }
  const attempts = Array.isArray(data.attempts) ? data.attempts : [];
  return (
    <div className="act-body">
      {head}
      {/* 결과 공개 뒤에만 서버가 `score`를 보낸다. */}
      {data.score != null ? (
        <section className="note ok" aria-label="평가">
          <h2>평가</h2>
          <p>{`${scoreText(data.score)} / ${scoreText(info.maxScore)}점`}</p>
        </section>
      ) : null}
      {attempts.length ? (
        <>
          <h2>내 응시</h2>
          <div className="stack srv-quiz-attempts">{attempts.map((item) => <AttemptRow key={item.attemptId} item={item} kj={kj} quizId={quizId} />)}</div>
        </>
      ) : null}
      <StartArea data={data} courseId={courseId} kj={kj} quizId={quizId} attempts={attempts} />
    </div>
  );
}

function AttemptRow({ item, kj, quizId }: { item: AttemptSummary; kj: string; quizId: number }) {
  const live = item.status === "in_progress";
  const meta = [
    `${koDateTime(item.startedAt)} 시작`,
    item.submittedAt ? `${koDateTime(item.submittedAt)} ${FINISH[item.finishReason || ""] || "제출"}` : `마감 ${koDateTime(item.deadlineAt)}`,
  ].join(" · ");
  return (
    <div className="file-row">
      <span>
        <b>{`${item.attemptNo}회차 · ${ATTEMPT_STATUS[item.status || ""] || item.status}`}</b>
        <span className="tiny">{meta}</span>
        {item.totalScore != null ? <span className="tiny">{`총점 ${scoreText(item.totalScore)}점`}</span> : null}
      </span>
      {live ? null : <Link href={studentQuizHref(kj, quizId, { attempt: item.attemptId })} className="btn btn-secondary" aria-label={`${item.attemptNo}회차 답안 보기`}>답안 보기</Link>}
    </div>
  );
}

// 시작 요청이 나가 있는 퀴즈(quizKey). 응답을 기다리는 동안 다시 그린 화면의 새 버튼도 두 번째 시작을 보내지 않는다.
const starting = new Set<string>();

/**
 * 응시 시작 또는 진행 중 응시 이어 가기(상세의 시작 단추, 옮겨진 응시의 이어서 응시 단추). 실패 문구는 단추 아래
 * 상태 줄에, 그 화면이 이미 사라졌으면 토스트로 알린다.
 */
function StartButton({ courseId, quizId, kj, label, disabled = false }: { courseId: number | string; quizId: number; kj: string; label: string; disabled?: boolean }) {
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("");
  const live = useRef(true);
  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
    };
  }, []);
  const start = async () => {
    const scope = quizKey(courseId, quizId);
    if (busy || starting.has(scope)) return;
    starting.add(scope);
    setBusy(true);
    try {
      const { state, resumed } = await startAttempt(courseId, quizId);
      const memo = memoOf(attemptKey(courseId, quizId, state.attemptId));
      memo.moved = false;
      // 옮겨지기 전에 저장하지 못한 답은 이 화면이 이어받은 뒤 다시 보낸다.
      for (const entry of memo.answers.values()) entry.error = "";
      getState().toast = resumed ? "진행 중인 응시를 이어 갑니다" : `${state.attemptNo}회차 응시를 시작했습니다`;
      const href = studentQuizHref(kj, quizId, { attempt: state.attemptId });
      if (memo.paint && `${location.pathname}${location.search}` === href) {
        render();
        void attemptDetails.load(attemptKey(courseId, quizId, state.attemptId), courseId, quizId, state.attemptId);
      } else navigate(href);
    } catch (error) {
      if (live.current) setStatus(quizMessage(error));
      else toast(quizMessage(error));
      if (error instanceof ApiError && (error.status === 409 || error.status === 404)) void quizDetails.load(quizKey(courseId, quizId), courseId, quizId);
    } finally {
      starting.delete(scope);
      if (live.current) setBusy(false);
    }
  };
  return (
    <>
      <div className="btn-row">
        <Button kind="primary" disabled={disabled || busy} aria-busy={busy ? "true" : undefined} onClick={() => void start()}>{busy ? "시작하는 중…" : label}</Button>
      </div>
      <p className="tiny" role={status ? "alert" : "status"} aria-live="polite">{status}</p>
    </>
  );
}

interface StartAreaProps {
  data: QuizDetail;
  courseId: number | string;
  kj: string;
  quizId: number;
  attempts: AttemptSummary[];
}

function StartArea({ data, courseId, kj, quizId, attempts }: StartAreaProps) {
  const info: Partial<QuizInfo> = data.quiz || {};
  const [agreed, setAgreed] = useState(false);
  if (info.examType === "offline") {
    return <div className="srv-quiz-start"><p className="note">오프라인(대면) 시험입니다. 온라인으로 응시할 수 없습니다.</p></div>;
  }
  if (!data.canStart) {
    const why = data.phase === "not_open" ? "아직 응시 기간이 아닙니다."
      : data.phase === "closed" ? "응시 기간이 끝났습니다."
        : info.attemptLimit && (data.attemptsUsed ?? 0) >= info.attemptLimit ? "응시 횟수를 모두 썼습니다." : "지금은 응시할 수 없습니다.";
    return <div className="srv-quiz-start"><p className="note">{why}</p></div>;
  }
  const live = attempts.find((item) => item.status === "in_progress");
  if (live) {
    return (
      <div className="srv-quiz-start">
        <p className="tiny">{`${live.attemptNo}회차 응시가 진행 중입니다. 마감 ${koDateTime(live.deadlineAt)}. 다른 기기에서 열었던 응시도 이 화면으로 옮겨 이어서 풉니다.`}</p>
        <StartButton courseId={courseId} quizId={quizId} kj={kj} label="이어서 응시" />
      </div>
    );
  }
  const agreeId = `srv-quiz-agree-${quizId}`;
  return (
    <div className="srv-quiz-start">
      <ul className="tiny srv-quiz-rules">
        <li>{info.timeLimitMinutes ? `시작하면 ${info.timeLimitMinutes}분 제한 시간이 바로 흐르고, 응시 마감 일시를 넘지 않습니다.` : "시작하면 응시 마감 일시까지 풀 수 있습니다."}</li>
        <li>답은 고르거나 입력하는 대로 서버에 저장됩니다. 마감 시각이 되면 마지막으로 저장된 답으로 제출됩니다.</li>
        <li>다른 기기나 브라우저에서 이어서 풀면 이 화면에서는 더 저장할 수 없습니다.</li>
      </ul>
      <label className="choice" htmlFor={agreeId}>
        <input type="checkbox" id={agreeId} checked={agreed} onChange={(event) => setAgreed(event.target.checked)} />
        <span>응시 안내를 확인했습니다</span>
      </label>
      <StartButton courseId={courseId} quizId={quizId} kj={kj} label="응시 시작" disabled={!agreed} />
    </div>
  );
}

// ---------- 응시 / 결과 ----------

interface AttemptRef extends QuizRef {
  attemptId: number;
}

function AttemptView({ courseId, kj, quizId, attemptId, parts }: AttemptRef) {
  const key = attemptKey(courseId, quizId, attemptId);
  const headKey = quizKey(courseId, quizId);
  const memo = memoOf(key);
  const load = () => void attemptDetails.load(key, courseId, quizId, attemptId);
  const entry = useResource(attemptDetails, key, load);
  const head = useResource(quizDetails, headKey);
  const [, repaint] = useReducer((n: number) => n + 1, 0);
  useEffect(() => {
    memo.paint = repaint;
    return () => {
      if (memo.paint === repaint) memo.paint = null;
    };
  }, [memo]);
  useEffect(() => {
    void attemptDetails.visit("quiz-attempt", key, courseId, quizId, attemptId);
    if (!quizDetails.peek(headKey)) void quizDetails.load(headKey, courseId, quizId);
  });
  const detailHref = studentQuizHref(kj, quizId);
  // 접수 결과는 그것을 만든 history 항목에서만 보인다.
  if (memo.receipt && memo.receiptEntry !== history.state) memo.receipt = null;
  const data = entry && entry.data;
  const info = head && head.data ? head.data.quiz : null;
  let body: ReactNode;
  if (!data) {
    if (!entry || !entry.error) body = <LoadingState title="응시를 불러오는 중입니다" />;
    else if (missing(entry.error)) body = <EmptyState title="응시를 찾을 수 없습니다" body="본인의 응시가 아니거나 이 시험의 응시가 아닙니다." actions={<Link href={detailHref} className="btn btn-secondary">시험 정보</Link>} />;
    else body = <ErrorState title="응시를 불러오지 못했습니다" body={quizMessage(entry.error)} actions={<RetryButton reload={load} />} />;
    return <div className="act-body">{parts.typeLine("test")}{body}</div>;
  }
  if (data.status === "in_progress" && !memo.ended) {
    return (
      <div className="act-body">
        <TakingForm courseId={courseId} kj={kj} quizId={quizId} attemptId={attemptId} parts={parts} attemptKey={key} memo={memo} data={data} info={info} staleError={entry.error} />
      </div>
    );
  }
  return (
    <div className="act-body">
      <ResultView data={data} info={info} memo={memo} detailHref={detailHref} parts={parts} />
    </div>
  );
}

function valueOf(question: QuizQuestion): AnswerValue {
  const answer = question.answer || {};
  if (question.type === "short") return answer.text == null ? "" : String(answer.text);
  return Array.isArray(answer.optionIds) ? [...answer.optionIds].sort((a, b) => a - b) : [];
}

const answered = (question: QuizQuestion, value: AnswerValue | null): boolean => (question.type === "short" ? String(value || "").trim() !== "" : Array.isArray(value) && value.length > 0);

interface TakingProps extends AttemptRef {
  attemptKey: string;
  memo: AttemptMemo;
  data: LiveAttempt;
  info: QuizInfo | null;
  staleError: unknown;
}

/** 응시 폼이 비동기 작업(답 저장, 제출) 사이에 읽고 바꾸는 상태. */
interface TakingRun {
  questions: QuizQuestion[];
  timers: Map<number, number>;
  merged: LiveAttempt | null;
  warned: boolean;
  alertLine: string;
  confirming: boolean;
  message: { text: string; alert: boolean };
  live: boolean;
}

function TakingForm({ courseId, kj, quizId, attemptId, parts, attemptKey: key, memo, data, info, staleError }: TakingProps) {
  const [, bump] = useReducer((n: number) => n + 1, 0);
  const runRef = useRef<TakingRun | null>(null);
  if (!runRef.current) {
    runRef.current = { questions: [], timers: new Map(), merged: null, warned: false, alertLine: "", confirming: false, message: { text: "", alert: false }, live: true };
  }
  const run = runRef.current;
  const latest = useRef(data);
  latest.current = data;

  const stateOf = (questionId: number): AnswerState => {
    let s = memo.answers.get(questionId);
    if (!s) {
      s = { value: null, revision: 0, dirty: false, inflight: null, savedAt: null, error: "" };
      memo.answers.set(questionId, s);
    }
    return s;
  };
  const locked = () => memo.moved || memo.ended || memo.expired || memo.submitting;
  const repaint = () => {
    if (run.live) bump();
    else if (memo.paint) memo.paint();
  };
  const say = (text: string, alert = false) => {
    run.message = { text: text || "", alert };
    repaint();
  };

  // 고친 뒤 아직 저장하지 않은 답은 그대로 두고, 나머지는 서버가 저장한 답과 개정 번호를 쓴다.
  if (run.merged !== data) {
    run.merged = data;
    run.questions = Array.isArray(data.questions) ? data.questions : [];
    for (const question of run.questions) {
      const s = stateOf(question.questionId);
      const server: SavedAnswer = question.answer || {};
      if (s.value == null || s.adopt || (!s.dirty && !s.inflight)) {
        s.value = valueOf(question);
        s.revision = Number(server.revision) || 0;
        s.savedAt = server.savedAt || s.savedAt;
        if (s.adopt) s.dirty = false;
        s.adopt = false;
      }
    }
  }
  const questions = run.questions;

  function save(question: QuizQuestion): Promise<void> | null {
    const s = stateOf(question.questionId);
    if (s.inflight || !s.dirty || locked()) return s.inflight;
    s.dirty = false;
    const value = s.value;
    const payload = question.type === "short"
      ? { revision: s.revision, text: typeof value === "string" && value.trim() ? value : NO_TEXT }
      : { revision: s.revision, optionIds: Array.isArray(value) ? value : [] };
    s.inflight = saveAnswer(courseId, quizId, attemptId, question.questionId, payload).then((saved) => {
      s.revision = saved.revision;
      s.savedAt = saved.savedAt;
      s.error = "";
    }, (error: unknown) => {
      s.error = quizMessage(error);
      if (isConflict(error, "answer_conflict")) {
        // 다른 탭이 먼저 저장했다. 서버의 답과 개정 번호를 받아 쓴다.
        s.adopt = true;
        void attemptDetails.load(key, courseId, quizId, attemptId);
      } else if (isConflict(error, "attempt_moved")) {
        memo.moved = true;
        s.dirty = true;
      } else if (isConflict(error, "deadline_passed") || isConflict(error, "attempt_closed")) {
        s.dirty = true;
        void finish(false, true);
      } else s.dirty = true;
    }).finally(() => {
      s.inflight = null;
      repaint();
      if (s.dirty && !s.error && !locked()) void save(question);
    });
    repaint();
    return s.inflight;
  }

  function change(question: QuizQuestion, value: AnswerValue) {
    if (locked()) return;
    const s = stateOf(question.questionId);
    s.value = value;
    s.dirty = true;
    s.error = "";
    repaint();
    if (question.type === "short") {
      clearTimeout(run.timers.get(question.questionId));
      run.timers.set(question.questionId, window.setTimeout(() => { if (run.live) void save(question); }, TEXT_DELAY));
    } else void save(question);
  }

  // 모든 답의 저장이 끝날 때까지 기다린다(대기 중인 단답형도 바로 보낸다).
  async function flushAll() {
    for (let round = 0; round < 3; round += 1) {
      for (const question of run.questions) {
        clearTimeout(run.timers.get(question.questionId));
        void save(question);
      }
      const pending = run.questions.map((question) => stateOf(question.questionId).inflight).filter((p): p is Promise<void> => p !== null);
      if (!pending.length) return;
      await Promise.allSettled(pending);
    }
  }

  /**
   * 제출. `auto`는 마감 시각이 되었을 때이고, 서버가 마지막으로 저장한 답으로 닫는다. 마감 뒤에는 답을 더
   * 저장하지 않고(입력이 잠긴다) 제출만 다시 보낼 수 있다. 제출 절차는 한 번에 하나다: `memo.finishing`을
   * 기다리기 전에 켜므로, 저장을 마무리하는 동안 온 두 번째 클릭이나 마감은 새 요청을 보내지 않는다. 그동안
   * 마감이 오면 `memo.expired`만 켜고, 진행 중인 절차가 저장 확인 없이 바로 제출한다. `byUser`는 단추로 낸
   * 제출이다(결과를 토스트로 알린다).
   */
  async function finish(byUser: boolean, auto: boolean) {
    if (memo.moved || memo.ended) return;
    if (auto || memo.expired) {
      memo.expired = true;
      say("응시 시간이 끝났습니다. 마지막으로 저장된 답으로 제출하고 있습니다.");
    }
    if (memo.finishing) {
      repaint();
      return;
    }
    memo.finishing = true;
    repaint();
    try {
      if (!memo.expired) {
        await flushAll();
        if (memo.moved || memo.ended) return;
        const failed = run.questions.filter((question) => stateOf(question.questionId).error || stateOf(question.questionId).dirty);
        if (!memo.expired && failed.length) {
          say(`저장하지 못한 답이 ${failed.length}개 있습니다(${failed.map((q) => `${q.number}번`).join(", ")}). 네트워크를 확인하고 그 문항을 다시 고른 뒤 제출하세요.`, true);
          return;
        }
      }
      await send(byUser);
    } finally {
      memo.finishing = false;
      // 그사이 다시 그린 응시 폼(같은 memo)도 제출 버튼을 되돌려 받는다.
      repaint();
    }
  }

  async function send(byUser: boolean) {
    memo.submitting = true;
    repaint();
    try {
      const receipt = await submitAttempt(courseId, quizId, attemptId);
      memo.receipt = receipt;
      memo.receiptEntry = history.state;
      memo.ended = true;
      const text = receipt.finishReason === "deadline" ? "마감 시각에 저장된 답으로 제출되었습니다" : "시험을 제출했습니다";
      if (byUser) toast(text);
      else say(text);
      void attemptDetails.load(key, courseId, quizId, attemptId);
    } catch (error) {
      if (isConflict(error, "attempt_moved")) memo.moved = true;
      else if (submitUncertain(courseId, quizId, attemptId)) {
        say(`${memo.expired ? "응시 시간이 끝났지만 " : ""}제출 결과를 확인하지 못했습니다. 네트워크를 확인한 뒤 다시 제출하세요. 이미 접수되었다면 같은 접수 결과를 돌려받습니다.`, true);
      } else {
        say(quizMessage(error), true);
        void attemptDetails.load(key, courseId, quizId, attemptId);
      }
    } finally {
      memo.submitting = false;
      if (run.live) run.confirming = false;
      repaint();
      if (memo.ended && memo.paint) memo.paint();
    }
  }

  function tick() {
    const left = remainingMs(latest.current);
    if (Number.isNaN(left)) return;
    if (left <= 60000 && !run.warned) {
      run.warned = true;
      run.alertLine = "1분 남았습니다. 마감 시각이 되면 저장된 답으로 제출됩니다.";
    }
    if (left <= 0 && !memo.expired && !memo.ended) void finish(false, true);
  }
  const tickRef = useRef(tick);
  tickRef.current = tick;

  useEffect(() => {
    run.live = true;
    const timer = window.setInterval(() => {
      tickRef.current();
      bump();
    }, 1000);
    return () => {
      run.live = false;
      clearInterval(timer);
      run.timers.forEach((id) => clearTimeout(id));
    };
  }, [run]);

  // Each new response: send answers still waiting, close an attempt the server no longer takes, show a stale load.
  useEffect(() => {
    if (data.acceptingAnswers && !locked()) {
      for (const question of run.questions) {
        const s = stateOf(question.questionId);
        if (s.dirty && !s.inflight && !s.error) void save(question);
      }
    }
    if (!data.acceptingAnswers && !memo.expired && !memo.ended) void finish(false, true);
    if (staleError) say(quizMessage(staleError), true);
    tick();
    bump();
  }, [data, staleError]);

  const submit = () => {
    if (memo.moved || memo.ended || memo.finishing) return;
    if (memo.expired) return void finish(true, false);
    if (!run.confirming) {
      const empty = run.questions.filter((question) => !answered(question, stateOf(question.questionId).value));
      run.confirming = true;
      say(`${empty.length ? `답하지 않은 문항이 ${empty.length}개 있습니다. ` : ""}제출하면 답을 바꿀 수 없습니다. 제출 확인을 누르면 제출합니다.`, Boolean(empty.length));
      return;
    }
    void finish(true, false);
  };

  const noteText = (question: QuizQuestion, s: AnswerState): string => {
    if (s.inflight) return "저장하는 중입니다.";
    if (s.error) return s.error;
    if (s.dirty) return "저장 대기 중입니다.";
    if (s.savedAt) return `${koDateTime(s.savedAt)} 저장됨`;
    return answered(question, s.value) ? "저장됨" : "아직 답하지 않았습니다.";
  };

  const left = remainingMs(data);
  const off = locked();
  // 제출 절차(저장 마무리부터 접수까지) 동안에는 제출 버튼을 막는다.
  const submitOff = memo.moved || memo.ended || memo.finishing;
  return (
    <div className="srv-quiz-take">
      {parts.typeLine("test")}
      <h1>{info ? `${info.title} · ${data.attemptNo}회차` : `${data.attemptNo}회차 응시`}</h1>
      <div className="srv-quiz-bar">
        <p className={`srv-quiz-clock${!Number.isNaN(left) && left <= 60000 ? " is-low" : ""}`} role="timer" aria-live="off">
          {Number.isNaN(left) ? "" : `남은 시간 ${clockText(left)} · 마감 ${koDateTime(data.deadlineAt)}`}
        </p>
        <p className="tiny" role="status" aria-live="polite">{run.alertLine}</p>
      </div>
      <div>
        {memo.moved ? (
          <section className="note srv-quiz-moved" role="alert">
            <h2>다른 기기나 브라우저에서 응시 중입니다</h2>
            <p>이 화면에서는 답을 저장하거나 제출할 수 없습니다. 이 화면에서 이어서 풀면 다른 쪽 화면이 더 저장할 수 없게 됩니다. 마감 일시는 바뀌지 않습니다.</p>
            <StartButton courseId={courseId} quizId={quizId} kj={kj} label="이 화면에서 이어서 응시" />
          </section>
        ) : null}
      </div>
      <ol className="srv-quiz-questions">
        {questions.map((question) => {
          const s = stateOf(question.questionId);
          const name = `srv-quiz-q-${question.questionId}`;
          const picked = Array.isArray(s.value) ? s.value : [];
          return (
            <li key={question.questionId}>
              <fieldset className="srv-quiz-q">
                <legend><b>{`${question.number}. `}</b><span className="tiny">{`${QUESTION_TYPE[question.type] || question.type} · ${scoreText(question.points)}점`}</span></legend>
                <Prose text={question.text} />
                {question.type === "short" ? (
                  <textarea
                    rows={4}
                    maxLength={MAX_TEXT}
                    aria-label={`${question.number}번 답`}
                    disabled={off}
                    value={typeof s.value === "string" ? s.value : ""}
                    onChange={(event) => change(question, event.target.value)}
                    onBlur={() => {
                      clearTimeout(run.timers.get(question.questionId));
                      void save(question);
                    }}
                  />
                ) : (
                  <>
                    {(question.options || []).map((option) => (
                      <label className="choice" key={option.optionId}>
                        <input
                          type={question.type === "single" ? "radio" : "checkbox"}
                          name={name}
                          value={String(option.optionId)}
                          disabled={off}
                          checked={picked.includes(option.optionId)}
                          onChange={(event) => {
                            const next = question.type === "single"
                              ? (event.target.checked ? [option.optionId] : [])
                              : (event.target.checked ? [...picked, option.optionId] : picked.filter((id) => id !== option.optionId)).sort((a, b) => a - b);
                            change(question, next);
                          }}
                        />
                        <span>{option.text}</span>
                      </label>
                    ))}
                    {question.type === "single" ? (
                      <button type="button" className="btn btn-ghost srv-quiz-clear" aria-label={`${question.number}번 선택 지우기`} disabled={off} onClick={() => change(question, [])}>선택 지우기</button>
                    ) : null}
                  </>
                )}
                <p className={s.error ? "tiny err" : "tiny"} role="status" aria-live="polite">{noteText(question, s)}</p>
              </fieldset>
            </li>
          );
        })}
      </ol>
      <p className="tiny" role={run.message.alert ? "alert" : "status"} aria-live="polite">{run.message.text}</p>
      <div className="submit-actions">
        <Button
          kind="ghost"
          hidden={!run.confirming}
          disabled={submitOff}
          onClick={() => {
            run.confirming = false;
            say("");
          }}
        >
          취소
        </Button>
        <Button kind="primary" disabled={submitOff} onClick={submit}>{memo.submitting ? "제출하는 중…" : run.confirming ? "제출 확인" : "제출"}</Button>
      </div>
    </div>
  );
}

interface ResultProps {
  data: AttemptDetail;
  info: QuizInfo | null;
  memo: AttemptMemo;
  detailHref: string;
  parts: DetailParts;
}

function ResultView({ data, info, memo, detailHref, parts }: ResultProps) {
  const live = data.status === "in_progress";
  const rows: ([string, ReactNode] | null)[] = [
    ["상태", live ? "제출 처리 중" : ATTEMPT_STATUS[data.status || ""] || data.status],
    ["시작", koDateTime(data.startedAt)],
    ["접수", data.submittedAt ? `${koDateTime(data.submittedAt)} · ${FINISH[data.finishReason || ""] || ""}` : "—"],
    data.totalScore != null ? ["총점", `${scoreText(data.totalScore)}${info ? ` / ${scoreText(info.maxScore)}` : ""}점`] : null,
    info ? ["결과공개", releaseText(info)] : null,
  ];
  const questions = Array.isArray(data.questions) ? data.questions : [];
  return (
    <div>
      {parts.typeLine("test")}
      <h1>{info ? `${info.title} · ${data.attemptNo}회차` : `${data.attemptNo}회차 답안`}</h1>
      {memo.receipt ? (
        <section className="note ok" role="status">
          <h2>{memo.receipt.finishReason === "deadline" ? "마감 시각에 제출되었습니다" : "제출했습니다"}</h2>
          <p>{`서버 접수 시각 ${koDateTime(memo.receipt.submittedAt)}`}</p>
        </section>
      ) : null}
      {parts.metaTable(rows.filter((row): row is [string, ReactNode] => row !== null))}
      {data.status === "submitted" ? <p className="tiny">단답형 문항을 채점하고 있습니다.</p> : null}
      <h2>내 답안</h2>
      <ol className="srv-quiz-questions">
        {questions.map((question) => {
          const answer = question.answer || {};
          const picked = new Set(Array.isArray(answer.optionIds) ? answer.optionIds : []);
          return (
            <li className="srv-quiz-q" key={question.questionId}>
              <p>
                <b>{`${question.number}. `}</b>
                <span className="tiny">{`${QUESTION_TYPE[question.type] || question.type} · ${scoreText(question.points)}점`}</span>
                {answer.correct != null ? <Badge tone={answer.correct ? "success" : "danger"}>{answer.correct ? "정답" : "오답"}</Badge> : null}
                {answer.awardedScore != null ? <span className="tiny">{` 받은 점수 ${scoreText(answer.awardedScore)}점`}</span> : null}
              </p>
              <Prose text={question.text} />
              {question.type === "short" ? (
                <div className="srv-quiz-answer"><span className="tiny">내 답</span><Prose text={answer.text} empty="답하지 않았습니다." /></div>
              ) : (
                <ul className="srv-quiz-options">
                  {(question.options || []).map((option) => (
                    <li key={option.optionId}>
                      <span>{option.text}</span>
                      {picked.has(option.optionId) ? <Badge tone="neutral">내 선택</Badge> : null}
                      {option.correct === true ? <Badge tone="success">정답 보기</Badge> : null}
                    </li>
                  ))}
                </ul>
              )}
              {answer.feedback ? <div><span className="tiny">채점 의견</span><Prose text={answer.feedback} /></div> : null}
              {question.explanation ? <div><span className="tiny">해설</span><Prose text={question.explanation} /></div> : null}
            </li>
          );
        })}
      </ol>
      <div className="btn-row"><Link href={detailHref} className="btn btn-secondary">시험 정보</Link></div>
    </div>
  );
}
