// 교수 강의실의 서버 화면(백엔드 `ready`, 서버에 있는 과목): 주별 보기와 수업활동 보기(`pf_room_week`·`pf_room_activity`),
// 활동 등록(`pf_week_add`)의 유형 고르기. 활동은 `shared/course-room-remote.ts`가 서버 주차 순서에 유형별 목록을 붙여
// 만든다. 줄마다 그 활동을 관리하는 서버 화면으로 가고, 공개 전환과 삭제(삭제 표시)는 마지막으로 읽은 버전으로
// 보낸다. 줄의 안내와 삭제 확인은 세션이 바뀌면 버리는 메모리 맵에 두어 다시 그려도 남는다.
import { useEffect, useRef, useState, type ReactNode } from "react";
import { Button, Badge, EmptyState, LoadingState, ErrorState, type BadgeTone } from "../../ui/kit.tsx";
import { Link, navigate } from "../../router.tsx";
import { getState, save, render, toast } from "../../store.ts";
import type { Course } from "../../data.ts";
import type { ScreenCtx } from "../../types.ts";
import { backendStatus, courseIdFor, roleIn, subscribe } from "../../shared/backend.ts";
import { SIGNED_OUT } from "../../shared/backend-prompt.tsx";
import { routeFor } from "../../shared/routes.ts";
import { koDate, koDateTime } from "../../shared/home-remote.ts";
import {
  ROOM_TYPES, DELETABLE, useRoom, reloadRoom, setPublished, removeActivity, forgetRow, isStale, isGone, roomMessage,
  type RoomEntry, type RoomItem, type RoomRow, type RoomWeek,
} from "../../shared/course-room-remote.ts";
import { PHASE as ASSIGNMENT_PHASE } from "../../shared/assignments-remote.ts";
import { PHASE as QUIZ_PHASE } from "../../shared/quizzes-remote.ts";
import { PHASE as SURVEY_PHASE } from "../../shared/surveys-remote.ts";
import { lectureRef, lectureItemsOf, ACTIVITY_PHASE } from "../../shared/lectures-remote.ts";
import { serverRef as assignmentRef } from "../../shared/assignments-remote.ts";
import { quizRef } from "../../shared/quizzes-remote.ts";
import { surveyRef } from "../../shared/surveys-remote.ts";
import { presetLectureWeek } from "./lectures-server.tsx";

const pf = (screenId: string, extra?: Record<string, string>) => routeFor("professor", "screen", { screenId, ...(extra || {}) });
const MAX_WEEK = 60;

// "<courseId>:<type>:<id>" -> { text, alert }, 진행 중인 명령, 한 번 누른 삭제
const notes = new Map<string, { text: string; alert: boolean }>();
const busy = new Set<string>();
const armed = new Set<string>();
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  notes.clear();
  busy.clear();
  armed.clear();
});

/** 이 과목의 강의실을 서버로 보이는지. `anon`은 로그인 안내만 보인다. 서버에 없는 과목은 샘플 강의실 그대로다. */
export function serverRoom(course: Course | null | undefined): boolean {
  const status = backendStatus();
  return Boolean(course) && (status === "anon" || (status === "ready" && Boolean(courseIdFor(course!.id))));
}

function gate(course: Course, text: string): ReactNode {
  if (backendStatus() === "anon") return SIGNED_OUT;
  if (roleIn(course.id) !== "INSTRUCTOR") return <div className="stack"><p className="pf-sub">이 과목을 담당하는 교수 계정으로 로그인해야 강의실 활동을 관리할 수 있습니다.</p></div>;
  return null;
}

const rowKey = (courseId: number | string, row: RoomRow) => `${courseId}:${row.type}:${row.id ?? `a${row.activityId}`}`;

/** A list field the room row type does not declare (counts per type); undefined when absent. */
function extra(item: RoomItem, name: string): unknown {
  return Object.getOwnPropertyDescriptor(item, name)?.value;
}

function periodText(row: RoomRow) {
  if (row.closeAt) return `${row.openAt ? `${koDateTime(row.openAt)} ~ ` : "~ "}${koDateTime(row.closeAt)}`;
  if (row.openAt) return `${koDateTime(row.openAt)}부터`;
  return "기간 없음";
}

function countText(row: RoomRow) {
  const item = row.item;
  if (row.type === "lecture_weeks") {
    if (!row.detail) return "콘텐츠 정보를 불러오는 중";
    const items = lectureItemsOf(row.detail);
    const by = (state: string) => items.filter((entry) => entry.state === state).length;
    return [`콘텐츠 ${items.length}개`, `재생 가능 ${by("ready")}`, by("processing") ? `변환 중 ${by("processing")}` : "", by("failed") ? `재생 불가 ${by("failed")}` : ""].filter(Boolean).join(" · ");
  }
  if (!item) return row.type in ROOM_TYPES ? "목록 정보를 불러오는 중" : "";
  const n = (name: string) => String(extra(item, name) ?? 0);
  if (row.type === "lecture_material") {
    const files = extra(item, "files");
    return `파일 ${Array.isArray(files) ? files.length : 0}개`;
  }
  if (row.type === "report") return `제출 ${n("submittedCount")} · 지각 ${n("lateCount")}`;
  if (row.type === "test") {
    if (extra(item, "examType") === "offline") return "오프라인 시험";
    return `응시 중 ${n("inProgressCount")} · 종료 ${n("finishedCount")} · 채점 대기 ${n("awaitingGradingCount")}`;
  }
  if (row.type === "survey") return `${extra(item, "anonymous") ? "익명" : "실명"} · ${n("questionCount")}문항 · 응답 ${n("respondentCount")}명`;
  return "";
}

/** 공개 여부와 서버 시계로 정한 진행 단계(`phase`). */
function StateBadge({ row }: { row: RoomRow }) {
  const badge = (text: string, tone: BadgeTone) => <Badge tone={tone}>{text}</Badge>;
  if (!row.published) return badge("비공개", "neutral");
  const phase = row.phase;
  const tone = phase === "open" || phase === "late" ? "success" : "neutral";
  if (row.type === "report" && phase) return badge(ASSIGNMENT_PHASE[phase] || phase, tone);
  if (row.type === "test" && phase) return badge(QUIZ_PHASE[phase] || phase, tone);
  if (row.type === "survey" && phase) return badge(SURVEY_PHASE[phase] || phase, tone);
  if (row.type === "lecture_weeks" && row.detail) {
    const items = lectureItemsOf(row.detail);
    if (items.some((item) => item.state === "failed")) return badge("재생 불가 콘텐츠", "danger");
    if (items.some((item) => item.state === "processing")) return badge("변환 중", "neutral");
  }
  if (phase && phase !== "open") return badge(ACTIVITY_PHASE[phase] || phase, "neutral");
  return badge("공개", "success");
}

/** 줄의 본문 링크: 그 활동을 관리하는 서버 화면. */
function detailHref(row: RoomRow, course: Course): string | null {
  const kj = course.id;
  if (row.type === "lecture_weeks") return pf("pf_lecture", { kj, n: lectureRef(row.activityId ?? "") });
  if (row.type === "lecture_material") return pf("pf_pdf", { kj, material: String(row.activityId) });
  if (row.type === "report") return row.id ? pf("pf_submissions", { kj, n: assignmentRef(row.id) }) : pf("pf_report_status", { kj });
  if (row.type === "test") return row.id ? pf("pf_quiz", { kj, n: quizRef(row.id) }) : pf("pf_exam_status", { kj });
  if (row.type === "survey") return row.id ? pf("pf_discuss", { kj, n: surveyRef(row.id) }) : pf("pf_discuss", { kj });
  return null;
}

function editHref(row: RoomRow, course: Course): string | null {
  const kj = course.id;
  if (row.type === "lecture_weeks") return pf("pf_contents", { kj, open: String(row.activityId) });
  if (row.type === "lecture_material") return pf("pf_material_write", { kj, open: String(row.activityId) });
  if (!row.id) return null;
  if (row.type === "report") return pf("pf_report_create", { kj, edit: String(row.id) });
  if (row.type === "test") return pf("pf_exam_create", { kj, edit: String(row.id) });
  if (row.type === "survey") return pf("pf_discuss", { kj, edit: String(row.id) });
  return null;
}

const DELETE_WHAT: Record<string, string> = {
  lecture_weeks: "이 강의와 영상이 학생과 교수 모두에게서 바로 사라지고, 학습 기록은 남습니다.",
  lecture_material: "이 자료와 첨부가 학생 강의실과 학습실에서 바로 사라지고, 학습 기록은 남습니다.",
  report: "이 과제와 제출물이 학생과 교수 모두에게서 바로 사라지고, 성적부의 항목도 빠집니다. 제출 파일과 점수 기록은 서버에 남습니다.",
  test: "이 시험과 응시가 학생과 교수 모두에게서 바로 사라지고, 성적부의 항목도 빠집니다. 응시와 점수 기록은 서버에 남습니다.",
  survey: "이 설문과 결과가 학생과 교수 모두에게서 바로 사라집니다. 응답 기록은 서버에 남습니다.",
};

async function settle(courseId: number, row: RoomRow) {
  forgetRow(courseId, row);
  await reloadRoom(courseId).catch(() => {});
  render();
}

/** 공개 여부만 바꾼 전체 수정. */
async function roomPublish(courseId: number, key: string, row: RoomRow) {
  if (busy.has(key)) return;
  const next = !row.published;
  busy.add(key);
  armed.delete(key);
  notes.delete(key);
  render();
  try {
    await setPublished(courseId, row, next);
    toast(next ? `「${row.title}」을 공개했습니다. 학생 강의실에 보입니다.` : `「${row.title}」을 비공개로 바꿨습니다. 학생 강의실에서 보이지 않습니다.`);
  } catch (error) {
    if (isStale(error)) notes.set(key, { text: "다른 곳에서 이 활동을 먼저 고쳤습니다. 아무것도 바꾸지 않고 최신 내용을 불러왔으니 확인한 뒤 다시 누르세요.", alert: true });
    else if (isGone(error)) toast("이미 삭제되었거나 찾을 수 없는 활동입니다. 목록을 새로 불러왔습니다.");
    else notes.set(key, { text: roomMessage(row.type, error), alert: true });
  } finally {
    busy.delete(key);
    await settle(courseId, row);
  }
}

/** 두 번 누르면 활동을 지운다(삭제 표시, 읽은 `version`·`revision`을 `If-Match`로). */
async function roomDelete(courseId: number, key: string, row: RoomRow) {
  if (busy.has(key) || !DELETABLE.has(row.type)) return;
  if (!armed.has(key)) {
    armed.add(key);
    notes.set(key, { text: `한 번 더 누르면 삭제합니다. ${DELETE_WHAT[row.type]} 되돌릴 수 없습니다.`, alert: true });
    render();
    return;
  }
  busy.add(key);
  render();
  try {
    await removeActivity(courseId, row);
    armed.delete(key);
    notes.delete(key);
    toast(`「${row.title}」을 삭제했습니다. 학생 강의실에서도 사라졌습니다.`);
  } catch (error) {
    armed.delete(key);
    if (isStale(error)) notes.set(key, { text: "다른 곳에서 이 활동을 먼저 고쳤습니다. 지우지 않고 최신 내용을 불러왔으니 확인한 뒤 다시 삭제하세요.", alert: true });
    else if (isGone(error)) {
      notes.delete(key);
      toast("이미 삭제된 활동입니다. 목록을 새로 불러왔습니다.");
    } else notes.set(key, { text: roomMessage(row.type, error), alert: true });
  } finally {
    busy.delete(key);
    await settle(courseId, row);
  }
}

function ActivityRow({ row, course, courseId, stale }: { row: RoomRow; course: Course; courseId: number; stale: boolean }) {
  const key = rowKey(courseId, row);
  const meta = ROOM_TYPES[row.type];
  const href = detailHref(row, course);
  const label = meta ? meta.label : "기타";
  const body = (
    <>
      <span className={`pill ${meta ? meta.chip : "post"}`}>{label}</span>
      <span className="pf-activity-copy">
        <b>{row.title}</b>
        <span className="pf-meta">{[`${row.weekNo}주차`, periodText(row), countText(row)].filter(Boolean).join(" · ")}</span>
        {meta ? null : <span className="pf-meta">이 유형은 아직 관리 화면이 없습니다.</span>}
      </span>
      <StateBadge row={row} />
    </>
  );
  const working = busy.has(key);
  const isArmed = armed.has(key);
  const edit = meta ? editHref(row, course) : null;
  const canPublish = Boolean(meta) && (row.type === "lecture_weeks" || row.type === "lecture_material" || Boolean(row.id));
  const canDelete = DELETABLE.has(row.type) && (row.type === "lecture_weeks" || row.type === "lecture_material" || Boolean(row.id));
  const said = notes.get(key);
  return (
    <div className="pf-activity" data-activity={key}>
      {href
        ? <Link className="pf-activity-main" href={href} data-activity={key}>{body}</Link>
        : <div className="pf-activity-main" data-activity={key}>{body}</div>}
      {edit || canPublish || canDelete ? (
        <div className="pf-activity-actions">
          {edit ? <Link className="btn btn-ghost" href={edit} aria-label={`${row.title} 수정`}>수정</Link> : null}
          {canPublish ? (
            <Button
              kind="ghost"
              disabled={working || stale}
              aria-label={`${row.title} ${row.published ? "비공개로 바꾸기" : "공개하기"}`}
              aria-busy={working ? "true" : "false"}
              onClick={() => { void roomPublish(courseId, key, row); }}
            >
              {working && !isArmed ? "바꾸는 중…" : row.published ? "비공개로" : "공개하기"}
            </Button>
          ) : null}
          {canDelete ? (
            <Button
              kind={isArmed ? "danger" : "ghost"}
              disabled={working || stale}
              aria-label={`${row.title} 삭제`}
              aria-busy={working && isArmed ? "true" : "false"}
              onClick={() => { void roomDelete(courseId, key, row); }}
            >
              {working && isArmed ? "삭제하는 중…" : isArmed ? "한 번 더 누르면 삭제" : "삭제"}
            </Button>
          ) : null}
        </div>
      ) : null}
      {said ? <p className={said.alert ? "err" : "pf-sub"} role={said.alert ? "alert" : "status"}>{said.text}</p> : null}
    </div>
  );
}

function Retry({ courseId }: { courseId: number }) {
  const [pressed, setPressed] = useState(false);
  return <Button kind="ghost" disabled={pressed} onClick={() => { setPressed(true); void reloadRoom(courseId); }}>다시 불러오기</Button>;
}

function weekHead(week: RoomWeek) {
  const range = week.startsOn ? ` · ${koDate(week.startsOn)}${week.endsOn ? ` – ${koDate(week.endsOn)}` : ""}` : "";
  return `${week.weekNo}주차${week.title ? ` · ${week.title}` : ""}${range}`;
}

const chipOf = (row: RoomRow) => (ROOM_TYPES[row.type] ? ROOM_TYPES[row.type].chip : "etc");

const PART_TYPE: Record<string, string> = { lectures: "lecture_weeks", assignments: "report", quizzes: "test", surveys: "survey", materials: "lecture_material" };

/** professor.tsx helpers the room view puts in its toolbar and stat row. */
export interface RoomParts {
  stat(label: string, value: string): ReactNode;
  chipRow(ctx: ScreenCtx, screenId: string, options: [string, string, string][], param: string): ReactNode;
  finder(ctx: ScreenCtx, label: string): ReactNode;
}

/** 강의실 본문(배너와 주별/수업활동 탭 아래). `parts`는 professor.tsx의 `stat`·`chipRow`·`finder`다. */
export function serverRoomView(mode: string, ctx: ScreenCtx, course: Course, parts: RoomParts): ReactNode {
  const blocked = gate(course, "ASTRA 서버에 로그인하면 이 과목의 주차와 활동을 서버에서 불러옵니다.");
  if (blocked) return blocked;
  return <RoomView mode={mode} ctx={ctx} course={course} courseId={Number(courseIdFor(course.id))} parts={parts} />;
}

function RoomView({ mode, ctx, course, courseId, parts }: { mode: string; ctx: ScreenCtx; course: Course; courseId: number; parts: RoomParts }) {
  const slot = useRef<HTMLDivElement>(null);
  const last = useRef<RoomEntry | null>(null);
  const { stat, chipRow, finder } = parts;
  const params = ctx.params;
  const fresh = useRoom(courseId);
  // 명령 뒤 다시 받는 동안은 지난 목록을 그대로 둔다(캐시를 버린 직후 빈 화면으로 깜박이지 않게).
  // 그 행은 보이기만 한다: 공개 전환과 삭제는 지난 version·revision을 보내게 되므로 새 목록이 올 때까지 끈다.
  const room = !fresh.weeks && !fresh.error && last.current ? last.current : fresh;
  const stale = room !== fresh;
  if (room.weeks && !stale) last.current = room;

  let content: ReactNode;
  if (!room.weeks) {
    if (room.error && room.error.part === "weeks") {
      content = isGone(room.error.error)
        ? <EmptyState title="이 과목의 강의실을 볼 수 없습니다" body="담당 교수 계정인지, 과목이 아직 있는지 확인하세요." />
        : <ErrorState title="강의실 활동을 불러오지 못했습니다" body={roomMessage("weeks", room.error.error)} actions={<Retry courseId={courseId} />} />;
    } else content = <LoadingState title="강의실 활동을 불러오는 중입니다" />;
  } else {
    const partial = room.error
      ? (
        <div className="stack">
          <p className="err" role="alert">{`일부 목록을 불러오지 못해 개수가 빠질 수 있습니다. ${roomMessage(PART_TYPE[room.error.part] || "weeks", room.error.error)}`}</p>
          <Retry courseId={courseId} />
        </div>
      )
      : null;
    const rows = room.rows;
    const widgets = (
      <div className="stat-row">
        {stat("활동", String(rows.length))}
        {stat("비공개", String(rows.filter((row) => !row.published).length))}
        {/* 자료는 마감이 없어 시작한 뒤 늘 open이므로 세지 않는다. */}
        {stat("진행 중", String(rows.filter((row) => row.published && row.type !== "lecture_material" && (row.phase === "open" || row.phase === "late")).length))}
        {stat("주차", String(room.weeks.length))}
      </div>
    );
    const guide = <p className="pf-sub">공개 전환과 삭제는 마지막으로 불러온 버전으로 보냅니다. 그 사이 다른 곳에서 고쳤으면 서버가 거절하고 최신 내용을 다시 불러옵니다. 삭제는 학생과 교수 모두에게서 활동을 지우고, 학습·제출·응시·응답 기록은 서버에 남깁니다.</p>;
    if (mode === "activity") {
      const type = (params && params.get("type")) || "";
      const q = ((params && params.get("q")) || "").trim();
      const shown = rows.filter((row) => !type || chipOf(row) === type)
        .filter((row) => !q || `${row.title} ${ROOM_TYPES[row.type] ? ROOM_TYPES[row.type].label : ""}`.includes(q));
      const options: [string, string, string][] = [["all", "전체", "all"], ...Object.values(ROOM_TYPES).map(({ chip, label }): [string, string, string] => [chip, `${label} ${rows.filter((row) => chipOf(row) === chip).length}`, chip])];
      content = (
        <>
          {widgets}
          {partial}
          <div className="pf-toolbar">{chipRow(ctx, "pf_room_activity", options, "type")}{finder(ctx, "활동 검색")}</div>
          {shown.length
            ? <div className="pf-activities">{shown.map((row) => <ActivityRow key={rowKey(courseId, row)} row={row} course={course} courseId={courseId} stale={stale} />)}</div>
            : <EmptyState title={rows.length ? "해당하는 활동이 없습니다" : "서버에 등록된 활동이 없습니다"} body={rows.length ? "다른 유형을 선택하거나 검색어를 지워 보세요." : "활동 등록에서 영상, 자료, 과제, 시험, 설문을 만들 수 있습니다."} />}
          {guide}
        </>
      );
    } else if (!room.weeks.length) {
      content = <>{widgets}{partial}<EmptyState title="이 과목에는 서버에 주차가 없습니다" body="학사 일정에서 주차가 만들어지면 여기에 나옵니다." /></>;
    } else {
      const wanted = Number(ctx.state.week) || 1;
      const week = room.weeks.find((w) => w.weekNo === wanted) || room.weeks[0];
      content = (
        <>
          {widgets}
          {partial}
          <div className="weekbar" role="group" aria-label="주차">
            {room.weeks.map((w) => (
              <button
                key={w.weekNo}
                type="button"
                className={`week-num${w.weekNo === week.weekNo ? " on" : ""}${w.rows.length ? " done" : ""}`}
                aria-label={`${w.weekNo}주차 활동 ${w.rows.length}개`}
                aria-pressed={w.weekNo === week.weekNo ? "true" : "false"}
                onClick={() => {
                  getState().week = w.weekNo || 1;
                  save();
                  render();
                }}
              >
                {String(w.weekNo)}
              </button>
            ))}
          </div>
          <div className="week-block">
            <div className="pf-week-head">
              <h2>{weekHead(week)}</h2>
              <Link className="btn btn-primary" href={pf("pf_week_add", { kj: course.id, week: String(week.weekNo) })}>활동 등록</Link>
            </div>
            {week.rows.length
              ? <div className="pf-activities">{week.rows.map((row) => <ActivityRow key={rowKey(courseId, row)} row={row} course={course} courseId={courseId} stale={stale} />)}</div>
              : <EmptyState title="이 주차에 등록된 활동이 없습니다" body="활동 등록에서 영상, 자료, 과제, 시험, 설문을 만들 수 있습니다." />}
          </div>
          {guide}
        </>
      );
    }
  }
  return <div ref={slot} className="stack pf-room-server" data-room={String(courseId)}>{content}</div>;
}

// ---------- 활동 등록: 유형 고르기 ----------

const CHOICES = [
  { type: "lecture_weeks", screen: "pf_contents", text: "영상을 올려 HLS로 변환하고 콘텐츠마다 인정 기준 시간을 정합니다." },
  { type: "lecture_material", screen: "pf_material_write", text: "PDF를 올려 강의자료로 등록하고 쪽별 요약을 만듭니다." },
  { type: "report", screen: "pf_report_create", text: "제출 기간, 지각 제출, 배점을 정해 과제를 냅니다." },
  { type: "test", screen: "pf_exam_create", text: "응시 기간과 제한 시간, 문항을 정해 시험을 냅니다." },
  { type: "survey", screen: "pf_discuss", text: "문항과 응답 기간을 정해 설문을 만듭니다." },
];

/** `pf_week_add`(서버 과목): 서버가 만들 수 있는 유형만 고르고, 고른 주차를 가지고 그 유형의 등록 화면으로 간다. */
export function serverActivityChooser(ctx: ScreenCtx, course: Course): ReactNode {
  const blocked = gate(course, "ASTRA 서버에 로그인하면 이 과목에 활동을 등록할 수 있습니다.");
  if (blocked) return blocked;
  const fromUrl = Number(ctx.params && ctx.params.get("week"));
  const start = Number.isInteger(fromUrl) && fromUrl >= 1 && fromUrl <= MAX_WEEK ? fromUrl : Number(ctx.state.week) || 1;
  return <ActivityChooser course={course} start={start} />;
}

function ActivityChooser({ course, start }: { course: Course; start: number }) {
  const week = useRef<HTMLInputElement>(null);
  const [error, setError] = useState("");

  /** 고른 주차를 강의실 주차로 두고 그 유형의 등록 화면으로 간다. 영상 등록 폼에는 주차를 미리 넣는다. */
  function weekRoute(target: string) {
    const n = Number(week.current && week.current.value);
    if (!Number.isInteger(n) || n < 1 || n > MAX_WEEK) {
      setError(`주차는 1부터 ${MAX_WEEK} 사이의 정수입니다.`);
      week.current?.focus();
      return;
    }
    const kj = course.id;
    getState().week = n;
    save();
    if (target === "pf_contents") presetLectureWeek(courseIdFor(kj), n);
    navigate(pf(target, { kj, week: String(n) }));
  }

  return (
    <div className="sheet" data-room-chooser={course.id}>
      <h2>활동 유형</h2>
      <div className="pf-form">
        <label className="field">
          <span className="field-label">주차</span>
          <input ref={week} type="number" min={1} max={MAX_WEEK} defaultValue={String(start)} data-room-week="" aria-describedby="pf-room-week-error" aria-invalid={error ? "true" : undefined} />
        </label>
        <div className="span-2"><p className="err" id="pf-room-week-error" role="alert" data-room-week-error="">{error}</p></div>
      </div>
      <div className="pf-activities">
        {CHOICES.map((choice) => {
          const meta = ROOM_TYPES[choice.type];
          return (
            <div className="pf-activity" key={choice.type}>
              <div className="pf-activity-main">
                <span className={`pill ${meta.chip}`}>{meta.label}</span>
                <span className="pf-activity-copy"><b>{`${meta.label} 등록`}</b><span className="pf-meta">{choice.text}</span></span>
              </div>
              <div className="pf-activity-actions">
                <Button kind="secondary" aria-label={`${meta.label} 등록 화면으로`} onClick={() => weekRoute(choice.screen)}>등록 화면으로</Button>
              </div>
            </div>
          );
        })}
      </div>
      <p className="pf-sub">실시간 강의(Zoom), 글, 토론, 오프라인 활동은 ASTRA 서버에 아직 만드는 기능이 없어 이 과목에서는 등록할 수 없습니다.</p>
    </div>
  );
}
