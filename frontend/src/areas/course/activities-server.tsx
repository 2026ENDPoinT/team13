// 백엔드 모드의 학생 강의실(40·72), 학습활동 목록(50–56), 파일모음(46). 한 과목의 서버 활동을
// shared/course-activities-remote.ts가 모은 행으로만 그린다(주차 목록, 자료, 영상과 본인 진도, 과제·시험·설문과
// 본인 상태). 연결된 과목과 `anon`에서는 샘플 활동을 섞지 않는다. 진행 중·미제출 필터는 서버 상태(phase)만 본다.
// 파일모음은 과목 파일 목록(`GET …/files`)이다. 서버 글은 텍스트로만 넣는다.
import { useEffect, useMemo, useRef, useState, useSyncExternalStore, type ReactNode } from "react";
import type { Course } from "../../data.ts";
import type { Screen, ScreenCtx } from "../../types.ts";
import { Link } from "../../router.tsx";
import { Badge, Button, EmptyState, ErrorState, LoadingState, Pagination } from "../../ui/kit.tsx";
import { Icon } from "../../ui/icon.tsx";
import { hrefFor, find } from "../../manifest.ts";
import { ApiError } from "../../shared/api.ts";
import { freshVisit, useRefreshOnFocus, useResource, useWatch } from "../../shared/remote-cache.ts";
import { backendStatus, courseIdFor, roleIn } from "../../shared/backend.ts";
import { SIGNED_OUT } from "../../shared/backend-prompt.tsx";
import { CompactActivityRow } from "../../shared/activity-row.tsx";
import {
  activityParts, loadPart, activitySnapshot, passServerFilter, reloadFailed, serverOwnsCourse, PART_LABEL,
  type ActivityPart, type ActivityRow, type ActivitySnapshot,
} from "../../shared/course-activities-remote.ts";
import { rememberCourseFiles } from "../../shared/course-pdfs-remote.ts";
import { downloadControl, sizeText } from "../../shared/uploads-remote.tsx";
import { SaveToStudy } from "../../shared/save-to-study.tsx";
import { todoLabel, koDateTime } from "../../shared/home-remote.ts";
import { serverActivityHref, materialHref } from "../../shared/activity-links.ts";
import { windowText, lectureMessage, PROGRESS_STATE, ACTIVITY_PHASE, type CourseWeek, type WeekActivity } from "../../shared/lectures-remote.ts";
import { materialMessage, courseFiles, filesKey, loadMoreFiles, materialLists, materialKey, type CourseFiles, type MaterialFile } from "../../shared/materials-remote.ts";
import { schoolMaterialsOf, schoolFilesOf, schoolFileRef, type SchoolFile, type SchoolMaterial } from "../../shared/school-content.tsx";
import { assignmentMessage, periodText, PHASE as REPORT_PHASE, STATUS as REPORT_STATUS } from "../../shared/assignments-remote.ts";
import { quizMessage, PHASE as QUIZ_PHASE, ATTEMPT_STATUS } from "../../shared/quizzes-remote.ts";
import { surveyMessage, PHASE as SURVEY_PHASE, STATE as SURVEY_STATE } from "../../shared/surveys-remote.ts";
import { pickWeek, roomFilter, toggleWeek, type HeadFn, type SearchBoxFn } from "./course.tsx";
import { RetryButton } from "./groups-server.tsx";
import { RoomLayout, CoursePlanLink, CourseActivitySection, CourseEmpty } from "./room-view.tsx";

const PAGE = 8;

const TYPE_LABEL: Record<string, string> = { video: "영상", material: "자료", report: "과제", test: "시험", survey: "설문", zoom: "Zoom", post: "글" };

const MESSAGE: Record<string, (error: unknown) => string> = {
  weeks: lectureMessage, progress: lectureMessage, materials: materialMessage,
  assignments: assignmentMessage, quizzes: quizMessage, surveys: surveyMessage,
};

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

/** 이 과목의 활동 화면을 서버가 맡는지: `anon`이거나 `ready`이고 서버에 그 과목이 있을 때. */
export function serverOwnsActivities(course: Course): boolean {
  return serverOwnsCourse(course.id);
}

const instructorIn = (kj: string) => roleIn(kj) === "INSTRUCTOR";

/**
 * Re-renders on any change of `parts`, re-reads them on focus, and loads all of them on a new
 * history entry (else only the missing ones).
 */
function useActivityParts(parts: ActivityPart[], scope: string): void {
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
function weekRange(week: { startsOn?: string | null; endsOn?: string | null } | null | undefined): string {
  const part = (value: string | null | undefined) => {
    const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(value || ""));
    return match ? `${Number(match[2])}월 ${Number(match[3])}일` : "";
  };
  const start = part(week && week.startsOn);
  const end = part(week && week.endsOn);
  return start && end ? `${start} – ${end}` : start || end;
}

function periodOf(row: ActivityRow): string {
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

/** 학습시간(초) "25분 5초", "1시간 2분". 수가 아니면 빈 글. */
function learningTime(seconds: unknown): string {
  const total = typeof seconds === "number" && Number.isFinite(seconds) && seconds >= 0 ? Math.round(seconds) : null;
  if (total == null) return "";
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return h ? `${h}시간 ${m}분` : m ? `${m}분${s ? ` ${s}초` : ""}` : `${s}초`;
}

/**
 * 학교 e-Class에서 가져온 행의 상태: 본인 완료(`done`), 미완료(진행 중인데 `done: false`), 아니면 서버 시계의 구간.
 * 영상은 출석 차시와 학습시간, 시험은 오프라인 표시를 붙이고, 끝에 그 행이 어디서 열리는지 적는다(학교 자료는 STack 자료 상세).
 */
function schoolState(row: ActivityRow): string {
  // A school row's item is its week activity, or for a material only the materials list has, that material.
  const item = row.item as Partial<WeekActivity>;
  const phase = ACTIVITY_PHASE[row.phase || ""] || "";
  const own = row.done ? "완료" : row.missing ? "미완료" : phase || (row.external ? "e-Class" : row.href ? "" : "열 수 없음");
  const time = row.type === "video" ? learningTime(item.learningTimeSec) : "";
  return [
    own,
    row.done && row.phase && row.phase !== "open" ? phase : "",
    row.type === "video" && item.attendance ? `출석 ${item.attendance}` : "",
    time ? `학습시간 ${time}` : "",
    item.offline === true ? "오프라인" : "",
    row.files && row.files.length ? `파일 ${row.files.length}개` : "",
    row.external ? "e-Class에서 열기" : row.href ? "학교 자료" : "e-Class 주소 없음",
  ].filter(Boolean).join(" · ");
}

/** 행 오른쪽의 상태 글. 학생은 본인 상태와 서버 phase, 담당자는 서버의 수. */
function stateText(row: ActivityRow, instructor: boolean): string {
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

function viewType(row: ActivityRow): string {
  return row.type === "other" ? row.kind : row.type;
}

/** 서버 상태의 긴 설명은 title에 보존하고 목업의 짧은 3열 행으로 표시한다. */
function rowNode(row: ActivityRow, kj: string): ReactNode {
  const type = viewType(row);
  const label = TYPE_LABEL[type] || todoLabel(row.kind);
  const state = stateText(row, instructorIn(kj));
  const detail = [label, row.weekNo == null ? "" : `${row.weekNo}주`, periodOf(row), state].filter(Boolean).join(" · ");
  return <CompactActivityRow key={row.key} id={row.key} type={type} title={row.title} label={label} href={row.href} done={row.done} status={row.type === "other" ? "열 수 없음" : state.split(" · ")[0]} detail={detail} external={row.external === true} />;
}

/** 실패한 부분 목록 안내와 다시 받기. 받아 둔 내용이 있으면 그것은 그대로 보인다. */
function failureNotes(snapshot: ActivitySnapshot, parts: ActivityPart[]): ReactNode {
  if (!snapshot.failures.length) return null;
  return (
    <div className="err crs-srv-fail" role="alert">
      <p>일부 활동을 불러오지 못했습니다.</p>
      <ul>
        {snapshot.failures.map((failure) => {
          const say = MESSAGE[failure.name] || lectureMessage;
          return <li key={failure.name}>{`${PART_LABEL[failure.name] || failure.name}: ${failure.error instanceof ApiError && failure.error.status === 404 ? "볼 수 없습니다." : say(failure.error)}`}</li>;
        })}
      </ul>
      <RetryButton reload={() => reloadFailed(parts)} />
    </div>
  );
}

// ---------- 강의실 ----------

function roomTags(course: Course, rows: ActivityRow[]): string[] {
  const tags: string[] = [];
  if (course.kind === "special") tags.push("비교과");
  if (rows.some((row) => row.type === "video" || row.kind === "zoom")) tags.push("온라인");
  if (course.room) tags.push("대면");
  if (course.credit) tags.push(`${course.credit}학점`);
  return tags;
}

/** 백엔드 모드의 강의실 본문(40·72). */
export function remoteRoom(screen: Screen, ctx: ScreenCtx, course: Course): ReactNode {
  if (backendStatus() === "anon") {
    return (
      <section className="crs" data-area="course">
        <CoursePlanLink courseId={course.id} />
      </section>
    );
  }
  return <RemoteRoom screen={screen} ctx={ctx} course={course} />;
}

function RemoteRoom({ screen, ctx, course }: { screen: Screen; ctx: ScreenCtx; course: Course }) {
  const kj = course.id;
  const courseId = courseIdFor(kj);
  const instructor = instructorIn(kj);
  const parts = useMemo(() => (courseId == null ? [] : activityParts(courseId, { instructor })), [courseId, instructor]);
  useActivityParts(parts, "course-room");
  const draft = ctx.state.draft || {};
  const type: string = screen.focus === "video" ? "video" : (draft.roomType || "");
  const status: string = draft.roomStatus || "";
  const closed = new Set<number>(draft.closedWeeks || []);
  const unit = course.kind === "special" ? "회차" : "주";
  const snapshot = activitySnapshot(parts, kj, { instructor });
  const { rows, weeks } = snapshot;
  const present = [...new Set([...rows.map(viewType), ...(type ? [type] : [])])];
  const week = weeks.some((entry) => entry.weekNo === ctx.state.week) ? ctx.state.week : weeks[0]?.weekNo || 1;
  const reload = () => parts.forEach((entry) => void loadPart(entry));

  let body: ReactNode;
  if (snapshot.denied) body = <EmptyState title="이 과목의 활동을 볼 수 없습니다" body="수강 중인 과목인지 확인해 주세요." />;
  else if (snapshot.pending && !rows.length) body = <LoadingState title="주차와 활동을 불러오는 중입니다" />;
  else {
    const shown = rows.filter((row) => (!type || viewType(row) === type) && (!status || passServerFilter(row, status)));
    const byWeek = new Map<number, ActivityRow[]>();
    for (const row of shown) {
      const n = Number(row.weekNo);
      byWeek.set(n, [...(byWeek.get(n) || []), row]);
    }
    const sections = [...byWeek.keys()].sort((a, b) => a - b).map((n) => {
      const entry = weeks.find((item: CourseWeek) => item.weekNo === n);
      return (
        <CourseActivitySection key={n} week={{ number: n, title: entry?.title || "", range: weekRange(entry) }} unit={unit} current={week === n} closed={closed.has(n)} onToggle={() => toggleWeek(n)}>
          {(byWeek.get(n) || []).map((row) => rowNode(row, kj))}
        </CourseActivitySection>
      );
    });
    body = (
      <>
        {failureNotes(snapshot, parts)}
        {sections}
        {sections.length ? null : rows.length
          ? <CourseEmpty title="해당하는 활동이 없습니다" body="다른 종류나 진행 상태를 선택해 보세요." onReload={reload} />
          : snapshot.pending ? <LoadingState title="주차와 활동을 불러오는 중입니다" /> : <CourseEmpty title="등록된 활동이 없습니다" body="교수님이 활동을 공개하면 주차별로 표시됩니다." onReload={reload} />}
      </>
    );
  }

  // 이어서 학습하기는 이 서비스의 행이 먼저다. 학교에서 가져온 행만 있는 과목에서만 다음 학교 행(새 탭)으로 간다.
  const pool = rows.some((row) => !row.school) ? rows.filter((row) => !row.school) : rows;
  const next = pool.find((row) => row.href && row.missing) || pool.find((row) => row.href && row.ongoing && !row.done);
  return (
    <RoomLayout
      courseId={kj}
      term={course.term || ""}
      title={course.ko}
      instructor={course.instructor}
      meta={[course.en, course.time, course.code].filter(Boolean).join(" · ")}
      tags={roomTags(course, rows)}
      nextHref={next?.href}
      nextExternal={next?.external === true}
      types={present.map((key) => [key, TYPE_LABEL[key] || todoLabel(key)])}
      type={type}
      status={status}
      weeks={weeks.map((entry) => {
        const tracked = rows.filter((row) => row.weekNo === entry.weekNo && ["video", "report", "test", "survey"].includes(row.type));
        const done = tracked.filter((row) => row.done).length;
        return { number: entry.weekNo, title: entry.title || "", mark: !done ? "" : done === tracked.length ? "✓" : `${done}/${tracked.length}` };
      })}
      week={week}
      unit={unit}
      onFilter={roomFilter}
      onWeek={pickWeek}
    >
      {body}
    </RoomLayout>
  );
}

// ---------- 학습활동 목록 ----------

/** 백엔드 모드의 학습활동 목록 본문(50–56): 서버 활동 전체를 필터에 맞게 주차별로, 8개씩 쪽을 나눈다. */
export function remoteActivityList(screen: Screen, ctx: ScreenCtx, course: Course, filter: string): ReactNode {
  if (backendStatus() === "anon") return SIGNED_OUT;
  return <RemoteActivityList screen={screen} ctx={ctx} kj={course.id} filter={filter} />;
}

function RemoteActivityList({ screen, ctx, kj, filter }: { screen: Screen; ctx: ScreenCtx; kj: string; filter: string }) {
  const courseId = courseIdFor(kj);
  const instructor = instructorIn(kj);
  const parts = useMemo(() => (courseId == null ? [] : activityParts(courseId, { instructor })), [courseId, instructor]);
  useActivityParts(parts, "course-activity-list");
  const page = Math.max(1, Math.floor(Number(ctx.params && ctx.params.get("page"))) || 1);
  const pageHref = (n: number) => hrefFor(find(screen.id), n > 1 ? { kj, page: String(n) } : { kj });
  const snapshot = activitySnapshot(parts, kj, { instructor });
  const rows = snapshot.rows.filter((row) => passServerFilter(row, filter));
  let content: ReactNode;
  if (snapshot.denied) content = <EmptyState title="이 과목의 활동을 볼 수 없습니다" body="수강 중인 과목인지 확인해 주세요." />;
  else if (snapshot.pending && !rows.length) content = <LoadingState title="학습활동을 불러오는 중입니다" />;
  else {
    const pages = Math.max(1, Math.ceil(rows.length / PAGE));
    const current = Math.min(pages, page);
    const slice = rows.slice((current - 1) * PAGE, current * PAGE);
    const weeks = [...new Set(slice.map((row) => Number(row.weekNo)))].sort((a, b) => a - b);
    content = (
      <>
        {failureNotes(snapshot, parts)}
        {!slice.length
          ? snapshot.pending
            ? <LoadingState title="학습활동을 불러오는 중입니다" />
            : <CourseEmpty title="해당하는 활동이 없습니다" body={filter === "all" ? "교수님이 활동을 공개하면 이 목록에 표시됩니다." : "다른 필터를 선택해 보세요."} onReload={() => parts.forEach((entry) => void loadPart(entry))} />
          : (
            <>
              {weeks.map((n) => {
                const entry = snapshot.weeks.find((item: CourseWeek) => item.weekNo === n);
                return (
                  <CourseActivitySection key={n} week={{ number: n, range: weekRange(entry) }}>
                    {slice.filter((row) => Number(row.weekNo) === n).map((row) => rowNode(row, kj))}
                  </CourseActivitySection>
                );
              })}
              <Pagination page={current} pages={pages} hrefForPage={pageHref} />
            </>
          )}
      </>
    );
  }
  return <>{content}</>;
}

// ---------- 파일모음 ----------

function extensionOf(file: Partial<MaterialFile>): string {
  const ext = String(file.extension || String(file.name || "").split(".").pop() || "").toLowerCase();
  return ext ? ext.toUpperCase() : "파일";
}

/** 검색어를 고친 뒤 서버에 묻기까지 기다리는 시간(ms). 그 사이 다시 고치면 앞 요청은 보내지 않는다. */
const SEARCH_DELAY = 300;

interface FilesParts {
  head: HeadFn;
  searchBox: SearchBoxFn;
  query: string;
}

/**
 * 백엔드 모드의 파일모음(46): `GET …/files?q=&cursor=&size=`. 학습활동에 첨부된 파일을 주차·활동 순으로 서버가 나눈 쪽으로
 * 받고, 검색(`q`, 파일 이름·활동 제목)도 서버가 한다. "더 보기"는 `nextCursor`로 다음 쪽을 붙인다. 내려받기는 첨부 원본
 * Blob, 미리보기는 서버 미리보기 화면이다. 그 아래에 학교 e-Class에서 가져온 자료의 파일(`GET …/materials`의 `origin: "school"`)을
 * 같은 검색어로 거른다. 목록은 학교에 묻지 않는다: 미리보기·내려받기를 누를 때만 그 파일을 학교에서 가져온다.
 */
export function remoteFiles(ctx: ScreenCtx, course: Course, { head, searchBox, query }: FilesParts): ReactNode {
  if (backendStatus() === "anon") {
    return (
      <section className="crs" data-area="course">
        {head("파일모음", "file", null)}
      </section>
    );
  }
  return <RemoteFiles ctx={ctx} kj={course.id} head={head} searchBox={searchBox} query={query} />;
}

function MoreFiles({ courseId, q, data, busy }: { courseId: number | string; q: string; data: CourseFiles; busy: boolean }) {
  const [pressed, setPressed] = useState(false);
  const disabled = busy || pressed;
  return (
    <div className="btn-row">
      <Button
        kind="secondary"
        disabled={disabled}
        aria-busy={disabled ? "true" : undefined}
        onClick={() => {
          if (disabled) return;
          setPressed(true);
          void loadMoreFiles(courseId, q);
        }}
      >
        {`더 보기 (${data.items.length}/${data.total})`}
      </Button>
    </div>
  );
}

/** 학교 자료 파일 한 줄과 그 자료. */
interface SchoolFileLine {
  material: SchoolMaterial;
  file: SchoolFile;
}

/** 학교 자료의 파일 중 검색어(파일 이름·자료 제목)에 맞고, 서버 파일모음이 이미 보인 복사본이 아닌 것. */
function schoolFileLines(materials: SchoolMaterial[], q: string, shown: Set<string>): SchoolFileLine[] {
  const needle = q.toLowerCase();
  const lines: SchoolFileLine[] = [];
  for (const material of materials) {
    for (const file of schoolFilesOf(material)) {
      if (shown.has(String(file.fileId))) continue;
      if (needle && !String(file.name || "").toLowerCase().includes(needle) && !String(material.title || "").toLowerCase().includes(needle)) continue;
      lines.push({ material, file });
    }
  }
  return lines.sort((a, b) => (Number(a.material.weekNo) || 0) - (Number(b.material.weekNo) || 0));
}

function SchoolFilesSection({ kj, courseId, lines }: { kj: string; courseId: number | string; lines: SchoolFileLine[] }) {
  return (
    <section className="crs-week open school-files" aria-label="학교 e-Class 자료 파일">
      <h2 className="crs-rail-week">학교 e-Class 자료</h2>
      {lines.map(({ material, file }) => (
        <article key={`${material.activityId}:${String(file.fileId)}`} className="crs-file" data-file={String(file.fileId)}>
          <span className="crs-kind">{extensionOf({ extension: file.extension, name: file.name })}</span>
          <div className="crs-file-main">
            <h3>{file.name}</h3>
            <p className="tiny">
              {[material.weekNo != null ? `${material.weekNo}주` : "", sizeText(file.sizeBytes) ? `약 ${sizeText(file.sizeBytes)}` : ""].filter(Boolean).join(" · ")}
              {" · "}
              <Link href={materialHref(kj, material.activityId)} className="crs-file-activity">{material.title}</Link>
            </p>
          </div>
          <div className="crs-file-actions">
            <Link className="icon-btn" href={hrefFor(find("65_file_preview"), { n: schoolFileRef(file), kj })} aria-label={`${file.name} 미리보기`}><Icon name="search" /></Link>
            {downloadControl(courseId, { fileId: file.fileId, name: file.name, schoolUrl: material.schoolUrl })}
          </div>
        </article>
      ))}
    </section>
  );
}

function RemoteFiles({ ctx, kj, head, searchBox, query }: { ctx: ScreenCtx; kj: string; head: HeadFn; searchBox: SearchBoxFn; query: string }) {
  const courseId = courseIdFor(kj);
  const q = String(query || "").trim().slice(0, 100);
  const key = courseId == null ? null : filesKey(courseId, q);
  // 학교 자료는 자료 목록에서 온다(강의실과 같은 캐시). 없을 때만 한 번 읽는다.
  const materialsKey = courseId == null ? null : materialKey(courseId);
  const materials = useResource(materialLists, materialsKey);
  useEffect(() => {
    if (materialsKey != null && courseId != null && !materialLists.peek(materialsKey)) void materialLists.load(materialsKey, courseId);
  });
  const load = () => {
    if (key != null && courseId != null) void courseFiles.load(key, courseId, q, null);
  };
  // 포커스 복귀 때 첫 쪽만 다시 받으면 "더 보기"로 붙인 쪽이 사라지므로, 새로 읽는 것은 화면 이동과 검색어 변경 때다.
  const entry = useResource(courseFiles, key);
  const live = useRef(true);
  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
    };
  }, []);
  useEffect(() => {
    if (key == null || courseId == null) return;
    const fresh = freshVisit("course-files", key);
    if (fresh !== "entry" && courseFiles.peek(key)) return;
    const run = () => void courseFiles.load(key, courseId, q, null);
    // 검색어를 고치는 동안은 잠시 기다렸다가 묻는다. 그 사이 화면이 다시 그려지면(다음 글자) 이 요청은 보내지 않는다.
    if (fresh === "key") setTimeout(() => { if (live.current) run(); }, SEARCH_DELAY);
    else run();
  });
  const data = entry && entry.data;
  // 미리보기 화면의 제목과 요약 게시 여부가 이 목록의 레코드를 쓴다.
  const remembered = useRef<CourseFiles | null>(null);
  useEffect(() => {
    if (data && courseId != null && remembered.current !== data) {
      remembered.current = data;
      rememberCourseFiles(data.items, kj, courseId);
    }
  });

  const shown = new Set((data ? data.items : []).map((row) => String(row.file && row.file.fileId)));
  const school = courseId == null ? [] : schoolFileLines(schoolMaterialsOf(materials && materials.data), q, shown);
  const schoolSection = school.length && courseId != null ? <SchoolFilesSection kj={kj} courseId={courseId} lines={school} /> : null;

  let body: ReactNode;
  if (!data) {
    if (!entry || !entry.error) body = <LoadingState title="파일을 불러오는 중입니다" />;
    else if (entry.error instanceof ApiError && entry.error.status === 404) body = <EmptyState title="이 과목의 파일을 볼 수 없습니다" body="수강 중인 과목인지 확인해 주세요." />;
    else body = <><ErrorState title="파일을 불러오지 못했습니다" body={materialMessage(entry.error)} actions={<RetryButton reload={load} />} />{schoolSection}</>;
  } else {
    const stale = entry.error ? <p className="err" role="alert">{materialMessage(entry.error)}</p> : null;
    if (!data.items.length) {
      body = (
        <>
          {stale}
          {schoolSection || (q
            ? <EmptyState title="검색 결과가 없습니다" body="파일 이름이나 활동 제목의 일부만 입력해 보세요." />
            : <EmptyState title="파일이 없습니다" body="학습활동에 파일이 첨부되면 주차별로 모입니다." />)}
        </>
      );
    } else {
      const blocks: { weekNo: number; rows: CourseFiles["items"] }[] = [];
      for (const row of data.items) {
        const last = blocks[blocks.length - 1];
        if (!last || last.weekNo !== row.weekNo) blocks.push({ weekNo: row.weekNo, rows: [row] });
        else last.rows.push(row);
      }
      body = (
        <>
          {stale}
          {blocks.map((block, index) => {
            const first = block.rows[0];
            return (
              <section key={`${block.weekNo}-${index}`} className="crs-week open">
                <h2 className="crs-rail-week">{[`${block.weekNo}주`, first.weekTitle || "", weekRange(first)].filter(Boolean).join("  ")}</h2>
                {block.rows.map((row) => {
                  const file: Partial<MaterialFile> = row.file || {};
                  const preview = hrefFor(find("65_file_preview"), { n: `srv-${file.fileId}`, kj });
                  const detail = serverActivityHref(kj, row.activityType, row.activityId);
                  return (
                    <article key={`${row.activityId}-${file.fileId}`} className="crs-file" data-file={String(file.fileId)}>
                      <span className="crs-kind">{extensionOf(file)}</span>
                      <div className="crs-file-main">
                        <h3>{file.name}</h3>
                        <p className="tiny">
                          {sizeText(file.sizeBytes)}
                          {" · "}
                          {detail ? <Link href={detail} className="crs-file-activity">{row.activityTitle}</Link> : <span>{row.activityTitle}</span>}
                        </p>
                      </div>
                      <div className="crs-file-actions">
                        <Link className="icon-btn" href={preview} aria-label={`${file.name} 미리보기`}><Icon name="search" /></Link>
                        {file.fileId == null ? null : downloadControl(courseId, { fileId: file.fileId, name: file.name || "" })}
                        {file.fileId != null && file.pdf ? <SaveToStudy kj={kj} file={{ fileId: file.fileId, name: file.name || "" }} /> : null}
                      </div>
                    </article>
                  );
                })}
              </section>
            );
          })}
          {data.nextCursor && courseId != null ? <MoreFiles key={data.items.length} courseId={courseId} q={q} data={data} busy={Boolean(entry.loading)} /> : null}
          {schoolSection}
        </>
      );
    }
  }
  return (
    <section className="crs" data-area="course">
      {head("파일모음", "file", data ? <Badge tone="neutral">{String(data.total + school.length)}</Badge> : null)}
      <div className="crs-toolbar">
        <p className="crs-note">이 과목 학습활동에 첨부된 파일을 주차별로 모았습니다.</p>
        {searchBox("파일 검색", ctx, "files")}
      </div>
      <div className="crs-files" aria-live="polite">{body}</div>
    </section>
  );
}
