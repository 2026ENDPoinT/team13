// 교수의 서버 시험 화면(백엔드 모드): 시험 출제(등록, `revision`으로 설정 수정, 문항 전체 교체, 응시가 생기면
// `questions_locked`), 응시 현황(응시 목록, `cursor`로 다음 쪽), 시험 채점(응시 하나의 답안과 출제 순서의 보기, 단답형 점수와
// 의견), 시험 상세(문항마다 정답·배점 정정과 재채점, 오프라인 시험은 성적부와 같은 점수 표), 삭제(delete-server.tsx).
// 편집 중인 문항과 정답·배점은 세션이 바뀌면 버리는 메모리 맵에만 둔다.
import { useEffect, useRef, useState, type ReactNode } from "react";
import { ApiError } from "../../shared/api.ts";
import { backendStatus, courseIdFor, roleIn, subscribe } from "../../shared/backend.ts";
import { backendPanel } from "../../shared/backend-prompt.tsx";
import { freshVisit, useResource, type Entry } from "../../shared/remote-cache.ts";
import { koDateTime } from "../../shared/home-remote.ts";
import { routeFor } from "../../shared/routes.ts";
import {
  quizLists, quizDetails, attemptPages, attemptDetails, quizListKey, quizKey, attemptKey, quizIdOf, quizRef,
  createQuiz, updateQuiz, replaceQuestions, gradeAnswer, regradeQuestion, loadMoreAttempts, quizMessage, isConflict,
  PHASE, ATTEMPT_STATUS, FINISH, QUESTION_TYPE, scoreText,
  type QuizInfo, type QuizQuestion, type QuizAnswer, type QuizOption, type AttemptSummary, type QuizDetail,
} from "../../shared/quizzes-remote.ts";
import { getState, render } from "../../store.ts";
import { Link, navigate } from "../../router.tsx";
import { Badge, Button, Field, EmptyState, Table } from "../../ui/kit.tsx";
import type { Course } from "../../data.ts";
import type { ScreenCtx } from "../../types.ts";
import type { FormParts } from "./assignments-server.tsx";
import { deleteControl } from "./delete-server.tsx";
import { quizScoreSheet } from "./grades-server.tsx";

// quizzes-remote.ts의 타입에 없는, 담당자 응답에만 오는 필드.
interface QuizRow extends QuizInfo { inProgressCount?: number; finishedCount?: number; awaitingGradingCount?: number }
interface AttemptRow extends AttemptSummary { pendingCount?: number }
interface GradingOption extends QuizOption { number?: number; selected?: boolean }

const missing = (error: unknown) => error instanceof ApiError && error.status === 404;
const pf = (screenId: string, extra?: Record<string, string>) => routeFor("professor", "screen", { screenId, ...(extra || {}) });
const LOGIN = "ASTRA 서버에 로그인하면 이 과목의 시험을 서버에 내고 응시를 채점할 수 있습니다.";

function toastNow(text: string) {
  getState().toast = text;
  render();
}

function Retry({ reload }: { reload: () => void }) {
  const [pressed, setPressed] = useState(false);
  return <Button kind="ghost" disabled={pressed} onClick={() => { setPressed(true); reload(); }}>다시 불러오기</Button>;
}

function Failure({ error, reload }: { error: unknown; reload?: () => void }) {
  return <div><p className="err" role="alert">{quizMessage(error)}</p>{reload ? <Retry reload={reload} /> : null}</div>;
}

function Prose({ text }: { text: string | null | undefined }) {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  return (
    <div className="pf-prose">
      {blocks.map((block, index) => (
        <p key={index}>{block.split("\n").map((line, at) => <span key={at}>{at ? <br /> : null}{line}</span>)}</p>
      ))}
    </div>
  );
}

function gate(course: Course): ReactNode {
  if (backendStatus() === "anon") return <div className="stack">{backendPanel({ text: LOGIN })}</div>;
  if (!courseIdFor(course.id)) return <div className="stack">{backendPanel()}<EmptyState title="이 과목은 ASTRA 서버에 없습니다" body="서버에 등록된 과목에만 시험을 내고 채점할 수 있습니다." /></div>;
  if (roleIn(course.id) !== "INSTRUCTOR") return <div className="stack">{backendPanel()}<p className="pf-sub">이 과목을 담당하는 교수 계정으로 로그인해야 시험을 내고 채점할 수 있습니다.</p></div>;
  return null;
}

const toInput = (value: string | null | undefined) => (value ? String(value).slice(0, 16) : "");
const fromInput = (value: string) => (value ? (value.length === 16 ? `${value}:00` : value) : null);
const hundredths = (n: number) => Math.abs(Math.round(n * 100) - n * 100) < 1e-6;
const periodText = (item: QuizInfo) => (item.openAt ? `${koDateTime(item.openAt)} ~ ${koDateTime(item.closeAt)}` : `~ ${koDateTime(item.closeAt)}`);

/** Loads `key` on a new visit or when nothing is cached. */
function useVisit<T>(resource: { peek(key: string): Entry<T> | null }, scope: string, key: string, load: () => void) {
  useEffect(() => {
    if (freshVisit(scope, key) === "entry" || !resource.peek(key)) load();
  }, [scope, key]);
}

function useMounted() {
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);
  return mounted;
}

interface Note { text: string; alert: boolean }

// ---------- 문항 편집 ----------

interface DraftOption { text: string; correct: boolean }
interface DraftQuestion { type: string; text: string; points: string; explanation: string; options: DraftOption[] }
interface KeyDraft { points: string; correct: Set<number> }

// 폼 키("<courseId>:new" 또는 "<courseId>:<quizId>") -> 편집 중인 문항 목록
const drafts = new Map<string, DraftQuestion[]>();
// "<courseId>:<quizId>:<questionId>" -> 입력 중인 정답·배점, 그 문항의 재채점 안내
const keyDrafts = new Map<string, KeyDraft>();
const keyNotes = new Map<string, Note>();
const openForms = new Set<string>(); // 펼쳐 둔 정정 폼(같은 키)
// 폼 키 -> 문항 저장 안내. 저장 뒤 재조회로 문항 칸을 다시 그려도 남고, 세션이 바뀌면 버린다.
const questionNotes = new Map<string, Note>();
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  drafts.clear();
  keyDrafts.clear();
  keyNotes.clear();
  openForms.clear();
  questionNotes.clear();
});

const blankOption = (): DraftOption => ({ text: "", correct: false });
const blankQuestion = (): DraftQuestion => ({ type: "single", text: "", points: "1", explanation: "", options: [blankOption(), blankOption()] });

function fromServer(questions: QuizQuestion[] | undefined): DraftQuestion[] {
  return (questions || []).map((q) => ({
    type: q.type,
    text: q.text || "",
    points: scoreText(q.points),
    explanation: q.explanation || "",
    options: (q.options || []).map((o) => ({ text: o.text, correct: Boolean(o.correct) })),
  }));
}

/** 문항 편집기. `list`는 메모리의 문항 배열이고 입력할 때마다 바로 바뀐다. */
function QuestionEditor({ list, locked, disabled, onChange }: { list: DraftQuestion[]; locked: boolean; disabled: boolean; onChange: () => void }) {
  const rootRef = useRef<HTMLDivElement>(null);
  const [focusLast, setFocusLast] = useState(false);
  useEffect(() => {
    if (!focusLast) return;
    setFocusLast(false);
    const texts = rootRef.current?.querySelectorAll<HTMLTextAreaElement>("textarea[data-q-text]");
    if (texts && texts.length) texts[texts.length - 1].focus();
  }, [focusLast]);
  const off = locked || disabled;
  return (
    <div className="stack" ref={rootRef}>
      {list.map((q, index) => (
        <fieldset className="sheet pf-q" key={index}>
          <legend>{`${index + 1}번 문항`}</legend>
          <div className="pf-form">
            <Field label="종류">
              <select aria-label={`${index + 1}번 문항 종류`} value={q.type} disabled={off} onChange={(event) => {
                q.type = event.currentTarget.value;
                if (q.type === "short") q.options = [];
                else if (q.options.length < 2) q.options = [blankOption(), blankOption()];
                if (q.type === "single") {
                  let seen = false;
                  q.options.forEach((o) => { if (o.correct) { o.correct = !seen; seen = true; } });
                }
                onChange();
              }}>
                {Object.entries(QUESTION_TYPE).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
              </select>
            </Field>
            <Field label="배점">
              <input type="number" min="0.01" max="1000" step="0.01" value={q.points} aria-label={`${index + 1}번 배점`} disabled={off}
                onChange={(event) => { q.points = event.currentTarget.value; onChange(); }} />
            </Field>
            <div className="span-2"><Field label="문항 (일반 텍스트)">
              <textarea rows={3} maxLength={5000} data-q-text="" aria-label={`${index + 1}번 문항`} value={q.text} disabled={off}
                onChange={(event) => { q.text = event.currentTarget.value; onChange(); }} />
            </Field></div>
            {q.type === "short"
              ? <p className="span-2 pf-sub">단답형은 학생이 제출한 뒤 시험 채점 화면에서 점수를 줍니다.</p>
              : <div className="span-2">
                  <p className="pf-sub">{q.type === "single" ? "정답 보기를 하나 고르세요." : "정답 보기를 하나 이상 고르세요. 고른 보기 집합이 정답과 같을 때만 배점을 줍니다."}</p>
                  <div className="stack">
                    {q.options.map((option, at) => (
                      <div className="pf-inline" key={at}>
                        <label className="choice">
                          <input type={q.type === "single" ? "radio" : "checkbox"} name={`pf-quiz-correct-${index}`} checked={option.correct} disabled={off} onChange={(event) => {
                            if (q.type === "single") q.options.forEach((o) => { o.correct = o === option; });
                            else option.correct = event.currentTarget.checked;
                            onChange();
                          }} />
                          <span>정답</span>
                        </label>
                        <input maxLength={1000} value={option.text} aria-label={`${index + 1}번 보기 ${at + 1}`} disabled={off}
                          onChange={(event) => { option.text = event.currentTarget.value; onChange(); }} />
                        {locked ? null : <Button kind="ghost" aria-label={`${index + 1}번 보기 ${at + 1} 빼기`} disabled={off || q.options.length <= 2}
                          onClick={() => { q.options.splice(at, 1); onChange(); }}>빼기</Button>}
                      </div>
                    ))}
                    {!locked && q.options.length < 20
                      ? <Button kind="ghost" disabled={off} onClick={() => { q.options.push(blankOption()); onChange(); }}>보기 추가</Button>
                      : null}
                  </div>
                </div>}
            <div className="span-2"><Field label="해설 (정답을 공개할 때 학생에게 보입니다)">
              <textarea rows={2} maxLength={5000} aria-label={`${index + 1}번 해설`} value={q.explanation} disabled={off}
                onChange={(event) => { q.explanation = event.currentTarget.value; onChange(); }} />
            </Field></div>
          </div>
          {locked ? null : <div className="pf-actions">
            <Button kind="ghost" aria-label={`${index + 1}번 문항 위로`} disabled={off || index === 0}
              onClick={() => { list.splice(index - 1, 0, ...list.splice(index, 1)); onChange(); }}>위로</Button>
            <Button kind="ghost" aria-label={`${index + 1}번 문항 빼기`} disabled={off || list.length <= 1}
              onClick={() => { list.splice(index, 1); onChange(); }}>문항 빼기</Button>
          </div>}
        </fieldset>
      ))}
      {locked ? null : <div className="pf-actions">
        <Button kind="secondary" disabled={off} onClick={() => { list.push(blankQuestion()); onChange(); setFocusLast(true); }}>문항 추가</Button>
      </div>}
    </div>
  );
}

interface QuestionBody { type: string; text: string; points: number; explanation: string | null; options: DraftOption[] }

/** 편집 중인 문항을 검사해 요청 본문으로. `{ errors }` 또는 `{ questions }`. */
function readQuestions(list: DraftQuestion[]): { errors: string[] } | { questions: QuestionBody[] } {
  const errors: string[] = [];
  if (!list.length || list.length > 200) errors.push("문항은 1개 이상 200개 이하입니다");
  let total = 0;
  const questions = list.map((q, index) => {
    const n = index + 1;
    const points = Number(q.points);
    if (!q.text.trim()) errors.push(`${n}번 문항을 입력하세요`);
    if (q.text.length > 5000 || q.explanation.length > 5000) errors.push(`${n}번 문항과 해설은 5,000자 이하입니다`);
    if (!Number.isFinite(points) || points <= 0 || points > 1000 || !hundredths(points)) errors.push(`${n}번 배점은 0보다 크고 1000 이하, 소수 둘째 자리까지입니다`);
    else total += points;
    const out: QuestionBody = { type: q.type, text: q.text, points, explanation: q.explanation.trim() ? q.explanation : null, options: [] };
    if (q.type !== "short") {
      if (q.options.length < 2 || q.options.length > 20) errors.push(`${n}번 보기는 2개 이상 20개 이하입니다`);
      if (q.options.some((o) => !o.text.trim())) errors.push(`${n}번 보기 문구를 모두 입력하세요`);
      const correct = q.options.filter((o) => o.correct).length;
      if (q.type === "single" ? correct !== 1 : correct < 1) errors.push(`${n}번 정답 보기를 ${q.type === "single" ? "하나" : "하나 이상"} 고르세요`);
      out.options = q.options.map((o) => ({ text: o.text.trim(), correct: Boolean(o.correct) }));
    }
    return out;
  });
  if (total > 1000) errors.push("문항 배점의 합은 1000 이하입니다");
  return errors.length ? { errors } : { questions };
}

// ---------- 시험 출제 ----------

/** `pf_exam_create` (백엔드 모드). `?edit=<quizId>`면 그 시험을 고친다. */
export function serverExamFields(ctx: ScreenCtx, course: Course, parts: FormParts): ReactNode {
  const blocked = gate(course);
  if (blocked) return blocked;
  const courseId = Number(courseIdFor(course.id));
  const editId = Number((ctx.params && ctx.params.get("edit")) || "");
  const edit = Number.isInteger(editId) && editId > 0 ? editId : null;
  return (
    <div className="stack">
      {backendPanel()}
      <QuizForm key={edit ?? "new"} week={ctx.state.week || 1} course={course} courseId={courseId} editId={edit} parts={parts} />
      <QuizList course={course} courseId={courseId} target="pf_exam_create" />
    </div>
  );
}

interface QuizFields {
  title: string;
  weekNo: string;
  examType: string;
  openAt: string;
  closeAt: string;
  timeLimitMinutes: string;
  attemptLimit: string;
  maxScore: string;
  shuffleQuestions: boolean;
  published: boolean;
  resultReleaseAt: string;
  answersVisible: boolean;
  description: string;
}

function fieldsOf(info: QuizInfo): QuizFields {
  return {
    title: info.title,
    weekNo: String(info.weekNo),
    examType: info.examType || "online",
    openAt: toInput(info.openAt),
    closeAt: toInput(info.closeAt),
    timeLimitMinutes: info.timeLimitMinutes != null ? String(info.timeLimitMinutes) : "",
    attemptLimit: info.attemptLimit != null ? String(info.attemptLimit) : "",
    maxScore: scoreText(info.maxScore),
    shuffleQuestions: Boolean(info.shuffleQuestions),
    published: Boolean(info.published),
    resultReleaseAt: toInput(info.resultReleaseAt),
    answersVisible: Boolean(info.answersVisible),
    description: info.description || "",
  };
}

/** 설정 폼을 검사한다. `{ errors }` 또는 `{ payload, online }`. */
function readSettings(f: QuizFields): { errors: string[] } | { online: boolean; payload: Record<string, unknown> } {
  const errors: string[] = [];
  const online = f.examType !== "offline";
  const title = f.title.trim();
  const weekNo = Number(f.weekNo);
  const openAt = fromInput(f.openAt);
  const closeAt = fromInput(f.closeAt);
  const minutesRaw = f.timeLimitMinutes.trim();
  const attemptsRaw = f.attemptLimit.trim();
  const minutes = minutesRaw ? Number(minutesRaw) : null;
  const attempts = attemptsRaw ? Number(attemptsRaw) : null;
  const maxScore = Number(f.maxScore);
  if (!title || title.length > 200) errors.push("시험명은 1자 이상 200자 이하입니다");
  if (!Number.isInteger(weekNo) || weekNo < 1 || weekNo > 60) errors.push("주차는 1부터 60 사이입니다");
  if (!closeAt) errors.push("응시 마감 일시를 입력하세요");
  if (openAt && closeAt && openAt >= closeAt) errors.push("시작은 응시 마감보다 앞이어야 합니다");
  if (online && minutes != null && (!Number.isInteger(minutes) || minutes < 1 || minutes > 1440)) errors.push("제한 시간은 1분부터 1440분 사이입니다");
  if (online && attempts != null && (!Number.isInteger(attempts) || attempts < 1 || attempts > 100)) errors.push("응시 횟수는 1부터 100 사이입니다");
  if (!online && (!Number.isFinite(maxScore) || maxScore <= 0 || maxScore > 1000 || !hundredths(maxScore))) errors.push("배점은 0보다 크고 1000 이하, 소수 둘째 자리까지입니다");
  if (f.description.length > 10000) errors.push("안내는 10,000자 이하입니다");
  if (errors.length) return { errors };
  return {
    online,
    payload: {
      weekNo,
      title,
      description: f.description.trim() ? f.description : null,
      openAt,
      closeAt,
      published: f.published,
      timeLimitMinutes: online ? minutes : null,
      attemptLimit: online ? attempts : null,
      shuffleQuestions: online ? f.shuffleQuestions : false,
      resultReleaseAt: fromInput(f.resultReleaseAt),
      answersVisible: online ? f.answersVisible : false,
      maxScore: online ? null : maxScore,
    },
  };
}

type Latest = { info: QuizInfo; revision: number } | { error: unknown };

function QuizForm({ week, course, courseId, editId, parts }: { week: number; course: Course; courseId: number; editId: number | null; parts: FormParts }) {
  const { formSheet, span, linkBtn } = parts;
  const formKey = `${courseId}:${editId || "new"}`;
  const key = editId ? quizKey(courseId, editId) : null;
  const entry = useResource(quizDetails, key);
  const mounted = useMounted();
  const [fields, setFields] = useState<QuizFields>({
    title: "", weekNo: String(week), examType: "online", openAt: "", closeAt: "", timeLimitMinutes: "", attemptLimit: "",
    maxScore: "100", shuffleQuestions: false, published: true, resultReleaseAt: "", answersVisible: false, description: "",
  });
  const [filled, setFilled] = useState(false);
  const [status, setStatus] = useState<Note | null>(null);
  const [busy, setBusy] = useState<"" | "settings" | "questions">("");
  const [latest, setLatest] = useState<Latest | null>(null);
  const [, setTick] = useState(0);
  const repaint = () => setTick((n) => n + 1);
  const revision = useRef<number | null>(null);
  const questionsFrom = useRef("");
  const touched = useRef(false);

  useEffect(() => {
    if (!key || !editId) return;
    if (freshVisit("pf-quiz-edit", key) === "entry" || !quizDetails.peek(key)) void quizDetails.load(key, courseId, editId);
  }, [key]);

  const data = entry && entry.data;
  const ready = Boolean(editId && data && data.revision != null);
  if (ready && data && data.revision != null) revision.current = data.revision;
  useEffect(() => {
    if (!ready || filled || !data) return;
    setFilled(true);
    setFields(fieldsOf(data.quiz));
  });

  if (!editId && !drafts.has(formKey)) drafts.set(formKey, [blankQuestion()]);
  // 문항 편집기: 응시가 생기면 잠근다. 서버 문항으로 처음 한 번만 채우고, 문항 저장 뒤에는 서버 값으로 다시 채운다.
  // 잠긴 시험은 바꿀 수 없으므로 언제나 서버 문항을 보인다. 응시 시작과 거절된 문항 저장은 `revision`을 올리지 않아
  // (백엔드 QuizService.replaceQuestions는 409 `questions_locked`를 던져 올린 번호를 되돌린다) 번호로는 알 수 없다.
  const locked = Boolean(data && data.questionsLocked);
  if (ready && data && data.quiz.examType !== "offline") {
    if (!drafts.has(formKey) || locked || (questionsFrom.current !== String(data.revision) && !touched.current)) {
      drafts.set(formKey, fromServer(data.questions));
    }
    questionsFrom.current = String(data.revision);
  }

  const waiting = Boolean(editId && !filled);
  const off = waiting || Boolean(busy);
  const online = fields.examType !== "offline";
  let shown: Note = status || { text: "", alert: false };
  if (waiting) {
    shown = {
      text: !entry || !entry.error ? "시험을 불러오는 중입니다."
        : missing(entry.error) ? "시험을 찾을 수 없습니다. 이 과목의 시험이 아닙니다." : quizMessage(entry.error),
      alert: Boolean(entry && entry.error),
    };
  } else if (editId && data && data.revision != null && !(status && (status.alert || busy === "settings"))) {
    // 저장 실패 안내(alert)는 재조회로 다시 그려도 지우지 않는다. 다음 저장이 바꾼다.
    shown = { text: `수정 번호 ${data.revision} · ${PHASE[data.phase || ""] || data.phase} · 응시 중 ${data.inProgressCount}명 · 종료 ${data.finishedCount}건 · 채점 대기 ${data.awaitingGradingCount}건`, alert: false };
  }

  const set = <K extends keyof QuizFields>(name: K, value: QuizFields[K]) => setFields((prev) => ({ ...prev, [name]: value }));
  const text = (name: "title" | "weekNo" | "openAt" | "closeAt" | "timeLimitMinutes" | "attemptLimit" | "maxScore" | "resultReleaseAt") => ({
    value: fields[name],
    disabled: off,
    onChange: (event: { currentTarget: HTMLInputElement }) => set(name, event.currentTarget.value),
  });
  const choice = (name: "shuffleQuestions" | "published" | "answersVisible", label: string) => (
    <label className="choice pf-check">
      <input type="checkbox" checked={fields[name]} disabled={off} onChange={(event) => set(name, event.currentTarget.checked)} />
      <span>{label}</span>
    </label>
  );

  /** 409 `edit_conflict` 뒤: 최신 시험을 받아 수정 번호를 바꾸고 입력한 폼 옆에 보여 준다. */
  const showLatest = async (id: number) => {
    const detailKey = quizKey(courseId, id);
    await quizDetails.load(detailKey, courseId, id);
    const current = quizDetails.peek(detailKey);
    if (!mounted.current) return;
    if (!current || !current.data) {
      setLatest({ error: current && current.error });
      return;
    }
    if (current.data.revision != null) revision.current = current.data.revision;
    setLatest({ info: current.data.quiz, revision: Number(current.data.revision) });
  };

  /** `pf-quiz-save`: 등록(문항 포함) 또는 읽은 `revision`으로 설정 수정. */
  const saveSettings = async () => {
    if (off) return;
    const read = readSettings(fields);
    if ("errors" in read) return setStatus({ text: read.errors.join(". "), alert: true });
    let body: Record<string, unknown> = read.payload;
    if (!editId) {
      body = { ...read.payload, examType: read.online ? "online" : "offline" };
      if (read.online) {
        const questions = readQuestions(drafts.get(formKey) || []);
        if ("errors" in questions) return setStatus({ text: questions.errors.join(". "), alert: true });
        body.questions = questions.questions;
      }
    }
    setBusy("settings");
    setStatus({ text: "ASTRA 서버에 저장하고 있습니다.", alert: false });
    try {
      if (editId) {
        const updated = await updateQuiz(courseId, editId, { ...body, revision: Number(revision.current) });
        if (updated.revision != null) revision.current = updated.revision;
        if (mounted.current) setStatus(null);
        toastNow("시험 설정을 저장했습니다");
        void quizDetails.load(quizKey(courseId, editId), courseId, editId);
      } else {
        const created = await createQuiz(courseId, body);
        drafts.delete(formKey);
        getState().toast = "시험을 서버에 등록했습니다";
        navigate(pf("pf_exam_create", { kj: course.id, edit: String(created.quiz.quizId) }));
        return;
      }
    } catch (error) {
      if (mounted.current) setStatus({ text: quizMessage(error), alert: true });
      if (editId && isConflict(error, "edit_conflict")) await showLatest(editId);
      else if (!mounted.current) toastNow(quizMessage(error));
    } finally {
      if (mounted.current) setBusy("");
    }
  };

  /** `pf-quiz-questions`: 문항 전체 교체(읽은 `revision`). 409 `questions_locked`면 편집기를 잠근다. */
  const saveQuestions = async () => {
    if (off || !editId) return;
    // 안내는 `questionNotes`에 둔다. 재조회가 문항 칸을 다시 그려도 같은 안내를 보인다.
    const say = (message: string, alert = false) => {
      questionNotes.set(formKey, { text: message, alert });
      if (mounted.current) repaint();
    };
    const read = readQuestions(drafts.get(formKey) || []);
    if ("errors" in read) return say(read.errors.join(". "), true);
    setBusy("questions");
    say("문항을 저장하고 있습니다.");
    try {
      await replaceQuestions(courseId, editId, Number(revision.current), read.questions);
      touched.current = false;
      drafts.delete(formKey);
      questionNotes.delete(formKey);
      toastNow("문항을 저장했습니다");
      await quizDetails.load(quizKey(courseId, editId), courseId, editId);
    } catch (error) {
      say(quizMessage(error), true);
      if (isConflict(error, "questions_locked")) {
        touched.current = false;
        await quizDetails.load(quizKey(courseId, editId), courseId, editId);
        say(quizMessage(error), true);
      } else if (isConflict(error, "edit_conflict")) {
        await showLatest(editId);
        say("다른 곳에서 이 시험을 먼저 수정했습니다. 새 수정 번호를 받았습니다. 편집한 문항은 그대로 두었으니 확인한 뒤 다시 저장하세요.", true);
      }
    } finally {
      if (mounted.current) setBusy("");
    }
  };

  let questionSlot: ReactNode = null;
  const list = drafts.get(formKey);
  if (!editId && list) {
    questionSlot = <><h3>문항</h3><QuestionEditor list={list} locked={false} disabled={off} onChange={repaint} /></>;
  } else if (ready && data && data.quiz.examType !== "offline" && list) {
    const qNote = questionNotes.get(formKey);
    questionSlot = <>
      <h3>{`문항 ${(data.questions || []).length}개 · 배점 합 ${scoreText(data.quiz.maxScore)}점`}</h3>
      {locked
        ? <p className="pf-sub" role="note" data-questions-locked="">응시가 시작된 시험이라 문항과 보기를 바꿀 수 없습니다. 설정(기간, 제한 시간, 응시 횟수, 결과 공개)은 바꿀 수 있고, 진행 중인 응시의 마감 일시는 그대로입니다.</p>
        : <p className="pf-sub">문항 저장은 문항 전체를 이 목록으로 바꿉니다. 응시가 하나라도 생기면 더 바꿀 수 없습니다.</p>}
      <QuestionEditor list={list} locked={locked} disabled={off} onChange={() => { touched.current = true; repaint(); }} />
      <p className="pf-sub" role={qNote && qNote.alert ? "alert" : "status"} aria-live="polite">{qNote ? qNote.text : ""}</p>
      {locked ? null : <div className="pf-actions">
        <Button kind="primary" disabled={off} onClick={() => void saveQuestions()}>{busy === "questions" ? "저장하는 중…" : "문항 저장"}</Button>
      </div>}
    </>;
  }

  return formSheet(editId ? "시험 수정" : "시험",
    span(<Field label="시험명"><input maxLength={200} {...text("title")} /></Field>),
    <Field label="주차"><input type="number" min="1" max="60" {...text("weekNo")} /></Field>,
    <Field label="시험형태 (등록한 뒤 바꿀 수 없습니다)">
      <select value={fields.examType} disabled={Boolean(editId) || off} onChange={(event) => set("examType", event.currentTarget.value)}>
        <option value="online">온라인 시험</option>
        <option value="offline">오프라인(대면) 시험</option>
      </select>
    </Field>,
    <Field label="시작 (비우면 바로 시작)"><input type="datetime-local" {...text("openAt")} /></Field>,
    <Field label="응시 마감"><input type="datetime-local" {...text("closeAt")} /></Field>,
    online ? <Field label="제한 시간(분)"><input type="number" min="1" max="1440" placeholder="비우면 응시 마감까지" {...text("timeLimitMinutes")} /></Field> : null,
    online ? <Field label="응시 횟수"><input type="number" min="1" max="100" placeholder="비우면 제한 없음" {...text("attemptLimit")} /></Field> : null,
    online ? span(choice("shuffleQuestions", "문항 순서 섞기 (학생마다 고정된 순서)")) : null,
    online ? span(choice("answersVisible", "결과 공개 뒤 응시 기간이 끝나면 정답·해설·문항별 점수도 보여 주기")) : null,
    online ? null : <Field label="배점"><input type="number" min="0.01" max="1000" step="0.01" {...text("maxScore")} /></Field>,
    span(choice("published", "학생에게 공개 (끄면 학생 목록에 나오지 않습니다)")),
    <Field label="결과 공개 일시 (비우면 비공개)"><input type="datetime-local" {...text("resultReleaseAt")} /></Field>,
    span(<p className="pf-sub">결과 공개 일시가 지나기 전에는 학생이 받는 응답에 점수가 아예 들어가지 않습니다. 응시 마감은 시작할 때 min(시작 + 제한 시간, 응시 마감)으로 서버가 고정합니다.</p>),
    span(<Field label="안내 (일반 텍스트)">
      <textarea rows={4} maxLength={10000} value={fields.description} disabled={off} onChange={(event) => set("description", event.currentTarget.value)} />
    </Field>),
    span(<p className="pf-sub" role={shown.alert ? "alert" : "status"} aria-live="polite">{shown.text}</p>),
    span(<div>{latest ? ("error" in latest
      ? <p className="pf-sub">{`최신 내용을 불러오지 못했습니다. ${quizMessage(latest.error)}`}</p>
      : <details className="pf-server-pdf" open>
          <summary><b>서버의 최신 내용</b><span className="pf-meta">{` · 수정 번호 ${latest.revision}`}</span></summary>
          <p className="pf-sub">{`${latest.info.title} · ${latest.info.weekNo}주 · ${periodText(latest.info)} · 제한 ${latest.info.timeLimitMinutes ? `${latest.info.timeLimitMinutes}분` : "없음"} · 응시 ${latest.info.attemptLimit ? `${latest.info.attemptLimit}회` : "제한 없음"} · 결과 공개 ${latest.info.resultReleaseAt ? koDateTime(latest.info.resultReleaseAt) : "비공개"}`}</p>
        </details>) : null}</div>),
    span(<div className="pf-actions">
      <Button kind="primary" disabled={off} onClick={() => void saveSettings()}>{busy === "settings" ? "저장하는 중…" : editId ? "설정 저장" : "시험 등록"}</Button>
      {editId ? linkBtn(pf("pf_exam_create", { kj: course.id }), "새 시험 쓰기") : null}
      {editId ? linkBtn(pf("pf_exam_status", { kj: course.id, n: quizRef(editId) }), "응시 현황") : null}
    </div>),
    editId ? span(<div>{ready && data ? deleteControl("quiz", courseId, editId, { title: data.quiz.title, back: pf("pf_exam_create", { kj: course.id }) }) : null}</div>) : null,
    online ? <div className="span-2">{questionSlot}</div> : null,
  );
}

type QuizTarget = "pf_exam_create" | "pf_exam_status" | "pf_exam_grade" | "pf_quiz";

/** 이 과목의 서버 시험 목록. `target`은 행의 주 링크가 가는 화면이다. */
function QuizList({ course, courseId, target }: { course: Course; courseId: number; target: QuizTarget }) {
  const key = quizListKey(courseId);
  const load = () => { void quizLists.load(key, courseId); };
  const entry = useResource(quizLists, key, load);
  useVisit(quizLists, `pf-quizzes-${target}`, key, load);
  const data = entry && entry.data;
  let body: ReactNode;
  if (!data) body = !entry || !entry.error ? <p className="pf-sub">서버에 등록된 시험을 불러오는 중입니다.</p> : <Failure error={entry.error} reload={load} />;
  else {
    const items: QuizRow[] = Array.isArray(data) ? data : [];
    body = !items.length ? <p className="pf-sub">아직 서버에 등록된 시험이 없습니다.</p> : items.map((item) => {
      const ref = quizRef(item.quizId);
      const edit = pf("pf_exam_create", { kj: course.id, edit: String(item.quizId) });
      const status = pf("pf_exam_status", { kj: course.id, n: ref });
      const grading = pf("pf_exam_grade", { kj: course.id, n: ref });
      const detail = pf("pf_quiz", { kj: course.id, n: ref });
      const href = target === "pf_exam_grade" ? grading : target === "pf_exam_status" ? status : target === "pf_quiz" ? detail : edit;
      return (
        <div className="pf-activity" key={item.quizId}>
          <Link className="pf-activity-main" href={href}>
            <Badge tone={item.phase === "open" ? "success" : "neutral"}>{item.published ? PHASE[item.phase || ""] || item.phase : "비공개"}</Badge>
            <span className="pf-activity-copy">
              <b>{item.title}</b>
              <span className="pf-meta">{`${item.weekNo}주 · ${item.examType === "offline" ? "오프라인" : "온라인"} · ${periodText(item)} · 응시 중 ${item.inProgressCount ?? 0} · 종료 ${item.finishedCount ?? 0} · 채점 대기 ${item.awaitingGradingCount ?? 0}`}</span>
            </span>
          </Link>
          <div className="pf-activity-actions">
            <Link href={edit} className="btn btn-ghost" aria-label={`${item.title} 수정`}>수정</Link>
            {item.examType === "offline"
              ? <Link href={detail} className="btn btn-ghost" aria-label={`${item.title} 점수 입력`}>점수 입력</Link>
              : <Link href={status} className="btn btn-ghost" aria-label={`${item.title} 응시 현황`}>응시 현황</Link>}
          </div>
        </div>
      );
    });
  }
  return (
    <section className="sheet" aria-label="ASTRA 서버에 등록된 시험">
      <h2>서버에 등록된 시험</h2>
      <div className="stack">{body}</div>
    </section>
  );
}

// ---------- 응시 현황 / 시험 채점 ----------

/** `pf_exam_status` (백엔드 모드). `?n=srv-<quizId>`면 그 시험의 응시 목록. */
export function serverExamStatus(ctx: ScreenCtx, course: Course): ReactNode {
  const blocked = gate(course);
  if (blocked) return blocked;
  const courseId = Number(courseIdFor(course.id));
  const quizId = quizIdOf(ctx.params && ctx.params.get("n"));
  if (!quizId) return <div className="stack">{backendPanel()}<QuizList course={course} courseId={courseId} target="pf_exam_status" /></div>;
  return (
    <div className="stack">
      {backendPanel()}
      <QuizHeader course={course} courseId={courseId} quizId={quizId} screen="pf_exam_status" />
      <AttemptTable course={course} courseId={courseId} quizId={quizId} />
    </div>
  );
}

/**
 * `pf_exam_grade` (백엔드 모드): 시험 → 응시 → 답안 채점. 응시 하나를 볼 때는 시험 상세를 받지 않는다(답안의 `options`가
 * 출제 순서의 보기와 정답·학생 선택을 함께 준다).
 */
export function serverExamGrade(ctx: ScreenCtx, course: Course): ReactNode {
  const blocked = gate(course);
  if (blocked) return blocked;
  const courseId = Number(courseIdFor(course.id));
  const quizId = quizIdOf(ctx.params && ctx.params.get("n"));
  if (!quizId) return <div className="stack">{backendPanel()}<QuizList course={course} courseId={courseId} target="pf_exam_grade" /></div>;
  const attemptId = Number((ctx.params && ctx.params.get("attempt")) || "");
  if (!Number.isInteger(attemptId) || attemptId < 1) {
    return (
      <div className="stack">
        {backendPanel()}
        <QuizHeader course={course} courseId={courseId} quizId={quizId} screen="pf_exam_grade" />
        <AttemptTable course={course} courseId={courseId} quizId={quizId} />
      </div>
    );
  }
  return <div className="stack">{backendPanel()}<AttemptView key={attemptId} course={course} courseId={courseId} quizId={quizId} attemptId={attemptId} /></div>;
}

/** 시험 정보와 수정·응시 목록·삭제. `screen`은 삭제한 뒤 돌아갈 목록 화면이다. */
function QuizHeader({ course, courseId, quizId, screen }: { course: Course; courseId: number; quizId: number; screen: QuizTarget }) {
  const key = quizKey(courseId, quizId);
  const load = () => { void quizDetails.load(key, courseId, quizId); };
  const entry = useResource(quizDetails, key, load);
  useVisit(quizDetails, "pf-quiz-head", key, load);
  const data = entry && entry.data;
  let body: ReactNode;
  if (!data || data.revision == null) {
    if (!entry || !entry.error) body = <p className="pf-sub">시험을 불러오는 중입니다.</p>;
    else if (missing(entry.error)) {
      body = <EmptyState title="시험을 찾을 수 없습니다" body="이 과목의 시험이 아니거나 삭제되었습니다."
        actions={<Link href={pf("pf_exam_create", { kj: course.id })} className="btn btn-secondary">시험 목록</Link>} />;
    } else body = <Failure error={entry.error} reload={load} />;
  } else {
    const info = data.quiz;
    const offline = info.examType === "offline";
    body = <>
      <h2>{info.title}</h2>
      <p className="pf-sub">{`${info.weekNo}주 · ${offline ? "오프라인" : "온라인"} · ${periodText(info)} · ${PHASE[data.phase || ""] || data.phase} · 배점 ${scoreText(info.maxScore)}점${offline ? "" : ` · 문항 ${info.questionCount}개`}`}</p>
      {offline
        ? <p className="pf-sub">{`결과 공개 ${info.resultReleaseAt ? `${koDateTime(info.resultReleaseAt)} 이후` : "비공개"} · 수정 번호 ${data.revision}`}</p>
        : <p className="pf-sub">{`응시 중 ${data.inProgressCount}명 · 종료 ${data.finishedCount}건 · 채점 대기 ${data.awaitingGradingCount}건 · 결과 공개 ${info.resultReleaseAt ? `${koDateTime(info.resultReleaseAt)} 이후` : "비공개"}${data.questionsLocked ? " · 문항 잠김" : ""}`}</p>}
      <div className="pf-actions">
        <Link href={pf("pf_exam_create", { kj: course.id, edit: String(quizId) })} className="btn btn-ghost">시험 수정</Link>
        {offline
          ? <Link href={pf("pf_quiz", { kj: course.id, n: quizRef(quizId) })} className="btn btn-ghost">점수 입력</Link>
          : <Link href={pf("pf_exam_status", { kj: course.id, n: quizRef(quizId) })} className="btn btn-ghost">응시 목록</Link>}
      </div>
      {deleteControl("quiz", courseId, quizId, { title: info.title, back: pf(screen, { kj: course.id }) })}
    </>;
  }
  return <section className="sheet" aria-label="시험 정보"><div className="stack">{body}</div></section>;
}

function AttemptTable({ course, courseId, quizId }: { course: Course; courseId: number; quizId: number }) {
  const key = quizKey(courseId, quizId);
  const load = () => { void attemptPages.load(key, courseId, quizId, null); };
  const entry = useResource(attemptPages, key, load);
  useVisit(attemptPages, "pf-quiz-attempts", key, load);
  const data = entry && entry.data;
  let body: ReactNode;
  if (!data) {
    if (!entry || !entry.error) body = <p className="pf-sub">응시 목록을 불러오는 중입니다.</p>;
    else if (missing(entry.error)) body = <EmptyState title="응시 목록을 볼 수 없습니다" body="이 과목의 시험이 아니거나 삭제되었습니다." />;
    else body = <Failure error={entry.error} reload={load} />;
  } else if (!data.items.length) body = <p className="pf-sub">아직 응시한 학생이 없습니다.</p>;
  else {
    const loading = Boolean(entry.loading);
    const items: AttemptRow[] = data.items;
    body = <>
      {entry.error ? <p className="err" role="alert">{quizMessage(entry.error)}</p> : null}
      <Table headers={["학생", "회차", "상태", "시작", "접수", "총점", ""]} rows={items.map((item) => {
        const href = pf("pf_exam_grade", { kj: course.id, n: quizRef(quizId), attempt: String(item.attemptId) });
        return [
          item.studentName || "이름 없음",
          `${item.attemptNo}회`,
          <Badge tone={item.status === "graded" ? "success" : "neutral"}>{item.pendingCount ? `채점 대기 ${item.pendingCount}` : ATTEMPT_STATUS[item.status || ""] || item.status}</Badge>,
          koDateTime(item.startedAt),
          item.submittedAt ? `${koDateTime(item.submittedAt)} · ${FINISH[item.finishReason || ""] || ""}` : `마감 ${koDateTime(item.deadlineAt)}`,
          item.totalScore != null ? `${scoreText(item.totalScore)}점` : "—",
          <Link href={href} className="btn btn-secondary" aria-label={`${item.studentName || "학생"} ${item.attemptNo}회차 보기`}>{item.status === "in_progress" ? "답안 보기" : "보기·채점"}</Link>,
        ];
      })} />
      <p className="pf-sub">{`${data.items.length}건 표시${data.nextCursor ? " · 더 있습니다" : ""}`}</p>
      {data.nextCursor
        ? <div className="pf-actions">
            {/* `pf-quiz-more`: 응시 목록의 다음 쪽. */}
            <Button kind="secondary" disabled={loading} aria-busy={loading ? "true" : undefined} onClick={() => void loadMoreAttempts(courseId, quizId)}>
              {loading ? "불러오는 중…" : "더 보기"}
            </Button>
          </div>
        : null}
    </>;
  }
  return <section className="sheet" aria-label="응시 목록"><h2>응시 목록</h2><div className="stack">{body}</div></section>;
}

const GRADING: Record<string, string> = { auto: "자동 채점", pending: "채점 대기", manual: "직접 채점" };

function AttemptView({ course, courseId, quizId, attemptId }: { course: Course; courseId: number; quizId: number; attemptId: number }) {
  const key = attemptKey(courseId, quizId, attemptId);
  const load = () => { void attemptDetails.load(key, courseId, quizId, attemptId); };
  const entry = useResource(attemptDetails, key, load);
  useVisit(attemptDetails, "pf-quiz-attempt", key, load);
  const typed = useRef(new Map<number, { score: string; feedback: string }>()); // questionId -> 입력 중인 값
  const data = entry && entry.data;
  let body: ReactNode;
  if (!data || data.answers == null) {
    if (!entry || !entry.error) body = <p className="pf-sub">답안을 불러오는 중입니다.</p>;
    else if (missing(entry.error)) body = <EmptyState title="응시를 찾을 수 없습니다" body="이 시험의 응시가 아닙니다." />;
    else body = <Failure error={entry.error} reload={load} />;
  } else {
    const closed = data.status !== "in_progress";
    body = <>
      <h2>{`${data.studentName || "학생"} · ${data.attemptNo}회차 · ${ATTEMPT_STATUS[data.status || ""] || data.status}`}</h2>
      <p className="pf-sub">{`시작 ${koDateTime(data.startedAt)} · ${data.submittedAt ? `접수 ${koDateTime(data.submittedAt)} (${FINISH[data.finishReason || ""] || ""})` : `마감 ${koDateTime(data.deadlineAt)}`} · 총점 ${data.totalScore != null ? `${scoreText(data.totalScore)}점` : "확정 전"}`}</p>
      {closed ? null : <p className="pf-sub">아직 응시 중입니다. 제출하거나 마감된 뒤에 채점할 수 있습니다.</p>}
      {data.answers.map((answer, index) => (
        <AnswerCard key={answer.questionId ?? index} course={course} courseId={courseId} quizId={quizId} attemptId={attemptId} answer={answer} closed={closed} typed={typed.current} />
      ))}
      <div className="pf-actions">
        <Link href={pf("pf_exam_grade", { kj: course.id, n: quizRef(quizId) })} className="btn btn-ghost">응시 목록</Link>
        <Link href={pf("pf_quiz", { kj: course.id, n: quizRef(quizId) })} className="btn btn-ghost">시험 상세·재채점</Link>
      </div>
    </>;
  }
  return <section className="sheet" aria-label="응시 답안"><div className="stack">{body}</div></section>;
}

/** 답안 하나. 선택 문항은 서버가 준 `options`(출제 순서, 정답 여부, 학생 선택)를 그대로 그린다. */
function AnswerCard({ course, courseId, quizId, attemptId, answer, closed, typed }: {
  course: Course; courseId: number; quizId: number; attemptId: number; answer: QuizAnswer; closed: boolean; typed: Map<number, { score: string; feedback: string }>;
}) {
  const fix = <Link href={pf("pf_quiz", { kj: course.id, n: quizRef(quizId), q: String(answer.questionId) })} className="btn btn-ghost" aria-label={`${answer.number}번 정답·배점 고치기`}>정답·배점 고치기</Link>;
  const head = <>
    <div className="pf-inline">
      <Badge tone="neutral">{QUESTION_TYPE[answer.type || ""] || answer.type}</Badge>
      <Badge tone="neutral">{`${scoreText(answer.points)}점`}</Badge>
      {answer.gradingStatus ? <Badge tone={answer.gradingStatus === "pending" ? "danger" : "success"}>{GRADING[answer.gradingStatus] || answer.gradingStatus}</Badge> : null}
      {answer.awardedScore != null ? <Badge tone="neutral">{`받은 점수 ${scoreText(answer.awardedScore)}`}</Badge> : null}
    </div>
    <h3>{`${answer.number}번`}</h3>
    <Prose text={answer.text} />
  </>;
  if (answer.type !== "short") {
    const options: GradingOption[] = Array.isArray(answer.options) ? answer.options : [];
    return (
      <article className="sheet pf-q">
        {head}
        {options.length
          ? <ul className="pf-grading-options">{options.map((o) => (
            <li data-option={String(o.optionId)} key={o.optionId}>
              <span>{`${o.number}. ${o.text}`}</span>
              {o.selected ? <Badge tone="neutral">학생 선택</Badge> : null}
              {o.correct ? <Badge tone="success">정답</Badge> : null}
            </li>
          ))}</ul>
          : null}
        <p className="pf-sub">{options.some((o) => o.selected) ? `${answer.correct == null ? "채점 전" : answer.correct ? "정답" : "오답"}` : "답하지 않았습니다."}</p>
        <div className="pf-actions">{fix}</div>
      </article>
    );
  }
  return (
    <article className="sheet pf-q">
      {head}
      <div><span className="pf-meta">학생 답</span>{answer.answerText ? <Prose text={answer.answerText} /> : <p className="pf-sub">답하지 않았습니다.</p>}</div>
      {closed && answer.questionId != null
        ? <ShortGrade courseId={courseId} quizId={quizId} attemptId={attemptId} questionId={answer.questionId} answer={answer} typed={typed} fix={fix} />
        : null}
    </article>
  );
}

function ShortGrade({ courseId, quizId, attemptId, questionId, answer, typed, fix }: {
  courseId: number; quizId: number; attemptId: number; questionId: number; answer: QuizAnswer; typed: Map<number, { score: string; feedback: string }>; fix: ReactNode;
}) {
  let memo = typed.get(questionId);
  if (!memo) {
    memo = { score: answer.awardedScore != null ? scoreText(answer.awardedScore) : "", feedback: answer.feedback || "" };
    typed.set(questionId, memo);
  }
  const values = memo;
  const [, setTick] = useState(0);
  const [status, setStatus] = useState<Note>({ text: answer.gradedAt ? `${koDateTime(answer.gradedAt)}에 채점했습니다.` : "", alert: false });
  const [saving, setSaving] = useState(false);
  const mounted = useMounted();
  const max = scoreText(answer.points);

  /** `pf-quiz-grade`: 문항 배점 안의 점수와 일반 텍스트 의견. 응답의 응시 상세로 화면을 바꾼다. */
  const save = async () => {
    if (saving) return;
    const raw = values.score.trim();
    const limit = Number(max);
    const score = Number(raw);
    if (raw === "" || !Number.isFinite(score) || score < 0 || !(score <= limit) || !hundredths(score)) {
      return setStatus({ text: `점수는 0부터 ${scoreText(limit)}까지, 소수 둘째 자리까지 입력하세요.`, alert: true });
    }
    if (values.feedback.length > 1000) return setStatus({ text: "의견은 1,000자 이하입니다.", alert: true });
    setSaving(true);
    setStatus({ text: "점수를 저장하고 있습니다.", alert: false });
    try {
      const detail = await gradeAnswer(courseId, quizId, attemptId, questionId, { score, feedback: values.feedback.trim() ? values.feedback : null });
      toastNow(detail.status === "graded" ? `점수를 저장했습니다. 총점 ${scoreText(detail.totalScore)}점으로 확정했습니다` : "점수를 저장했습니다");
    } catch (error) {
      if (mounted.current) setStatus({ text: quizMessage(error), alert: true });
    } finally {
      if (mounted.current) setSaving(false);
    }
  };

  return (
    <div className="pf-form">
      <Field label={`점수 (0~${max})`}>
        <input type="number" min="0" max={max} step="0.01" value={values.score} aria-label={`${answer.number}번 점수 (0~${max})`} disabled={saving}
          onChange={(event) => { values.score = event.currentTarget.value; setTick((n) => n + 1); }} />
      </Field>
      <div className="span-2"><Field label="의견 (일반 텍스트, 1,000자까지)">
        <textarea rows={3} maxLength={1000} aria-label={`${answer.number}번 의견`} value={values.feedback} disabled={saving}
          onChange={(event) => { values.feedback = event.currentTarget.value; setTick((n) => n + 1); }} />
      </Field></div>
      <p className="span-2 pf-sub" role={status.alert ? "alert" : "status"} aria-live="polite">{status.text}</p>
      <div className="span-2 pf-actions">
        <Button kind="primary" disabled={saving} onClick={() => void save()}>{saving ? "저장하는 중…" : "점수 저장"}</Button>
        {fix}
      </div>
    </div>
  );
}

// ---------- 시험 상세 (`pf_quiz`) ----------

/**
 * `pf_quiz`(백엔드 모드). `?n=srv-<quizId>`면 그 시험의 정보와 문항(정답 표시, 문항마다 정답·배점 정정과 재채점), 없으면
 * 시험 목록. `?q=<questionId>`면 그 문항의 정정 폼을 펼친다. 오프라인 시험은 문항 대신 점수 표(`?page=`)를 보인다.
 */
export function serverQuizDetail(ctx: ScreenCtx, course: Course): ReactNode {
  const blocked = gate(course);
  if (blocked) return blocked;
  const courseId = Number(courseIdFor(course.id));
  const quizId = quizIdOf(ctx.params && ctx.params.get("n"));
  if (!quizId) return <div className="stack">{backendPanel()}<QuizList course={course} courseId={courseId} target="pf_quiz" /></div>;
  const focus = Number((ctx.params && ctx.params.get("q")) || "") || null;
  const page = Math.max(1, Math.floor(Number(ctx.params && ctx.params.get("page"))) || 1);
  return (
    <div className="stack">
      {backendPanel()}
      <QuizHeader course={course} courseId={courseId} quizId={quizId} screen="pf_quiz" />
      <QuestionSheet course={course} courseId={courseId} quizId={quizId} focus={focus} page={page} />
    </div>
  );
}

function QuestionSheet({ course, courseId, quizId, focus, page }: { course: Course; courseId: number; quizId: number; focus: number | null; page: number }) {
  const key = quizKey(courseId, quizId);
  const load = () => { void quizDetails.load(key, courseId, quizId); };
  const entry = useResource(quizDetails, key, load);
  useEffect(() => { if (!quizDetails.peek(key)) load(); }, [key]);
  const data = entry && entry.data;
  const hasQuestions = Boolean(data && data.revision != null && data.quiz.examType !== "offline");
  const scrolled = useRef(false);
  useEffect(() => {
    if (!focus || scrolled.current || !hasQuestions) return;
    scrolled.current = true;
    const target = document.getElementById(`pf-q-${focus}`);
    if (target) target.scrollIntoView({ block: "start" });
  });

  let heading = "문항";
  let label = "시험 문항";
  let body: ReactNode;
  if (!data || data.revision == null) {
    if (!entry || !entry.error) body = <p className="pf-sub">문항을 불러오는 중입니다.</p>;
    else if (missing(entry.error)) body = <p className="pf-sub">이 시험의 문항을 볼 수 없습니다.</p>;
    else body = <Failure error={entry.error} reload={load} />;
  } else if (data.quiz.examType === "offline") {
    heading = "점수 입력";
    label = "오프라인 시험 점수";
    body = <>
      <p className="pf-sub">{`오프라인 시험이라 서버에 문항이 없습니다. 학생별 점수(배점 ${scoreText(data.quiz.maxScore)}점)와 의견을 아래 표에 입력합니다.`}</p>
      {quizScoreSheet(course, courseId, quizId, page, (n: number) => pf("pf_quiz", { kj: course.id, n: quizRef(quizId), ...(n > 1 ? { page: String(n) } : {}) }))}
    </>;
  } else {
    const questions = Array.isArray(data.questions) ? data.questions : [];
    body = !questions.length
      ? <EmptyState title="문항이 없습니다" body="시험 수정에서 문항을 넣으세요."
          actions={<Link href={pf("pf_exam_create", { kj: course.id, edit: String(quizId) })} className="btn btn-secondary">시험 수정</Link>} />
      : <>
          {data.questionsLocked ? <p className="pf-sub">응시가 있어 지문과 보기는 잠겨 있습니다. 정답과 배점은 문항마다 고칠 수 있고, 고치면 제출을 마친 응시를 다시 채점합니다.</p> : null}
          {questions.map((q) => (
            <article className="sheet pf-q" id={`pf-q-${q.questionId}`} key={q.questionId}>
              <div className="pf-inline">
                <Badge tone="neutral">{`${q.number}번`}</Badge>
                <Badge tone="neutral">{QUESTION_TYPE[q.type] || q.type}</Badge>
                <Badge tone="neutral">{`${scoreText(q.points)}점`}</Badge>
              </div>
              <Prose text={q.text} />
              {Array.isArray(q.options) && q.options.length
                ? <ol>{q.options.map((option) => <li key={option.optionId}>{option.text}{option.correct ? " " : null}{option.correct ? <Badge tone="success">정답</Badge> : null}</li>)}</ol>
                : <p className="pf-sub">단답형 문항은 응시한 뒤 시험 채점에서 점수를 줍니다.</p>}
              {q.explanation ? <div><p className="pf-kicker">해설</p><Prose text={q.explanation} /></div> : null}
              <AnswerKeyForm courseId={courseId} quizId={quizId} q={q} open={focus === q.questionId} />
            </article>
          ))}
        </>;
  }
  return <section className="sheet" aria-label={label}><h2>{heading}</h2><div className="stack">{body}</div></section>;
}

// ---------- 정답·배점 정정(재채점) ----------

const keyOf = (courseId: number, quizId: number, questionId: number) => `${courseId}:${quizId}:${questionId}`;

/** 문항 하나의 정답·배점 정정 폼. 입력 중인 값과 안내는 다시 그려도 남고, 저장에 성공하면 서버 값으로 돌아간다. */
function AnswerKeyForm({ courseId, quizId, q, open }: { courseId: number; quizId: number; q: QuizQuestion; open: boolean }) {
  const key = keyOf(courseId, quizId, q.questionId);
  const options = Array.isArray(q.options) ? q.options : [];
  const memo = keyDrafts.get(key) || { points: scoreText(q.points), correct: new Set(options.filter((o) => o.correct).map((o) => o.optionId)) };
  const touch = () => keyDrafts.set(key, memo);
  const [, setTick] = useState(0);
  const repaint = () => setTick((n) => n + 1);
  const [working, setWorking] = useState(false);
  const mounted = useMounted();
  const said = keyNotes.get(key);
  const sayKey = (text: string, alert: boolean) => {
    if (text) keyNotes.set(key, { text, alert });
    else keyNotes.delete(key);
    if (mounted.current) repaint();
  };

  /**
   * `pf-quiz-regrade`: 읽은 `revision`으로 문항 하나의 정답과 배점을 고친다(`PUT …/answer-key`). 응답의 `quiz`가 화면을 바꾸고
   * `rescoredAttempts`를 알린다. 409 `edit_conflict`면 최신 상세를 받고 입력은 그대로 둔다.
   */
  const regrade = async () => {
    if (working) return;
    const entry = quizDetails.peek(quizKey(courseId, quizId));
    const detail: QuizDetail | null = entry && entry.data;
    const revision = detail ? detail.revision : null;
    if (revision == null) return sayKey("시험 정보를 아직 불러오지 못했습니다. 잠시 후 다시 누르세요.", true);
    const points = Number(memo.points);
    if (!Number.isFinite(points) || points <= 0 || points > 1000 || !hundredths(points)) return sayKey("배점은 0보다 크고 1000 이하, 소수 둘째 자리까지입니다.", true);
    let correctOptionIds: number[] | undefined;
    if (q.type !== "short") {
      correctOptionIds = options.filter((o) => memo.correct.has(o.optionId)).map((o) => o.optionId);
      if (q.type === "single" ? correctOptionIds.length !== 1 : correctOptionIds.length < 1) {
        return sayKey(q.type === "single" ? "정답 보기를 하나 고르세요." : "정답 보기를 하나 이상 고르세요.", true);
      }
    }
    setWorking(true);
    sayKey("정답과 배점을 저장하고 응시를 다시 채점하고 있습니다.", false);
    try {
      const result = await regradeQuestion(courseId, quizId, q.questionId, { revision, points, correctOptionIds });
      keyDrafts.delete(key);
      const count = Number(result && result.rescoredAttempts) || 0;
      const text = count ? `정답과 배점을 저장했습니다. 응시 ${count}건을 다시 채점했습니다.` : "정답과 배점을 저장했습니다. 다시 채점할 제출된 응시는 없었습니다.";
      sayKey(text, false);
      toastNow(text);
    } catch (error) {
      if (isConflict(error, "edit_conflict")) {
        sayKey("다른 곳에서 이 시험을 먼저 수정했습니다. 최신 내용을 불러왔습니다. 입력한 정답과 배점은 그대로 두었으니 확인한 뒤 다시 저장하세요.", true);
        await quizDetails.load(quizKey(courseId, quizId), courseId, quizId);
      } else sayKey(quizMessage(error), true);
    } finally {
      if (mounted.current) setWorking(false);
    }
  };

  return (
    <details className="pf-regrade" open={open || Boolean(said) || openForms.has(key)} onToggle={(event) => {
      if (event.currentTarget.open) openForms.add(key);
      else openForms.delete(key);
    }}>
      <summary>정답·배점 고치기 (재채점)</summary>
      <div className="pf-form">
        <p className="span-2 pf-sub">지문과 보기는 바꾸지 않습니다. 저장하면 제출을 마친 응시를 모두 새 정답과 배점으로 다시 채점하고 총점과 성적부를 고칩니다. 진행 중인 응시는 제출할 때 새 정답으로 채점합니다.</p>
        <Field label="배점">
          <input type="number" min="0.01" max="1000" step="0.01" value={memo.points} aria-label={`${q.number}번 배점`} disabled={working}
            onChange={(event) => { memo.points = event.currentTarget.value; touch(); repaint(); }} />
        </Field>
        {q.type !== "short"
          ? <fieldset className="span-2">
              <legend>{q.type === "single" ? "정답 보기 (하나)" : "정답 보기 (하나 이상)"}</legend>
              <div className="stack">
                {options.map((o, at) => (
                  <label className="choice" key={o.optionId}>
                    <input type={q.type === "single" ? "radio" : "checkbox"} name={`pf-key-${quizId}-${q.questionId}`} data-option={String(o.optionId)}
                      checked={memo.correct.has(o.optionId)} disabled={working} onChange={(event) => {
                        if (q.type === "single") memo.correct = new Set([o.optionId]);
                        else if (event.currentTarget.checked) memo.correct.add(o.optionId);
                        else memo.correct.delete(o.optionId);
                        touch();
                        repaint();
                      }} />
                    <span>{`${at + 1}. ${o.text}`}</span>
                  </label>
                ))}
              </div>
            </fieldset>
          : <p className="span-2 pf-sub">단답형은 배점만 바꿉니다. 이미 준 점수보다 낮출 수 없습니다.</p>}
        <p className={said && said.alert ? "err span-2" : "pf-sub span-2"} role={said && said.alert ? "alert" : "status"} aria-live="polite">{said ? said.text : ""}</p>
        <div className="span-2 pf-actions">
          <Button kind="primary" disabled={working} aria-busy={working ? "true" : undefined} onClick={() => void regrade()}>
            {working ? "다시 채점하는 중…" : "정답·배점 저장하고 다시 채점"}
          </Button>
        </div>
      </div>
    </details>
  );
}
