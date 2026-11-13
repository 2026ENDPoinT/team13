import type { ReactNode } from "react";
import { Link } from "../../router.tsx";
import { Button } from "../../ui/kit.tsx";
import { Icon } from "../../ui/icon.tsx";
import { hrefFor, find } from "../../manifest.ts";

export interface RoomWeek {
  number: number;
  title?: string;
  range?: string;
  mark?: string;
}

interface RoomLayoutProps {
  courseId: string;
  term: string;
  title: string;
  instructor: string;
  meta: string;
  tags: string[];
  nextHref?: string | null;
  types: [string, string][];
  type: string;
  status: string;
  weeks: RoomWeek[];
  week: number;
  unit: string;
  onFilter: (kind: "type" | "status", value: string) => void;
  onWeek: (week: number) => void;
  children: ReactNode;
}

export function CoursePlanLink({ courseId }: { courseId: string }) {
  return (
    <Link className="crs-plan" href={hrefFor(find("40b_room_lecplan_pop"), { kj: courseId })}>
      <span className="crs-plan-label"><Icon name="file" /><span>강의계획서</span></span>
      <Icon name="chev" />
    </Link>
  );
}

/** 목업과 서버는 내용만 전달하고 강의실의 DOM·버튼·주차 표현은 여기서 함께 쓴다. */
export function RoomLayout(props: RoomLayoutProps) {
  const { courseId, term, title, instructor, meta, tags, nextHref, types, type, status, weeks, week, unit, onFilter, onWeek, children } = props;
  return (
    <section className="crs" data-area="course">
      <article className="crs-hero">
        <div className="crs-hero-copy">
          <p className="crs-kicker">{term}</p>
          <div className="course-name">
            <h1 className="course-title">{title}</h1>
            <span className="course-prof">{instructor}</span>
          </div>
          <p className="crs-hero-meta">{meta}</p>
          <div className="crs-tags">{tags.map((tag) => <span key={tag} className="crs-tag">{tag}</span>)}</div>
        </div>
        {nextHref ? <Link href={nextHref} className="btn btn-primary">이어서 학습하기</Link> : null}
      </article>
      <CoursePlanLink courseId={courseId} />
      <div className="crs-filters">
        <div className="crs-filter-row" role="toolbar" aria-label="활동 종류">
          {types.map(([key, label]) => (
            <button key={key} type="button" className={`chip ${key}${type === key ? " on" : ""}`} aria-pressed={type === key} onClick={() => onFilter("type", key)}>{label}</button>
          ))}
        </div>
        <div className="crs-filter-row" role="toolbar" aria-label="진행 상태">
          {[["ongoing", "진행중"], ["missing", "미제출"]].map(([key, label]) => (
            <button key={key} type="button" className={`chip${status === key ? " on" : ""}`} aria-pressed={status === key} onClick={() => onFilter("status", key)}>{label}</button>
          ))}
        </div>
      </div>
      {weeks.length ? <div className="crs-progress" role="group" aria-label="주차">
        {weeks.map((entry) => (
          <button key={entry.number} type="button" className={`crs-wk${week === entry.number ? " on" : ""}`} aria-pressed={week === entry.number} aria-label={`${entry.number}${unit}${entry.title ? ` ${entry.title}` : ""}`} onClick={() => onWeek(entry.number)}>
            <span>{String(entry.number)}</span>
            <span className="crs-wk-mark">{entry.mark || " "}</span>
          </button>
        ))}
      </div> : null}
      {children}
    </section>
  );
}

export function CourseActivitySection({ week, unit = "주", current = false, closed = false, onToggle, children }: {
  week: RoomWeek;
  unit?: string;
  current?: boolean;
  closed?: boolean;
  onToggle?: () => void;
  children: ReactNode;
}) {
  const label = `${week.number}${unit}${week.title ? ` ${week.title}` : ""}`;
  return (
    <section className={`crs-week${closed ? "" : " open"}${current ? " current" : ""}`}>
      {onToggle ? <button type="button" className="crs-week-h" aria-expanded={!closed} onClick={onToggle}>
        <span className="crs-week-label"><strong>{label}</strong><span>{week.range}</span></span>
        <Icon name="chev" />
      </button> : <h2 className="crs-rail-week">{[label, week.range].filter(Boolean).join("  ")}</h2>}
      {closed ? null : onToggle ? <div className="crs-week-b">{children}</div> : children}
    </section>
  );
}

export function CourseEmpty({ title, body, onReload, action }: { title: string; body: string; onReload: () => void; action?: ReactNode }) {
  return (
    <div className="empty crs-empty" role="status">
      <div className="crs-empty-art" aria-hidden="true"><Icon name="layers" /></div>
      <strong>{title}</strong>
      {body ? <p>{body}</p> : null}
      <div className="btn-row">{action}<Button kind="ghost" onClick={onReload}>다시 불러오기</Button></div>
    </div>
  );
}
