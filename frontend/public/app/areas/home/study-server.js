// 학습실의 백엔드 화면. `study_summary?doc=srv-<fileId>&kj=`는 서버 자료의 게시된 쪽별 요약과 파일
// 요약, 테크뇽 학습 대화로 들어가는 버튼을 보이고, `study_lecture?kj=&lecture=srv-<activityId>`는
// 영상 강의의 콘텐츠와 본인 진도, 플레이어 링크, 함께 볼 서버 강의자료를 보인다. 연결된 과목에서는
// 샘플 요약을 실제 자료처럼 보이지 않는다. `off`이면 null을 돌려주어 샘플 요약과 녹음 화면이 그대로
// 그려진다. 응답은 메모리 캐시(remote-cache.js)에만 두고 세션이 바뀌면 버린다.
import { el, icon } from "../../dom.js";
import { go, btn, badge, emptyState, loadingState, errorState } from "../../ui/components.js";
import { courses, studyMaterials } from "../../data.js";
import { hrefFor, find } from "../../manifest.js";
import { ApiError } from "../../shared/api.js";
import { backendStatus, backendSettled, connect as connectBackend, courseIdFor } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { createResource, freshVisit } from "../../shared/remote-cache.js";
import { listCoursePdfs } from "../../shared/course-pdfs.js";
import { getPublishedSummary, summaryMessage } from "../../shared/course-pdfs-remote.js";
import { fileOverview, studyMessage } from "../../shared/study-chat-remote.js";
import { summaryView, markdown } from "../../shared/page-summary.js";
import {
  lectureDetails, lectureProgress, detailKey, courseKey, progressOf, lectureIdOf, lectureMessage, studentLectureHref,
  lecturePlayerHref, VIDEO_STATE, PROGRESS_STATE, durationText, windowText,
} from "../../shared/lectures-remote.js";
import { wallFormat } from "../../shared/home-remote.js";

const LOGIN_SUMMARY = "ASTRA 서버에 로그인하면 이 과목의 자료 요약을 볼 수 있습니다.";
const LOGIN_LECTURE = "ASTRA 서버에 로그인하면 이 과목의 영상 강의와 진도를 볼 수 있습니다.";

/** 서버 강의자료 PDF 레코드 목록(`course-pdfs.js listCoursePdfs`). 키는 샘플 과목 ID다. */
const pdfLists = createResource((kj) => listCoursePdfs(kj));
/** 게시된 쪽별 요약. 게시본이 없으면 null. 키는 `${backendCourseId}:${fileId}`. */
const summaries = createResource((courseId, fileId) => getPublishedSummary({ backendCourseId: courseId, fileId }));
/** 파일 요약(`…/summary/overview`). 게시본이 없으면 null. 키는 summaries와 같다. */
const overviews = createResource((courseId, fileId) => fileOverview(courseId, fileId));

const studyHomeHref = () => hrefFor(find("study_home"));
const folderHref = (kj) => hrefFor(find("study_home"), { kj });
const documentHref = (kj, id) => hrefFor(find("study_home"), { kj, doc: id });

/** 서버 자료의 요약 화면. `doc` 이름은 `shared/study-chat-panel.js`가 테크뇽 패널에서 읽는다. */
export const studySummaryHref = (kj, id) => hrefFor(find("study_summary"), { doc: id, kj });

/** 학습실에서 서버 영상 강의 하나를 보는 화면. */
export const studyLectureHref = (kj, activityId) => hrefFor(find("study_lecture"), { kj, lecture: `srv-${activityId}` });

const fileIdOf = (ref) => {
  const match = /^srv-([1-9][0-9]{0,17})$/.exec(String(ref || ""));
  return match ? match[1] : null;
};

const missing = (error) => error instanceof ApiError && error.status === 404;

/** 서버가 보낸 detail이 있으면 그 문장, 없으면 기능별 문구다. */
function detailOf(error, fallback) {
  return error instanceof ApiError && error.detail ? error.detail : fallback(error);
}

const courseName = (course) => course.cardTitle || course.ko;

function crumbs(course) {
  return el("p", { class: "term" }, go(studyHomeHref(), "학습실"), " · ", go(folderHref(course.id), courseName(course)));
}

function retry(load) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    load();
  });
  return button;
}

// replaceChildren()는 null을 "null" 글자로 넣으므로 빈 조각을 거른다.
function fill(node, ...children) {
  node.replaceChildren(...children.filter((child) => child != null && child !== false && child !== ""));
}

/** 첫 연결 확인 전의 서버 주소: 확인이 끝나 `off`이면 목업 화면으로 바꾼다. 다른 상태는 app.js가 다시 그린다. */
function waiting(fallback) {
  const holder = el("section", { class: "study", "aria-busy": "true" }, loadingState("ASTRA 서버에 연결하는 중입니다"));
  queueMicrotask(() => connectBackend().then((next) => {
    if (!holder.isConnected || next.status !== "off") return;
    const node = fallback();
    node.setAttribute("data-area", holder.getAttribute("data-area") || "home");
    holder.replaceWith(node);
  }));
  return holder;
}

// ---------- study_summary ----------

/**
 * `study_summary`의 백엔드 화면, 또는 샘플 요약을 그대로 둘 때 null. `fallback()`은 샘플 요약이며
 * 연결 확인이 `off`로 끝났을 때만 부른다.
 */
export function serverStudySummary(ctx, fallback) {
  const params = ctx.params || new URLSearchParams();
  const fileId = fileIdOf(params.get("doc"));
  const status = backendStatus();
  if (status === "off") return fileId && !backendSettled() ? waiting(fallback) : null;
  if (!fileId) {
    const item = studyMaterials.find((row) => row.id === params.get("m")) || studyMaterials[0];
    const course = courses.find((row) => row.id === item.courseId);
    if (status === "anon") return summaryShell(course, backendPanel({ text: LOGIN_SUMMARY }));
    if (!courseIdFor(item.courseId)) return null;
    // 연결된 과목의 샘플 ID: 샘플 문장을 실제 자료처럼 보이지 않는다.
    return summaryShell(course, emptyState("자료를 찾을 수 없습니다", "이 과목의 자료는 ASTRA 서버에 등록된 강의자료만 볼 수 있습니다. 학습실 폴더에서 자료를 다시 골라 주세요.",
      go(folderHref(course.id), "과목 폴더로", { class: "btn btn-secondary" })));
  }
  const kj = params.get("kj");
  const course = courses.find((row) => row.id === kj);
  if (!course) {
    return el("section", { class: "study" }, el("h1", { text: "자료 요약" }),
      errorState("과목을 찾을 수 없습니다", "학습실에서 과목 폴더를 다시 골라 주세요.", go(studyHomeHref(), "학습실로", { class: "btn btn-secondary" })));
  }
  if (status === "anon") return summaryShell(course, backendPanel({ text: LOGIN_SUMMARY }));
  const courseId = courseIdFor(kj);
  if (!courseId) {
    return summaryShell(course, emptyState("이 과목은 ASTRA 서버에 없습니다", "서버에 등록된 과목의 자료만 요약을 볼 수 있습니다.",
      go(folderHref(kj), "과목 폴더로", { class: "btn btn-secondary" })));
  }
  return serverSummary(ctx, course, courseId, fileId);
}

function summaryShell(course, ...body) {
  return el("section", { class: "study study-summary" },
    course ? crumbs(course) : null,
    el("h1", { text: "자료 요약" }),
    ...body,
  );
}

function serverSummary(ctx, course, courseId, fileId) {
  const kj = course.id;
  const docId = `srv-${fileId}`;
  const key = `${courseId}:${fileId}`;
  const body = el("div", { class: "study-summary__body", "aria-live": "polite" });
  const root = summaryShell(course, body);
  const loadList = () => pdfLists.load(kj, kj);
  const loadSummary = () => summaries.load(key, courseId, fileId);
  const loadOverview = () => overviews.load(key, courseId, fileId);
  const open = Boolean(ctx.state && ctx.state.technyonOpen);

  const paint = () => {
    const list = pdfLists.peek(kj);
    if (!list || (!list.data && !list.error)) return fill(body, loadingState("자료를 불러오는 중입니다"));
    if (!list.data) {
      if (missing(list.error)) return fill(body, notFound(kj));
      return fill(body, errorState("자료를 불러오지 못했습니다", detailOf(list.error, () => "ASTRA 서버에서 자료 목록을 받지 못했습니다. 잠시 후 다시 시도해 주세요."), retry(loadList)));
    }
    const record = list.data.find((item) => item.id === docId);
    if (!record) return fill(body, notFound(kj));
    // 목록이 게시본이 있다고 할 때만 요약을 읽는다. 없으면 묻지 않고 "게시된 요약이 없습니다"다.
    hasSummary = record.summaryPublished === true;
    if (hasSummary && wantSummary) {
      wantSummary = false;
      if (freshSummary || !summaries.peek(key)) loadSummary();
      if (freshSummary || !overviews.peek(key)) loadOverview();
    }
    const summary = record.summaryPublished ? summaries.peek(key) : { data: null };
    const overview = record.summaryPublished ? overviews.peek(key) : { data: null };
    const published = summary && summary.data;
    fill(body,
      el("article", { class: "summary-doc", "aria-label": `${record.title} 자료 요약` },
        el("div", { class: "sum-h" },
          icon("book"),
          el("div", {},
            el("strong", { text: record.title }),
            el("span", { class: "tiny", text: `${courseName(course)} · ${record.fileName}` }),
          ),
        ),
        el("div", { class: "btn-row study-summary__actions" },
          published ? btn(open ? "테크뇽 대화 닫기" : "테크뇽에게 이 자료 묻기", {
            "data-act": "technyon", "aria-expanded": open ? "true" : "false",
          }, "primary") : null,
          go(documentHref(kj, docId), "문서 보기", { class: "btn btn-secondary" }),
        ),
        published ? el("p", { class: "tiny", text: "테크뇽은 교수님이 게시한 이 자료의 요약으로 답합니다. 대화는 ASTRA 서버에 남습니다." }) : null,
      ),
      overviewSection(overview, loadOverview),
      pageSummarySection(summary, loadSummary, courseId, fileId),
    );
  };
  // 새 화면 이동이면 게시본도 새로 읽는다. 읽기는 목록의 `summaryPublished`를 본 paint가 한 번 시작한다.
  let wantSummary = false;
  let freshSummary = false;
  let hasSummary = false;
  paint();
  queueMicrotask(() => {
    if (!root.isConnected) return;
    pdfLists.watch(root, kj, paint, loadList);
    summaries.watch(root, key, paint, () => { if (hasSummary) loadSummary(); });
    overviews.watch(root, key, paint, () => { if (hasSummary) loadOverview(); });
    freshSummary = freshVisit("study-summary", key) === "entry";
    wantSummary = true;
    if (freshSummary || !pdfLists.peek(kj)) loadList();
    else paint();
  });
  return root;
}

function notFound(kj) {
  return emptyState("자료를 찾을 수 없습니다", "삭제되었거나 아직 공개되지 않았거나 볼 수 있는 권한이 없습니다.",
    go(folderHref(kj), "과목 폴더로", { class: "btn btn-secondary" }));
}

// 게시본 없음(null)도 답이다. 데이터 없이 아직 받는 중인 항목만 기다린다.
const pending = (entry) => !entry || (entry.data == null && !entry.error && Boolean(entry.loading));

function overviewSection(entry, load) {
  const body = el("div", { class: "study-summary__overview" });
  if (pending(entry)) fill(body, el("p", { class: "tiny", text: "파일 요약을 불러오는 중입니다." }));
  else if (entry.error && !entry.data) fill(body, el("p", { class: "err", role: "alert", text: detailOf(entry.error, studyMessage) }), retry(load));
  else if (entry.data === null) fill(body, el("p", { class: "tiny", text: "게시된 파일 요약이 없습니다." }));
  else fill(body, markdown(entry.data.markdown || ""));
  return el("section", { class: "study-section", "aria-label": "파일 요약" }, el("h2", { text: "파일 요약" }), body);
}

const publishedFormat = wallFormat("ko-KR", { year: "numeric", month: "long", day: "numeric", hour: "2-digit", minute: "2-digit" });

function pageSummarySection(entry, load, courseId, fileId) {
  const body = el("div", { class: "study-summary__pages" });
  if (pending(entry)) fill(body, loadingState("쪽별 요약을 불러오는 중입니다"));
  else if (entry.error && !entry.data) fill(body, errorState("쪽별 요약을 불러오지 못했습니다", detailOf(entry.error, summaryMessage), retry(load)));
  else if (!entry.data) fill(body, emptyState("게시된 요약이 없습니다", "교수님이 이 자료의 쪽별 요약을 게시하면 여기에서 보고 테크뇽에게 물어볼 수 있습니다."));
  else {
    const published = entry.data;
    const at = publishedFormat.format(published.publishedAt);
    fill(body,
      el("p", { class: "tiny", text: `${at ? `${at} 게시 · ` : ""}교수님이 검토해 게시한 AI 요약입니다. 원문과 함께 확인해 주세요.` }),
      summaryView(published, {
        pageImageBase: published.pageImageBase,
        refreshImages: async () => {
          const fresh = await getPublishedSummary({ backendCourseId: courseId, fileId });
          return fresh ? fresh.pageImageBase : null;
        },
      }),
    );
  }
  return el("section", { class: "study-section", "aria-label": "쪽별 요약" }, el("h2", { text: "쪽별 요약" }), body);
}

// ---------- study_lecture ----------

/**
 * `study_lecture?lecture=srv-<activityId>`의 백엔드 화면, 또는 녹음 화면을 그대로 둘 때 null.
 * `fallback()`은 녹음 화면이며 연결 확인이 `off`로 끝났을 때만 부른다.
 */
export function serverStudyLecture(ctx, fallback) {
  const params = ctx.params || new URLSearchParams();
  const activityId = lectureIdOf(params.get("lecture"));
  if (activityId == null) return null;
  const status = backendStatus();
  if (status === "off") return backendSettled() ? null : waiting(fallback);
  const kj = params.get("kj");
  const course = courses.find((row) => row.id === kj);
  if (!course) {
    return el("section", { class: "study rec" }, el("h1", { text: "영상 강의" }),
      errorState("과목을 찾을 수 없습니다", "학습실에서 과목 폴더를 다시 골라 주세요.", go(studyHomeHref(), "학습실로", { class: "btn btn-secondary" })));
  }
  const shell = (...body) => el("section", { class: "study rec study-lecture" },
    crumbs(course),
    el("h1", {}, icon("play"), el("span", { text: "영상 강의" })),
    ...body,
  );
  if (status === "anon") return shell(backendPanel({ text: LOGIN_LECTURE }));
  const courseId = courseIdFor(kj);
  if (!courseId) {
    return shell(emptyState("이 과목은 ASTRA 서버에 없습니다", "서버에 등록된 과목의 강의만 볼 수 있습니다.",
      go(folderHref(kj), "과목 폴더로", { class: "btn btn-secondary" })));
  }
  const key = detailKey(courseId, activityId);
  const pkey = courseKey(courseId);
  const main = el("div", { class: "study-lecture__main", "aria-live": "polite" });
  const pdfs = el("div", { class: "study-lecture__pdfs" });
  const root = shell(main, el("section", { class: "study-section", "aria-label": "함께 볼 강의자료" }, el("h2", { text: "함께 볼 강의자료" }), pdfs));
  const load = () => lectureDetails.load(key, courseId, activityId);
  const loadProgress = () => lectureProgress.load(pkey, courseId);
  const loadList = () => pdfLists.load(kj, kj);

  const paint = () => {
    const entry = lectureDetails.peek(key);
    const data = entry && entry.data;
    if (!data) {
      if (!entry || !entry.error) fill(main, loadingState("강의를 불러오는 중입니다"));
      else if (missing(entry.error)) {
        fill(main, emptyState("강의를 찾을 수 없습니다", "아직 공개되지 않았거나 삭제되었거나 볼 수 있는 권한이 없습니다.",
          go(folderHref(kj), "과목 폴더로", { class: "btn btn-secondary" })));
      } else fill(main, errorState("강의를 불러오지 못했습니다", detailOf(entry.error, lectureMessage), retry(load)));
    } else if (data.type !== "lecture_weeks") {
      fill(main, emptyState("영상 강의가 아닙니다", "학습실 폴더에서 영상 강의를 다시 골라 주세요.", go(folderHref(kj), "과목 폴더로", { class: "btn btn-secondary" })));
    } else fill(main, lectureBody(data, kj, entry.error, lectureProgress.peek(pkey)));
    paintPdfs(pdfs, pdfLists.peek(kj), kj, loadList);
  };
  paint();
  queueMicrotask(() => {
    if (!root.isConnected) return;
    lectureDetails.watch(root, key, paint, load);
    lectureProgress.watch(root, pkey, paint, loadProgress);
    pdfLists.watch(root, kj, paint, loadList);
    if (freshVisit("study-lecture", key) === "entry") {
      load();
      loadProgress();
      loadList();
    } else {
      if (!lectureDetails.peek(key)) load();
      if (!lectureProgress.peek(pkey)) loadProgress();
      if (!pdfLists.peek(kj)) loadList();
    }
  });
  return root;
}

function progressLine(progress) {
  if (!progress) return "아직 학습 기록이 없습니다.";
  const required = progress.requiredSec == null ? "인정 기준 없음" : `인정 기준 ${durationText(progress.requiredSec)}`;
  return `적립 ${durationText(progress.creditedSec)} / ${required} · ${PROGRESS_STATE[progress.status] || progress.status}`;
}

function progressBar(progress) {
  const percent = progress ? Math.max(0, Math.min(100, Number(progress.progressPercent) || 0)) : 0;
  return el("div", { class: "bar", role: "img", "aria-label": `진도 ${percent}%` }, el("i", { style: `width:${percent}%` }));
}

function lectureBody(data, kj, staleError, progressEntry) {
  const items = Array.isArray(data.lectureItems) ? data.lectureItems : [];
  const progress = progressEntry && progressEntry.data ? progressOf(progressEntry.data, data.activityId) : new Map();
  const progressLoading = !progressEntry || (!progressEntry.data && !progressEntry.error);
  const progressFailed = progressEntry && progressEntry.error && !progressEntry.data;
  const detailHref = studentLectureHref(kj, data.activityId);
  return el("div", { class: "study-lecture__body" },
    el("h2", { class: "rec-title", text: data.title }),
    el("p", { class: "rec-meta tiny", text: `${data.weekNo}주차 · 학습 인정 기간 ${windowText(data)} · 콘텐츠 ${items.length}개` }),
    staleError ? el("p", { class: "err", role: "alert", text: detailOf(staleError, lectureMessage) }) : null,
    progressFailed ? el("p", { class: "err", role: "alert", text: `진도를 불러오지 못했습니다. ${detailOf(progressEntry.error, lectureMessage)}` }) : null,
    items.length
      ? el("div", { class: "srv-lecture-items" }, ...items.map((item) => itemCard(item, kj, data.activityId, progress.get(item.itemId), progressLoading)))
      : emptyState("등록된 콘텐츠가 없습니다", "교수님이 영상을 올리면 여기에서 학습할 수 있습니다."),
    el("div", { class: "btn-row" }, go(detailHref, "강의실 활동 상세", { class: "btn btn-secondary" })),
  );
}

function itemCard(item, kj, activityId, progress, loading) {
  const meta = [item.durationSec != null ? `길이 ${durationText(item.durationSec)}` : null, item.requiredSec != null ? `인정 기준 ${durationText(item.requiredSec)}` : null].filter(Boolean).join(" · ");
  const action = item.state === "ready"
    ? go(lecturePlayerHref(kj, activityId, item.itemId), "학습하기", { class: "btn btn-primary", "aria-label": `${item.title} 학습하기` })
    : el("p", { class: "tiny", text: item.state === "processing" ? "영상을 변환하고 있습니다. 끝나면 학습할 수 있습니다." : "영상을 재생할 수 없습니다. 담당 교수에게 문의하세요." });
  return el("article", { class: "progress-card srv-lecture-item" },
    el("div", { class: "progress-pct", text: progress ? `${progress.progressPercent}%` : "—" }),
    el("div", { class: "srv-lecture-copy" },
      el("b", { text: item.title }),
      el("p", { class: "tiny" }, badge(VIDEO_STATE[item.state] || "재생할 수 없음", item.state === "ready" ? "success" : item.state === "processing" ? "neutral" : "danger"), meta ? ` ${meta}` : ""),
      el("p", { class: "tiny", text: loading ? "진도를 불러오는 중입니다." : progressLine(progress) }),
      progressBar(progress),
      el("div", { class: "btn-row" }, action),
    ),
  );
}

function paintPdfs(node, entry, kj, load) {
  if (!entry || (!entry.data && !entry.error)) return fill(node, el("p", { class: "tiny", text: "강의자료를 불러오는 중입니다." }));
  if (!entry.data) {
    if (missing(entry.error)) return fill(node, el("p", { class: "tiny", text: "이 과목의 강의자료를 볼 수 없습니다." }));
    return fill(node, el("p", { class: "err", role: "alert", text: detailOf(entry.error, () => "강의자료 목록을 받지 못했습니다. 잠시 후 다시 시도해 주세요.") }), retry(load));
  }
  if (!entry.data.length) return fill(node, el("p", { class: "tiny", text: "서버에 등록된 PDF 강의자료가 없습니다." }));
  // 요약 게시본이 있는 자료는 요약 화면으로, 없는 자료는 문서 보기로 간다.
  fill(node, el("ul", { class: "study-lecture__pdf-list" }, ...entry.data.map((record) => el("li", {},
    record.summaryPublished
      ? go(studySummaryHref(kj, record.id), record.title, { "aria-label": `${record.title} 자료 요약` })
      : go(documentHref(kj, record.id), record.title, { "aria-label": `${record.title} 문서 보기` }),
    el("span", { class: "tiny", text: ` · ${record.weekNo}주차 · ${record.fileName}` }),
  ))));
}
