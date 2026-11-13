import { el, icon } from "../../dom.js";
import { go, btn, emptyState, loadingState, errorState } from "../../ui/components.js";
import { courses, courseById, studyMaterials, COURSE_ORDER } from "../../data.js";
import { hrefFor, find } from "../../manifest.js";
import { readQuery, pageSearch } from "../../shared/query.js";
import { listCoursePdfs } from "../../shared/course-pdfs.js";
import { backendStatus, courseIdFor } from "../../shared/backend.js";
import { listRecordings, activeRecording, onRecordingEvent, formatDuration } from "../../shared/course-recordings.js";
import { listFolders, createFolder, placements } from "../../shared/study-folders.js";
import { serverCourseViews } from "../../shared/course-view.js";
import { thumbnailImage } from "../../shared/preview-remote.js";
import { ApiError } from "../../shared/api.js";
import { freshVisit } from "../../shared/remote-cache.js";
import { courseWeeks, lectureProgress, courseKey, lectureRows, weeksOf, lectureMessage, windowText, PROGRESS_STATE, ACTIVITY_PHASE } from "../../shared/lectures-remote.js";
import { serverStudySummary, studyLectureHref } from "./study-server.js";
import { wallFormat } from "../../shared/home-remote.js";

const REFRESH_MS = 30000;
const shortDate = wallFormat("ko-KR", { month: "long", day: "numeric" });

export function studyCourses() {
  if (backendStatus() === "ready") return serverCourseViews().map(({ course }) => course);
  const rank = (id) => (COURSE_ORDER.includes(id) ? COURSE_ORDER.indexOf(id) : COURSE_ORDER.length);
  return courses
    .filter((course) => course.kind === "regular" && course.enrolled && course.term === "2026-2")
    .sort((a, b) => rank(a.id) - rank(b.id));
}

const courseName = (course) => course && !course.missing ? course.cardTitle || course.ko : "과목 정보 없음";
export const folderHref = (courseId, folderId) => hrefFor(find("study_home"), { kj: courseId, f: folderId });
const pdfHref = (record) => hrefFor(find("study_home"), { kj: record.courseId, doc: record.id });
export const recordingHref = (courseId, id) => hrefFor(find("study_lecture"), id ? { kj: courseId, rec: id } : { kj: courseId });
const sampleHref = (item) => hrefFor(find("study_summary"), { m: item.id });
// A course linked on the server (backend `ready`) shows server files only, never the samples.
const serverOwned = (courseId) => backendStatus() === "ready" && Boolean(courseIdFor(courseId));
const samplesOf = (courseId) => (backendStatus() === "ready" ? [] : studyMaterials.filter((item) => item.courseId === courseId));

function dateLabel(value) {
  return shortDate.format(value);
}

const CHANGED = "study-files-changed";
/** Tells open folder views to re-read PDFs and recordings after something was added here. */
export const notifyStudyChanged = () => window.dispatchEvent(new Event(CHANGED));

/** Re-reads browser storage while `root` is on screen: on focus, when the tab becomes visible, every 30 s, and on notifyStudyChanged. */
function keepFresh(root, load) {
  const refresh = () => {
    if (!root.isConnected) return stop();
    if (document.visibilityState === "visible") load();
  };
  const timer = window.setInterval(refresh, REFRESH_MS);
  const off = onRecordingEvent(refresh);
  window.addEventListener("focus", refresh);
  document.addEventListener("visibilitychange", refresh);
  window.addEventListener(CHANGED, refresh);
  function stop() {
    window.clearInterval(timer);
    off();
    window.removeEventListener("focus", refresh);
    window.removeEventListener(CHANGED, refresh);
    document.removeEventListener("visibilitychange", refresh);
  }
  // The caller attaches `root` synchronously; load after that so isConnected is meaningful.
  queueMicrotask(load);
}

function fileCard({ href, kind, badge, title, preview, iconName, meta, label }) {
  return el("a", { class: `study-card file-card kind-${kind}`, href, "data-go": href, "aria-label": label || null },
    el("span", { class: "sf-thumb fc-thumb", "aria-hidden": "true" },
      badge ? el("span", { class: "fc-badge", text: badge }) : null,
      preview
        ? el("span", { class: "fc-preview", text: preview })
        : el("span", { class: "sf-icon" }, icon(iconName)),
    ),
    el("b", { class: "sf-name", text: title, title }),
    el("span", { class: "sf-meta" }, ...meta.filter(Boolean).map((text, i) => el("span", { class: i ? null : "sf-count", text }))),
  );
}

export function sampleCard(item, showCourse) {
  const course = courseById(item.courseId);
  return fileCard({
    href: sampleHref(item), kind: "sample", badge: "샘플", title: item.title, preview: item.sentence,
    meta: [`요약 ${item.points.length}줄`, showCourse ? courseName(course) : "샘플 자료"],
  });
}

export function pdfCard(record, showCourse) {
  const ready = record.status !== "no-text";
  const preview = ready ? record.overview.slice(0, 2).map((point) => point.text.replace(/\s+/g, " ")).join(" ") : "";
  // Server records have no page count until the document is opened and read in this tab.
  const detail = record.pageCount != null ? `${record.pageCount}쪽 · ${dateLabel(record.uploadedAt)}` : dateLabel(record.uploadedAt);
  const card = fileCard({
    href: pdfHref(record), kind: "pdf", badge: "PDF", title: record.title, preview, iconName: "file",
    meta: [record.remote ? "ASTRA 서버 자료" : ready ? "개요 준비됨" : "텍스트 없음", showCourse ? courseName(courseById(record.courseId)) : detail],
  });
  // Server PDFs: the first-page thumbnail the backend made while converting (removed when there is none).
  if (record.remote) card.querySelector(".sf-thumb")?.append(thumbnailImage(record.backendCourseId, record.fileId, "fc-thumb-img"));
  return card;
}

export function recordingCard(record, showCourse) {
  return fileCard({
    href: recordingHref(record.courseId, record.id), kind: "rec", badge: record.source === "audio" ? "오디오 파일" : record.source === "tab" ? "탭 녹음" : "마이크 녹음",
    title: record.title, iconName: "play",
    meta: [formatDuration(record.durationMs), showCourse ? courseName(courseById(record.courseId)) : dateLabel(record.createdAt)],
  });
}

function liveRecordingCard(course) {
  const live = activeRecording();
  if (!live || live.courseId !== course.id) return null;
  return fileCard({
    href: recordingHref(course.id), kind: "live", title: live.paused ? "녹음 일시정지" : "녹음 중", iconName: "mic",
    meta: [formatDuration(live.elapsedMs), "눌러서 이어 보기"],
  });
}

function lectureProgressText(items) {
  if (!items.length) return "학습 기록 없음";
  const met = items.filter((item) => item.progress && item.progress.status === "met").length;
  if (met === items.length) return PROGRESS_STATE.met;
  const percent = Math.round(items.reduce((sum, item) => sum + (Number(item.progress && item.progress.progressPercent) || 0), 0) / items.length);
  return `진도 ${percent}% · 인정 ${met}/${items.length}`;
}

function lectureCard(activity, kj, progress) {
  const mine = [];
  for (const week of Array.isArray(progress) ? progress : []) {
    for (const item of Array.isArray(week.items) ? week.items : []) if (item.activityId === activity.activityId) mine.push(item);
  }
  return fileCard({
    href: studyLectureHref(kj, activity.activityId), kind: "lecture", badge: "영상", title: activity.title, iconName: "play",
    meta: [progress ? lectureProgressText(mine) : null, `${activity.weekNo}주차 · ${windowText(activity)}${activity.phase && activity.phase !== "open" ? ` · ${ACTIVITY_PHASE[activity.phase] || ""}` : ""}`],
    label: `${activity.title} 영상 강의`,
  });
}

/**
 * Server video lectures of a linked course (`GET …/weeks` and the caller's `…/lecture-progress`).
 * Students receive opened activities only, so every row links to its study-room lecture view.
 */
function lectureSection(course) {
  const courseId = courseIdFor(course.id);
  const key = courseKey(courseId);
  const load = () => courseWeeks.load(key, courseId);
  const loadProgress = () => lectureProgress.load(key, courseId);
  const body = el("div", { "aria-live": "polite" });
  const section = el("section", { class: "study-section", "aria-label": "영상 강의" }, el("h2", { text: "영상 강의" }), body);
  const paint = () => {
    const entry = courseWeeks.peek(key);
    const data = entry && entry.data;
    if (!data) {
      if (!entry || !entry.error) body.replaceChildren(loadingState("영상 강의를 불러오는 중입니다"));
      else if (entry.error instanceof ApiError && entry.error.status === 404) body.replaceChildren(el("p", { class: "tiny", text: "이 과목의 영상 강의를 볼 수 없습니다." }));
      else {
        const again = btn("다시 불러오기", {}, "ghost");
        again.addEventListener("click", () => {
          again.disabled = true;
          load();
        });
        body.replaceChildren(errorState("영상 강의를 불러오지 못했습니다", entry.error.detail || lectureMessage(entry.error), again));
      }
      return;
    }
    const rows = lectureRows(weeksOf(data));
    const progress = (lectureProgress.peek(key) || {}).data || null;
    body.replaceChildren(rows.length
      ? el("div", { class: "study-grid" }, ...rows.map((activity) => lectureCard(activity, course.id, progress)))
      : el("p", { class: "tiny", text: "서버에 등록된 영상 강의가 없습니다." }));
  };
  paint();
  queueMicrotask(() => {
    if (!section.isConnected) return;
    courseWeeks.watch(section, key, paint, load);
    lectureProgress.watch(section, key, paint, loadProgress);
    if (freshVisit("study-lectures", key) === "entry" || !courseWeeks.peek(key)) {
      load();
      loadProgress();
    } else if (!lectureProgress.peek(key)) loadProgress();
  });
  return section;
}

function userFolderCard(course, folder, count) {
  const href = folderHref(course.id, folder.id);
  return el("a", { class: "study-folder user-folder", href, "data-go": href, "aria-label": `${folder.name} 폴더` },
    el("span", { class: "sf-thumb", "aria-hidden": "true" },
      el("span", { class: "sf-icon" }, icon("folder")),
      el("span", { class: "sf-open" }, icon("chev")),
    ),
    el("b", { class: "sf-name", text: folder.name, title: folder.name }),
    el("span", { class: "sf-meta" }, el("span", { class: "sf-count", text: count ? `${count}개` : "비어 있음" })),
  );
}

/** Card with a name field for a folder being created; Enter or leaving the field saves, Escape cancels. */
function folderNameCard(onDone) {
  const input = el("input", { type: "text", class: "sf-name-input", maxlength: "60", placeholder: "폴더 이름", "aria-label": "새 폴더 이름" });
  let finished = false;
  const finish = (save) => {
    if (finished) return;
    finished = true;
    onDone(save ? input.value : null);
  };
  input.addEventListener("keydown", (event) => {
    if (event.key === "Enter") finish(true);
    else if (event.key === "Escape") finish(false);
  });
  input.addEventListener("blur", () => finish(Boolean(input.value.trim())));
  const card = el("div", { class: "study-folder user-folder naming" },
    el("span", { class: "sf-thumb", "aria-hidden": "true" }, el("span", { class: "sf-icon" }, icon("folder"))),
    input,
  );
  queueMicrotask(() => input.focus());
  return card;
}

/**
 * One course folder or one user folder inside it: the "새로 만들기" card, folders (course root only),
 * PDFs, recordings, and samples. `makeCreator({ say, nameFolder })` returns the "새로 만들기" node.
 */
export function courseFiles(course, folder, makeCreator) {
  const folderId = folder ? folder.id : "";
  const samples = folder ? [] : samplesOf(course.id);
  const status = el("p", { class: "course-study__count", role: "status", "aria-live": "polite", text: "PDF와 녹음을 불러오는 중입니다" });
  const notice = el("p", { class: "course-study__notice", role: "status", "aria-live": "polite", hidden: true });
  const grid = el("div", { class: "study-grid" });
  const root = el("div", { class: "course-study__files", "aria-busy": "true" }, status, notice, grid, !folder && serverOwned(course.id) ? lectureSection(course) : null);
  const say = (text, isError = false) => {
    notice.textContent = text;
    notice.classList.toggle("is-error", isError);
    notice.hidden = !text;
  };
  let data = { pdfs: [], recordings: [] };
  let naming = false;
  let signature = "";
  const creator = makeCreator({
    say,
    nameFolder: () => {
      naming = true;
      draw();
    },
  });

  function draw() {
    const placed = placements();
    const here = (id) => (placed[id] || "") === folderId;
    const pdfs = data.pdfs.filter((record) => here(record.id));
    const recordings = data.recordings.filter((record) => here(record.id));
    const folders = folder ? [] : listFolders(course.id);
    const countIn = (id) => [...data.pdfs, ...data.recordings].filter((record) => placed[record.id] === id).length;
    const next = [
      naming, folders.map((item) => item.id + countIn(item.id)),
      pdfs.map((r) => r.id + r.uploadedAt), recordings.map((r) => r.id),
      (() => { const live = activeRecording(); return live && live.courseId === course.id ? `live${live.paused}` : ""; })(),
    ].join("|");
    status.textContent = [folders.length && `폴더 ${folders.length}개`, `PDF ${pdfs.length}개`, `녹음 ${recordings.length}개`].filter(Boolean).join(" · ");
    if (next === signature) return;
    signature = next;
    grid.replaceChildren(...[
      creator,
      liveRecordingCard(course),
      naming ? folderNameCard((name) => {
        naming = false;
        if (name !== null) {
          try {
            createFolder(course.id, name);
            say(`"${name.trim()}" 폴더를 만들었습니다.`);
          } catch (error) {
            say(error.message, true);
          }
        }
        draw();
      }) : null,
      ...folders.map((item) => userFolderCard(course, item, countIn(item.id))),
      ...pdfs.map((record) => pdfCard(record, false)),
      ...recordings.map((record) => recordingCard(record, false)),
      ...samples.map((item) => sampleCard(item, false)),
    ].filter(Boolean));
  }

  keepFresh(root, () => {
    Promise.all([listCoursePdfs(course.id), listRecordings(course.id)]).then(([pdfs, recordings]) => {
      if (!root.isConnected) return;
      data = { pdfs, recordings };
      root.removeAttribute("aria-busy");
      draw();
    }).catch(() => {
      if (!root.isConnected) return;
      // The area is no longer loading, so screen readers announce the failure text below.
      root.removeAttribute("aria-busy");
      status.textContent = backendStatus() === "ready"
        ? "ASTRA 서버에서 PDF 목록을 받지 못했거나 이 브라우저의 녹음을 읽지 못했습니다. 잠시 후 다시 시도해 주세요."
        : "이 브라우저의 저장소에서 PDF와 녹음을 읽지 못했습니다. 사이트 데이터 접근 설정을 확인해 주세요.";
    });
  });
  return root;
}

function folderCard(course) {
  const samples = samplesOf(course.id).length;
  const count = el("span", { class: "sf-count", text: samples ? `샘플 ${samples}` : "비어 있음" });
  const href = folderHref(course.id);
  const card = el("a", { class: "study-folder", href, "data-go": href, "aria-label": `${courseName(course)} 폴더` },
    el("span", { class: "sf-thumb", "aria-hidden": "true" },
      el("span", { class: "sf-icon" }, icon("notebook")),
      el("span", { class: "sf-open" }, icon("chev")),
    ),
    el("b", { class: "sf-name", text: courseName(course) }),
    el("span", { class: "sf-meta" }, count, el("span", { text: course.code })),
  );
  Promise.all([listCoursePdfs(course.id), listRecordings(course.id)]).then(([pdfs, recordings]) => {
    const parts = [pdfs.length && `PDF ${pdfs.length}`, recordings.length && `녹음 ${recordings.length}`, samples && `샘플 ${samples}`].filter(Boolean);
    count.textContent = parts.length ? parts.join(" · ") : "비어 있음";
  }).catch(() => {});
  return card;
}

function matches(q, ...parts) {
  return parts.join(" ").toLowerCase().includes(q);
}

function searchResults(q) {
  const list = studyCourses();
  const samples = backendStatus() === "ready" ? [] : studyMaterials.filter((item) => {
    const course = courseById(item.courseId);
    return matches(q, item.title, course.ko, course.en, course.cardTitle || "");
  });
  const grid = el("div", { class: "study-grid" }, ...samples.map((item) => sampleCard(item, true)));
  const root = el("div", { "aria-busy": "true" }, grid);
  Promise.all(list.map((course) => Promise.all([listCoursePdfs(course.id), listRecordings(course.id)]).then(([pdfs, recordings]) => {
    const hit = (title) => matches(q, title, course.ko, course.en, course.cardTitle || "");
    return [...pdfs.filter((r) => hit(r.title)).map((r) => pdfCard(r, true)), ...recordings.filter((r) => hit(r.title)).map((r) => recordingCard(r, true))];
  }))).catch(() => []).then((found) => {
    if (!root.isConnected) return;
    grid.prepend(...found.flat());
    root.removeAttribute("aria-busy");
    if (!grid.children.length) root.replaceChildren(emptyState("검색 결과가 없습니다", "자료명이나 과목명을 바꿔 보세요."));
  });
  return root;
}

export function studyRoot(ctx) {
  const scope = "home:study";
  const q = readQuery(ctx.state, scope).trim().toLowerCase();
  return el("section", { class: "study" },
    el("div", { class: "page-head" },
      el("h1", { text: "학습실" }),
      pageSearch(ctx, scope, "학습실 검색", "자료명 또는 과목명"),
    ),
    q ? searchResults(q) : el("div", { class: "study-grid" }, ...studyCourses().map(folderCard)),
  );
}

export function sampleSummary(ctx) {
  // Backend `ready`/`anon`: the server material summary or a refusal (study-server.js); `off` keeps the sample.
  const server = serverStudySummary(ctx, () => sampleSummary(ctx));
  if (server) return server;
  const id = (ctx.params && ctx.params.get("m")) || "sm-db";
  const item = studyMaterials.find((row) => row.id === id);
  if (!item) return emptyState("자료를 찾을 수 없습니다", "학습실에서 자료를 다시 선택해 주세요.");
  const course = courseById(item.courseId);
  return el("section", { class: "study" },
    el("p", { class: "term" }, go(hrefFor(find("study_home")), "학습실"), " · ", go(folderHref(course.id), courseName(course))),
    el("article", { class: "summary-doc" },
      el("div", { class: "sum-h" },
        icon("book"),
        el("div", {},
          el("strong", { text: "자료 요약" }),
          el("span", { class: "tiny", text: `${courseName(course)} · ${item.title}` }),
        ),
      ),
      el("p", { text: item.sentence }),
      el("ol", { class: "key-points" }, ...item.points.map((point) => el("li", { text: point }))),
      el("p", { class: "tiny", text: "샘플 자료입니다. 실제 PDF를 요약한 결과가 아닙니다." }),
    ),
  );
}
