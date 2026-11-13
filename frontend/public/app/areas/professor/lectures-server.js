// The professor's server video lectures (콘텐츠관리 `pf_contents` and the video type of 활동 등록
// `pf_week_add`, backend `ready`): register a lecture whose items are videos sent through the
// stream upload (progress and cancel per file), list every lecture from `GET …/lectures` (all pages,
// 100 at a time), watch each item's transcoding state (the list is read again with a growing delay
// while any item is `processing`), and edit (keep/add items) or delete with `If-Match`. The edit
// form reads that one lecture's detail when it opens, because the list has no description and a
// full update without it would clear it. Typed edits and running uploads live in memory per course
// and lecture, so a re-render or a 409 reload keeps them; a session change drops them.
import { el } from "../../dom.js";
import { btn, field, badge, emptyState } from "../../ui/components.js";
import { ApiError, newRequestKey } from "../../shared/api.js";
import { backendStatus, courseIdFor, roleIn, subscribe } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { freshVisit } from "../../shared/remote-cache.js";
import { discardUpload, sizeText } from "../../shared/uploads-remote.js";
import {
  lectureCatalog, lectureDetails, lectureForEdit, lectureItemsOf, detailKey, courseKey, uploadLectureVideo, createLecture,
  updateLecture, deleteLecture, isStaleVersion, lectureMessage, serverTime, localInput, durationText, windowText, VIDEO_STATE,
  VIDEO_EXTENSIONS,
} from "../../shared/lectures-remote.js";
import { go, loadingState, errorState, table, pagination } from "../../ui/components.js";
import { routeFor } from "../../shared/routes.js";
import { koDateTime } from "../../shared/home-remote.js";
import { lectureRoster, rosterKey, ROSTER_PAGE, lectureIdOf, lectureRef, PROGRESS_STATE } from "../../shared/lectures-remote.js";

const MAX_ITEMS = 20;
const MAX_TITLE = 300;
const MAX_REQUIRED_MIN = 240;
const POLL_FIRST_MS = 3000;
const POLL_MAX_MS = 60000;
const ACCEPT = VIDEO_EXTENSIONS.map((ext) => `.${ext}`).join(",");

// "create:<courseId>" | "edit:<activityId>" -> form memory (fields, uploads, notes)
const drafts = new Map();
const openEdits = new Set(); // 수정 칸을 펼친 채로 열 강의(activityId)
const pollers = new Map(); // courseId -> { timer, delay }
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  for (const memo of drafts.values()) for (const entry of memo.uploads) if (entry.controller) entry.controller.abort();
  drafts.clear();
  openEdits.clear();
  for (const poll of pollers.values()) clearTimeout(poll.timer);
  pollers.clear();
});

const missing = (error) => error instanceof ApiError && error.status === 404;
const uncertain = (error) => error instanceof ApiError && [0, 502, 503, 504].includes(error.status) && error.code !== "aborted";
let localIds = 0;

function createMemo(weekNo) {
  return { title: "", weekNo: String(weekNo || 1), description: "", openAt: "", closeAt: "", published: true, uploads: [], note: "", alert: false, busy: false, repaint: null };
}

/** 활동 등록에서 고른 주차를 이 과목의 새 강의 폼에 넣는다. 쓰는 중이거나 올린 영상이 있으면 그대로 둔다. */
export function presetLectureWeek(courseId, weekNo) {
  if (!courseId) return;
  const slotKey = `create:${courseId}`;
  const memo = drafts.get(slotKey);
  if (!memo) drafts.set(slotKey, createMemo(weekNo));
  else if (!memo.busy && !memo.title && !memo.uploads.length) memo.weekNo = String(weekNo);
}

function editMemo(lecture) {
  const items = lectureItemsOf(lecture);
  return {
    open: false,
    title: lecture.title,
    weekNo: String(lecture.weekNo),
    description: lecture.description || "",
    openAt: localInput(lecture.openAt),
    closeAt: localInput(lecture.closeAt),
    published: lecture.published !== false,
    keep: new Map(items.map((item) => [item.itemId, { title: item.title, requiredMin: item.requiredSec ? String(Math.round(item.requiredSec / 60)) : "" }])),
    uploads: [],
    note: "",
    alert: false,
    busy: false,
    armed: false,
    latest: null,
    repaint: null,
  };
}

function stateBadge(state) {
  return badge(VIDEO_STATE[state] || "재생할 수 없음", state === "ready" ? "success" : state === "processing" ? "neutral" : "danger");
}

/** Minutes text → seconds, null for empty; `false` when invalid. */
function requiredSec(text) {
  const raw = String(text || "").trim();
  if (!raw) return null;
  const minutes = Number(raw);
  if (!Number.isInteger(minutes) || minutes < 1 || minutes > MAX_REQUIRED_MIN) return false;
  return minutes * 60;
}

function checkFields(memo) {
  const title = memo.title.trim();
  const weekNo = Number(memo.weekNo);
  if (!title || title.length > MAX_TITLE) return "강의 제목은 1자 이상 300자 이하입니다.";
  if (!Number.isInteger(weekNo) || weekNo < 1 || weekNo > 60) return "주차는 1부터 60 사이입니다.";
  if (memo.description.length > 10000) return "설명은 10,000자 이하입니다.";
  const openAt = serverTime(memo.openAt);
  const closeAt = serverTime(memo.closeAt);
  if (memo.openAt && !openAt) return "공개 시각을 확인하세요.";
  if (memo.closeAt && !closeAt) return "학습 인정 종료 시각을 확인하세요.";
  if (openAt && closeAt && openAt >= closeAt) return "학습 인정 종료는 공개 시각보다 뒤여야 합니다.";
  return null;
}

function addedItems(memo) {
  const out = [];
  for (const entry of memo.uploads) {
    const title = entry.title.trim();
    if (!title || title.length > MAX_TITLE) return `${entry.name}: 콘텐츠 제목은 1자 이상 300자 이하입니다.`;
    const required = requiredSec(entry.requiredMin);
    if (required === false) return `${entry.name}: 인정 시간은 1분부터 ${MAX_REQUIRED_MIN}분 사이입니다.`;
    out.push({ title, uploadId: entry.uploadId, requiredSec: required });
  }
  return out;
}

// ---------- uploads ----------

const UPLOAD_TEXT = { sending: "올리는 중", ready: "올림 완료", failed: "실패", cancelled: "취소함" };

function sendUpload(entry, courseId, memo) {
  entry.state = "sending";
  entry.loaded = 0;
  entry.message = "";
  entry.controller = new AbortController();
  const repaint = () => { if (memo.repaint) memo.repaint(); };
  repaint();
  uploadLectureVideo(entry.file, courseId, {
    idempotencyKey: entry.key,
    signal: entry.controller.signal,
    onProgress: (loaded, total) => {
      entry.loaded = loaded;
      entry.total = total || entry.size;
      if (entry.bar && entry.bar.isConnected) {
        entry.bar.value = entry.total ? Math.round((loaded / entry.total) * 100) : 0;
        entry.bar.textContent = `${entry.bar.value}%`;
      } else repaint();
    },
  }).then((view) => {
    entry.controller = null;
    if (!memo.uploads.includes(entry)) {
      if (view && view.uploadId) discardUpload(view.uploadId);
      return;
    }
    entry.uploadId = view.uploadId;
    entry.state = "ready";
    entry.file = null; // the bytes are on the server now
    repaint();
  }, (error) => {
    entry.controller = null;
    if (error instanceof ApiError && error.code === "aborted") {
      entry.state = "cancelled";
    } else {
      entry.state = "failed";
      entry.message = lectureMessage(error);
      // A lost answer may have stored the upload: the retry reuses the key and gets the same one back.
      if (!uncertain(error)) entry.key = newRequestKey();
    }
    repaint();
  });
}

function uploader(memo, courseId, disabled) {
  const list = el("div", { class: "stack pf-lecture-uploads", "aria-live": "polite" });
  const picker = el("input", { type: "file", multiple: true, accept: ACCEPT, "aria-label": "강의 영상 파일" });
  picker.disabled = disabled;
  picker.addEventListener("change", (event) => {
    event.stopPropagation(); // the app-wide file listener would keep the name in saved sample state
    const picked = [...(picker.files || [])];
    picker.value = "";
    for (const file of picked) {
      const ext = String(file.name || "").split(".").pop().toLowerCase();
      const entry = {
        local: (localIds += 1), name: String(file.name || "video"), size: file.size, file, total: file.size, loaded: 0,
        title: String(file.name || "").replace(/\.[^.]+$/, "").slice(0, MAX_TITLE), requiredMin: "",
        state: "failed", uploadId: null, message: "", key: newRequestKey(), controller: null, bar: null,
      };
      memo.uploads.push(entry);
      if (!VIDEO_EXTENSIONS.includes(ext)) entry.message = "mp4, mov, webm, mkv 영상만 올릴 수 있습니다.";
      else sendUpload(entry, courseId, memo);
    }
    paint();
  });
  function paint() {
    list.replaceChildren(...memo.uploads.map((entry) => {
      const remove = btn(entry.state === "sending" ? "업로드 취소" : "빼기", { "aria-label": `${entry.name} ${entry.state === "sending" ? "업로드 취소" : "빼기"}` }, "ghost");
      remove.disabled = memo.busy;
      remove.addEventListener("click", () => {
        if (entry.controller) entry.controller.abort();
        memo.uploads = memo.uploads.filter((item) => item !== entry);
        if (entry.uploadId) discardUpload(entry.uploadId);
        paint();
      });
      const percent = entry.total ? Math.round((entry.loaded / entry.total) * 100) : 0;
      entry.bar = entry.state === "sending" ? el("progress", { max: "100", value: String(percent), "aria-label": `${entry.name} 업로드 진행률`, text: `${percent}%` }) : null;
      const title = el("input", { maxlength: String(MAX_TITLE), value: entry.title, "aria-label": `${entry.name} 콘텐츠 제목` });
      title.disabled = memo.busy;
      title.addEventListener("input", () => { entry.title = title.value; });
      const required = el("input", { type: "number", min: "1", max: String(MAX_REQUIRED_MIN), value: entry.requiredMin, "aria-label": `${entry.name} 출석 인정 시간(분)`, placeholder: "영상 길이" });
      required.disabled = memo.busy;
      required.addEventListener("input", () => { entry.requiredMin = required.value; });
      const again = entry.state === "failed" && entry.file && !entry.message.startsWith("mp4")
        ? btn("다시 올리기", { "aria-label": `${entry.name} 다시 올리기` }, "secondary")
        : null;
      if (again) {
        again.disabled = memo.busy;
        again.addEventListener("click", () => sendUpload(entry, courseId, memo));
      }
      const stateText = entry.state === "failed" || entry.state === "cancelled" ? [UPLOAD_TEXT[entry.state], entry.message].filter(Boolean).join(" · ") : UPLOAD_TEXT[entry.state];
      return el("div", { class: "pf-lecture-upload" },
        el("p", { class: "pf-sub" }, el("b", { text: entry.name }), ` · ${sizeText(entry.size)} · ${stateText}`),
        entry.bar,
        el("div", { class: "pf-form" }, field("콘텐츠 제목", title), field("출석 인정 시간(분, 비우면 영상 길이)", required)),
        el("div", { class: "pf-actions" }, again, remove),
      );
    }));
  }
  paint();
  return { node: el("div", { class: "stack" }, field("영상 추가 (mp4, mov, webm, mkv · 2GB까지)", picker), list), paint };
}

// ---------- shared field block ----------

function fieldBlock(memo, prefix) {
  const title = el("input", { maxlength: String(MAX_TITLE), value: memo.title, "aria-label": `${prefix}강의 제목` });
  const week = el("input", { type: "number", min: "1", max: "60", value: memo.weekNo, "aria-label": `${prefix}주차` });
  const description = el("textarea", { rows: "3", maxlength: "10000", "aria-label": `${prefix}설명` });
  description.value = memo.description;
  const openAt = el("input", { type: "datetime-local", value: memo.openAt, "aria-label": `${prefix}공개 시각` });
  const closeAt = el("input", { type: "datetime-local", value: memo.closeAt, "aria-label": `${prefix}학습 인정 종료` });
  const published = el("input", { type: "checkbox" });
  published.checked = memo.published;
  title.addEventListener("input", () => { memo.title = title.value; });
  week.addEventListener("input", () => { memo.weekNo = week.value; });
  description.addEventListener("input", () => { memo.description = description.value; });
  openAt.addEventListener("input", () => { memo.openAt = openAt.value; });
  closeAt.addEventListener("input", () => { memo.closeAt = closeAt.value; });
  published.addEventListener("change", () => { memo.published = published.checked; });
  const controls = [title, week, description, openAt, closeAt, published];
  controls.forEach((control) => { control.disabled = memo.busy; });
  return {
    controls,
    nodes: [
      el("div", { class: "span-2" }, field("강의 제목", title)),
      field("주차", week),
      el("label", { class: "choice pf-check" }, published, el("span", { text: "학생에게 공개" })),
      field("공개 시각 (비우면 바로)", openAt),
      field("학습 인정 종료 (비우면 제한 없음)", closeAt),
      el("div", { class: "span-2" }, field("설명", description)),
    ],
  };
}

function statusLine(memo) {
  const node = el("p", { class: memo.alert ? "err" : "pf-sub", role: memo.alert ? "alert" : "status", "aria-live": "polite", text: memo.note });
  return node;
}

/** True when the course's video lectures live on the server (`anon` shows the login strip instead of the sample form). */
export function serverVideoCourse(course) {
  const status = backendStatus();
  return Boolean(course) && (status === "anon" || (status === "ready" && Boolean(courseIdFor(course.id))));
}

// ---------- create ----------

/** The registration sheet for course `course` (also the video panel of 활동 등록). */
export function serverLectureCreate(course, { onCreated, weekNo } = {}) {
  const courseId = courseIdFor(course.id);
  if (backendStatus() === "anon") return backendPanel({ text: "ASTRA 서버에 로그인하면 강의 영상을 서버에 올려 등록할 수 있습니다." });
  if (!courseId) return el("p", { class: "pf-sub", text: "이 과목은 ASTRA 서버에 없습니다. 서버에 등록된 과목에서만 강의 영상을 올릴 수 있습니다." });
  if (roleIn(course.id) !== "INSTRUCTOR") return el("p", { class: "pf-sub", text: "이 과목을 담당하는 교수 계정으로 로그인해야 강의 영상을 등록할 수 있습니다." });
  const slotKey = `create:${courseId}`;
  if (!drafts.has(slotKey)) drafts.set(slotKey, createMemo(weekNo));
  const memo = drafts.get(slotKey);
  const sheet = el("div", { class: "sheet pf-lecture-create" });
  const paint = () => {
    const fields = fieldBlock(memo, "새 강의 ");
    const uploads = uploader(memo, courseId, memo.busy);
    const save = btn(memo.busy ? "등록하는 중…" : "ASTRA 서버에 등록", { "data-act": "pf-lecture-create", "aria-busy": memo.busy ? "true" : "false" }, "primary");
    save.disabled = memo.busy;
    sheet.replaceChildren(
      el("h2", { text: "새 영상 강의" }),
      el("p", { class: "pf-sub", text: "영상은 올린 뒤 서버에서 HLS로 변환합니다. 변환이 끝나야 학생이 학습할 수 있고, 변환 상태는 아래 목록에 표시됩니다." }),
      el("div", { class: "pf-form" }, ...fields.nodes, el("div", { class: "span-2" }, uploads.node), el("div", { class: "span-2" }, statusLine(memo)),
        el("div", { class: "span-2" }, el("div", { class: "pf-actions" }, save))),
    );
    memo.repaint = () => { if (sheet.isConnected) uploads.paint(); };
  };
  const say = (text, alert) => {
    memo.note = text || "";
    memo.alert = Boolean(alert);
    paint();
  };
  async function submit(api) {
    if (memo.busy) return;
    const problem = checkFields(memo);
    if (problem) return say(problem, true);
    if (!memo.uploads.length) return say("영상을 하나 이상 올리세요.", true);
    if (memo.uploads.length > MAX_ITEMS) return say(`콘텐츠는 ${MAX_ITEMS}개까지입니다.`, true);
    if (memo.uploads.some((entry) => entry.state === "sending")) return say("업로드가 끝난 뒤 등록하세요.", true);
    if (memo.uploads.some((entry) => entry.state !== "ready")) return say("실패하거나 취소한 영상을 다시 올리거나 빼세요.", true);
    const items = addedItems(memo);
    if (typeof items === "string") return say(items, true);
    memo.busy = true;
    say("ASTRA 서버에 등록하고 있습니다.");
    try {
      const created = await createLecture(courseId, {
        weekNo: Number(memo.weekNo),
        title: memo.title.trim(),
        description: memo.description.trim() ? memo.description : null,
        openAt: serverTime(memo.openAt),
        closeAt: serverTime(memo.closeAt),
        published: memo.published,
        items,
      });
      drafts.set(slotKey, createMemo(memo.weekNo));
      toast(api, "강의를 등록했습니다. 영상 변환이 끝나면 학생이 학습할 수 있습니다.");
      void lectureCatalog.load(courseKey(courseId), courseId);
      if (onCreated) onCreated(created);
      if (sheet.isConnected) {
        const next = serverLectureCreate(course, { onCreated, weekNo });
        sheet.replaceWith(next);
      }
    } catch (error) {
      memo.busy = false;
      say(lectureMessage(error), true);
    }
  }
  controllers.set(sheet, { create: submit });
  paint();
  return sheet;
}

// ---------- list, polling, edit ----------

function processing(lecture) {
  return lectureItemsOf(lecture).some((item) => item.state === "processing");
}

/**
 * While any lecture has a `processing` item, reads the lecture list again (every page, one request for
 * up to 100 lectures) after 3s, 6s, 12s … up to 60s, while `node` is on screen. No per-lecture reads.
 */
function poll(courseId, node, restart) {
  const key = courseKey(courseId);
  let state = pollers.get(courseId);
  const current = lectureCatalog.peek(key);
  const signature = current && Array.isArray(current.data) ? current.data.filter(processing).map((item) => `${item.activityId}:${item.version}`).join(",") : "";
  // New or edited lectures with processing items start over at the short delay.
  if (state && state.signature !== signature) restart = true;
  if (restart && state) {
    clearTimeout(state.timer);
    pollers.delete(courseId);
    state = null;
  }
  if (state) {
    state.node = node; // the list was drawn again; keep polling for the new one
    return;
  }
  const entry = lectureCatalog.peek(key);
  if (!entry || !Array.isArray(entry.data) || !entry.data.some(processing)) return;
  state = { timer: 0, delay: POLL_FIRST_MS, signature, node };
  pollers.set(courseId, state);
  const tick = async () => {
    const latest = lectureCatalog.peek(key);
    const waiting = latest && Array.isArray(latest.data) ? latest.data.filter(processing) : [];
    if (!state.node.isConnected || !waiting.length) {
      pollers.delete(courseId);
      return;
    }
    // The list read repaints the list, which calls poll() again with the new signature.
    await lectureCatalog.load(key, courseId);
    if (pollers.get(courseId) !== state) return;
    state.delay = Math.min(POLL_MAX_MS, state.delay * 2);
    state.timer = setTimeout(tick, state.delay);
  };
  state.timer = setTimeout(tick, state.delay);
}

/** 콘텐츠관리: login strip, the registration sheet and the server lecture list of `course`. `open` 강의는 펼쳐 둔다. */
export function serverLectureManager(course, { weekNo, open } = {}) {
  const status = backendStatus();
  if (status === "anon") return backendPanel({ text: "ASTRA 서버에 로그인하면 강의 영상을 올리고 변환 상태를 볼 수 있습니다." });
  const courseId = courseIdFor(course.id);
  if (!courseId) return emptyState("이 과목은 ASTRA 서버에 없습니다", "서버에 등록된 과목을 강의실에서 고른 뒤 다시 열어 주세요.");
  return el("div", { class: "stack" },
    el("p", { class: "pf-sub", text: `과목: ${course.ko}. 다른 과목은 강의실에서 과목을 바꾼 뒤 열면 됩니다.` }),
    serverLectureCreate(course, { weekNo }),
    serverLectureList(course, { open }),
  );
}

export function serverLectureList(course, { open } = {}) {
  const courseId = courseIdFor(course.id);
  const instructor = roleIn(course.id) === "INSTRUCTOR";
  const key = courseKey(courseId);
  const load = () => lectureCatalog.load(key, courseId);
  const list = el("div", { class: "stack", "aria-live": "polite" });
  const section = el("section", { class: "sheet", "aria-label": "ASTRA 서버의 영상 강의" },
    el("h2", { text: "서버에 등록된 영상 강의" }),
    el("p", { class: "pf-sub", text: "변환 중인 영상은 상태가 바뀔 때까지 자동으로 다시 확인합니다. 수정과 삭제는 마지막으로 불러온 버전을 기준으로 하고, 그 사이 다른 곳에서 고쳤으면 아무것도 바꾸지 않고 최신 내용을 불러옵니다." }),
    list,
  );
  const opened = new Set();
  // 강의실 주별 보기의 `수정`(`?open=<activityId>`)은 그 강의의 수정 칸을 펼친 채로 연다.
  const openId = Number(open);
  if (Number.isInteger(openId) && openId > 0) {
    opened.add(openId);
    const memo = drafts.get(`edit:${openId}`);
    if (memo) memo.open = true;
    else openEdits.add(openId);
  }
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      if (!entry || !entry.error) list.replaceChildren(el("p", { class: "pf-sub", role: "status", text: "서버에 등록된 강의를 불러오는 중입니다." }));
      else {
        const again = btn("다시 불러오기", {}, "ghost");
        again.addEventListener("click", () => {
          again.disabled = true;
          load();
        });
        list.replaceChildren(el("p", { class: "err", role: "alert", text: lectureMessage(entry.error) }), again);
      }
      return;
    }
    const lectures = Array.isArray(data) ? data : [];
    list.replaceChildren(
      entry.error ? el("p", { class: "err", role: "alert", text: lectureMessage(entry.error) }) : "",
      ...(lectures.length ? lectures.map((lecture) => lectureEntry(course, courseId, lecture, instructor, opened, load)) : [el("p", { class: "pf-sub", text: "아직 서버에 등록된 영상 강의가 없습니다." })]),
    );
    poll(courseId, section, false);
  };
  paint(lectureCatalog.peek(key));
  queueMicrotask(() => {
    if (!section.isConnected) return;
    lectureCatalog.watch(section, key, paint, null);
    if (freshVisit("pf-lectures", key) === "entry" || !lectureCatalog.peek(key)) load();
  });
  return section;
}

function lectureEntry(course, courseId, lecture, instructor, opened, reload) {
  const items = lectureItemsOf(lecture);
  const details = el("details", { class: "pf-server-pdf pf-lecture-entry", open: opened.has(lecture.activityId) ? true : null },
    el("summary", {},
      el("b", { text: lecture.title }),
      el("span", { class: "pf-meta", text: ` · ${lecture.weekNo}주차 · 콘텐츠 ${items.length}개${lecture.published ? "" : " · 비공개"}${processing(lecture) ? " · 변환 중" : ""}` }),
    ),
    el("p", { class: "pf-sub", text: `학습 인정 기간: ${windowText(lecture)}` }),
    el("ul", { class: "pf-lecture-items" }, ...items.map((item) => el("li", {},
      stateBadge(item.state), " ",
      el("span", { text: item.title }),
      el("span", { class: "pf-meta", text: [item.durationSec != null ? ` · 길이 ${durationText(item.durationSec)}` : "", item.requiredSec != null ? ` · 인정 ${durationText(item.requiredSec)}` : ""].join("") }),
      item.state === "failed" ? el("span", { class: "pf-meta", text: " · 변환에 실패했습니다. 이 콘텐츠를 빼고 영상을 다시 올리세요." }) : null,
    ))),
  );
  details.addEventListener("toggle", () => {
    if (details.open) opened.add(lecture.activityId);
    else opened.delete(lecture.activityId);
  });
  if (instructor) details.append(editor(course, courseId, lecture, reload));
  return details;
}

function editor(course, courseId, lecture, reload) {
  const id = lecture.activityId;
  const slotKey = `edit:${id}`;
  if (!drafts.has(slotKey)) {
    // 목록 항목에 설명(`description`)까지 있으므로 상세를 따로 읽지 않고 바로 채운다.
    drafts.set(slotKey, { ...editMemo(lecture), version: lecture.version, repaint: null });
    if (openEdits.delete(id)) drafts.get(slotKey).open = true;
  }
  const memo = drafts.get(slotKey);
  const items = lectureItemsOf(lecture);
  // An item the server no longer has cannot be kept.
  for (const itemId of [...memo.keep.keys()]) if (!items.some((item) => item.itemId === itemId)) memo.keep.delete(itemId);
  const panel = el("details", { class: "pf-material-edit pf-lecture-edit", open: memo.open ? true : null });
  const say = (text, alert) => {
    memo.note = text || "";
    memo.alert = Boolean(alert);
    paint();
  };
  panel.addEventListener("toggle", () => {
    memo.open = panel.open;
  });
  // 409·428 뒤 최신 상세가 오면 가장 새로 그린 패널에 그린다.
  memo.paintEditor = () => { if (memo.panelPaint) memo.panelPaint(); };
  const paint = () => {
    memo.panelPaint = paint;
    const fields = fieldBlock(memo, "");
    const keepList = el("div", { class: "stack" }, ...items.map((item) => {
      const kept = memo.keep.get(item.itemId);
      const box = el("input", { type: "checkbox" });
      box.checked = Boolean(kept);
      box.disabled = memo.busy;
      const title = el("input", { maxlength: String(MAX_TITLE), value: kept ? kept.title : item.title, "aria-label": `${item.title} 콘텐츠 제목` });
      const required = el("input", { type: "number", min: "1", max: String(MAX_REQUIRED_MIN), value: kept ? kept.requiredMin : "", placeholder: "영상 길이", "aria-label": `${item.title} 출석 인정 시간(분)` });
      title.disabled = memo.busy || !kept;
      required.disabled = memo.busy || !kept;
      box.addEventListener("change", () => {
        if (box.checked) memo.keep.set(item.itemId, { title: title.value || item.title, requiredMin: required.value });
        else memo.keep.delete(item.itemId);
        title.disabled = !box.checked;
        required.disabled = !box.checked;
      });
      title.addEventListener("input", () => { const k = memo.keep.get(item.itemId); if (k) k.title = title.value; });
      required.addEventListener("input", () => { const k = memo.keep.get(item.itemId); if (k) k.requiredMin = required.value; });
      return el("div", { class: "pf-lecture-upload" },
        el("label", { class: "choice pf-check" }, box, el("span", {}, "유지 · ", stateBadge(item.state), ` ${item.title}`)),
        el("div", { class: "pf-form" }, field("콘텐츠 제목", title), field("출석 인정 시간(분)", required)),
      );
    }));
    const uploads = uploader(memo, courseId, memo.busy);
    const save = btn(memo.busy && !memo.armed ? "저장하는 중…" : "수정 저장", { "data-act": "pf-lecture-save", "aria-busy": memo.busy && !memo.armed ? "true" : "false" }, "primary");
    const remove = btn(memo.armed ? (memo.busy ? "삭제하는 중…" : "한 번 더 누르면 삭제") : "강의 삭제", { "data-act": "pf-lecture-delete", "aria-label": `${lecture.title} 삭제`, "aria-busy": memo.busy && memo.armed ? "true" : "false" }, "danger");
    save.disabled = memo.busy;
    // 409·428 뒤 최신 상세를 받는 동안은 보낼 버전이 아직 낡았으므로 삭제를 막는다.
    remove.disabled = memo.busy || Boolean(memo.latest && memo.latest.pending);
    panel.replaceChildren(
      el("summary", {}, el("b", { text: "강의 수정·삭제" }), el("span", { class: "pf-meta", text: ` · 버전 ${memo.version}` })),
      el("div", { class: "pf-form" },
        ...fields.nodes,
        el("div", { class: "span-2" }, el("p", { class: "pf-sub", text: "콘텐츠 (체크를 풀면 저장할 때 이 강의에서 빠지고 그 영상은 지워집니다. 학습 중인 학생의 학습도 끝납니다)" }), keepList),
        el("div", { class: "span-2" }, uploads.node),
        memo.latest ? el("div", { class: "span-2" }, latestView(memo.latest)) : null,
        el("div", { class: "span-2" }, statusLine(memo)),
        el("div", { class: "span-2" }, el("div", { class: "pf-actions" }, save, remove)),
      ),
    );
    panel.setAttribute("aria-busy", memo.busy ? "true" : "false");
    memo.repaint = () => { if (panel.isConnected) uploads.paint(); };
  };
  // 409·428: keep what was typed, read the server's latest detail, and save against its version next time.
  const reloadKeeping = (message) => {
    memo.latest = { pending: true };
    memo.busy = false;
    memo.armed = false;
    say(message, true);
    reload();
    lectureForEdit(courseId, id, memo.version + 1).then((detail) => {
      if (drafts.get(slotKey) !== memo) return;
      const latestItems = lectureItemsOf(detail);
      memo.version = detail.version;
      memo.latest = { title: detail.title, weekNo: detail.weekNo, version: detail.version, items: latestItems.map((item) => item.title), published: detail.published };
      for (const itemId of [...memo.keep.keys()]) if (!latestItems.some((item) => item.itemId === itemId)) memo.keep.delete(itemId);
    }, () => {
      if (drafts.get(slotKey) === memo) memo.latest = null;
    }).finally(() => memo.paintEditor());
  };
  async function submit(api) {
    if (memo.busy) return;
    memo.armed = false;
    const problem = checkFields(memo);
    if (problem) return say(problem, true);
    if (memo.uploads.some((entry) => entry.state === "sending")) return say("업로드가 끝난 뒤 저장하세요.", true);
    if (memo.uploads.some((entry) => entry.state !== "ready")) return say("실패하거나 취소한 영상을 다시 올리거나 빼세요.", true);
    const keep = [];
    for (const [itemId, kept] of memo.keep) {
      const title = kept.title.trim();
      const required = requiredSec(kept.requiredMin);
      if (!title || title.length > MAX_TITLE) return say("콘텐츠 제목은 1자 이상 300자 이하입니다.", true);
      if (required === false) return say(`인정 시간은 1분부터 ${MAX_REQUIRED_MIN}분 사이입니다.`, true);
      keep.push({ itemId, title, requiredSec: required });
    }
    const add = addedItems(memo);
    if (typeof add === "string") return say(add, true);
    if (!keep.length && !add.length) return say("콘텐츠를 하나 이상 남기거나 추가하세요. 강의를 없애려면 삭제하세요.", true);
    if (keep.length + add.length > MAX_ITEMS) return say(`콘텐츠는 ${MAX_ITEMS}개까지입니다.`, true);
    memo.busy = true;
    say("ASTRA 서버에 저장하고 있습니다.");
    try {
      await updateLecture(courseId, id, memo.version, {
        weekNo: Number(memo.weekNo),
        title: memo.title.trim(),
        description: memo.description.trim() ? memo.description : null,
        openAt: serverTime(memo.openAt),
        closeAt: serverTime(memo.closeAt),
        published: memo.published,
        items: { keep, add },
      });
      drafts.delete(slotKey);
      toast(api, "강의를 수정했습니다");
      reload();
    } catch (error) {
      if (isStaleVersion(error)) reloadKeeping(lectureMessage(error));
      else if (missing(error)) {
        drafts.delete(slotKey);
        toast(api, "이미 삭제된 강의입니다. 목록을 새로 불러왔습니다.");
        reload();
      } else {
        memo.busy = false;
        say(lectureMessage(error), true);
      }
    }
  }
  async function destroy(api) {
    if (memo.busy || (memo.latest && memo.latest.pending)) return;
    if (!memo.armed) {
      memo.armed = true;
      say("삭제하면 이 강의와 영상이 학생과 교수 모두에게서 바로 사라집니다. 학습 기록은 남습니다.", true);
      return;
    }
    memo.busy = true;
    paint();
    try {
      // 저장과 같은 버전: 409·428 뒤에는 최신 상세의 버전이 `memo.version`에 들어 있고, 목록의 `lecture`는 낡았을 수 있다.
      await deleteLecture(courseId, id, memo.version);
      drafts.delete(slotKey);
      toast(api, "강의를 삭제했습니다. 학생 화면에서도 사라졌습니다.");
      reload();
    } catch (error) {
      if (isStaleVersion(error)) reloadKeeping("다른 곳에서 이 강의를 먼저 고쳤습니다. 최신 내용을 불러왔으니 확인한 뒤 다시 삭제하세요.");
      else if (missing(error)) {
        drafts.delete(slotKey);
        toast(api, "이미 삭제된 강의입니다. 목록을 새로 불러왔습니다.");
        reload();
      } else {
        memo.busy = false;
        memo.armed = false;
        say(lectureMessage(error), true);
      }
    }
  }
  controllers.set(panel, { save: submit, remove: destroy });
  paint();
  return panel;
}

function latestView(latest) {
  if (latest.pending) return el("p", { class: "pf-sub", text: "서버의 최신 내용을 불러오는 중입니다." });
  return el("div", { class: "pf-server-pdf" },
    el("p", { class: "pf-sub" }, el("b", { text: "서버의 최신 내용" }), ` · 버전 ${latest.version}`),
    el("p", { class: "pf-sub", text: `${latest.title} · ${latest.weekNo}주차 · ${latest.published ? "공개" : "비공개"}` }),
    el("p", { class: "pf-sub", text: latest.items.length ? `콘텐츠: ${latest.items.join(", ")}` : "콘텐츠 없음" }),
  );
}

const controllers = new WeakMap(); // sheet/panel node -> { create(api) } | { save(api), remove(api) }

function toast(api, text) {
  api.getState().toast = text;
  api.paintToast();
}

/** `pf-lecture-create` (professor/actions.js). */
export function createLectureEntry(node, api) {
  const sheet = node.closest(".pf-lecture-create");
  const controller = sheet && controllers.get(sheet);
  if (controller) void controller.create(api);
}

/** `pf-lecture-save` (professor/actions.js). */
export function saveLectureEdit(node, api) {
  const panel = node.closest(".pf-lecture-edit");
  const controller = panel && controllers.get(panel);
  if (controller) void controller.save(api);
}

/** `pf-lecture-delete` (professor/actions.js). */
export function deleteLectureEntry(node, api) {
  const panel = node.closest(".pf-lecture-edit");
  const controller = panel && controllers.get(panel);
  if (controller) void controller.remove(api);
}

// ---------- 강의 미리보기 (`pf_lecture?n=srv-<activityId>`) ----------

const pfHref = (screenId, extra) => routeFor("professor", "screen", { screenId, ...(extra || {}) });

function plainText(text) {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  return el("div", { class: "pf-prose" }, ...blocks.map((block) => {
    const p = el("p", {});
    block.split("\n").forEach((line, index) => {
      if (index) p.append(el("br", {}));
      p.append(line);
    });
    return p;
  }));
}

function bindDetail(node, resource, scope, key, args, paint) {
  paint(resource.peek(key));
  queueMicrotask(() => {
    if (!node.isConnected) return;
    resource.watch(node, key, paint, () => resource.load(key, ...args));
    resource.visit(scope, key, ...args);
  });
}

function reloadButton(load) {
  const again = btn("다시 불러오기", {}, "ghost");
  again.addEventListener("click", () => {
    again.disabled = true;
    load();
  });
  return again;
}

/**
 * `pf_lecture`(서버 과목): `?n=srv-<activityId>`의 강의 상세(콘텐츠와 영상 상태)와 수강생별 진도.
 * 진도는 `GET …/lecture-progress/students?activityId=&page=&size=`로 이 강의의 열만, 20명씩 서버가 나눈 쪽으로 받는다(`?page=`).
 */
export function serverLectureDetail(ctx, course) {
  if (backendStatus() === "anon") return backendPanel({ text: "ASTRA 서버에 로그인하면 강의 콘텐츠와 수강생 진도를 볼 수 있습니다." });
  const courseId = courseIdFor(course.id);
  if (!courseId) return emptyState("이 과목은 ASTRA 서버에 없습니다", "서버에 등록된 과목의 강의만 볼 수 있습니다.");
  if (roleIn(course.id) !== "INSTRUCTOR") return el("div", { class: "stack" }, backendPanel(), el("p", { class: "pf-sub", text: "이 과목을 담당하는 교수 계정으로 로그인해야 수강생 진도를 볼 수 있습니다." }));
  const activityId = lectureIdOf(ctx.params && ctx.params.get("n"));
  if (!activityId) {
    return el("div", { class: "stack" },
      el("p", { class: "pf-sub", text: "강의실 주별 보기나 아래 목록에서 강의를 고르세요." }),
      serverLectureList(course),
    );
  }
  const page = Math.max(1, Math.floor(Number(ctx.params && ctx.params.get("page"))) || 1);
  return el("div", { class: "stack" }, lectureHead(course, courseId, activityId), rosterSection(course, courseId, activityId, page));
}

function lectureHead(course, courseId, activityId) {
  const key = detailKey(courseId, activityId);
  const load = () => lectureDetails.load(key, courseId, activityId);
  const section = el("section", { class: "sheet", "aria-label": "강의 정보" });
  bindDetail(section, lectureDetails, "pf-lecture-detail", key, [courseId, activityId], (entry) => {
    const data = entry && entry.data;
    if (!data) {
      if (!entry || !entry.error) section.replaceChildren(loadingState("강의를 불러오는 중입니다"));
      else if (missing(entry.error)) section.replaceChildren(emptyState("강의를 찾을 수 없습니다", "이 과목의 강의가 아니거나 삭제되었습니다.", go(pfHref("pf_room_week", { kj: course.id }), "강의실", { class: "btn btn-secondary" })));
      else section.replaceChildren(errorState("강의를 불러오지 못했습니다", lectureMessage(entry.error), reloadButton(load)));
      return;
    }
    if (data.type && data.type !== "lecture_weeks") {
      section.replaceChildren(emptyState("영상 강의가 아닙니다", "강의실 주별 보기에서 다시 고르세요.", go(pfHref("pf_room_week", { kj: course.id }), "강의실", { class: "btn btn-secondary" })));
      return;
    }
    const items = lectureItemsOf(data);
    section.replaceChildren(
      el("h2", { text: data.title }),
      el("p", { class: "pf-sub", text: `${data.weekNo}주차 · ${data.published ? "공개" : "비공개"} · 학습 인정 기간 ${windowText(data)} · 버전 ${data.version}` }),
      data.description ? plainText(data.description) : null,
      el("h3", { text: `콘텐츠 ${items.length}개` }),
      items.length
        ? el("ul", { class: "pf-lecture-items" }, ...items.map((item) => el("li", {},
          stateBadge(item.state), " ",
          el("span", { text: item.title }),
          el("span", { class: "pf-meta", text: [item.durationSec != null ? ` · 길이 ${durationText(item.durationSec)}` : "", item.requiredSec != null ? ` · 인정 ${durationText(item.requiredSec)}` : ""].join("") }),
          item.state === "failed" ? el("span", { class: "pf-meta", text: " · 변환에 실패했습니다. 콘텐츠관리에서 이 콘텐츠를 빼고 다시 올리세요." }) : null,
        )))
        : el("p", { class: "pf-sub", text: "콘텐츠가 없습니다." }),
      el("div", { class: "pf-actions" },
        go(pfHref("pf_contents", { kj: course.id, open: String(activityId) }), "수정·삭제", { class: "btn btn-secondary" }),
        go(pfHref("pf_attend", { kj: course.id }), "출석부", { class: "btn btn-ghost" }),
      ),
    );
  });
  return section;
}

function rosterSection(course, courseId, activityId, page) {
  const key = rosterKey(courseId, activityId, page);
  const load = () => lectureRoster.load(key, courseId, activityId, page);
  const section = el("section", { class: "sheet", "aria-label": "수강생별 진도" });
  const href = (n) => pfHref("pf_lecture", n > 1 ? { kj: course.id, n: lectureRef(activityId), page: String(n) } : { kj: course.id, n: lectureRef(activityId) });
  bindDetail(section, lectureRoster, "pf-lecture-roster", key, [courseId, activityId, page], (entry) => {
    const data = entry && entry.data;
    const head = el("h2", { text: "수강생별 진도" });
    if (!data) {
      if (!entry || !entry.error) section.replaceChildren(head, loadingState("수강생 진도를 불러오는 중입니다"));
      else if (missing(entry.error)) section.replaceChildren(head, emptyState("수강생 진도를 볼 수 없습니다", "이 과목을 담당하는 교수만 볼 수 있고, 영상 강의가 아니면 진도가 없습니다."));
      else section.replaceChildren(head, errorState("수강생 진도를 불러오지 못했습니다", lectureMessage(entry.error), reloadButton(load)));
      return;
    }
    // 서버가 이 강의의 콘텐츠만 열로 준다.
    const columns = (Array.isArray(data.items) ? data.items : []).map((item, index) => ({ item, index }));
    const students = Array.isArray(data.students) ? data.students : [];
    const total = Math.max(0, Number(data.totalStudents) || 0);
    const size = Number(data.size) || ROSTER_PAGE;
    const pages = Math.max(1, Math.ceil(total / size));
    if (!columns.length) {
      section.replaceChildren(head, el("p", { class: "pf-sub", text: "이 강의에는 진도를 셀 콘텐츠가 없습니다." }));
      return;
    }
    if (!total) {
      section.replaceChildren(head, emptyState("수강생이 없습니다", "수강이 확정된 학생이 생기면 진도가 여기에 모입니다."));
      return;
    }
    if (!students.length) {
      section.replaceChildren(head, emptyState("이 쪽에는 학생이 없습니다", `수강생 ${total}명은 ${pages}쪽까지 있습니다.`, go(href(pages), "마지막 쪽", { class: "btn btn-secondary" })));
      return;
    }
    const cellOf = (student, index) => (Array.isArray(student.items) ? student.items[index] : null) || null;
    const met = (student) => columns.filter(({ index }) => { const p = cellOf(student, index); return p && p.status === "met"; }).length;
    const all = students.filter((student) => met(student) === columns.length).length;
    const average = Math.round(students.reduce((sum, student) => sum + columns.reduce((s, { index }) => s + ((cellOf(student, index) || {}).progressPercent || 0), 0) / columns.length, 0) / students.length);
    const first = (page - 1) * size + 1;
    const headers = ["학번", "이름", ...columns.map(({ item }) => item.title), "인정"];
    const rows = students.map((student) => [
      student.studentNo || "—",
      student.name || "이름 없음",
      ...columns.map(({ index }) => {
        const p = cellOf(student, index);
        if (!p) return "—";
        return el("span", {},
          el("b", { text: `${p.progressPercent}%` }), " ",
          badge(PROGRESS_STATE[p.status] || p.status, p.status === "met" ? "success" : p.status === "under_required" ? "danger" : "neutral"),
          el("span", { class: "pf-meta", text: ` · ${durationText(p.creditedSec)}${p.requiredSec != null ? ` / ${durationText(p.requiredSec)}` : ""}${p.attendedAt ? ` · ${koDateTime(p.attendedAt)} 인정` : ""}` }),
        );
      }),
      `${met(student)}/${columns.length}`,
    ]);
    section.replaceChildren(
      head,
      el("div", { class: "stat-row cols-3" },
        el("div", { class: "stat pf-stat" }, el("span", { class: "tiny", text: "수강생" }), el("b", { text: String(total) })),
        el("div", { class: "stat pf-stat" }, el("span", { class: "tiny", text: "이 쪽 모두 인정" }), el("b", { text: `${all}명` })),
        el("div", { class: "stat pf-stat" }, el("span", { class: "tiny", text: "이 쪽 평균 진도" }), el("b", { text: `${average}%` })),
      ),
      el("p", { class: "pf-sub", text: `적립 시간은 서버가 학습 인정 기간 안에서만 셉니다. ${total}명 가운데 ${first}–${first + students.length - 1}번째입니다.` }),
      table(headers, rows, { layout: "scroll" }),
      pagination({ page: Math.min(page, pages), pages, hrefForPage: href }) || "",
    );
  });
  return section;
}
