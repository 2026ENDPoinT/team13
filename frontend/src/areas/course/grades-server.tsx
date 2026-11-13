// 학생 성적 화면(43, 43b, 43c)의 서버 버전. 공개 여부는 서버가 항목마다 정하므로 세 경로가 같은 서버 화면을 그린다.
// 출석 요약은 `GET …/attendance`, 평가 활동은 `GET …/grades/me?type=&q=`다. 공개하지 않은 항목에는 점수·의견·채점 일시
// 속성이 아예 없으므로 화면은 속성이 있을 때만 그리고, 합계는 서버가 준 값만 쓴다(직접 더하지 않는다).
import { useEffect, useRef, type ReactNode } from "react";
import type { Course } from "../../data.ts";
import type { Screen, ScreenCtx } from "../../types.ts";
import { getState, save, render } from "../../store.ts";
import { Link } from "../../router.tsx";
import { EmptyState, ErrorState, LoadingState, Table } from "../../ui/kit.tsx";
import { ApiError } from "../../shared/api.ts";
import { backendStatus, courseIdFor } from "../../shared/backend.ts";
import { backendPanel } from "../../shared/backend-prompt.tsx";
import { readQuery } from "../../shared/query.tsx";
import { freshVisit, useResource } from "../../shared/remote-cache.ts";
import { koDateTime } from "../../shared/home-remote.ts";
import { studentAssignmentHref } from "../../shared/assignments-remote.ts";
import { myGrades, myGradesKey, GRADE_TYPES, TYPE_LABEL, points, totalText, gradeMessage, type MyGradeItem } from "../../shared/grades-remote.ts";
import { attendanceViews, attendanceKey, attendanceMessage } from "../../shared/attendance-remote.ts";
import type { CourseParts } from "./course.tsx";
import { RetryButton } from "./groups-server.tsx";

const LOGIN = "ASTRA 서버에 로그인하면 이 과목의 성적을 서버에서 불러옵니다.";
const SEARCH_DELAY = 300;

const missing = (error: unknown) => error instanceof ApiError && error.status === 404;
const has = (object: object, key: string) => Object.prototype.hasOwnProperty.call(object, key);

/** `srv-grade-filter`: the server `type` filter (all, assignment, quiz, manual), kept apart from the sample filter. */
function gradeTypeFilter(value: string) {
  const state = getState();
  state.draft.srvGradeType = value || "all";
  save();
  render();
}

/** 이 항목의 점수 칸: 공개 전 "미공개 / 배점", 공개했고 점수 없음 "- / 배점", 점수 "획득 / 배점". */
function scoreCell(item: MyGradeItem): ReactNode {
  const max = `${points(item.maxScore)}점`;
  if (!item.released) return <span className="crs-eval-score"><span className="tiny">미공개</span>{` / ${max}`}</span>;
  if (!has(item, "score")) return <span className="crs-eval-score">{`- / ${max}`}</span>;
  return <span className="crs-eval-score">{`${points(item.score)} / ${max}`}</span>;
}

function itemRow(item: MyGradeItem, kj: string): ReactNode {
  const meta = [
    TYPE_LABEL[item.type] || item.type,
    item.closeAt ? `마감 ${koDateTime(item.closeAt)}` : "",
    item.weight != null ? `반영 ${points(item.weight)}%` : "",
    has(item, "gradedAt") ? `채점 ${koDateTime(item.gradedAt)}` : "",
  ].filter(Boolean).join(" · ");
  const title = <h3>{item.name}</h3>;
  return (
    <article key={item.itemId} className="crs-eval-row" data-grade-item={String(item.itemId)}>
      <span className="crs-kind">{TYPE_LABEL[item.type] || item.type}</span>
      <div>
        {item.assignmentId ? <Link href={studentAssignmentHref(kj, item.assignmentId)}>{title}</Link> : title}
        <p className="tiny">{meta}</p>
        {has(item, "feedback") ? <p className="crs-note">{`의견: ${item.feedback}`}</p> : null}
      </div>
      {scoreCell(item)}
      <span className={`crs-eval-check${item.submitted ? " done" : ""}`} aria-label={item.submitted ? "제출함" : undefined}>{item.submitted ? "✓" : ""}</span>
    </article>
  );
}

function AttendanceBlock({ courseId }: { courseId: number | string }) {
  const key = attendanceKey(courseId);
  const load = () => void attendanceViews.load(key, courseId);
  const entry = useResource(attendanceViews, key, load);
  useEffect(() => {
    if (freshVisit("grades-attendance", key) === "entry" || !attendanceViews.peek(key)) void attendanceViews.load(key, courseId);
  });
  const data = entry && entry.data;
  let body: ReactNode;
  if (!data) {
    if (!entry || !entry.error) body = <LoadingState title="출석을 불러오는 중입니다" />;
    else if (missing(entry.error)) body = <p className="crs-note">출석 정보를 볼 수 없습니다.</p>;
    else body = <ErrorState title="출석을 불러오지 못했습니다" body={attendanceMessage(entry.error)} actions={<RetryButton reload={load} />} />;
  } else if (!data.summary) {
    body = <p className="crs-note">학생 본인의 출석 요약만 보여 줍니다.</p>;
  } else {
    const s = data.summary;
    const settings = data.settings;
    body = (
      <>
        <p className="crs-note">
          {settings && settings.latesPerAbsence > 0
            ? `지각 ${settings.latesPerAbsence}번을 결석 1번으로 칩니다. 결석 환산 ${s.absenceEquivalent}번.`
            : "출석 기준은 담당 교수의 설정을 따릅니다."}
        </p>
        <Table headers={["전체", "출석", "지각", "결석", "유고"]} rows={[[String(s.sessions), String(s.present), String(s.late), String(s.absent), String(s.excused)]]} />
      </>
    );
  }
  return (
    <section className="crs-block">
      <h2>출석</h2>
      <div>{body}</div>
    </section>
  );
}

/** 강의실 성적 화면(백엔드 모드). `parts`는 course.tsx의 head·searchBox와 검색어 범위다. */
export function remoteGrades(screen: Screen, ctx: ScreenCtx, course: Course, parts: CourseParts): ReactNode {
  if (backendStatus() === "anon") {
    return <section className="crs crs-grades" data-area="course">{parts.head("성적", "chart", null)}{backendPanel({ text: LOGIN })}</section>;
  }
  const courseId = courseIdFor(course.id);
  if (!courseId) {
    return (
      <section className="crs crs-grades" data-area="course">
        {parts.head("성적", "chart", null)}
        <EmptyState title="이 과목은 ASTRA 서버에 없습니다" body="서버에 등록된 과목만 성적을 볼 수 있습니다." />
      </section>
    );
  }
  return <RemoteGrades ctx={ctx} kj={course.id} courseId={courseId} parts={parts} />;
}

function RemoteGrades({ ctx, kj, courseId, parts }: { ctx: ScreenCtx; kj: string; courseId: number | string; parts: CourseParts }) {
  const type = GRADE_TYPES.some(([key]) => key === ctx.state.draft.srvGradeType) ? String(ctx.state.draft.srvGradeType) : "all";
  const q = readQuery(ctx.state, parts.scope).trim().slice(0, 100);
  const key = myGradesKey(courseId, type, q);
  const load = () => void myGrades.load(key, courseId, type, q);
  const entry = useResource(myGrades, key, load);
  const live = useRef(true);
  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
    };
  }, []);
  useEffect(() => {
    const fresh = freshVisit("course-grades", key);
    if (fresh === "entry") void myGrades.load(key, courseId, type, q);
    else if (!myGrades.peek(key)) {
      if (fresh === "key" && q) setTimeout(() => { if (live.current && !myGrades.peek(key)) void myGrades.load(key, courseId, type, q); }, SEARCH_DELAY);
      else void myGrades.load(key, courseId, type, q);
    }
  });

  const data = entry && entry.data;
  // 평가 활동 비공개 과목(43c): 목록과 합계가 응답에 없다.
  const hidden = Boolean(data && !data.activitiesVisible);
  let total: ReactNode = null;
  let body: ReactNode;
  if (!data) {
    if (!entry || !entry.error) body = <LoadingState title="성적을 불러오는 중입니다" />;
    else if (missing(entry.error)) body = <EmptyState title="성적을 볼 수 없습니다" body="이 과목의 수강생 계정으로 로그인해야 성적을 볼 수 있습니다." />;
    else body = <ErrorState title="성적을 불러오지 못했습니다" body={gradeMessage(entry.error)} actions={<RetryButton reload={load} />} />;
  } else if (hidden) {
    body = <p className="crs-note">담당 교수가 평가 활동을 공개하지 않은 과목입니다.</p>;
  } else {
    total = data.total
      ? <p className="crs-note"><b>{`합계 ${totalText(data.total)}`}</b>{" · 공개한 항목 가운데 점수를 받은 것만 더했습니다."}</p>
      : <p className="crs-note">공개된 점수가 아직 없어 합계가 없습니다.</p>;
    const items = Array.isArray(data.items) ? data.items : [];
    if (!items.length) {
      body = q || type !== "all"
        ? <EmptyState title="해당하는 항목이 없습니다" body="다른 종류를 선택하거나 검색어를 지워 보세요." />
        : <EmptyState title="평가 항목이 없습니다" body="담당 교수가 성적 항목을 만들면 여기에 표시됩니다." />;
    } else {
      body = (
        <>
          {entry.error ? <p className="err" role="alert">{gradeMessage(entry.error)}</p> : null}
          <div className="crs-eval">{items.map((item) => itemRow(item, kj))}</div>
        </>
      );
    }
  }
  return (
    <section className="crs crs-grades" data-area="course">
      {parts.head("성적", "chart", null)}
      <AttendanceBlock courseId={courseId} />
      <section className="crs-block">
        <div className="crs-toolbar">
          <h2>평가 활동</h2>
          {hidden ? null : parts.searchBox("평가 항목 검색", ctx, "grades")}
        </div>
        <div className="crs-filter-row" role="toolbar" aria-label="평가 종류" hidden={hidden}>
          {GRADE_TYPES.map(([value, label]) => (
            <button key={value} type="button" className={`chip${type === value ? " on" : ""}`} aria-pressed={type === value ? "true" : "false"} onClick={() => gradeTypeFilter(value)}>{label}</button>
          ))}
        </div>
        <div className="crs-grade-total" aria-live="polite">{total}</div>
        <div className="crs-remote" aria-live="polite">{body}</div>
      </section>
    </section>
  );
}
