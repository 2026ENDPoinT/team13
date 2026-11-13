// 한 과목의 서버 활동 행을 화면에 쓰는 글: 종류 이름, 상태·기간 문장, 주 범위, 부분 목록 구독 훅.
// 강의실·학습활동 목록(areas/course/activities-server.tsx)과 활동 상세의 왼쪽 목록(shared/activity-rail.tsx)이 같이 쓴다.
import { useEffect, useMemo, useSyncExternalStore } from "react";
import { freshVisit, useRefreshOnFocus, useWatch } from "./remote-cache.ts";
import { roleIn } from "./backend.ts";
import { loadPart, type ActivityPart, type ActivityRow } from "./course-activities-remote.ts";
import { koDateTime } from "./home-remote.ts";
import { windowText, PROGRESS_STATE, ACTIVITY_PHASE, type WeekActivity } from "./lectures-remote.ts";
import { schoolDuration } from "./school-content.tsx";
import { periodText, PHASE as REPORT_PHASE, STATUS as REPORT_STATUS } from "./assignments-remote.ts";
import { PHASE as QUIZ_PHASE, ATTEMPT_STATUS } from "./quizzes-remote.ts";
import { PHASE as SURVEY_PHASE, STATE as SURVEY_STATE } from "./surveys-remote.ts";

export const TYPE_LABEL: Record<string, string> = { video: "영상", material: "자료", report: "과제", test: "시험", survey: "설문", zoom: "Zoom", post: "글" };

/** The fields of a row's server item (lecture, material, assignment, quiz, survey) that this screen reads. */
interface ServerItem {
  published?: boolean;
  phase?: string;
  status?: string;
  state?: string;
  examType?: string;
  submittedCount?: number;
  inProgressCount?: number;
  finishedCount?: number;
  respondentCount?: number;
  openAt?: string | null;
  closeAt?: string | null;
}

/** One lecture item of the student's own progress. */
interface ProgressItem {
  progress?: { status?: string; progressPercent?: number } | null;
}

export const instructorIn = (kj: string) => roleIn(kj) === "INSTRUCTOR";

/**
 * Re-renders on any change of `parts`, re-reads them on focus, and loads all of them on a new
 * history entry (else only the missing ones).
 */
export function useActivityParts(parts: ActivityPart[], scope: string): void {
  const subscribe = useMemo(() => (listener: () => void) => {
    const offs = parts.map((entry) => entry.resource.subscribe(listener));
    return () => offs.forEach((off) => off());
  }, [parts]);
  useSyncExternalStore(subscribe, () => parts.map((entry) => entry.resource.version()).join(","));
  useRefreshOnFocus(() => parts.forEach((entry) => void loadPart(entry)));
  useWatch(parts);
  useEffect(() => {
    const fresh = freshVisit(scope, parts.map((entry) => entry.key).join("|")) === "entry";
    for (const entry of parts) if (fresh || !entry.resource.peek(entry.key)) void loadPart(entry);
  });
}

/** 주 범위 "11월 3일 – 11월 9일"(서버 LocalDate). 없으면 빈 글. */
export function weekRange(week: { startsOn?: string | null; endsOn?: string | null } | null | undefined): string {
  const part = (value: string | null | undefined) => {
    const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(value || ""));
    return match ? `${Number(match[2])}월 ${Number(match[3])}일` : "";
  };
  const start = part(week && week.startsOn);
  const end = part(week && week.endsOn);
  return start && end ? `${start} – ${end}` : start || end;
}

export function periodOf(row: ActivityRow): string {
  // The row's item is the server record of its type; this screen reads only ServerItem fields.
  const item = row.item as ServerItem;
  if (!row.school && row.type === "report") return periodText(item);
  if (!row.school && row.type === "video") return windowText(item);
  if (!row.school && row.type === "material") return row.openAt ? `${koDateTime(row.openAt)} 공개` : "";
  if (!row.closeAt) return row.openAt ? `${koDateTime(row.openAt)} ~` : "";
  return row.openAt ? `${koDateTime(row.openAt)} ~ ${koDateTime(row.closeAt)}` : `~ ${koDateTime(row.closeAt)}`;
}

function lectureState(row: ActivityRow, item: ServerItem, instructor: boolean): string {
  // 서버 시계의 구간. 진행 중이 아니면 앞에 붙인다.
  const phase = row.phase && row.phase !== "open" ? ACTIVITY_PHASE[row.phase] || "" : "";
  if (instructor) return [item.published ? "" : "비공개", phase].filter(Boolean).join(" · ");
  if (!row.progress) return phase;
  // Progress entries are the student's own lecture items.
  const progress = row.progress as ProgressItem[];
  if (!progress.length) return [phase, "학습 기록 없음"].filter(Boolean).join(" · ");
  const met = progress.filter((entry) => entry.progress && entry.progress.status === "met").length;
  if (met === progress.length) return [phase, PROGRESS_STATE.met].filter(Boolean).join(" · ");
  const percent = Math.round(progress.reduce((sum, entry) => sum + (Number(entry.progress && entry.progress.progressPercent) || 0), 0) / progress.length);
  return [phase, `진도 ${percent}% · 인정 기준 충족 ${met}/${progress.length}`].filter(Boolean).join(" · ");
}

/**
 * 학교 e-Class에서 가져온 행의 상태: 본인 완료(`done`), 미완료(진행 중인데 `done: false`), 아니면 서버 시계의 구간.
 * 영상은 출석 차시와 학습시간, 시험은 오프라인 표시를 붙이고, 끝에 그 행이 어디서 열리는지 적는다(학교 자료·영상·과제는 STack 상세).
 */
function schoolState(row: ActivityRow): string {
  // A school row's item is its week activity, or for a material only the materials list has, that material.
  const item = row.item as Partial<WeekActivity>;
  const phase = ACTIVITY_PHASE[row.phase || ""] || "";
  const own = row.done ? "완료" : row.missing ? "미완료" : phase || (row.external ? "e-Class" : row.href ? "" : "열 수 없음");
  const time = row.type === "video" ? schoolDuration(item.learningTimeSec) : "";
  return [
    own,
    row.done && row.phase && row.phase !== "open" ? phase : "",
    row.type === "video" && item.attendance ? `출석 ${item.attendance}` : "",
    time ? `학습시간 ${time}` : "",
    item.offline === true ? "오프라인" : "",
    row.files && row.files.length ? `파일 ${row.files.length}개` : "",
    row.external ? "e-Class에서 열기" : row.href ? ({ video: "학교 영상", report: "학교 과제" }[row.type] || "학교 자료") : "e-Class 주소 없음",
  ].filter(Boolean).join(" · ");
}

/** 행 오른쪽의 상태 글. 학생은 본인 상태와 서버 phase, 담당자는 서버의 수. */
export function stateText(row: ActivityRow, instructor: boolean): string {
  if (row.school) return schoolState(row);
  // The row's item is the server record of its type; this screen reads only ServerItem fields.
  const item = row.item as ServerItem;
  const status = item.status || "";
  const phaseKey = item.phase || "";
  if (row.type === "video") return lectureState(row, item, instructor);
  if (row.type === "material") {
    // 시작 전 자료는 담당자에게만 온다.
    return [row.phase === "not_open" ? ACTIVITY_PHASE.not_open : "", row.files && row.files.length ? `파일 ${row.files.length}개` : ""].filter(Boolean).join(" · ");
  }
  if (row.type === "report") {
    if (!instructor) return item.phase === "open" ? REPORT_STATUS[status] || status : `${REPORT_STATUS[status] || status} · ${REPORT_PHASE[phaseKey] || phaseKey}`;
    return [`제출 ${item.submittedCount ?? 0}`, item.published ? null : "비공개", REPORT_PHASE[phaseKey] || phaseKey].filter(Boolean).join(" · ");
  }
  if (row.type === "test") {
    const phase = QUIZ_PHASE[phaseKey] || phaseKey;
    if (!instructor) {
      const own = item.examType === "offline" ? "오프라인" : ATTEMPT_STATUS[status] || status;
      return item.phase === "open" ? own : `${own} · ${phase}`;
    }
    return [`응시 중 ${item.inProgressCount ?? 0}`, `종료 ${item.finishedCount ?? 0}`, item.published ? null : "비공개", phase].filter(Boolean).join(" · ");
  }
  if (row.type === "survey") {
    const phase = SURVEY_PHASE[phaseKey] || phaseKey;
    const state = item.state || "";
    if (!instructor) return item.phase === "open" ? SURVEY_STATE[state] || state : `${SURVEY_STATE[state] || state} · ${phase}`;
    return [`응답 ${item.respondentCount ?? 0}`, item.published ? null : "비공개", phase].filter(Boolean).join(" · ");
  }
  return "이 화면에서 열 수 없는 활동";
}

export function viewType(row: ActivityRow): string {
  return row.type === "other" ? row.kind : row.type;
}
