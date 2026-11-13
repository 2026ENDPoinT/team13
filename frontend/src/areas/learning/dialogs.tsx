import { useEffect, useRef, useState, type ReactNode } from "react";
import { Button, Dialog, ErrorState, Field, LoadingState } from "../../ui/kit.tsx";
import { Icon } from "../../ui/icon.tsx";
import { Link, closeDialog, navigate } from "../../router.tsx";
import { getState, save } from "../../store.ts";
import { readForm, showErrors, stamp } from "../../forms.ts";
import { events, people, surveyQuestions, activities, courseById, type Activity } from "../../data.ts";
import { dday, typeLabel, activityHref, activityById } from "../../shared/format.ts";
import { hrefFor, find } from "../../manifest.ts";
import { remoteSubmitDialog } from "./assignments-server.tsx";
import { backendStatus } from "../../shared/backend.ts";
import { calendarTodoHref, serverDayDialog } from "./calendar-server.tsx";
import { homeEntry, todoKind, todoLabel, ddayAt } from "../../shared/home-remote.ts";
import { calendarRanges, rangeKey, closeDay, calendarMessage, EVENT_LABEL } from "../../shared/calendar-remote.ts";
import { usesServerMessages, serverRecipientDialog } from "./messages-server.tsx";
import { remoteSurveyDialog } from "./surveys-server.tsx";
import { completeStep, completionToken, runState } from "../../shared/assistant/plan.ts";
import { useResource } from "../../shared/remote-cache.ts";
import type { ScreenCtx } from "../../types.ts";
import { RetryButton } from "./activity-server.tsx";
import { DropZone, surveyRecord } from "./learning.tsx";

const DOW = ["일", "월", "화", "수", "목", "금", "토"];

function koDay(iso: string): string {
  const [y, m, d] = iso.split("-").map(Number);
  const date = new Date(y, m - 1, d);
  if (Number.isNaN(date.getTime())) return iso;
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${y}.${pad(m)}.${pad(d)} (${DOW[date.getDay()]})`;
}

const UPCOMING = 6;

/**
 * Backend `ready`: 다가오는 일정 from this month's server calendar (`GET /api/me/calendar?view=month`): academic events
 * that have not ended and activity deadlines from the server's today on, nearest first.
 */
function ServerUpcomingDialog() {
  const key = rangeKey("month", "");
  const load = () => void calendarRanges.load(key, "month", "");
  const entry = useResource(calendarRanges, key, load);
  useEffect(() => {
    void calendarRanges.visit("learning:upcoming", key, "month", "");
  });
  const data = entry && entry.data;
  let body: ReactNode;
  if (!data) {
    body = entry && entry.error
      ? <ErrorState title="일정을 불러오지 못했습니다" body={calendarMessage(entry.error)} actions={<RetryButton reload={load} />} />
      : <LoadingState title="일정을 불러오는 중입니다" />;
  } else {
    const home = homeEntry()?.data;
    const rows: { day: string; key: string; node: ReactNode }[] = [
      ...(data.events || []).filter((event) => (event.endDate || event.startDate) >= data.today)
        .map((event, index) => ({
          day: event.startDate < data.today ? data.today : event.startDate,
          key: `e${index}`,
          node: <><b>{event.title}</b>{" · "}{EVENT_LABEL[event.type] || "학사일정"}</>,
        })),
      ...(data.activities || []).filter((item) => closeDay(item) >= data.today)
        .map((item) => {
          const href = calendarTodoHref(item, home);
          const copy = <><b>{item.title}</b>{" · "}{item.courseName}{" · "}{ddayAt(item.closeAt, data.serverTime) || todoLabel(item.type)}</>;
          return {
            day: closeDay(item),
            key: `a${item.activityId}`,
            node: href ? <Link className="todo-link" href={href} data-todo={todoKind(item.type)}>{copy}</Link> : <p>{copy}</p>,
          };
        }),
    ].sort((a, b) => (a.day < b.day ? -1 : a.day > b.day ? 1 : 0)).slice(0, UPCOMING);
    body = rows.length
      ? rows.map((row) => (typeof row.node === "object" && row.key.startsWith("e") ? <p key={row.key}>{row.node}</p> : <span key={row.key} style={{ display: "contents" }}>{row.node}</span>))
      : <p className="msg-placeholder">이번 달에 남은 일정이 없습니다.</p>;
  }
  return (
    <Dialog title="다가오는 일정">
      <div>
        <div className="day-sheet">{body}</div>
        <Link href={hrefFor(find("07_home_calendar_week"))} className="btn btn-secondary">주간 달력</Link>
      </div>
    </Dialog>
  );
}

function DayDialog({ day }: { day: string }) {
  const list = events.filter((ev) => ev.date === day);
  return (
    <Dialog title={koDay(day)}>
      <div className="day-sheet">
        {list.length ? null : <p className="msg-placeholder">이 날짜에 등록된 일정이 없습니다.</p>}
        {list.map((ev, index) => {
          const activity = ev.activityId ? activities.find((item) => item.id === ev.activityId) : undefined;
          const course = ev.courseId ? courseById(ev.courseId) : null;
          const when = ev.date === "2026-11-10" ? "오늘" : ev.date > "2026-11-10" ? dday(`${ev.date}T23:59`) : "지남";
          const label = ev.label || ev.title;
          const href = activity ? activityHref(activity) : course ? hrefFor(find("40_room_submain"), { kj: course.id }) : "";
          const content = (
            <>
              <span className={`rail-dot ${ev.type}`} aria-hidden="true" />
              <span className="day-copy">
                <b>{label}</b>
                <span className="tiny">{course ? `${course.ko} · ${course.code} · ${typeLabel(ev.type)}` : typeLabel(ev.type)}</span>
              </span>
              <span className={when === "오늘" || (when && when.startsWith("D-")) ? "dday" : "tiny"}>{when}</span>
            </>
          );
          return href
            ? <Link key={index} href={href} className="day-item todo-link" data-item={activity ? activity.id : undefined} data-todo={activity ? activity.type : undefined}>{content}</Link>
            : <div key={index} className="day-item" data-item={activity ? activity.id : undefined} data-todo={activity ? activity.type : undefined}>{content}</div>;
        })}
      </div>
    </Dialog>
  );
}

function SubmitDialog({ activity, ctx }: { activity: Activity; ctx: ScreenCtx }) {
  const err = ctx.state.formError || {};
  const fileName = ctx.state.draft.fileName || "";
  const [body, setBody] = useState<string>(ctx.state.draft.reportBody || "");
  const area = useRef<HTMLTextAreaElement>(null);
  // Navigation replaces the dialog; a queued second click is not a new submission.
  const submitted = useRef(false);

  const formatNote = (mark: string) => {
    const state = getState();
    const node = area.current;
    const current = node ? node.value : String(state.draft.reportBody || "");
    const start = node ? node.selectionStart : 0;
    const end = node ? node.selectionEnd : start;
    const selected = current.slice(start, end) || "텍스트";
    const wrapped = mark === "list"
      ? selected.split("\n").map((line) => (line.startsWith("- ") ? line : `- ${line}`)).join("\n")
      : `${mark}${selected}${mark}`;
    state.draft.reportBody = current.slice(0, start) + wrapped + current.slice(end);
    save();
    setBody(state.draft.reportBody);
  };

  const submitReport = () => {
    if (submitted.current) return;
    const state = getState();
    const data = readForm();
    const text = String(data.body || state.draft.reportBody || "");
    const title = String(data.title || "");
    const file = String(data.file || "");
    const errors: Record<string, string> = {};
    if (!title.trim()) errors.title = "제목을 입력하세요";
    if (!text.trim() || text.trim().length < 4) errors.body = "설명을 4자 이상 입력하세요";
    if (!file) errors.file = "첨부 파일을 선택하세요";
    if (Object.keys(errors).length) {
      state.draft.reportBody = text;
      showErrors(errors);
      return;
    }
    const target = activityById(new URLSearchParams(location.search).get("n")) || activityById("r-open")!;
    const token = completionToken(target.id);
    state.submissions[target.id] = { who: "minjun", title: title.trim(), body: text.trim(), fileName: file, at: stamp() };
    state.fileBox.unshift({ name: file, at: stamp().slice(0, 10) });
    state.draft.reportBody = "";
    state.draft.fileName = "";
    state.toast = "과제를 제출했습니다";
    save();
    submitted.current = true;
    if (!completeStep(token, { navigate, event: "report-submitted" }) || runState()?.status === "done") {
      navigate(hrefFor(find("60_activity_report_detail_open"), { n: target.id, kj: target.courseId }));
    }
  };

  return (
    <Dialog title="과제 제출" wide>
      <div className="submit-form">
        <p className="tiny">{activity.title}</p>
        <div className="editor">
          <div className="editor-bar" role="toolbar" aria-label="서식">
            <Button kind="ghost" onClick={() => formatNote("**")}>굵게</Button>
            <Button kind="ghost" onClick={() => formatNote("*")}>기울임</Button>
            <Button kind="ghost" onClick={() => formatNote("list")}>목록</Button>
          </div>
          <Field label="설명" error={err.body}>
            <textarea ref={area} data-name="body" placeholder="제출 설명을 입력하세요." value={body} onChange={(event) => setBody(event.target.value)} aria-invalid={err.body ? "true" : undefined} />
          </Field>
        </div>
        <Field label="제목" error={err.title}>
          <input data-name="title" defaultValue={activity.title} placeholder="제목" aria-invalid={err.title ? "true" : undefined} />
        </Field>
        <DropZone text={fileName ? `선택됨 · ${fileName}` : "파일을 마우스로 끌어 놓아 업로드하세요. 파일 1개, 이 샘플은 학교 서버로 보내지 않습니다."} />
        <div className="compose-files">
          <label className="btn btn-secondary">
            <Icon name="file" />파일첨부
            <input type="file" data-name="file" className="file-input" aria-invalid={err.file ? "true" : undefined} onChange={(event) => { getState().draft.fileName = event.target.files?.[0]?.name || ""; }} />
          </label>
          {err.file ? <span className="err">{err.file}</span> : <span className="tiny">{fileName || "아직 선택한 파일이 없습니다."}</span>}
        </div>
        <div className="submit-actions">
          <Button kind="ghost" onClick={closeDialog}>취소</Button>
          <Button kind="primary" onClick={submitReport}>제출</Button>
        </div>
      </div>
    </Dialog>
  );
}

function SurveyDialog({ id, ctx }: { id: string; ctx: ScreenCtx }) {
  const err = ctx.state.formError || {};
  const saved = surveyRecord(ctx.state.surveys[id]);
  const submitted = useRef(false);
  const saveSurvey = () => {
    if (submitted.current) return;
    const state = getState();
    const data = readForm();
    const errors: Record<string, string> = {};
    for (const key of ["sq1", "sq2"]) if (!data[key]) errors[key] = "선택하세요";
    if (Object.keys(errors).length) {
      showErrors(errors);
      return;
    }
    const token = completionToken(id);
    state.surveys[id] = { ...data, done: true };
    state.toast = "설문을 저장했습니다";
    save();
    submitted.current = true;
    if (!completeStep(token, { navigate, event: "survey-saved" }) || runState()?.status === "done") closeDialog();
  };
  return (
    <Dialog title="설문">
      <div className="survey-form">
        <p className="lead">응답은 이 브라우저에만 저장됩니다. 모든 문항을 고른 뒤에 저장할 수 있습니다.</p>
        {saved.done ? <p className="note ok">이미 저장한 응답입니다. 고친 뒤 다시 저장할 수 있습니다.</p> : null}
        {surveyQuestions.map((q) => (
          <Field key={q.id} label={q.prompt} error={err[q.id]}>
            <select data-name={q.id} defaultValue={typeof saved[q.id] === "string" ? String(saved[q.id]) : ""} aria-invalid={err[q.id] ? "true" : undefined}>
              <option value="">선택</option>
              {q.options.map((op) => <option key={op} value={op}>{op}</option>)}
            </select>
          </Field>
        ))}
        <div className="submit-actions">
          <Button kind="ghost" onClick={closeDialog}>취소</Button>
          <Button kind="primary" onClick={saveSurvey}>응답 저장</Button>
        </div>
      </div>
    </Dialog>
  );
}

function RecipientsDialog({ ctx }: { ctx: ScreenCtx }) {
  const err = ctx.state.formError || {};
  const chosen = new Set(String((ctx.state.draft.mail && ctx.state.draft.mail.to) || "").split(",").map((s) => s.trim()).filter(Boolean));
  const pickRecipients = () => {
    const state = getState();
    const data = readForm();
    const names = Array.isArray(data.recipient) ? data.recipient : [];
    if (!names.length) {
      showErrors({ recipient: "한 명 이상 선택하세요" });
      return;
    }
    state.draft.mail = { ...(state.draft.mail || {}), to: names.join(", ") };
    state.toast = "수신자를 넣었습니다";
    save();
    navigate(hrefFor(find("02e_msg_compose")));
  };
  return (
    <Dialog title="수신자">
      <div className="recipient-list">
        <p className="tiny">한 명 이상 고르면 쪽지 쓰기로 돌아갑니다.</p>
        {people.map((p) => (
          <label key={p.id} className="choice">
            <input type="checkbox" data-name="recipient" value={p.name} defaultChecked={chosen.has(p.name)} />
            <span className="msg-avatar">{p.name.slice(0, 1)}</span>
            <span><b>{p.name}</b><span className="tiny">{p.role === "professor" ? "교수" : "학생"}</span></span>
          </label>
        ))}
        {err.recipient ? <p className="err">{err.recipient}</p> : null}
        <div className="submit-actions">
          <Button kind="ghost" onClick={closeDialog}>취소</Button>
          <Button kind="primary" onClick={pickRecipients}>선택</Button>
        </div>
      </div>
    </Dialog>
  );
}

function CalendarDialog() {
  const soon = events.filter((ev) => ev.date >= "2026-11-10").slice(0, 4);
  return (
    <Dialog title="다가오는 일정">
      <div className="day-sheet">
        {soon.map((ev, index) => (
          <p key={index}><b>{ev.title}</b>{" · "}{courseById(ev.courseId).ko}{" · "}{dday(`${ev.date}T23:59`) || typeLabel(ev.type)}</p>
        ))}
        <Link href={hrefFor(find("07_home_calendar_week"))} className="btn btn-secondary">주간 달력</Link>
      </div>
    </Dialog>
  );
}

export function renderDialog(name: string, ctx: ScreenCtx): ReactNode {
  // Backend `ready`: that day's academic events and deadlines from the server.
  if (name === "day" && backendStatus() === "ready") return serverDayDialog(ctx);
  if (name === "day") return <DayDialog day={(ctx.params && ctx.params.get("day")) || "2026-11-10"} />;
  if (name === "submit" && ctx.screen && ctx.screen.serverAssignment) return remoteSubmitDialog(ctx);
  if (name === "recipients" && usesServerMessages(ctx.params)) return serverRecipientDialog(ctx);
  if (name === "submit") {
    const activity = activities.find((item) => item.id === ((ctx.params && ctx.params.get("n")) || "r-open")) || activities.find((item) => item.id === "r-open")!;
    return <SubmitDialog key={activity.id} activity={activity} ctx={ctx} />;
  }
  if (name === "survey" && ctx.screen && ctx.screen.serverSurvey) return remoteSurveyDialog(ctx);
  if (name === "survey") {
    const id = (ctx.params && (ctx.params.get("n") || ctx.params.get("id"))) || "campus";
    return <SurveyDialog key={id} id={id} ctx={ctx} />;
  }
  if (name === "recipients") return <RecipientsDialog ctx={ctx} />;
  if (name === "todo" || name === "todo-more") return null;
  if (name === "calendar" && backendStatus() === "ready") return <ServerUpcomingDialog />;
  if (name === "calendar") return <CalendarDialog />;
  return null;
}
