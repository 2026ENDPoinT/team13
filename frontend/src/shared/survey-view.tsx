// 서버 설문의 응답 폼과 결과 집계 화면. 강의실 설문(learning/surveys-server)과 알림마당 설문
// (home/surveys-server)이 함께 쓴다. 문항·보기·답변 문구는 모두 텍스트로만 넣는다.
// 고른 답은 세션이 바뀌면 버리는 메모리 맵에만 두어 다시 그리기와 재조회 뒤에도 남는다.
import { useEffect, useRef, useState, type ReactNode } from "react";
import { getState, render } from "../store.ts";
import { Badge, Button, ErrorState, LoadingState } from "../ui/kit.tsx";
import { ApiError } from "./api.ts";
import { subscribe } from "./backend.ts";
import { freshVisit, useResource } from "./remote-cache.ts";
import { koDateTime } from "./home-remote.ts";
import {
  surveyPath, surveyDetails, surveyResults, surveyTexts, textsKey, completeSurvey, completionUncertain,
  surveyMessage, isConflict, PHASE, STATE, QUESTION_TYPE, WITHHELD, TEXT_PAGE_SIZE,
} from "./surveys-remote.ts";
import type { CompletionReceipt, SurveyAnswer, SurveyDetail, SurveyInfo, SurveyQuestion } from "./surveys-remote.ts";

type Id = number | string;

const MAX_TEXT = 2000;

/** 고른 답(문항 ID → 보기 ID들 | 서술 답)과, 완료했다면 그 결과와 그것을 만든 history 항목. */
interface Draft {
  answers: Map<number, number[] | string>;
  receipt: CompletionReceipt | null;
  receiptEntry: unknown;
}

// surveyPath -> Draft
const drafts = new Map<string, Draft>();
let session = "";
subscribe((next: { status: string; me: { userId: unknown } | null }) => {
  const id = `${next.status}:${next.me ? String(next.me.userId) : ""}`;
  if (id === session) return;
  session = id;
  drafts.clear();
});

function draftOf(path: string): Draft {
  let draft = drafts.get(path);
  if (!draft) {
    draft = { answers: new Map(), receipt: null, receiptEntry: null };
    drafts.set(path, draft);
  }
  return draft;
}

/** 완료 결과는 그것을 만든 history 항목에서만 보여 준다. */
export function surveyReceipt(courseId: Id | null | undefined, surveyId: Id): CompletionReceipt | null {
  const memo = drafts.get(surveyPath(courseId, surveyId));
  return memo && memo.receipt && memo.receiptEntry === history.state ? memo.receipt : null;
}

/** 빈 줄로 나눈 문단, 줄바꿈은 `<br>`. 내용이 없으면 null. */
function prose(text: unknown): ReactNode {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  if (!blocks.length) return null;
  return (
    <div className="prose">
      {blocks.map((block, index) => (
        <p key={index}>{block.split("\n").map((line, at) => [at ? <br key="br" /> : null, line])}</p>
      ))}
    </div>
  );
}

function RetryButton({ reload }: { reload: () => void }) {
  const [pressed, setPressed] = useState(false);
  return (
    <Button
      kind="ghost"
      disabled={pressed}
      onClick={() => {
        setPressed(true);
        reload();
      }}
    >
      다시 불러오기
    </Button>
  );
}

export function periodText(info: Pick<SurveyInfo, "openAt" | "closeAt">): string {
  const close = koDateTime(info.closeAt);
  return info.openAt ? `${koDateTime(info.openAt)} ~ ${close}` : `~ ${close}`;
}

export function phaseBadge(phase: string): ReactNode {
  return <Badge tone={phase === "open" ? "success" : phase === "closed" ? "danger" : "neutral"}>{PHASE[phase] || phase}</Badge>;
}

export function stateBadge(state: string): ReactNode {
  return <Badge tone={state === "completed" ? "success" : "neutral"}>{STATE[state] || state}</Badge>;
}

/** 결과 공개 설정을 한 줄로. */
export function releaseText(info: Pick<SurveyInfo, "resultsVisible" | "resultsReleaseAt">): string {
  if (!info.resultsVisible) return "공개하지 않습니다";
  return info.resultsReleaseAt ? `${koDateTime(info.resultsReleaseAt)} 이후, 마감 뒤 공개` : "마감 뒤 공개";
}

function selectText(question: SurveyQuestion): string {
  if (question.type !== "multiple") return "";
  const max = question.maxSelect ?? (question.options || []).length;
  return question.minSelect === max ? ` · ${max}개 고르기` : ` · ${question.minSelect ?? 1}~${max}개 고르기`;
}

// ---------- 응답 폼 ----------

export interface SurveyFormOptions {
  /** 완료 뒤 화면이 할 일(창 닫기 등). */
  onDone?: (receipt: CompletionReceipt) => void;
}

/**
 * 응답자 상세(`questions`가 있을 때)의 응답 폼. `응답 제출`이 답을 확인해 `completeSurvey`로 보낸다.
 * `onDone(receipt)`은 완료 뒤 화면이 할 일이다(창 닫기 등).
 */
export function surveyForm(courseId: Id | null | undefined, surveyId: Id, data: SurveyDetail, { onDone }: SurveyFormOptions = {}): ReactNode {
  const path = surveyPath(courseId, surveyId);
  return <SurveyForm key={path} courseId={courseId} surveyId={surveyId} data={data} onDone={onDone} />;
}

interface Message {
  text: string;
  alert: boolean;
}

function SurveyForm({ courseId, surveyId, data, onDone }: { courseId: Id | null | undefined; surveyId: Id; data: SurveyDetail; onDone?: (receipt: CompletionReceipt) => void }) {
  const path = surveyPath(courseId, surveyId);
  const memo = draftOf(path);
  const questions = Array.isArray(data.questions) ? data.questions : [];
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<Message>({ text: "", alert: false });
  const sending = useRef(false);
  const live = useRef(true);
  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
    };
  }, []);
  const say = (text: string, alert = false) => {
    if (live.current) setMessage({ text: text || "", alert });
  };

  /** `srv-survey-complete`: 답을 확인하고 보낸다. */
  async function send() {
    if (sending.current) return;
    const answers: SurveyAnswer[] = [];
    const problems: string[] = [];
    for (const question of questions) {
      const value = memo.answers.get(question.questionId);
      if (question.type === "essay") {
        const text = typeof value === "string" ? value : "";
        if (text.length > MAX_TEXT) problems.push(`${question.questionNo}번은 ${MAX_TEXT.toLocaleString("ko-KR")}자 이하입니다`);
        if (text.trim()) answers.push({ questionId: question.questionId, text });
        else if (question.required) problems.push(`${question.questionNo}번에 답하세요`);
        continue;
      }
      const ids = Array.isArray(value) ? value : [];
      if (!ids.length) {
        if (question.required) problems.push(`${question.questionNo}번에 답하세요`);
        continue;
      }
      if (question.type === "multiple") {
        const min = question.minSelect ?? 1;
        const max = question.maxSelect ?? (question.options || []).length;
        if (ids.length < min || ids.length > max) {
          problems.push(`${question.questionNo}번은 ${min === max ? `${max}개` : `${min}~${max}개`}를 고르세요`);
          continue;
        }
      }
      answers.push({ questionId: question.questionId, optionIds: [...ids].sort((a, b) => a - b) });
    }
    if (problems.length) {
      say(`${problems.join(". ")}.`, true);
      return;
    }
    sending.current = true;
    setBusy(true);
    say("ASTRA 서버에 응답을 보내고 있습니다.");
    try {
      const receipt = await completeSurvey(courseId, surveyId, answers);
      memo.answers.clear();
      memo.receipt = receipt;
      memo.receiptEntry = history.state;
      getState().toast = "설문 응답을 제출했습니다";
      render();
      void surveyDetails.load(path, path);
      if (onDone) onDone(receipt);
    } catch (error) {
      say(surveyMessage(error), true);
      if (isConflict(error, "already_completed") || isConflict(error, "deadline_passed") || isConflict(error, "survey_changed")
        || isConflict(error, "idempotency_conflict") || (error instanceof ApiError && error.status === 404)) {
        void surveyDetails.load(path, path);
      } else if (completionUncertain(courseId, surveyId)) {
        say("제출 결과를 확인하지 못했습니다. 네트워크를 확인한 뒤 같은 답으로 다시 제출하세요. 이미 접수되었다면 같은 결과를 돌려받습니다.", true);
      }
    } finally {
      sending.current = false;
      if (live.current) setBusy(false);
    }
  }

  return (
    <div className="srv-survey-form" aria-busy={busy ? "true" : undefined}>
      <p className="tiny">
        {data.survey && data.survey.anonymous
          ? "익명 설문입니다. 담당자에게는 누가 무엇을 답했는지 보이지 않습니다. 제출한 응답은 고칠 수 없습니다."
          : "실명 설문입니다. 담당자는 집계만 보고, 누가 무엇을 답했는지는 보이지 않습니다. 제출한 응답은 고칠 수 없습니다."}
      </p>
      <ol className="srv-survey-questions">
        {questions.map((question) => <QuestionBlock key={question.questionId} question={question} memo={memo} disabled={busy} />)}
      </ol>
      <p className="tiny" role={message.alert ? "alert" : "status"} aria-live="polite">{message.text}</p>
      <div className="submit-actions">
        <Button kind="primary" disabled={busy} onClick={() => void send()}>{busy ? "제출하는 중…" : "응답 제출"}</Button>
      </div>
    </div>
  );
}

function QuestionBlock({ question, memo, disabled }: { question: SurveyQuestion; memo: Draft; disabled: boolean }) {
  const id = `srv-survey-q-${question.questionId}`;
  const saved = memo.answers.get(question.questionId);
  let body: ReactNode;
  if (question.type === "essay") {
    body = (
      <textarea
        rows={4}
        maxLength={MAX_TEXT}
        aria-label={`${question.questionNo}번 답`}
        defaultValue={typeof saved === "string" ? saved : ""}
        disabled={disabled}
        onChange={(event) => memo.answers.set(question.questionId, event.target.value)}
      />
    );
  } else {
    const multiple = question.type === "multiple";
    const picked = new Set(Array.isArray(saved) ? saved : []);
    body = (question.options || []).map((option) => {
      const label = question.type === "scale" ? `${option.optionNo} · ${option.text}` : option.text;
      return (
        <label key={option.optionId} className="choice">
          <input
            type={multiple ? "checkbox" : "radio"}
            name={id}
            value={String(option.optionId)}
            defaultChecked={picked.has(option.optionId)}
            disabled={disabled}
            onChange={(event) => {
              const current = memo.answers.get(question.questionId);
              const chosen = multiple && Array.isArray(current) ? current.filter((optionId) => optionId !== option.optionId) : [];
              if (event.target.checked) chosen.push(option.optionId);
              memo.answers.set(question.questionId, chosen);
            }}
          />
          <span>{label}</span>
        </label>
      );
    });
  }
  return (
    <li>
      <fieldset className="srv-survey-q">
        <legend>
          <b>{`${question.questionNo}. `}</b>
          <span className="tiny">{`${QUESTION_TYPE[question.type] || question.type}${question.required ? " · 필수" : " · 선택"}${selectText(question)}`}</span>
        </legend>
        {prose(question.text)}
        {body}
      </fieldset>
    </li>
  );
}

// ---------- 결과 ----------

export interface ResultsSectionOptions {
  title?: string;
}

/** 결과 집계 화면. 마운트되면 `GET …/results`를 받는다. 서버가 가린 설문·문항은 이유만 보인다. */
export function resultsSection(courseId: Id | null | undefined, surveyId: Id, { title = "결과" }: ResultsSectionOptions = {}): ReactNode {
  return <ResultsSection courseId={courseId} surveyId={surveyId} title={title} />;
}

function ResultsSection({ courseId, surveyId, title }: { courseId: Id | null | undefined; surveyId: Id; title: string }) {
  const path = surveyPath(courseId, surveyId);
  const load = () => {
    void surveyResults.load(path, path);
  };
  const entry = useResource(surveyResults, path, load);
  useEffect(() => {
    if (freshVisit(`survey-results-${path}`, path) === "entry" || !surveyResults.peek(path)) void surveyResults.load(path, path);
  });
  const data = entry && entry.data;
  let body: ReactNode;
  if (!data) {
    if (!entry || !entry.error) body = <LoadingState title="결과를 불러오는 중입니다" />;
    else if (entry.error instanceof ApiError && entry.error.status === 404) body = <p className="tiny">아직 결과를 볼 수 없습니다.</p>;
    else body = <ErrorState title="결과를 불러오지 못했습니다" body={surveyMessage(entry.error)} actions={<RetryButton reload={load} />} />;
  } else {
    const count = <p className="tiny">{`응답 ${data.respondentCount}명 · ${data.anonymous ? "익명" : "실명"} 설문`}</p>;
    if (!data.disclosed) {
      const reason = (data.withheldReason && WITHHELD[data.withheldReason]) || "아직 결과를 볼 수 없습니다.";
      body = (
        <>
          {count}
          <p className="note">{`${reason}${data.withheldReason === "below_threshold" ? ` (최소 ${data.minRespondents}명)` : ""}`}</p>
        </>
      );
    } else {
      const questions = Array.isArray(data.questions) ? data.questions : [];
      body = (
        <>
          {count}
          <ol className="srv-survey-questions">
            {questions.map((question) => (
              <QuestionResult key={question.questionId} path={path} question={question} min={data.minRespondents} />
            ))}
          </ol>
        </>
      );
    }
  }
  return (
    <section className="srv-survey-results" aria-label={`설문 ${title}`}>
      <h2>{title}</h2>
      <div className="stack" aria-live="polite">{body}</div>
    </section>
  );
}

function QuestionResult({ path, question, min }: { path: string; question: SurveyQuestion; min: number }) {
  const head = (
    <p>
      <b>{`${question.questionNo}. `}</b>
      <span className="tiny">{`${QUESTION_TYPE[question.type] || question.type} · 답 ${question.answerCount}명`}</span>
    </p>
  );
  let detail: ReactNode = null;
  if (question.suppressed) {
    detail = <p className="tiny">{`답한 사람이 ${min}명보다 적어 이 문항의 집계를 가렸습니다.`}</p>;
  } else if (question.type === "essay") {
    detail = <TextAnswers path={path} question={question} />;
  } else {
    const total = Math.max(1, question.answerCount ?? 0);
    const options = Array.isArray(question.options) ? question.options : [];
    detail = (
      <>
        <ul className="srv-survey-bars">
          {options.map((option) => {
            const count = option.count ?? 0;
            const pct = Math.round((count / total) * 100);
            const label = question.type === "scale" ? `${option.optionNo} · ${option.text}` : option.text;
            return (
              <li key={option.optionId}>
                <span className="srv-survey-bar-label">{label}</span>
                <meter min={0} max={total} value={count} aria-label={`${label} ${count}명`} />
                <span className="tiny">{`${count}명 (${pct}%)`}</span>
              </li>
            );
          })}
        </ul>
        {question.scaleMean != null ? <p className="tiny">{`평균 ${question.scaleMean}`}</p> : null}
      </>
    );
  }
  return (
    <li className="srv-survey-q">
      {head}
      {prose(question.text)}
      {detail}
    </li>
  );
}

/** 서술형 답변. 누르면 첫 쪽을 받고, `더 보기`로 다음 쪽을 붙인다. 문구 순서다(입력 순서가 아니다). */
function TextAnswers({ path, question }: { path: string; question: SurveyQuestion }) {
  const [items, setItems] = useState<string[]>([]);
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState("");
  const [busy, setBusy] = useState(false);
  const [exhausted, setExhausted] = useState(false);
  const live = useRef(true);
  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
    };
  }, []);
  const hasAnswers = Boolean(question.answerCount);

  const more = async () => {
    if (busy || !hasAnswers || exhausted) return;
    const next = page + 1;
    const key = textsKey(path, question.questionId, next);
    setBusy(true);
    setStatus("답변을 불러오는 중입니다.");
    await surveyTexts.load(key, path, question.questionId, next);
    if (!live.current) return;
    const entry = surveyTexts.peek(key);
    setBusy(false);
    if (!entry || !entry.data) {
      setStatus(surveyMessage(entry && entry.error));
      return;
    }
    const added = Array.isArray(entry.data.items) ? entry.data.items.map((item) => String(item)) : [];
    const shown = items.length + added.length;
    setPage(next);
    setItems([...items, ...added]);
    setStatus(`${entry.data.total}개 중 ${shown}개 (문구 순서)`);
    setExhausted(!(shown < entry.data.total && added.length === TEXT_PAGE_SIZE));
  };

  return (
    <div>
      <ul className="srv-survey-texts">
        {items.map((text, index) => <li key={index}>{prose(text) || <span>{text}</span>}</li>)}
      </ul>
      <p className="tiny" role="status" aria-live="polite">{status}</p>
      <Button
        kind="secondary"
        disabled={busy || !hasAnswers || exhausted}
        hidden={exhausted}
        aria-busy={busy ? "true" : undefined}
        onClick={() => void more()}
      >
        {page ? "더 보기" : hasAnswers ? "답변 보기" : "답변 없음"}
      </Button>
    </div>
  );
}
