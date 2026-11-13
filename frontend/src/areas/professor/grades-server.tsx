import { useEffect, useRef, useState, useSyncExternalStore, type ReactNode } from "react";
import { ApiError } from "../../shared/api.ts";
import { backendState, backendStatus, courseIdFor, roleIn, subscribe } from "../../shared/backend.ts";
import { backendPanel } from "../../shared/backend-prompt.tsx";
import { freshVisit, useResource, type Entry } from "../../shared/remote-cache.ts";
import { koDateTime } from "../../shared/home-remote.ts";
import { routeFor } from "../../shared/routes.ts";
import { serverRef } from "../../shared/assignments-remote.ts";
import { quizRef } from "../../shared/quizzes-remote.ts";
import { Button, Field, Badge, EmptyState, LoadingState, ErrorState, Pagination } from "../../ui/kit.tsx";
import { Link } from "../../router.tsx";
import { toast } from "../../store.ts";
import type { Course } from "../../data.ts";
import type { ScreenCtx } from "../../types.ts";
import {
  GRADEBOOK_PAGE, TYPE_LABEL, gradebooks, gradebookKey, loadGradebook, updateGradeSettings,
  createGradeItem, updateGradeItem, releaseGradeItem, deleteGradeItem, saveScores, saveQuizScores,
  rejectedRows, rowCodeText, isConflict, points, totalText, gradeMessage, gradeItemSheets,
  itemSheetKey, loadItemSheet, quizGradeItems, quizItemKey,
  type Gradebook, type GradeItem, type ItemSheet, type ScoreRow, type GradeTotal,
} from "../../shared/grades-remote.ts";

type Id = number | string;
interface Notice { text: string; alert: boolean }
interface ItemValues { name: string; maxScore: string; weight: string }
interface ItemDraft {
  values: ItemValues;
  version: number | null;
  note?: Notice;
  conflict?: boolean;
  open: boolean;
}
interface ScoreDraft { score: string; feedback: string }
const drafts = new Map<string, ScoreDraft>();
const rowErrors = new Map<string, string>();
const notices = new Map<string, Notice>();
const itemDrafts = new Map<string, ItemDraft>();
const listeners = new Set<() => void>();
let changes = 0;
let generation = 0;
const sessionOf = () => {
  const state = backendState();
  return `${state.status}:${state.me?.userId ?? ""}`;
};
let session = sessionOf();
function notify(): void {
  changes += 1;
  for (const listener of listeners) listener();
}
subscribe(() => {
  const next = sessionOf();
  if (next === session) return;
  session = next;
  generation += 1;
  drafts.clear();
  rowErrors.clear();
  notices.clear();
  itemDrafts.clear();
  notify();
});
const watchDrafts = (listener: () => void) => {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
};
const draftSnapshot = () => changes;
function useDrafts(): void { useSyncExternalStore(watchDrafts, draftSnapshot); }
const pf = (screenId: string, extra?: Record<string, string>): string => routeFor("professor", "screen", { screenId, ...extra });
const missing = (error: unknown): boolean => error instanceof ApiError && error.status === 404;
const itemDraftKey = (courseId: Id, itemId?: number): string => `${courseId}:${itemId ?? "new"}`;
const cellKey = (courseId: Id, itemId: number, studentId: number): string => `${courseId}:${itemId}:${studentId}`;
const pageOf = (ctx: ScreenCtx): number => {
  const n = Number(ctx.params.get("page"));
  return Number.isFinite(n) ? Math.max(1, Math.floor(n) || 1) : 1;
};
const itemOf = (ctx: ScreenCtx): number | null => Number(ctx.params.get("item")) || null;
function decimal(value: string): number | null {
  const text = value.trim();
  return !text ? null : /^\d+(\.\d{1,2})?$/.test(text) ? Number(text) : Number.NaN;
}
const Stack = ({ children }: { children: ReactNode }) => <div className="stack">{children}</div>;
const Note = ({ children, alert = false }: { children: ReactNode; alert?: boolean }) => (
  <p className={alert ? "err" : "pf-sub"} role={alert ? "alert" : "status"} aria-live="polite">{children}</p>
);
const Retry = ({ reload }: { reload: () => Promise<void> }) => {
  const [busy, setBusy] = useState(false);
  return <Button kind="ghost" disabled={busy} onClick={async () => {
    setBusy(true);
    try { await reload(); } finally { setBusy(false); }
  }}>다시 불러오기</Button>;
};
function gate(course: Course): ReactNode {
  if (backendStatus() === "anon") return <Stack>{backendPanel({ text: "ASTRA 서버에 로그인하면 이 과목의 성적부를 서버에서 관리할 수 있습니다." })}</Stack>;
  if (courseIdFor(course.id) == null) return <Stack>{backendPanel()}<EmptyState title="이 과목은 ASTRA 서버에 없습니다" body="서버에 등록된 과목만 성적부를 관리할 수 있습니다." /></Stack>;
  if (roleIn(course.id) !== "INSTRUCTOR") return <Stack>{backendPanel()}<Note>이 과목을 담당하는 교수 계정으로 로그인해야 성적부를 관리할 수 있습니다.</Note></Stack>;
  return null;
}

function Settings({ courseId, data, reload }: { courseId: Id; data: Gradebook; reload: () => Promise<void> }) {
  const settings = data.settings || { activitiesVisible: true, revision: 0 };
  const on = Boolean(settings.activitiesVisible);
  const [busy, setBusy] = useState(false);
  const pending = useRef(false);
  const change = async () => {
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    const gen = generation;
    try {
      await updateGradeSettings(courseId, { activitiesVisible: !on, revision: settings.revision });
      if (gen !== generation) return;
      toast(on ? "학생 화면에서 평가 활동을 숨겼습니다" : "학생에게 평가 활동을 공개했습니다");
    } catch (error) {
      if (gen !== generation) return;
      toast(gradeMessage(error));
      if (isConflict(error)) await reload();
    } finally { pending.current = false; setBusy(false); }
  };
  return <section className="sheet" aria-label="성적 공개 설정">
    <h2>학생 화면</h2>
    <p className="pf-sub"><b>{on ? "평가 활동 공개 중" : "평가 활동 비공개"}</b> · {on
      ? "학생은 성적 화면에서 항목과 공개한 점수, 공개한 점수의 합계를 봅니다."
      : "학생 성적 화면에는 출석만 보이고 평가 활동 목록과 합계가 나오지 않습니다."}</p>
    <div className="pf-actions"><Button kind={on ? "secondary" : "primary"} disabled={busy} aria-busy={busy} onClick={change}>{busy ? "바꾸는 중…" : on ? "평가 활동 숨기기" : "평가 활동 공개하기"}</Button></div>
  </section>;
}

function initialValues(item: GradeItem | null): ItemValues {
  return { name: item?.name ?? "", maxScore: item ? points(item.maxScore) : "", weight: points(item?.weight) };
}
function ItemForm({ courseId, item, page, open = true }: { courseId: Id; item: GradeItem | null; page: number; open?: boolean }) {
  useDrafts();
  const key = itemDraftKey(courseId, item?.itemId);
  const draft = itemDrafts.get(key);
  const values = draft?.values ?? initialValues(item);
  const managed = Boolean(item?.managedByAssignment || item?.managedByQuiz);
  const [busy, setBusy] = useState(false);
  const pending = useRef(false);
  const remember = (next: ItemValues): ItemDraft => {
    const current = itemDrafts.get(key) ?? { values: next, version: item?.revision ?? null, open };
    current.values = next;
    itemDrafts.set(key, current);
    notify();
    return current;
  };
  const submit = async () => {
    if (pending.current) return;
    const current = remember(values);
    const tell = (text: string) => { current.note = { text, alert: true }; notify(); };
    const name = values.name.trim();
    const maxScore = decimal(values.maxScore);
    const weight = decimal(values.weight);
    const errors: string[] = [];
    if (!managed) {
      if (!name || name.length > 200) errors.push("이름은 1자 이상 200자 이하입니다");
      if (maxScore == null || !Number.isFinite(maxScore) || maxScore <= 0 || maxScore > 1000) errors.push("배점은 0보다 크고 1000 이하, 소수 둘째 자리까지입니다");
    }
    if (weight != null && (!Number.isFinite(weight) || weight > 100)) errors.push("반영 비율은 0부터 100까지, 소수 둘째 자리까지입니다");
    if (errors.length) { tell(errors.join(". ")); return; }
    pending.current = true;
    setBusy(true);
    const gen = generation;
    try {
      if (item) await updateGradeItem(courseId, item, { name, maxScore: maxScore ?? undefined, weight, revision: current.version ?? item.revision });
      else await createGradeItem(courseId, { name, maxScore: maxScore ?? undefined, weight });
      if (gen !== generation) return;
      if (itemDrafts.get(key) === current) itemDrafts.delete(key);
      toast(item ? "항목을 수정했습니다" : "항목을 만들었습니다. 점수를 공개하기 전에는 학생에게 점수가 보이지 않습니다");
      notify();
    } catch (error) {
      if (gen !== generation) return;
      const stale = Boolean(item) && isConflict(error, "edit_conflict");
      if (stale) current.conflict = true;
      if (item && missing(error)) itemDrafts.delete(key);
      tell(gradeMessage(error));
      if (stale || missing(error)) {
        await loadGradebook(courseId, page);
        if (gen !== generation) return;
        const entry = gradebooks.peek(gradebookKey(courseId, page));
        const fresh = stale && !entry?.error ? entry?.data?.items.find((candidate) => candidate.itemId === item?.itemId) : undefined;
        if (fresh && itemDrafts.get(key) === current) current.version = fresh.revision;
        notify();
      }
    } finally { pending.current = false; setBusy(false); }
  };
  const set = (name: keyof ItemValues, value: string) => { remember({ ...values, [name]: value }); };
  return <div className="pf-form pf-grade-item-form" aria-busy={busy}>
    <Field label="항목 이름"><input maxLength={200} disabled={managed || busy} value={values.name} onChange={(e) => set("name", e.target.value)} /></Field>
    <Field label="배점"><input type="text" inputMode="decimal" disabled={managed || busy} value={values.maxScore} onChange={(e) => set("maxScore", e.target.value)} /></Field>
    <Field label="반영 비율 (%, 비우면 없음)"><input type="text" inputMode="decimal" disabled={busy} value={values.weight} onChange={(e) => set("weight", e.target.value)} /></Field>
    <div className="pf-actions span-2"><Button kind="primary" disabled={busy} onClick={submit}>{busy ? "저장하는 중…" : item ? "저장" : "항목 만들기"}</Button></div>
    {draft?.note ? <div className="span-2"><Note alert={draft.note.alert}>{draft.note.text}</Note></div> : null}
    {draft?.conflict && item ? <p className="pf-sub span-2">서버의 최신 값 · 수정 번호 {item.revision}: {item.name} · 배점 {points(item.maxScore)}점 · {item.weight != null ? `반영 ${points(item.weight)}%` : "반영 비율 없음"}</p> : null}
  </div>;
}

function Item({ courseId, kj, item, selected, page, reload }: {
  courseId: Id; kj: string; item: GradeItem; selected: boolean; page: number; reload: () => Promise<void>;
}) {
  useDrafts();
  const key = itemDraftKey(courseId, item.itemId);
  const [open, setOpen] = useState(itemDrafts.get(key)?.open ?? false);
  const [armed, setArmed] = useState(false);
  const [busy, setBusy] = useState<"release" | "delete" | null>(null);
  const pending = useRef(false);
  const act = async (action: "release" | "delete") => {
    if (pending.current) return;
    if (action === "delete" && !armed) { setArmed(true); return; }
    setArmed(false);
    pending.current = true;
    setBusy(action);
    const gen = generation;
    try {
      if (action === "release") await releaseGradeItem(courseId, item.itemId, !item.released, item.revision);
      else await deleteGradeItem(courseId, item.itemId, item.revision);
      if (gen !== generation) return;
      if (action === "delete") { itemDrafts.delete(key); notify(); }
      toast(action === "delete" ? "항목을 삭제했습니다" : item.released ? "점수 공개를 거뒀습니다" : "점수를 학생에게 공개했습니다");
    } catch (error) {
      if (gen !== generation) return;
      toast(gradeMessage(error));
      if (isConflict(error) || missing(error)) await reload();
    } finally { pending.current = false; setBusy(null); }
  };
  const meta = [TYPE_LABEL[item.type] || item.type, `배점 ${points(item.maxScore)}점`,
    item.weight != null ? `반영 ${points(item.weight)}%` : "반영 비율 없음", `채점 ${item.gradedCount ?? 0}명`,
    item.released && item.releaseAt ? `공개 ${koDateTime(item.releaseAt)}` : "", `수정 번호 ${item.revision}`].filter(Boolean).join(" · ");
  return <article className="pf-grade-item">
    <div className="pf-inline"><b>{item.name}</b><Badge tone={item.released ? "success" : "neutral"}>{item.released ? "공개" : "비공개"}</Badge></div>
    <p className="pf-meta">{meta}</p>
    <details open={open} onToggle={(e) => {
      const next = e.currentTarget.open;
      setOpen(next);
      const current = itemDrafts.get(key);
      if (current) current.open = next;
    }}>
      <summary>{item.managedByAssignment || item.managedByQuiz ? "반영 비율 고치기" : "이름·배점·비율 고치기"}</summary>
      {item.managedByAssignment ? <Note>과제 항목의 이름, 배점, 점수 공개, 점수는 과제 화면에서 바꿉니다. 여기서는 반영 비율만 정합니다.</Note> : null}
      {item.managedByQuiz ? <Note>시험 항목의 이름과 배점은 시험 화면에서, 점수 공개는 시험의 결과 공개 일시로 정합니다. 오프라인 시험의 점수는 시험 상세 화면에서 입력합니다. 여기서는 반영 비율만 정합니다.</Note> : null}
      <ItemForm courseId={courseId} item={item} page={page} open={open} />
    </details>
    <div className="pf-actions">
      <Link href={pf("pf_gradebook", { kj, item: String(item.itemId), ...(page > 1 ? { page: String(page) } : {}) })} className={`btn ${selected ? "btn-secondary" : "btn-ghost"}`} aria-current={selected ? "true" : undefined}>{selected ? "점수 표 보는 중" : "점수 표"}</Link>
      {item.managedByAssignment && item.assignmentId != null ? <Link href={pf("pf_report_status", { kj, n: serverRef(item.assignmentId) })} className="btn btn-ghost">과제 화면에서 공개·채점</Link>
        : item.managedByQuiz && item.quizId != null ? <Link href={pf("pf_quiz", { kj, n: quizRef(item.quizId) })} className="btn btn-ghost">시험 화면에서 점수·공개</Link>
          : !item.managedByAssignment && !item.managedByQuiz ? <>
            <Button kind={item.released ? "secondary" : "primary"} disabled={busy !== null} onClick={() => act("release")}>{busy === "release" ? "바꾸는 중…" : item.released ? "점수 거두기" : "점수 공개"}</Button>
            <Button kind="ghost" disabled={busy !== null} onClick={() => act("delete")}>{busy === "delete" ? "삭제하는 중…" : armed ? "한 번 더 누르면 삭제" : "삭제"}</Button>
          </> : null}
    </div>
  </article>;
}
function Items({ courseId, kj, data, selectedId, page, reload }: {
  courseId: Id; kj: string; data: Gradebook; selectedId: number | null; page: number; reload: () => Promise<void>;
}) {
  const weights = data.items.reduce((sum, item) => sum + Number(item.weight ?? 0), 0);
  return <section className="sheet" aria-label="성적 항목">
    <div className="pf-week-head"><h2>성적 항목</h2><Badge tone={Math.abs(weights - 100) < 1e-9 ? "success" : "neutral"}>비율 합 {points(weights)}%</Badge></div>
    <Note>직접 입력 항목은 비공개로 만들어집니다. 점수를 공개하기 전에는 학생 응답에 점수가 들어가지 않습니다. 비율 합은 서버가 검사하지 않습니다.</Note>
    {data.items.length ? <Stack>{data.items.map((item) => <Item key={item.itemId} courseId={courseId} kj={kj} item={item} selected={item.itemId === selectedId} page={page} reload={reload} />)}</Stack>
      : <Note>아직 성적 항목이 없습니다. 과제를 내면 과제 항목이 생기고, 아래에서 직접 입력 항목을 만들 수 있습니다.</Note>}
    <h3>직접 입력 항목 만들기</h3><ItemForm courseId={courseId} item={null} page={page} />
  </section>;
}

interface StudentRow {
  studentId: number; name: string; total?: GradeTotal | null;
  cell: { score?: number | null; feedback?: string | null; version: number } | null;
}
interface ScoresProps {
  courseId: Id; kj: string; data: Gradebook | ItemSheet; item: GradeItem | null; page: number;
  editable: boolean; quiz?: boolean; hrefForPage?: (n: number) => string; reload: () => Promise<void>;
}
function Scores({ courseId, kj, data, item, page, editable, quiz, hrefForPage, reload }: ScoresProps) {
  useDrafts();
  const [busy, setBusy] = useState(false);
  const pending = useRef(false);
  const sheet = "item" in data;
  const students: StudentRow[] = "item" in data
    ? data.students.map((student) => ({ ...student, cell: student.version > 0 ? student : null }))
    : data.students.map((student) => ({ ...student, cell: student.scores?.find((score) => score.itemId === item?.itemId) ?? null }));
  const pages = Math.max(1, Math.ceil(data.totalStudents / (data.size || GRADEBOOK_PAGE)));
  const pager = <Pagination page={page} pages={pages} hrefForPage={hrefForPage ?? ((n) => pf("pf_gradebook", { kj, ...(item ? { item: String(item.itemId) } : {}), ...(n > 1 ? { page: String(n) } : {}) }))} />;
  const say = (text: string, alert: boolean) => { notices.set(String(courseId), { text, alert }); notify(); };
  const save = async () => {
    if (pending.current || !item || !editable) return;
    const rows: ScoreRow[] = [];
    const sent: { key: string; draft: ScoreDraft }[] = [];
    let local = 0;
    for (const student of students) {
      const key = cellKey(courseId, item.itemId, student.studentId);
      const draft = drafts.get(key);
      if (!draft) continue;
      const score = decimal(draft.score);
      const feedback = draft.feedback.trim() ? draft.feedback : null;
      rowErrors.delete(key);
      let code: string | undefined;
      if (score != null && !Number.isFinite(score)) code = "invalid_score";
      else if (score != null && score > item.maxScore) code = "score_exceeds_max";
      else if (score == null && feedback) code = "feedback_without_score";
      else if (feedback && feedback.length > 1000) code = "invalid_feedback";
      if (code) { rowErrors.set(key, code); local += 1; }
      rows.push({ studentId: student.studentId, score, feedback: score == null ? null : feedback, version: student.cell?.version ?? 0 });
      sent.push({ key, draft });
    }
    if (!rows.length) { say("바꾼 점수가 없습니다.", false); return; }
    if (local) { say(`틀린 행 ${local}개를 고친 뒤 저장하세요. 아직 아무것도 보내지 않았습니다.`, true); return; }
    if (item.managedByQuiz && item.quizId == null) { say("시험 항목을 찾지 못했습니다. 성적부를 새로 불러오세요.", true); await reload(); return; }
    pending.current = true;
    setBusy(true);
    const gen = generation;
    try {
      if (item.managedByQuiz && item.quizId != null) await saveQuizScores(courseId, item.quizId, rows);
      else await saveScores(courseId, item.itemId, rows);
      if (gen !== generation) return;
      for (const { key, draft } of sent) {
        if (drafts.get(key) === draft) drafts.delete(key);
        rowErrors.delete(key);
      }
      say(`${rows.length}명의 점수를 저장했습니다.`, false);
      toast("점수를 저장했습니다");
    } catch (error) {
      if (gen !== generation) return;
      const rejected = rejectedRows(error);
      for (const row of rejected) {
        const target = sent[row.index];
        if (target) rowErrors.set(target.key, row.code);
      }
      say(`${gradeMessage(error)}${rejected.length ? ` (틀린 행 ${rejected.length}개)` : ""}`, true);
      if (isConflict(error, "version_conflict") || missing(error)) await reload();
    } finally { pending.current = false; setBusy(false); }
  };
  const head = <div className="pf-week-head"><h2>{item ? `점수 · ${item.name}` : "학생별 합계"}</h2><span className="pf-meta">수강생 {data.totalStudents}명 · {page}/{pages}쪽</span></div>;
  if (!students.length) return <section className="sheet">{head}<Note>이 쪽에 수강생이 없습니다.</Note>{pager}</section>;
  const headers = !item ? ["이름", "합계"] : ["이름", editable ? `점수 (최대 ${points(item.maxScore)})` : "점수", "의견", ...(editable ? ["상태"] : []), ...(sheet ? [] : ["합계"])];
  const notice = notices.get(String(courseId));
  return <section className="sheet" aria-label="점수 표" aria-busy={busy}>
    {head}
    {!item ? <Note>위 항목의 "점수 표"를 누르면 그 항목의 점수를 입력합니다. 합계는 모든 항목의 받은 점수를 더한 서버 값입니다.</Note> : null}
    {item && !editable ? <Note>{item.managedByQuiz ? "시험 항목의 점수는 시험 채점(온라인) 또는 시험 상세의 점수 입력(오프라인)에서 줍니다. 여기서는 읽기만 합니다." : "과제 항목의 점수는 과제 채점 화면에서 줍니다. 여기서는 읽기만 합니다."}</Note> : null}
    {editable ? <Note>바꾼 행만 한 번에 저장합니다. 한 행이라도 틀리면 아무것도 저장하지 않고 틀린 행을 표시합니다. 점수를 비우면 점수와 의견을 지웁니다.</Note> : null}
    {quiz ? <Note>학생은 시험의 결과 공개 일시가 지난 뒤에 자기 점수만 봅니다.</Note> : null}
    <div className="table-wrap table-scroll pf-wide"><table className="grid" aria-label={item ? `${item.name} 점수` : "학생별 합계"}>
      <thead><tr>{headers.map((label, i) => <th key={label} scope="col" className={i === 0 ? "pf-lock" : undefined}>{label}</th>)}</tr></thead>
      <tbody>{students.map((student) => {
        const { cell } = student;
        const name = student.name || `학생 ${student.studentId}`;
        const total = student.total ? totalText(student.total) : "—";
        const nameCell = <td className="pf-lock" data-label="이름">{name}</td>;
        if (!item) return <tr key={student.studentId}>{nameCell}<td data-label="합계">{total}</td></tr>;
        if (!editable) return <tr key={student.studentId}>{nameCell}<td data-label="점수">{cell?.score != null ? `${points(cell.score)} / ${points(item.maxScore)}` : "-"}</td><td data-label="의견">{cell?.feedback || "—"}</td>{sheet ? null : <td data-label="합계">{total}</td>}</tr>;
        const key = cellKey(courseId, item.itemId, student.studentId);
        const draft = drafts.get(key);
        const code = rowErrors.get(key);
        const serverScore = cell?.score != null ? points(cell.score) : "";
        const serverFeedback = cell?.feedback || "";
        const value = draft ?? { score: serverScore, feedback: serverFeedback };
        const remember = (next: ScoreDraft) => {
          if (next.score.trim() === serverScore && next.feedback === serverFeedback) drafts.delete(key);
          else drafts.set(key, next);
          notify();
        };
        return <tr key={student.studentId} className={code ? "pf-row-error" : undefined}>
          {nameCell}
          <td data-label={`점수 (최대 ${points(item.maxScore)})`}><input type="text" inputMode="decimal" disabled={busy} value={value.score} aria-label={`${name} 점수`} aria-invalid={Boolean(code)} onChange={(e) => remember({ ...value, score: e.target.value })} /></td>
          <td data-label="의견"><input type="text" maxLength={1000} disabled={busy} value={value.feedback} aria-label={`${name} 의견`} onChange={(e) => remember({ ...value, feedback: e.target.value })} /></td>
          <td data-label="상태">{code ? <span className="err">{rowCodeText(code)}</span> : <span className="pf-meta">{draft ? "저장 전" : cell ? `버전 ${cell.version}` : "미채점"}</span>}
            {draft && code === "version_conflict" ? <span className="pf-meta"> 서버 값: {serverScore || "-"}{serverFeedback ? ` · ${serverFeedback}` : ""}</span> : null}
          </td>
          {sheet ? null : <td data-label="합계">{total}</td>}
        </tr>;
      })}</tbody>
    </table></div>
    {editable ? <div className="pf-actions"><Button kind="primary" disabled={busy} onClick={save}>{busy ? "저장하는 중…" : "바꾼 점수 저장"}</Button></div> : null}
    {notice ? <Note alert={notice.alert}>{notice.text}</Note> : null}{pager}
  </section>;
}

function ResourceFailure<T>({ entry, reload, itemSheet = false }: { entry: Entry<T> | null; reload: () => Promise<void>; itemSheet?: boolean }) {
  if (!entry?.error) return <LoadingState title="성적부를 불러오는 중입니다" />;
  if (missing(entry.error)) return <EmptyState title={itemSheet ? "성적부에 이 시험의 항목이 없습니다" : "성적부를 볼 수 없습니다"} body={itemSheet ? "시험이 삭제되었거나 이 과목의 담당 교수가 아닙니다. 시험 목록을 새로 불러오세요." : "이 과목의 담당 교수가 아니거나 과목을 찾을 수 없습니다."} />;
  return <ErrorState title="성적부를 불러오지 못했습니다" body={gradeMessage(entry.error)} actions={<Retry reload={reload} />} />;
}
function Body({ courseId, page, itemId, kj }: { courseId: Id; page: number; itemId: number | null; kj: string }) {
  const key = gradebookKey(courseId, page);
  const reload = () => loadGradebook(courseId, page);
  const entry = useResource(gradebooks, key, reload);
  useEffect(() => {
    if (freshVisit("pf-gradebook", key) === "entry" || !gradebooks.peek(key)) void loadGradebook(courseId, page);
  }, [courseId, page, key, entry, history.state]);
  if (!entry?.data) return <ResourceFailure entry={entry} reload={reload} />;
  const data = entry.data;
  const item = data.items.find((candidate) => candidate.itemId === itemId) ?? null;
  return <div className="stack" aria-live="polite">
    {entry.error ? <Note alert>{gradeMessage(entry.error)}</Note> : null}
    <Settings courseId={courseId} data={data} reload={reload} />
    <Items courseId={courseId} kj={kj} data={data} selectedId={item?.itemId ?? null} page={page} reload={reload} />
    {itemId && !item ? <Note>고른 항목이 없어졌습니다. 다른 항목을 고르세요.</Note> : null}
    <Scores courseId={courseId} kj={kj} data={data} item={item} page={page} editable={Boolean(item && !item.managedByAssignment && !item.managedByQuiz)} reload={reload} />
  </div>;
}
export function serverGradebook(ctx: ScreenCtx, course: Course): ReactNode {
  const blocked = gate(course);
  if (blocked) return blocked;
  const courseId = courseIdFor(course.id);
  if (courseId == null) return gate(course);
  return <Stack>{backendPanel()}<Body key={`${session}:${courseId}`} courseId={courseId} page={pageOf(ctx)} itemId={itemOf(ctx)} kj={course.id} /></Stack>;
}
function QuizBody({ course, courseId, quizId, page, hrefForPage }: {
  course: Course; courseId: number; quizId: number; page: number; hrefForPage: (n: number) => string;
}) {
  const key = quizItemKey(courseId, quizId);
  const entry = useResource(quizGradeItems, key);
  const find = () => quizGradeItems.load(key, courseId, quizId);
  useEffect(() => {
    const cached = quizGradeItems.peek(key);
    if (!cached || cached.error || cached.data?.itemId == null) void quizGradeItems.load(key, courseId, quizId);
  }, [courseId, quizId, key]);
  if (!entry?.data) return <ResourceFailure entry={entry} reload={find} />;
  if (entry.data.itemId == null) return <EmptyState title="성적부에 이 시험의 항목이 없습니다" body="시험이 삭제되었거나 아직 성적부에 반영되지 않았습니다. 잠시 후 다시 불러오세요." actions={<Retry reload={find} />} />;
  return <QuizSheet key={`${courseId}:${entry.data.itemId}`} course={course} courseId={courseId} quizId={quizId} itemId={entry.data.itemId} page={page} hrefForPage={hrefForPage} />;
}
function QuizSheet({ course, courseId, quizId, itemId, page, hrefForPage }: {
  course: Course; courseId: number; quizId: number; itemId: number; page: number; hrefForPage: (n: number) => string;
}) {
  const key = itemSheetKey(courseId, itemId, page);
  const reload = () => loadItemSheet(courseId, itemId, page);
  const entry = useResource(gradeItemSheets, key, reload);
  useEffect(() => {
    if (freshVisit(`pf-quiz-scores-${quizId}`, key) === "entry" || !gradeItemSheets.peek(key)) void loadItemSheet(courseId, itemId, page);
  }, [courseId, quizId, itemId, page, key, entry, history.state]);
  if (!entry?.data) return <ResourceFailure entry={entry} reload={reload} itemSheet />;
  return <div className="stack" aria-live="polite">
    {entry.error ? <Note alert>{gradeMessage(entry.error)}</Note> : null}
    <Scores courseId={courseId} kj={course.id} data={entry.data} item={entry.data.item} page={page} editable quiz hrefForPage={hrefForPage} reload={reload} />
  </div>;
}
export function quizScoreSheet(course: Course, courseId: number, quizId: number, page: number, hrefForPage: (n: number) => string): ReactNode {
  return <QuizBody key={`${session}:${courseId}:${quizId}`} course={course} courseId={courseId} quizId={quizId} page={page} hrefForPage={hrefForPage} />;
}

