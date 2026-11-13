// 백엔드 연결 때의 교수 강의과목(`GET /api/me/courses`에서 담당 과목)과 수강생 화면(pf_monitor)의 수강생 명단
// (`GET /api/courses/{courseId}/students?q&sort&order&page&size`, 행마다 최근 영상 학습 `lastAccessAt`과 진도율 `progressPercent`)과
// 신청 처리 (`GET /api/courses/{courseId}/enrollment-requests`, `POST …/enrollments/{enrollmentId}/decision`).
// 승인·반려는 읽은 `version`을 보내고, 409(`not_pending`, `version_conflict`)면 목록을 다시 받는다.
// `seat_full`은 신청을 대기로 둔다.
import { Fragment, useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { Button, EmptyState, LoadingState, ErrorState, Pagination, Badge, Table } from "../../ui/kit.tsx";
import { navigate } from "../../router.tsx";
import { getState, render } from "../../store.ts";
import { courses, type Course } from "../../data.ts";
import type { ScreenCtx } from "../../types.ts";
import { ApiError } from "../../shared/api.ts";
import { backendStatus, courseIdFor, roleIn, subscribe } from "../../shared/backend.ts";
import { backendPanel } from "../../shared/backend-prompt.tsx";
import { useResource, type Entry } from "../../shared/remote-cache.ts";
import {
  myCourses, enrollmentRequests, requestKey, decideEnrollment, isConflict, enrollmentMessage,
  REQUEST_PAGE, DECISION_REASON_MAX, STATUS_LABEL, KIND_LABEL, scheduleText, dotDateTime, termName,
  courseRosters, rosterKey, rosterFilter, rosterQuery, ROSTER_PAGE, ROSTER_SORT_LABEL, ROSTER_ROLE_LABEL,
  type EnrollmentRequest, type CourseStudent, type MyCourseItem, type RosterFilter,
} from "../../shared/catalog-remote.ts";
import { koDateTime } from "../../shared/home-remote.ts";

function Retry({ reload }: { reload: () => void }) {
  const [pressed, setPressed] = useState(false);
  return <Button kind="ghost" disabled={pressed} onClick={() => { setPressed(true); reload(); }}>다시 불러오기</Button>;
}

const waiting = <T,>(entry: Entry<T> | null): boolean => !entry || (!entry.data && !entry.error);

/** 강의과목(pf_courses): 담당 과목. 샘플 화면이 있는 과목은 그 카드(`card(local, 0)`)로, 없는 과목은 정보만 보인다. */
export function serverTaughtCourses(card: (course: Course, unread: number) => ReactNode): ReactNode {
  return <TaughtCourses card={card} />;
}

function TaughtCourses({ card }: { card: (course: Course, unread: number) => ReactNode }) {
  const key = "";
  const load = () => { void myCourses.load(key, key); };
  const entry = useResource(myCourses, key, load);
  useEffect(() => { void myCourses.visit("pf:courses", key, key); });
  let body: ReactNode;
  if (!entry || waiting(entry)) body = <LoadingState title="담당 과목을 불러오는 중입니다" />;
  else if (!entry.data) body = <ErrorState title="담당 과목을 불러오지 못했습니다" body={enrollmentMessage(entry.error)} actions={<Retry reload={load} />} />;
  else {
    const rows: MyCourseItem[] = Array.isArray(entry.data) ? entry.data : entry.data.items || [];
    const taught = rows.filter((course) => course.role === "INSTRUCTOR");
    body = !taught.length
      ? <EmptyState title="담당 과목이 없습니다" body="ASTRA 서버에 이 계정이 담당하는 과목이 없습니다." />
      : (
        <>
          <p className="pf-sub" role="status">{`담당 과목 ${taught.length}개`}</p>
          <div className="cards">
            {taught.map((remote) => {
              const kj = "key" in remote ? String(remote.key) : "";
              const local = courses.find((course) => course.id === kj);
              if (local) return <Fragment key={remote.courseId}>{card(local, 0)}</Fragment>;
              return (
                <article key={remote.courseId} className="course-card srv-card">
                  <div className="course-card-main">
                    <div className={`cover g${(Number(remote.courseId) % 8) + 1}`}><b>{remote.name}</b><span>{[remote.code, remote.section].filter(Boolean).join("-")}</span></div>
                    <div className="meta">
                      <div>{[termName(remote.term), remote.type === "NONREGULAR" ? "비정규" : "정규"].filter(Boolean).join(" · ")}</div>
                      <div>{scheduleText(remote.schedules)}</div>
                      <div className="tiny">이 샘플에 강의실 화면이 없는 과목입니다.</div>
                    </div>
                  </div>
                </article>
              );
            })}
          </div>
        </>
      );
  }
  return <div className="srv-list">{body}</div>;
}

// 처리 결과 안내(같은 history 항목에서만).
const notes = new Map<string, { entry: unknown; text: string; alert: boolean }>(); // courseId -> note
subscribe(() => notes.clear());
// 입력 중인 처리 사유. 처리·409 뒤 목록을 다시 그려도 남고, 그 신청을 처리했거나 없어지면(404·`not_pending`) 지운다. 세션이 바뀌면 버린다.
const reasons = new Map<string, string>(); // `${courseId}:${enrollmentId}` -> text
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  reasons.clear();
});
const reasonKey = (courseId: number | string, enrollmentId: number | string) => `${courseId}:${enrollmentId}`;

function Note({ courseId }: { courseId: number | string }) {
  const item = notes.get(String(courseId));
  const live = item && item.entry === history.state;
  return (
    <p className={`srv-msg${live && item.alert ? " is-alert" : ""}`} role={live && item.alert ? "alert" : "status"} aria-live="polite" data-note={`pf-enroll:${courseId}`}>
      {live ? item.text : ""}
    </p>
  );
}

function say(courseId: number | string, text: string, alert = false) {
  notes.set(String(courseId), { entry: history.state, text, alert });
  render();
}

function pageHref(page: number) {
  const params = new URLSearchParams(location.search);
  if (page > 1) params.set("page", String(page));
  else params.delete("page");
  return `${location.pathname}?${params}`;
}

/** 수강생 화면(pf_monitor)의 서버 부분: 처리를 기다리는 수강·청강 신청. */
export function serverEnrollmentRequests(ctx: ScreenCtx, course: Course): ReactNode {
  if (backendStatus() === "anon") return backendPanel({ text: "ASTRA 서버에 로그인하면 이 과목의 수강 신청과 청강 신청을 처리할 수 있습니다." });
  const courseId = courseIdFor(course.id);
  if (!courseId) return <EmptyState title="이 과목은 ASTRA 서버에 없습니다" body="서버에 등록된 과목만 신청을 처리할 수 있습니다." />;
  if (roleIn(course.id) !== "INSTRUCTOR") return <EmptyState title="담당 과목이 아닙니다" body="이 과목을 담당하는 교수 계정으로 로그인해야 신청을 처리할 수 있습니다." />;
  const raw = Number((ctx.params && ctx.params.get("page")) || 1);
  const page = Number.isInteger(raw) && raw >= 1 && raw <= 10000 ? raw : 1;
  return <EnrollmentRequests courseId={courseId} page={page} />;
}

function EnrollmentRequests({ courseId, page }: { courseId: number | string; page: number }) {
  const key = requestKey(courseId, page);
  const load = () => { void enrollmentRequests.load(key, courseId, page); };
  const entry = useResource(enrollmentRequests, key, load);
  useEffect(() => { void enrollmentRequests.visit("pf:requests", key, courseId, page); });
  let body: ReactNode;
  if (!entry || waiting(entry)) body = <><LoadingState title="신청을 불러오는 중입니다" /><Note courseId={courseId} /></>;
  else if (!entry.data) {
    const denied = entry.error instanceof ApiError && entry.error.status === 404;
    body = denied
      ? <EmptyState title="신청을 처리할 수 없습니다" body="이 과목의 교수와 비정규 담당자만 신청을 처리합니다. 조교 계정은 처리할 수 없습니다." />
      : <ErrorState title="신청을 불러오지 못했습니다" body={enrollmentMessage(entry.error)} actions={<Retry reload={load} />} />;
  } else {
    const data = entry.data;
    const items = Array.isArray(data.items) ? data.items : [];
    body = (
      <>
        <h2 className="section-label">{`처리를 기다리는 신청 ${Number(data.total) || 0}건`}</h2>
        <Note courseId={courseId} />
        {items.length
          ? <div className="srv-request-list">{items.map((item) => <RequestRow key={`${item.enrollmentId}:${item.version}`} courseId={courseId} item={item} />)}</div>
          : <EmptyState title="처리를 기다리는 신청이 없습니다" body="학생이 승인 과정에 신청하거나 청강을 신청하면 여기에 모입니다." />}
        <Pagination page={page} pages={Math.max(1, Math.ceil((Number(data.total) || 0) / REQUEST_PAGE))} hrefForPage={pageHref} />
      </>
    );
  }
  return <div className="srv-requests">{body}</div>;
}

const running = new Set<string>();

function RequestRow({ courseId, item }: { courseId: number | string; item: EnrollmentRequest }) {
  const key = reasonKey(courseId, item.enrollmentId);
  const [reason, setReason] = useState(reasons.get(key) || "");
  const [busy, setBusy] = useState<"" | "APPROVE" | "REJECT">("");
  const live = useRef(true);
  useEffect(() => () => { live.current = false; }, []);

  /** 승인 또는 반려. 두 번 눌러도 한 번만 보낸다. */
  async function decide(decision: "APPROVE" | "REJECT") {
    const enrollment = String(item.enrollmentId);
    if (running.has(enrollment) || busy) return;
    const text = reason.trim();
    if (text.length > DECISION_REASON_MAX) {
      say(courseId, `처리 사유는 ${DECISION_REASON_MAX}자 이내로 적어 주세요.`, true);
      return;
    }
    running.add(enrollment);
    setBusy(decision);
    try {
      const receipt = await decideEnrollment(courseId, enrollment, decision, Number(item.version), text);
      reasons.delete(key);
      getState().toast = receipt.status === "ENROLLED" ? "승인했습니다. 학생의 수강이 확정되었습니다" : "반려했습니다";
      render();
    } catch (error) {
      // 처리할 수 없게 된 신청(이미 처리·취소, 없음)의 사유는 버린다. 버전 충돌은 사유를 두고 새 version의 행으로 다시 그린다.
      if (isConflict(error, "not_pending") || (error instanceof ApiError && error.status === 404)) reasons.delete(key);
      if (isConflict(error) && ["not_pending", "version_conflict"].includes(String(error.code))) {
        // 다른 곳에서 먼저 처리했거나 학생이 취소했다: 목록을 서버에서 다시 받는다.
        notes.set(String(courseId), { entry: history.state, text: enrollmentMessage(error), alert: true });
        enrollmentRequests.drop((cached) => cached.startsWith(`${courseId}|`));
        render();
        return;
      }
      say(courseId, isConflict(error, "seat_full") ? "정원이 찼습니다. 이 신청은 승인 대기로 남았습니다. 수강이 확정된 학생이 취소하면 다시 승인할 수 있습니다." : enrollmentMessage(error), true);
    } finally {
      running.delete(enrollment);
      if (live.current) setBusy("");
    }
  }

  const who = item.studentName || "신청자";
  return (
    <article className="srv-request">
      <div className="srv-request__who">
        <b>{item.studentName || "이름 없음"}</b>
        <span className="tiny">{item.studentNumber || ""}</span>
        <Badge>{KIND_LABEL[item.kind] || item.kind}</Badge>
        <Badge>{STATUS_LABEL[item.status] || item.status}</Badge>
      </div>
      <p className="tiny">{`신청 ${dotDateTime(item.appliedAt)}`}</p>
      {item.reason ? <p className="srv-request__reason">{item.reason}</p> : null}
      <label className="field">
        <span className="field-label">처리 사유</span>
        <input
          type="text"
          maxLength={DECISION_REASON_MAX}
          data-decision-reason={String(item.enrollmentId)}
          placeholder="학생에게 남길 처리 사유(선택)"
          value={reason}
          onChange={(event) => {
            const value = event.target.value;
            setReason(value);
            if (value) reasons.set(key, value);
            else reasons.delete(key);
          }}
        />
      </label>
      <div className="btn-row">
        <Button kind="primary" disabled={Boolean(busy)} aria-busy={busy === "APPROVE" ? "true" : undefined} aria-label={`${who} 승인`} onClick={() => { void decide("APPROVE"); }}>
          {busy === "APPROVE" ? "승인하는 중…" : "승인"}
        </Button>
        <Button kind="secondary" disabled={Boolean(busy)} aria-busy={busy === "REJECT" ? "true" : undefined} aria-label={`${who} 반려`} onClick={() => { void decide("REJECT"); }}>
          {busy === "REJECT" ? "반려하는 중…" : "반려"}
        </Button>
      </div>
    </article>
  );
}

// ---------- 수강생 명단 ----------

function rosterHref(page: number) {
  const params = new URLSearchParams(location.search);
  if (page > 1) params.set("spage", String(page));
  else params.delete("spage");
  const q = params.toString();
  return `${location.pathname}${q ? `?${q}` : ""}`;
}

/** 검색어·정렬·순서 줄. 조회가 값을 주소로 옮기고 명단 1쪽으로 간다(다른 주소 값은 그대로). 검색어에서 Enter도 조회다. */
function RosterBar({ filter }: { filter: RosterFilter }) {
  const q = useRef<HTMLInputElement>(null);
  const sort = useRef<HTMLSelectElement>(null);
  const order = useRef<HTMLSelectElement>(null);
  function search() {
    const params = new URLSearchParams(location.search);
    for (const [name, input] of [["q", q.current], ["sort", sort.current], ["order", order.current]] as const) {
      const value = String((input && input.value) || "").trim();
      if (value) params.set(name, value);
      else params.delete(name);
    }
    params.delete("spage");
    navigate(`${location.pathname}?${params}`);
  }
  const onKey = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key !== "Enter" || event.nativeEvent.isComposing) return;
    event.preventDefault();
    search();
  };
  return (
    <div className="srv-filters" role="search" aria-label="수강생 검색">
      <label className="field">
        <span className="field-label">검색어</span>
        <input ref={q} type="search" data-roster="q" defaultValue={filter.q} maxLength={50} placeholder="이름 또는 학번" onKeyDown={onKey} />
      </label>
      <label className="field">
        <span className="field-label">정렬</span>
        <select ref={sort} data-roster="sort" defaultValue={filter.sort || "name"}>
          {Object.entries(ROSTER_SORT_LABEL).map(([value, text]) => <option key={value} value={value}>{text}</option>)}
        </select>
      </label>
      <label className="field">
        <span className="field-label">순서</span>
        <select ref={order} data-roster="order" defaultValue={filter.order || "asc"}>
          <option value="asc">오름차순</option>
          <option value="desc">내림차순</option>
        </select>
      </label>
      <div className="srv-filters__go"><Button kind="primary" onClick={search}>조회</Button></div>
    </div>
  );
}

/** 진도율(0~100). 학생에게 열린 영상 콘텐츠가 없으면 서버가 null을 보내고 "—"로 둔다. */
function progressCell(value: number | null | undefined): ReactNode {
  if (!Number.isInteger(value)) return "—";
  const n = Math.max(0, Math.min(100, Number(value)));
  return (
    <div className="pf-meter">
      <div className="bar" aria-hidden="true"><i style={{ width: `${n}%` }} /></div>
      <span>{`${n}%`}</span>
    </div>
  );
}

function rosterCell(item: CourseStudent): ReactNode {
  const auditor = item.role === "AUDITOR";
  const kind = "kind" in item ? String(item.kind || "") : "";
  return (
    <span className="srv-roster__kind">
      <Badge tone={auditor ? "neutral" : undefined}>{(item.role && ROSTER_ROLE_LABEL[item.role]) || item.role || "수강생"}</Badge>
      {!auditor && kind && KIND_LABEL[kind] ? <span className="tiny">{KIND_LABEL[kind]}</span> : null}
    </span>
  );
}

/** 수강생 화면(pf_monitor)의 서버 명단. 주소 `q`, `sort`(name|studentNumber), `order`(asc|desc), `spage`. */
export function serverCourseRoster(ctx: ScreenCtx, course: Course): ReactNode {
  if (backendStatus() === "anon") return null;
  const courseId = courseIdFor(course.id);
  if (!courseId || roleIn(course.id) !== "INSTRUCTOR") return null;
  const filter = rosterFilter(ctx.params || new URLSearchParams(location.search));
  return <CourseRoster courseId={courseId} filter={filter} />;
}

function CourseRoster({ courseId, filter }: { courseId: number | string; filter: RosterFilter }) {
  const query = rosterQuery(filter);
  const key = rosterKey(courseId, query);
  const load = () => { void courseRosters.load(key, courseId, query); };
  const entry = useResource(courseRosters, key, load);
  useEffect(() => { void courseRosters.visit("pf:roster", key, courseId, query); });
  let list: ReactNode;
  if (!entry || waiting(entry)) list = <LoadingState title="수강생 명단을 불러오는 중입니다" />;
  else if (!entry.data) {
    list = entry.error instanceof ApiError && entry.error.status === 404
      ? <EmptyState title="수강생 명단을 볼 수 없습니다" body="이 과목의 담당 교수, 조교, 비정규 담당자만 명단을 봅니다." />
      : <ErrorState title="수강생 명단을 불러오지 못했습니다" body={enrollmentMessage(entry.error)} actions={<Retry reload={load} />} />;
  } else {
    const data = entry.data;
    const items = Array.isArray(data.items) ? data.items : [];
    const total = Number(data.total) || 0;
    const size = Number(data.size) || ROSTER_PAGE;
    list = (
      <>
        <p className="pf-sub" role="status">{filter.q ? `"${filter.q}"에 맞는 수강생 ${total}명` : `수강생 ${total}명`}</p>
        {items.length
          ? <Table headers={["이름", "학번", "구분", "최근 영상 학습", "진도율"]} rows={items.map((item) => [
            item.name || "이름 없음",
            item.studentNumber || "-",
            rosterCell(item),
            // 그 과목에서 마지막으로 영상 학습을 시작한 일시다(일반 접속 시각이 아니다). 없으면 null.
            item.lastAccessAt ? koDateTime(item.lastAccessAt) || "—" : "—",
            progressCell(item.progressPercent),
          ])} />
          : <EmptyState title={filter.q ? "검색어에 맞는 수강생이 없습니다" : "수강생이 없습니다"} body={filter.q ? "검색어를 바꾸거나 지워 보세요." : "수강이 확정되거나 청강이 승인된 학생이 여기에 나옵니다."} />}
        <Pagination page={Number(data.page) || filter.page} pages={Math.max(1, Math.ceil(total / size))} hrefForPage={rosterHref} />
      </>
    );
  }
  return (
    <section className="srv-roster" aria-label="수강생 명단">
      <h2 className="section-label">수강생 명단</h2>
      <RosterBar key={query} filter={filter} />
      <div className="srv-roster__list">{list}</div>
    </section>
  );
}
