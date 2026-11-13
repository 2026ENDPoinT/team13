// 과목 카드. 샘플 홈·과목 목록(home.tsx)과 서버 홈의 수강 과목(home-server.tsx)이 함께 쓴다.
import type { ReactNode } from "react";
import { Link } from "../../router.tsx";
import { Icon } from "../../ui/icon.tsx";
import { CoverImage } from "../../ui/assets.tsx";
import { hrefFor, find } from "../../manifest.ts";
import type { Course } from "../../data.ts";

interface Face {
  weeks: [number, number];
  join: [number, number];
  attend: [number, number];
}

const FACE: Record<string, Face> = {
  algo: { weeks: [10, 16], join: [20, 30], attend: [18, 24] },
  ai: { weeks: [10, 16], join: [20, 29], attend: [22, 27] },
  bi: { weeks: [10, 16], join: [35, 36], attend: [24, 25] },
  db: { weeks: [10, 16], join: [16, 27], attend: [14, 16] },
  oss: { weeks: [10, 16], join: [9, 14], attend: [8, 9] },
  se: { weeks: [10, 16], join: [6, 9], attend: [6, 9] },
  secu: { weeks: [10, 16], join: [12, 16], attend: [10, 14] },
  ux: { weeks: [0, 15], join: [0, 24], attend: [0, 14] },
  web1: { weeks: [15, 15], join: [41, 41], attend: [14, 14] },
  sec: { weeks: [2, 8], join: [80, 100], attend: [1, 8] },
  lit: { weeks: [0, 8], join: [64, 120], attend: [0, 8] },
};

function faceOf(course: Course): Face {
  const face = FACE[course.id];
  if (face) return face;
  const total = 14;
  const done = Math.round(((course.progress || 0) / 100) * total);
  const learners = course.learners ?? 0;
  return { weeks: [done, total], join: [learners, learners], attend: [done, total] };
}

function scheduleLabel(time: string): string {
  const match = String(time).match(/^(\S+)\s+(\d{1,2}):\d{2}-(\d{1,2})/);
  if (!match) return time;
  return `${match[1]}(${Number(match[2])} - ${Number(match[3])})`;
}

export function termLabel(term: string | null | undefined): string {
  if (term === "2026-1") return "2026년 1학기";
  if (term === "2026-2") return "2026년 2학기";
  return term || "";
}

export type CardMode = "home" | "catalog" | "enrolled";

function courseHref(course: Course, mode: CardMode): string {
  const openDetail = mode === "catalog" || !course.enrolled;
  if (openDetail) {
    return hrefFor(find(course.kind === "special" ? "24_nonregular_view" : "25_regular_view"), { id: course.id });
  }
  return hrefFor(find(course.kind === "special" ? "70_nr_room_submain" : "40_room_submain"), { kj: course.id });
}

function CourseStudyEntry({ course }: { course: Course }) {
  return (
    <Link
      href={hrefFor(find("study_home"), { kj: course.id })}
      className="btn btn-primary course-study-entry"
      aria-label={`${course.cardTitle || course.ko} 학습실 입장`}
      resetScroll
    >
      학습실 입장
    </Link>
  );
}

const count = (value: unknown): number | null => (typeof value === "number" && Number.isInteger(value) && value >= 0 ? value : null);

/** A server course's `GET /api/me/home` record fields the card reads. */
export interface CourseStats {
  courseId?: number;
  unreadNotices?: number;
  currentWeek?: number;
  weekCount?: number;
  progressPercent?: number;
  attendance?: { attended?: number; total?: number } | null;
}

/**
 * 진행 주·진도·출석 of a server course from its `GET /api/me/home` course record (`currentWeek`, `weekCount`,
 * `progressPercent`, `attendance{attended,total}`). Values the server does not send are left out, never filled in.
 */
function serverFace(stats: CourseStats | undefined): ReactNode[] {
  const body: ReactNode[] = [];
  const week = count(stats?.currentWeek);
  const weeks = count(stats?.weekCount);
  if (week != null && weeks) {
    body.push(
      <div key="line" className="progress-line"><span>{`${Math.min(week, weeks)} / ${weeks} 주`}</span></div>,
      <div key="bar" className="bar"><i style={{ width: `${Math.round((Math.min(week, weeks) / weeks) * 100)}%` }} /></div>,
    );
  }
  const pills: ReactNode[] = [];
  const progress = count(stats?.progressPercent);
  if (progress != null) pills.push(<span key="progress" className="act-pill">{`진도 ${Math.min(progress, 100)}%`}</span>);
  const attended = count(stats?.attendance?.attended);
  const total = count(stats?.attendance?.total);
  if (attended != null && total != null) pills.push(<span key="attend" className="act-pill">{`출석 ${attended} / ${total}`}</span>);
  if (pills.length) body.push(<div key="pills" className="act-pills">{pills}</div>);
  return body;
}

export interface CourseCardsOptions {
  /** Course id -> its `/api/me/home` course record (backend mode). */
  stats?: Map<string, CourseStats>;
}

function CourseCard({ course, mode, options }: { course: Course; mode: CardMode; options: CourseCardsOptions }) {
  const href = courseHref(course, mode);
  const special = course.server && course.kind === "special";
  const body: ReactNode[] = [
    <div key="facts" className="facts">
      {course.learningPeriod ? <span><Icon name="calendar" /><span>{course.learningPeriod}</span></span> : null}
      {course.time || (!special && !course.learningPeriod)
        ? <span><Icon name="calendar" /><span>{scheduleLabel(course.time) || "시간 미정"}</span></span>
        : null}
      {course.lectureType ? <span>{course.lectureType}</span> : null}
      {course.credit ? <span><Icon name="star" /><span>{`${course.credit}학점`}</span></span> : null}
    </div>,
  ];
  if (mode === "catalog") body.push(<div key="term" className="tiny">{termLabel(course.term)}</div>);
  if (mode === "home" && (course.server || course.missing)) body.push(...serverFace(options.stats?.get(course.id)));
  else if (mode === "home") {
    const face = faceOf(course);
    const [done, total] = face.weeks;
    const width = total ? Math.max(0, Math.min(100, Math.round((done / total) * 100))) : 0;
    body.push(
      <div key="line" className="progress-line"><span>{`${done} / ${total} 주`}</span></div>,
      <div key="bar" className="bar"><i style={{ width: `${width}%` }} /></div>,
      <div key="pills" className="act-pills">
        <span className="act-pill">{`참여 ${face.join[0]} / ${face.join[1]}`}</span>
        <span className="act-pill">{`출석 ${face.attend[0]} / ${face.attend[1]}`}</span>
      </div>,
    );
  }
  if (mode === "enrolled") body.push(<span key="room" className="act-pill room">강의실</span>);
  return (
    <article className="course-card">
      <Link className="course-card-main" href={href}>
        <div className={`cover ${course.gradient}`}>
          <CoverImage course={course} />
          <b>{course.cardTitle || course.ko}</b>
          <span className="code">{course.code}</span>
          <span className="instructor">{course.instructor}</span>
        </div>
        <div className="card-body">{body}</div>
      </Link>
      <CourseStudyEntry course={course} />
    </article>
  );
}

/**
 * `options.stats`: Map of course id -> its `/api/me/home` course record (backend mode only).
 * Server courses (`course.server`) show only server facts.
 */
export function courseCards(list: Course[], mode: CardMode = "home", options: CourseCardsOptions = {}): ReactNode {
  return (
    <div className="cards">
      {list.map((course) => <CourseCard key={course.id} course={course} mode={mode} options={options} />)}
    </div>
  );
}

export function specialCard(course: Course): ReactNode {
  const href = courseHref(course, course.enrolled ? "enrolled" : "catalog");
  const status = course.enrolled ? "수강 중" : course.open ? "모집 중" : "예정";
  const tone = course.enrolled ? "on" : course.open ? "open" : "";
  return (
    <article key={course.id} className="course-card special-card">
      <Link className="course-card-main" href={href}>
        <div className={`cover ${course.gradient}`}>
          <CoverImage course={course} />
          <b>{course.cardTitle || course.ko}</b>
          <span className="code">{course.code}</span>
          <span className="who">{course.instructor}</span>
        </div>
        <div className="card-body">
          <div className="facts">
            <span>2026.09.01 – 2026.12.19</span>
            <span className={`status ${tone}`}>{status}</span>
          </div>
          <div className="tiny">{`신청 ${course.learners} / ${course.capacity || 100}`}</div>
          <span className="act-pill room">{course.enrolled ? "강의실" : "신청하기"}</span>
        </div>
      </Link>
      <CourseStudyEntry course={course} />
    </article>
  );
}
