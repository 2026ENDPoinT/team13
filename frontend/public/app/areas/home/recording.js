import { el, icon } from "../../dom.js";
import { go, btn, errorState, loadingState } from "../../ui/components.js";
import { courseById } from "../../data.js";
import { hrefFor, find } from "../../manifest.js";
import {
  activeRecording, getRecording, importRecording, formatDuration, recordingExtension, onRecordingEvent,
} from "../../shared/course-recordings.js";
import { folderHref } from "./study-files.js";
import { serverStudyLecture } from "./study-server.js";

const SOURCES = [
  { id: "mic", label: "마이크", hint: "음성 메모나 강의실 수업을 녹음해요." },
  { id: "tab", label: "브라우저 탭", hint: "온라인 강의·Zoom 탭을 고르고 ‘탭 오디오도 공유’를 켜 주세요." },
  { id: "audio", label: "오디오 파일 업로드", hint: "이미 녹음해 둔 MP3·M4A 파일을 올려요." },
];
const dateTime = new Intl.DateTimeFormat("ko-KR", { month: "long", day: "numeric", hour: "2-digit", minute: "2-digit" });

/** Last start/stop failure, shown once on the next render of the source choices. */
let lastError = "";
export function setRecordingError(message) {
  lastError = message;
}

/** Locks the source cards while `card` connects or saves, showing `text` in place of its hint; returns the unlock. */
export function lockSources(card, text) {
  const cards = [...card.parentElement.children];
  const hint = card.querySelector(".rec-source-hint");
  const idle = hint.textContent;
  for (const other of cards) other.disabled = true;
  card.setAttribute("aria-busy", "true");
  hint.textContent = text;
  return () => {
    for (const other of cards) other.disabled = false;
    card.removeAttribute("aria-busy");
    hint.textContent = idle;
  };
}

const sourceLabel = (source) => SOURCES.find((item) => item.id === source)?.label || "마이크";

function sourceIcon(source) {
  if (source === "mic") return icon("mic");
  const glyph = el("span", { class: "ico" });
  const paths = source === "tab"
    ? '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M3 9h18M7 6.5h.01M10 6.5h.01"/><path d="m10 12 5 3-5 3Z"/>'
    : '<path d="M12 15V3m-4 4 4-4 4 4M4 14v5a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-5"/>';
  glyph.innerHTML = `<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round" stroke-linecap="round" aria-hidden="true">${paths}</svg>`;
  return glyph;
}

function crumbs(course) {
  return el("p", { class: "term" },
    go(hrefFor(find("study_home")), "학습실"), " · ", go(folderHref(course.id), course.cardTitle || course.ko));
}

function transcriptNote(source) {
  if (source === "audio") {
    return el("div", { class: "rec-transcript-status", role: "status" },
      el("strong", { text: "전사 준비 · 목업" }),
      el("p", { text: "오디오 파일이 저장되어 재생할 수 있습니다. 전사 API는 연결되지 않아 실제 전사 결과는 생성되지 않습니다." }),
    );
  }
  return el("p", { class: "rec-note", text: "전사(글자 변환)는 아직 연결되지 않았습니다. 녹음 파일만 이 브라우저에 저장됩니다." });
}

// MediaRecorder WebM files carry no duration, so the player shows 0:00 and cannot seek.
// Seeking far past the end once makes the browser scan the file and learn the real length.
function playableAudio(url, title) {
  const audio = el("audio", { class: "rec-audio", controls: true, preload: "metadata", src: url, "aria-label": `${title} 재생` });
  audio.addEventListener("loadedmetadata", () => {
    if (Number.isFinite(audio.duration)) return;
    audio.addEventListener("durationchange", () => { audio.currentTime = 0; }, { once: true });
    audio.currentTime = Number.MAX_SAFE_INTEGER;
  }, { once: true });
  return audio;
}

/** Picking a source acts at once: mic and tab start recording, a chosen audio file is stored and opened. */
function setupView(course) {
  const errorMessage = el("p", { class: "err", role: "alert", text: lastError, hidden: !lastError });
  lastError = "";
  const fileInput = el("input", {
    type: "file", accept: "audio/*,.mp3,.m4a,.wav,.ogg,.webm,.aac,.flac",
    hidden: true, "aria-label": "저장할 오디오 파일 선택",
  });
  const cards = SOURCES.map((source) => el("button", {
    type: "button", class: "rec-source-card", "data-source": source.id,
    "data-act": source.id === "audio" ? null : "rec-start", "data-course": course.id,
  },
    el("span", { class: `rec-source-icon rec-source-icon-${source.id}`, "aria-hidden": "true" }, sourceIcon(source.id)),
    el("strong", { text: source.label }),
    el("span", { class: "rec-source-hint", text: source.hint }),
  ));
  const upload = cards.find((card) => card.dataset.source === "audio");
  upload.addEventListener("click", () => fileInput.click());
  fileInput.addEventListener("change", async () => {
    const file = fileInput.files?.[0];
    fileInput.value = "";
    if (!file) return;
    errorMessage.hidden = true;
    const unlock = lockSources(upload, "파일 저장 중…");
    try {
      // The "saved" event in renderRecording opens the stored file.
      await importRecording({ courseId: course.id, file });
    } catch (failure) {
      unlock();
      errorMessage.textContent = failure.message;
      errorMessage.hidden = false;
    }
  });
  return el("div", { class: "rec-panel" },
    el("div", { class: "rec-source-grid" }, ...cards),
    errorMessage,
    el("p", { class: "rec-note", text: "녹음 전 교수님의 허락을 받아 주세요. 오디오는 이 브라우저에만 저장되며, 자동 전사는 연결되지 않았습니다." }),
    fileInput,
  );
}

function liveView(course, live) {
  const clock = el("strong", { class: "rec-clock", text: formatDuration(live.elapsedMs) });
  const root = el("div", { class: `rec-panel rec-live${live.paused ? " paused" : ""}` },
    el("div", { class: "rec-state" },
      el("span", { class: "rec-dot", "aria-hidden": "true" }),
      el("span", { text: `${live.paused ? "일시정지" : "녹음 중"} · ${sourceLabel(live.source)}` }),
    ),
    clock,
    el("div", { class: "btn-row" },
      live.paused
        ? btn("다시 시작", { "data-act": "rec-resume" }, "secondary")
        : btn("일시정지", { "data-act": "rec-pause" }, "secondary"),
      btn("중지하고 저장", { "data-act": "rec-stop", "data-course": course.id }, "primary"),
    ),
    el("p", { class: "rec-note", text: "다른 화면으로 이동해도 녹음은 계속됩니다. 이 탭을 닫으면 녹음이 사라집니다." }),
    transcriptNote(),
  );
  const tick = window.setInterval(() => {
    const now = activeRecording();
    if (!root.isConnected || !now) return window.clearInterval(tick);
    clock.textContent = formatDuration(now.elapsedMs);
  }, 500);
  return root;
}

function playbackView(course, id) {
  const root = el("div", { class: "rec-panel", "aria-busy": "true" }, loadingState("녹음을 불러오는 중입니다"));
  getRecording(id).then((record) => {
    if (!root.isConnected) return;
    root.removeAttribute("aria-busy");
    if (!record || record.courseId !== course.id) {
      root.replaceChildren(errorState("녹음을 찾을 수 없습니다", "삭제되었거나 다른 브라우저에서 만든 녹음입니다.", go(folderHref(course.id), "폴더로", { class: "btn btn-secondary" })));
      return;
    }
    const url = URL.createObjectURL(record.blob);
    const observer = new MutationObserver(() => {
      if (root.isConnected) return;
      observer.disconnect();
      URL.revokeObjectURL(url);
    });
    observer.observe(document.body, { childList: true, subtree: true });
    root.replaceChildren(
      el("h2", { class: "rec-title", text: record.title }),
      el("p", { class: "rec-meta", text: `${sourceLabel(record.source)} · ${formatDuration(record.durationMs)} · ${dateTime.format(new Date(record.createdAt))}` }),
      playableAudio(url, record.title),
      el("div", { class: "btn-row" },
        el("a", { class: "btn btn-secondary", href: url, download: record.fileName || `${record.title.replace(/[\\/:*?"<>|]/g, "-")}.${recordingExtension(record.mimeType)}`, text: "다운로드" }),
        btn("삭제", { "data-act": "rec-delete", "data-id": record.id, "data-course": course.id }, "ghost"),
      ),
      transcriptNote(record.source),
    );
  }).catch(() => {
    if (!root.isConnected) return;
    root.removeAttribute("aria-busy");
    root.replaceChildren(errorState("녹음을 불러오지 못했습니다", "이 브라우저의 저장소에 접근할 수 없습니다. 사이트 데이터 접근 설정을 확인해 주세요."));
  });
  return root;
}

export function renderRecording(ctx) {
  // `lecture=srv-<activityId>`: the server video lecture (study-server.js); recordings stay as they are.
  const server = serverStudyLecture(ctx, () => renderRecording(ctx));
  if (server) return server;
  const params = ctx.params || new URLSearchParams();
  const courseId = params.get("kj");
  const course = courseById(courseId);
  if (!courseId || course.missing || course.id !== courseId) {
    return el("section", { class: "study rec" },
      el("h1", { text: "녹음" }),
      errorState("과목을 찾을 수 없습니다", "학습실에서 과목 폴더를 열고 새 녹음을 누르세요.", go(hrefFor(find("study_home")), "학습실로", { class: "btn btn-secondary" })));
  }
  const id = params.get("rec");
  const live = activeRecording();
  let body;
  if (id) body = playbackView(course, id);
  else if (live && live.courseId !== course.id) {
    const other = courseById(live.courseId);
    body = el("div", { class: "rec-panel" },
      el("p", { text: `${other && !other.missing && other.id === live.courseId ? other.cardTitle || other.ko : "다른 과목"}에서 녹음 중입니다. 한 번에 하나만 녹음할 수 있습니다.` }),
      go(hrefFor(find("study_lecture"), { kj: live.courseId }), "진행 중인 녹음으로", { class: "btn btn-primary" }));
  } else body = live ? liveView(course, live) : setupView(course);
  const root = el("section", { class: "study rec" },
    crumbs(course),
    el("h1", {}, icon("mic"), el("span", { text: id ? "녹음 파일" : "강의 녹음" })),
    body,
  );
  if (!id) {
    // A share ended from the browser bar saves on its own; follow it to the saved file.
    const off = onRecordingEvent((event) => {
      if (!root.isConnected) return off();
      if (event.type === "saved" && event.record.courseId === course.id) {
        off();
        root.append(el("a", { hidden: true, "data-go": hrefFor(find("study_lecture"), { kj: course.id, rec: event.record.id }) }));
        root.lastChild.click();
      } else if (event.type === "error") {
        off();
        setRecordingError(event.error.message);
        root.append(el("a", { hidden: true, "data-go": hrefFor(find("study_lecture"), { kj: course.id }) }));
        root.lastChild.click();
      }
    });
  }
  return root;
}
