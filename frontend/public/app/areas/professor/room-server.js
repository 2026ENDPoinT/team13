// 교수 강의실의 서버 화면(백엔드 `ready`, 서버에 있는 과목): 주별 보기와 수업활동 보기(`pf_room_week`·`pf_room_activity`),
// 활동 등록(`pf_week_add`)의 유형 고르기. 활동은 `shared/course-room-remote.js`가 서버 주차 순서에 유형별 목록을 붙여
// 만든다. 줄마다 그 활동을 관리하는 서버 화면으로 가고, 공개 전환과 삭제(삭제 표시)는 마지막으로 읽은 버전으로
// 보낸다. 줄의 안내와 삭제 확인은 세션이 바뀌면 버리는 메모리 맵에 두어 다시 그려도 남는다.
import { el } from "../../dom.js";
import { btn, go, badge, emptyState, loadingState, errorState, field } from "../../ui/components.js";
import { backendStatus, courseIdFor, roleIn, subscribe } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { routeFor } from "../../shared/routes.js";
import { koDate, koDateTime } from "../../shared/home-remote.js";
import {
  ROOM_TYPES, DELETABLE, roomEntry, watchRoom, reloadRoom, setPublished, removeActivity, forgetRow, isStale, isGone, roomMessage,
} from "../../shared/course-room-remote.js";
import { PHASE as ASSIGNMENT_PHASE } from "../../shared/assignments-remote.js";
import { PHASE as QUIZ_PHASE } from "../../shared/quizzes-remote.js";
import { PHASE as SURVEY_PHASE } from "../../shared/surveys-remote.js";
import { lectureRef, lectureItemsOf, ACTIVITY_PHASE } from "../../shared/lectures-remote.js";
import { serverRef as assignmentRef } from "../../shared/assignments-remote.js";
import { quizRef } from "../../shared/quizzes-remote.js";
import { surveyRef } from "../../shared/surveys-remote.js";
import { presetLectureWeek } from "./lectures-server.js";

const pf = (screenId, extra) => routeFor("professor", "screen", { screenId, ...(extra || {}) });
const note = (text) => el("p", { class: "pf-sub", text });
const MAX_WEEK = 60;

// "<courseId>:<type>:<id>" -> { text, alert }, 진행 중인 명령, 한 번 누른 삭제
const notes = new Map();
const busy = new Set();
const armed = new Set();
const rowsByKey = new Map(); // 마지막으로 그린 줄
const views = new WeakMap(); // 강의실 노드 -> { courseId, paint }
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  notes.clear();
  busy.clear();
  armed.clear();
  rowsByKey.clear();
});

/** 이 과목의 강의실을 서버로 보이는지. `anon`은 로그인 안내만 보인다. 서버에 없는 과목은 샘플 강의실 그대로다. */
export function serverRoom(course) {
  const status = backendStatus();
  return Boolean(course) && (status === "anon" || (status === "ready" && Boolean(courseIdFor(course.id))));
}

function gate(course, text) {
  if (backendStatus() === "anon") return el("div", { class: "stack" }, backendPanel({ text }));
  if (roleIn(course.id) !== "INSTRUCTOR") return el("div", { class: "stack" }, backendPanel(), note("이 과목을 담당하는 교수 계정으로 로그인해야 강의실 활동을 관리할 수 있습니다."));
  return null;
}

const rowKey = (courseId, row) => `${courseId}:${row.type}:${row.id ?? `a${row.activityId}`}`;

function periodText(row) {
  if (row.closeAt) return `${row.openAt ? `${koDateTime(row.openAt)} ~ ` : "~ "}${koDateTime(row.closeAt)}`;
  if (row.openAt) return `${koDateTime(row.openAt)}부터`;
  return "기간 없음";
}

function countText(row) {
  const item = row.item;
  if (row.type === "lecture_weeks") {
    const items = row.detail ? lectureItemsOf(row.detail) : null;
    if (!items) return "콘텐츠 정보를 불러오는 중";
    const by = (state) => items.filter((entry) => entry.state === state).length;
    return [`콘텐츠 ${items.length}개`, `재생 가능 ${by("ready")}`, by("processing") ? `변환 중 ${by("processing")}` : "", by("failed") ? `재생 불가 ${by("failed")}` : ""].filter(Boolean).join(" · ");
  }
  if (!item) return row.type in ROOM_TYPES ? "목록 정보를 불러오는 중" : "";
  if (row.type === "lecture_material") return `파일 ${Array.isArray(item.files) ? item.files.length : 0}개`;
  if (row.type === "report") return `제출 ${item.submittedCount ?? 0} · 지각 ${item.lateCount ?? 0}`;
  if (row.type === "test") {
    if (item.examType === "offline") return "오프라인 시험";
    return `응시 중 ${item.inProgressCount ?? 0} · 종료 ${item.finishedCount ?? 0} · 채점 대기 ${item.awaitingGradingCount ?? 0}`;
  }
  if (row.type === "survey") return `${item.anonymous ? "익명" : "실명"} · ${item.questionCount ?? 0}문항 · 응답 ${item.respondentCount ?? 0}명`;
  return "";
}

/** 공개 여부와 서버 시계로 정한 진행 단계(`phase`). */
function stateBadge(row) {
  if (!row.published) return badge("비공개", "neutral");
  const phase = row.phase;
  const tone = phase === "open" || phase === "late" ? "success" : "neutral";
  if (row.type === "report" && phase) return badge(ASSIGNMENT_PHASE[phase] || phase, tone);
  if (row.type === "test" && phase) return badge(QUIZ_PHASE[phase] || phase, tone);
  if (row.type === "survey" && phase) return badge(SURVEY_PHASE[phase] || phase, tone);
  if (row.type === "lecture_weeks" && row.detail) {
    const items = lectureItemsOf(row.detail);
    if (items.some((item) => item.state === "failed")) return badge("재생 불가 콘텐츠", "danger");
    if (items.some((item) => item.state === "processing")) return badge("변환 중", "neutral");
  }
  if (phase && phase !== "open") return badge(ACTIVITY_PHASE[phase] || phase, "neutral");
  return badge("공개", "success");
}

/** 줄의 본문 링크: 그 활동을 관리하는 서버 화면. */
function detailHref(row, course) {
  const kj = course.id;
  if (row.type === "lecture_weeks") return pf("pf_lecture", { kj, n: lectureRef(row.activityId) });
  if (row.type === "lecture_material") return pf("pf_pdf", { kj, material: String(row.activityId) });
  if (row.type === "report") return row.id ? pf("pf_submissions", { kj, n: assignmentRef(row.id) }) : pf("pf_report_status", { kj });
  if (row.type === "test") return row.id ? pf("pf_quiz", { kj, n: quizRef(row.id) }) : pf("pf_exam_status", { kj });
  if (row.type === "survey") return row.id ? pf("pf_discuss", { kj, n: surveyRef(row.id) }) : pf("pf_discuss", { kj });
  return null;
}

function editHref(row, course) {
  const kj = course.id;
  if (row.type === "lecture_weeks") return pf("pf_contents", { kj, open: String(row.activityId) });
  if (row.type === "lecture_material") return pf("pf_material_write", { kj, open: String(row.activityId) });
  if (!row.id) return null;
  if (row.type === "report") return pf("pf_report_create", { kj, edit: String(row.id) });
  if (row.type === "test") return pf("pf_exam_create", { kj, edit: String(row.id) });
  if (row.type === "survey") return pf("pf_discuss", { kj, edit: String(row.id) });
  return null;
}

function activityRow(row, course, courseId) {
  const key = rowKey(courseId, row);
  rowsByKey.set(key, row);
  const meta = ROOM_TYPES[row.type];
  const href = detailHref(row, course);
  const label = meta ? meta.label : "기타";
  const main = el(href ? "a" : "div", { class: "pf-activity-main", href, "data-go": href, "data-activity": key },
    el("span", { class: `pill ${meta ? meta.chip : "post"}`, text: label }),
    el("span", { class: "pf-activity-copy" },
      el("b", { text: row.title }),
      el("span", { class: "pf-meta", text: [`${row.weekNo}주차`, periodText(row), countText(row)].filter(Boolean).join(" · ") }),
      meta ? null : el("span", { class: "pf-meta", text: "이 유형은 아직 관리 화면이 없습니다." }),
    ),
    stateBadge(row),
  );
  const working = busy.has(key);
  const actions = [];
  const edit = meta ? editHref(row, course) : null;
  if (edit) actions.push(go(edit, "수정", { class: "btn btn-ghost", "aria-label": `${row.title} 수정` }));
  if (meta && (row.type === "lecture_weeks" || row.type === "lecture_material" || row.id)) {
    const toggle = btn(working && !armed.has(key) ? "바꾸는 중…" : row.published ? "비공개로" : "공개하기", {
      "data-act": "pf-room-publish",
      "data-key": key,
      "data-course": String(courseId),
      "data-next": row.published ? "0" : "1",
      "aria-label": `${row.title} ${row.published ? "비공개로 바꾸기" : "공개하기"}`,
      "aria-busy": working ? "true" : "false",
    }, "ghost");
    toggle.disabled = working;
    actions.push(toggle);
  }
  if (DELETABLE.has(row.type) && (row.type === "lecture_weeks" || row.type === "lecture_material" || row.id)) {
    const remove = btn(working && armed.has(key) ? "삭제하는 중…" : armed.has(key) ? "한 번 더 누르면 삭제" : "삭제", {
      "data-act": "pf-room-delete",
      "data-key": key,
      "data-course": String(courseId),
      "aria-label": `${row.title} 삭제`,
      "aria-busy": working && armed.has(key) ? "true" : "false",
    }, armed.has(key) ? "danger" : "ghost");
    remove.disabled = working;
    actions.push(remove);
  }
  const said = notes.get(key);
  return el("div", { class: "pf-activity", "data-activity": key },
    main,
    actions.length ? el("div", { class: "pf-activity-actions" }, ...actions) : null,
    said ? el("p", { class: said.alert ? "err" : "pf-sub", role: said.alert ? "alert" : "status", text: said.text }) : null,
  );
}

function retry(courseId, slot, paint) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    void reloadRoom(courseId, slot, paint);
  });
  return button;
}

function weekHead(week) {
  const range = week.startsOn ? ` · ${koDate(week.startsOn)}${week.endsOn ? ` – ${koDate(week.endsOn)}` : ""}` : "";
  return `${week.weekNo}주차${week.title ? ` · ${week.title}` : ""}${range}`;
}

const chipOf = (row) => (ROOM_TYPES[row.type] ? ROOM_TYPES[row.type].chip : "etc");

/**
 * 강의실 본문(배너와 주별/수업활동 탭 아래). `parts`는 professor.js의 `stat`·`chipRow`·`finder`다.
 * 캐시로 바로 그리고, 노드가 문서에 붙은 뒤에만 받는다.
 */
export function serverRoomView(mode, ctx, course, parts) {
  const blocked = gate(course, "ASTRA 서버에 로그인하면 이 과목의 주차와 활동을 서버에서 불러옵니다.");
  if (blocked) return blocked;
  const courseId = courseIdFor(course.id);
  const { stat, chipRow, finder } = parts;
  const slot = el("div", { class: "stack pf-room-server", "data-room": String(courseId) });
  const params = ctx.params;
  let last = null;
  const paint = (fresh) => {
    // 명령 뒤 다시 받는 동안은 지난 목록을 그대로 둔다(캐시를 버린 직후 빈 화면으로 깜박이지 않게).
    const room = !fresh.weeks && !fresh.error && last ? last : fresh;
    if (room.weeks) last = room;
    if (!room.weeks) {
      if (room.error && room.error.part === "weeks") {
        slot.replaceChildren(isGone(room.error.error)
          ? emptyState("이 과목의 강의실을 볼 수 없습니다", "담당 교수 계정인지, 과목이 아직 있는지 확인하세요.")
          : errorState("강의실 활동을 불러오지 못했습니다", roomMessage("weeks", room.error.error), retry(courseId, slot, paint)));
      } else slot.replaceChildren(loadingState("강의실 활동을 불러오는 중입니다"));
      return;
    }
    const partial = room.error
      ? el("div", { class: "stack" }, el("p", { class: "err", role: "alert", text: `일부 목록을 불러오지 못해 개수가 빠질 수 있습니다. ${roomMessage({ lectures: "lecture_weeks", assignments: "report", quizzes: "test", surveys: "survey", materials: "lecture_material" }[room.error.part] || "weeks", room.error.error)}` }), retry(courseId, slot, paint))
      : null;
    const rows = room.rows;
    const widgets = el("div", { class: "stat-row" },
      stat("활동", String(rows.length)),
      stat("비공개", String(rows.filter((row) => !row.published).length)),
      // 자료는 마감이 없어 시작한 뒤 늘 open이므로 세지 않는다.
      stat("진행 중", String(rows.filter((row) => row.published && row.type !== "lecture_material" && (row.phase === "open" || row.phase === "late")).length)),
      stat("주차", String(room.weeks.length)),
    );
    const guide = note("공개 전환과 삭제는 마지막으로 불러온 버전으로 보냅니다. 그 사이 다른 곳에서 고쳤으면 서버가 거절하고 최신 내용을 다시 불러옵니다. 삭제는 학생과 교수 모두에게서 활동을 지우고, 학습·제출·응시·응답 기록은 서버에 남깁니다.");
    if (mode === "activity") {
      const type = (params && params.get("type")) || "";
      const q = ((params && params.get("q")) || "").trim();
      const shown = rows.filter((row) => !type || chipOf(row) === type)
        .filter((row) => !q || `${row.title} ${ROOM_TYPES[row.type] ? ROOM_TYPES[row.type].label : ""}`.includes(q));
      const options = [["all", "전체", "all"], ...Object.values(ROOM_TYPES).map(({ chip, label }) => [chip, `${label} ${rows.filter((row) => chipOf(row) === chip).length}`, chip])];
      slot.replaceChildren(
        widgets,
        partial || "",
        el("div", { class: "pf-toolbar" }, chipRow(ctx, "pf_room_activity", options, "type"), finder(ctx, "활동 검색")),
        shown.length
          ? el("div", { class: "pf-activities" }, ...shown.map((row) => activityRow(row, course, courseId)))
          : emptyState(rows.length ? "해당하는 활동이 없습니다" : "서버에 등록된 활동이 없습니다", rows.length ? "다른 유형을 선택하거나 검색어를 지워 보세요." : "활동 등록에서 영상, 자료, 과제, 시험, 설문을 만들 수 있습니다."),
        guide,
      );
      return;
    }
    if (!room.weeks.length) {
      slot.replaceChildren(widgets, partial || "", emptyState("이 과목에는 서버에 주차가 없습니다", "학사 일정에서 주차가 만들어지면 여기에 나옵니다."));
      return;
    }
    const wanted = Number(ctx.state.week) || 1;
    const week = room.weeks.find((w) => w.weekNo === wanted) || room.weeks[0];
    const bar = el("div", { class: "weekbar", role: "group", "aria-label": "주차" }, ...room.weeks.map((w) => el("button", {
      type: "button",
      class: `week-num${w.weekNo === week.weekNo ? " on" : ""}${w.rows.length ? " done" : ""}`,
      "data-act": "week",
      "data-week": String(w.weekNo),
      "aria-label": `${w.weekNo}주차 활동 ${w.rows.length}개`,
      "aria-pressed": w.weekNo === week.weekNo ? "true" : "false",
      text: String(w.weekNo),
    })));
    slot.replaceChildren(
      widgets,
      partial || "",
      bar,
      el("div", { class: "week-block" },
        el("div", { class: "pf-week-head" },
          el("h2", { text: weekHead(week) }),
          go(pf("pf_week_add", { kj: course.id, week: String(week.weekNo) }), "활동 등록", { class: "btn btn-primary" }),
        ),
        week.rows.length
          ? el("div", { class: "pf-activities" }, ...week.rows.map((row) => activityRow(row, course, courseId)))
          : emptyState("이 주차에 등록된 활동이 없습니다", "활동 등록에서 영상, 자료, 과제, 시험, 설문을 만들 수 있습니다."),
      ),
      guide,
    );
  };
  views.set(slot, { courseId, paint });
  paint(roomEntry(courseId));
  queueMicrotask(() => {
    if (!slot.isConnected) return;
    if (watchRoom(slot, courseId, paint)) {
      // 새로 들어온 화면에서는 지난 안내를 지운다.
      for (const key of [...notes.keys()]) if (key.startsWith(`${courseId}:`) && !busy.has(key)) notes.delete(key);
      for (const key of [...armed]) if (key.startsWith(`${courseId}:`)) armed.delete(key);
      paint(roomEntry(courseId));
    }
  });
  return slot;
}

function viewOf(node) {
  const slot = node.closest("[data-room]");
  return slot ? { slot, ...(views.get(slot) || {}) } : null;
}

function repaint(view) {
  if (view && view.paint && view.slot.isConnected) view.paint(roomEntry(view.courseId));
}

function toast(api, text) {
  api.getState().toast = text;
  api.paintToast();
}

async function settle(view, courseId, row) {
  forgetRow(courseId, row);
  await reloadRoom(courseId, view && view.slot, view && view.paint).catch(() => {});
  repaint(view);
}

/** `pf-room-publish`: 공개 여부만 바꾼 전체 수정. */
export async function roomPublish(node, api) {
  const key = node.dataset.key;
  const courseId = Number(node.dataset.course);
  const row = rowsByKey.get(key);
  if (!row || busy.has(key) || node.disabled) return;
  const view = viewOf(node);
  const next = node.dataset.next === "1";
  busy.add(key);
  armed.delete(key);
  notes.delete(key);
  node.disabled = true;
  node.setAttribute("aria-busy", "true");
  node.textContent = "바꾸는 중…";
  try {
    await setPublished(courseId, row, next);
    toast(api, next ? `「${row.title}」을 공개했습니다. 학생 강의실에 보입니다.` : `「${row.title}」을 비공개로 바꿨습니다. 학생 강의실에서 보이지 않습니다.`);
  } catch (error) {
    if (isStale(error)) notes.set(key, { text: "다른 곳에서 이 활동을 먼저 고쳤습니다. 아무것도 바꾸지 않고 최신 내용을 불러왔으니 확인한 뒤 다시 누르세요.", alert: true });
    else if (isGone(error)) toast(api, "이미 삭제되었거나 찾을 수 없는 활동입니다. 목록을 새로 불러왔습니다.");
    else notes.set(key, { text: roomMessage(row.type, error), alert: true });
  } finally {
    busy.delete(key);
    await settle(view, courseId, row);
  }
}

/** `pf-room-delete`: 두 번 누르면 활동을 지운다(삭제 표시, 읽은 `version`·`revision`을 `If-Match`로). */
export async function roomDelete(node, api) {
  const key = node.dataset.key;
  const courseId = Number(node.dataset.course);
  const row = rowsByKey.get(key);
  if (!row || busy.has(key) || node.disabled || !DELETABLE.has(row.type)) return;
  const view = viewOf(node);
  if (!armed.has(key)) {
    armed.add(key);
    const what = {
      lecture_weeks: "이 강의와 영상이 학생과 교수 모두에게서 바로 사라지고, 학습 기록은 남습니다.",
      lecture_material: "이 자료와 첨부가 학생 강의실과 학습실에서 바로 사라지고, 학습 기록은 남습니다.",
      report: "이 과제와 제출물이 학생과 교수 모두에게서 바로 사라지고, 성적부의 항목도 빠집니다. 제출 파일과 점수 기록은 서버에 남습니다.",
      test: "이 시험과 응시가 학생과 교수 모두에게서 바로 사라지고, 성적부의 항목도 빠집니다. 응시와 점수 기록은 서버에 남습니다.",
      survey: "이 설문과 결과가 학생과 교수 모두에게서 바로 사라집니다. 응답 기록은 서버에 남습니다.",
    }[row.type];
    notes.set(key, { text: `한 번 더 누르면 삭제합니다. ${what} 되돌릴 수 없습니다.`, alert: true });
    repaint(view);
    return;
  }
  busy.add(key);
  node.disabled = true;
  node.setAttribute("aria-busy", "true");
  node.textContent = "삭제하는 중…";
  try {
    await removeActivity(courseId, row);
    armed.delete(key);
    notes.delete(key);
    toast(api, `「${row.title}」을 삭제했습니다. 학생 강의실에서도 사라졌습니다.`);
  } catch (error) {
    armed.delete(key);
    if (isStale(error)) notes.set(key, { text: "다른 곳에서 이 활동을 먼저 고쳤습니다. 지우지 않고 최신 내용을 불러왔으니 확인한 뒤 다시 삭제하세요.", alert: true });
    else if (isGone(error)) {
      notes.delete(key);
      toast(api, "이미 삭제된 활동입니다. 목록을 새로 불러왔습니다.");
    } else notes.set(key, { text: roomMessage(row.type, error), alert: true });
  } finally {
    busy.delete(key);
    await settle(view, courseId, row);
  }
}

// ---------- 활동 등록: 유형 고르기 ----------

const CHOICES = [
  { type: "lecture_weeks", screen: "pf_contents", text: "영상을 올려 HLS로 변환하고 출석 인정 시간을 정합니다." },
  { type: "lecture_material", screen: "pf_material_write", text: "PDF를 올려 강의자료로 등록하고 쪽별 요약을 만듭니다." },
  { type: "report", screen: "pf_report_create", text: "제출 기간, 지각 제출, 배점을 정해 과제를 냅니다." },
  { type: "test", screen: "pf_exam_create", text: "응시 기간과 제한 시간, 문항을 정해 시험을 냅니다." },
  { type: "survey", screen: "pf_discuss", text: "문항과 응답 기간을 정해 설문을 만듭니다." },
];

/** `pf_week_add`(서버 과목): 서버가 만들 수 있는 유형만 고르고, 고른 주차를 가지고 그 유형의 등록 화면으로 간다. */
export function serverActivityChooser(ctx, course) {
  const blocked = gate(course, "ASTRA 서버에 로그인하면 이 과목에 활동을 등록할 수 있습니다.");
  if (blocked) return blocked;
  const fromUrl = Number(ctx.params && ctx.params.get("week"));
  const start = Number.isInteger(fromUrl) && fromUrl >= 1 && fromUrl <= MAX_WEEK ? fromUrl : Number(ctx.state.week) || 1;
  const week = el("input", { type: "number", min: "1", max: String(MAX_WEEK), value: String(start), "data-room-week": "", "aria-describedby": "pf-room-week-error" });
  const error = el("p", { class: "err", id: "pf-room-week-error", role: "alert", "data-room-week-error": "" });
  return el("div", { class: "sheet", "data-room-chooser": course.id },
    el("h2", { text: "활동 유형" }),
    el("div", { class: "pf-form" }, field("주차", week), el("div", { class: "span-2" }, error)),
    el("div", { class: "pf-activities" }, ...CHOICES.map((choice) => {
      const meta = ROOM_TYPES[choice.type];
      return el("div", { class: "pf-activity" },
        el("div", { class: "pf-activity-main" },
          el("span", { class: `pill ${meta.chip}`, text: meta.label }),
          el("span", { class: "pf-activity-copy" }, el("b", { text: `${meta.label} 등록` }), el("span", { class: "pf-meta", text: choice.text })),
        ),
        el("div", { class: "pf-activity-actions" },
          btn("등록 화면으로", { "data-act": "pf-week-route", "data-target": choice.screen, "data-kj": course.id, "aria-label": `${meta.label} 등록 화면으로` }, "secondary"),
        ),
      );
    })),
    note("실시간 강의(Zoom), 글, 토론, 오프라인 활동은 ASTRA 서버에 아직 만드는 기능이 없어 이 과목에서는 등록할 수 없습니다."),
  );
}

/** `pf-week-route`: 고른 주차를 강의실 주차로 두고 그 유형의 등록 화면으로 간다. 영상 등록 폼에는 주차를 미리 넣는다. */
export function weekRoute(node, api) {
  const box = node.closest("[data-room-chooser]");
  const input = box && box.querySelector("[data-room-week]");
  const error = box && box.querySelector("[data-room-week-error]");
  const n = Number(input && input.value);
  if (!Number.isInteger(n) || n < 1 || n > MAX_WEEK) {
    if (error) error.textContent = `주차는 1부터 ${MAX_WEEK} 사이의 정수입니다.`;
    if (input) {
      input.setAttribute("aria-invalid", "true");
      input.focus();
    }
    return;
  }
  const kj = node.dataset.kj;
  const target = node.dataset.target;
  const state = api.getState();
  state.week = n;
  api.save();
  if (target === "pf_contents") presetLectureWeek(courseIdFor(kj), n);
  api.navigate(pf(target, { kj, week: String(n) }));
}
