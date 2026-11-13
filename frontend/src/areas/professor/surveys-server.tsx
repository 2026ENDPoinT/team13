// 교수의 서버 강의실 설문(백엔드 모드, `pf_discuss`의 설문 부분): 등록, `revision`으로 전체 수정(응답한 사람이
// 있으면 문항과 응답 방식은 보내지 않는다), 완료 수가 있는 목록, 결과 집계(`?n=srv-<surveyId>`).
// 토론은 서버 API가 없어 목업 그대로다.
import { useEffect, useRef, useState, type ReactNode } from "react";
import { Button, Badge, EmptyState } from "../../ui/kit.tsx";
import { Link, navigate } from "../../router.tsx";
import { getState, toast } from "../../store.ts";
import type { Course } from "../../data.ts";
import type { ScreenCtx } from "../../types.ts";
import { ApiError } from "../../shared/api.ts";
import { backendStatus, courseIdFor, roleIn, subscribe } from "../../shared/backend.ts";
import { backendPanel } from "../../shared/backend-prompt.tsx";
import { freshVisit, shownRows, useResource } from "../../shared/remote-cache.ts";
import { koDateTime } from "../../shared/home-remote.ts";
import { routeFor } from "../../shared/routes.ts";
import {
  surveyLists, surveyDetails, surveyPath, surveyIdOf, surveyRef, createSurvey, updateSurvey, surveyMessage, isConflict,
  PHASE, QUESTION_TYPE, type SurveyDetail, type SurveyInfo, type SurveyQuestion,
} from "../../shared/surveys-remote.ts";
import { resultsSection } from "../../shared/survey-view.tsx";
import { deleteControl } from "./delete-server.tsx";

const missing = (error: unknown) => error instanceof ApiError && error.status === 404;
const pf = (screenId: string, extra?: Record<string, string>) => routeFor("professor", "screen", { screenId, ...(extra || {}) });
const LOGIN = "ASTRA 서버에 로그인하면 이 과목의 설문을 서버에 만들고 결과를 볼 수 있습니다.";

const toInput = (value: string | null | undefined) => (value ? String(value).slice(0, 16) : "");
const fromInput = (value: string) => (value ? (value.length === 16 ? `${value}:00` : value) : null);
const periodText = (item: SurveyInfo) => (item.openAt ? `${koDateTime(item.openAt)} ~ ${koDateTime(item.closeAt)}` : `~ ${koDateTime(item.closeAt)}`);
// Surveys created from this screen: after a create it navigates to itself, and the new key gives an empty form.
let created = 0;

function Retry({ reload }: { reload: () => void }) {
  const [pressed, setPressed] = useState(false);
  return <Button kind="ghost" disabled={pressed} onClick={() => { setPressed(true); reload(); }}>다시 불러오기</Button>;
}

interface DraftQuestion {
  type: string;
  text: string;
  required: boolean;
  minSelect: string;
  maxSelect: string;
  options: string[];
}

// 폼 키 -> 편집 중인 문항. 세션이 바뀌면 버린다.
const drafts = new Map<string, DraftQuestion[]>();
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  drafts.clear();
});

const blankQuestion = (): DraftQuestion => ({ type: "single", text: "", required: true, minSelect: "", maxSelect: "", options: ["", ""] });

function fromServer(questions: SurveyQuestion[] | null | undefined): DraftQuestion[] {
  return (questions || []).map((q) => ({
    type: q.type,
    text: q.text || "",
    required: Boolean(q.required),
    minSelect: q.minSelect != null ? String(q.minSelect) : "",
    maxSelect: q.maxSelect != null ? String(q.maxSelect) : "",
    options: (q.options || []).map((o) => o.text),
  }));
}

function QuestionEditor({ list, locked }: { list: DraftQuestion[]; locked: boolean }) {
  const [, setTick] = useState(0);
  const repaint = () => setTick((n) => n + 1);
  return (
    <div className="stack" data-survey-questions="">
      {list.map((q, index) => <QuestionCard key={index} list={list} q={q} index={index} locked={locked} repaint={repaint} />)}
      {locked ? null : <div className="pf-actions"><Button kind="secondary" onClick={() => { list.push(blankQuestion()); repaint(); }}>문항 추가</Button></div>}
    </div>
  );
}

function QuestionCard({ list, q, index, locked, repaint }: { list: DraftQuestion[]; q: DraftQuestion; index: number; locked: boolean; repaint: () => void }) {
  const n = index + 1;
  const max = q.type === "scale" ? 11 : 20;
  const change = (patch: Partial<DraftQuestion>) => {
    Object.assign(q, patch);
    repaint();
  };
  return (
    <fieldset className="sheet pf-q" disabled={locked}>
      <legend>{`${n}번 문항`}</legend>
      <div className="pf-form">
        <label className="field">
          <span className="field-label">종류</span>
          <select
            aria-label={`${n}번 문항 종류`}
            value={q.type}
            onChange={(event) => {
              q.type = event.target.value;
              if (q.type === "essay") q.options = [];
              else if (q.options.length < 2) q.options = q.type === "scale" ? ["매우 아니다", "아니다", "보통", "그렇다", "매우 그렇다"] : ["", ""];
              repaint();
            }}
          >
            {Object.entries(QUESTION_TYPE).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </label>
        <label className="choice pf-check">
          <input type="checkbox" checked={q.required} onChange={(event) => change({ required: event.target.checked })} />
          <span>필수 응답</span>
        </label>
        <div className="span-2">
          <label className="field">
            <span className="field-label">문항 (일반 텍스트)</span>
            <textarea rows={2} maxLength={1000} aria-label={`${n}번 문항`} value={q.text} onChange={(event) => change({ text: event.target.value })} />
          </label>
        </div>
        {q.type === "multiple" ? (
          <label className="field">
            <span className="field-label">최소 선택</span>
            <input type="number" min={1} max={20} placeholder="1" aria-label={`${n}번 최소 선택 수`} value={q.minSelect} onChange={(event) => change({ minSelect: event.target.value })} />
          </label>
        ) : null}
        {q.type === "multiple" ? (
          <label className="field">
            <span className="field-label">최대 선택</span>
            <input type="number" min={1} max={20} placeholder="보기 수" aria-label={`${n}번 최대 선택 수`} value={q.maxSelect} onChange={(event) => change({ maxSelect: event.target.value })} />
          </label>
        ) : null}
        {q.type === "essay" ? null : (
          <div className="span-2">
            <p className="pf-sub">{q.type === "scale" ? "척도는 보기 순서가 1부터의 값입니다(2~11개)." : "보기는 2개 이상 20개 이하입니다."}</p>
            <div className="stack">
              {q.options.map((value, at) => (
                <div className="pf-inline" key={at}>
                  {q.type === "scale" ? <span className="pf-meta">{String(at + 1)}</span> : null}
                  <input maxLength={300} aria-label={`${n}번 보기 ${at + 1}`} value={value} onChange={(event) => { q.options[at] = event.target.value; repaint(); }} />
                  {locked ? null : (
                    <Button kind="ghost" aria-label={`${n}번 보기 ${at + 1} 빼기`} disabled={q.options.length <= 2} onClick={() => { q.options.splice(at, 1); repaint(); }}>빼기</Button>
                  )}
                </div>
              ))}
              {!locked && q.options.length < max ? <Button kind="ghost" onClick={() => { q.options.push(""); repaint(); }}>보기 추가</Button> : null}
            </div>
          </div>
        )}
      </div>
      {locked ? null : (
        <div className="pf-actions">
          <Button kind="ghost" aria-label={`${n}번 문항 빼기`} disabled={list.length <= 1} onClick={() => { list.splice(index, 1); repaint(); }}>문항 빼기</Button>
        </div>
      )}
    </fieldset>
  );
}

interface OutQuestion {
  type: string;
  text: string;
  required: boolean;
  options?: string[];
  minSelect?: number;
  maxSelect?: number;
}

function readQuestions(list: DraftQuestion[]): { errors: string[]; questions: OutQuestion[] } {
  const errors: string[] = [];
  if (!list.length || list.length > 100) errors.push("문항은 1개 이상 100개 이하입니다");
  const questions = list.map((q, index) => {
    const n = index + 1;
    if (!q.text.trim()) errors.push(`${n}번 문항을 입력하세요`);
    if (q.text.length > 1000) errors.push(`${n}번 문항은 1,000자 이하입니다`);
    const out: OutQuestion = { type: q.type, text: q.text, required: Boolean(q.required) };
    if (q.type === "essay") return out;
    const max = q.type === "scale" ? 11 : 20;
    if (q.options.length < 2 || q.options.length > max) errors.push(`${n}번 보기는 2개 이상 ${max}개 이하입니다`);
    if (q.options.some((o) => !o.trim() || o.length > 300)) errors.push(`${n}번 보기 문구를 모두 300자 이하로 입력하세요`);
    out.options = q.options.map((o) => o.trim());
    if (q.type === "multiple") {
      const min = q.minSelect === "" ? 1 : Number(q.minSelect);
      const most = q.maxSelect === "" ? q.options.length : Number(q.maxSelect);
      if (!Number.isInteger(min) || !Number.isInteger(most) || min < 1 || min > most || most > q.options.length) errors.push(`${n}번 선택 수는 1 이상 보기 수 이하이고 최소가 최대 이하여야 합니다`);
      out.minSelect = min;
      out.maxSelect = most;
    }
    return out;
  });
  return { errors, questions };
}

/** `pf_discuss`의 설문 부분(백엔드 모드). `?edit=<surveyId>` 수정, `?n=srv-<surveyId>` 결과. `_parts`는 쓰지 않는다. */
export function serverSurveyFields(ctx: ScreenCtx, course: Course, _parts?: unknown): ReactNode {
  if (backendStatus() === "anon") return <div className="stack">{backendPanel({ text: LOGIN })}</div>;
  const remoteId = courseIdFor(course.id);
  if (!remoteId) return <div className="stack">{backendPanel()}<EmptyState title="이 과목은 ASTRA 서버에 없습니다" body="서버에 등록된 과목에만 설문을 만들 수 있습니다." /></div>;
  if (roleIn(course.id) !== "INSTRUCTOR") return <div className="stack">{backendPanel()}<p className="pf-sub">이 과목을 담당하는 교수 계정으로 로그인해야 설문을 만들고 결과를 볼 수 있습니다.</p></div>;
  const courseId = Number(remoteId);
  const resultsId = surveyIdOf(ctx.params && ctx.params.get("n"));
  if (resultsId) {
    return (
      <div className="stack">
        {backendPanel()}
        <SurveyHeader course={course} courseId={courseId} surveyId={resultsId} />
        <section className="sheet">
          {resultsSection(courseId, resultsId, { title: "결과 집계" })}
          <p className="pf-sub">익명 설문은 응답 기간이 끝나고 응답자가 5명 이상일 때만 집계를 볼 수 있습니다. 답한 사람이 5명보다 적은 문항은 가립니다. 누가 무엇을 답했는지는 어떤 화면에도 나오지 않습니다.</p>
        </section>
        <SurveyList course={course} courseId={courseId} />
      </div>
    );
  }
  const editId = Number((ctx.params && ctx.params.get("edit")) || "");
  const edit = Number.isInteger(editId) && editId > 0 ? editId : null;
  return (
    <div className="stack">
      {backendPanel()}
      <SurveyForm key={`${courseId}:${edit || `new:${created}`}`} week={Number(ctx.state.week) || 1} course={course} courseId={courseId} editId={edit} />
      <SurveyList course={course} courseId={courseId} />
    </div>
  );
}

interface Status {
  text: string;
  alert: boolean;
}

function SurveyForm({ week, course, courseId, editId }: { week: number; course: Course; courseId: number; editId: number | null }) {
  const formKey = `${courseId}:${editId || "new"}`;
  const path = editId ? surveyPath(courseId, editId) : null;
  const entry = useResource(surveyDetails, path);
  const [filled, setFilled] = useState<SurveyDetail | null>(null);
  const [status, setStatus] = useState<Status | null>(null);
  const [saving, setSaving] = useState(false);
  const [latest, setLatest] = useState<SurveyDetail | null>(null);
  const revision = useRef(0);
  const sheet = useRef<HTMLDivElement>(null);
  const live = useRef(true);
  useEffect(() => { live.current = true; return () => { live.current = false; }; }, []);
  if (!editId && !drafts.has(formKey)) drafts.set(formKey, [blankQuestion()]);
  useEffect(() => {
    if (!path) return;
    if (freshVisit("pf-survey-edit", path) === "entry" || !surveyDetails.peek(path)) void surveyDetails.load(path, path);
  }, [path]);
  const data = entry && entry.data && entry.data.revision != null ? entry.data : null;
  const locked = Boolean(data && Number(data.respondentCount) > 0);
  if (data) {
    revision.current = Number(data.revision);
    // 잠긴 문항은 서버의 문항만 보여 준다.
    if (locked || (!filled && editId)) drafts.set(formKey, fromServer(data.questions));
  }
  useEffect(() => {
    if (editId && data && !filled) setFilled(data);
  }, [editId, data, filled]);

  const waiting = Boolean(editId) && !filled;
  let shown: Status = status || { text: "", alert: false };
  if (waiting) {
    shown = {
      alert: Boolean(entry && entry.error),
      text: !entry || !entry.error ? "설문을 불러오는 중입니다."
        : missing(entry.error) ? "설문을 찾을 수 없습니다. 이 과목의 설문이 아닙니다." : surveyMessage(entry.error),
    };
  } else if (data && !(status && status.alert)) {
    // 저장 실패 안내(role=alert)는 재조회로 다시 그려도 지우지 않는다.
    shown = { text: `수정 번호 ${data.revision} · ${(data.phase && PHASE[data.phase]) || data.phase} · 응답 ${data.respondentCount}명`, alert: false };
  }
  const disabled = waiting || saving;
  const info = filled ? filled.survey : null;

  /** 등록, 또는 읽은 `revision`으로 전체 수정. */
  async function save() {
    const root = sheet.current;
    if (!root || saving) return;
    const get = (name: string) => root.querySelector<HTMLInputElement>(`[data-field="${name}"]`)!;
    const textarea = root.querySelector<HTMLTextAreaElement>('[data-field="description"]')!;
    const errors: string[] = [];
    const title = get("title").value.trim();
    const weekNo = Number(get("weekNo").value);
    const openAt = fromInput(get("openAt").value);
    const closeAt = fromInput(get("closeAt").value);
    const resultsVisible = get("resultsVisible").checked;
    const releaseAt = fromInput(get("resultsReleaseAt").value);
    const description = textarea.value;
    if (!title || title.length > 200) errors.push("제목은 1자 이상 200자 이하입니다");
    if (!Number.isInteger(weekNo) || weekNo < 1 || weekNo > 60) errors.push("주차는 1부터 60 사이입니다");
    if (!closeAt) errors.push("마감 일시를 입력하세요");
    if (openAt && closeAt && openAt >= closeAt) errors.push("시작은 마감보다 앞이어야 합니다");
    if (releaseAt && !resultsVisible) errors.push("결과 공개 일시는 학생에게 결과를 공개할 때만 정합니다");
    if (description.length > 10000) errors.push("안내는 10,000자 이하입니다");
    const read = locked ? null : readQuestions(drafts.get(formKey) || []);
    if (read) errors.push(...read.errors);
    if (errors.length) {
      setStatus({ text: `${errors.join(". ")}.`, alert: true });
      return;
    }
    const payload: Record<string, unknown> = {
      weekNo,
      title,
      description: description.trim() ? description : null,
      openAt,
      closeAt,
      published: get("published").checked,
      resultsVisible,
      resultsReleaseAt: releaseAt,
    };
    // 응답한 사람이 있으면 문항과 응답 방식을 보내지 않는다(서버가 그대로 둔다).
    if (read) {
      payload.anonymous = get("anonymous").checked;
      payload.questions = read.questions;
    }
    setSaving(true);
    setStatus({ text: "ASTRA 서버에 저장하고 있습니다.", alert: false });
    try {
      if (editId) await updateSurvey(courseId, editId, { ...payload, revision: revision.current });
      else await createSurvey(courseId, payload);
      if (!editId) created += 1;
      drafts.delete(formKey);
      getState().toast = editId ? "설문을 수정했습니다" : "설문을 서버에 등록했습니다";
      navigate(pf("pf_discuss", { kj: course.id }));
      return;
    } catch (error) {
      if (!live.current) {
        toast(surveyMessage(error));
        return;
      }
      setStatus({ text: surveyMessage(error), alert: true });
      if (editId && path && (isConflict(error, "edit_conflict") || isConflict(error, "survey_has_responses"))) {
        await surveyDetails.load(path, path);
        const fresh = surveyDetails.peek(path);
        if (fresh && fresh.data && live.current) {
          revision.current = Number(fresh.data.revision);
          setLatest(fresh.data);
        }
      }
    } finally {
      if (live.current) setSaving(false);
    }
  }

  const questions = drafts.get(formKey) || [];
  return (
    <div ref={sheet} className="sheet" data-survey-form="" aria-busy={saving ? "true" : undefined}>
      <h2>{editId ? "설문 수정" : "설문"}</h2>
      <div className="pf-form" key={filled ? "filled" : "empty"}>
        <label className="field span-2"><span className="field-label">제목</span><input data-field="title" maxLength={200} defaultValue={info ? info.title : ""} disabled={disabled} /></label>
        <label className="field"><span className="field-label">주차</span><input data-field="weekNo" type="number" min={1} max={60} defaultValue={String(info ? info.weekNo : week)} disabled={disabled} /></label>
        <label className="field"><span className="field-label">시작 (비우면 바로 시작)</span><input data-field="openAt" type="datetime-local" defaultValue={toInput(info && info.openAt)} disabled={disabled} /></label>
        <label className="field"><span className="field-label">마감</span><input data-field="closeAt" type="datetime-local" defaultValue={toInput(info && info.closeAt)} disabled={disabled} /></label>
        <label className="choice pf-check span-2"><input data-field="published" type="checkbox" defaultChecked={info ? Boolean(info.published) : true} disabled={disabled} /><span>학생에게 공개</span></label>
        <label className="choice pf-check span-2"><input data-field="anonymous" type="checkbox" defaultChecked={info ? Boolean(info.anonymous) : true} disabled={disabled || locked} /><span>익명 설문 (담당자도 누가 답했는지 모릅니다. 결과는 마감 뒤 응답자 5명 이상일 때만)</span></label>
        <label className="choice pf-check span-2"><input data-field="resultsVisible" type="checkbox" defaultChecked={info ? Boolean(info.resultsVisible) : false} disabled={disabled} /><span>학생에게 결과 공개 (마감 뒤, 응답자 5명 이상일 때)</span></label>
        <label className="field"><span className="field-label">결과 공개 일시 (비우면 마감 뒤 바로)</span><input data-field="resultsReleaseAt" type="datetime-local" defaultValue={toInput(info && info.resultsReleaseAt)} disabled={disabled} /></label>
        <label className="field span-2"><span className="field-label">안내 (일반 텍스트)</span><textarea data-field="description" rows={4} maxLength={10000} defaultValue={info ? info.description || "" : ""} disabled={disabled} /></label>
        <p className="pf-sub span-2" role={shown.alert ? "alert" : "status"} aria-live="polite" data-survey-status="">{shown.text}</p>
        <div className="span-2" data-survey-latest="">
          {latest ? (
            <details className="pf-server-pdf" open>
              <summary><b>서버의 최신 내용</b><span className="pf-meta">{` · 수정 번호 ${latest.revision} · 응답 ${latest.respondentCount}명`}</span></summary>
              <p className="pf-sub">{`${latest.survey.title} · ${latest.survey.weekNo}주 · ${periodText(latest.survey)} · ${latest.survey.anonymous ? "익명" : "실명"} · 결과 ${latest.survey.resultsVisible ? "공개" : "비공개"}`}</p>
            </details>
          ) : null}
        </div>
        <div className="span-2">
          {!editId ? <><h3>문항</h3><QuestionEditor list={questions} locked={false} /></> : null}
          {editId && data ? (
            <>
              <h3>{`문항 ${(data.questions || []).length}개`}</h3>
              {locked ? <p className="pf-sub" role="note">응답한 사람이 있어 문항과 응답 방식(익명/실명)은 바꿀 수 없습니다. 기간, 안내, 공개, 결과 공개는 바꿀 수 있습니다.</p> : null}
              <QuestionEditor key={locked ? `locked:${data.revision}` : "open"} list={questions} locked={locked || saving} />
            </>
          ) : null}
        </div>
        <div className="pf-actions span-2">
          <Button kind="primary" disabled={disabled} onClick={() => { void save(); }}>{saving ? "저장하는 중…" : editId ? "수정 저장" : "설문 등록"}</Button>
          {editId ? <Link className="btn btn-secondary" href={pf("pf_discuss", { kj: course.id })}>새 설문 쓰기</Link> : null}
          {editId ? <Link className="btn btn-secondary" href={pf("pf_discuss", { kj: course.id, n: surveyRef(editId) })}>결과 보기</Link> : null}
        </div>
        {editId ? (
          <div className="span-2" data-survey-delete="">
            {data ? deleteControl("survey", courseId, editId, { title: data.survey.title, back: pf("pf_discuss", { kj: course.id }) }) : null}
          </div>
        ) : null}
      </div>
    </div>
  );
}

function SurveyHeader({ course, courseId, surveyId }: { course: Course; courseId: number; surveyId: number }) {
  const path = surveyPath(courseId, surveyId);
  const load = () => { void surveyDetails.load(path, path); };
  const entry = useResource(surveyDetails, path, load);
  useEffect(() => {
    if (freshVisit("pf-survey-head", path) === "entry" || !surveyDetails.peek(path)) void surveyDetails.load(path, path);
  }, [path]);
  const data = entry && entry.data;
  let body: ReactNode;
  if (!data || data.revision == null) {
    if (!entry || !entry.error) body = <p className="pf-sub">설문을 불러오는 중입니다.</p>;
    else if (missing(entry.error)) body = <EmptyState title="설문을 찾을 수 없습니다" body="이 과목의 설문이 아니거나 삭제되었습니다." actions={<Link className="btn btn-secondary" href={pf("pf_discuss", { kj: course.id })}>설문 목록</Link>} />;
    else body = <><p className="err" role="alert">{surveyMessage(entry.error)}</p><Retry reload={load} /></>;
  } else {
    const info = data.survey;
    body = (
      <>
        <h2>{info.title}</h2>
        <p className="pf-sub">{`${info.weekNo}주 · ${periodText(info)} · ${(data.phase && PHASE[data.phase]) || data.phase} · ${info.anonymous ? "익명" : "실명"} · 응답 ${data.respondentCount}명 · 문항 ${(data.questions || []).length}개`}</p>
        <div className="pf-actions"><Link className="btn btn-ghost" href={pf("pf_discuss", { kj: course.id, edit: String(surveyId) })}>설문 수정</Link></div>
        {deleteControl("survey", courseId, surveyId, { title: info.title, back: pf("pf_discuss", { kj: course.id }) })}
      </>
    );
  }
  return <section className="sheet" aria-label="설문 정보"><div className="stack">{body}</div></section>;
}

function SurveyList({ course, courseId }: { course: Course; courseId: number }) {
  const key = String(courseId);
  const load = () => { void surveyLists.load(key, courseId); };
  const entry = useResource(surveyLists, key, load);
  useEffect(() => {
    if (freshVisit("pf-surveys", key) === "entry" || !surveyLists.peek(key)) void surveyLists.load(key, courseId);
  }, [key, courseId]);
  // After a write the list reloads; its previous rows (links only) stay on screen meanwhile.
  const { rows: data, stale } = shownRows(entry);
  let list: ReactNode;
  if (!data) {
    list = !entry || !entry.error
      ? <p className="pf-sub">서버에 등록된 설문을 불러오는 중입니다.</p>
      : <div><p className="err" role="alert">{surveyMessage(entry.error)}</p><Retry reload={load} /></div>;
  } else {
    const items = Array.isArray(data) ? data : [];
    const note = entry?.error ? <div><p className="err" role="alert">{surveyMessage(entry.error)}</p><Retry reload={load} /></div>
      : stale ? <p className="pf-sub" role="status">저장한 내용을 반영한 목록을 불러오는 중입니다.</p> : null;
    list = <>{note}{!items.length
      ? <p className="pf-sub">아직 서버에 등록된 설문이 없습니다.</p>
      : items.map((item) => {
        const edit = pf("pf_discuss", { kj: course.id, edit: String(item.surveyId) });
        const results = pf("pf_discuss", { kj: course.id, n: surveyRef(item.surveyId) });
        return (
          <div className="pf-activity" key={item.surveyId}>
            <Link className="pf-activity-main" href={results}>
              <Badge tone={item.phase === "open" ? "success" : "neutral"}>{item.published ? (item.phase && PHASE[item.phase]) || item.phase || "" : "비공개"}</Badge>
              <span className="pf-activity-copy">
                <b>{item.title}</b>
                <span className="pf-meta">{`${item.weekNo}주 · ${periodText(item)} · ${item.anonymous ? "익명" : "실명"} · ${item.questionCount}문항 · 응답 ${item.respondentCount ?? 0}명`}</span>
              </span>
            </Link>
            <div className="pf-activity-actions">
              <Link className="btn btn-ghost" href={edit} aria-label={`${item.title} 수정`}>수정</Link>
              <Link className="btn btn-ghost" href={results} aria-label={`${item.title} 결과`}>결과</Link>
            </div>
          </div>
        );
      })}</>;
  }
  return (
    <section className="sheet" aria-label="ASTRA 서버에 등록된 설문">
      <h2>서버에 등록된 설문</h2>
      <div className="stack">{list}</div>
    </section>
  );
}
