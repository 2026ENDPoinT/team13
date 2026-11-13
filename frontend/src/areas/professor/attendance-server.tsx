import { useEffect, useRef, useState, type ReactNode } from "react";
import { Badge, Button, EmptyState, ErrorState, Field, LoadingState, Pagination } from "../../ui/kit.tsx";
import { courseIdFor, backendState, backendStatus, roleIn, subscribe } from "../../shared/backend.ts";
import { backendPanel } from "../../shared/backend-prompt.tsx";
import { useResource, type Entry } from "../../shared/remote-cache.ts";
import { ApiError } from "../../shared/api.ts";
import { groupLists, groupsKey } from "../../shared/groups-remote.ts";
import { koDateTime } from "../../shared/home-remote.ts";
import { routeFor } from "../../shared/routes.ts";
import { QrCode, qrMatrix } from "../../shared/qr-code.tsx";
import { Link, navigate } from "../../router.tsx";
import { toast } from "../../store.ts";
import type { Course } from "../../data.ts";
import type { ScreenCtx } from "../../types.ts";
import {
  attendanceViews, attendanceSettings, rosters, activeRounds, roundViews,
  attendanceKey, rosterKey, roundKey, loadRoster, setRecord, fillRecords,
  updateAttendanceSettings, createSession, updateSession, deleteSession, openRound, finishRound,
  pollDelay, serverGap, sessionName, koDay, timeRange, STATUS, DECIDABLE, SOURCE, METHOD,
  ROUND_STATE, statusTone, attendanceMessage, ROSTER_PAGE, ROSTER_SORTS, rosterSort,
  type Id, type AttendanceSession, type AttendanceSettings, type AttendanceSummary,
  type AttendanceView, type AttendanceCell, type RosterStudent, type RoundView, type SessionBody,
} from "../../shared/attendance-remote.ts";

const pf = (screenId: string, extra: Record<string, string | number> = {}): string => routeFor("professor", "screen", { screenId, ...extra });
const param = (ctx: ScreenCtx, key: string): string => ctx.params?.get(key) || "";
const positive = (value: unknown): number | null => Number.isInteger(Number(value)) && Number(value) > 0 ? Number(value) : null;
const missing = (error: unknown): boolean => error instanceof ApiError && error.status === 404;
const conflict = (error: unknown): boolean => error instanceof ApiError && error.status === 409 && error.code === "version_conflict";
const allSessions = (data?: AttendanceView | null): AttendanceSession[] => data?.weeks?.flatMap((week) => week.sessions || []) || [];
const clock = (value?: string | null): string => /T(\d{2}:\d{2}:\d{2})/.exec(value || "")?.[1] || "";
const intOrNull = (value: string): number | null => value.trim() ? (Number.isInteger(Number(value)) ? Number(value) : Number.NaN) : null;
interface Notice { text: string; alert?: boolean }
const drafts = new Map<string, unknown>();
let reasonDraft = "";
function onSessionChange(reset: () => void): () => void {
  const current = backendState();
  let session = `${current.status}:${current.me?.userId || ""}`;
  const stop = subscribe((next) => {
    const id = `${next.status}:${next.me?.userId || ""}`;
    if (session === id) return;
    session = id;
    reset();
  });
  return () => { stop(); };
}
onSessionChange(() => { drafts.clear(); reasonDraft = ""; });

function useDraft<T>(key: string, initial: () => T): [T, (next: T, persist?: boolean) => void] {
  const [value, setValue] = useState<T>(() => drafts.has(key) ? drafts.get(key) as T : initial());
  const initialRef = useRef(initial);
  initialRef.current = initial;
  useEffect(() => {
    const stop = onSessionChange(() => setValue(initialRef.current()));
    return () => { stop(); };
  }, []);
  const update = (next: T, persist = true) => {
    if (persist) drafts.set(key, next);
    else drafts.delete(key);
    setValue(next);
  };
  return [value, update];
}
function Message({ notice }: { notice?: Notice | null }): ReactNode {
  return <p className={notice?.alert ? "err" : "pf-sub"} role={notice?.alert ? "alert" : "status"} aria-live="polite">{notice?.text || ""}</p>;
}
function Retry({ reload }: { reload: () => Promise<void> }): ReactNode {
  const [busy, setBusy] = useState(false);
  return <Button kind="ghost" disabled={busy} onClick={() => { setBusy(true); void reload().finally(() => setBusy(false)); }}>다시 불러오기</Button>;
}
function ResourceState({ entry, title, reload }: { entry: Entry<unknown> | null; title: string; reload: () => Promise<void> }): ReactNode {
  if (!entry?.error) return <LoadingState title={`${title} 불러오는 중입니다`} />;
  if (missing(entry.error)) return <EmptyState title={`${title} 볼 수 없습니다`} body="이 과목의 담당 교수가 아니거나 정보를 찾을 수 없습니다." />;
  return <ErrorState title={`${title} 불러오지 못했습니다`} body={attendanceMessage(entry.error)} actions={<Retry reload={reload} />} />;
}
function gate(course: Course): ReactNode {
  if (backendStatus() === "anon") return <div className="stack">{backendPanel({ text: "ASTRA 서버에 로그인하면 이 과목의 출석을 서버에서 관리할 수 있습니다." })}</div>;
  if (!courseIdFor(course.id)) return <div className="stack">{backendPanel()}<EmptyState title="이 과목은 ASTRA 서버에 없습니다" body="서버에 등록된 과목만 출석을 관리할 수 있습니다." /></div>;
  if (roleIn(course.id) !== "INSTRUCTOR") return <div className="stack">{backendPanel()}<p className="pf-sub">이 과목을 담당하는 교수 계정으로 로그인해야 출석을 관리할 수 있습니다.</p></div>;
  return null;
}
function useSessions(courseId: Id, scope: string) {
  const key = attendanceKey(courseId);
  const entry = useResource(attendanceViews, key, () => { void attendanceViews.load(key, courseId); });
  useEffect(() => { void attendanceViews.visit(scope, key, courseId); }, [courseId, key, scope]);
  return entry;
}

interface SettingsDraft { values: { lateAfterMinutes: string; roundMinutes: string; latesPerAbsence: string }; version: number; notice?: Notice; conflict?: boolean }
const settingsDraft = (data: AttendanceSettings): SettingsDraft => ({ values: { lateAfterMinutes: String(data.lateAfterMinutes), roundMinutes: String(data.roundMinutes), latesPerAbsence: String(data.latesPerAbsence) }, version: data.version });
function SettingsForm({ courseId, data }: { courseId: Id; data: AttendanceSettings }): ReactNode {
  const [draft, setDraft] = useDraft(`settings:${courseId}`, () => settingsDraft(data));
  const [busy, setBusy] = useState(false);
  const dirty = useRef(drafts.has(`settings:${courseId}`));
  useEffect(() => { if (!dirty.current) setDraft(settingsDraft(data), false); }, [data]);
  const change = (name: keyof SettingsDraft["values"], value: string) => { dirty.current = true; setDraft({ ...draft, values: { ...draft.values, [name]: value } }); };
  const submit = async () => {
    if (busy) return;
    const lateAfterMinutes = intOrNull(draft.values.lateAfterMinutes);
    const roundMinutes = intOrNull(draft.values.roundMinutes);
    const latesPerAbsence = intOrNull(draft.values.latesPerAbsence);
    if (lateAfterMinutes == null || roundMinutes == null || latesPerAbsence == null || [lateAfterMinutes, roundMinutes, latesPerAbsence].some(Number.isNaN)) {
      setDraft({ ...draft, notice: { text: "세 값을 모두 정수로 입력하세요.", alert: true } }); return;
    }
    if (lateAfterMinutes < 1 || lateAfterMinutes > 240 || roundMinutes < 1 || roundMinutes > 240 || latesPerAbsence < 0 || latesPerAbsence > 20) {
      setDraft({ ...draft, notice: { text: "지각 기준과 진행 시간은 1~240분, 지각 환산 횟수는 0~20입니다.", alert: true } }); return;
    }
    setBusy(true);
    try {
      const saved = await updateAttendanceSettings(courseId, { lateAfterMinutes, roundMinutes, latesPerAbsence, version: draft.version });
      dirty.current = false;
      setDraft({ ...settingsDraft(saved), notice: { text: "출석 설정을 저장했습니다." } }, false);
      toast("출석 설정을 저장했습니다");
    } catch (error) {
      let next = { ...draft, conflict: draft.conflict || conflict(error), notice: { text: attendanceMessage(error), alert: true } };
      if (error instanceof ApiError && error.status === 409) {
        await attendanceSettings.load(attendanceKey(courseId), courseId);
        const fresh = attendanceSettings.peek(attendanceKey(courseId));
        if (conflict(error) && fresh?.data && !fresh.error) next = { ...next, version: fresh.data.version };
      }
      setDraft(next);
    } finally { setBusy(false); }
  };
  return <form className="pf-form" onSubmit={(event) => { event.preventDefault(); void submit(); }}>
    <Field label="지각 기준 (회차를 연 뒤 분)"><input type="number" min={1} max={240} value={draft.values.lateAfterMinutes} onChange={(event) => change("lateAfterMinutes", event.target.value)} /></Field>
    <Field label="스마트출석 기본 진행 시간 (분)"><input type="number" min={1} max={240} value={draft.values.roundMinutes} onChange={(event) => change("roundMinutes", event.target.value)} /></Field>
    <Field label="지각 몇 번을 결석 1번으로 (0은 환산 안 함)"><input type="number" min={0} max={20} value={draft.values.latesPerAbsence} onChange={(event) => change("latesPerAbsence", event.target.value)} /></Field>
    <div className="pf-actions span-2"><Button kind="primary" type="submit" disabled={busy} aria-busy={busy}>{busy ? "저장하는 중…" : "설정 저장"}</Button></div>
    <div className="span-2"><Message notice={draft.notice} /></div>
    {draft.conflict ? <p className="pf-sub span-2">서버의 최신 값 · 버전 {data.version}: 지각 기준 {data.lateAfterMinutes}분 · 진행 시간 {data.roundMinutes}분 · {data.latesPerAbsence ? `지각 ${data.latesPerAbsence}번을 결석 1번으로` : "지각 환산 안 함"}</p> : null}
  </form>;
}
function Settings({ courseId }: { courseId: Id }): ReactNode {
  const key = attendanceKey(courseId);
  const entry = useResource(attendanceSettings, key, () => { void attendanceSettings.load(key, courseId); });
  useEffect(() => { void attendanceSettings.visit("pf-attend-settings", key, courseId); }, [courseId, key]);
  return <section className="sheet" aria-label="출석 설정"><h2>출석 설정</h2>{entry?.data ? <><Message notice={entry.error ? { text: attendanceMessage(entry.error), alert: true } : null} /><SettingsForm courseId={courseId} data={entry.data} /></> : <ResourceState entry={entry} title="설정을" reload={() => attendanceSettings.load(key, courseId)} />}</section>;
}
function summaryText(summary?: AttendanceSummary | null): string {
  if (!summary) return "—";
  return `출석 ${summary.present ?? 0} · 지각 ${summary.late ?? 0} · 결석 ${summary.absent ?? 0} · 유고 ${summary.excused ?? 0}${summary.absenceEquivalent != null && summary.absenceEquivalent !== summary.absent ? ` · 환산 결석 ${summary.absenceEquivalent}` : ""}`;
}
function Cell({ courseId, student, session, cell, reason, tell, requireReason, reload }: { courseId: Id; student: RosterStudent; session: AttendanceSession; cell?: AttendanceCell; reason: string; tell: (notice: Notice) => void; requireReason: () => void; reload: () => Promise<void> }): ReactNode {
  const [busy, setBusy] = useState(false);
  const ref = useRef<HTMLSelectElement>(null);
  const before = cell?.status || "unmarked";
  const label = `${student.name || student.studentNo || student.userId} ${sessionName(session)}`;
  const change = async (status: string) => {
    if (!reason.trim()) { requireReason(); return; }
    if (busy) return;
    setBusy(true); tell({ text: `${label}: 저장하는 중입니다.` });
    try {
      const saved = await setRecord(courseId, session.sessionId, student.userId, { status, reason: reason.trim(), version: cell?.version || 0 });
      rosters.patch((cachedKey) => cachedKey.startsWith(`${courseId}:`), (data) => {
        const row = data.students?.find((item) => item.userId === student.userId);
        if (row && !row.cells?.some((item) => item.sessionId === session.sessionId)) row.cells = [...(row.cells || []), saved];
      });
      // 신규 칸도 캐시에 추가되고 학생별 요약이 최신 상태가 되도록 다시 읽는다.
      await reload();
      tell({ text: `${label}: ${STATUS[saved.status] || saved.status}(으)로 저장했습니다.` });
    } catch (error) {
      tell({ text: `${label}: ${attendanceMessage(error)}`, alert: true });
      if (error instanceof ApiError && [409, 404, 428].includes(error.status)) await reload();
    } finally { setBusy(false); ref.current?.focus(); }
  };
  if (session.cancelled) return <span className="pf-meta">휴강</span>;
  return <select ref={ref} className={`pf-attend-select pf-attend-${statusTone(before)}`} aria-label={label} value={before} disabled={busy} aria-busy={busy} title={cell?.source ? `${SOURCE[cell.source] || cell.source}${cell.decidedAt ? ` · ${koDateTime(cell.decidedAt)}` : ""}${cell.reason ? ` · ${cell.reason}` : ""}` : cell?.reason || ""} onChange={(event) => void change(event.target.value)}>
    {before === "unmarked" ? <option value="unmarked" disabled>{STATUS.unmarked}</option> : null}
    {DECIDABLE.map((status) => <option key={status} value={status}>{STATUS[status]}</option>)}
  </select>;
}
function FillForm({ courseId, sessions, reason, tell, requireReason }: { courseId: Id; sessions: AttendanceSession[]; reason: string; tell: (notice: Notice) => void; requireReason: () => void }): ReactNode {
  const open = sessions.filter((session) => !session.cancelled);
  const [sessionId, setSessionId] = useState("");
  const [status, setStatus] = useState("absent");
  const [busy, setBusy] = useState(false);
  const selected = open.some((session) => String(session.sessionId) === sessionId) ? sessionId : String(open[0]?.sessionId || "");
  const submit = async () => {
    if (!reason.trim()) { requireReason(); return; }
    if (busy || !selected) return;
    setBusy(true);
    try { const result = await fillRecords(courseId, selected, { status, reason: reason.trim() }); tell({ text: `판정 전이던 ${result.updated}명을 ${STATUS[status]}(으)로 채웠습니다.` }); }
    catch (error) { tell({ text: attendanceMessage(error), alert: true }); }
    finally { setBusy(false); }
  };
  if (!open.length) return null;
  return <form className="pf-form pf-attend-fill" onSubmit={(event) => { event.preventDefault(); void submit(); }}><h3 className="span-2">판정 전 칸 한 번에 채우기</h3>
    <Field label="차시"><select value={selected} onChange={(event) => setSessionId(event.target.value)}>{open.map((session) => <option key={session.sessionId} value={session.sessionId}>{sessionName(session)}</option>)}</select></Field>
    <Field label="상태"><select value={status} onChange={(event) => setStatus(event.target.value)}>{DECIDABLE.map((value) => <option key={value} value={value}>{STATUS[value]}</option>)}</select></Field>
    <p className="pf-sub span-2">미처리인 칸만 채웁니다. 이미 판정한 칸은 그대로 두고, 위의 사유를 함께 남깁니다.</p><div className="pf-actions span-2"><Button type="submit" disabled={busy} aria-busy={busy}>{busy ? "채우는 중…" : "채우기"}</Button></div>
  </form>;
}
function General({ ctx, course, courseId }: { ctx: ScreenCtx; course: Course; courseId: Id }): ReactNode {
  const page = positive(param(ctx, "page")) || 1;
  const sort = rosterSort(param(ctx, "sort"));
  const key = rosterKey(courseId, page, sort);
  const entry = useResource(rosters, key, () => { void loadRoster(courseId, page, sort); });
  const [reason, setReason] = useState(reasonDraft);
  const [notice, setNotice] = useState<Notice>();
  const reasonRef = useRef<HTMLInputElement>(null);
  const reload = () => loadRoster(courseId, page, sort);
  useEffect(() => { void loadRoster(courseId, page, sort); }, [courseId, page, sort]);
  useEffect(() => {
    const stop = onSessionChange(() => setReason(""));
    return () => { stop(); };
  }, []);
  const requireReason = () => { setNotice({ text: "바꾸는 사유를 먼저 입력하세요. 사유는 변경 이력에 남습니다.", alert: true }); reasonRef.current?.focus(); };
  const data = entry?.data;
  const sessions = data?.sessions || [];
  const students = data?.students || [];
  const pages = Math.max(1, Math.ceil((data?.total || 0) / (data?.size || ROSTER_PAGE)));
  const hrefForPage = (n: number) => pf("pf_attend", { kj: course.id, ...(sort !== "studentNo" ? { sort } : {}), ...(n > 1 ? { page: String(n) } : {}) });
  return <div className="stack">{backendPanel()}<Settings courseId={courseId} /><section className="sheet" aria-label="출석부">
    <div className="pf-week-head"><h2>출석부</h2><div className="filters" role="group" aria-label="정렬">{ROSTER_SORTS.map(([value, label]) => <Link key={value} href={pf("pf_attend", { kj: course.id, ...(value !== "studentNo" ? { sort: value } : {}) })} className={`chip all${sort === value ? " on" : ""}`} aria-current={sort === value ? "true" : undefined}>{label}</Link>)}</div></div>
    <div className="pf-form"><div className="span-2"><Field label="바꾸는 사유 (칸을 바꿀 때 함께 남습니다)"><input ref={reasonRef} type="text" maxLength={200} value={reason} placeholder="예: 병원 진료 확인서 제출" onChange={(event) => { reasonDraft = event.target.value; setReason(event.target.value); }} /></Field></div></div><Message notice={notice} />
    {!data ? <ResourceState entry={entry} title="출석부를" reload={reload} /> : <>
      {entry.error ? <Message notice={{ text: attendanceMessage(entry.error), alert: true }} /> : null}
      {!sessions.length ? <EmptyState title="등록된 차시가 없습니다" body="휴강·보강 탭에서 차시를 먼저 만드세요." actions={<Link className="btn btn-secondary" href={pf("pf_attend_makeup", { kj: course.id })}>차시 만들기</Link>} /> : !students.length ? <EmptyState title={page > 1 && data.total > 0 ? "이 쪽에는 수강생이 없습니다" : "수강생이 없습니다"} body={page > 1 && data.total > 0 ? undefined : "이 과목에 등록된 수강생이 없습니다."} actions={page > 1 && data.total > 0 ? <Link className="btn btn-secondary" href={hrefForPage(1)}>첫 쪽으로</Link> : undefined} /> : <>
        <p className="pf-meta">수강생 {data.total}명 · {page}/{pages}쪽 · {ROSTER_SORTS.find(([value]) => value === data.sort)?.[1] || "학번순"}</p>
        <div className="table-wrap table-scroll pf-wide pf-attend"><table className="grid" aria-label="출석부"><thead><tr><th scope="col">학번</th><th scope="col">이름</th>{sessions.map((session) => <th scope="col" key={session.sessionId}>{sessionName(session)}{session.cancelled ? " (휴강)" : ""}</th>)}<th scope="col">요약</th></tr></thead>
          <tbody>{students.map((student) => <tr key={student.userId}><td data-label="학번">{student.studentNo || "—"}</td><td className="pf-lock" data-label="이름">{student.name || "—"}</td>{sessions.map((session) => <td key={session.sessionId} data-label={sessionName(session)}><Cell courseId={courseId} student={student} session={session} cell={student.cells?.find((cell) => cell.sessionId === session.sessionId)} reason={reason} tell={setNotice} requireReason={requireReason} reload={reload} /></td>)}<td data-label="요약">{summaryText(student.summary)}</td></tr>)}</tbody></table></div>
        <Pagination page={page} pages={pages} hrefForPage={hrefForPage} /><FillForm courseId={courseId} sessions={sessions} reason={reason} tell={setNotice} requireReason={requireReason} />
      </>}
    </>}
  </section></div>;
}

function OpenRoundForm({ course, courseId, sessions, settings }: { course: Course; courseId: Id; sessions: AttendanceSession[]; settings?: AttendanceSettings | null }): ReactNode {
  const open = sessions.filter((session) => !session.cancelled);
  const gkey = groupsKey(courseId);
  const groups = useResource(groupLists, gkey);
  const [sessionId, setSessionId] = useState("");
  const [method, setMethod] = useState("pin");
  const [minutes, setMinutes] = useState("");
  const [lateAfterMinutes, setLateAfterMinutes] = useState("");
  const [groupId, setGroupId] = useState("");
  const [notice, setNotice] = useState<Notice>();
  const [busy, setBusy] = useState(false);
  const selected = open.some((session) => String(session.sessionId) === sessionId) ? sessionId : String(open[0]?.sessionId || "");
  useEffect(() => { void groupLists.visit("pf-smart-groups", gkey, courseId); }, [courseId, gkey]);
  const submit = async () => {
    if (busy || !selected) return;
    const duration = intOrNull(minutes);
    const late = intOrNull(lateAfterMinutes);
    if (Number.isNaN(duration) || Number.isNaN(late)) { setNotice({ text: "진행 시간과 지각 기준은 분 단위 정수입니다.", alert: true }); return; }
    if ((duration != null && (duration < 1 || duration > 240)) || (late != null && (late < 1 || late > 240))) { setNotice({ text: "진행 시간과 지각 기준은 1~240분입니다.", alert: true }); return; }
    if (duration != null && late != null && late > duration) { setNotice({ text: "지각 기준은 진행 시간보다 길 수 없습니다.", alert: true }); return; }
    setBusy(true);
    try {
      const round = await openRound(courseId, selected, { method, minutes: duration, lateAfterMinutes: late, groupId: groupId ? Number(groupId) : null });
      void roundViews.load(roundKey(courseId, round.roundId), courseId, round.roundId);
      toast("스마트출석을 열었습니다");
      navigate(pf("pf_attend_smart", { kj: course.id, round: String(round.roundId) }));
    } catch (error) { setNotice({ text: attendanceMessage(error), alert: true }); }
    finally { setBusy(false); }
  };
  return <section className="sheet" aria-label="스마트출석 회차 열기"><h2>회차 열기</h2>{!open.length ? <EmptyState title="출석을 받을 차시가 없습니다" body="휴강·보강 탭에서 차시를 먼저 만드세요." actions={<Link className="btn btn-secondary" href={pf("pf_attend_makeup", { kj: course.id })}>차시 만들기</Link>} /> : <form className="pf-form" onSubmit={(event) => { event.preventDefault(); void submit(); }}>
    <Field label="차시"><select value={selected} onChange={(event) => setSessionId(event.target.value)}>{open.map((session) => <option key={session.sessionId} value={session.sessionId}>{sessionName(session)}{session.classDate ? ` · ${koDay(session.classDate)}` : ""}</option>)}</select></Field>
    <Field label="방식"><select value={method} onChange={(event) => setMethod(event.target.value)}><option value="pin">PIN (숫자)</option><option value="qr">QR (15초마다 바뀜)</option></select></Field>
    <Field label={`진행 시간 (분, 비우면 ${settings?.roundMinutes ?? "과목 설정"})`}><input type="number" min={1} max={240} value={minutes} onChange={(event) => setMinutes(event.target.value)} /></Field>
    <Field label={`지각 기준 (분, 비우면 ${settings?.lateAfterMinutes ?? "과목 설정"})`}><input type="number" min={1} max={240} value={lateAfterMinutes} onChange={(event) => setLateAfterMinutes(event.target.value)} /></Field>
    <div className="span-2"><Field label="대상"><select value={groupId} onChange={(event) => setGroupId(event.target.value)}><option value="">수강생 전체</option>{(groups?.data || []).map((group) => <option key={group.groupId} value={group.groupId}>{group.name} ({group.members.length}명)</option>)}</select></Field>
      {groups?.error ? <ErrorState title="대상 그룹을 불러오지 못했습니다" body={attendanceMessage(groups.error)} actions={<Retry reload={() => groupLists.load(gkey, courseId)} />} /> : null}
    </div>
    <div className="pf-actions span-2"><Button type="submit" kind="primary" disabled={busy} aria-busy={busy}>{busy ? "여는 중…" : "회차 열기"}</Button></div><div className="span-2"><Message notice={notice} /></div>
  </form>}</section>;
}
function ActiveList({ course, courseId, sessions }: { course: Course; courseId: Id; sessions: AttendanceSession[] }): ReactNode {
  const key = attendanceKey(courseId);
  const entry = useResource(activeRounds, key, () => { void activeRounds.load(key, courseId); });
  useEffect(() => { void activeRounds.visit("pf-smart-active", key, courseId); }, [courseId, key]);
  useEffect(() => {
    if (!entry?.data || entry.loading) return;
    let timer: ReturnType<typeof setTimeout>;
    let disposed = false;
    const tick = () => {
      if (disposed) return;
      if (document.visibilityState === "hidden") { timer = setTimeout(tick, 3000); return; }
      void activeRounds.load(key, courseId);
    };
    timer = setTimeout(tick, pollDelay(entry.data));
    return () => { disposed = true; clearTimeout(timer); };
  }, [entry, entry?.loading, entry?.data, entry?.error, courseId, key]);
  const rounds = entry?.data?.rounds || [];
  return <section className="sheet" aria-label="진행 중인 회차"><h2>진행 중인 회차</h2><div className="stack">
    {!entry?.data ? <ResourceState entry={entry} title="진행 중인 회차를" reload={() => activeRounds.load(key, courseId)} /> : <>
      {entry.error ? <Message notice={{ text: attendanceMessage(entry.error), alert: true }} /> : null}
      {rounds.length ? rounds.map((round) => { const session = sessions.find((item) => item.sessionId === round.sessionId); return <div className="pf-activity" key={round.roundId}><Link className="pf-activity-main" href={pf("pf_attend_smart", { kj: course.id, round: String(round.roundId) })}><Badge tone="success">{round.method === "qr" ? "QR" : "PIN"}</Badge><span className="pf-activity-copy"><b>{session ? sessionName(session) : `차시 ${round.sessionId}`}</b><span className="pf-meta">마감 {clock(round.endsAt)} · 확정 {round.counts?.confirmed ?? 0}/{round.counts?.targets ?? 0}</span></span></Link></div>; }) : <p className="pf-sub">진행 중인 회차가 없습니다.</p>}
    </>}
  </div></section>;
}
function RoundPanel({ course, courseId, roundId, sessions }: { course: Course; courseId: Id; roundId: number; sessions: AttendanceSession[] }): ReactNode {
  const key = roundKey(courseId, roundId);
  const entry = useResource(roundViews, key, () => { void roundViews.load(key, courseId, roundId); });
  const [notice, setNotice] = useState<Notice>();
  const [busy, setBusy] = useState<"close" | "cancel" | null>(null);
  const [armed, setArmed] = useState(false);
  useEffect(() => { void roundViews.visit("pf-smart-round", key, courseId, roundId); }, [courseId, key, roundId]);
  useEffect(() => {
    const round = entry?.data;
    if (!round || round.state !== "open" || entry.loading) return;
    const gap = round.method === "qr" && round.qrRefreshAt ? serverGap(round.qrRefreshAt, round.serverTime) : null;
    const wait = gap != null && gap > 0 ? Math.min(gap + 250, pollDelay(round)) : pollDelay(round);
    let timer: ReturnType<typeof setTimeout>;
    let disposed = false;
    const tick = () => {
      if (disposed) return;
      if (document.visibilityState === "hidden") { timer = setTimeout(tick, 3000); return; }
      void roundViews.load(key, courseId, roundId);
    };
    timer = setTimeout(tick, Math.max(1000, wait));
    return () => { disposed = true; clearTimeout(timer); };
  }, [entry, entry?.loading, entry?.data, entry?.error, entry?.data?.state, courseId, key, roundId]);
  const finish = async (how: "close" | "cancel") => {
    if (busy) return;
    if (how === "cancel" && !armed) { setArmed(true); return; }
    setBusy(how); setArmed(false);
    try {
      await finishRound(courseId, roundId, how);
      setNotice({ text: how === "close" ? "스마트출석을 마감했습니다." : "스마트출석을 취소했습니다. 이 회차로 남은 기록을 지웠습니다." });
      toast(how === "close" ? "스마트출석을 마감했습니다" : "스마트출석을 취소했습니다. 이 회차로 남은 기록을 지웠습니다");
    } catch (error) { setNotice({ text: attendanceMessage(error), alert: true }); await roundViews.load(key, courseId, roundId); }
    finally { setBusy(null); }
  };
  const round: RoundView | null = entry?.data || null;
  if (!round) return <section className="sheet pf-smart-round">{missing(entry?.error) ? <EmptyState title="회차를 찾을 수 없습니다" body="이 과목의 회차가 아닙니다." actions={<Link className="btn btn-secondary" href={pf("pf_attend_smart", { kj: course.id })}>회차 목록</Link>} /> : <ResourceState entry={entry} title="회차를" reload={() => roundViews.load(key, courseId, roundId)} />}</section>;
  const session = sessions.find((item) => item.sessionId === round.sessionId);
  const open = round.state === "open";
  const counts: [string, number | undefined][] = [["대상", round.counts?.targets], ["확정", round.counts?.confirmed], ["출석", round.counts?.present], ["지각", round.counts?.late], ["틀린 제출", round.counts?.failedAttempts], ["잠긴 학생", round.counts?.lockedStudents]];
  return <section className="sheet pf-smart-round" aria-label="스마트출석 회차"><div className="stack" aria-live="polite">
    {entry?.error ? <Message notice={{ text: attendanceMessage(entry.error), alert: true }} /> : null}
    <div className="pf-week-head"><h2>{session ? sessionName(session) : `차시 ${round.sessionId}`} · {round.method === "qr" ? "QR" : "PIN"}</h2><Badge tone={open ? "success" : round.state === "cancelled" ? "danger" : "neutral"}>{ROUND_STATE[round.state || ""] || round.state}</Badge></div>
    <p className="pf-sub">{[`연 시각 ${koDateTime(round.openedAt)}`, `지각 기준 ${clock(round.lateAt)}`, `마감 ${clock(round.endsAt)}`, round.closedAt ? `닫은 시각 ${koDateTime(round.closedAt)}` : "", round.groupId ? "대상: 그룹" : "대상: 수강생 전체"].filter(Boolean).join(" · ")}</p>
    {open && round.method === "pin" && round.pin ? <><p className="pf-smart-pin" aria-label={`PIN ${round.pin.split("").join(" ")}`}>{round.pin}</p><p className="pf-sub">PIN은 이 회차가 끝날 때까지 바뀌지 않습니다.</p></> : null}
    {open && round.method === "qr" && round.qrToken ? <>
      {qrMatrix(round.qrToken) ? <div className="pf-smart-qr"><QrCode text={round.qrToken} label="스마트출석 QR 코드" /></div> : <p className="err">QR 코드를 그리지 못했습니다. 아래 문자열을 보여 주세요.</p>}
      <p className="pf-meta pf-smart-token">문자열 {round.qrToken}</p><p className="pf-sub">다음 교체 {clock(round.qrRefreshAt)} (서버 시각). 교체되면 자동으로 새 코드를 보여 줍니다.</p>
    </> : null}
    {round.counts ? <div className="stat-row cols-3 pf-smart-counts">{counts.map(([label, value]) => <div className="stat pf-stat" key={label}><span className="tiny">{label}</span><b>{value ?? 0}</b></div>)}</div> : null}
    {open ? <><div className="pf-actions"><Button kind="primary" disabled={Boolean(busy)} aria-busy={busy === "close"} onClick={() => void finish("close")}>{busy === "close" ? "마감하는 중…" : "마감"}</Button><Button kind="danger" disabled={Boolean(busy)} aria-busy={busy === "cancel"} onClick={() => void finish("cancel")}>{busy === "cancel" ? "취소하는 중…" : armed ? "한 번 더 누르면 확정" : "취소"}</Button></div><p className="pf-sub">취소하면 이 회차로 확정된 출석 기록이 지워지고 학생 화면은 &quot;취소한 출석&quot;이 됩니다.</p></> : <div className="pf-actions"><Link className="btn btn-secondary" href={pf("pf_attend_smart", { kj: course.id })}>새 회차 열기</Link></div>}
    <Message notice={notice} />
  </div></section>;
}
function Smart({ ctx, course, courseId }: { ctx: ScreenCtx; course: Course; courseId: Id }): ReactNode {
  const entry = useSessions(courseId, "pf-attend-smart");
  const roundId = positive(param(ctx, "round"));
  const sessions = allSessions(entry?.data);
  return <div className="stack">{backendPanel()}{!entry?.data ? <ResourceState entry={entry} title="차시를" reload={() => attendanceViews.load(attendanceKey(courseId), courseId)} /> : <>
    {entry.error ? <Message notice={{ text: attendanceMessage(entry.error), alert: true }} /> : null}
    {roundId ? <RoundPanel key={`${courseId}:${roundId}`} course={course} courseId={courseId} roundId={roundId} sessions={sessions} /> : <><ActiveList course={course} courseId={courseId} sessions={sessions} /><OpenRoundForm course={course} courseId={courseId} sessions={sessions} settings={entry.data.settings} /></>}
  </>}</div>;
}

interface SessionDraft {
  values: { weekNo: string; sessionNo: string; classDate: string; startTime: string; endTime: string; method: string; cancelled: boolean; makeupOfSessionId: string };
  version: number;
  notice?: Notice;
  conflict?: boolean;
}
function newSessionDraft(editing: AttendanceSession | undefined, base: AttendanceSession | undefined, makeupOf: number | null): SessionDraft {
  return { values: {
    weekNo: String(editing?.weekNo || base?.weekNo || ""), sessionNo: String(editing?.sessionNo || base?.sessionNo || ""),
    classDate: editing?.classDate || "", startTime: editing?.startTime?.slice(0, 5) || "", endTime: editing?.endTime?.slice(0, 5) || "",
    method: editing?.method || "manual", cancelled: Boolean(editing?.cancelled), makeupOfSessionId: String(makeupOf || ""),
  }, version: editing?.version || 0 };
}
function SessionForm({ course, courseId, sessions, editing, makeupOf }: { course: Course; courseId: Id; sessions: AttendanceSession[]; editing?: AttendanceSession; makeupOf: number | null }): ReactNode {
  const draftKey = `session:${courseId}:${editing?.sessionId || (makeupOf ? `makeup-${makeupOf}` : "new")}`;
  const base = sessions.find((session) => session.sessionId === makeupOf);
  const [draft, setDraft] = useDraft(draftKey, () => newSessionDraft(editing, base, makeupOf));
  const [busy, setBusy] = useState(false);
  const dirty = useRef(drafts.has(draftKey));
  useEffect(() => { if (!dirty.current) setDraft(newSessionDraft(editing, base, makeupOf), false); }, [editing, base, makeupOf]);
  const change = <K extends keyof SessionDraft["values"],>(name: K, value: SessionDraft["values"][K]) => { dirty.current = true; setDraft({ ...draft, values: { ...draft.values, [name]: value } }); };
  const submit = async () => {
    if (busy) return;
    const value = draft.values;
    const weekNo = intOrNull(value.weekNo);
    const sessionNo = intOrNull(value.sessionNo);
    const errors: string[] = [];
    if (weekNo == null || !Number.isInteger(weekNo) || weekNo < 1 || weekNo > 60) errors.push("주차는 1부터 60 사이입니다");
    if (sessionNo == null || !Number.isInteger(sessionNo) || sessionNo < 1 || sessionNo > 20) errors.push("차시 번호는 1부터 20 사이입니다");
    if (value.startTime && value.endTime && value.startTime >= value.endTime) errors.push("시작은 끝보다 앞이어야 합니다");
    if (errors.length || weekNo == null || sessionNo == null) { setDraft({ ...draft, notice: { text: errors.join(". "), alert: true } }); return; }
    const body: SessionBody = { weekNo, sessionNo, classDate: value.classDate || null, startTime: value.startTime || null, endTime: value.endTime || null, method: value.method,
      ...(editing ? { cancelled: value.cancelled } : { makeupOfSessionId: positive(value.makeupOfSessionId) }),
    };
    setBusy(true);
    try {
      if (editing) await updateSession(courseId, editing.sessionId, { ...body, version: draft.version });
      else await createSession(courseId, body);
      drafts.delete(draftKey);
      dirty.current = false;
      setDraft({ ...newSessionDraft(undefined, undefined, null), notice: { text: editing ? "차시를 수정했습니다." : "차시를 등록했습니다." } }, false);
      drafts.delete(draftKey);
      navigate(pf("pf_attend_makeup", { kj: course.id }));
      toast(editing ? "차시를 수정했습니다" : "차시를 등록했습니다");
    } catch (error) {
      let next: SessionDraft = { ...draft, conflict: draft.conflict || conflict(error), notice: { text: attendanceMessage(error), alert: true } };
      if (error instanceof ApiError && [409, 404].includes(error.status)) {
        await attendanceViews.load(attendanceKey(courseId), courseId);
        const fresh = attendanceViews.peek(attendanceKey(courseId));
        const session = allSessions(fresh?.data).find((item) => item.sessionId === editing?.sessionId);
        if (conflict(error) && session && !fresh?.error) next = { ...next, version: session.version || 0 };
      }
      setDraft(next);
      if (missing(error) && editing) drafts.delete(draftKey);
    } finally { setBusy(false); }
  };
  const cancelledSessions = sessions.filter((session) => session.cancelled && !session.makeup);
  return <section className="sheet" aria-label={editing ? "차시 수정" : "차시 등록"}><h2>{editing ? `차시 수정 · ${sessionName(editing)}` : makeupOf ? "보강 차시 등록" : "차시 등록"}</h2><form className="pf-form" onSubmit={(event) => { event.preventDefault(); void submit(); }}>
    <Field label="주차"><input type="number" min={1} max={60} value={draft.values.weekNo} onChange={(event) => change("weekNo", event.target.value)} /></Field>
    <Field label="차시 번호"><input type="number" min={1} max={20} value={draft.values.sessionNo} onChange={(event) => change("sessionNo", event.target.value)} /></Field>
    <Field label="수업일"><input type="date" value={draft.values.classDate} onChange={(event) => change("classDate", event.target.value)} /></Field>
    <Field label="방식"><select value={draft.values.method} onChange={(event) => change("method", event.target.value)}>{Object.entries(METHOD).map(([method, label]) => <option key={method} value={method}>{label}</option>)}</select></Field>
    <Field label="시작"><input type="time" value={draft.values.startTime} onChange={(event) => change("startTime", event.target.value)} /></Field>
    <Field label="끝"><input type="time" value={draft.values.endTime} onChange={(event) => change("endTime", event.target.value)} /></Field>
    {editing ? <label className="choice pf-check span-2"><input type="checkbox" checked={draft.values.cancelled} onChange={(event) => change("cancelled", event.target.checked)} /><span>휴강</span></label> : <div className="span-2"><Field label="보강 대상 (휴강한 차시)"><select value={draft.values.makeupOfSessionId} onChange={(event) => change("makeupOfSessionId", event.target.value)}><option value="">보강이 아님</option>{cancelledSessions.map((session) => <option key={session.sessionId} value={session.sessionId}>{sessionName(session)} 보강</option>)}</select></Field></div>}
    <div className="pf-actions span-2"><Button type="submit" kind="primary" disabled={busy} aria-busy={busy}>{busy ? "저장하는 중…" : editing ? "수정 저장" : "차시 등록"}</Button>{editing || makeupOf ? <Link className="btn btn-ghost" href={pf("pf_attend_makeup", { kj: course.id })}>새 차시 쓰기</Link> : null}</div><div className="span-2"><Message notice={draft.notice} /></div>
    {draft.conflict && editing ? <p className="pf-sub span-2">서버의 최신 내용 · 버전 {editing.version}: {sessionName(editing)} · {[koDay(editing.classDate), timeRange(editing)].filter(Boolean).join(" ") || "일시 미정"} · {METHOD[editing.method || "manual"] || editing.method}{editing.cancelled ? " · 휴강" : ""}</p> : null}
  </form></section>;
}
function countsLine(counts?: Record<string, number>): string {
  const entries = Object.entries(counts || {}).filter(([, count]) => count);
  return entries.length ? entries.map(([status, count]) => `${STATUS[status] || status} ${count}`).join(" · ") : "기록 없음";
}
function SessionRow({ course, courseId, session, sessions, tell }: { course: Course; courseId: Id; session: AttendanceSession; sessions: AttendanceSession[]; tell: (notice: Notice) => void }): ReactNode {
  const [busy, setBusy] = useState<"cancel" | "delete" | null>(null);
  const [armed, setArmed] = useState(false);
  const of = sessions.find((item) => item.sessionId === session.makeupOfSessionId);
  const edit = pf("pf_attend_makeup", { kj: course.id, edit: String(session.sessionId) });
  const work = async (how: "cancel" | "delete") => {
    if (busy) return;
    if (how === "delete" && !armed) { setArmed(true); return; }
    setArmed(false); setBusy(how);
    try {
      if (how === "delete") {
        await deleteSession(courseId, session.sessionId, session.version || 0);
        drafts.delete(`session:${courseId}:${session.sessionId}`);
        tell({ text: "차시를 삭제했습니다." });
        toast("차시를 삭제했습니다");
      } else {
        await updateSession(courseId, session.sessionId, { weekNo: session.weekNo, sessionNo: session.sessionNo, classDate: session.classDate || null, startTime: session.startTime, endTime: session.endTime, method: session.method || "manual", cancelled: !session.cancelled, version: session.version || 0 });
        tell({ text: session.cancelled ? "휴강을 해제했습니다." : "휴강으로 바꿨습니다." });
        toast(session.cancelled ? "휴강을 해제했습니다" : "휴강으로 바꿨습니다");
      }
    } catch (error) {
      tell({ text: `${sessionName(session)}: ${attendanceMessage(error)}`, alert: true });
      if (error instanceof ApiError && [409, 404].includes(error.status)) await attendanceViews.load(attendanceKey(courseId), courseId);
    } finally { setBusy(null); }
  };
  return <div className="pf-activity"><Link className="pf-activity-main" href={edit}>
    <Badge tone={session.cancelled ? "danger" : session.makeup ? "success" : "neutral"}>{session.cancelled ? "휴강" : session.makeup ? "보강" : METHOD[session.method || "manual"] || session.method}</Badge>
    <span className="pf-activity-copy"><b>{sessionName(session)}</b><span className="pf-meta">{[[koDay(session.classDate), timeRange(session)].filter(Boolean).join(" ") || "일시 미정", of ? `${sessionName(of)}의 보강` : "", countsLine(session.counts), `버전 ${session.version || 0}`].filter(Boolean).join(" · ")}</span></span>
  </Link><div className="pf-activity-actions"><Link className="btn btn-ghost" href={edit} aria-label={`${sessionName(session)} 수정`}>수정</Link>
    <Button kind="ghost" disabled={Boolean(busy)} aria-busy={busy === "cancel"} aria-label={`${sessionName(session)} ${session.cancelled ? "휴강 해제" : "휴강으로 바꾸기"}`} onClick={() => void work("cancel")}>{busy === "cancel" ? "바꾸는 중…" : session.cancelled ? "휴강 해제" : "휴강으로"}</Button>
    {session.cancelled && !session.makeup ? <Link className="btn btn-ghost" href={pf("pf_attend_makeup", { kj: course.id, makeup: String(session.sessionId) })}>보강 등록</Link> : null}
    <Button kind="ghost" disabled={Boolean(busy)} aria-busy={busy === "delete"} aria-label={`${sessionName(session)} 삭제`} onClick={() => void work("delete")}>{busy === "delete" ? "삭제하는 중…" : armed ? "한 번 더 누르면 확정" : "삭제"}</Button>
  </div></div>;
}
function Sessions({ ctx, course, courseId }: { ctx: ScreenCtx; course: Course; courseId: Id }): ReactNode {
  const entry = useSessions(courseId, "pf-attend-sessions");
  const sessions = allSessions(entry?.data);
  const editId = positive(param(ctx, "edit"));
  const makeupOf = positive(param(ctx, "makeup"));
  const editing = sessions.find((session) => session.sessionId === editId);
  const [notice, setNotice] = useDraft<Notice>(`session-list:${courseId}`, () => ({ text: "" }));
  return <div className="stack">{backendPanel()}{!entry?.data ? <ResourceState entry={entry} title="차시를" reload={() => attendanceViews.load(attendanceKey(courseId), courseId)} /> : <>
    {entry.error ? <Message notice={{ text: attendanceMessage(entry.error), alert: true }} /> : null}
    {editId && !editing ? <p className="pf-sub">고친 차시를 찾지 못했습니다. 삭제되었을 수 있습니다.</p> : null}
    <SessionForm key={`${courseId}:${editing?.sessionId || (makeupOf ? `makeup-${makeupOf}` : "new")}`} course={course} courseId={courseId} sessions={sessions} editing={editing} makeupOf={editing ? null : makeupOf} />
    <section className="sheet" aria-label="차시 목록"><h2>차시</h2><Message notice={notice} />{sessions.length ? <div className="stack">{sessions.map((session) => <SessionRow key={session.sessionId} course={course} courseId={courseId} session={session} sessions={sessions} tell={setNotice} />)}</div> : <p className="pf-sub">아직 등록된 차시가 없습니다.</p>}</section>
  </>}</div>;
}
export function serverAttendGeneral(ctx: ScreenCtx, course: Course): ReactNode {
  return gate(course) || <General key={course.id} ctx={ctx} course={course} courseId={courseIdFor(course.id) as Id} />;
}
export function serverAttendSmart(ctx: ScreenCtx, course: Course): ReactNode {
  return gate(course) || <Smart key={course.id} ctx={ctx} course={course} courseId={courseIdFor(course.id) as Id} />;
}
export function serverAttendSessions(ctx: ScreenCtx, course: Course): ReactNode {
  return gate(course) || <Sessions key={course.id} ctx={ctx} course={course} courseId={courseIdFor(course.id) as Id} />;
}
