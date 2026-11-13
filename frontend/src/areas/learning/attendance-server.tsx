// 학생 출석 화면(41, 41b)의 서버 버전. 차시마다 자기 기록과 과목 전체 요약은 `GET …/attendance`에서, 진행 중인 스마트출석은
// `GET …/smart-rounds/active`에서 받는다. 응답의 `pollAfterSec`마다 다시 묻고, 진행 목록에서 빠진 회차는 `…/me`로 마지막 상태
// (마감, 만료, 취소)를 한 번 더 읽는다. "출석했습니다."는 상태가 `confirmed`이고 서버 기록(`attendance`)이 있을 때만 쓴다.
// 입력 중인 PIN·QR token은 다시 그려도 남게 메모리에만 둔다.
import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { Button, EmptyState, ErrorState, Field, LoadingState } from "../../ui/kit.tsx";
import { courseById } from "../../data.ts";
import { getState, render, save } from "../../store.ts";
import { ApiError } from "../../shared/api.ts";
import { backendStatus, courseIdFor, subscribe } from "../../shared/backend.ts";
import { freshVisit, useResource } from "../../shared/remote-cache.ts";
import { koDateTime } from "../../shared/home-remote.ts";
import {
  attendanceViews, activeRounds, myRounds, attendanceKey, roundKey, checkRound, pollDelay, serverGap, roundOutcome,
  STATUS, SOURCE, METHOD, ROUND_STATE, statusTone, koDay, timeRange, attendanceMessage,
  type AttendanceSession, type MyRound,
} from "../../shared/attendance-remote.ts";
import type { Screen, ScreenCtx } from "../../types.ts";
import { RetryButton } from "./activity-server.tsx";


interface Note {
  text: string;
  alert: boolean;
}

interface SeenRound {
  state: string;
  settled: boolean;
}

// 회차별 입력값(PIN 또는 QR token)과, 이 세션에서 본 회차(진행 목록에서 빠지면 마지막 상태를 읽는다).
const typed = new Map<string, string>(); // roundKey -> text
const seen = new Map<number, Map<number, SeenRound>>(); // courseId -> Map(roundId -> { state, settled })
const notes = new Map<string, Note>(); // roundKey -> { text, alert }
subscribe(() => {
  typed.clear();
  seen.clear();
  notes.clear();
});

const missing = (error: unknown) => error instanceof ApiError && error.status === 404;

/**
 * 주차 버튼(샘플·서버 출석 공용): 고른 주차를 표시하고, 그 주차 카드(`att-<주>`)를 화면에 보이게 옮겨 초점을 둔다.
 * 움직임 줄이기 설정이면 바로 옮긴다. 위 머리말에 가리지 않는 여백은 learning.css의 `scroll-margin-top`이다.
 */
export function pickAttendanceWeek(week: number): void {
  getState().attendanceWeek = week || 1;
  save();
  render();
  const card = document.getElementById(`att-${week}`);
  if (!card) return;
  const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  card.scrollIntoView({ behavior: reduced ? "auto" : "smooth", block: "start" });
  card.focus({ preventScroll: true });
}

function courseOf(ctx: ScreenCtx) {
  const kj = ctx.params && ctx.params.get("kj");
  if (kj && courseById(kj).id === kj) return courseById(kj);
  return courseById(ctx.state.courseId || (ctx.screen && ctx.screen.course));
}

function seenOf(courseId: number): Map<number, SeenRound> {
  let known = seen.get(courseId);
  if (!known) {
    known = new Map();
    seen.set(courseId, known);
  }
  return known;
}

// ---------- 스마트출석 패널 ----------

function RecordLine({ attendance }: { attendance: MyRound["attendance"] }) {
  if (!attendance) return null;
  // `check`·`me`의 기록에는 출처와 결정 시각도 온다.
  const record: { status: string; source?: string | null; decidedAt?: string | null } = attendance;
  const parts = [STATUS[record.status] || record.status];
  if (record.source) parts.push(SOURCE[record.source] || record.source);
  if (record.decidedAt) parts.push(koDateTime(record.decidedAt));
  return <p className="tiny">{`서버 기록: ${parts.join(" · ")}`}</p>;
}

/** `check`: PIN 또는 QR token 제출. 같은 버튼을 두 번 눌러도 요청은 하나다. */
function RoundCard({ courseId, view, sessionLabel }: { courseId: number; view: MyRound; sessionLabel: string | undefined }) {
  const key = roundKey(courseId, view.roundId);
  const [value, setValue] = useState(() => typed.get(key) || "");
  const [note, setNote] = useState<Note | undefined>(() => notes.get(key));
  const [busy, setBusy] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const live = useRef(true);
  const refocus = useRef(false);
  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
    };
  }, []);
  useEffect(() => {
    if (busy || !refocus.current) return;
    refocus.current = false;
    input.current?.focus();
  }, [busy]);
  const pin = view.method !== "qr";
  const say = (text: string, alert: boolean) => {
    const next = { text, alert };
    notes.set(key, next);
    if (live.current) setNote(next);
  };
  const submit = async () => {
    if (busy) return;
    const proof = String(value || typed.get(key) || "").trim();
    if (pin ? !/^[0-9]{4,8}$/.test(proof) : !/^[0-9A-Za-z._-]{1,40}$/.test(proof)) {
      say(pin ? "PIN은 숫자로 입력하세요." : "QR 코드에서 읽은 문자열을 입력하세요.", true);
      input.current?.focus();
      return;
    }
    setBusy(true);
    try {
      const state = await checkRound(courseId, view.roundId, pin ? { pin: proof } : { qrToken: proof });
      typed.delete(key);
      notes.delete(key);
      if (live.current) {
        setValue("");
        setNote(undefined);
      }
      seenOf(courseId).set(view.roundId, { state: state.state, settled: false });
      void attendanceViews.load(attendanceKey(courseId), courseId);
      void activeRounds.load(attendanceKey(courseId), courseId);
      getState().toast = state.state === "confirmed" && state.attendance ? "출석했습니다" : ROUND_STATE[state.state] || "제출했습니다";
      render();
    } catch (error) {
      say(attendanceMessage(error), true);
      // 잠김·만료·마감·취소·대상 아님은 상태가 바뀐 것이다. 진행 목록을 다시 받아 그 상태를 그린다.
      if (error instanceof ApiError && (error.status === 409 || error.status === 429 || error.status === 422)) {
        void activeRounds.load(attendanceKey(courseId), courseId);
      }
    } finally {
      if (live.current) {
        refocus.current = true;
        setBusy(false);
      }
    }
  };
  const onKey = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key !== "Enter") return;
    event.preventDefault();
    void submit();
  };
  const state = view.state;
  let body: ReactNode;
  if (state === "confirmed") {
    body = view.attendance
      ? (
        <>
          <p className={`att-smart-result st-${statusTone(view.attendance.status)}`} role="status">출석했습니다.</p>
          <RecordLine attendance={view.attendance} />
        </>
      )
      // confirmed인데 기록이 없으면 확정을 단정하지 않는다(SEC-06). 다음 조회에서 다시 본다.
      : <p className="att-smart-result" role="status">출석 기록을 확인하고 있습니다.</p>;
  } else if (state !== "pending") {
    // 잠김(진행 중)·잠긴 채 마감·제출 없이 마감은 서버의 `failedAttempts`·`lockedOut`으로 가른다.
    const closedLocked = (state === "expired" || state === "closed") && view.lockedOut;
    const outcome = roundOutcome(view);
    body = (
      <>
        <p className={`att-smart-result${state === "locked" || closedLocked ? " st-absent" : ""}`} role="status">{ROUND_STATE[state] || state}</p>
        {outcome
          ? <p className="tiny" data-round-outcome={state === "locked" ? "locked" : closedLocked ? "closed-locked" : (Number(view.failedAttempts) || 0) === 0 ? "never-submitted" : "closed-failed"}>{outcome}</p>
          : null}
        {state === "cancelled" ? <p className="tiny">담당 교수가 이 출석을 취소해 이 회차로 남은 기록이 지워졌습니다.</p> : null}
      </>
    );
  } else {
    const left = view.serverTime && view.endsAt ? serverGap(view.endsAt, view.serverTime) : null;
    const control = pin
      ? <input ref={input} type="text" value={value} autoComplete="off" disabled={busy} inputMode="numeric" pattern="[0-9]*" maxLength={view.pinLength || 8} placeholder={`${view.pinLength || 6}자리 숫자`} onChange={(event) => { setValue(event.target.value); typed.set(key, event.target.value.trim()); }} onKeyDown={onKey} />
      : <input ref={input} type="text" value={value} autoComplete="off" disabled={busy} maxLength={40} placeholder="QR 코드의 문자열" spellCheck={false} onChange={(event) => { setValue(event.target.value); typed.set(key, event.target.value.trim()); }} onKeyDown={onKey} />;
    body = (
      <>
        <Field label={pin ? "담당 교수가 보여 준 PIN" : "QR 코드를 읽은 문자열"}>{control}</Field>
        <p className="tiny">{[
          view.failuresLeft != null ? `남은 제출 기회 ${view.failuresLeft}번` : "",
          Number(view.failedAttempts) > 0 ? `틀린 제출 ${view.failedAttempts}번` : "",
          left != null && left > 0 ? `약 ${Math.max(1, Math.ceil(left / 60000))}분 남음` : "",
          pin ? "" : "QR 코드는 15초마다 바뀝니다. 휴대폰으로 읽은 값을 바로 붙여 넣으세요.",
        ].filter(Boolean).join(" · ")}</p>
        <div className="btn-row">
          <Button kind="primary" disabled={busy} aria-busy={busy ? "true" : undefined} onClick={() => void submit()}>{busy ? "확인하는 중…" : "출석하기"}</Button>
        </div>
        <p className={note && note.alert ? "err" : "tiny"} role={note && note.alert ? "alert" : "status"}>{note ? note.text : ""}</p>
      </>
    );
  }
  return (
    <article className="att-card att-smart" data-round={String(view.roundId)} aria-live="polite">
      <header className="att-card-h">
        <h2>{`스마트출석 · ${view.method === "qr" ? "QR" : "PIN"}`}</h2>
        <p className="tiny">{[sessionLabel, view.endsAt ? `${koDateTime(view.endsAt)}까지` : ""].filter(Boolean).join(" · ")}</p>
      </header>
      {body}
    </article>
  );
}

function SmartPanel({ courseId, sessionsById }: { courseId: number; sessionsById: Map<number, string> }) {
  const key = attendanceKey(courseId);
  const entry = useResource(activeRounds, key);
  useResource(myRounds, null);
  const known = seenOf(courseId);
  const live = entry && entry.data && Array.isArray(entry.data.mine) ? entry.data.mine : [];
  useEffect(() => {
    for (const view of live) known.set(view.roundId, { state: view.state, settled: false });
  });
  const data = entry && entry.data;
  if (!data) {
    if (entry && entry.error && !missing(entry.error)) {
      return <div className="att-smart-list"><p className="err" role="alert">{`스마트출석을 확인하지 못했습니다. ${attendanceMessage(entry.error)}`}</p></div>;
    }
    return <div className="att-smart-list" />;
  }
  const cards: MyRound[] = [];
  const ids = new Set<number>();
  for (const view of live) {
    ids.add(view.roundId);
    const mine = myRounds.peek(roundKey(courseId, view.roundId));
    // 제출 직후의 응답이 조회보다 새로울 수 있다. 같은 회차면 확정 쪽을 쓴다.
    cards.push(mine && mine.data && mine.data.state === "confirmed" && view.state === "pending" ? mine.data : view);
  }
  for (const [roundId] of known) {
    if (ids.has(roundId)) continue;
    const mine = myRounds.peek(roundKey(courseId, roundId));
    if (mine && mine.data) cards.push(mine.data);
  }
  return (
    <div className="att-smart-list">
      {cards.map((view) => <RoundCard key={view.roundId} courseId={courseId} view={view} sessionLabel={view.sessionId != null ? sessionsById.get(view.sessionId) : undefined} />)}
    </div>
  );
}

/**
 * 응답의 간격마다 진행 목록을 다시 받고, 빠진 회차는 `me`를 한 번 읽는다. 화면이 붙어 있는 동안만 돌고, 떠나면 다음 차례에 멈춘다.
 */
function usePolling(courseId: number): void {
  useEffect(() => {
    let stopped = false;
    let timer = 0;
    const known = seenOf(courseId);
    const tick = async () => {
      if (stopped) return;
      if (typeof document !== "undefined" && document.visibilityState === "hidden") {
        timer = window.setTimeout(tick, 3000);
        return;
      }
      const key = attendanceKey(courseId);
      const before = JSON.stringify(activeRounds.peek(key)?.data?.mine || []);
      await activeRounds.load(key, courseId);
      const entry = activeRounds.peek(key);
      const live = new Set((entry?.data?.mine || []).map((view) => view.roundId));
      const settling = [...known].filter(([roundId, info]) => !live.has(roundId) && !info.settled);
      await Promise.all(settling.map(async ([roundId, info]) => {
        await myRounds.load(roundKey(courseId, roundId), courseId, roundId);
        const mine = myRounds.peek(roundKey(courseId, roundId));
        if (mine && mine.data) {
          info.state = mine.data.state;
          info.settled = true;
        } else if (mine && missing(mine.error)) known.delete(roundId);
      }));
      const after = JSON.stringify(activeRounds.peek(key)?.data?.mine || []);
      // 회차가 열리거나 끝나면 출석부의 기록도 바뀌었을 수 있다.
      if (before !== after || settling.length) void attendanceViews.load(key, courseId);
      if (!stopped) timer = window.setTimeout(tick, pollDelay(entry && entry.data));
    };
    timer = window.setTimeout(tick, 0);
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [courseId]);
}

// ---------- 출석부 ----------

function SessionRow({ session }: { session: AttendanceSession }) {
  const record = session.record || { status: "unmarked", source: null };
  const cancelled = session.cancelled;
  const time = [koDay(session.classDate), timeRange(session)].filter(Boolean).join(" ");
  return (
    <div className={`att-session${cancelled ? " is-cancelled" : ""}`}>
      <span className="att-rail" aria-hidden="true"><i /></span>
      <span>
        <b>{`${session.sessionNo}차시${session.makeup ? " 보강" : ""}`}</b>
        <span className="tiny">{[cancelled ? "휴강" : METHOD[session.method || ""] || session.method, time].filter(Boolean).join(" · ")}</span>
      </span>
      {cancelled
        ? <span className="att-status st-empty">휴강</span>
        : <span className={`att-status st-${statusTone(record.status)}`} title={record.source ? SOURCE[record.source] || record.source : ""}>{STATUS[record.status] || record.status}</span>}
    </div>
  );
}

function AttendanceBody({ courseId, selectedWeek }: { courseId: number; selectedWeek: number | undefined }) {
  const key = attendanceKey(courseId);
  const load = () => void attendanceViews.load(key, courseId);
  const entry = useResource(attendanceViews, key, load);
  useEffect(() => {
    if (freshVisit("student-attendance", key) === "entry" || !attendanceViews.peek(key)) load();
  });
  // 다음 조회는 화면이 붙어 있을 때만 보낸다. 다른 화면으로 가면 그 tick에서 멈춘다.
  usePolling(courseId);
  const data = entry && entry.data;
  const weeks = data && Array.isArray(data.weeks) ? data.weeks : [];
  const sessionsById = new Map<number, string>();
  for (const week of weeks) for (const session of week.sessions || []) sessionsById.set(session.sessionId, `${week.weekNo}주 ${session.sessionNo}차시`);
  let body: ReactNode;
  if (!data) {
    if (!entry || !entry.error) body = <LoadingState title="출석을 불러오는 중입니다" />;
    else if (missing(entry.error)) body = <EmptyState title="출석을 볼 수 없습니다" body="이 과목의 구성원이 아니거나 과목을 찾을 수 없습니다." />;
    else body = <ErrorState title="출석을 불러오지 못했습니다" body={attendanceMessage(entry.error)} actions={<RetryButton reload={load} />} />;
  } else {
    const s = data.summary;
    const stats = s ? (
      <div className="att-stats">
        <span>{`전체 ${s.sessions}`}</span>
        <span>{`출석 ${s.present}`}</span>
        <span>{`지각 ${s.late}`}</span>
        <span>{`결석 ${s.absent}`}</span>
        <span>{`유고 ${s.excused}`}</span>
        <span>{`미처리 ${s.unmarked}`}</span>
        {data.settings && data.settings.latesPerAbsence > 0 ? <span>{`결석 환산 ${s.absenceEquivalent} (지각 ${data.settings.latesPerAbsence}번 = 결석 1번)`}</span> : null}
      </div>
    ) : null;
    if (!weeks.length) {
      body = <>{stats}<EmptyState title="등록된 차시가 없습니다" body="담당 교수가 출석 차시를 만들면 여기에 주차별로 표시됩니다." /></>;
    } else {
      const numbers = weeks.map((week) => week.weekNo);
      const selected = selectedWeek != null && numbers.includes(selectedWeek) ? selectedWeek : null;
      body = (
        <>
          {entry.error ? <p className="err" role="alert">{attendanceMessage(entry.error)}</p> : null}
          {stats}
          <div className="attend-weeks" role="group" aria-label="주차">
            {numbers.map((n) => (
              <button key={n} type="button" className={`week-num${n === selected ? " on" : ""}`} aria-pressed={n === selected ? "true" : "false"} onClick={() => pickAttendanceWeek(n)}>{String(n)}</button>
            ))}
          </div>
          {weeks.map((week) => (
            <article key={week.weekNo} className={`att-card${week.weekNo === selected ? " is-current" : ""}`} id={`att-${week.weekNo}`} tabIndex={-1}>
              <header className="att-card-h"><h2>{`${week.weekNo}주`}</h2></header>
              {(week.sessions || []).map((session) => <SessionRow key={session.sessionId} session={session} />)}
            </article>
          ))}
        </>
      );
    }
  }
  return (
    <>
      <SmartPanel courseId={courseId} sessionsById={sessionsById} />
      <div className="att-body" aria-live="polite">{body}</div>
    </>
  );
}

/** 학생 출석 화면(백엔드 모드). `head`는 learning.tsx의 제목 줄이다. */
export function remoteAttendance(screen: Screen, ctx: ScreenCtx, head: ReactNode): ReactNode {
  const course = courseOf(ctx);
  const courseId = courseIdFor(course.id);
  let rest: ReactNode;
  if (backendStatus() === "anon") rest = null;
  else if (!courseId) rest = <EmptyState title="이 과목은 ASTRA 서버에 없습니다" body="서버에 등록된 과목만 출석을 볼 수 있습니다." />;
  else rest = <AttendanceBody key={courseId} courseId={Number(courseId)} selectedWeek={ctx.state.attendanceWeek} />;
  return (
    <section className="bleed attend">
      {head}
      <p className="subhead">{`${course.ko} · ${course.code}`}</p>
      {rest}
    </section>
  );
}
