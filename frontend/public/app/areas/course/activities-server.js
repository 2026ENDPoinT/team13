// 백엔드 모드의 학생 강의실(40·72), 학습활동 목록(50–56), 파일모음(46). 한 과목의 서버 활동을
// shared/course-activities-remote.js가 모은 행으로만 그린다(주차 목록, 자료, 영상과 본인 진도, 과제·시험·설문과
// 본인 상태). 연결된 과목과 `anon`에서는 샘플 활동을 섞지 않는다. 진행 중·미제출 필터는 서버 상태(phase)만 본다.
// 강의실에는 그 과목의 최근 공지와 할 일(`/api/me/todo?courseId=`)도 서버에서 받아 보인다. 파일모음은 과목 파일
// 목록(`GET …/files`)이다. 서버 글은 텍스트 노드로만 넣는다.
import { el, icon } from "../../dom.js";
import { btn, go, loadingState, errorState, emptyState, pagination, badge } from "../../ui/components.js";
import { coverImage } from "../../ui/assets.js";
import { hrefFor, find } from "../../manifest.js";
import { ApiError } from "../../shared/api.js";
import { freshVisit } from "../../shared/remote-cache.js";
import { backendStatus, courseIdFor, roleIn, serverCourseOf } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { TYPE_COLOR } from "../../shared/format.js";
import {
  activityParts, watchActivities, activitySnapshot, passServerFilter, reloadFailed, serverOwnsCourse, PART_LABEL,
} from "../../shared/course-activities-remote.js";
import { rememberCourseFiles } from "../../shared/course-pdfs-remote.js";
import { downloadControl, sizeText } from "../../shared/uploads-remote.js";
import { noticeLists, listKey as noticeListKey, noticeMessage } from "../../shared/notices-remote.js";
import { courseTodoEntry, watchCourseTodo, reloadCourseTodo, todoHref, todoLabel, todoKind, ddayAt, koDateTime, koDate, homeMessage } from "../../shared/home-remote.js";
import { serverActivityHref } from "../../shared/activity-links.js";
import { windowText, lectureMessage, PROGRESS_STATE, ACTIVITY_PHASE } from "../../shared/lectures-remote.js";
import { materialMessage, courseFiles, filesKey, loadMoreFiles } from "../../shared/materials-remote.js";
import { assignmentMessage, periodText, PHASE as REPORT_PHASE, STATUS as REPORT_STATUS } from "../../shared/assignments-remote.js";
import { quizMessage, PHASE as QUIZ_PHASE, ATTEMPT_STATUS } from "../../shared/quizzes-remote.js";
import { surveyMessage, PHASE as SURVEY_PHASE, STATE as SURVEY_STATE } from "../../shared/surveys-remote.js";

const PAGE = 8;
const STRIP_NOTICES = 3;
const STRIP_TODOS = 5;

const TYPE_LABEL = { video: "영상", material: "자료", report: "과제", test: "시험", survey: "설문" };
const ROOM_TYPES = [["video", "영상"], ["material", "자료"], ["report", "과제"], ["test", "시험"], ["survey", "설문"]];

const MESSAGE = {
  weeks: lectureMessage, progress: lectureMessage, materials: materialMessage,
  assignments: assignmentMessage, quizzes: quizMessage, surveys: surveyMessage,
};

/** 이 과목의 활동 화면을 서버가 맡는지: `anon`이거나 `ready`이고 서버에 그 과목이 있을 때. */
export function serverOwnsActivities(course) {
  return serverOwnsCourse(course.id);
}

const instructorIn = (kj) => roleIn(kj) === "INSTRUCTOR";

function retryButton(onClick) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    onClick();
  });
  return button;
}

/** 주 범위 "11월 3일 – 11월 9일"(서버 LocalDate). 없으면 빈 글. */
function weekRange(week) {
  const part = (value) => {
    const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(value || ""));
    return match ? `${Number(match[2])}월 ${Number(match[3])}일` : "";
  };
  const start = part(week && week.startsOn);
  const end = part(week && week.endsOn);
  return start && end ? `${start} – ${end}` : start || end;
}

function periodOf(row) {
  if (row.type === "report") return periodText(row.item);
  if (row.type === "video") return windowText(row.item);
  if (row.type === "material") return row.openAt ? `${koDateTime(row.openAt)} 공개` : "";
  if (!row.closeAt) return row.openAt ? `${koDateTime(row.openAt)} ~` : "";
  return row.openAt ? `${koDateTime(row.openAt)} ~ ${koDateTime(row.closeAt)}` : `~ ${koDateTime(row.closeAt)}`;
}

function lectureState(row, instructor) {
  // 서버 시계의 구간. 진행 중이 아니면 앞에 붙인다.
  const phase = row.phase && row.phase !== "open" ? ACTIVITY_PHASE[row.phase] || "" : "";
  if (instructor) return [row.item.published ? "" : "비공개", phase].filter(Boolean).join(" · ");
  if (!row.progress) return phase;
  if (!row.progress.length) return [phase, "학습 기록 없음"].filter(Boolean).join(" · ");
  const met = row.progress.filter((item) => item.progress && item.progress.status === "met").length;
  if (met === row.progress.length) return [phase, PROGRESS_STATE.met].filter(Boolean).join(" · ");
  const percent = Math.round(row.progress.reduce((sum, item) => sum + (Number(item.progress && item.progress.progressPercent) || 0), 0) / row.progress.length);
  return [phase, `진도 ${percent}% · 인정 ${met}/${row.progress.length}`].filter(Boolean).join(" · ");
}

/** 행 오른쪽의 상태 글. 학생은 본인 상태와 서버 phase, 담당자는 서버의 수. */
function stateText(row, instructor) {
  const item = row.item;
  if (row.type === "video") return lectureState(row, instructor);
  if (row.type === "material") {
    // 시작 전 자료는 담당자에게만 온다.
    return [row.phase === "not_open" ? ACTIVITY_PHASE.not_open : "", row.files && row.files.length ? `파일 ${row.files.length}개` : ""].filter(Boolean).join(" · ");
  }
  if (row.type === "report") {
    if (!instructor) return item.phase === "open" ? REPORT_STATUS[item.status] || item.status : `${REPORT_STATUS[item.status] || item.status} · ${REPORT_PHASE[item.phase] || item.phase}`;
    return [`제출 ${item.submittedCount ?? 0}`, item.published ? null : "비공개", REPORT_PHASE[item.phase] || item.phase].filter(Boolean).join(" · ");
  }
  if (row.type === "test") {
    const phase = QUIZ_PHASE[item.phase] || item.phase;
    if (!instructor) {
      const own = item.examType === "offline" ? "오프라인" : ATTEMPT_STATUS[item.status] || item.status;
      return item.phase === "open" ? own : `${own} · ${phase}`;
    }
    return [`응시 중 ${item.inProgressCount ?? 0}`, `종료 ${item.finishedCount ?? 0}`, item.published ? null : "비공개", phase].filter(Boolean).join(" · ");
  }
  if (row.type === "survey") {
    const phase = SURVEY_PHASE[item.phase] || item.phase;
    if (!instructor) return item.phase === "open" ? SURVEY_STATE[item.state] || item.state : `${SURVEY_STATE[item.state] || item.state} · ${phase}`;
    return [`응답 ${item.respondentCount ?? 0}`, item.published ? null : "비공개", phase].filter(Boolean).join(" · ");
  }
  return "이 화면에서 열 수 없는 활동";
}

/** 활동 한 줄. 볼 화면이 있으면 링크, 없으면 비활성 줄이다. */
function rowNode(row, kj) {
  const type = row.type === "other" ? "post" : row.type;
  const label = row.type === "other" ? todoLabel(row.kind) : TYPE_LABEL[row.type];
  const attrs = { class: "activity", "data-type": type, "data-row": row.key, "data-activity": row.key };
  const node = row.href ? el("a", { ...attrs, href: row.href, "data-go": row.href }) : el("div", { ...attrs, "aria-disabled": "true" });
  const period = periodOf(row);
  node.append(
    el("span", { class: "swatch", style: `background:${TYPE_COLOR[type] || "var(--surface-2)"}` }),
    el("span", {}, el("b", { text: row.title }), el("div", { class: "tiny", text: [label, `${row.weekNo}주`, period].filter(Boolean).join(" · ") })),
    el("span", { class: "tiny" }, row.done ? el("span", { class: "check", "aria-hidden": "true", text: "✓ " }) : "", stateText(row, instructorIn(kj))),
  );
  return node;
}

/** 실패한 부분 목록 안내와 다시 받기. 받아 둔 내용이 있으면 그것은 그대로 보인다. */
function failureNotes(snapshot, parts) {
  if (!snapshot.failures.length) return null;
  const lines = snapshot.failures.map((failure) => {
    const say = MESSAGE[failure.name] || lectureMessage;
    return el("li", { text: `${PART_LABEL[failure.name] || failure.name}: ${failure.error instanceof ApiError && failure.error.status === 404 ? "볼 수 없습니다." : say(failure.error)}` });
  });
  return el("div", { class: "err crs-srv-fail", role: "alert" },
    el("p", { text: "일부 활동을 불러오지 못했습니다." }),
    el("ul", {}, ...lines),
    retryButton(() => reloadFailed(parts)),
  );
}

function loginPanel(text) {
  return el("section", { class: "crs-week open" }, backendPanel({ compact: true, text }));
}

// ---------- 강의실 ----------

function noticeStrip(kj, courseId) {
  const key = noticeListKey(courseId, "", 1, STRIP_NOTICES);
  const load = () => noticeLists.load(key, courseId, "", 1, STRIP_NOTICES);
  const list = el("div", { class: "crs-strip-body", "aria-live": "polite" });
  const all = hrefFor(find("44_room_notice_list"), { kj });
  const card = el("section", { class: "crs-strip", "aria-label": "최근 공지" },
    el("div", { class: "crs-strip-h" }, el("h2", { text: "공지" }), go(all, "전체 보기", { class: "more-link" })),
    list,
  );
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      if (!entry || !entry.error) list.replaceChildren(loadingState("공지를 불러오는 중입니다"));
      else list.replaceChildren(errorState("공지를 불러오지 못했습니다", noticeMessage(entry.error), retryButton(load)));
      return;
    }
    const items = Array.isArray(data.items) ? data.items : [];
    list.replaceChildren(
      entry.error ? el("p", { class: "err", role: "alert", text: noticeMessage(entry.error) }) : "",
      ...(items.length ? items.map((notice) => {
        const href = hrefFor(find("44d_room_notice_view_pop"), { kj, id: String(notice.noticeId) });
        return el("a", { class: "crs-strip-row", href, "data-go": href },
          notice.read ? el("span", { class: "crs-strip-dot read", "aria-hidden": "true" }) : el("span", { class: "crs-strip-dot", role: "img", "aria-label": "읽지 않음" }),
          el("span", { class: "crs-strip-title" }, notice.pinned ? badge("고정", "neutral") : "", el("b", { text: notice.title })),
          el("span", { class: "tiny", text: koDate(notice.publishedAt) }),
        );
      }) : [el("p", { class: "tiny", text: "등록된 공지가 없습니다." })]),
    );
  };
  paint(noticeLists.peek(key));
  queueMicrotask(() => {
    if (!card.isConnected) return;
    noticeLists.watch(card, key, paint, load);
    if (freshVisit("course-room-notices", key) === "entry" || !noticeLists.peek(key)) load();
  });
  return card;
}

/** 이 과목의 할 일(`GET /api/me/todo?courseId=`의 첫 쪽과 남은 수). 전체 목록은 할 일 팝업이다. */
function todoStrip(kj, courseId) {
  const body = el("div", { class: "crs-strip-body", "aria-live": "polite" });
  const count = el("span", { class: "count-num" });
  const card = el("section", { class: "crs-strip", "aria-label": "이 과목의 할 일" },
    el("div", { class: "crs-strip-h" }, el("h2", {}, "할 일 ", count), go(hrefFor(find("09_home_todo_more")), "모두 보기", { class: "more-link" })),
    body,
  );
  const paint = () => {
    const entry = courseTodoEntry(courseId);
    const data = entry && entry.data;
    if (!data) {
      count.textContent = "";
      if (!entry || !entry.error) body.replaceChildren(loadingState("할 일을 불러오는 중입니다"));
      else body.replaceChildren(errorState("할 일을 불러오지 못했습니다", homeMessage(entry.error), retryButton(() => reloadCourseTodo(courseId))));
      return;
    }
    count.textContent = String(data.total);
    body.replaceChildren(
      entry.error ? el("p", { class: "err", role: "alert", text: homeMessage(entry.error) }) : "",
      ...(data.items.length ? data.items.slice(0, STRIP_TODOS).map((item) => {
        const href = todoHref(item, null);
        const due = ddayAt(item.closeAt, data.serverTime);
        return el(href ? "a" : "div", { class: "crs-strip-row", href, "data-go": href, "data-todo": todoKind(item.type) },
          el("span", { class: "crs-kind", text: todoLabel(item.type) }),
          el("span", { class: "crs-strip-title" }, el("b", { text: item.title })),
          el("span", { class: "tiny", text: [due, koDateTime(item.closeAt)].filter(Boolean).join(" · ") }),
        );
      }) : [el("p", { class: "tiny", text: "남은 할 일이 없습니다." })]),
    );
  };
  paint();
  queueMicrotask(() => watchCourseTodo(card, courseId, paint));
  return { card, paint };
}

function heroOf(course, kj, rows) {
  const server = serverCourseOf(kj);
  const name = server ? server.name : course.ko;
  const meta = server
    ? [server.term && server.term.name, [server.code, server.section].filter(Boolean).join("-")].filter(Boolean).join(" · ")
    : "";
  const tags = [];
  if (server && server.type === "NONREGULAR") tags.push("비교과");
  if (rows.some((row) => row.type === "video")) tags.push("온라인");
  if (server && Array.isArray(server.schedules) && server.schedules.some((slot) => slot.room)) tags.push("대면");
  if (server && server.credit) tags.push(`${server.credit}학점`);
  const next = rows.find((row) => row.href && row.missing) || rows.find((row) => row.href && row.ongoing && !row.done);
  return el("article", { class: `crs-hero ${course.gradient || "g3"}` },
    coverImage(course, 1110, 188),
    el("div", { class: "crs-hero-copy" },
      el("p", { class: "crs-kicker", text: server && server.term ? server.term.name : "" }),
      el("div", { class: "course-name" },
        el("h1", { class: "course-title", text: name }),
        el("span", { class: "course-prof", text: server && Array.isArray(server.instructors) ? server.instructors.join(", ") : "" }),
      ),
      meta ? el("p", { class: "crs-hero-meta", text: meta }) : null,
      el("div", { class: "crs-tags" }, ...tags.map((tag) => el("span", { class: "crs-tag", text: tag }))),
    ),
    next ? go(next.href, "이어서 학습하기", { class: "btn btn-ondark" }) : null,
  );
}

function chip(key, label, kind, on) {
  return el("button", {
    type: "button",
    class: `chip ${kind === "type" ? key : ""}${on ? " on" : ""}`.trim(),
    "data-act": "room-filter",
    "data-kind": kind,
    "data-value": key,
    "aria-pressed": on ? "true" : "false",
    text: label,
  });
}

/** 백엔드 모드의 강의실 본문(40·72). */
export function remoteRoom(screen, ctx, course) {
  const kj = course.id;
  const planHref = hrefFor(find("40b_room_lecplan_pop"), { kj });
  const plan = el("a", { class: "crs-plan", href: planHref, "data-go": planHref },
    el("span", { class: "crs-plan-label" }, icon("file"), el("span", { text: "강의계획서" })),
    icon("chev"),
  );
  if (backendStatus() === "anon") {
    return el("section", { class: "crs" }, plan, loginPanel("ASTRA 서버에 로그인하면 이 과목의 주차와 활동, 공지, 할 일을 서버에서 불러옵니다."));
  }
  const courseId = courseIdFor(kj);
  const instructor = instructorIn(kj);
  const parts = activityParts(courseId, { instructor });
  const draft = ctx.state.draft || {};
  const type = screen.focus === "video" ? "video" : (draft.roomType || "");
  const status = draft.roomStatus || "";
  const closed = new Set(draft.closedWeeks || []);
  const unit = serverCourseOf(kj) && serverCourseOf(kj).type === "NONREGULAR" ? "회차" : "주";

  const hero = el("div", { class: "crs-srv-hero" });
  const todo = todoStrip(kj, courseId);
  const strips = el("div", { class: "crs-strips" }, noticeStrip(kj, courseId), todo.card);
  const filters = el("div", { class: "crs-filters" });
  const weekTabs = el("div", { class: "crs-progress", role: "tablist", "aria-label": `${unit}차` });
  const body = el("div", { class: "crs-srv-weeks", "aria-live": "polite" });
  const root = el("section", { class: "crs", "aria-label": "ASTRA 서버 강의실" }, hero, plan, strips, filters, weekTabs, body);

  const paint = () => {
    todo.paint();
    const snapshot = activitySnapshot(parts, kj, { instructor });
    const { rows, weeks } = snapshot;
    hero.replaceChildren(heroOf(course, kj, rows));
    const present = new Set(rows.map((row) => row.type));
    filters.replaceChildren(
      el("div", { class: "crs-filter-row", role: "toolbar", "aria-label": "활동 종류" },
        ...ROOM_TYPES.filter(([key]) => present.has(key) || type === key).map(([key, label]) => chip(key, label, "type", type === key))),
      el("div", { class: "crs-filter-row", role: "toolbar", "aria-label": "진행 상태" },
        chip("ongoing", "진행중", "status", status === "ongoing"), chip("missing", "미제출", "status", status === "missing")),
    );
    const week = ctx.state.week || 1;
    weekTabs.replaceChildren(...weeks.map((entry) => {
      const n = entry.weekNo;
      const tracked = rows.filter((row) => row.weekNo === n && ["video", "report", "test", "survey"].includes(row.type));
      const done = tracked.filter((row) => row.done).length;
      const markText = !tracked.length || !done ? "" : done === tracked.length ? "✓" : `${done}/${tracked.length}`;
      return el("button", {
        type: "button",
        class: `crs-wk${week === n ? " on" : ""}`,
        "data-act": "week",
        "data-week": String(n),
        "aria-pressed": week === n ? "true" : "false",
        "aria-label": `${n}${unit}${entry.title ? ` ${entry.title}` : ""}`,
      }, el("span", { text: String(n) }), el("span", { class: "crs-wk-mark", text: markText || " " }));
    }));
    weekTabs.hidden = !weeks.length;
    if (snapshot.denied) {
      body.replaceChildren(emptyState("이 과목의 활동을 볼 수 없습니다", "수강 중인 과목인지 확인해 주세요."));
      return;
    }
    if (snapshot.pending && !rows.length) {
      body.replaceChildren(loadingState("주차와 활동을 불러오는 중입니다"));
      return;
    }
    const shown = rows.filter((row) => (!type || row.type === type) && (!status || passServerFilter(row, status)));
    const notes = failureNotes(snapshot, parts);
    const byWeek = new Map();
    for (const row of shown) {
      if (!byWeek.has(row.weekNo)) byWeek.set(row.weekNo, []);
      byWeek.get(row.weekNo).push(row);
    }
    const sections = [...byWeek.keys()].sort((a, b) => a - b).map((n) => {
      const entry = weeks.find((item) => item.weekNo === n);
      const isClosed = closed.has(n);
      return el("section", { class: `crs-week${isClosed ? "" : " open"}${week === n ? " current" : ""}` },
        el("button", { type: "button", class: "crs-week-h", "data-act": "toggle-week", "data-week": String(n), "aria-expanded": isClosed ? "false" : "true" },
          el("span", { class: "crs-week-label" },
            el("strong", { text: `${n}${unit}${entry && entry.title ? ` ${entry.title}` : ""}` }),
            el("span", { text: weekRange(entry) }),
          ),
          icon("chev"),
        ),
        isClosed ? null : el("div", { class: "crs-week-b" }, ...byWeek.get(n).map((row) => rowNode(row, kj))),
      );
    });
    body.replaceChildren(...[notes, ...sections].filter(Boolean));
    if (!sections.length) {
      body.append(rows.length
        ? emptyState("해당하는 활동이 없습니다", "다른 종류나 진행 상태를 선택해 보세요.")
        : snapshot.pending ? loadingState("주차와 활동을 불러오는 중입니다") : emptyState("등록된 활동이 없습니다", "교수님이 활동을 공개하면 주차별로 표시됩니다."));
    }
  };
  paint();
  queueMicrotask(() => watchActivities(root, parts, "course-room", paint));
  return root;
}

// ---------- 학습활동 목록 ----------

/** 백엔드 모드의 학습활동 목록 본문(50–56): 서버 활동 전체를 필터에 맞게 주차별로, 8개씩 쪽을 나눈다. */
export function remoteActivityList(screen, ctx, course, filter) {
  if (backendStatus() === "anon") return loginPanel("ASTRA 서버에 로그인하면 이 과목의 학습활동을 서버에서 불러옵니다.");
  const kj = course.id;
  const courseId = courseIdFor(kj);
  const instructor = instructorIn(kj);
  const parts = activityParts(courseId, { instructor });
  const page = Math.max(1, Math.floor(Number(ctx.params && ctx.params.get("page"))) || 1);
  const pageHref = (n) => hrefFor(find(screen.id), n > 1 ? { kj, page: String(n) } : { kj });
  const body = el("div", { class: "crs-srv-list", "aria-live": "polite", "aria-label": "ASTRA 서버 학습활동" });
  const paint = () => {
    const snapshot = activitySnapshot(parts, kj, { instructor });
    if (snapshot.denied) return body.replaceChildren(emptyState("이 과목의 활동을 볼 수 없습니다", "수강 중인 과목인지 확인해 주세요."));
    const rows = snapshot.rows.filter((row) => passServerFilter(row, filter));
    if (snapshot.pending && !rows.length) return body.replaceChildren(loadingState("학습활동을 불러오는 중입니다"));
    const pages = Math.max(1, Math.ceil(rows.length / PAGE));
    const current = Math.min(pages, page);
    const slice = rows.slice((current - 1) * PAGE, current * PAGE);
    const weeks = [...new Set(slice.map((row) => row.weekNo))].sort((a, b) => a - b);
    const notes = failureNotes(snapshot, parts);
    body.replaceChildren(...[notes].filter(Boolean));
    if (!slice.length) {
      body.append(snapshot.pending ? loadingState("학습활동을 불러오는 중입니다") : emptyState("해당하는 활동이 없습니다", filter === "all" ? "교수님이 활동을 공개하면 이 목록에 표시됩니다." : "다른 필터를 선택해 보세요."));
      return;
    }
    for (const n of weeks) {
      const entry = snapshot.weeks.find((item) => item.weekNo === n);
      body.append(el("section", { class: "crs-week open" },
        el("h2", { class: "crs-rail-week", text: [`${n}주`, weekRange(entry)].filter(Boolean).join("  ") }),
        ...slice.filter((row) => row.weekNo === n).map((row) => rowNode(row, kj)),
      ));
    }
    const nav = pagination({ page: current, pages, hrefForPage: pageHref });
    if (nav) body.append(nav);
  };
  paint();
  queueMicrotask(() => watchActivities(body, parts, "course-activity-list", paint));
  return body;
}

// ---------- 파일모음 ----------

function extensionOf(file) {
  const ext = String(file.extension || String(file.name || "").split(".").pop() || "").toLowerCase();
  return ext ? ext.toUpperCase() : "파일";
}

/** 검색어를 고친 뒤 서버에 묻기까지 기다리는 시간(ms). 그 사이 다시 고치면 앞 요청은 보내지 않는다. */
const SEARCH_DELAY = 300;

/**
 * 백엔드 모드의 파일모음(46): `GET …/files?q=&cursor=&size=`. 학습활동에 첨부된 파일을 주차·활동 순으로 서버가 나눈 쪽으로
 * 받고, 검색(`q`, 파일 이름·활동 제목)도 서버가 한다. "더 보기"는 `nextCursor`로 다음 쪽을 붙인다. 내려받기는 첨부 원본
 * Blob, 미리보기는 서버 미리보기 화면이다.
 */
export function remoteFiles(ctx, course, { head, searchBox, query }) {
  const kj = course.id;
  const title = head("파일모음", "file", null);
  const toolbar = el("div", { class: "crs-toolbar" },
    el("p", { class: "crs-note", text: "이 과목 학습활동에 첨부된 파일을 주차별로 모았습니다." }),
    searchBox("파일 검색", ctx, "files"),
  );
  if (backendStatus() === "anon") {
    return el("section", { class: "crs" }, title, loginPanel("ASTRA 서버에 로그인하면 이 과목의 파일을 서버에서 불러옵니다."));
  }
  const courseId = courseIdFor(kj);
  const q = String(query || "").trim().slice(0, 100);
  const key = filesKey(courseId, q);
  const load = () => courseFiles.load(key, courseId, q, null);
  const body = el("div", { class: "crs-files", "aria-live": "polite" });
  const root = el("section", { class: "crs" }, title, toolbar, body);
  const total = badge("0", "neutral");
  let remembered = null;
  const paint = () => {
    const entry = courseFiles.peek(key);
    const data = entry && entry.data;
    if (!data) {
      total.remove();
      if (!entry || !entry.error) body.replaceChildren(loadingState("파일을 불러오는 중입니다"));
      else if (entry.error instanceof ApiError && entry.error.status === 404) body.replaceChildren(emptyState("이 과목의 파일을 볼 수 없습니다", "수강 중인 과목인지 확인해 주세요."));
      else body.replaceChildren(errorState("파일을 불러오지 못했습니다", materialMessage(entry.error), retryButton(load)));
      return;
    }
    // 미리보기 화면의 제목과 요약 게시 여부가 이 목록의 레코드를 쓴다.
    if (remembered !== data) {
      remembered = data;
      rememberCourseFiles(data.items, kj, courseId);
    }
    total.textContent = String(data.total);
    const headTitle = title.querySelector(".crs-head-title");
    if (headTitle && !total.isConnected) headTitle.append(total);
    const stale = entry.error ? el("p", { class: "err", role: "alert", text: materialMessage(entry.error) }) : null;
    if (!data.items.length) {
      return body.replaceChildren(...[stale, q
        ? emptyState("검색 결과가 없습니다", "파일 이름이나 활동 제목의 일부만 입력해 보세요.")
        : emptyState("파일이 없습니다", "학습활동에 파일이 첨부되면 주차별로 모입니다.")].filter(Boolean));
    }
    body.replaceChildren(...[stale].filter(Boolean));
    let block = null;
    let blockWeek = null;
    for (const row of data.items) {
      const file = row.file || {};
      if (!block || blockWeek !== row.weekNo) {
        blockWeek = row.weekNo;
        block = el("section", { class: "crs-week open" },
          el("h2", { class: "crs-rail-week", text: [`${row.weekNo}주`, row.weekTitle || "", weekRange(row)].filter(Boolean).join("  ") }));
        body.append(block);
      }
      const preview = hrefFor(find("65_file_preview"), { n: `srv-${file.fileId}`, kj });
      const detail = serverActivityHref(kj, row.activityType, row.activityId);
      const { button, note } = downloadControl(courseId, { fileId: file.fileId, name: file.name });
      block.append(el("article", { class: "crs-file", "data-file": String(file.fileId) },
        el("span", { class: "crs-kind", text: extensionOf(file) }),
        el("div", { class: "crs-file-main" },
          el("h3", { text: file.name }),
          el("p", { class: "tiny" },
            sizeText(file.sizeBytes),
            " · ",
            detail ? go(detail, row.activityTitle, { class: "crs-file-activity" }) : el("span", { text: row.activityTitle }),
          ),
          note,
        ),
        el("div", { class: "crs-file-actions" },
          el("a", { class: "icon-btn", href: preview, "data-go": preview, "aria-label": `${file.name} 미리보기` }, icon("search")),
          button,
        ),
      ));
    }
    if (data.nextCursor) {
      const more = btn(`더 보기 (${data.items.length}/${data.total})`, {}, "secondary");
      if (entry.loading) {
        more.disabled = true;
        more.setAttribute("aria-busy", "true");
      }
      more.addEventListener("click", () => {
        if (more.disabled) return;
        more.disabled = true;
        more.setAttribute("aria-busy", "true");
        void loadMoreFiles(courseId, q);
      });
      body.append(el("div", { class: "btn-row" }, more));
    }
  };
  paint();
  queueMicrotask(() => {
    if (!root.isConnected) return;
    // 포커스 복귀 때 첫 쪽만 다시 받으면 "더 보기"로 붙인 쪽이 사라지므로, 새로 읽는 것은 화면 이동과 검색어 변경 때다.
    courseFiles.watch(root, key, paint, null);
    const fresh = freshVisit("course-files", key);
    if (fresh !== "entry" && courseFiles.peek(key)) return;
    // 검색어를 고치는 동안은 잠시 기다렸다가 묻는다. 그 사이 화면이 다시 그려지면(다음 글자) 이 요청은 보내지 않는다.
    if (fresh === "key") setTimeout(() => { if (root.isConnected) load(); }, SEARCH_DELAY);
    else load();
  });
  return root;
}
