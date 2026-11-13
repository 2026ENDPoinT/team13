// Backend online lectures for the student (`?m=lecture_weeks&n=srv-<activityId>` detail and the
// player `player_form.acl?n=srv-<activityId>&item=<itemId>`). The player starts a learning
// session by itself only on a fresh entry to it (otherwise from its button), plays the AES-128 HLS
// stream the server names (Safari natively, other browsers through the vendored hls.js, loaded only
// here), sends heartbeats on the server's interval with an increasing `seq`, and ends the session
// on the 종료 button, on leaving the screen, and with `fetch` keepalive when the page goes away.
// Credited time, progress and the credit window are shown from server responses only; nothing
// about attendance is computed here.
import { el } from "../../dom.js";
import { btn, go, emptyState, loadingState, errorState, badge } from "../../ui/components.js";
import { ApiError } from "../../shared/api.js";
import { backendStatus, backendSettled, connect as connectBackend, courseIdFor, offers, roleIn, subscribe } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { freshVisit } from "../../shared/remote-cache.js";
import { koDateTime } from "../../shared/home-remote.js";
import { courseById } from "../../data.js";
import { videoStudy } from "./video-study.js";
import { ensurePlayerChrome } from "../../shared/player-controls.js";
import {
  lectureDetails, lectureProgress, detailKey, courseKey, progressOf, forgetLecture, lectureMessage, studentLectureHref,
  lecturePlayerHref, startSession, sendHeartbeat, endSession, heartbeatOnUnload, endOnUnload, manifestOf, createChallenge, verifyChallenge,
  developerReauth, reauthHref, reauthReturnTo, onReauthMessage, announceReauth, reauthFailedAtLoad, SESSION_CLOSED,
  closedCodeOf, VIDEO_STATE, PROGRESS_STATE, durationText, windowText,
} from "../../shared/lectures-remote.js";

const LOGIN_TEXT = "ASTRA 서버에 로그인하면 이 과목의 강의를 보고 학습할 수 있습니다.";
const HLS_MODULE = "../../vendor/hlsjs/hls.light.min.mjs";
const RETRY_FIRST_MS = 5000;

const missing = (error) => error instanceof ApiError && error.status === 404;
const listHref = (kj) => `/ilos/cls/st/activity/activity_list_form.acl?kj=${encodeURIComponent(kj)}`;

function retry(reload) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    reload();
  });
  return button;
}

/** Shared gate for the detail and the player: a node to show instead, or null when ready. */
function gate(root, kj, paintOff) {
  const status = backendStatus();
  if (status === "anon") return backendPanel({ text: LOGIN_TEXT });
  if (status === "off") {
    if (!backendSettled()) {
      queueMicrotask(() => connectBackend().then((next) => {
        if (root.isConnected && next.status === "off") paintOff();
      }));
      return loadingState("ASTRA 서버에 연결하는 중입니다");
    }
    return emptyState("활동을 찾을 수 없습니다", "ASTRA 서버에 연결되어 있지 않습니다. 목록에서 다른 활동을 선택해 보세요.");
  }
  if (!courseIdFor(kj)) return emptyState("이 과목은 ASTRA 서버에 없습니다", "서버에 등록된 과목의 강의만 볼 수 있습니다.");
  return null;
}

function prose(text) {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  if (!blocks.length) return null;
  return el("div", { class: "prose" }, ...blocks.map((block) => {
    const p = el("p", {});
    block.split("\n").forEach((line, index) => {
      if (index) p.append(el("br", {}));
      p.append(line);
    });
    return p;
  }));
}

function stateBadge(state) {
  return badge(VIDEO_STATE[state] || "재생할 수 없음", state === "ready" ? "success" : state === "processing" ? "neutral" : "danger");
}

/** "적립 12분 30초 / 인정 기준 40분 · 31%" from a server `ItemProgress`. */
function progressLine(progress) {
  if (!progress) return "아직 학습 기록이 없습니다.";
  const required = progress.requiredSec == null ? "인정 기준 없음" : `인정 기준 ${durationText(progress.requiredSec)}`;
  const met = progress.attendedAt ? ` · ${koDateTime(progress.attendedAt)} 출석 인정` : "";
  return `적립 ${durationText(progress.creditedSec)} / ${required} · ${PROGRESS_STATE[progress.status] || progress.status}${met}`;
}

function progressBar(progress) {
  const percent = progress ? Math.max(0, Math.min(100, Number(progress.progressPercent) || 0)) : 0;
  return el("div", { class: "bar", role: "img", "aria-label": `진도 ${percent}%` }, el("i", { style: `width:${percent}%` }));
}

// ---------- detail ----------

/** The detail screen of `screen.serverLecture` (backend mode). `parts` are learning.js helpers. */
export function remoteLectureDetail(screen, ctx, parts) {
  const kj = screen.course;
  const activityId = screen.serverLecture;
  const main = el("div", { class: "act-main" });
  const root = el("section", { class: "bleed act-layout is-solo is-open", "data-item-open": `srv-${activityId}` }, main);
  const blocked = gate(root, kj, () => main.replaceChildren(emptyState("활동을 찾을 수 없습니다", "ASTRA 서버에 연결되어 있지 않습니다.")));
  if (blocked) {
    main.append(el("div", { class: "act-body" }, parts.typeLine("video"), blocked));
    return root;
  }
  const courseId = courseIdFor(kj);
  const key = detailKey(courseId, activityId);
  const pkey = courseKey(courseId);
  const load = () => lectureDetails.load(key, courseId, activityId);
  const loadProgress = () => lectureProgress.load(pkey, courseId);
  const paint = () => {
    const entry = lectureDetails.peek(key);
    const data = entry && entry.data;
    if (!data) {
      if (!entry || !entry.error) main.replaceChildren(loadingState("강의를 불러오는 중입니다"));
      else if (missing(entry.error)) main.replaceChildren(emptyState("강의를 찾을 수 없습니다", "아직 공개되지 않았거나 삭제되었거나 볼 수 있는 권한이 없습니다.", go(listHref(kj), "학습활동 목록", { class: "btn btn-secondary" })));
      else main.replaceChildren(errorState("강의를 불러오지 못했습니다", lectureMessage(entry.error), retry(load)));
      return;
    }
    if (data.type !== "lecture_weeks") {
      main.replaceChildren(emptyState("영상 활동이 아닙니다", "학습활동 목록에서 다시 선택해 보세요.", go(listHref(kj), "학습활동 목록", { class: "btn btn-secondary" })));
      return;
    }
    main.replaceChildren(detailBody(data, kj, parts, entry.error, lectureProgress.peek(pkey)));
  };
  paint();
  queueMicrotask(() => {
    if (!root.isConnected) return;
    lectureDetails.watch(root, key, paint, load);
    lectureProgress.watch(root, pkey, paint, loadProgress);
    if (freshVisit("lecture-detail", key) === "entry" || !lectureDetails.peek(key)) {
      load();
      loadProgress();
    } else if (!lectureProgress.peek(pkey)) loadProgress();
  });
  return root;
}

function detailBody(data, kj, parts, staleError, progressEntry) {
  const course = courseById(kj);
  const items = Array.isArray(data.lectureItems) ? data.lectureItems : [];
  const instructor = roleIn(kj) === "INSTRUCTOR";
  const progress = progressEntry && progressEntry.data ? progressOf(progressEntry.data, data.activityId) : new Map();
  const progressFailed = progressEntry && progressEntry.error && !progressEntry.data;
  return el("div", { class: "act-body" },
    parts.typeLine("video"),
    el("h1", { text: data.title }),
    el("p", { class: "tiny", text: `${course.ko} · ${data.weekNo}주차 · 콘텐츠 ${items.length}개${data.published ? "" : " · 비공개"}` }),
    staleError ? el("p", { class: "err", role: "alert", text: lectureMessage(staleError) }) : null,
    el("section", { class: "callout" },
      el("h2", { text: "주의사항" }),
      el("ul", {},
        el("li", { text: "출석 시간은 ASTRA 서버가 학습 창이 열려 있는 동안의 서버 시각으로 적립합니다. 배속이나 재생 위치를 옮겨도 시간이 줄지 않습니다." }),
        el("li", { text: "한 번에 한 강의만 학습할 수 있습니다. 다른 곳에서 학습을 시작하면 이 화면의 학습이 끝납니다." }),
        el("li", { text: "학습 인정 기간이 끝난 뒤에도 재생할 수 있지만 출석 시간은 쌓이지 않습니다." }),
        el("li", { text: "학습을 마칠 때는 학습 창의 학습 종료 버튼을 누르세요." }),
      ),
    ),
    parts.metaTable([
      ["학습 인정 기간", windowText(data)],
      ["출석 인정", "콘텐츠마다 인정 기준 시간 이상 적립하면 인정됩니다"],
    ]),
    prose(data.description),
    el("h2", { text: "콘텐츠" }),
    progressFailed ? el("p", { class: "err", role: "alert", text: `진도를 불러오지 못했습니다. ${lectureMessage(progressEntry.error)}` }) : null,
    items.length
      ? el("div", { class: "srv-lecture-items" }, ...items.map((item) => itemCard(item, kj, data.activityId, progress.get(item.itemId), instructor, progressEntry)))
      : el("p", { class: "tiny", text: "등록된 콘텐츠가 없습니다." }),
  );
}

function itemCard(item, kj, activityId, progress, instructor, progressEntry) {
  const meta = [item.durationSec != null ? `길이 ${durationText(item.durationSec)}` : null, item.requiredSec != null ? `인정 기준 ${durationText(item.requiredSec)}` : null].filter(Boolean).join(" · ");
  const action = item.state === "ready"
    ? go(lecturePlayerHref(kj, activityId, item.itemId), instructor ? "미리보기" : "학습하기", { class: "btn btn-primary", "aria-label": `${item.title} ${instructor ? "미리보기" : "학습하기"}` })
    : el("p", { class: "tiny", text: item.state === "processing" ? "영상을 변환하고 있습니다. 끝나면 학습할 수 있습니다." : "영상을 재생할 수 없습니다. 담당 교수에게 문의하세요." });
  const loading = !progressEntry || (!progressEntry.data && !progressEntry.error);
  return el("article", { class: "progress-card srv-lecture-item" },
    el("div", { class: "progress-pct", text: progress ? `${progress.progressPercent}%` : "—" }),
    el("div", { class: "srv-lecture-copy" },
      el("b", { text: item.title }),
      el("p", { class: "tiny" }, stateBadge(item.state), meta ? ` ${meta}` : ""),
      instructor ? el("p", { class: "tiny", text: "담당자 미리보기는 출석 시간을 적립하지 않습니다." })
        : el("p", { class: "tiny", text: loading ? "진도를 불러오는 중입니다." : progressLine(progress) }),
      instructor ? null : progressBar(progress),
      el("div", { class: "btn-row" }, action),
    ),
  );
}

// ---------- player ----------

const PHASE_TEXT = {
  idle: "학습 시작을 누르면 학습을 시작합니다.",
  starting: "학습을 시작하는 중입니다.",
  auth: "학습 인증이 필요합니다.",
  active: "학습 중입니다.",
  ended: "학습을 마쳤습니다.",
  superseded: SESSION_CLOSED.session_superseded.text,
  expired: SESSION_CLOSED.session_expired.text,
  stale: SESSION_CLOSED.session_ended.text,
  withdrawn: SESSION_CLOSED.session_withdrawn.text,
  unavailable: "이 콘텐츠는 지금 학습할 수 없습니다.",
  error: "학습을 시작하지 못했습니다.",
};

// Closed-session code → player phase. `stale` is a session that had already ended elsewhere
// (another tab, the page going away); `ended` is reserved for this screen's own 학습 종료.
const CLOSED_PHASE = { session_superseded: "superseded", session_expired: "expired", session_ended: "stale", session_withdrawn: "withdrawn" };
const phaseOfCode = (code) => CLOSED_PHASE[code] || "stale";
// Phases that offer a new session, with the button text. `withdrawn` offers none: the content is gone.
// `idle` is a player that did not start by itself (see `remoteLecturePlayer`).
const RESTART_LABEL = { idle: "학습 시작", superseded: "이 화면에서 이어 보기", expired: "마지막 위치에서 다시 시작", stale: "다시 시작", ended: "다시 시작", error: "다시 시작" };

let current = null; // the one live player controller
subscribe((next) => {
  if (current && next.status !== "ready") current.leave("unload");
});

function position(video, durationSec) {
  const at = Number.isFinite(video.currentTime) ? Math.floor(video.currentTime) : null;
  if (at == null || at < 0) return null;
  return durationSec != null ? Math.min(at, durationSec) : at;
}

/**
 * One learning session and its media for (course, item). It outlives re-renders: the same
 * <video> element is moved into each new tree, so playback continues; the session ends once the
 * element stays detached (the learner left the screen).
 */
function createController({ kj, courseId, activityId, itemId }) {
  const ctl = {
    key: `${courseId}:${itemId}`,
    kj, courseId, activityId, itemId,
    phase: "idle",
    item: null,
    session: null,
    lastSeq: 0,
    pendingSeq: null,
    inflight: false,
    failures: 0,
    timer: 0,
    progress: null,
    creditable: true,
    creditOpen: null,
    creditState: "authorized",
    creditUntil: null,
    outcome: "",
    notice: "",
    alert: false,
    auth: { needed: false, challengeId: null, redirect: null, busy: false, note: "", alert: false },
    ended: null,
    hls: null,
    manifest: null,
    recovered: false,
    sendPosition: true,
    netWarn: false,
    ending: false,
    views: null,
  };
  const video = el("video", { class: "lecture-video srv-video", controls: true, playsinline: true, preload: "metadata" });
  ctl.video = video;

  const paint = () => { if (ctl.views) paintViews(ctl); };
  ctl.paint = paint;
  const say = (text, alert) => {
    ctl.notice = text || "";
    ctl.alert = Boolean(alert);
    paint();
  };
  const clear = () => {
    clearTimeout(ctl.timer);
    ctl.timer = 0;
  };
  const schedule = (ms) => {
    clear();
    ctl.timer = setTimeout(() => { void beat(); }, ms);
  };
  const interval = () => Math.max(10, Math.min(300, Number(ctl.session && ctl.session.heartbeatIntervalSec) || 60)) * 1000;

  function detachMedia() {
    if (ctl.hls) {
      try { ctl.hls.destroy(); } catch { /* already gone */ }
      ctl.hls = null;
    }
    ctl.manifest = null;
    video.pause();
    video.removeAttribute("src");
    try { video.load(); } catch { /* nothing loaded */ }
  }

  function close(phase, { unload = false } = {}) {
    clear();
    const wasPhase = ctl.phase;
    ctl.phase = phase;
    ctl.auth.needed = false;
    // Withdrawn content must not keep playing; other closes only pause, so a restart resumes here.
    if (phase === "withdrawn" || unload) detachMedia();
    else video.pause();
    // A session changes progress, not the lecture: the detail stays cached, so a repaint after the
    // end still draws this player (with its <video>) instead of a loading screen that detaches it.
    if (wasPhase === "active" || phase === "withdrawn" || phase === "ended") forgetLecture(courseId, null);
    paint();
  }

  async function attach(manifest, at) {
    const start = Number.isFinite(at) && at > 0 ? at : 0;
    if (ctl.manifest === manifest) {
      if (ctl.hls) ctl.hls.startLoad(Math.floor(video.currentTime || start));
      return;
    }
    detachMedia();
    ctl.manifest = manifest;
    ctl.recovered = false;
    const seekOnce = () => {
      if (start > 0.5 && Number.isFinite(video.duration) && start < video.duration - 1) video.currentTime = start;
    };
    const native = typeof video.canPlayType === "function" && video.canPlayType("application/vnd.apple.mpegurl") !== "";
    if (native) {
      video.addEventListener("loadedmetadata", seekOnce, { once: true });
      video.addEventListener("error", () => {
        // A browser that claims native HLS but cannot play this stream falls back to hls.js.
        if (ctl.manifest === manifest && video.readyState === 0 && !ctl.hls) void attachHls(manifest, start, true);
      }, { once: true });
      video.src = manifest;
      return;
    }
    await attachHls(manifest, start, false);
  }

  async function attachHls(manifest, start, fallback) {
    let Hls;
    try {
      ({ default: Hls } = await import(HLS_MODULE));
    } catch {
      say("영상 재생기를 불러오지 못했습니다. 페이지를 새로 불러오세요.", true);
      return;
    }
    if (ctl.manifest !== manifest) return;
    if (!Hls.isSupported()) {
      say("이 브라우저는 강의 영상(HLS) 재생을 지원하지 않습니다. 최신 Chrome, Edge, Safari, Firefox에서 열어 주세요.", true);
      return;
    }
    if (fallback) video.removeAttribute("src");
    // No worker: the CSP allows same-origin workers only, and hls.js would build one from a Blob.
    const hls = new Hls({ enableWorker: false, startPosition: start > 0 ? start : -1, capLevelToPlayerSize: true });
    ctl.hls = hls;
    hls.on(Hls.Events.ERROR, (_event, data) => {
      if (!data || !data.fatal || ctl.hls !== hls) return;
      if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
        hls.recoverMediaError();
        return;
      }
      if (data.type === Hls.ErrorTypes.NETWORK_ERROR && !ctl.recovered && ctl.phase === "active") {
        // Usually an expired video cookie (a long pause, a sleeping laptop): a heartbeat renews it.
        ctl.recovered = true;
        void beat({ now: true }).then(() => { if (ctl.hls === hls && ctl.phase === "active") hls.startLoad(Math.floor(video.currentTime || 0)); });
        return;
      }
      say("영상을 받지 못했습니다. 잠시 후 다시 시작하세요.", true);
      if (ctl.phase === "active") video.pause();
    });
    hls.on(Hls.Events.FRAG_LOADED, () => { ctl.recovered = false; });
    hls.loadSource(manifest);
    hls.attachMedia(video);
  }

  function apply(result) {
    if (result.progress) ctl.progress = result.progress;
    ctl.creditOpen = result.creditOpen;
    ctl.creditState = result.creditState || "authorized";
    ctl.creditUntil = result.creditUntil || null;
    ctl.outcome = result.outcome || "";
    if (ctl.netWarn) {
      ctl.netWarn = false;
      ctl.notice = "";
      ctl.alert = false;
    }
    if (ctl.creditState === "reauth_required" && ctl.creditable) {
      if (!ctl.auth.needed) ctl.auth = { needed: true, challengeId: null, redirect: null, busy: false, note: "", alert: false };
    } else if (ctl.auth.needed) {
      ctl.auth.needed = false;
      ctl.notice = "학습 인증을 확인했습니다. 지금 위치에서 출석 시간 적립을 이어 갑니다.";
      ctl.alert = false;
    }
    paint();
  }

  async function beat({ now = false } = {}) {
    if (ctl.phase !== "active" || !ctl.session) return;
    if (ctl.inflight) return;
    if (!video.isConnected) {
      ctl.leave("unload");
      return;
    }
    const session = ctl.session;
    const seq = ctl.pendingSeq != null ? ctl.pendingSeq : ctl.lastSeq + 1;
    ctl.pendingSeq = seq;
    ctl.inflight = true;
    clear();
    try {
      const at = ctl.sendPosition ? position(video, ctl.item && ctl.item.durationSec) : null;
      const result = await sendHeartbeat(courseId, session.sessionId, { seq, positionSec: at, playing: !video.paused && !video.ended });
      if (ctl.session !== session) return;
      ctl.lastSeq = seq;
      ctl.pendingSeq = null;
      ctl.failures = 0;
      apply(result);
      if (ctl.phase === "active") schedule(interval());
    } catch (error) {
      if (ctl.session !== session) return;
      failed(error);
    } finally {
      ctl.inflight = false;
      if (now && ctl.phase === "active" && !ctl.timer) schedule(interval());
    }
  }

  function failed(error) {
    const code = error instanceof ApiError ? error.code : null;
    const status = error instanceof ApiError ? error.status : 0;
    // A closed session never restarts by itself: each code gets its own text and recovery.
    if (status === 409) return close(phaseOfCode(code));
    if (status === 404) return close("withdrawn");
    if (status === 401) return close("ended", { unload: true });
    if (status === 400 && ctl.sendPosition) {
      // The position hint was refused (a stream longer than the server's duration): drop it.
      ctl.sendPosition = false;
      ctl.pendingSeq = null;
      schedule(1000);
      return;
    }
    // Lost answer, overload or a rate limit: retry the same `seq` (the server ignores a repeat).
    ctl.failures += 1;
    const wait = error instanceof ApiError && error.retryAfter ? error.retryAfter * 1000 : Math.min(interval(), RETRY_FIRST_MS * 2 ** (ctl.failures - 1));
    ctl.netWarn = true;
    say(`서버와 연결이 불안정합니다. ${Math.round(wait / 1000)}초 뒤 다시 연결합니다. ${status === 429 ? "" : "연결이 오래 끊기면 학습이 끝납니다."}`.trim(), true);
    schedule(wait);
  }

  ctl.start = async ({ keepPosition = false } = {}) => {
    if (ctl.phase === "starting") return;
    clear();
    ctl.phase = "starting";
    ctl.notice = "";
    ctl.alert = false;
    paint();
    try {
      const started = await startSession(courseId, itemId);
      if (current !== ctl) {
        void endSession(courseId, started.sessionId, { reason: "unload" }).catch(() => {});
        return;
      }
      const manifest = manifestOf(started.playback);
      if (!manifest) {
        ctl.phase = "error";
        say("영상 주소를 받지 못했습니다. 잠시 후 다시 시작하세요.", true);
        return;
      }
      ctl.session = started;
      ctl.lastSeq = 0;
      ctl.pendingSeq = null;
      ctl.failures = 0;
      ctl.sendPosition = true;
      ctl.progress = started.progress || null;
      ctl.creditable = started.creditable !== false;
      ctl.creditUntil = started.creditUntil || null;
      ctl.creditOpen = null;
      ctl.creditState = "authorized";
      ctl.outcome = "";
      ctl.ended = null;
      ctl.auth.needed = false;
      ctl.phase = "active";
      paint();
      const resume = keepPosition && Number.isFinite(video.currentTime) && video.currentTime > 0 ? video.currentTime : Number(started.resumePositionSec) || 0;
      await attach(manifest, resume);
      if (keepPosition && ctl.phase === "active") {
        const playing = video.play();
        if (playing && typeof playing.catch === "function") playing.catch(() => {});
      }
      if (ctl.phase === "active") schedule(interval());
    } catch (error) {
      if (current !== ctl) return;
      if (error instanceof ApiError && error.status === 403 && error.code === "learning_auth_required") {
        ctl.phase = "auth";
        ctl.auth = { needed: true, challengeId: null, redirect: null, busy: false, note: "", alert: false };
        paint();
        return;
      }
      ctl.phase = missing(error) ? "unavailable" : "error";
      say(lectureMessage(error), true);
    }
  };

  ctl.finish = async () => {
    if (ctl.phase !== "active" || !ctl.session || ctl.ending) return;
    ctl.ending = true;
    paint();
    const session = ctl.session;
    clear();
    try {
      const ended = await endSession(courseId, session.sessionId, { reason: "button", positionSec: position(video, ctl.item && ctl.item.durationSec) });
      if (ended && ended.progress) ctl.progress = ended.progress;
      ctl.ended = ended;
      const reason = ended && ended.endReason;
      close(!reason || reason === "button" ? "ended" : phaseOfCode(closedCodeOf(reason)));
    } catch (error) {
      if (missing(error)) close("withdrawn");
      else if (error instanceof ApiError && error.status === 409) close(phaseOfCode(error.code));
      else {
        say(lectureMessage(error), true);
        if (ctl.phase === "active") schedule(interval());
      }
    } finally {
      ctl.ending = false;
      paint();
    }
  };

  ctl.leave = (reason) => {
    if (current === ctl) current = null;
    window.removeEventListener("beforeunload", onBeforeUnload);
    window.removeEventListener("pagehide", onPageHide);
    window.removeEventListener("pageshow", onPageShow);
    stopAuthChannel();
    observer.disconnect();
    const session = ctl.session;
    if (ctl.phase === "active" && session) {
      void endSession(courseId, session.sessionId, { reason, positionSec: position(video, ctl.item && ctl.item.durationSec) }).catch(() => {});
    }
    ctl.session = null;
    close("ended", { unload: true });
  };

  // ---- learning auth ----
  const authNote = (text, alert) => {
    ctl.auth.note = text || "";
    ctl.auth.alert = Boolean(alert);
    paint();
  };
  ctl.beginAuth = async () => {
    if (ctl.auth.busy) return;
    ctl.auth.busy = true;
    authNote("학습 인증을 준비하고 있습니다.");
    try {
      const created = await createChallenge();
      ctl.auth.challengeId = created.challengeId;
      if (created.redirectPath === "/api/auth/reauth") {
        ctl.auth.redirect = reauthHref(reauthReturnTo());
        authNote("새 탭에서 학교 계정으로 다시 로그인한 뒤 이 탭으로 돌아오세요. 돌아오면 자동으로 확인합니다.");
      } else if (offers("devLogin")) {
        await developerReauth();
        await ctl.confirmAuth();
        return;
      } else {
        ctl.auth.challengeId = null;
        authNote("이 서버에서 쓸 수 있는 학습 인증 수단이 없습니다. 학교 정보화본부에 문의하세요.", true);
      }
    } catch (error) {
      authNote(lectureMessage(error), true);
    } finally {
      ctl.auth.busy = false;
      paint();
    }
  };
  ctl.confirmAuth = async () => {
    const challengeId = ctl.auth.challengeId;
    if (!challengeId) return;
    ctl.auth.busy = true;
    authNote("학습 인증을 확인하고 있습니다.");
    try {
      await verifyChallenge(challengeId);
      ctl.auth = { needed: false, challengeId: null, redirect: null, busy: false, note: "", alert: false };
      if (ctl.phase === "auth") {
        await ctl.start({ keepPosition: true });
        return;
      }
      // A session waiting for re-authentication resumes on the next heartbeat; send it now.
      ctl.auth.needed = true;
      authNote("학습 인증을 확인했습니다. 적립을 다시 시작하는 중입니다.");
      await beat({ now: true });
    } catch (error) {
      const used = error instanceof ApiError && error.status === 409;
      if (used) ctl.auth.challengeId = null;
      authNote(lectureMessage(error), true);
    } finally {
      ctl.auth.busy = false;
      paint();
    }
  };
  let stopAuthChannel = onReauthMessage(({ type }) => {
    if (!ctl.auth.challengeId) return;
    if (type === "done") void ctl.confirmAuth();
    else authNote("학교 계정으로 다시 인증하지 못했습니다. 학습 인증을 다시 시작하세요.", true);
  });

  // ---- page lifecycle ----
  // `beforeunload` does not mean the page goes: another handler may keep it (a recording's leave
  // warning) and the learner may stay. It only sends a keepalive heartbeat, sent while the document
  // is still fully alive (Chrome drops some keepalive requests issued only in `pagehide`), so the
  // credit so far is recorded even if the end below is lost (the lease then closes the session).
  function onBeforeUnload() {
    const session = ctl.session;
    if (ctl.phase !== "active" || !session) return;
    const seq = ctl.pendingSeq != null ? ctl.pendingSeq : ctl.lastSeq + 1;
    const at = ctl.sendPosition ? position(video, ctl.item && ctl.item.durationSec) : null;
    if (!heartbeatOnUnload(courseId, session.sessionId, { seq, positionSec: at, playing: !video.paused && !video.ended })) return;
    // The next heartbeat uses a higher `seq`, so it is not ignored as a repeat of this one. A beat
    // in flight already carries `seq` and moves `lastSeq` when it lands.
    if (!ctl.inflight) {
      ctl.lastSeq = seq;
      ctl.pendingSeq = null;
    }
  }
  // `pagehide` is the page really going (or entering the back/forward cache): end the session there.
  function onPageHide() {
    const session = ctl.session;
    if (ctl.phase !== "active" || !session) return;
    const at = position(video, ctl.item && ctl.item.durationSec);
    // Stop the media first: open segment requests would otherwise hold the connections the
    // keepalive end has to use while the page goes away.
    detachMedia();
    endOnUnload(courseId, session.sessionId, at);
    clear();
    ctl.session = null;
    ctl.phase = "ended";
    paint();
  }
  function onPageShow(event) {
    if (event.persisted && current === ctl) {
      video.pause();
      paint();
    }
  }
  window.addEventListener("beforeunload", onBeforeUnload);
  window.addEventListener("pagehide", onPageHide);
  window.addEventListener("pageshow", onPageShow);
  // The <video> stays connected across re-renders (moved, not recreated); once it is detached
  // after a render settles, the learner has left the player.
  const observer = new MutationObserver(() => {
    if (!video.isConnected && current === ctl) ctl.leave("unload");
  });
  observer.observe(document.body, { childList: true, subtree: true });
  return ctl;
}

function controllerFor(spec) {
  const key = `${spec.courseId}:${spec.itemId}`;
  if (current && current.key === key) return current;
  if (current) current.leave("unload");
  current = createController(spec);
  return current;
}

function statusText(ctl) {
  if (ctl.phase !== "active") return PHASE_TEXT[ctl.phase] || "";
  if (!ctl.creditable) return "담당자 미리보기입니다. 출석 시간을 적립하지 않습니다.";
  if (ctl.creditState === "reauth_required") return "학습 인증이 끝나 출석 시간 적립을 멈췄습니다. 재생은 계속할 수 있습니다.";
  if (ctl.creditOpen === false) return "학습 인정 기간이 끝났습니다. 재생은 계속할 수 있지만 출석 시간은 쌓이지 않습니다.";
  return "학습 중입니다. 출석 시간은 서버가 적립합니다.";
}

// `ctl.views` is always the latest render's tree, so it is painted even before that tree is
// mounted (`remoteLecturePlayer` paints while building it, so a re-render shows the right buttons).
function paintViews(ctl) {
  const { pill, status, progress, actions, auth, notice } = ctl.views;
  const met = ctl.progress && ctl.progress.status === "met";
  pill.textContent = met ? "출석 인정" : ctl.phase === "active" && ctl.creditable ? "출석 진행" : ctl.phase === "active" ? "미리보기" : "학습 중 아님";
  pill.classList.toggle("ok", Boolean(met));
  status.textContent = statusText(ctl);
  notice.textContent = ctl.notice;
  notice.className = ctl.alert ? "err" : "tiny";
  notice.setAttribute("role", ctl.alert ? "alert" : "status");
  const p = ctl.progress;
  progress.replaceChildren(
    el("p", { class: "tiny", text: ctl.creditable ? progressLine(p) : "미리보기는 진도에 반영되지 않습니다." }),
    ctl.creditable ? progressBar(p) : "",
    ctl.creditUntil && ctl.creditable ? el("p", { class: "tiny", text: `적립 마감 ${koDateTime(ctl.creditUntil)}` }) : "",
    ctl.ended ? el("p", { class: "tiny", text: `이번 종료로 ${durationText(ctl.ended.creditedSec)}를 더 적립했습니다.` }) : "",
  );
  const detail = ctl.phase === "withdrawn"
    ? go(listHref(ctl.kj), "학습활동 목록", { class: "btn btn-secondary" })
    : go(studentLectureHref(ctl.kj, ctl.activityId), "강의 목록으로", { class: "btn btn-secondary" });
  const nodes = [];
  if (ctl.phase === "active") {
    const end = btn(ctl.ending ? "종료하는 중…" : "학습 종료", { "aria-busy": ctl.ending ? "true" : "false" }, "primary");
    end.disabled = Boolean(ctl.ending);
    end.addEventListener("click", () => { void ctl.finish(); });
    nodes.push(end);
  } else if (RESTART_LABEL[ctl.phase]) {
    // Every restart is a new learning session (`POST …/learning-sessions`) from where the video stopped.
    const again = btn(RESTART_LABEL[ctl.phase], { "data-restart": ctl.phase }, "primary");
    again.addEventListener("click", () => { again.disabled = true; void ctl.start({ keepPosition: true }); });
    nodes.push(again);
  } else if (ctl.phase === "starting") {
    nodes.push(btn("시작하는 중…", { disabled: true, "aria-busy": "true" }, "primary"));
  }
  nodes.push(detail);
  actions.replaceChildren(...nodes);
  auth.hidden = !ctl.auth.needed;
  if (ctl.auth.needed) auth.replaceChildren(...authNodes(ctl));
}

function authNodes(ctl) {
  const state = ctl.auth;
  const nodes = [
    el("h2", { text: "학습 인증" }),
    el("p", { text: ctl.phase === "auth"
      ? "이 강의는 학습 전에 본인 인증이 필요합니다. 학교 계정으로 다시 로그인해 인증하면 이어서 학습할 수 있습니다."
      : "학습 인증 시간이 끝나 출석 시간 적립을 멈췄습니다. 영상은 계속 재생됩니다. 다시 인증하면 지금 위치에서 적립을 이어 갑니다. 인증하지 않은 동안의 시간은 적립되지 않습니다." }),
  ];
  const row = el("div", { class: "btn-row" });
  if (!state.challengeId) {
    const start = btn(state.busy ? "준비하는 중…" : "학습 인증 시작", { "aria-busy": state.busy ? "true" : "false" }, "primary");
    start.disabled = state.busy;
    start.addEventListener("click", () => { void ctl.beginAuth(); });
    row.append(start);
  } else {
    if (state.redirect) row.append(el("a", { class: "btn btn-primary", href: state.redirect, target: "_blank", rel: "noopener", text: "학교 계정으로 다시 인증 (새 탭)" }));
    const confirm = btn(state.busy ? "확인하는 중…" : "인증을 마쳤습니다", { "aria-busy": state.busy ? "true" : "false" }, state.redirect ? "secondary" : "primary");
    confirm.disabled = state.busy;
    confirm.addEventListener("click", () => { void ctl.confirmAuth(); });
    const again = btn("인증 다시 시작", {}, "ghost");
    again.disabled = state.busy;
    again.addEventListener("click", () => { state.challengeId = null; void ctl.beginAuth(); });
    row.append(confirm, again);
  }
  nodes.push(row);
  if (state.note) nodes.push(el("p", { class: state.alert ? "err" : "tiny", role: state.alert ? "alert" : "status", text: state.note }));
  return nodes;
}

// What this page reported to the learning tab (once per page load): "done" | "failed" | null.
let reported = null;

/**
 * The re-authentication tab's result. The IdP comes back to the player with `auth=done`, and a
 * failed attempt adds `reauth=failed` (also caught at module load in lectures-remote.js, which
 * already announced it). A failure is announced as `failed`, never `done`, and the address loses
 * `reauth` and says `auth=failed`, so a reload or a repaint repeats the true result and never
 * starts a session in this tab. Returns null for an ordinary player address.
 */
function reauthReport(params) {
  const auth = params ? params.get("auth") : null;
  const failed = auth === "failed" || (params && params.get("reauth") === "failed") || (auth === "done" && reauthFailedAtLoad);
  if (!failed && auth !== "done") return null;
  const type = failed ? "failed" : "done";
  if (!reported) {
    reported = type;
    if (!(type === "failed" && reauthFailedAtLoad)) queueMicrotask(() => announceReauth(type));
  }
  if (type === "failed" && typeof location !== "undefined") {
    const next = new URLSearchParams(location.search);
    if (next.has("reauth") || next.get("auth") !== "failed") {
      next.delete("reauth");
      next.set("auth", "failed");
      history.replaceState(history.state, "", `${location.pathname}?${next}${location.hash}`);
    }
  }
  return type;
}

/** The player of `screen.serverLecture` / `screen.serverItem` (backend mode). */
export function remoteLecturePlayer(screen, ctx) {
  ensurePlayerChrome();
  const kj = screen.course;
  const activityId = screen.serverLecture;
  const itemId = screen.serverItem;
  const shell = el("section", { class: "player-page is-open srv-player", "data-item-open": `srv-${activityId}` });
  const blocked = gate(shell, kj, () => shell.replaceChildren(emptyState("활동을 찾을 수 없습니다", "ASTRA 서버에 연결되어 있지 않습니다.")));
  if (blocked) {
    shell.append(blocked);
    return shell;
  }
  // The tab that came back from the IdP re-authentication only reports it; it never plays.
  const report = reauthReport(ctx.params);
  if (report === "failed") {
    shell.append(emptyState("학교 계정으로 다시 인증하지 못했습니다", "이 탭을 닫고 학습하던 탭에서 학습 인증을 다시 시작하세요.",
      go(studentLectureHref(kj, activityId), "강의 상세", { class: "btn btn-secondary" })));
    return shell;
  }
  if (report === "done") {
    shell.append(emptyState("학교 계정 재인증을 마쳤습니다", "이 탭을 닫고 학습하던 탭으로 돌아가세요. 학습하던 탭이 인증을 확인합니다.",
      go(studentLectureHref(kj, activityId), "강의 상세", { class: "btn btn-secondary" })));
    return shell;
  }
  if (!itemId) {
    shell.append(emptyState("콘텐츠를 고르세요", "강의 상세에서 학습할 콘텐츠를 선택하세요.", go(studentLectureHref(kj, activityId), "강의 상세", { class: "btn btn-primary" })));
    return shell;
  }
  const courseId = courseIdFor(kj);
  const key = detailKey(courseId, activityId);
  const entry = lectureDetails.peek(key);
  const data = entry && entry.data;
  if (!data) {
    if (!entry || !entry.error) shell.append(loadingState("강의를 불러오는 중입니다"));
    else if (missing(entry.error)) shell.append(emptyState("강의를 찾을 수 없습니다", "비공개로 바뀌었거나 삭제되었거나 볼 수 있는 권한이 없습니다.", go(listHref(kj), "학습활동 목록", { class: "btn btn-secondary" })));
    else shell.append(errorState("강의를 불러오지 못했습니다", lectureMessage(entry.error), retry(() => lectureDetails.load(key, courseId, activityId))));
    queueMicrotask(() => {
      if (!shell.isConnected) return;
      lectureDetails.watch(shell, key, () => {
        const next = lectureDetails.peek(key);
        if (next && (next.data || next.error)) {
          const node = remoteLecturePlayer(screen, ctx);
          if (shell.isConnected) shell.replaceWith(node);
        }
      }, null);
      if (!lectureDetails.peek(key) || !lectureDetails.peek(key).loading) lectureDetails.load(key, courseId, activityId);
    });
    return shell;
  }
  const items = Array.isArray(data.lectureItems) ? data.lectureItems : [];
  const item = items.find((entryItem) => entryItem.itemId === itemId);
  if (!item || item.state !== "ready") {
    shell.append(emptyState(item ? "아직 재생할 수 없는 콘텐츠입니다" : "콘텐츠를 찾을 수 없습니다",
      item ? (item.state === "processing" ? "영상을 변환하고 있습니다. 끝나면 학습할 수 있습니다." : "영상을 재생할 수 없습니다. 담당 교수에게 문의하세요.") : "삭제되었거나 이 강의의 콘텐츠가 아닙니다.",
      go(studentLectureHref(kj, activityId), "강의 상세", { class: "btn btn-secondary" })));
    return shell;
  }
  const ctl = controllerFor({ kj, courseId, activityId, itemId });
  ctl.item = item;
  ctl.video.setAttribute("aria-label", `${data.title} ${item.title}`);

  const pill = el("span", { class: "attend-pill" });
  const status = el("p", { class: "tiny", role: "status", "aria-live": "polite" });
  const notice = el("p", { class: "tiny", role: "status", "aria-live": "polite" });
  const progress = el("div", { class: "srv-player-progress" });
  const actions = el("div", { class: "btn-row" });
  const auth = el("section", { class: "callout srv-player-auth", "aria-label": "학습 인증", hidden: true });
  const page = el("section", { class: "player-page is-open srv-player", "data-item-open": `srv-${activityId}` },
    el("div", { class: "player-top" },
      el("h1", { text: `[${data.weekNo}주] ${item.title}` }),
      pill,
    ),
    el("div", { class: "player-stage" }, ctl.video),
    el("div", { class: "player-chrome" },
      status,
      notice,
      progress,
      auth,
      el("div", { class: "player-tools" }, actions),
    ),
  );
  ctl.views = { pill, status, progress, actions, auth, notice };
  ctl.paint();
  // A session starts by itself only on a fresh entry to this player (a link, back/forward, a reload):
  // `freshVisit` is recorded only here, once the player is drawn, so a detail that arrives late
  // still counts as that entry. Any other render (theme, study chat panel, a reconnect, a repaint
  // after the session closed) leaves a new controller idle with its 학습 시작 button.
  if (freshVisit("lecture-player", ctl.key) === "entry" && ctl.phase === "idle") {
    queueMicrotask(() => { if (ctl.video.isConnected && ctl.phase === "idle") void ctl.start(); });
  }
  return videoStudy(courseById(kj), { id: `srv-${activityId}`, title: data.title }, page, (seconds) => {
    ctl.video.currentTime = seconds;
  }, { server: true });
}
