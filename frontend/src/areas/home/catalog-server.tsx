// 백엔드 연결(`ready`) 때의 정규·비정규 카탈로그, 수강 과목과 내 신청, 과목 상세, 수강신청 창.
// 목록·상세는 서버 응답만 그리고 샘플 과목을 섞지 않는다. 서버 과목은 `?id=srv-<courseId>`로 연다.
// 신청·취소·청강 명령은 이 파일의 단추가 처리한다. 서버 글자는 텍스트로만 넣는다.
import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { hrefFor, find } from "../../manifest.ts";
import type { Screen, ScreenCtx } from "../../types.ts";
import { getState, render } from "../../store.ts";
import { Link, navigate, closeDialog } from "../../router.tsx";
import { Badge, Button, EmptyState, ErrorState, Field, LoadingState, Pagination, Table, type ButtonKind } from "../../ui/kit.tsx";
import { courseIdFor, kjOf, subscribe } from "../../shared/backend.ts";
import { courseCover, courseGradient } from "../../shared/course-view.ts";
import { useResource, type Entry, type Resource } from "../../shared/remote-cache.ts";
import { ApiError } from "../../shared/api.ts";
import { ddayAt, koDateTime } from "../../shared/home-remote.ts";
import {
  catalogFilters, regularPages, offeringPages, regularDetails, offeringDetails, myCourses,
  regularFilter, regularQuery, offeringFilter, offeringQuery, myCoursesQuery, serverIdOf,
  applyOffering, requestAudit, updateAuditReason, cancelEnrollment, isConflict, enrollmentMessage,
  CATALOG_PAGE, AUDIT_REASON_MAX, LECTURE_TYPES, ORG_LABEL, DIVISION_LABEL, AUDIENCE_LABEL, AUDIENCE_ORDER,
  STATE_LABEL, STATUS_LABEL, KIND_LABEL, reasonText, scheduleText, dotDateTime, rangeText,
  instructorsText, seatsText, termName,
  myApplications, applicationsKey, APPLICATION_PAGE, PENDING_STATUSES, offeringFilters,
  type CatalogCourse, type CatalogDetail, type CatalogOffering, type CatalogViewer, type EnrollmentReceipt,
  type Id, type MyCourseItem, type NumberedPage,
} from "../../shared/catalog-remote.ts";

const PATH = {
  regular: "/ilos/main/rg/regular_list_form.acl",
  special: "/ilos/main/nr/non_regular_list_form.acl",
  mine: "/ilos/main/rg/regular_register_list_form.acl",
  mineSpecial: "/ilos/main/nr/non_regular_register_list_form.acl",
};

/** A regular course as the detail sends it (the class format comes along). */
type RegularCourse = CatalogCourse & { lectureType?: string | null };
/** A row of `GET /api/me/courses` with the course fields the card shows. */
interface MineItem extends MyCourseItem {
  credit?: number | null;
  lectureType?: string | null;
  learningPeriod?: string | null;
  coverTemplate?: string | null;
}

/** 캐시로 바로 그리고, 화면에 오면 조회한다(새 화면 이동이거나 캐시가 없을 때). key가 null이면 아무것도 하지 않는다. */
function useBound<T, A extends unknown[]>(resource: Resource<T, A>, scope: string, key: string | null, args: A): Entry<T> | null {
  const entry = useResource(resource, key, key == null ? undefined : () => void resource.load(key, ...args));
  useEffect(() => {
    if (key != null) void resource.visit(scope, key, ...args);
  });
  return entry;
}

function Retry<A extends unknown[]>({ resource, id, args }: { resource: Resource<unknown, A>; id: string; args: A }) {
  const [busy, setBusy] = useState(false);
  return (
    <Button kind="ghost" disabled={busy} onClick={() => { setBusy(true); void resource.load(id, ...args).finally(() => setBusy(false)); }}>다시 불러오기</Button>
  );
}

const waiting = <T,>(entry: Entry<T> | null): entry is null => !entry || (!entry.data && !entry.error);

// 명령 결과 안내. 같은 history 항목에서만 보이고, 다른 화면으로 가면 사라진다.
const notes = new Map<string, { entry: unknown; text: string; alert: boolean }>();
subscribe(() => notes.clear());

function setNote(scope: string, text: string, alert: boolean) {
  notes.set(scope, { entry: typeof history !== "undefined" ? history.state : null, text, alert });
}

function Note({ scope }: { scope: string }) {
  const note = notes.get(scope);
  const live = Boolean(note && note.entry === (typeof history !== "undefined" ? history.state : null));
  const alert = live && Boolean(note && note.alert);
  return <p className={`srv-msg${alert ? " is-alert" : ""}`} role={alert ? "alert" : "status"} aria-live="polite">{live && note ? note.text : ""}</p>;
}

/** 안내 줄을 바꾸고 다시 그린다. */
function sayNow(scope: string, text: string, alert: boolean) {
  setNote(scope, text, alert);
  render();
}

export function detailHref(special: boolean, courseId: Id): string {
  return hrefFor(find(special ? "24_nonregular_view" : "25_regular_view"), { id: `srv-${courseId}` });
}

function classroomHref(special: boolean, key: string | null) {
  return hrefFor(find(special ? "70_nr_room_submain" : "40_room_submain"), { kj: key });
}

function pageHref(params: URLSearchParams, page: number) {
  const next = new URLSearchParams(params);
  next.delete("as");
  if (page > 1) next.set("page", String(page));
  else next.delete("page");
  const q = next.toString();
  return `${location.pathname}${q ? `?${q}` : ""}`;
}

function Pager({ params, data, size }: { params: URLSearchParams; data: NumberedPage<unknown>; size: number }) {
  const pages = Math.max(1, Math.ceil((Number(data.total) || 0) / size));
  return <Pagination page={Number(data.page) || 1} pages={pages} hrefForPage={(n) => pageHref(params, n)} />;
}

// ---------- 필터 ----------

function SelectOf({ name, label, options, value }: { name: string; label: string; options: [string, string][]; value: string }) {
  return (
    <Field label={label}>
      <select data-filter={name} defaultValue={value}>
        {options.map(([v, text]) => <option key={v} value={v}>{text}</option>)}
      </select>
    </Field>
  );
}

function Check({ name, label, value, on }: { name: string; label: string; value: string; on: boolean }) {
  return (
    <label className="choice srv-check">
      <input type="checkbox" data-filter={name} value={value} defaultChecked={on} />
      <span>{label}</span>
    </label>
  );
}

/** `srv-catalog-search`: 필터 값을 모아 같은 목록의 1쪽으로 간다. */
function searchCatalog(bar: HTMLElement | null, path: string) {
  if (!bar) return;
  const query = new URLSearchParams();
  for (const input of bar.querySelectorAll<HTMLInputElement | HTMLSelectElement>("[data-filter]")) {
    const name = input.getAttribute("data-filter") || "";
    if (input instanceof HTMLInputElement && input.type === "checkbox") {
      if (input.checked) query.append(name, input.value);
    } else if (String(input.value || "").trim()) query.set(name, String(input.value).trim());
  }
  const q = query.toString();
  navigate(`${path}${q ? `?${q}` : ""}`);
}

/** 필터 줄. 조회 단추가 값을 주소로 옮긴다. 검색어에서 Enter도 조회다. */
function FilterBar({ path, label, controls, q }: { path: string; label: string; controls: ReactNode; q: string }) {
  const bar = useRef<HTMLDivElement>(null);
  const onKey = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key !== "Enter" || event.nativeEvent.isComposing) return;
    event.preventDefault();
    searchCatalog(bar.current, path);
  };
  return (
    <div ref={bar} className="srv-filters" role="search" aria-label={`${label} 조건`}>
      {controls}
      <Field label="검색어">
        <input type="search" data-filter="q" defaultValue={q} maxLength={100} placeholder="과목명 또는 학수번호" aria-label={`${label} 검색어`} onKeyDown={onKey} />
      </Field>
      <div className="srv-filters__go">
        <Button kind="primary" onClick={() => searchCatalog(bar.current, path)}>조회</Button>
      </div>
    </div>
  );
}

// ---------- 카드 ----------

// Imported templates use local covers; other server courses retain their courseId-based colour.
function Cover({ item, children }: { item: { courseId: Id; coverTemplate?: string | null }; children: ReactNode }) {
  const appearance = courseCover(item);
  return (
    <div className={`cover ${appearance.gradient}`}>
      {appearance.cover ? <img className="asset asset-cover" src={appearance.cover} alt="" width={233} height={140} /> : null}
      {children}
    </div>
  );
}

function RegularCard({ item }: { item: CatalogCourse }) {
  return (
    <article className="course-card srv-card">
      <Link className="course-card-main" href={detailHref(false, item.courseId)}>
        <Cover item={item}>
          <b>{item.name}</b>
          <span className="code">{[item.code, item.section].filter(Boolean).join("-")}</span>
          <span className="instructor">{instructorsText(item.instructors)}</span>
        </Cover>
        <div className="card-body">
          <div className="facts">
            <span>{scheduleText(item.schedules)}</span>
            {item.credit != null ? <span>{`${item.credit}학점`}</span> : null}
          </div>
          <div className="tiny">{[termName(item.term), item.departmentName, ORG_LABEL[item.orgSect || ""]].filter(Boolean).join(" · ")}</div>
          {item.auditAllowed ? <span className="act-pill">청강 가능</span> : null}
        </div>
      </Link>
    </article>
  );
}

function OfferingCard({ item, serverTime }: { item: CatalogOffering; serverTime: string | undefined }) {
  const open = item.state === "OPEN";
  const dday = open && item.applicationCloseAt ? ddayAt(item.applicationCloseAt, serverTime) : "";
  return (
    <article className="course-card special-card srv-card">
      <Link className="course-card-main" href={detailHref(true, item.courseId)}>
        <Cover item={item}>
          <b>{dday ? <span className="srv-dday">{dday}</span> : null}{`${item.roundLabel ? `${item.roundLabel} ` : ""}${item.name}`}</b>
          <span className="code">{[item.code, item.section].filter(Boolean).join("-")}</span>
          <span className="who">{instructorsText(item.instructors)}</span>
        </Cover>
        <div className="card-body">
          <div className="facts">
            <span>{rangeText(item.learningStartDate, item.learningEndDate)}</span>
            <span className={`status${open ? " open" : ""}`}>{STATE_LABEL[item.state || ""] || ""}</span>
          </div>
          <div className="tiny">{`신청 ${seatsText(item)} · ${item.lectureType || "수업형태 미정"}`}</div>
          <span className="act-pill room">{item.approvalMode === "MANUAL" ? "담당자 승인" : "자동 승인"}</span>
        </div>
      </Link>
    </article>
  );
}

// ---------- 정규·비정규 전체 ----------

/** 정규과목 전체(20)와 비정규과목 전체(22, 22b). */
export function serverCatalog(screen: Screen, ctx: ScreenCtx): ReactNode {
  const params = ctx.params || new URLSearchParams();
  return screen.catalog === "special" ? <SpecialCatalog params={params} /> : <RegularCatalog params={params} />;
}

function CatalogShell({ title, filters, list }: { title: string; filters: ReactNode; list: ReactNode }) {
  return (
    <section className="srv-catalog" data-area="home">
      <div className="page-head"><h1>{title}</h1></div>
      <div className="srv-filters-slot">{filters}</div>
      <div className="srv-list">{list}</div>
    </section>
  );
}

function SpecialCatalog({ params }: { params: URLSearchParams }) {
  const title = "비정규과목 전체";
  const filter = offeringFilter(params);
  const filtersEntry = useBound(offeringFilters, "home:catalog:categories", filter.div, [filter.div]);
  const data = filtersEntry && filtersEntry.data;
  const categories = data && Array.isArray(data.categories) ? data.categories : [];
  // 과목분류는 고른 비정규 분류(`division=`)의 `categories[]`에서 고르고, 주소의 `cat`도 그 안에 있을 때만 `category=`로 보낸다.
  const cat = categories.some((item) => item.code === filter.cat) ? filter.cat : "";
  const listReady = !filter.cat || Boolean(data) || Boolean(filtersEntry && filtersEntry.error);
  const query = listReady ? offeringQuery({ ...filter, cat }) : null;
  const listEntry = useBound(offeringPages, "home:catalog:special", query, [query || ""]);
  return (
    <CatalogShell
      title={title}
      filters={(
        <>
          <FilterBar
            key={`${params.toString()}|${categories.length}`} path={PATH.special} label={title} q={filter.q}
            controls={(
              <>
                <div className="srv-filters__group"><Check name="open" label="접수 가능 과목만 보기" value="1" on={filter.open} /></div>
                <SelectOf name="aud" label="신청대상" options={[["", "전체"], ...AUDIENCE_ORDER.map((code): [string, string] => [code, AUDIENCE_LABEL[code]])]} value={filter.aud} />
                <SelectOf name="div" label="비정규 분류" options={[["", "전체"], ...Object.entries(DIVISION_LABEL)]} value={filter.div} />
                <SelectOf name="cat" label="과목분류" options={[["", "전체"], ...categories.map((item): [string, string] => [item.code, item.name])]} value={cat} />
                <fieldset className="srv-filters__group">
                  <legend className="field-label">수업형태</legend>
                  {LECTURE_TYPES.map((type) => <Check key={type} name="type" label={type} value={type} on={filter.types.includes(type)} />)}
                </fieldset>
              </>
            )}
          />
          {filtersEntry && filtersEntry.error && !data ? <p className="tiny" role="alert">{`과목분류 목록을 불러오지 못했습니다. ${enrollmentMessage(filtersEntry.error)}`}</p> : null}
        </>
      )}
      list={query == null ? <LoadingState title="과목을 불러오는 중입니다" /> : <CourseCards entry={listEntry} params={params} special listKey={query} />}
    />
  );
}

function RegularCatalog({ params }: { params: URLSearchParams }) {
  const title = "정규과목 전체";
  const filter = regularFilter(params);
  const termKey = filter.term || "";
  const filtersEntry = useBound(catalogFilters, "home:catalog:filters", termKey, [termKey]);
  const query = regularQuery(filter);
  const listEntry = useBound(regularPages, "home:catalog:regular", query, [query]);
  const data = filtersEntry && filtersEntry.data;
  const terms = data && Array.isArray(data.terms) ? data.terms : [];
  const depts = data && Array.isArray(data.departments) ? data.departments : [];
  return (
    <CatalogShell
      title={title}
      filters={(
        <>
          <FilterBar
            key={`${params.toString()}|${terms.length}|${depts.length}`} path={PATH.regular} label={title} q={filter.q}
            controls={(
              <>
                <SelectOf name="term" label="학기" options={[["", "현재 학기"], ...terms.map((term): [string, string] => [String(term.termId), term.name])]} value={filter.term} />
                <SelectOf name="org" label="대학구분" options={[["", "전체"], ...Object.entries(ORG_LABEL)]} value={filter.org} />
                <SelectOf name="dept" label="학과" options={[["", "전체"], ...depts.map((dept): [string, string] => [dept.code, dept.name])]} value={filter.dept} />
                <div className="srv-filters__group"><Check name="audit" label="청강 가능 과목만 보기" value="1" on={filter.audit} /></div>
              </>
            )}
          />
          {filtersEntry && filtersEntry.error && !data ? <p className="tiny" role="alert">{`필터 목록을 불러오지 못했습니다. ${enrollmentMessage(filtersEntry.error)}`}</p> : null}
        </>
      )}
      list={<CourseCards entry={listEntry} params={params} special={false} listKey={query} />}
    />
  );
}

function CourseCards({ entry, params, special, listKey }: {
  entry: Entry<NumberedPage<CatalogCourse>> | Entry<NumberedPage<CatalogOffering>> | null;
  params: URLSearchParams; special: boolean; listKey: string;
}) {
  if (waiting(entry)) return <LoadingState title="과목을 불러오는 중입니다" />;
  if (!entry.data) {
    return (
      <ErrorState
        title="과목을 불러오지 못했습니다" body={enrollmentMessage(entry.error)}
        actions={special ? <Retry resource={offeringPages} id={listKey} args={[listKey]} /> : <Retry resource={regularPages} id={listKey} args={[listKey]} />}
      />
    );
  }
  const data = entry.data;
  const items = Array.isArray(data.items) ? data.items : [];
  return (
    <>
      <p className="tiny filter-count" role="status">{`${Number(data.total) || 0}과목`}</p>
      {items.length
        ? <div className="cards">{items.map((item) => (special ? <OfferingCard key={item.courseId} item={item} serverTime={data.serverTime} /> : <RegularCard key={item.courseId} item={item} />))}</div>
        : <EmptyState title="조건에 맞는 과목이 없습니다" body="필터를 바꾸거나 검색어를 지워 보세요." />}
      <Pager params={params} data={data} size={CATALOG_PAGE} />
    </>
  );
}

// ---------- 수강 과목 ----------

function previousTerm<T extends { current?: boolean }>(terms: T[]): T | null {
  const at = terms.findIndex((term) => term.current);
  return at >= 0 ? terms[at + 1] || null : terms[1] || null;
}

/**
 * 정규 수강과목(21, 21b 지난 학기)과 비정규 수강과목(23). 담당하거나 수강이 확정된 과목만 나온다. 처리를 기다리는 신청과
 * 지난 신청은 아래 내 신청(23은 비정규 신청, 21은 청강 신청, `?apage=`)에 있다.
 */
export function serverCourseList(screen: Screen, ctx: ScreenCtx): ReactNode {
  const params = ctx.params || new URLSearchParams();
  return screen.term === "special-enrolled"
    ? <SpecialCourseList screen={screen} params={params} />
    : <RegularCourseList screen={screen} params={params} />;
}

function CourseListShell({ special, head, list, children }: { special: boolean; head: ReactNode; list: ReactNode; children?: ReactNode }) {
  return (
    <section className="srv-catalog" data-area="home">
      <div className="page-head"><div><h1>수강과목</h1><p className="term tight">{special ? "비정규 · 수강 확정" : "정규과목"}</p></div></div>
      <div className="srv-filters-slot">{head}</div>
      <Note scope="home:mine" />
      <div className="srv-list">{list}</div>
      {children}
    </section>
  );
}

function SpecialCourseList({ screen, params }: { screen: Screen; params: URLSearchParams }) {
  const q = String(params.get("q") || "").trim().slice(0, 100);
  const query = myCoursesQuery({ type: "NONREGULAR", termId: "", q });
  const entry = useBound(myCourses, `home:mine:${screen.id}`, query, [query]);
  return (
    <CourseListShell special head={<FilterBar key={params.toString()} path={PATH.mineSpecial} label="수강과목" controls={null} q={q} />} list={<MineList entry={entry} special listKey={query} />}>
      <ApplicationsSection kind="NONREGULAR" params={params} />
      <p className="term-link"><Link href={hrefFor(find("22_nonregular_list"))}>비정규과목 전체</Link></p>
    </CourseListShell>
  );
}

function RegularCourseList({ screen, params }: { screen: Screen; params: URLSearchParams }) {
  const q = String(params.get("q") || "").trim().slice(0, 100);
  const asked = /^[1-9]\d{0,15}$/.test(params.get("termId") || "") ? params.get("termId") || "" : "";
  const termsEntry = useBound(catalogFilters, "home:mine:terms", "", [""]);
  const data = termsEntry && termsEntry.data;
  const terms = data && Array.isArray(data.terms) ? data.terms : [];
  let termId: string | null = null;
  if (!waiting(termsEntry)) {
    const chosen = asked || (screen.term === "2026-1" ? previousTerm(terms) : null);
    termId = typeof chosen === "string" ? chosen : chosen ? String(chosen.termId) : "";
  }
  const query = termId == null ? null : myCoursesQuery({ type: "REGULAR", termId, q });
  const entry = useBound(myCourses, `home:mine:${screen.id}`, query, [query || ""]);
  const head = termId == null
    ? <LoadingState title="학기를 불러오는 중입니다" />
    : (
      <>
        <FilterBar
          key={`${params.toString()}|${terms.length}`} path={PATH.mine} label="수강과목" q={q}
          controls={<SelectOf name="termId" label="학기" options={[["", "전체 학기"], ...terms.map((term): [string, string] => [String(term.termId), term.current ? `${term.name} (현재)` : term.name])]} value={termId} />}
        />
        {!data && termsEntry ? <p className="tiny" role="alert">{`학기 목록을 불러오지 못했습니다. ${enrollmentMessage(termsEntry.error)}`}</p> : null}
      </>
    );
  return (
    <CourseListShell special={false} head={head} list={query == null ? null : <MineList entry={entry} special={false} listKey={query} />}>
      {screen.term !== "2026-1" ? <ApplicationsSection kind="AUDIT" params={params} /> : null}
    </CourseListShell>
  );
}

// ---------- 내 신청 ----------

function appsHref(page: number) {
  const next = new URLSearchParams(location.search);
  next.delete("as");
  if (page > 1) next.set("apage", String(page));
  else next.delete("apage");
  const q = next.toString();
  return `${location.pathname}${q ? `?${q}` : ""}`;
}

function applicationStatus(item: EnrollmentReceipt) {
  if (item.kind === "AUDIT" && item.status === "ENROLLED") return "청강 승인";
  return STATUS_LABEL[item.status] || item.status || "";
}

function cancelLabel(item: EnrollmentReceipt) {
  if (item.status !== "ENROLLED") return "신청 취소";
  return item.kind === "AUDIT" ? "청강 취소" : "수강 취소";
}

function ApplicationRow({ item }: { item: EnrollmentReceipt }) {
  const course: Partial<CatalogCourse & CatalogOffering> & { offering?: CatalogOffering | null } = item.course || {};
  const special = course.type === "NONREGULAR";
  const offering = course.offering;
  const name = offering && offering.roundLabel ? `${offering.roundLabel} ${course.name || ""}` : course.name || "과목";
  const href = course.courseId ? detailHref(special, course.courseId) : null;
  const dates = ([["신청", item.appliedAt], ["처리", item.decidedAt], ["취소", item.cancelledAt]] as const)
    .filter(([, value]) => value)
    .map(([label, value]) => `${label} ${koDateTime(value)}`)
    .join(" · ");
  return (
    <article className="srv-request srv-app" data-enrollment={String(item.enrollmentId)}>
      <div className="srv-request__who">
        {href ? <Link href={href} className="srv-app__name">{name}</Link> : <b>{name}</b>}
        <span className="tiny">{[course.code, course.section].filter(Boolean).join("-")}</span>
        <Badge>{KIND_LABEL[item.kind || ""] || item.kind}</Badge>
        <Badge>{applicationStatus(item)}</Badge>
      </div>
      <p className="tiny">{[termName(course.term), offering ? `학습기간 ${rangeText(offering.learningStartDate, offering.learningEndDate)}` : ""].filter(Boolean).join(" · ")}</p>
      <p className="tiny">{dates}</p>
      {item.reason ? <p className="srv-request__reason">{`청강 사유: ${item.reason}`}</p> : null}
      {item.cancellable && course.courseId != null
        ? <div className="btn-row"><CancelButton courseId={course.courseId} enrollmentId={item.enrollmentId} wasEnrolled={item.status === "ENROLLED"} scope="home:apps" label={cancelLabel(item)} /></div>
        : null}
    </article>
  );
}

/** 내 신청(`GET /api/me/applications?kind=`): 처리를 기다리는 신청과 지난 신청. 취소는 `cancellable`일 때만 보인다. */
function ApplicationsSection({ kind, params }: { kind: "AUDIT" | "NONREGULAR"; params: URLSearchParams }) {
  const raw = Number(params.get("apage"));
  const page = Number.isInteger(raw) && raw >= 1 && raw <= 10000 ? raw : 1;
  const key = applicationsKey(kind, page);
  const title = kind === "AUDIT" ? "내 청강 신청" : "내 비정규 신청";
  const entry = useBound(myApplications, `home:apps:${kind}`, key, [kind, page]);
  let list: ReactNode;
  if (waiting(entry)) list = <LoadingState title="신청을 불러오는 중입니다" />;
  else if (!entry.data) list = <ErrorState title="신청을 불러오지 못했습니다" body={enrollmentMessage(entry.error)} actions={<Retry resource={myApplications} id={key} args={[kind, page]} />} />;
  else {
    const data = entry.data;
    const items = Array.isArray(data.items) ? data.items : [];
    const pending = items.filter((item) => PENDING_STATUSES.has(item.status));
    const past = items.filter((item) => !PENDING_STATUSES.has(item.status));
    const group = (label: string, rows: EnrollmentReceipt[]) => (rows.length
      ? <div className="srv-request-list"><h3 className="section-label">{label}</h3>{rows.map((item) => <ApplicationRow key={item.enrollmentId} item={item} />)}</div>
      : null);
    list = (
      <>
        <p className="tiny filter-count" role="status">{`신청 ${Number(data.total) || 0}건`}</p>
        {items.length
          ? <div className="stack">{group("처리를 기다리는 신청", pending)}{group("지난 신청", past)}</div>
          : <EmptyState title="신청한 내역이 없습니다" body={kind === "AUDIT" ? "정규과목 상세에서 청강을 신청할 수 있습니다." : "비정규과목 전체에서 과정을 신청할 수 있습니다."} />}
        <Pagination page={Number(data.page) || page} pages={Math.max(1, Math.ceil((Number(data.total) || 0) / APPLICATION_PAGE))} hrefForPage={appsHref} />
      </>
    );
  }
  return (
    <section className="srv-apps" aria-label={title}>
      <h2 className="section-label">{title}</h2>
      <p className="tiny srv-hint">승인을 기다리는 신청은 수강과목에 나오지 않고 여기에만 있습니다. 취소할 수 있는 신청에만 취소 단추가 있습니다.</p>
      <Note scope="home:apps" />
      <div className="srv-list">{list}</div>
    </section>
  );
}

function MineList({ entry, special, listKey }: { entry: Entry<NumberedPage<MyCourseItem>> | null; special: boolean; listKey: string }) {
  if (waiting(entry)) return <LoadingState title="수강 과목을 불러오는 중입니다" />;
  if (!entry.data) return <ErrorState title="수강 과목을 불러오지 못했습니다" body={enrollmentMessage(entry.error)} actions={<Retry resource={myCourses} id={listKey} args={[listKey]} />} />;
  // `GET /api/me/courses` answers with the rows themselves.
  const rows: unknown = entry.data;
  const items: MineItem[] = Array.isArray(rows) ? rows : [];
  return items.length
    ? <div className="cards">{items.map((item) => <MineCard key={item.courseId} item={item} special={special} />)}</div>
    : <EmptyState title="수강 중인 과목이 없습니다" body={special ? "비정규과목 전체에서 과정을 신청할 수 있습니다." : "다른 학기를 고르거나 검색어를 지워 보세요."} />;
}

function MineCard({ item, special }: { item: MineItem; special: boolean }) {
  // 내 과목은 강의실로 연다(`kj` = 샘플 ID 키 또는 `c<courseId>`).
  const mine = item.enrollment;
  const offering = item.offering;
  return (
    <article className="course-card srv-card">
      <Link className="course-card-main" href={classroomHref(special, kjOf(item))}>
        <Cover item={item}>
          <b>{offering && offering.roundLabel ? `${offering.roundLabel} ${item.name}` : item.name}</b>
          <span className="code">{[item.code, item.section].filter(Boolean).join("-")}</span>
          <span className="instructor">{instructorsText(item.instructors)}</span>
        </Cover>
        <div className="card-body">
          <div className="facts">
            <span>{offering ? rangeText(offering.learningStartDate, offering.learningEndDate) : item.learningPeriod || scheduleText(item.schedules)}</span>
            {item.lectureType ? <span>{item.lectureType}</span> : null}
            {item.credit != null && !special ? <span>{`${item.credit}학점`}</span> : null}
          </div>
          <div className="tiny">{[termName(item.term), item.role === "INSTRUCTOR" ? "담당" : mine ? KIND_LABEL[mine.kind || ""] : ""].filter(Boolean).join(" · ")}</div>
          <span className="act-pill room">강의실</span>
        </div>
      </Link>
      <Link href={hrefFor(find("study_home"), { kj: kjOf(item) })} className="btn btn-primary course-study-entry" aria-label={`${item.name} 학습실 입장`} resetScroll>학습실 입장</Link>
      {mine && mine.cancellable
        ? <CancelButton courseId={item.courseId} enrollmentId={mine.enrollmentId} wasEnrolled={mine.status === "ENROLLED"} scope="home:mine" label={mine.kind === "AUDIT" ? "청강 취소" : "수강 취소"} />
        : null}
    </article>
  );
}

// ---------- 명령 ----------

const running = new Set<string>(); // 진행 중인 명령: 두 번 눌러도 한 번만 보낸다

/**
 * A command button: while `run` is in flight it is disabled, `aria-busy`, and shows `busyLabel`.
 * With `armLabel`, the first press only arms it (shows `armLabel`); the second runs.
 */
function CommandButton({ id, label, busyLabel, armLabel, kind, run }: {
  id: string; label: string; busyLabel: string; armLabel?: string; kind: ButtonKind; run: () => Promise<void>;
}) {
  const [phase, setPhase] = useState<"idle" | "armed" | "busy">("idle");
  const onClick = () => {
    if (armLabel && phase === "idle") {
      setPhase("armed");
      return;
    }
    if (running.has(id) || phase === "busy") return;
    running.add(id);
    setPhase("busy");
    void run().finally(() => {
      running.delete(id);
      setPhase("idle");
    });
  };
  return (
    <Button kind={kind} disabled={phase === "busy"} aria-busy={phase === "busy" ? "true" : undefined} onClick={onClick}>
      {phase === "busy" ? busyLabel : phase === "armed" ? armLabel : label}
    </Button>
  );
}

const statusOf = (error: unknown) => (error instanceof ApiError ? error.status : undefined);
const codeOf = (error: unknown) => (error instanceof ApiError ? error.code : null);

/** `srv-enroll-cancel`: 첫 번째 누름은 확인, 두 번째에 취소를 보낸다. Idempotent on the server. */
function CancelButton({ courseId, enrollmentId, wasEnrolled, scope, label = "신청 취소" }: {
  courseId: Id; enrollmentId: Id; wasEnrolled: boolean; scope: string; label?: string;
}) {
  const run = async () => {
    try {
      await cancelEnrollment(courseId, enrollmentId, { wasEnrolled });
      getState().toast = "신청을 취소했습니다";
      render();
    } catch (error) {
      // 상태가 이미 바뀐 신청(취소 마감, 처리됨)이면 상세와 목록을 서버에서 다시 받아 그린다.
      if (isConflict(error) && codeOf(error) !== "idempotency_in_progress") {
        setNote(scope, `${enrollmentMessage(error)} 최신 상태를 불러왔습니다.`, true);
        offeringDetails.drop((key) => key === String(courseId));
        regularDetails.drop((key) => key === String(courseId));
        myCourses.drop(() => true);
        myApplications.drop(() => true);
        render();
      } else sayNow(scope, enrollmentMessage(error), true);
    }
  };
  return <CommandButton id={`cancel:${enrollmentId}`} label={label} busyLabel="취소하는 중…" armLabel="한 번 더 누르면 취소" kind="secondary" run={run} />;
}

// ---------- 상세 ----------

/** 상세 화면(24, 25)의 서버 과목 ID: `?id=srv-<id>`, 아니면 샘플 과목과 짝지은 서버 과목. */
function detailId(screen: Screen, ctx: ScreenCtx): string | null {
  const raw = (ctx.params && ctx.params.get("id")) || "";
  const paired = courseIdFor(raw || screen.course);
  return serverIdOf(raw) || (paired != null ? String(paired) : null);
}

export function serverCatalogDetail(screen: Screen, ctx: ScreenCtx): ReactNode {
  const special = screen.path.includes("/nr/");
  const id = detailId(screen, ctx);
  const listHref = hrefFor(find(special ? "22_nonregular_list" : "20_regular_list"));
  if (!id) {
    return (
      <section className="detail" data-area="home">
        <EmptyState title="ASTRA 서버에서 찾을 수 없는 과목입니다" body="목록에서 서버에 등록된 과목을 골라 주세요." actions={<Link href={listHref} className="btn btn-secondary">목록</Link>} />
      </section>
    );
  }
  return <CatalogDetailView special={special} id={id} listHref={listHref} />;
}

function CatalogDetailView({ special, id, listHref }: { special: boolean; id: string; listHref: string }) {
  const resource = special ? offeringDetails : regularDetails;
  const entry = useBound(resource, `home:detail:${special ? "nr" : "rg"}`, id, [id]);
  let body: ReactNode;
  if (waiting(entry)) body = <LoadingState title="과목 정보를 불러오는 중입니다" />;
  else if (!entry.data) {
    body = (
      <ErrorState
        title="과목 정보를 불러오지 못했습니다" body={enrollmentMessage(entry.error)}
        actions={<><Retry resource={resource} id={id} args={[id]} /><Link href={listHref} className="btn btn-secondary">목록</Link></>}
      />
    );
  } else body = special ? <OfferingView data={entry.data} listHref={listHref} /> : <RegularView data={entry.data} listHref={listHref} />;
  return <section className="detail srv-detail" data-area="home">{body}</section>;
}

function Hero({ item, kicker, title, pills }: {
  item: { courseId?: Id; code?: string | null; section?: string | null; instructors?: string[] | null };
  kicker: string; title: string; pills: (string | null | undefined)[];
}) {
  return (
    <div className={`detail-hero ${courseGradient(item.courseId)}`}>
      <p className="k">{kicker}</p>
      <h1>{title}</h1>
      <p>{`${[item.code, item.section].filter(Boolean).join("-")} · ${instructorsText(item.instructors)}`}</p>
      <div className="hero-pills">{pills.filter(Boolean).map((text, index) => <span key={index}>{text}</span>)}</div>
    </div>
  );
}

function Kv({ rows }: { rows: [string, string | null | undefined][] }) {
  return <dl className="kv">{rows.map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v || "—"}</dd></div>)}</dl>;
}

function RegularView({ data, listHref }: { data: CatalogDetail; listHref: string }) {
  const item: Partial<RegularCourse> = data.course || {};
  const viewer: CatalogViewer = data.viewer || {};
  const role = viewer.role;
  const audit = viewer.audit;
  const scope = `home:detail:rg:${item.courseId}`;
  return (
    <>
      <Hero item={item} kicker={termName(item.term) || "정규과목"} title={item.name || ""} pills={["정규", item.auditAllowed ? "청강 가능" : null, role ? "구성원" : null]} />
      <div className="detail-grid">
        <div className="sheet">
          <h2>이수 정보</h2>
          <Table
            headers={["항목", "내용"]}
            rows={[
              ["담당", instructorsText(item.instructors)],
              ["학기", termName(item.term) || "—"],
              ["대학구분", ORG_LABEL[item.orgSect || ""] || "—"],
              ["학과", item.departmentName || "—"],
              ["학점", item.credit != null ? `${item.credit}학점` : "—"],
              ["이수구분", item.completionType || "—"],
              ["학년", item.gradeYear ? `${item.gradeYear}학년` : "—"],
              ["수업형태", item.lectureType || "—"],
              ["수업 시간", scheduleText(item.schedules)],
              ["합반", item.merged ? "합반 과목" : "아님"],
            ]}
          />
          <p className="tiny">강의계획서 본문과 수강생 명단은 카탈로그에 공개하지 않습니다.</p>
        </div>
        <div className="sheet side">
          <h2>{item.name}</h2>
          <p className="tiny">{termName(item.term)}</p>
          <Kv
            rows={[
              ["내 역할", role === "INSTRUCTOR" ? "담당" : role === "STUDENT" ? (audit && audit.status === "ENROLLED" ? "청강" : "수강") : "구성원 아님"],
              ["청강", item.auditAllowed ? "허용" : "받지 않음"],
              ["청강 신청", audit ? STATUS_LABEL[audit.status] || audit.status : "없음"],
            ]}
          />
          <div className="btn-row">
            {role ? <Link href={classroomHref(false, kjOf(item))} className="btn btn-primary">강의실 입장</Link> : null}
            <Link href={listHref} className="btn btn-secondary">목록</Link>
          </div>
          {item.courseId != null ? <AuditSection courseId={item.courseId} viewer={viewer} scope={scope} /> : null}
          <Note scope={scope} />
        </div>
      </div>
    </>
  );
}

// 입력 중인 청강 사유. 다시 그리거나 409로 다시 받아도 남고, 세션이 바뀌면 버린다.
const reasonDrafts = new Map<string, string>(); // courseId -> text
subscribe(() => reasonDrafts.clear());

const typedReason = (courseId: Id, initial: string) => (reasonDrafts.get(String(courseId)) ?? initial).trim();

function ReasonBox({ courseId, initial }: { courseId: Id; initial: string }) {
  const key = String(courseId);
  const [value, setValue] = useState(reasonDrafts.get(key) ?? initial);
  return (
    <Field label={`청강 사유 (${AUDIT_REASON_MAX}자 이내)`}>
      <textarea
        maxLength={AUDIT_REASON_MAX} rows={4} placeholder="청강하려는 이유를 적어 주세요." value={value}
        onChange={(event) => {
          setValue(event.target.value);
          reasonDrafts.set(key, event.target.value);
        }}
      />
    </Field>
  );
}

/** 사유가 비었거나 길면 안내하고 false. */
function checkReason(scope: string, reason: string): boolean {
  if (!reason) sayNow(scope, "청강 사유를 입력하세요.", true);
  else if (reason.length > AUDIT_REASON_MAX) sayNow(scope, `청강 사유는 ${AUDIT_REASON_MAX}자 이내로 적어 주세요.`, true);
  else return true;
  return false;
}

/** `srv-audit-request`: 정규과목 청강 신청. */
async function requestAuditFor(courseId: Id, scope: string, reason: string) {
  try {
    await requestAudit(courseId, reason);
    reasonDrafts.delete(String(courseId));
    getState().toast = "청강을 신청했습니다. 담당 교수의 승인을 기다립니다";
    render();
  } catch (error) {
    if (isConflict(error) && codeOf(error) !== "idempotency_in_progress") void regularDetails.load(String(courseId), courseId);
    sayNow(scope, enrollmentMessage(error), true);
  }
}

/** `srv-audit-reason`: REQUESTED인 청강 사유 수정(읽은 `version`). 409면 다시 받고 입력은 남긴다. */
async function saveAuditReason(courseId: Id, audit: EnrollmentReceipt, scope: string, reason: string) {
  try {
    await updateAuditReason(courseId, audit.enrollmentId, reason, Number(audit.version));
    reasonDrafts.delete(String(courseId));
    getState().toast = "청강 사유를 저장했습니다";
    render();
  } catch (error) {
    if (isConflict(error)) {
      setNote(scope, enrollmentMessage(error), true);
      void regularDetails.load(String(courseId), courseId);
      return;
    }
    sayNow(scope, enrollmentMessage(error), true);
  }
}

/** A command that first checks the typed reason (outside the busy state, like the legacy command). */
function ReasonCommand({ id, label, courseId, initial, scope, send }: {
  id: string; label: string; courseId: Id; initial: string; scope: string; send: (reason: string) => Promise<void>;
}) {
  const [busy, setBusy] = useState(false);
  return (
    <Button
      kind="primary" disabled={busy} aria-busy={busy ? "true" : undefined}
      onClick={() => {
        const reason = typedReason(courseId, initial);
        if (!checkReason(scope, reason) || running.has(id) || busy) return;
        running.add(id);
        setBusy(true);
        void send(reason).finally(() => {
          running.delete(id);
          setBusy(false);
        });
      }}
    >
      {busy ? (id.startsWith("reason:") ? "저장하는 중…" : "신청하는 중…") : label}
    </Button>
  );
}

function AuditSection({ courseId, viewer, scope }: { courseId: Id; viewer: CatalogViewer; scope: string }) {
  const audit = viewer.audit;
  if (audit && audit.status === "REQUESTED") {
    const initial = audit.reason || "";
    return (
      <div className="srv-audit">
        <h3>청강</h3>
        <p className="tiny">청강 신청이 담당 교수의 승인을 기다리고 있습니다. 승인 전까지 사유를 고치거나 취소할 수 있습니다.</p>
        <ReasonBox courseId={courseId} initial={initial} />
        <div className="btn-row">
          <ReasonCommand id={`reason:${audit.enrollmentId}`} label="사유 저장" courseId={courseId} initial={initial} scope={scope} send={(reason) => saveAuditReason(courseId, audit, scope, reason)} />
          {audit.cancellable ? <CancelButton courseId={courseId} enrollmentId={audit.enrollmentId} wasEnrolled={false} scope={scope} label="청강 신청 취소" /> : null}
        </div>
      </div>
    );
  }
  if (audit && audit.status === "ENROLLED") {
    return (
      <div className="srv-audit">
        <h3>청강</h3>
        <p className="tiny">청강이 승인되었습니다. 강의실에서 학습할 수 있습니다.</p>
        {audit.cancellable ? <div className="btn-row"><CancelButton courseId={courseId} enrollmentId={audit.enrollmentId} wasEnrolled scope={scope} label="청강 취소" /></div> : null}
      </div>
    );
  }
  const reasons = Array.isArray(viewer.reasons) ? viewer.reasons : [];
  return (
    <div className="srv-audit">
      <h3>청강</h3>
      {audit ? <p className="tiny">{`지난 청강 신청: ${STATUS_LABEL[audit.status] || audit.status}`}</p> : null}
      {viewer.canRequestAudit ? (
        <>
          <ReasonBox courseId={courseId} initial="" />
          <div className="btn-row">
            <ReasonCommand id={`audit:${courseId}`} label="청강 신청" courseId={courseId} initial="" scope={scope} send={(reason) => requestAuditFor(courseId, scope, reason)} />
          </div>
        </>
      ) : <ul className="srv-reasons">{reasons.map((code) => <li key={code}>{reasonText(code)}</li>)}</ul>}
    </div>
  );
}

function OfferingView({ data, listHref }: { data: CatalogDetail; listHref: string }) {
  const item: Partial<CatalogOffering> = data.offering || {};
  const viewer: CatalogViewer = data.viewer || {};
  const mine = viewer.application;
  const scope = `home:detail:nr:${item.courseId}`;
  const open = item.state === "OPEN";
  const dday = open && item.applicationCloseAt ? ddayAt(item.applicationCloseAt, data.serverTime) : "";
  const reasons = Array.isArray(viewer.reasons) ? viewer.reasons : [];
  return (
    <>
      <Hero
        item={item} kicker={DIVISION_LABEL[item.division || ""] || "비정규과목"} title={`${item.roundLabel ? `${item.roundLabel} ` : ""}${item.name}`}
        pills={["비정규", STATE_LABEL[item.state || ""], dday || null, mine ? STATUS_LABEL[mine.status] : null]}
      />
      <div className="detail-grid">
        <div className="sheet">
          <h2>과정 정보</h2>
          <Table
            headers={["항목", "내용"]}
            rows={[
              ["담당", instructorsText(item.instructors)],
              ["분류", DIVISION_LABEL[item.division || ""] || "—"],
              ["수업형태", item.lectureType || "—"],
              ["학습 기간", rangeText(item.learningStartDate, item.learningEndDate)],
              ["접수 기간", rangeText(item.applicationOpenAt, item.applicationCloseAt, dotDateTime)],
              ["신청대상", (Array.isArray(item.audiences) ? item.audiences : []).map((code) => AUDIENCE_LABEL[code] || code).join(", ") || "없음"],
              ["승인", item.approvalMode === "MANUAL" ? "담당자가 승인한 뒤 확정" : "신청하면 바로 확정"],
              ["정원", seatsText(item)],
              ["수강 취소", item.cancelAllowed ? `가능${item.cancelCloseAt ? ` (${dotDateTime(item.cancelCloseAt)}까지)` : ""}` : "확정 뒤에는 취소할 수 없음"],
            ]}
          />
          <p className="tiny">{`접수 상태는 서버 시각 ${dotDateTime(data.serverTime)} 기준입니다.`}</p>
        </div>
        <div className="sheet side">
          <h2>{item.name}</h2>
          <p className="tiny">{[item.code, item.section].filter(Boolean).join("-")}</p>
          <Kv
            rows={[
              ["접수", STATE_LABEL[item.state || ""] || "—"],
              ["내 신청", mine ? STATUS_LABEL[mine.status] || mine.status : "없음"],
              ["정원", seatsText(item)],
            ]}
          />
          <div className="btn-row">
            {mine && mine.status === "ENROLLED" ? <Link href={classroomHref(true, kjOf(item))} className="btn btn-primary">강의실 입장</Link> : null}
            {viewer.eligible ? <Link href={hrefFor(find("24c_nonregular_apply_pop"), { id: `srv-${item.courseId}` })} className="btn btn-primary">수강 신청</Link> : null}
            {mine && mine.cancellable && item.courseId != null
              ? <CancelButton courseId={item.courseId} enrollmentId={mine.enrollmentId} wasEnrolled={mine.status === "ENROLLED"} scope={scope} label={mine.status === "ENROLLED" ? "수강 취소" : "신청 취소"} />
              : null}
            <Link href={listHref} className="btn btn-secondary">목록</Link>
          </div>
          {!viewer.eligible && reasons.length ? <ul className="srv-reasons">{reasons.map((code) => <li key={code}>{reasonText(code)}</li>)}</ul> : null}
          <Note scope={scope} />
        </div>
      </div>
    </>
  );
}

// ---------- 신청 창 ----------

/** `srv-enroll-apply`: 신청 창의 신청. A retry after a lost response reuses the Idempotency-Key. */
async function applyFor(courseId: Id) {
  try {
    const receipt = await applyOffering(courseId);
    getState().toast = receipt && receipt.status === "ENROLLED"
      ? "수강 신청이 완료되었습니다. 수강과목에 추가했습니다"
      : "신청했습니다. 담당자의 승인을 기다립니다";
    navigate(detailHref(true, courseId));
  } catch (error) {
    if (isConflict(error) && codeOf(error) !== "idempotency_in_progress") void offeringDetails.load(String(courseId), courseId);
    const lost = [0, 502, 503, 504].includes(statusOf(error) ?? -1);
    sayNow("home:apply", lost
      ? `${enrollmentMessage(error)} 신청이 접수됐는지 확인하려면 신청을 다시 누르세요. 같은 신청으로 확인합니다.`
      : enrollmentMessage(error), true);
  }
}

/** `?pop=apply&id=srv-<courseId>`의 본문(서버 미리보기와 신청 단추). */
export function serverApplyBody(ctx: ScreenCtx): ReactNode {
  const id = serverIdOf(ctx.params && ctx.params.get("id"));
  if (!id) return <EmptyState title="ASTRA 서버의 과정을 골라 주세요" body="비정규과목 전체에서 과정을 연 뒤 신청할 수 있습니다." />;
  return <ApplyBody id={id} />;
}

function ApplyBody({ id }: { id: string }) {
  const entry = useBound(offeringDetails, "home:apply", id, [id]);
  let body: ReactNode;
  if (waiting(entry)) body = <LoadingState title="과정 정보를 불러오는 중입니다" />;
  else if (!entry.data) body = <ErrorState title="과정 정보를 불러오지 못했습니다" body={enrollmentMessage(entry.error)} actions={<Retry resource={offeringDetails} id={id} args={[id]} />} />;
  else {
    const item: Partial<CatalogOffering> = entry.data.offering || {};
    const viewer: CatalogViewer = entry.data.viewer || {};
    const reasons = Array.isArray(viewer.reasons) ? viewer.reasons : [];
    const courseId = item.courseId;
    body = (
      <>
        <p><b>{`${item.roundLabel ? `${item.roundLabel} ` : ""}${item.name}`}</b>{` (${[item.code, item.section].filter(Boolean).join("-")})`}</p>
        <p className="tiny">{`접수 ${rangeText(item.applicationOpenAt, item.applicationCloseAt, dotDateTime)} · 정원 ${seatsText(item)}`}</p>
        <p>
          {item.approvalMode === "MANUAL"
            ? "신청하면 담당자의 승인을 기다립니다. 승인되면 수강과목에 나옵니다."
            : "신청하면 바로 수강이 확정되고 수강과목에 나옵니다."}
        </p>
        {viewer.eligible && courseId != null ? (
          <div className="btn-row">
            <Button kind="ghost" onClick={closeDialog}>취소</Button>
            <CommandButton id={`apply:${courseId}`} label="신청" busyLabel="신청하는 중…" kind="primary" run={() => applyFor(courseId)} />
          </div>
        ) : <ul className="srv-reasons">{reasons.map((code) => <li key={code}>{reasonText(code)}</li>)}</ul>}
        <Note scope="home:apply" />
      </>
    );
  }
  return <div className="srv-apply">{body}</div>;
}
