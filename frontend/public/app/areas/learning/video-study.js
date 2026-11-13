import { el } from "../../dom.js";
import { btn } from "../../ui/components.js";
import { listCoursePdfs, ensureCoursePdfText } from "../../shared/course-pdfs.js";
import { createCoursePdfViewer } from "../../shared/document-viewer.js";
import { mediaForActivity } from "../../media.js";
import { togglePlayerFullscreen } from "../../shared/player-controls.js";

// Demo jobs are keyed by professor-published activity, not a student upload.
const sessions = new Map();
const DEMO = [
  { at: 0, text: "이번 강의에서는 핵심 개념을 정의하고, 예제를 통해 적용 과정을 살펴봅니다." },
  { at: 18, text: "문제를 풀기 전에 입력 조건과 가정을 먼저 확인해야 합니다." },
  { at: 42, text: "같은 방법을 다른 사례에 적용하고 결과의 차이를 비교해 보세요." },
];

// `{ server: true }`: a backend lecture. Only the real linked PDFs of the course are shown; the
// demo transcript and summary are sample text and would pass for server data.
export function videoStudy(course, activity, player, seekTo, { server = false } = {}) {
  const intro = server ? "교수님이 등록한 강의 영상 · 오른쪽에서 이 과목의 PDF 자료를 함께 볼 수 있습니다." : "교수님이 등록한 강의 영상 · 전사·요약은 외부 API 없는 목업입니다.";
  const root = el("section", { class: "video-study", "aria-label": "강의 영상 학습모드", "data-item-open": activity.id });
  const message = el("p", { class: "vs-note", role: "status", text: intro });
  const stage = el("div", { class: "vs-stage" });
  root.append(message, stage);
  let docs = [];
  let tab = server ? "materials" : sessions.get(activity.id)?.tab || "summary";
  let selectedDoc = "";
  let active;
  let video;
  let side;
  let unsubscribe;

  function renderSide() {
    if (!side || !active) return;
    const ready = server || active.phase === "done";
    const tabs = el("div", { class: "vs-tabs", role: "tablist", "aria-label": "학습 정보" });
    for (const [key, label] of server ? [["materials", "연결된 PDF"]] : [["summary", "영상 요약"], ["transcript", "전사"], ["materials", "연결된 PDF"]]) {
      const button = btn(label, { role: "tab", id: `vs-${course.id}-${key}`, "aria-controls": `vs-${course.id}-panel`, tabindex: tab === key ? "0" : "-1", "aria-selected": tab === key ? "true" : "false", class: `vs-tab${tab === key ? " on" : ""}` });
      button.addEventListener("click", () => { tab = key; active.tab = key; renderSide(); side.querySelector('[aria-selected="true"]').focus(); });
      button.addEventListener("keydown", (event) => {
        if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
        event.preventDefault();
        const buttons = [...tabs.children];
        const index = buttons.indexOf(button);
        const next = event.key === "Home" ? 0 : event.key === "End" ? buttons.length - 1 : (index + (event.key === "ArrowRight" ? 1 : -1) + buttons.length) % buttons.length;
        buttons[next].click();
      });
      tabs.append(button);
    }
    const body = el("div", { class: "vs-side-body", id: `vs-${course.id}-panel`, role: "tabpanel", "aria-labelledby": `vs-${course.id}-${tab}` });
    if (tab === "materials") {
      if (!docs.length) body.append(el("p", { class: "vs-note", text: "이 과목에 연결된 PDF가 없습니다. 교수 화면에서 등록한 자료가 여기에 표시됩니다." }));
      else {
        const select = el("select", { "aria-label": "연결 자료 선택" }, ...docs.map(doc => el("option", { value: doc.id, text: doc.title })));
        if (!docs.some(doc => doc.id === selectedDoc)) selectedDoc = docs[0].id;
        select.value = selectedDoc;
        select.addEventListener("change", () => { selectedDoc = select.value; renderSide(); });
        const doc = docs.find(item => item.id === selectedDoc);
        if (doc.remote && doc.pageCount == null) {
          // Server PDFs are read in this tab on first use, then the panel is drawn again.
          body.append(select, el("p", { class: "vs-note", role: "status", text: "PDF 원문을 읽는 중입니다." }));
          ensureCoursePdfText(doc).then(() => { if (root.isConnected) renderSide(); }, () => {
            if (doc.pageCount == null) Object.assign(doc, { pageCount: 0, status: "no-text" });
            if (root.isConnected) renderSide();
          });
        } else body.append(
          select,
          createCoursePdfViewer(doc, { compact: true }),
          el("p", { class: "vs-note", text: "자료 개요 · PDF 원문 발췌 (AI 요약 아님)" }),
          doc.overview.length
            ? el("ul", { class: "vs-points" }, ...doc.overview.map(point => el("li", {}, point.text, el("small", { text: `${point.page}쪽` }))))
            : el("p", { class: "vs-note", text: "텍스트를 추출할 수 없는 자료입니다. 뷰어에서 원본 쪽을 확인하세요." }),
        );
      }
    } else if (!ready) {
      body.append(el("h3", { text: active.phase === "transcribing" ? "백그라운드에서 전사 중" : "핵심 내용을 정리하는 중" }), el("progress", { max: "100", value: active.phase === "transcribing" ? "35" : "75", "aria-label": "데모 처리 진행률" }), el("p", { class: "vs-note", text: "영상을 재생하거나 연결 자료를 보면서 기다릴 수 있습니다. 화면을 이동해도 데모 처리는 계속됩니다." }));
    } else if (tab === "transcript") {
      const cues = (mediaForActivity(activity).cues || []).map((cue) => ({ at: cue.start, text: cue.text }));
      const lines = cues.length ? cues : DEMO;
      body.append(el("p", { class: "vs-note", text: cues.length ? "자막과 같은 문장입니다. 실제 음성 분석은 하지 않습니다." : "아래는 강의 음성과 무관한 목업 전사입니다." }), ...lines.map(line => {
        const seek = btn(`${String(Math.floor(line.at / 60)).padStart(2, "0")}:${String(line.at % 60).padStart(2, "0")}`, { class: "vs-time", "aria-label": `${line.at}초로 이동` });
        seek.addEventListener("click", () => seekTo(line.at));
        return el("div", { class: "vs-transcript" }, seek, el("p", { text: line.text }));
      }));
    } else {
      body.append(el("h3", { text: "핵심 요약" }), el("p", { class: "vs-note", text: "실제 강의 분석 결과가 아닌 목업 요약입니다." }), el("ul", { class: "vs-points" }, ...DEMO.map(line => el("li", { text: line.text }))), el("h3", { text: "학습 체크" }), el("p", { text: "핵심 개념을 자신의 말로 설명하고, 연결 자료의 예제와 비교해 보세요." }));
    }
    side.replaceChildren(el("header", { class: "vs-side-header" }, el("strong", { text: "학습 노트" }), server ? null : el("span", { class: "vs-demo", text: "MOCK" })), tabs, server ? null : el("p", { class: "vs-status", role: "status", "aria-live": "polite", text: ready ? "데모 전사·요약 완료" : active.phase === "transcribing" ? "전사 중 · 데모" : "요약 중 · 데모" }), body);
  }

  function show(session) {
    if (unsubscribe) unsubscribe();
    active = session;
    video = player.querySelector("video") || player;
    const fullscreen = btn("영상 전체화면", { class: "btn btn-secondary" });
    fullscreen.addEventListener("click", async () => {
      const result = await togglePlayerFullscreen(player);
      if (!result.ok && result.reason === "unsupported") {
        message.textContent = "이 브라우저는 영상 전체화면을 지원하지 않습니다.";
        return;
      }
      if (!result.ok) {
        message.textContent = `전체화면을 열지 못했습니다: ${result.reason}`;
        return;
      }
      message.textContent = intro;
    });
    side = el("aside", { class: "vs-side", "aria-label": "영상 및 연결 자료 요약" });
    stage.replaceChildren(el("div", { class: "vs-layout" }, el("div", { class: "vs-main" }, el("div", { class: "vs-mode" }, el("span", { class: "vs-mode-label", text: "학습모드" }), el("span", { text: activity.title })), player, el("div", { class: "vs-player-actions" }, fullscreen), el("p", { class: "vs-note", text: "전체화면에서는 강의 플레이어만 표시됩니다. Esc로 돌아오면 학습 노트를 계속 볼 수 있습니다." })), side));
    renderSide();
    session.listeners.add(renderSide);
    unsubscribe = () => session.listeners.delete(renderSide);
  }

  let session = server ? { phase: "done", tab: "materials", listeners: new Set() } : sessions.get(activity.id);
  if (!session) {
    session = { phase: "transcribing", tab: "summary", listeners: new Set() };
    sessions.set(activity.id, session);
    for (const [delay, phase] of [[2500, "summarizing"], [5500, "done"]]) {
      setTimeout(() => { session.phase = phase; session.listeners.forEach(update => update()); }, delay);
    }
  }
  show(session);
  listCoursePdfs(course.id).then(records => { docs = records.filter(doc => doc.courseId === course.id); if (root.isConnected) renderSide(); }).catch(() => { message.textContent = "연결 자료를 불러오지 못했습니다. 브라우저 저장소 접근 설정을 확인해 주세요."; });
  queueMicrotask(() => {
    if (!root.isConnected) return;
    const observer = new MutationObserver(() => {
      if (!root.isConnected) { unsubscribe?.(); observer.disconnect(); }
    });
    observer.observe(document.body, { childList: true, subtree: true });
  });
  return root;
}
