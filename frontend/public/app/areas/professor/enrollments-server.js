// 백엔드 연결 때의 교수 강의과목(`GET /api/me/courses`에서 담당 과목)과 수강생 화면(pf_monitor)의 수강생 명단
// (`GET /api/courses/{courseId}/students?q&sort&order&page&size`, 행마다 최근 영상 학습 `lastAccessAt`과 진도율 `progressPercent`)과
// 신청 처리 (`GET /api/courses/{courseId}/enrollment-requests`, `POST …/enrollments/{enrollmentId}/decision`).
// 승인·반려는 읽은 `version`을 보내고, 409(`not_pending`, `version_conflict`)면 목록을 다시 받는다.
// `seat_full`은 신청을 대기로 둔다. 처리 버튼은 `pf-enroll-decide`, 명단 조회는 `pf-roster-search`(professor/actions.js)로 들어온다.
import { el } from "../../dom.js";
import { btn, field, emptyState, loadingState, errorState, pagination, badge, table } from "../../ui/components.js";
import { courses } from "../../data.js";
import { backendStatus, courseIdFor, roleIn, subscribe } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { sessionDrafts } from "../../shared/session-drafts.js";
import {
  myCourses, enrollmentRequests, requestKey, decideEnrollment, isConflict, enrollmentMessage,
  REQUEST_PAGE, DECISION_REASON_MAX, STATUS_LABEL, KIND_LABEL, scheduleText, dotDateTime, termName,
  courseRosters, rosterKey, rosterFilter, rosterQuery, ROSTER_PAGE, ROSTER_SORT_LABEL, ROSTER_ROLE_LABEL,
} from "../../shared/catalog-remote.js";
import { koDateTime } from "../../shared/home-remote.js";

function attached(node, fn) {
  queueMicrotask(() => {
    if (node.isConnected) fn();
  });
}

function retry(reload) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    reload();
  });
  return button;
}

const waiting = (entry) => !entry || (!entry.data && !entry.error);

/** 강의과목(pf_courses): 담당 과목. 샘플 화면이 있는 과목은 그 카드(`card(local, 0)`)로, 없는 과목은 정보만 보인다. */
export function serverTaughtCourses(card) {
  const slot = el("div", { class: "srv-list" });
  const key = "";
  const paint = (entry) => {
    if (waiting(entry)) return slot.replaceChildren(loadingState("담당 과목을 불러오는 중입니다"));
    if (!entry.data) return slot.replaceChildren(errorState("담당 과목을 불러오지 못했습니다", enrollmentMessage(entry.error), retry(() => myCourses.load(key, key))));
    const taught = (Array.isArray(entry.data) ? entry.data : []).filter((course) => course.role === "INSTRUCTOR");
    if (!taught.length) return slot.replaceChildren(emptyState("담당 과목이 없습니다", "ASTRA 서버에 이 계정이 담당하는 과목이 없습니다."));
    const cards = taught.map((remote) => {
      const local = courses.find((course) => course.id === remote.key);
      if (local) return card(local, 0);
      return el("article", { class: "course-card srv-card" },
        el("div", { class: "course-card-main" },
          el("div", { class: `cover g${(Number(remote.courseId) % 8) + 1}` }, el("b", { text: remote.name }), el("span", { text: [remote.code, remote.section].filter(Boolean).join("-") })),
          el("div", { class: "meta" },
            el("div", { text: [termName(remote.term), remote.type === "NONREGULAR" ? "비정규" : "정규"].filter(Boolean).join(" · ") }),
            el("div", { text: scheduleText(remote.schedules) }),
            el("div", { class: "tiny", text: "이 샘플에 강의실 화면이 없는 과목입니다." }),
          ),
        ),
      );
    });
    slot.replaceChildren(el("p", { class: "pf-sub", role: "status", text: `담당 과목 ${taught.length}개` }), el("div", { class: "cards" }, ...cards));
  };
  paint(myCourses.peek(key));
  attached(slot, () => {
    myCourses.watch(slot, key, paint, () => myCourses.load(key, key));
    myCourses.visit("pf:courses", key, key);
  });
  return slot;
}

// 처리 결과 안내(같은 history 항목에서만).
const notes = new Map(); // courseId -> { entry, text, alert }
subscribe(() => notes.clear());
// 입력 중인 처리 사유. 처리·409 뒤 목록을 다시 그려도 남고, 그 신청을 처리했거나 없어지면(404·`not_pending`) 지운다. 세션이 바뀌면 버린다.
const reasons = sessionDrafts(); // `${courseId}:${enrollmentId}` -> text
const reasonKey = (courseId, enrollmentId) => `${courseId}:${enrollmentId}`;

function note(courseId) {
  const item = notes.get(String(courseId));
  const live = item && item.entry === history.state;
  return el("p", {
    class: `srv-msg${live && item.alert ? " is-alert" : ""}`,
    role: live && item.alert ? "alert" : "status",
    "aria-live": "polite",
    "data-note": `pf-enroll:${courseId}`,
    text: live ? item.text : "",
  });
}

function say(courseId, text, alert) {
  notes.set(String(courseId), { entry: history.state, text, alert: Boolean(alert) });
  for (const node of document.querySelectorAll(`[data-note="pf-enroll:${CSS.escape(String(courseId))}"]`)) {
    node.textContent = text;
    node.setAttribute("role", alert ? "alert" : "status");
    node.classList.toggle("is-alert", Boolean(alert));
  }
}

function pageHref(page) {
  const params = new URLSearchParams(location.search);
  if (page > 1) params.set("page", String(page));
  else params.delete("page");
  return `${location.pathname}?${params}`;
}

/** 수강생 화면(pf_monitor)의 서버 부분: 처리를 기다리는 수강·청강 신청. */
export function serverEnrollmentRequests(ctx, course) {
  if (backendStatus() === "anon") return backendPanel({ text: "ASTRA 서버에 로그인하면 이 과목의 수강 신청과 청강 신청을 처리할 수 있습니다." });
  const courseId = courseIdFor(course.id);
  if (!courseId) return emptyState("이 과목은 ASTRA 서버에 없습니다", "서버에 등록된 과목만 신청을 처리할 수 있습니다.");
  if (roleIn(course.id) !== "INSTRUCTOR") return emptyState("담당 과목이 아닙니다", "이 과목을 담당하는 교수 계정으로 로그인해야 신청을 처리할 수 있습니다.");
  const raw = Number((ctx.params && ctx.params.get("page")) || 1);
  const page = Number.isInteger(raw) && raw >= 1 && raw <= 10000 ? raw : 1;
  const key = requestKey(courseId, page);
  const slot = el("div", { class: "srv-requests" });
  const paint = (entry) => {
    if (waiting(entry)) return slot.replaceChildren(loadingState("신청을 불러오는 중입니다"), note(courseId));
    if (!entry.data) {
      const denied = entry.error && entry.error.status === 404;
      return slot.replaceChildren(denied
        ? emptyState("신청을 처리할 수 없습니다", "이 과목의 교수와 비정규 담당자만 신청을 처리합니다. 조교 계정은 처리할 수 없습니다.")
        : errorState("신청을 불러오지 못했습니다", enrollmentMessage(entry.error), retry(() => enrollmentRequests.load(key, courseId, page))));
    }
    const data = entry.data;
    const items = Array.isArray(data.items) ? data.items : [];
    slot.replaceChildren(
      el("h2", { class: "section-label", text: `처리를 기다리는 신청 ${Number(data.total) || 0}건` }),
      note(courseId),
      items.length
        ? el("div", { class: "srv-request-list" }, ...items.map((item) => requestRow(courseId, item)))
        : emptyState("처리를 기다리는 신청이 없습니다", "학생이 승인 과정에 신청하거나 청강을 신청하면 여기에 모입니다."),
      pagination({ page, pages: Math.max(1, Math.ceil((Number(data.total) || 0) / REQUEST_PAGE)), hrefForPage: pageHref }),
    );
  };
  paint(enrollmentRequests.peek(key));
  attached(slot, () => {
    enrollmentRequests.watch(slot, key, paint, () => enrollmentRequests.load(key, courseId, page));
    enrollmentRequests.visit("pf:requests", key, courseId, page);
  });
  return slot;
}

function requestRow(courseId, item) {
  const key = reasonKey(courseId, item.enrollmentId);
  const reasonInput = el("input", { type: "text", maxlength: String(DECISION_REASON_MAX), "data-decision-reason": String(item.enrollmentId), placeholder: "학생에게 남길 처리 사유(선택)", value: reasons.get(key) || "" });
  reasonInput.addEventListener("input", () => {
    if (reasonInput.value) reasons.set(key, reasonInput.value);
    else reasons.delete(key);
  });
  const decide = (decision, label, kind) => btn(label, {
    "data-act": "pf-enroll-decide",
    "data-course": String(courseId),
    "data-enrollment": String(item.enrollmentId),
    "data-version": String(item.version),
    "data-decision": decision,
    "aria-label": `${item.studentName || "신청자"} ${label}`,
  }, kind);
  return el("article", { class: "srv-request" },
    el("div", { class: "srv-request__who" },
      el("b", { text: item.studentName || "이름 없음" }),
      el("span", { class: "tiny", text: item.studentNumber || "" }),
      badge(KIND_LABEL[item.kind] || item.kind),
      badge(STATUS_LABEL[item.status] || item.status),
    ),
    el("p", { class: "tiny", text: `신청 ${dotDateTime(item.appliedAt)}` }),
    item.reason ? el("p", { class: "srv-request__reason", text: item.reason }) : null,
    field("처리 사유", reasonInput),
    el("div", { class: "btn-row" }, decide("APPROVE", "승인", "primary"), decide("REJECT", "반려", "secondary")),
  );
}

const running = new Set();

/** `pf-enroll-decide`: 승인 또는 반려. 두 번 눌러도 한 번만 보낸다. */
export async function decideFromButton(node, api) {
  const { course, enrollment, version, decision } = node.dataset;
  if (running.has(enrollment) || node.disabled) return;
  const input = document.querySelector(`[data-decision-reason="${CSS.escape(enrollment)}"]`);
  const reason = input ? input.value.trim() : "";
  if (reason.length > DECISION_REASON_MAX) return say(course, `처리 사유는 ${DECISION_REASON_MAX}자 이내로 적어 주세요.`, true);
  running.add(enrollment);
  const row = node.closest(".srv-request");
  const buttons = row ? [...row.querySelectorAll("button")] : [node];
  for (const button of buttons) button.disabled = true;
  node.setAttribute("aria-busy", "true");
  const label = node.textContent;
  node.textContent = decision === "APPROVE" ? "승인하는 중…" : "반려하는 중…";
  try {
    const receipt = await decideEnrollment(course, enrollment, decision, Number(version), reason);
    reasons.delete(reasonKey(course, enrollment));
    api.getState().toast = receipt.status === "ENROLLED" ? "승인했습니다. 학생의 수강이 확정되었습니다" : "반려했습니다";
    api.render();
  } catch (error) {
    // 처리할 수 없게 된 신청(이미 처리·취소, 없음)의 사유는 버린다. 버전 충돌은 사유를 두고 새 version의 행으로 다시 그린다.
    if (isConflict(error, "not_pending") || (error && error.status === 404)) reasons.delete(reasonKey(course, enrollment));
    if (isConflict(error) && ["not_pending", "version_conflict"].includes(error.code)) {
      // 다른 곳에서 먼저 처리했거나 학생이 취소했다: 목록을 서버에서 다시 받는다.
      notes.set(String(course), { entry: history.state, text: enrollmentMessage(error), alert: true });
      enrollmentRequests.drop((key) => key.startsWith(`${course}|`));
      api.render();
      return;
    }
    say(course, isConflict(error, "seat_full") ? "정원이 찼습니다. 이 신청은 승인 대기로 남았습니다. 수강이 확정된 학생이 취소하면 다시 승인할 수 있습니다." : enrollmentMessage(error), true);
  } finally {
    running.delete(enrollment);
    if (node.isConnected) {
      for (const button of buttons) button.disabled = false;
      node.removeAttribute("aria-busy");
      node.textContent = label;
    }
  }
}

// ---------- 수강생 명단 ----------

function rosterHref(page) {
  const params = new URLSearchParams(location.search);
  if (page > 1) params.set("spage", String(page));
  else params.delete("spage");
  const q = params.toString();
  return `${location.pathname}${q ? `?${q}` : ""}`;
}

/** 검색어·정렬·순서 줄. 조회(`pf-roster-search`)가 값을 주소로 옮긴다. 검색어에서 Enter도 조회다. */
function rosterBar(filter) {
  const submit = btn("조회", { "data-act": "pf-roster-search" }, "primary");
  const q = el("input", { type: "search", "data-roster": "q", value: filter.q, maxlength: "50", placeholder: "이름 또는 학번" });
  q.addEventListener("keydown", (event) => {
    if (event.key !== "Enter" || event.isComposing) return;
    event.preventDefault();
    submit.click();
  });
  const sort = filter.sort || "name";
  const order = filter.order || "asc";
  return el("div", { class: "srv-filters", role: "search", "aria-label": "수강생 검색" },
    field("검색어", q),
    field("정렬", el("select", { "data-roster": "sort" },
      ...Object.entries(ROSTER_SORT_LABEL).map(([value, text]) => el("option", { value, text, selected: value === sort ? true : null })))),
    field("순서", el("select", { "data-roster": "order" },
      el("option", { value: "asc", text: "오름차순", selected: order === "asc" ? true : null }),
      el("option", { value: "desc", text: "내림차순", selected: order === "desc" ? true : null }))),
    el("div", { class: "srv-filters__go" }, submit),
  );
}

/** `pf-roster-search`: 검색어·정렬·순서를 주소에 넣고 명단 1쪽으로 간다. 다른 주소 값(신청 쪽 등)은 그대로 둔다. */
export function searchRoster(node, api) {
  const bar = node.closest(".srv-filters");
  if (!bar) return;
  const params = new URLSearchParams(location.search);
  for (const input of bar.querySelectorAll("[data-roster]")) {
    const name = input.getAttribute("data-roster");
    const value = String(input.value || "").trim();
    if (value) params.set(name, value);
    else params.delete(name);
  }
  params.delete("spage");
  api.navigate(`${location.pathname}?${params}`);
}

/** 진도율(0~100). 학생에게 열린 영상 콘텐츠가 없으면 서버가 null을 보내고 "—"로 둔다. */
function progressCell(value) {
  if (!Number.isInteger(value)) return "—";
  const n = Math.max(0, Math.min(100, value));
  return el("div", { class: "pf-meter" },
    el("div", { class: "bar", "aria-hidden": "true" }, el("i", { style: `width:${n}%` })),
    el("span", { text: `${n}%` }),
  );
}

function rosterCell(item) {
  const auditor = item.role === "AUDITOR";
  return el("span", { class: "srv-roster__kind" },
    badge(ROSTER_ROLE_LABEL[item.role] || item.role || "수강생", auditor ? "neutral" : undefined),
    !auditor && item.kind && KIND_LABEL[item.kind] ? el("span", { class: "tiny", text: KIND_LABEL[item.kind] }) : null,
  );
}

/** 수강생 화면(pf_monitor)의 서버 명단. 주소 `q`, `sort`(name|studentNumber), `order`(asc|desc), `spage`. */
export function serverCourseRoster(ctx, course) {
  if (backendStatus() === "anon") return null;
  const courseId = courseIdFor(course.id);
  if (!courseId || roleIn(course.id) !== "INSTRUCTOR") return null;
  const filter = rosterFilter(ctx.params || new URLSearchParams(location.search));
  const query = rosterQuery(filter);
  const key = rosterKey(courseId, query);
  const list = el("div", { class: "srv-roster__list" });
  const load = () => courseRosters.load(key, courseId, query);
  const paint = (entry) => {
    if (waiting(entry)) return list.replaceChildren(loadingState("수강생 명단을 불러오는 중입니다"));
    if (!entry.data) {
      return list.replaceChildren(entry.error && entry.error.status === 404
        ? emptyState("수강생 명단을 볼 수 없습니다", "이 과목의 담당 교수, 조교, 비정규 담당자만 명단을 봅니다.")
        : errorState("수강생 명단을 불러오지 못했습니다", enrollmentMessage(entry.error), retry(load)));
    }
    const data = entry.data;
    const items = Array.isArray(data.items) ? data.items : [];
    const total = Number(data.total) || 0;
    const size = Number(data.size) || ROSTER_PAGE;
    list.replaceChildren(
      el("p", { class: "pf-sub", role: "status", text: filter.q ? `"${filter.q}"에 맞는 수강생 ${total}명` : `수강생 ${total}명` }),
      items.length
        ? table(["이름", "학번", "구분", "최근 영상 학습", "진도율"], items.map((item) => [
          item.name || "이름 없음",
          item.studentNumber || "-",
          rosterCell(item),
          // 그 과목에서 마지막으로 영상 학습을 시작한 일시다(일반 접속 시각이 아니다). 없으면 null.
          item.lastAccessAt ? koDateTime(item.lastAccessAt) || "—" : "—",
          progressCell(item.progressPercent),
        ]))
        : emptyState(filter.q ? "검색어에 맞는 수강생이 없습니다" : "수강생이 없습니다", filter.q ? "검색어를 바꾸거나 지워 보세요." : "수강이 확정되거나 청강이 승인된 학생이 여기에 나옵니다."),
      pagination({ page: Number(data.page) || filter.page, pages: Math.max(1, Math.ceil(total / size)), hrefForPage: rosterHref }),
    );
    return undefined;
  };
  paint(courseRosters.peek(key));
  attached(list, () => {
    courseRosters.watch(list, key, paint, load);
    courseRosters.visit("pf:roster", key, courseId, query);
  });
  return el("section", { class: "srv-roster", "aria-label": "수강생 명단" },
    el("h2", { class: "section-label", text: "수강생 명단" }),
    rosterBar(filter),
    list,
  );
}
