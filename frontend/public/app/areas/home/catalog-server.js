// 백엔드 연결(`ready`) 때의 정규·비정규 카탈로그, 수강 과목과 내 신청, 과목 상세, 수강신청 창.
// 목록·상세는 서버 응답만 그리고 샘플 과목을 섞지 않는다. 서버 과목은 `?id=srv-<courseId>`로 연다.
// 신청·취소·청강 명령은 `data-act`(home/actions.js)로 들어와 이 파일의 함수가 처리한다.
// 서버 글자는 텍스트 노드로만 넣는다.
import { el } from "../../dom.js";
import { btn, go, emptyState, loadingState, errorState, pagination, table, field, badge } from "../../ui/components.js";
import { hrefFor, find } from "../../manifest.js";
import { courseIdFor, kjOf, subscribe } from "../../shared/backend.js";
import { courseCover } from "../../shared/course-view.js";
import { coverImage } from "../../ui/assets.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { ddayAt, koDateTime } from "../../shared/home-remote.js";
import {
  catalogFilters, regularPages, offeringPages, regularDetails, offeringDetails, myCourses,
  regularFilter, regularQuery, offeringFilter, offeringQuery, myCoursesQuery, serverIdOf,
  applyOffering, requestAudit, updateAuditReason, cancelEnrollment, isConflict, enrollmentMessage,
  CATALOG_PAGE, AUDIT_REASON_MAX, LECTURE_TYPES, ORG_LABEL, DIVISION_LABEL, AUDIENCE_LABEL, AUDIENCE_ORDER,
  STATE_LABEL, STATUS_LABEL, KIND_LABEL, reasonText, scheduleText, dotDateTime, rangeText,
  instructorsText, seatsText, termName,
  myApplications, applicationsKey, APPLICATION_PAGE, PENDING_STATUSES, offeringFilters,
} from "../../shared/catalog-remote.js";

const PATH = {
  regular: "/ilos/main/rg/regular_list_form.acl",
  special: "/ilos/main/nr/non_regular_list_form.acl",
  mine: "/ilos/main/rg/regular_register_list_form.acl",
  mineSpecial: "/ilos/main/nr/non_regular_register_list_form.acl",
};

/** `node`가 문서에 붙은 뒤 `fn`을 부른다(화면은 render 직후 붙는다). */
function attached(node, fn) {
  queueMicrotask(() => {
    if (node.isConnected) fn();
  });
}

/** 캐시로 바로 그리고, 붙은 뒤 구독·조회한다. */
function bind(slot, resource, scope, key, args, paint) {
  paint(resource.peek(key));
  attached(slot, () => {
    resource.watch(slot, key, paint, () => resource.load(key, ...args));
    resource.visit(scope, key, ...args);
  });
}

function retry(resource, key, args) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    resource.load(key, ...args);
  });
  return button;
}

const waiting = (entry) => !entry || (!entry.data && !entry.error);

// 명령 결과 안내. 같은 history 항목에서만 보이고, 다른 화면으로 가면 사라진다.
const notes = new Map(); // scope -> { entry, text, alert }
subscribe(() => notes.clear());

function setNote(scope, text, alert) {
  notes.set(scope, { entry: typeof history !== "undefined" ? history.state : null, text, alert: Boolean(alert) });
}

function noteNode(scope) {
  const note = notes.get(scope);
  const live = note && note.entry === (typeof history !== "undefined" ? history.state : null);
  return el("p", {
    class: `srv-msg${live && note.alert ? " is-alert" : ""}`,
    role: live && note.alert ? "alert" : "status",
    "aria-live": "polite",
    "data-note": scope,
    text: live ? note.text : "",
  });
}

/** 지금 화면의 안내 줄을 바로 바꾼다(다시 그리기 없이). */
function sayNow(scope, text, alert) {
  setNote(scope, text, alert);
  for (const node of document.querySelectorAll(`[data-note="${CSS.escape(scope)}"]`)) {
    node.textContent = text;
    node.setAttribute("role", alert ? "alert" : "status");
    node.classList.toggle("is-alert", Boolean(alert));
  }
}

export function detailHref(special, courseId) {
  return hrefFor(find(special ? "24_nonregular_view" : "25_regular_view"), { id: `srv-${courseId}` });
}

function classroomHref(special, key) {
  return hrefFor(find(special ? "70_nr_room_submain" : "40_room_submain"), { kj: key });
}

function pageHref(params, page) {
  const next = new URLSearchParams(params);
  next.delete("as");
  if (page > 1) next.set("page", String(page));
  else next.delete("page");
  const q = next.toString();
  return `${location.pathname}${q ? `?${q}` : ""}`;
}

function pager(params, data, size) {
  const pages = Math.max(1, Math.ceil((Number(data.total) || 0) / size));
  return pagination({ page: Number(data.page) || 1, pages, hrefForPage: (n) => pageHref(params, n) });
}

// ---------- 필터 ----------

function control(name, node) {
  node.setAttribute("data-filter", name);
  return node;
}

function selectOf(name, label, options, value) {
  return field(label, control(name, el("select", {},
    ...options.map(([v, text]) => el("option", { value: v, text, selected: v === value ? true : null })),
  )));
}

function check(name, label, value, on) {
  return el("label", { class: "choice srv-check" },
    control(name, el("input", { type: "checkbox", value, checked: on ? true : null })),
    el("span", { text: label }),
  );
}

/** 필터 줄. 조회 단추(`srv-catalog-search`)가 값을 주소로 옮긴다. 검색어에서 Enter도 조회다. */
function filterBar(path, label, controls, q) {
  const submit = btn("조회", { "data-act": "srv-catalog-search", "data-path": path }, "primary");
  const search = control("q", el("input", { type: "search", value: q, maxlength: "100", placeholder: "과목명 또는 학수번호", "aria-label": `${label} 검색어` }));
  search.addEventListener("keydown", (event) => {
    if (event.key !== "Enter" || event.isComposing) return;
    event.preventDefault();
    submit.click();
  });
  return el("div", { class: "srv-filters", role: "search", "aria-label": `${label} 조건` },
    ...controls,
    field("검색어", search),
    el("div", { class: "srv-filters__go" }, submit),
  );
}

/** `srv-catalog-search`: 필터 값을 모아 같은 목록의 1쪽으로 간다. */
export function searchCatalog(node, api) {
  const bar = node.closest(".srv-filters");
  if (!bar) return;
  const query = new URLSearchParams();
  for (const input of bar.querySelectorAll("[data-filter]")) {
    const name = input.getAttribute("data-filter");
    if (input.type === "checkbox") {
      if (input.checked) query.append(name, input.value);
    } else if (String(input.value || "").trim()) query.set(name, String(input.value).trim());
  }
  const q = query.toString();
  api.navigate(`${node.dataset.path}${q ? `?${q}` : ""}`);
}

// ---------- 카드 ----------

// Imported templates use local covers; other server courses retain their courseId-based colour.
function cover(item, ...lines) {
  const appearance = courseCover(item);
  return el("div", { class: `cover ${appearance.gradient}` }, coverImage(appearance), ...lines);
}

function regularCard(item) {
  const href = detailHref(false, item.courseId);
  return el("article", { class: "course-card srv-card" },
    el("a", { class: "course-card-main", href, "data-go": href },
      cover(item,
        el("b", { text: item.name }),
        el("span", { class: "code", text: [item.code, item.section].filter(Boolean).join("-") }),
        el("span", { class: "instructor", text: instructorsText(item.instructors) }),
      ),
      el("div", { class: "card-body" },
        el("div", { class: "facts" },
          el("span", { text: scheduleText(item.schedules) }),
          item.credit != null ? el("span", { text: `${item.credit}학점` }) : null,
        ),
        el("div", { class: "tiny", text: [termName(item.term), item.departmentName, ORG_LABEL[item.orgSect]].filter(Boolean).join(" · ") }),
        item.auditAllowed ? el("span", { class: "act-pill", text: "청강 가능" }) : null,
      ),
    ),
  );
}

function offeringCard(item, serverTime) {
  const href = detailHref(true, item.courseId);
  const open = item.state === "OPEN";
  const dday = open && item.applicationCloseAt ? ddayAt(item.applicationCloseAt, serverTime) : "";
  return el("article", { class: "course-card special-card srv-card" },
    el("a", { class: "course-card-main", href, "data-go": href },
      cover(item,
        el("b", {}, dday ? el("span", { class: "srv-dday", text: dday }) : null, `${item.roundLabel ? `${item.roundLabel} ` : ""}${item.name}`),
        el("span", { class: "code", text: [item.code, item.section].filter(Boolean).join("-") }),
        el("span", { class: "who", text: instructorsText(item.instructors) }),
      ),
      el("div", { class: "card-body" },
        el("div", { class: "facts" },
          el("span", { text: rangeText(item.learningStartDate, item.learningEndDate) }),
          el("span", { class: `status${open ? " open" : ""}`, text: STATE_LABEL[item.state] || "" }),
        ),
        el("div", { class: "tiny", text: `신청 ${seatsText(item)} · ${item.lectureType || "수업형태 미정"}` }),
        el("span", { class: "act-pill room", text: item.approvalMode === "MANUAL" ? "담당자 승인" : "자동 승인" }),
      ),
    ),
  );
}

// ---------- 정규·비정규 전체 ----------

/** 정규과목 전체(20)와 비정규과목 전체(22, 22b). */
export function serverCatalog(screen, ctx) {
  const special = screen.catalog === "special";
  const params = ctx.params || new URLSearchParams();
  const title = special ? "비정규과목 전체" : "정규과목 전체";
  const filters = el("div", { class: "srv-filters-slot" });
  const list = el("div", { class: "srv-list" });
  if (special) {
    const filter = offeringFilter(params);
    let started = false;
    const showList = (cat) => {
      started = true;
      const query = offeringQuery({ ...filter, cat });
      bind(list, offeringPages, "home:catalog:special", query, [query], (entry) => paintList(list, entry, params, true, [offeringPages, query]));
    };
    // 과목분류는 고른 비정규 분류(`division=`)의 `categories[]`에서 고르고, 주소의 `cat`도 그 안에 있을 때만 `category=`로 보낸다.
    const paintFilters = (entry) => {
      const data = entry && entry.data;
      const categories = data && Array.isArray(data.categories) ? data.categories : [];
      const cat = categories.some((item) => item.code === filter.cat) ? filter.cat : "";
      filters.replaceChildren(filterBar(PATH.special, title, [
        el("div", { class: "srv-filters__group" }, check("open", "접수 가능 과목만 보기", "1", filter.open)),
        selectOf("aud", "신청대상", [["", "전체"], ...AUDIENCE_ORDER.map((code) => [code, AUDIENCE_LABEL[code]])], filter.aud),
        selectOf("div", "비정규 분류", [["", "전체"], ...Object.entries(DIVISION_LABEL)], filter.div),
        selectOf("cat", "과목분류", [["", "전체"], ...categories.map((item) => [item.code, item.name])], cat),
        el("fieldset", { class: "srv-filters__group" },
          el("legend", { class: "field-label", text: "수업형태" }),
          ...LECTURE_TYPES.map((type) => check("type", type, type, filter.types.includes(type))),
        ),
      ], filter.q));
      if (entry && entry.error && !data) filters.append(el("p", { class: "tiny", role: "alert", text: `과목분류 목록을 불러오지 못했습니다. ${enrollmentMessage(entry.error)}` }));
      if (!started && (!filter.cat || data || (entry && entry.error))) showList(cat);
    };
    if (filter.cat) list.replaceChildren(loadingState("과목을 불러오는 중입니다"));
    bind(filters, offeringFilters, "home:catalog:categories", filter.div, [filter.div], paintFilters);
  } else {
    const filter = regularFilter(params);
    const termKey = filter.term || "";
    const paintFilters = (entry) => {
      const data = entry && entry.data;
      const terms = data && Array.isArray(data.terms) ? data.terms : [];
      const depts = data && Array.isArray(data.departments) ? data.departments : [];
      filters.replaceChildren(filterBar(PATH.regular, title, [
        selectOf("term", "학기", [["", "현재 학기"], ...terms.map((term) => [String(term.termId), term.name])], filter.term),
        selectOf("org", "대학구분", [["", "전체"], ...Object.entries(ORG_LABEL)], filter.org),
        selectOf("dept", "학과", [["", "전체"], ...depts.map((dept) => [dept.code, dept.name])], filter.dept),
        el("div", { class: "srv-filters__group" }, check("audit", "청강 가능 과목만 보기", "1", filter.audit)),
      ], filter.q));
      if (entry && entry.error && !data) filters.append(el("p", { class: "tiny", role: "alert", text: `필터 목록을 불러오지 못했습니다. ${enrollmentMessage(entry.error)}` }));
    };
    bind(filters, catalogFilters, "home:catalog:filters", termKey, [termKey], paintFilters);
    const query = regularQuery(filter);
    bind(list, regularPages, "home:catalog:regular", query, [query], (entry) => paintList(list, entry, params, false, [regularPages, query]));
  }
  return el("section", { class: "srv-catalog" },
    el("div", { class: "page-head" }, el("h1", { text: title })),
    filters,
    list,
  );
}

function paintList(list, entry, params, special, [resource, key]) {
  if (waiting(entry)) return list.replaceChildren(loadingState("과목을 불러오는 중입니다"));
  if (!entry.data) return list.replaceChildren(errorState("과목을 불러오지 못했습니다", enrollmentMessage(entry.error), retry(resource, key, [key])));
  const data = entry.data;
  const items = Array.isArray(data.items) ? data.items : [];
  list.replaceChildren(
    el("p", { class: "tiny filter-count", role: "status", text: `${Number(data.total) || 0}과목` }),
    items.length
      ? el("div", { class: "cards" }, ...items.map((item) => (special ? offeringCard(item, data.serverTime) : regularCard(item))))
      : emptyState("조건에 맞는 과목이 없습니다", "필터를 바꾸거나 검색어를 지워 보세요."),
    pager(params, data, CATALOG_PAGE),
  );
}

// ---------- 수강 과목 ----------

function previousTerm(terms) {
  const at = terms.findIndex((term) => term.current);
  return at >= 0 ? terms[at + 1] || null : terms[1] || null;
}

/**
 * 정규 수강과목(21, 21b 지난 학기)과 비정규 수강과목(23). 담당하거나 수강이 확정된 과목만 나온다. 처리를 기다리는 신청과
 * 지난 신청은 아래 내 신청(23은 비정규 신청, 21은 청강 신청, `?apage=`)에 있다.
 */
export function serverCourseList(screen, ctx) {
  const params = ctx.params || new URLSearchParams();
  const special = screen.term === "special-enrolled";
  const q = String(params.get("q") || "").trim().slice(0, 100);
  const head = el("div", { class: "srv-filters-slot" });
  const list = el("div", { class: "srv-list" });
  const section = el("section", { class: "srv-catalog" },
    el("div", { class: "page-head" }, el("div", {}, el("h1", { text: "수강과목" }), el("p", { class: "term tight", text: special ? "비정규 · 수강 확정" : "정규과목" }))),
    head,
    noteNode("home:mine"),
    list,
  );
  const showCourses = (termId) => {
    const query = myCoursesQuery({ type: special ? "NONREGULAR" : "REGULAR", termId, q });
    bind(list, myCourses, `home:mine:${screen.id}`, query, [query], (entry) => paintMine(list, entry, special, query));
  };
  if (special) {
    head.append(filterBar(PATH.mineSpecial, "수강과목", [], q));
    showCourses("");
    section.append(applicationsSection("NONREGULAR", params),
      el("p", { class: "term-link" }, go(hrefFor(find("22_nonregular_list")), "비정규과목 전체")));
    return section;
  }
  const asked = /^[1-9]\d{0,15}$/.test(params.get("termId") || "") ? params.get("termId") : "";
  let started = false;
  const paintTerms = (entry) => {
    const data = entry && entry.data;
    const terms = data && Array.isArray(data.terms) ? data.terms : [];
    if (waiting(entry)) return head.replaceChildren(loadingState("학기를 불러오는 중입니다"));
    const chosen = asked || (screen.term === "2026-1" ? previousTerm(terms) : null);
    const termId = typeof chosen === "string" ? chosen : chosen ? String(chosen.termId) : "";
    head.replaceChildren(filterBar(PATH.mine, "수강과목", [
      selectOf("termId", "학기", [["", "전체 학기"], ...terms.map((term) => [String(term.termId), term.current ? `${term.name} (현재)` : term.name])], termId),
    ], q));
    if (!data) head.append(el("p", { class: "tiny", role: "alert", text: `학기 목록을 불러오지 못했습니다. ${enrollmentMessage(entry.error)}` }));
    if (!started) {
      started = true;
      showCourses(termId);
    }
  };
  bind(head, catalogFilters, "home:mine:terms", "", [""], paintTerms);
  if (screen.term !== "2026-1") section.append(applicationsSection("AUDIT", params));
  return section;
}

// ---------- 내 신청 ----------

function appsHref(page) {
  const next = new URLSearchParams(location.search);
  next.delete("as");
  if (page > 1) next.set("apage", String(page));
  else next.delete("apage");
  const q = next.toString();
  return `${location.pathname}${q ? `?${q}` : ""}`;
}

function applicationStatus(item) {
  if (item.kind === "AUDIT" && item.status === "ENROLLED") return "청강 승인";
  return STATUS_LABEL[item.status] || item.status || "";
}

function cancelLabel(item) {
  if (item.status !== "ENROLLED") return "신청 취소";
  return item.kind === "AUDIT" ? "청강 취소" : "수강 취소";
}

function applicationRow(item) {
  const course = item.course || {};
  const special = course.type === "NONREGULAR";
  const offering = course.offering;
  const name = offering && offering.roundLabel ? `${offering.roundLabel} ${course.name || ""}` : course.name || "과목";
  const href = course.courseId ? detailHref(special, course.courseId) : null;
  const dates = [["신청", item.appliedAt], ["처리", item.decidedAt], ["취소", item.cancelledAt]]
    .filter(([, value]) => value)
    .map(([label, value]) => `${label} ${koDateTime(value)}`)
    .join(" · ");
  return el("article", { class: "srv-request srv-app", "data-enrollment": String(item.enrollmentId) },
    el("div", { class: "srv-request__who" },
      href ? go(href, name, { class: "srv-app__name" }) : el("b", { text: name }),
      el("span", { class: "tiny", text: [course.code, course.section].filter(Boolean).join("-") }),
      badge(KIND_LABEL[item.kind] || item.kind),
      badge(applicationStatus(item)),
    ),
    el("p", { class: "tiny", text: [termName(course.term), offering ? `학습기간 ${rangeText(offering.learningStartDate, offering.learningEndDate)}` : ""].filter(Boolean).join(" · ") }),
    el("p", { class: "tiny", text: dates }),
    item.reason ? el("p", { class: "srv-request__reason", text: `청강 사유: ${item.reason}` }) : null,
    item.cancellable
      ? el("div", { class: "btn-row" }, cancelButton(course.courseId, item.enrollmentId, item.status === "ENROLLED", "home:apps", cancelLabel(item)))
      : null,
  );
}

/** 내 신청(`GET /api/me/applications?kind=`): 처리를 기다리는 신청과 지난 신청. 취소는 `cancellable`일 때만 보인다. */
function applicationsSection(kind, params) {
  const raw = Number(params.get("apage"));
  const page = Number.isInteger(raw) && raw >= 1 && raw <= 10000 ? raw : 1;
  const key = applicationsKey(kind, page);
  const title = kind === "AUDIT" ? "내 청강 신청" : "내 비정규 신청";
  const list = el("div", { class: "srv-list" });
  bind(list, myApplications, `home:apps:${kind}`, key, [kind, page], (entry) => {
    if (waiting(entry)) return list.replaceChildren(loadingState("신청을 불러오는 중입니다"));
    if (!entry.data) return list.replaceChildren(errorState("신청을 불러오지 못했습니다", enrollmentMessage(entry.error), retry(myApplications, key, [kind, page])));
    const data = entry.data;
    const items = Array.isArray(data.items) ? data.items : [];
    const pending = items.filter((item) => PENDING_STATUSES.has(item.status));
    const past = items.filter((item) => !PENDING_STATUSES.has(item.status));
    const group = (label, rows) => (rows.length
      ? el("div", { class: "srv-request-list" }, el("h3", { class: "section-label", text: label }), ...rows.map(applicationRow))
      : null);
    list.replaceChildren(
      el("p", { class: "tiny filter-count", role: "status", text: `신청 ${Number(data.total) || 0}건` }),
      items.length
        ? el("div", { class: "stack" }, group("처리를 기다리는 신청", pending), group("지난 신청", past))
        : emptyState("신청한 내역이 없습니다", kind === "AUDIT" ? "정규과목 상세에서 청강을 신청할 수 있습니다." : "비정규과목 전체에서 과정을 신청할 수 있습니다."),
      pagination({ page: Number(data.page) || page, pages: Math.max(1, Math.ceil((Number(data.total) || 0) / APPLICATION_PAGE)), hrefForPage: appsHref }),
    );
    return undefined;
  });
  return el("section", { class: "srv-apps", "aria-label": title },
    el("h2", { class: "section-label", text: title }),
    el("p", { class: "tiny srv-hint", text: "승인을 기다리는 신청은 수강과목에 나오지 않고 여기에만 있습니다. 취소할 수 있는 신청에만 취소 단추가 있습니다." }),
    noteNode("home:apps"),
    list,
  );
}

function paintMine(list, entry, special, key) {
  if (waiting(entry)) return list.replaceChildren(loadingState("수강 과목을 불러오는 중입니다"));
  if (!entry.data) return list.replaceChildren(errorState("수강 과목을 불러오지 못했습니다", enrollmentMessage(entry.error), retry(myCourses, key, [key])));
  const items = Array.isArray(entry.data) ? entry.data : [];
  list.replaceChildren(items.length
    ? el("div", { class: "cards" }, ...items.map((item) => mineCard(item, special)))
    : emptyState("수강 중인 과목이 없습니다", special ? "비정규과목 전체에서 과정을 신청할 수 있습니다." : "다른 학기를 고르거나 검색어를 지워 보세요."));
}

function mineCard(item, special) {
  // 내 과목은 강의실로 연다(`kj` = 샘플 ID 키 또는 `c<courseId>`).
  const href = classroomHref(special, kjOf(item));
  const mine = item.enrollment;
  const offering = item.offering;
  return el("article", { class: "course-card srv-card" },
    el("a", { class: "course-card-main", href, "data-go": href },
      cover(item,
        el("b", { text: offering && offering.roundLabel ? `${offering.roundLabel} ${item.name}` : item.name }),
        el("span", { class: "code", text: [item.code, item.section].filter(Boolean).join("-") }),
        el("span", { class: "instructor", text: instructorsText(item.instructors) }),
      ),
      el("div", { class: "card-body" },
        el("div", { class: "facts" },
          el("span", { text: offering ? rangeText(offering.learningStartDate, offering.learningEndDate) : item.learningPeriod || scheduleText(item.schedules) }),
          item.lectureType ? el("span", { text: item.lectureType }) : null,
          item.credit != null && !special ? el("span", { text: `${item.credit}학점` }) : null,
        ),
        el("div", { class: "tiny", text: [termName(item.term), item.role === "INSTRUCTOR" ? "담당" : mine ? KIND_LABEL[mine.kind] : ""].filter(Boolean).join(" · ") }),
        el("span", { class: "act-pill room", text: "강의실" }),
      ),
    ),
    go(hrefFor(find("study_home"), { kj: kjOf(item) }), "학습실 입장", {
      class: "btn btn-primary course-study-entry",
      "aria-label": `${item.name} 학습실 입장`,
    }),
    mine && mine.cancellable
      ? cancelButton(item.courseId, mine.enrollmentId, mine.status === "ENROLLED", "home:mine", mine.kind === "AUDIT" ? "청강 취소" : "수강 취소")
      : null,
  );
}

function cancelButton(courseId, enrollmentId, wasEnrolled, scope, label = "신청 취소") {
  return btn(label, {
    "data-act": "srv-enroll-cancel",
    "data-course": String(courseId),
    "data-enrollment": String(enrollmentId),
    "data-was": wasEnrolled ? "1" : "0",
    "data-scope": scope,
    "data-label": label,
  }, "secondary");
}

// ---------- 상세 ----------

/** 상세 화면(24, 25)의 서버 과목 ID: `?id=srv-<id>`, 아니면 샘플 과목과 짝지은 서버 과목. */
function detailId(screen, ctx) {
  const raw = (ctx.params && ctx.params.get("id")) || "";
  return serverIdOf(raw) || (courseIdFor(raw || screen.course) != null ? String(courseIdFor(raw || screen.course)) : null);
}

export function serverCatalogDetail(screen, ctx) {
  const special = screen.path.includes("/nr/");
  const id = detailId(screen, ctx);
  const listHref = hrefFor(find(special ? "22_nonregular_list" : "20_regular_list"));
  if (!id) {
    return el("section", { class: "detail" },
      emptyState("ASTRA 서버에서 찾을 수 없는 과목입니다", "목록에서 서버에 등록된 과목을 골라 주세요.", go(listHref, "목록", { class: "btn btn-secondary" })));
  }
  const slot = el("section", { class: "detail srv-detail" });
  const resource = special ? offeringDetails : regularDetails;
  bind(slot, resource, `home:detail:${special ? "nr" : "rg"}`, id, [id], (entry) => {
    if (waiting(entry)) return slot.replaceChildren(loadingState("과목 정보를 불러오는 중입니다"));
    if (!entry.data) {
      return slot.replaceChildren(errorState("과목 정보를 불러오지 못했습니다", enrollmentMessage(entry.error),
        retry(resource, id, [id]), go(listHref, "목록", { class: "btn btn-secondary" })));
    }
    slot.replaceChildren(...(special ? offeringView(entry.data, listHref) : regularView(entry.data, listHref)));
  });
  return slot;
}

function hero(item, kicker, title, pills) {
  return el("div", { class: `detail-hero ${courseGradient(item.courseId)}` },
    el("p", { class: "k", text: kicker }),
    el("h1", { text: title }),
    el("p", { text: `${[item.code, item.section].filter(Boolean).join("-")} · ${instructorsText(item.instructors)}` }),
    el("div", { class: "hero-pills" }, ...pills.filter(Boolean).map((text) => el("span", { text }))),
  );
}

function kv(rows) {
  return el("dl", { class: "kv" }, ...rows.map(([k, v]) => el("div", {}, el("dt", { text: k }), el("dd", { text: v || "—" }))));
}

function regularView(data, listHref) {
  const item = data.course || {};
  const viewer = data.viewer || {};
  const role = viewer.role;
  const audit = viewer.audit;
  const scope = `home:detail:rg:${item.courseId}`;
  const side = el("div", { class: "sheet side" },
    el("h2", { text: item.name }),
    el("p", { class: "tiny", text: termName(item.term) }),
    kv([
      ["내 역할", role === "INSTRUCTOR" ? "담당" : role === "STUDENT" ? (audit && audit.status === "ENROLLED" ? "청강" : "수강") : "구성원 아님"],
      ["청강", item.auditAllowed ? "허용" : "받지 않음"],
      ["청강 신청", audit ? STATUS_LABEL[audit.status] || audit.status : "없음"],
    ]),
  );
  const actions = el("div", { class: "btn-row" });
  if (role) actions.append(go(classroomHref(false, kjOf(item)), "강의실 입장", { class: "btn btn-primary" }));
  actions.append(go(listHref, "목록", { class: "btn btn-secondary" }));
  side.append(actions, auditSection(item, viewer, scope), noteNode(scope));
  return [
    hero(item, termName(item.term) || "정규과목", item.name, ["정규", item.auditAllowed ? "청강 가능" : null, role ? "구성원" : null]),
    el("div", { class: "detail-grid" },
      el("div", { class: "sheet" },
        el("h2", { text: "이수 정보" }),
        table(["항목", "내용"], [
          ["담당", instructorsText(item.instructors)],
          ["학기", termName(item.term) || "—"],
          ["대학구분", ORG_LABEL[item.orgSect] || "—"],
          ["학과", item.departmentName || "—"],
          ["학점", item.credit != null ? `${item.credit}학점` : "—"],
          ["이수구분", item.completionType || "—"],
          ["학년", item.gradeYear ? `${item.gradeYear}학년` : "—"],
          ["수업형태", item.lectureType || "—"],
          ["수업 시간", scheduleText(item.schedules)],
          ["합반", item.merged ? "합반 과목" : "아님"],
        ]),
        el("p", { class: "tiny", text: "강의계획서 본문과 수강생 명단은 카탈로그에 공개하지 않습니다." }),
      ),
      side,
    ),
  ];
}

// 입력 중인 청강 사유. 다시 그리거나 409로 다시 받아도 남고, 세션이 바뀌면 버린다.
const reasonDrafts = new Map(); // courseId -> text
subscribe(() => reasonDrafts.clear());

function reasonBox(courseId, initial) {
  const key = String(courseId);
  const area = el("textarea", { "data-reason": key, maxlength: String(AUDIT_REASON_MAX), rows: "4", placeholder: "청강하려는 이유를 적어 주세요." });
  area.value = reasonDrafts.has(key) ? reasonDrafts.get(key) : initial || "";
  area.addEventListener("input", () => reasonDrafts.set(key, area.value));
  return field(`청강 사유 (${AUDIT_REASON_MAX}자 이내)`, area);
}

function auditSection(item, viewer, scope) {
  const audit = viewer.audit;
  const box = el("div", { class: "srv-audit" }, el("h3", { text: "청강" }));
  if (audit && audit.status === "REQUESTED") {
    box.append(
      el("p", { class: "tiny", text: "청강 신청이 담당 교수의 승인을 기다리고 있습니다. 승인 전까지 사유를 고치거나 취소할 수 있습니다." }),
      reasonBox(item.courseId, audit.reason),
      el("div", { class: "btn-row" },
        btn("사유 저장", { "data-act": "srv-audit-reason", "data-course": String(item.courseId), "data-enrollment": String(audit.enrollmentId), "data-version": String(audit.version) }, "primary"),
        audit.cancellable ? cancelButton(item.courseId, audit.enrollmentId, false, scope, "청강 신청 취소") : null,
      ),
    );
    return box;
  }
  if (audit && audit.status === "ENROLLED") {
    box.append(el("p", { class: "tiny", text: "청강이 승인되었습니다. 강의실에서 학습할 수 있습니다." }));
    if (audit.cancellable) box.append(el("div", { class: "btn-row" }, cancelButton(item.courseId, audit.enrollmentId, true, scope, "청강 취소")));
    return box;
  }
  if (audit) box.append(el("p", { class: "tiny", text: `지난 청강 신청: ${STATUS_LABEL[audit.status] || audit.status}` }));
  if (viewer.canRequestAudit) {
    box.append(
      reasonBox(item.courseId, ""),
      el("div", { class: "btn-row" }, btn("청강 신청", { "data-act": "srv-audit-request", "data-course": String(item.courseId) }, "primary")),
    );
  } else {
    const reasons = Array.isArray(viewer.reasons) ? viewer.reasons : [];
    box.append(el("ul", { class: "srv-reasons" }, ...reasons.map((code) => el("li", { text: reasonText(code) }))));
  }
  return box;
}

function offeringView(data, listHref) {
  const item = data.offering || {};
  const viewer = data.viewer || {};
  const mine = viewer.application;
  const scope = `home:detail:nr:${item.courseId}`;
  const open = item.state === "OPEN";
  const dday = open && item.applicationCloseAt ? ddayAt(item.applicationCloseAt, data.serverTime) : "";
  const actions = el("div", { class: "btn-row" });
  if (mine && mine.status === "ENROLLED") actions.append(go(classroomHref(true, kjOf(item)), "강의실 입장", { class: "btn btn-primary" }));
  if (viewer.eligible) actions.append(go(hrefFor(find("24c_nonregular_apply_pop"), { id: `srv-${item.courseId}` }), "수강 신청", { class: "btn btn-primary" }));
  if (mine && mine.cancellable) actions.append(cancelButton(item.courseId, mine.enrollmentId, mine.status === "ENROLLED", scope, mine.status === "ENROLLED" ? "수강 취소" : "신청 취소"));
  actions.append(go(listHref, "목록", { class: "btn btn-secondary" }));
  const reasons = Array.isArray(viewer.reasons) ? viewer.reasons : [];
  return [
    hero(item, DIVISION_LABEL[item.division] || "비정규과목", `${item.roundLabel ? `${item.roundLabel} ` : ""}${item.name}`, [
      "비정규", STATE_LABEL[item.state], dday || null, mine ? STATUS_LABEL[mine.status] : null,
    ]),
    el("div", { class: "detail-grid" },
      el("div", { class: "sheet" },
        el("h2", { text: "과정 정보" }),
        table(["항목", "내용"], [
          ["담당", instructorsText(item.instructors)],
          ["분류", DIVISION_LABEL[item.division] || "—"],
          ["수업형태", item.lectureType || "—"],
          ["학습 기간", rangeText(item.learningStartDate, item.learningEndDate)],
          ["접수 기간", rangeText(item.applicationOpenAt, item.applicationCloseAt, dotDateTime)],
          ["신청대상", (Array.isArray(item.audiences) ? item.audiences : []).map((code) => AUDIENCE_LABEL[code] || code).join(", ") || "없음"],
          ["승인", item.approvalMode === "MANUAL" ? "담당자가 승인한 뒤 확정" : "신청하면 바로 확정"],
          ["정원", seatsText(item)],
          ["수강 취소", item.cancelAllowed ? `가능${item.cancelCloseAt ? ` (${dotDateTime(item.cancelCloseAt)}까지)` : ""}` : "확정 뒤에는 취소할 수 없음"],
        ]),
        el("p", { class: "tiny", text: `접수 상태는 서버 시각 ${dotDateTime(data.serverTime)} 기준입니다.` }),
      ),
      el("div", { class: "sheet side" },
        el("h2", { text: item.name }),
        el("p", { class: "tiny", text: [item.code, item.section].filter(Boolean).join("-") }),
        kv([
          ["접수", STATE_LABEL[item.state] || "—"],
          ["내 신청", mine ? STATUS_LABEL[mine.status] || mine.status : "없음"],
          ["정원", seatsText(item)],
        ]),
        actions,
        !viewer.eligible && reasons.length ? el("ul", { class: "srv-reasons" }, ...reasons.map((code) => el("li", { text: reasonText(code) }))) : null,
        noteNode(scope),
      ),
    ),
  ];
}

// ---------- 신청 창 ----------

/** `?pop=apply&id=srv-<courseId>`의 본문(서버 미리보기와 신청 단추). */
export function serverApplyBody(ctx) {
  const id = serverIdOf(ctx.params && ctx.params.get("id"));
  if (!id) return emptyState("ASTRA 서버의 과정을 골라 주세요", "비정규과목 전체에서 과정을 연 뒤 신청할 수 있습니다.");
  const slot = el("div", { class: "srv-apply" });
  bind(slot, offeringDetails, "home:apply", id, [id], (entry) => {
    if (waiting(entry)) return slot.replaceChildren(loadingState("과정 정보를 불러오는 중입니다"));
    if (!entry.data) return slot.replaceChildren(errorState("과정 정보를 불러오지 못했습니다", enrollmentMessage(entry.error), retry(offeringDetails, id, [id])));
    const item = entry.data.offering || {};
    const viewer = entry.data.viewer || {};
    const reasons = Array.isArray(viewer.reasons) ? viewer.reasons : [];
    slot.replaceChildren(
      el("p", {}, el("b", { text: `${item.roundLabel ? `${item.roundLabel} ` : ""}${item.name}` }), ` (${[item.code, item.section].filter(Boolean).join("-")})`),
      el("p", { class: "tiny", text: `접수 ${rangeText(item.applicationOpenAt, item.applicationCloseAt, dotDateTime)} · 정원 ${seatsText(item)}` }),
      el("p", { text: item.approvalMode === "MANUAL"
        ? "신청하면 담당자의 승인을 기다립니다. 승인되면 수강과목에 나옵니다."
        : "신청하면 바로 수강이 확정되고 수강과목에 나옵니다." }),
      viewer.eligible
        ? el("div", { class: "btn-row" },
          btn("취소", { "data-act": "close" }, "ghost"),
          btn("신청", { "data-act": "srv-enroll-apply", "data-course": String(item.courseId) }, "primary"))
        : el("ul", { class: "srv-reasons" }, ...reasons.map((code) => el("li", { text: reasonText(code) }))),
      noteNode("home:apply"),
    );
  });
  return slot;
}

// ---------- 명령 ----------

const running = new Set(); // 진행 중인 명령: 두 번 눌러도 한 번만 보낸다

function busy(node, on, label) {
  node.disabled = on;
  if (on) node.setAttribute("aria-busy", "true");
  else node.removeAttribute("aria-busy");
  if (label) node.textContent = label;
}

async function once(id, node, label, work) {
  if (running.has(id) || node.disabled) return;
  running.add(id);
  const before = node.textContent;
  busy(node, true, label);
  try {
    await work();
  } finally {
    running.delete(id);
    if (node.isConnected) busy(node, false, before);
  }
}

/** `srv-enroll-apply`: 신청 창의 신청. */
export function applyFromDialog(node, api) {
  const courseId = node.dataset.course;
  return once(`apply:${courseId}`, node, "신청하는 중…", async () => {
    try {
      const receipt = await applyOffering(courseId);
      api.getState().toast = receipt.status === "ENROLLED"
        ? "수강 신청이 완료되었습니다. 수강과목에 추가했습니다"
        : "신청했습니다. 담당자의 승인을 기다립니다";
      api.navigate(detailHref(true, courseId));
    } catch (error) {
      if (isConflict(error) && error.code !== "idempotency_in_progress") offeringDetails.load(courseId, courseId);
      const lost = error && [0, 502, 503, 504].includes(error.status);
      sayNow("home:apply", lost
        ? `${enrollmentMessage(error)} 신청이 접수됐는지 확인하려면 신청을 다시 누르세요. 같은 신청으로 확인합니다.`
        : enrollmentMessage(error), true);
    }
  });
}

/** `srv-enroll-cancel`: 첫 번째 누름은 확인, 두 번째에 취소를 보낸다. */
export function cancelFromButton(node, api) {
  if (node.dataset.armed !== "1") {
    node.dataset.armed = "1";
    node.textContent = "한 번 더 누르면 취소";
    return undefined;
  }
  const { course, enrollment, scope } = node.dataset;
  return once(`cancel:${enrollment}`, node, "취소하는 중…", async () => {
    try {
      await cancelEnrollment(course, enrollment, { wasEnrolled: node.dataset.was === "1" });
      api.getState().toast = "신청을 취소했습니다";
      api.render();
    } catch (error) {
      setNote(scope, enrollmentMessage(error), true);
      // 상태가 이미 바뀐 신청(취소 마감, 처리됨)이면 상세와 목록을 서버에서 다시 받아 그린다.
      if (isConflict(error) && error.code !== "idempotency_in_progress") {
        setNote(scope, `${enrollmentMessage(error)} 최신 상태를 불러왔습니다.`, true);
        offeringDetails.drop((key) => key === String(course));
        regularDetails.drop((key) => key === String(course));
        myCourses.drop(() => true);
        myApplications.drop(() => true);
        api.render();
      } else sayNow(scope, enrollmentMessage(error), true);
    }
  }).then(() => {
    node.dataset.armed = "";
    if (node.isConnected) node.textContent = node.dataset.label || "신청 취소";
  });
}

function typedReason(courseId) {
  const area = document.querySelector(`textarea[data-reason="${CSS.escape(String(courseId))}"]`);
  return area ? area.value.trim() : "";
}

/** `srv-audit-request`: 정규과목 청강 신청. */
export function requestAuditFromDetail(node, api) {
  const courseId = node.dataset.course;
  const scope = `home:detail:rg:${courseId}`;
  const reason = typedReason(courseId);
  if (!reason) return sayNow(scope, "청강 사유를 입력하세요.", true);
  if (reason.length > AUDIT_REASON_MAX) return sayNow(scope, `청강 사유는 ${AUDIT_REASON_MAX}자 이내로 적어 주세요.`, true);
  return once(`audit:${courseId}`, node, "신청하는 중…", async () => {
    try {
      await requestAudit(courseId, reason);
      reasonDrafts.delete(String(courseId));
      api.getState().toast = "청강을 신청했습니다. 담당 교수의 승인을 기다립니다";
      api.render();
    } catch (error) {
      if (isConflict(error) && error.code !== "idempotency_in_progress") regularDetails.load(courseId, courseId);
      sayNow(scope, enrollmentMessage(error), true);
    }
  });
}

/** `srv-audit-reason`: REQUESTED인 청강 사유 수정(읽은 `version`). 409면 다시 받고 입력은 남긴다. */
export function saveAuditReason(node, api) {
  const { course: courseId, enrollment, version } = node.dataset;
  const scope = `home:detail:rg:${courseId}`;
  const reason = typedReason(courseId);
  if (!reason) return sayNow(scope, "청강 사유를 입력하세요.", true);
  if (reason.length > AUDIT_REASON_MAX) return sayNow(scope, `청강 사유는 ${AUDIT_REASON_MAX}자 이내로 적어 주세요.`, true);
  return once(`reason:${enrollment}`, node, "저장하는 중…", async () => {
    try {
      await updateAuditReason(courseId, enrollment, reason, Number(version));
      reasonDrafts.delete(String(courseId));
      api.getState().toast = "청강 사유를 저장했습니다";
      api.render();
    } catch (error) {
      if (isConflict(error)) {
        setNote(scope, enrollmentMessage(error), true);
        regularDetails.load(courseId, courseId);
        return;
      }
      sayNow(scope, enrollmentMessage(error), true);
    }
  });
}

/** 백엔드 `anon`일 때 카탈로그 화면 위에 두는 로그인 줄. */
export function catalogLoginPanel() {
  return backendPanel({ text: "ASTRA 서버에 로그인하면 서버의 과목 목록을 보고 수강 신청과 청강 신청을 할 수 있습니다." });
}
