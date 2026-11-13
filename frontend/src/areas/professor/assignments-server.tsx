// Professor assignment screens backed by the ASTRA backend (backend mode): 과제 출제 (create, edit
// with `revision`, list with submission counts), 제출 현황 (submission list, score release), and
// 과제 채점 (one submission with its versions and attachments, score and feedback).
import { useEffect, useRef, useState, type ReactNode } from "react";
import { ApiError } from "../../shared/api.ts";
import { backendStatus, courseIdFor, roleIn } from "../../shared/backend.ts";
import { backendPanel } from "../../shared/backend-prompt.tsx";
import { freshVisit, useResource, type Entry } from "../../shared/remote-cache.ts";
import { koDateTime } from "../../shared/home-remote.ts";
import { routeFor } from "../../shared/routes.ts";
import {
  assignmentLists, assignmentDetails, submissionLists, submissionDetails, listKey, assignmentKey, submissionKey,
  assignmentIdOf, serverRef, createAssignment, updateAssignment, gradeSubmission, releaseScores,
  assignmentMessage, isConflict, PHASE, STATUS, scoreText, receiptTime, periodText,
  type AssignmentInfo, type AssignmentPayload, type SubmissionFile,
} from "../../shared/assignments-remote.ts";
import { downloadControl, sizeText } from "../../shared/uploads-remote.tsx";
import { getState, render } from "../../store.ts";
import { Link, navigate } from "../../router.tsx";
import { Badge, Button, Field, EmptyState, Table, type ButtonKind } from "../../ui/kit.tsx";
import { Icon } from "../../ui/icon.tsx";
import type { Course } from "../../data.ts";
import type { ScreenCtx } from "../../types.ts";
import { deleteControl } from "./delete-server.tsx";

/** Layout helpers professor.tsx passes in. */
export interface FormParts {
  formSheet: (title: string, ...nodes: ReactNode[]) => ReactNode;
  span: (node: ReactNode) => ReactNode;
  linkBtn: (href: string, label: string, kind?: ButtonKind) => ReactNode;
}

type CourseId = number;

const missing = (error: unknown) => error instanceof ApiError && error.status === 404;
const pf = (screenId: string, extra?: Record<string, string>) => routeFor("professor", "screen", { screenId, ...(extra || {}) });

function toastNow(text: string) {
  getState().toast = text;
  render();
}

function Retry({ reload }: { reload: () => void }) {
  const [pressed, setPressed] = useState(false);
  return <Button kind="ghost" disabled={pressed} onClick={() => { setPressed(true); reload(); }}>다시 불러오기</Button>;
}

function Failure({ error, reload }: { error: unknown; reload?: () => void }) {
  return <div><p className="err" role="alert">{assignmentMessage(error)}</p>{reload ? <Retry reload={reload} /> : null}</div>;
}

/** Paragraphs of plain text. */
function Prose({ text }: { text: string }) {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  return (
    <div className="pf-prose">
      {blocks.map((block, index) => (
        <p key={index}>{block.split("\n").map((line, at) => <span key={at}>{at ? <br /> : null}{line}</span>)}</p>
      ))}
    </div>
  );
}

/** Login strip, unmapped course or wrong role: what to show instead of the server screen. */
function gate(course: Course, loginText: string): ReactNode {
  if (backendStatus() === "anon") return <div className="stack">{backendPanel({ text: loginText })}</div>;
  if (!courseIdFor(course.id)) return <div className="stack">{backendPanel()}<EmptyState title="이 과목은 ASTRA 서버에 없습니다" body="서버에 등록된 과목에만 과제를 내고 채점할 수 있습니다." /></div>;
  if (roleIn(course.id) !== "INSTRUCTOR") {
    return <div className="stack">{backendPanel()}<p className="pf-sub">이 과목을 담당하는 교수 계정으로 로그인해야 과제를 내고 채점할 수 있습니다.</p></div>;
  }
  return null;
}

const LOGIN = "ASTRA 서버에 로그인하면 이 과목의 과제를 서버에 내고 제출물을 채점할 수 있습니다.";

/** `datetime-local` value for a server LocalDateTime, and back. */
const toInput = (value: string | null | undefined) => (value ? String(value).slice(0, 16) : "");
const fromInput = (value: string) => (value ? (value.length === 16 ? `${value}:00` : value) : null);
/** At most two decimal places (the server's DECIMAL(6,2)). */
const hundredths = (n: number) => Math.abs(Math.round(n * 100) - n * 100) < 1e-6;

/** Loads `key` on a new visit or when nothing is cached (legacy `freshVisit` pattern). */
function useVisit<T>(resource: { peek(key: string): Entry<T> | null }, scope: string, key: string, load: () => void) {
  useEffect(() => {
    if (freshVisit(scope, key) === "entry" || !resource.peek(key)) load();
  }, [scope, key]);
}

// ---------- 과제 출제: form + list ----------

/** `pf_report_create` in backend mode. */
export function serverReportFields(ctx: ScreenCtx, course: Course, parts: FormParts): ReactNode {
  const blocked = gate(course, LOGIN);
  if (blocked) return blocked;
  const courseId = Number(courseIdFor(course.id));
  const editId = Number((ctx.params && ctx.params.get("edit")) || "");
  const edit = Number.isInteger(editId) && editId > 0 ? editId : null;
  return (
    <div className="stack">
      {backendPanel()}
      <AssignmentForm key={edit ?? "new"} week={ctx.state.week || 1} course={course} courseId={courseId} editId={edit} parts={parts} />
      <AssignmentList course={course} courseId={courseId} target="pf_report_create" />
    </div>
  );
}

interface FormFields {
  title: string;
  weekNo: string;
  maxScore: string;
  openAt: string;
  closeAt: string;
  late: boolean;
  lateUntil: string;
  allowText: boolean;
  allowFile: boolean;
  published: boolean;
  scoresReleased: boolean;
  description: string;
}

function fieldsOf(info: AssignmentInfo): FormFields {
  return {
    title: info.title,
    weekNo: String(info.weekNo),
    maxScore: scoreText(info.maxScore),
    openAt: toInput(info.openAt),
    closeAt: toInput(info.closeAt),
    late: info.latePolicy === "allow_until",
    lateUntil: toInput(info.lateUntil),
    allowText: Boolean(info.allowText),
    allowFile: Boolean(info.allowFile),
    published: Boolean(info.published),
    scoresReleased: Boolean(info.scoresReleased),
    description: info.description || "",
  };
}

/** Checks the form; `{ errors }` or `{ payload }`. */
function readAssignment(f: FormFields): { errors: string[] } | { payload: AssignmentPayload } {
  const errors: string[] = [];
  const title = f.title.trim();
  const weekNo = Number(f.weekNo);
  const maxScore = Number(f.maxScore);
  const openAt = fromInput(f.openAt);
  const closeAt = fromInput(f.closeAt);
  const lateUntil = f.late ? fromInput(f.lateUntil) : null;
  if (!title || title.length > 200) errors.push("제목은 1자 이상 200자 이하입니다");
  if (!Number.isInteger(weekNo) || weekNo < 1 || weekNo > 60) errors.push("주차는 1부터 60 사이입니다");
  if (!Number.isFinite(maxScore) || maxScore <= 0 || maxScore > 1000 || !hundredths(maxScore)) errors.push("배점은 0보다 크고 1000 이하, 소수 둘째 자리까지입니다");
  if (!closeAt) errors.push("마감 일시를 입력하세요");
  if (openAt && closeAt && openAt >= closeAt) errors.push("시작은 마감보다 앞이어야 합니다");
  if (f.late && (!lateUntil || (closeAt && lateUntil <= closeAt))) errors.push("지각 제출 마감은 마감보다 뒤여야 합니다");
  if (!f.allowText && !f.allowFile) errors.push("글 입력과 파일 첨부 중 하나는 받아야 합니다");
  if (f.description.length > 10000) errors.push("설명은 10,000자 이하입니다");
  if (errors.length || !closeAt) return { errors };
  return {
    payload: {
      weekNo,
      title,
      description: f.description.trim() ? f.description : null,
      openAt,
      closeAt,
      published: f.published,
      latePolicy: f.late ? "allow_until" : "none",
      lateUntil,
      allowText: f.allowText,
      allowFile: f.allowFile,
      maxScore,
      scoresReleased: f.scoresReleased,
    },
  };
}

interface LatestAssignment { info: AssignmentInfo; revision: number }

function AssignmentForm({ week, course, courseId, editId, parts }: { week: number; course: Course; courseId: CourseId; editId: number | null; parts: FormParts }) {
  const { formSheet, span, linkBtn } = parts;
  const key = editId ? assignmentKey(courseId, editId) : null;
  const entry = useResource(assignmentDetails, key);
  const [fields, setFields] = useState<FormFields>({
    title: "", weekNo: String(week), maxScore: "20", openAt: "", closeAt: "", late: false, lateUntil: "",
    allowText: true, allowFile: true, published: true, scoresReleased: false, description: "",
  });
  const [filled, setFilled] = useState(false);
  const [revision, setRevision] = useState<number | null>(null);
  const [status, setStatus] = useState<{ text: string; alert: boolean }>({ text: "", alert: false });
  const [busy, setBusy] = useState(false);
  const [latest, setLatest] = useState<LatestAssignment | null>(null);
  const mounted = useRef(true);
  useEffect(() => () => { mounted.current = false; }, []);
  useEffect(() => {
    if (!key || !editId) return;
    if (freshVisit("pf-assignment-edit", key) === "entry" || !assignmentDetails.peek(key)) void assignmentDetails.load(key, courseId, editId);
  }, [key]);

  // Edit: fill the form once from the server copy; a conflict reload leaves typed text alone.
  const data = entry && entry.data;
  const ready = Boolean(data && data.revision != null);
  useEffect(() => {
    if (!editId || filled || !data || data.revision == null) return;
    setFilled(true);
    setFields(fieldsOf(data.assignment));
    setRevision(data.revision);
    setStatus({ text: `수정 번호 ${data.revision} · 제출 ${data.submittedCount}명 · 지각 ${data.lateCount}명`, alert: false });
  });

  let shown = status;
  let locked = busy;
  if (editId && !filled) {
    locked = true;
    shown = {
      text: !entry || !entry.error ? "과제를 불러오는 중입니다."
        : missing(entry.error) ? "과제를 찾을 수 없습니다. 이 과목의 과제가 아닙니다." : assignmentMessage(entry.error),
      alert: Boolean(entry && entry.error),
    };
  }

  const set = <K extends keyof FormFields>(name: K, value: FormFields[K]) => setFields((prev) => ({ ...prev, [name]: value }));
  const text = (name: "title" | "weekNo" | "maxScore" | "openAt" | "closeAt" | "lateUntil") => ({
    value: fields[name],
    disabled: locked,
    onChange: (event: { currentTarget: HTMLInputElement }) => set(name, event.currentTarget.value),
  });
  const choice = (name: "late" | "allowText" | "allowFile" | "published" | "scoresReleased", label: string) => (
    <label className="choice pf-check">
      <input type="checkbox" checked={fields[name]} disabled={locked} onChange={(event) => set(name, event.currentTarget.checked)} />
      <span>{label}</span>
    </label>
  );

  /** After 409 edit_conflict: load the current assignment, adopt its revision, show it next to the typed form. */
  const showLatest = async (id: number) => {
    const detailKey = assignmentKey(courseId, id);
    await assignmentDetails.load(detailKey, courseId, id);
    const current = assignmentDetails.peek(detailKey);
    if (!mounted.current) return;
    if (!current || !current.data || current.data.revision == null) {
      setStatus({ text: `다른 곳에서 이 과제를 먼저 수정했지만 최신 내용을 불러오지 못했습니다. ${assignmentMessage(current && current.error)}`, alert: true });
      return;
    }
    setRevision(current.data.revision);
    setLatest({ info: current.data.assignment, revision: current.data.revision });
  };

  /** `pf-assignment-save`: create, or update with the revision the form was loaded with. */
  const submit = async () => {
    if (locked) return;
    const read = readAssignment(fields);
    if ("errors" in read) return setStatus({ text: read.errors.join(". "), alert: true });
    setBusy(true);
    setStatus({ text: "ASTRA 서버에 저장하고 있습니다.", alert: false });
    try {
      if (editId) await updateAssignment(courseId, editId, { ...read.payload, revision: Number(revision) });
      else await createAssignment(courseId, read.payload);
      getState().toast = editId ? "과제를 수정했습니다" : "과제를 서버에 등록했습니다";
      navigate(pf("pf_report_create", { kj: course.id }));
    } catch (error) {
      const message = assignmentMessage(error, { creating: !editId });
      if (mounted.current) setStatus({ text: message, alert: true });
      if (editId && isConflict(error, "edit_conflict")) await showLatest(editId);
      else if (!mounted.current) toastNow(message);
    } finally {
      if (mounted.current) setBusy(false);
    }
  };

  return formSheet(editId ? "과제 수정" : "과제",
    span(<Field label="제목"><input maxLength={200} {...text("title")} /></Field>),
    <Field label="주차"><input type="number" min="1" max="60" {...text("weekNo")} /></Field>,
    <Field label="배점"><input type="number" min="0.01" max="1000" step="0.01" {...text("maxScore")} /></Field>,
    <Field label="시작 (비우면 바로 시작)"><input type="datetime-local" {...text("openAt")} /></Field>,
    <Field label="마감"><input type="datetime-local" {...text("closeAt")} /></Field>,
    choice("late", "마감 뒤 지각 제출 받기"),
    <Field label="지각 제출 마감"><input type="datetime-local" {...text("lateUntil")} /></Field>,
    choice("allowText", "글 입력 제출"),
    choice("allowFile", "파일 첨부 제출"),
    span(choice("published", "학생에게 공개 (끄면 학생 목록에 나오지 않습니다)")),
    span(choice("scoresReleased", "점수 바로 공개")),
    span(<p className="pf-sub">점수를 공개하면 채점한 점수와 의견이 학생마다 자기 과제 화면에 보입니다. 공개하기 전에는 학생이 받는 응답에 점수가 아예 들어가지 않습니다.</p>),
    span(<Field label="설명 (일반 텍스트)">
      <textarea rows={6} maxLength={10000} value={fields.description} disabled={locked} onChange={(event) => set("description", event.currentTarget.value)} />
    </Field>),
    span(<p className="pf-sub" role={shown.alert ? "alert" : "status"} aria-live="polite">{shown.text}</p>),
    span(<div>{latest ? (
      <details className="pf-server-pdf" open>
        <summary><b>서버의 최신 내용</b><span className="pf-meta">{` · 수정 번호 ${latest.revision}`}</span></summary>
        <p className="pf-sub">{`${latest.info.title} · ${latest.info.weekNo}주 · ${periodText(latest.info)} · 배점 ${scoreText(latest.info.maxScore)}점 · ${latest.info.published ? "공개" : "비공개"} · 점수 ${latest.info.scoresReleased ? "공개" : "비공개"}`}</p>
        {latest.info.description ? <Prose text={latest.info.description} /> : null}
      </details>
    ) : null}</div>),
    span(<div className="pf-actions">
      <Button kind="primary" disabled={locked} onClick={() => void submit()}>{busy ? "저장하는 중…" : editId ? "수정 저장" : "과제 등록"}</Button>
      {editId ? linkBtn(pf("pf_report_create", { kj: course.id }), "새 과제 쓰기") : null}
    </div>),
    editId ? span(<div>{ready && data ? deleteControl("assignment", courseId, editId, { title: data.assignment.title, back: pf("pf_report_create", { kj: course.id }) }) : null}</div>) : null,
  );
}

type ListTarget = "pf_report_create" | "pf_report_status" | "pf_report_grade";

/** The course's server assignments with counts; `target` is where a row's main link goes. */
function AssignmentList({ course, courseId, target }: { course: Course; courseId: CourseId; target: ListTarget }) {
  const key = listKey(courseId);
  const load = () => { void assignmentLists.load(key, courseId); };
  const entry = useResource(assignmentLists, key, load);
  useVisit(assignmentLists, `pf-assignments-${target}`, key, load);
  const data = entry && entry.data;
  let body: ReactNode;
  if (!data) {
    body = !entry || !entry.error ? <p className="pf-sub">서버에 등록된 과제를 불러오는 중입니다.</p> : <Failure error={entry.error} reload={load} />;
  } else {
    const items = Array.isArray(data) ? data : [];
    body = !items.length ? <p className="pf-sub">아직 서버에 등록된 과제가 없습니다.</p> : <>
      {entry.error ? <p className="err" role="alert">{assignmentMessage(entry.error)}</p> : null}
      {items.map((item) => {
        const edit = pf("pf_report_create", { kj: course.id, edit: String(item.assignmentId) });
        const submissions = pf("pf_report_status", { kj: course.id, n: serverRef(item.assignmentId) });
        const grading = pf("pf_report_grade", { kj: course.id, n: serverRef(item.assignmentId) });
        const href = target === "pf_report_grade" ? grading : target === "pf_report_status" ? submissions : edit;
        return (
          <div className="pf-activity" key={item.assignmentId}>
            <Link className="pf-activity-main" href={href}>
              <Badge tone={item.phase === "open" || item.phase === "late" ? "success" : "neutral"}>{item.published ? PHASE[item.phase] || item.phase : "비공개"}</Badge>
              <span className="pf-activity-copy">
                <b>{item.title}</b>
                <span className="pf-meta">{`${item.weekNo}주 · ${periodText(item)} · 제출 ${item.submittedCount ?? 0} · 지각 ${item.lateCount ?? 0}`}</span>
              </span>
            </Link>
            <div className="pf-activity-actions">
              <Link href={edit} className="btn btn-ghost" aria-label={`${item.title} 수정`}>수정</Link>
              <Link href={submissions} className="btn btn-ghost" aria-label={`${item.title} 제출 현황`}>제출 현황</Link>
            </div>
          </div>
        );
      })}
    </>;
  }
  return (
    <section className="sheet" aria-label="ASTRA 서버에 등록된 과제">
      <h2>서버에 등록된 과제</h2>
      <p className="pf-sub">제출 수는 최종 제출한 학생 수입니다. 임시 저장은 세지 않습니다.</p>
      <div className="stack">{body}</div>
    </section>
  );
}

// ---------- 제출 현황 / 과제 채점 ----------

/** `pf_report_status` (and `pf_submissions?n=srv-…`) in backend mode. */
export function serverSubmissionStatus(ctx: ScreenCtx, course: Course): ReactNode {
  const blocked = gate(course, LOGIN);
  if (blocked) return blocked;
  const courseId = Number(courseIdFor(course.id));
  const assignmentId = assignmentIdOf(ctx.params && ctx.params.get("n"));
  if (!assignmentId) return <div className="stack">{backendPanel()}<AssignmentList course={course} courseId={courseId} target="pf_report_status" /></div>;
  return (
    <div className="stack">
      {backendPanel()}
      <AssignmentHeader course={course} courseId={courseId} assignmentId={assignmentId} screen="pf_report_status" />
      <SubmissionTable course={course} courseId={courseId} assignmentId={assignmentId} />
    </div>
  );
}

/** `pf_report_grade` in backend mode: pick an assignment, then a submission. */
export function serverGrading(ctx: ScreenCtx, course: Course): ReactNode {
  const blocked = gate(course, LOGIN);
  if (blocked) return blocked;
  const courseId = Number(courseIdFor(course.id));
  const assignmentId = assignmentIdOf(ctx.params && ctx.params.get("n"));
  if (!assignmentId) return <div className="stack">{backendPanel()}<AssignmentList course={course} courseId={courseId} target="pf_report_grade" /></div>;
  const submissionId = Number((ctx.params && ctx.params.get("sub")) || "");
  const head = <AssignmentHeader course={course} courseId={courseId} assignmentId={assignmentId} screen="pf_report_grade" />;
  if (!Number.isInteger(submissionId) || submissionId < 1) {
    return <div className="stack">{backendPanel()}{head}<SubmissionTable course={course} courseId={courseId} assignmentId={assignmentId} /></div>;
  }
  return <div className="stack">{backendPanel()}{head}<SubmissionView key={submissionId} course={course} courseId={courseId} assignmentId={assignmentId} submissionId={submissionId} /></div>;
}

/** Title, phase and counts of one assignment, and the score release switch. */
function AssignmentHeader({ course, courseId, assignmentId, screen }: { course: Course; courseId: CourseId; assignmentId: number; screen: ListTarget }) {
  const key = assignmentKey(courseId, assignmentId);
  const load = () => { void assignmentDetails.load(key, courseId, assignmentId); };
  const entry = useResource(assignmentDetails, key, load);
  useVisit(assignmentDetails, "pf-assignment-head", key, load);
  const [releaseNote, setReleaseNote] = useState("");
  const [releasing, setReleasing] = useState(false);
  const mounted = useRef(true);
  useEffect(() => () => { mounted.current = false; }, []);

  /**
   * `pf-assignment-release`: 점수 공개 명령(`PUT …/release`, `{ released, revision }`). 응답의 새 `revision`이 상세 캐시에
   * 들어가 다음 수정이 그 번호를 쓴다. 409 `edit_conflict`면 최신 상세를 다시 받고 알린다.
   */
  const toggleRelease = async (next: boolean) => {
    if (releasing) return;
    const say = (text: string) => {
      if (mounted.current) setReleaseNote(text);
      else toastNow(text);
    };
    const current = assignmentDetails.peek(key);
    if (!current || !current.data || current.data.revision == null) return say("과제 정보를 아직 불러오지 못했습니다. 잠시 후 다시 누르세요.");
    setReleasing(true);
    try {
      await releaseScores(courseId, assignmentId, next, current.data.revision);
      toastNow(next ? "점수를 공개했습니다. 학생 과제 화면에 자기 점수와 의견이 보입니다." : "점수를 비공개로 바꿨습니다. 학생 화면에서 점수와 의견이 사라졌습니다.");
    } catch (error) {
      if (isConflict(error, "edit_conflict")) {
        await assignmentDetails.load(key, courseId, assignmentId);
        say("다른 곳에서 이 과제를 먼저 수정했습니다. 바꾸지 않고 최신 내용을 불러왔으니 공개 상태를 확인한 뒤 다시 누르세요.");
      } else if (missing(error)) {
        await assignmentDetails.load(key, courseId, assignmentId);
        toastNow("이미 삭제되었거나 찾을 수 없는 과제입니다.");
      } else say(assignmentMessage(error));
    } finally {
      if (mounted.current) setReleasing(false);
    }
  };

  const data = entry && entry.data;
  let body: ReactNode;
  if (!data || data.revision == null) {
    if (!entry || !entry.error) body = <p className="pf-sub">과제를 불러오는 중입니다.</p>;
    else if (missing(entry.error)) {
      body = <EmptyState title="과제를 찾을 수 없습니다" body="이 과목의 과제가 아니거나 삭제되었습니다."
        actions={<Link href={pf("pf_report_create", { kj: course.id })} className="btn btn-secondary">과제 목록</Link>} />;
    } else body = <Failure error={entry.error} reload={load} />;
  } else {
    const info = data.assignment;
    const released = Boolean(info.scoresReleased);
    body = <>
      <h2>{info.title}</h2>
      <p className="pf-sub">{`${info.weekNo}주 · ${periodText(info)} · ${PHASE[data.phase || ""] || data.phase} · 배점 ${scoreText(info.maxScore)}점 · 제출 ${data.submittedCount}명 · 지각 ${data.lateCount}명`}</p>
      <div className="pf-release">
        <p className="pf-sub"><b>{released ? "점수 공개 중" : "점수 비공개"}</b>{" · "}{released
          ? "학생은 지금 과제 화면에서 자기 점수와 의견을 볼 수 있습니다. 비공개로 바꾸면 학생 화면에서 점수와 의견이 바로 사라지고, 준 점수는 그대로 남습니다."
          : "학생에게는 점수와 의견이 보이지 않습니다. 공개하면 제출한 학생마다 자기 점수와 의견만 과제 화면에서 바로 볼 수 있습니다. 다른 학생의 점수는 보이지 않습니다."}</p>
        <div className="pf-actions">
          <Button kind={released ? "secondary" : "primary"} disabled={releasing} aria-busy={releasing ? "true" : undefined} onClick={() => void toggleRelease(!released)}>
            {releasing ? "바꾸는 중…" : released ? "점수 비공개로 바꾸기" : "점수 공개하기"}
          </Button>
          <Link href={pf("pf_report_create", { kj: course.id, edit: String(assignmentId) })} className="btn btn-ghost">과제 수정</Link>
        </div>
        <p className="pf-sub" role={releaseNote ? "alert" : "status"} aria-live="polite">{releaseNote}</p>
      </div>
      {deleteControl("assignment", courseId, assignmentId, { title: info.title, back: pf(screen, { kj: course.id }) })}
    </>;
  }
  return <section className="sheet" aria-label="과제 정보"><div className="stack">{body}</div></section>;
}

function SubmissionTable({ course, courseId, assignmentId }: { course: Course; courseId: CourseId; assignmentId: number }) {
  const key = assignmentKey(courseId, assignmentId);
  const load = () => { void submissionLists.load(key, courseId, assignmentId); };
  const entry = useResource(submissionLists, key, load);
  useVisit(submissionLists, "pf-submissions", key, load);
  const data = entry && entry.data;
  let body: ReactNode;
  if (!data) {
    if (!entry || !entry.error) body = <p className="pf-sub">제출 목록을 불러오는 중입니다.</p>;
    else if (missing(entry.error)) body = <EmptyState title="제출 목록을 볼 수 없습니다" body="이 과목의 과제가 아니거나 삭제되었습니다." />;
    else body = <Failure error={entry.error} reload={load} />;
  } else {
    const items = Array.isArray(data) ? data : [];
    body = !items.length ? <p className="pf-sub">아직 최종 제출한 학생이 없습니다.</p> : <>
      {entry.error ? <p className="err" role="alert">{assignmentMessage(entry.error)}</p> : null}
      <Table headers={["학생", "상태", "제출 횟수", "최종 제출", "점수", ""]} rows={items.map((item) => {
        const href = pf("pf_report_grade", { kj: course.id, n: serverRef(assignmentId), sub: String(item.submissionId) });
        return [
          item.studentName || "이름 없음",
          <Badge tone={item.late ? "neutral" : "success"}>{STATUS[item.status] || item.status}</Badge>,
          `${item.versionCount}회`,
          koDateTime(item.submittedAt),
          item.score != null ? `${scoreText(item.score)}점` : "채점 전",
          <Link href={href} className="btn btn-secondary" aria-label={`${item.studentName || "학생"} 제출물 보기·채점`}>보기·채점</Link>,
        ];
      })} />
    </>;
  }
  return <section className="sheet" aria-label="제출 목록"><h2>제출 목록</h2><div className="stack">{body}</div></section>;
}

function FileLine({ courseId, file }: { courseId: CourseId; file: SubmissionFile }) {
  return (
    <div className="pf-activity">
      <span className="pf-activity-copy"><Icon name="file" /><b>{file.name}</b><span className="pf-meta">{` · ${sizeText(file.sizeBytes)}`}</span></span>
      {downloadControl(courseId, file)}
    </div>
  );
}

/** One student's submission: grade form, then every version (newest first) with its files. */
function SubmissionView({ course, courseId, assignmentId, submissionId }: { course: Course; courseId: CourseId; assignmentId: number; submissionId: number }) {
  const key = submissionKey(courseId, assignmentId, submissionId);
  const headKey = assignmentKey(courseId, assignmentId);
  const load = () => { void submissionDetails.load(key, courseId, assignmentId, submissionId); };
  const entry = useResource(submissionDetails, key, load);
  const head = useResource(assignmentDetails, headKey);
  useVisit(submissionDetails, "pf-submission", key, load);
  const [score, setScore] = useState("");
  const [feedback, setFeedback] = useState("");
  const [filled, setFilled] = useState(false);
  const [status, setStatus] = useState<{ text: string; alert: boolean }>({ text: "", alert: false });
  const [saving, setSaving] = useState(false);
  const mounted = useRef(true);
  useEffect(() => () => { mounted.current = false; }, []);
  const data = entry && entry.data;
  const info = head && head.data && head.data.assignment;
  useEffect(() => {
    if (filled || !data) return;
    setFilled(true);
    if (data.grade) {
      setScore(scoreText(data.grade.score));
      setFeedback(data.grade.feedback || "");
      if (!status.text) setStatus({ text: `${koDateTime(data.grade.gradedAt)}에 채점했습니다.`, alert: false });
    }
  });

  /** `pf-grade-save`: score within the assignment's bounds, plain-text feedback. */
  const saveGrade = async () => {
    if (saving || !info) return;
    const raw = score.trim();
    const max = Number(scoreText(info.maxScore));
    const value = Number(raw);
    if (raw === "" || !Number.isFinite(value) || value < 0 || !(value <= max) || !hundredths(value)) {
      return setStatus({ text: `점수는 0부터 ${scoreText(max)}까지, 소수 둘째 자리까지 입력하세요.`, alert: true });
    }
    if (feedback.length > 1000) return setStatus({ text: "의견은 1,000자 이하입니다.", alert: true });
    setSaving(true);
    setStatus({ text: "점수를 저장하고 있습니다.", alert: false });
    try {
      const grade = await gradeSubmission(courseId, assignmentId, submissionId, { score: value, feedback: feedback.trim() ? feedback : null });
      if (mounted.current) setStatus({ text: `${koDateTime(grade.gradedAt)}에 저장했습니다.`, alert: false });
      toastNow("점수를 저장했습니다");
    } catch (error) {
      if (mounted.current) setStatus({ text: assignmentMessage(error), alert: true });
    } finally {
      if (mounted.current) setSaving(false);
    }
  };

  let body: ReactNode;
  if (!data) {
    if (!entry || !entry.error) body = <p className="pf-sub">제출물을 불러오는 중입니다.</p>;
    else if (missing(entry.error)) body = <EmptyState title="제출물을 찾을 수 없습니다" body="이 과제의 제출물이 아니거나 아직 최종 제출하지 않았습니다." />;
    else body = <Failure error={entry.error} reload={load} />;
  } else {
    const versions = Array.isArray(data.versions) ? data.versions : [];
    const max = info ? scoreText(info.maxScore) : undefined;
    body = <>
      <h2>{`${data.studentName || "학생"} · ${STATUS[data.status || ""] || data.status}`}</h2>
      <div className="pf-form">
        <Field label="점수">
          <input type="number" min="0" step="0.01" max={max} aria-label={max ? `점수 (0~${max})` : "점수"} value={score} disabled={saving}
            onChange={(event) => setScore(event.currentTarget.value)} />
        </Field>
        <div className="span-2"><Field label="의견 (일반 텍스트, 1,000자까지)">
          <textarea rows={4} maxLength={1000} aria-label="의견" value={feedback} disabled={saving} onChange={(event) => setFeedback(event.currentTarget.value)} />
        </Field></div>
        <div className="span-2"><p className="pf-sub">{info ? (info.scoresReleased
          ? "점수를 공개한 과제입니다. 저장하면 이 학생의 과제 화면에 점수와 의견이 바로 보입니다."
          : "점수를 공개하기 전에는 학생에게 점수와 의견이 보이지 않습니다.") : ""}</p></div>
        <div className="span-2"><p className="pf-sub" role={status.alert ? "alert" : "status"} aria-live="polite">{status.text}</p></div>
        <div className="span-2"><div className="pf-actions">
          <Button kind="primary" disabled={!info || saving} onClick={() => void saveGrade()}>{saving ? "저장하는 중…" : "점수 저장"}</Button>
          <Link href={pf("pf_report_grade", { kj: course.id, n: serverRef(assignmentId) })} className="btn btn-ghost">제출 목록</Link>
        </div></div>
      </div>
      <h3>{`제출 버전 ${versions.length}개`}</h3>
      {versions.map((version, index) => {
        const files = Array.isArray(version.files) ? version.files : [];
        return (
          <details className="pf-server-pdf" open={index === 0} key={version.version}>
            <summary>
              <b>{`${version.version}번째 제출`}</b>
              <span className="pf-meta">{` · ${receiptTime(version.submittedAt)}${version.late ? " · 지각" : ""} · 파일 ${files.length}개`}</span>
            </summary>
            {version.body ? <Prose text={version.body} /> : <p className="pf-sub">글 없이 파일만 제출했습니다.</p>}
            {files.map((file) => <FileLine key={file.fileId} courseId={courseId} file={file} />)}
          </details>
        );
      })}
    </>;
  }
  return <section className="sheet" aria-label="제출물"><div className="stack">{body}</div></section>;
}
