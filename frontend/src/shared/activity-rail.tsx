// 활동 상세 화면(자료·영상·과제·시험·설문)의 왼쪽 목록. 원본 e-Class처럼 상세 옆에 그 과목의 학습활동을
// 종류 아이콘과 함께 주차별로 늘 보여 주고, 지금 보는 활동을 표시한다. 종류·진행 상태 칩으로 거르고, 왼쪽 주차 번호로 건너뛴다.
// 행은 course-activities-remote.ts가 모은 서버 활동이고, 상태 글은 강의실과 같다(activity-state.ts).
import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { Link } from "../router.tsx";
import { Icon } from "../ui/icon.tsx";
import { courseIdFor } from "./backend.ts";
import { activityParts, activitySnapshot, passServerFilter, type ActivityRow } from "./course-activities-remote.ts";
import { TYPE_LABEL, instructorIn, useActivityParts, viewType, stateText, periodOf, weekRange } from "./activity-state.ts";
import { todoLabel } from "./home-remote.ts";

const TYPE_ICON: Record<string, string> = { video: "play", zoom: "users", material: "file", report: "file", test: "check", survey: "chat", post: "pen" };
const STATUS_CHIPS: [string, string][] = [["ongoing", "진행중"], ["missing", "미제출"]];

/** 과목을 바꾸지 않는 동안 다른 활동으로 옮겨 가도(상세가 다시 그려져도) 거른 상태가 남게 한다. */
const kept = new Map<string, { type: string; status: string }>();

/** 이 행이 지금 열려 있는 주소인가: 쪽 경로가 같고 행 주소의 모든 질의 값이 현재 주소에 있다. */
function isCurrent(href: string | null): boolean {
  if (!href || typeof window === "undefined") return false;
  const here = new URL(window.location.href);
  const row = new URL(href, here);
  if (row.pathname !== here.pathname) return false;
  for (const [key, value] of row.searchParams) if (here.searchParams.get(key) !== value) return false;
  return true;
}

function RailRow({ row, kj }: { row: ActivityRow; kj: string }) {
  const type = viewType(row);
  const label = TYPE_LABEL[type] || todoLabel(row.kind);
  const state = stateText(row, instructorIn(kj));
  const period = periodOf(row);
  const detail = [label, row.weekNo == null ? "" : `${row.weekNo}주`, period, state].filter(Boolean).join(" · ");
  const current = isCurrent(row.href);
  const leaves = row.external === true && Boolean(row.href);
  const status = row.type === "other" ? "열 수 없음" : state.split(" · ")[0];
  const body = (
    <>
      <span className="act-rail-top">
        <span className={`type-ico ${type}`} aria-hidden="true"><Icon name={TYPE_ICON[type] || "file"} /></span>
        <span className="act-rail-kind">{label}{leaves ? <span className="act-rail-ext"> · e-Class<Icon name="ext" /></span> : null}</span>
        {row.done ? <span className="check" aria-label={status || "완료"}>✓</span> : null}
      </span>
      <b className="act-rail-title" title={row.title || undefined}>{row.title}</b>
      {!row.done && status ? <span className="act-rail-state">{status}</span> : row.done && state.split(" · ").length > 1 ? <span className="act-rail-state">{state.split(" · ").slice(1).join(" · ")}</span> : null}
    </>
  );
  const props = { className: `act-rail-row${current ? " is-current" : ""}`, "data-type": type, "data-row": row.key, title: detail, "aria-current": current ? ("page" as const) : undefined };
  if (row.href && leaves) {
    const name = [row.title, label, row.done ? status || "완료" : status].filter(Boolean).join(", ");
    return <a {...props} href={row.href} target="_blank" rel="noopener noreferrer" data-external="eclass" aria-label={`${name} — 학교 e-Class에서 열기(새 탭)`}>{body}</a>;
  }
  return row.href ? <Link {...props} href={row.href}>{body}</Link> : <div {...props} aria-disabled="true">{body}</div>;
}

export function ActivityRail({ kj }: { kj: string }) {
  const courseId = courseIdFor(kj);
  const instructor = instructorIn(kj);
  const parts = useMemo(() => (courseId == null ? [] : activityParts(courseId, { instructor })), [courseId, instructor]);
  useActivityParts(parts, "activity-rail");
  const snapshot = activitySnapshot(parts, kj, { instructor });
  const [filter, setFilter] = useState(() => kept.get(kj) || { type: "", status: "" });
  const [open, setOpen] = useState(false);
  const scroller = useRef<HTMLDivElement>(null);
  const change = (next: { type: string; status: string }) => {
    kept.set(kj, next);
    setFilter(next);
  };
  const { rows, weeks } = snapshot;
  const present = [...new Set(rows.map(viewType))];
  const shown = rows.filter((row) => (!filter.type || viewType(row) === filter.type) && (!filter.status || passServerFilter(row, filter.status)));
  const weekNos = [...new Set(shown.map((row) => Number(row.weekNo)))].sort((a, b) => a - b);
  const unit = "주";
  const currentKey = shown.find((row) => isCurrent(row.href))?.key;
  // 처음 그릴 때와 다른 활동으로 옮겨 올 때 지금 활동이 보이게 한다.
  useEffect(() => {
    const box = scroller.current;
    const row = currentKey ? box?.querySelector<HTMLElement>(`[data-row="${CSS.escape(currentKey)}"]`) : null;
    if (box && row) box.scrollTop = Math.max(0, row.offsetTop - box.clientHeight / 3);
  }, [currentKey, rows.length]);

  let list: ReactNode;
  if (snapshot.denied) list = <p className="act-rail-note" role="status">이 과목의 활동을 볼 수 없습니다.</p>;
  else if (snapshot.pending && !rows.length) list = <p className="act-rail-note" role="status">활동을 불러오는 중입니다…</p>;
  else if (!shown.length) list = <p className="act-rail-note" role="status">{rows.length ? "해당하는 활동이 없습니다." : "등록된 활동이 없습니다."}</p>;
  else {
    list = (
      <div className="act-rail-scroll" ref={scroller}>
        <nav className="act-rail-weeks" aria-label="주차로 이동">
          {weekNos.map((n) => (
            <button key={n} type="button" aria-label={`${n}${unit}`} onClick={() => scroller.current?.querySelector<HTMLElement>(`[data-week="${n}"]`)?.scrollIntoView({ block: "start" })}>{n}</button>
          ))}
        </nav>
        <div className="act-rail-list">
          {weekNos.map((n) => {
            const entry = weeks.find((item) => item.weekNo === n);
            return (
              <section key={n} className="act-rail-week" data-week={n}>
                <h2 title={weekRange(entry) || undefined}>{`${n} ${unit}`}</h2>
                {shown.filter((row) => Number(row.weekNo) === n).map((row) => <RailRow key={row.key} row={row} kj={kj} />)}
              </section>
            );
          })}
        </div>
      </div>
    );
  }
  return (
    <aside className={`act-rail${open ? " is-open" : ""}`} aria-label="학습활동 목록">
      <button type="button" className="act-rail-toggle" aria-expanded={open} onClick={() => setOpen(!open)}>
        <span>학습활동 목록</span><Icon name="chev" />
      </button>
      <div className="act-rail-panel">
        <div className="act-rail-filters">
          <div className="act-rail-chips" role="toolbar" aria-label="활동 종류">
            {present.map((key) => (
              <button key={key} type="button" className={`chip ${key}${filter.type === key ? " on" : ""}`} aria-pressed={filter.type === key} onClick={() => change({ ...filter, type: filter.type === key ? "" : key })}>{TYPE_LABEL[key] || todoLabel(key)}</button>
            ))}
          </div>
          <div className="act-rail-chips" role="toolbar" aria-label="진행 상태">
            {STATUS_CHIPS.map(([key, label]) => (
              <button key={key} type="button" className={`chip${filter.status === key ? " on" : ""}`} aria-pressed={filter.status === key} onClick={() => change({ ...filter, status: filter.status === key ? "" : key })}>{label}</button>
            ))}
          </div>
        </div>
        {list}
      </div>
    </aside>
  );
}

/** 활동 상세를 왼쪽 활동 목록과 함께 그리는 바깥틀. `itemOpen`은 `data-item-open`(화면 시험·이동 표시)이다. */
export function ActivityShell({ kj, itemOpen, solo = false, sampleBlocked = false, children }: { kj: string; itemOpen?: string | number; solo?: boolean; sampleBlocked?: boolean; children: ReactNode }): ReactNode {
  return (
    <section className={`bleed act-layout has-rail${solo ? " is-solo" : ""} is-open`} data-item-open={itemOpen} data-sample-blocked={sampleBlocked ? "" : undefined}>
      {kj ? <ActivityRail kj={kj} /> : null}
      <div className="act-main">{children}</div>
    </section>
  );
}
