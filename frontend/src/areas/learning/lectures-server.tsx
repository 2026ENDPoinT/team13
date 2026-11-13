import { Fragment, useEffect, useReducer, useRef, useState, useSyncExternalStore, type ReactNode } from "react";
import { Button, EmptyState, ErrorState, LoadingState, Badge } from "../../ui/kit.tsx";
import { Link } from "../../router.tsx";
import { ActivityShell } from "../../shared/activity-rail.tsx";
import { ApiError } from "../../shared/api.ts";
import { backendStatus, backendSettled, connect as connectBackend, courseIdFor, offers, roleIn, subscribe } from "../../shared/backend.ts";
import { SIGNED_OUT } from "../../shared/backend-prompt.tsx";
import { freshVisit, useResource, type Entry } from "../../shared/remote-cache.ts";
import { koDateTime } from "../../shared/home-remote.ts";
import { courseById } from "../../data.ts";
import { videoStudy } from "./video-study.tsx";
import { ensurePlayerChrome, PlayerIcon, usePlayerFullscreen, togglePlayerFullscreen } from "../../shared/player-controls.ts";
import type { ActivityParts } from "./learning.tsx";
import type { Screen, ScreenCtx } from "../../types.ts";
import {
  lectureDetails, lectureProgress, detailKey, courseKey, progressOf, forgetLecture, lectureMessage, studentLectureHref,
  lecturePlayerHref, startSession, sendHeartbeat, endSession, heartbeatOnUnload, endOnUnload, manifestOf, createChallenge, verifyChallenge,
  developerReauth, reauthHref, reauthReturnTo, onReauthMessage, announceReauth, reauthFailedAtLoad, SESSION_CLOSED,
  closedCodeOf, VIDEO_STATE, PROGRESS_STATE, durationText, windowText,
  type ActivityDetail, type LectureItem, type ItemProgress, type ProgressWeek, type StartedSession, type HeartbeatResult, type EndedSession,
} from "../../shared/lectures-remote.ts";

const HLS_MODULE = "/app/vendor/hlsjs/hls.light.min.mjs";
const RETRY_FIRST_MS = 5000;
const missing = (error: unknown): boolean => error instanceof ApiError && error.status === 404;
const listHref = (kj: string): string => `/ilos/cls/st/activity/activity_list_form.acl?kj=${encodeURIComponent(kj)}`;

function Retry({ reload }: { reload: () => void }): ReactNode {
  const [busy, setBusy] = useState(false);
  return <Button kind="ghost" disabled={busy} onClick={() => { setBusy(true); reload(); }}>다시 불러오기</Button>;
}

function useGate(kj: string): ReactNode {
  const status = useSyncExternalStore(subscribe, backendStatus);
  const [settled, setSettled] = useState(backendSettled);
  useEffect(() => {
    if (status !== "off" || backendSettled()) return;
    let live = true;
    void connectBackend().then(() => { if (live) setSettled(true); });
    return () => { live = false; };
  }, [status]);
  if (status === "anon") return SIGNED_OUT;
  if (status === "off") return !settled && !backendSettled()
    ? <LoadingState title="ASTRA 서버에 연결하는 중입니다" />
    : <EmptyState title="활동을 찾을 수 없습니다" body="ASTRA 서버에 연결되어 있지 않습니다. 목록에서 다른 활동을 선택해 보세요." />;
  if (!courseIdFor(kj)) return <EmptyState title="이 과목은 ASTRA 서버에 없습니다" body="서버에 등록된 과목의 강의만 볼 수 있습니다." />;
  return null;
}

function Prose({ text }: { text?: string | null }): ReactNode {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  return blocks.length ? <div className="prose">{blocks.map((block, index) => <p key={index}>{block.split("\n").map((line, i) => <Fragment key={i}>{i ? <br /> : null}{line}</Fragment>)}</p>)}</div> : null;
}

function progressLine(progress: ItemProgress | null | undefined): string {
  if (!progress) return "아직 학습 기록이 없습니다.";
  const required = progress.requiredSec == null ? "인정 기준 시간 없음" : `인정 기준 시간 ${durationText(progress.requiredSec)}`;
  const met = progress.attendedAt ? ` (${koDateTime(progress.attendedAt)})` : "";
  const status = typeof progress.status === "string" ? progress.status : "";
  return `적립 ${durationText(progress.creditedSec)} / ${required} · ${PROGRESS_STATE[status] || status}${met}`;
}

function ProgressBar({ progress }: { progress?: ItemProgress | null }): ReactNode {
  const percent = progress ? Math.max(0, Math.min(100, Number(progress.progressPercent) || 0)) : 0;
  return <div className="bar" role="img" aria-label={`진도 ${percent}%`}><i style={{ width: `${percent}%` }} /></div>;
}

export function remoteLectureDetail(screen: Screen, _ctx: ScreenCtx, parts: ActivityParts): ReactNode {
  return <LectureDetail screen={screen} parts={parts} />;
}

function LectureDetail({ screen, parts }: { screen: Screen; parts: ActivityParts }): ReactNode {
  const kj = screen.course || "";
  const gate = useGate(kj);
  const courseId = courseIdFor(kj);
  const activityId = screen.serverLecture || 0;
  const key = courseId ? detailKey(courseId, activityId) : null;
  const pkey = courseId ? courseKey(courseId) : null;
  const load = () => { if (courseId && key && !gate) void lectureDetails.load(key, courseId, activityId); };
  const loadProgress = () => { if (courseId && pkey && !gate) void lectureProgress.load(pkey, courseId); };
  const entry = useResource(lectureDetails, key, gate ? undefined : load);
  const progress = useResource(lectureProgress, pkey, gate ? undefined : loadProgress);
  useEffect(() => {
    if (gate || !courseId || !key || !pkey) return;
    if (freshVisit("lecture-detail", key) === "entry" || !lectureDetails.peek(key)) { load(); loadProgress(); }
    else if (!lectureProgress.peek(pkey)) loadProgress();
  }, [courseId, key, pkey, Boolean(gate)]);
  let body: ReactNode = null;
  if (gate) body = <div className="act-body">{parts.typeLine("video")}{gate}</div>;
  else if (!entry?.data) body = !entry?.error ? <LoadingState title="강의를 불러오는 중입니다" /> : missing(entry.error)
    ? <EmptyState title="강의를 찾을 수 없습니다" body="아직 공개되지 않았거나 삭제되었거나 볼 수 있는 권한이 없습니다." actions={<Link href={listHref(kj)} className="btn btn-secondary">학습활동 목록</Link>} />
    : <ErrorState title="강의를 불러오지 못했습니다" body={lectureMessage(entry.error)} actions={<Retry key={String(entry.error)} reload={load} />} />;
  else if (entry.data.type !== "lecture_weeks") body = <EmptyState title="영상 활동이 아닙니다" body="학습활동 목록에서 다시 선택해 보세요." actions={<Link href={listHref(kj)} className="btn btn-secondary">학습활동 목록</Link>} />;
  else body = <DetailBody data={entry.data} kj={kj} parts={parts} staleError={entry.error} progressEntry={progress} />;
  return <ActivityShell kj={kj} itemOpen={`srv-${activityId}`} solo>{body}</ActivityShell>;
}

function DetailBody({ data, kj, parts, staleError, progressEntry }: { data: ActivityDetail; kj: string; parts: ActivityParts; staleError: unknown; progressEntry: Entry<ProgressWeek[]> | null }): ReactNode {
  const items = data.lectureItems || [];
  const instructor = roleIn(kj) === "INSTRUCTOR";
  const progress = progressOf(progressEntry?.data, data.activityId);
  return <div className="act-body">
    {parts.typeLine("video")}<h1>{data.title}</h1>
    <p className="tiny">{`${courseById(kj).ko} · ${data.weekNo}주차 · 콘텐츠 ${items.length}개${data.published ? "" : " · 비공개"}`}</p>
    {staleError ? <p className="err" role="alert">{lectureMessage(staleError)}</p> : null}
    <section className="callout"><h2>주의사항</h2><ul>
      <li>적립 시간은 ASTRA 서버가 학습 창이 열려 있는 동안의 서버 시각으로 셉니다. 배속이나 재생 위치를 옮겨도 시간이 줄지 않습니다.</li>
      <li>한 번에 한 강의만 학습할 수 있습니다. 다른 곳에서 학습을 시작하면 이 화면의 학습이 끝납니다.</li>
      <li>학습 인정 기간이 끝난 뒤에도 재생할 수 있지만 적립 시간은 늘지 않습니다.</li>
      <li>학습을 마칠 때는 학습 창의 학습 종료 버튼을 누르세요.</li>
    </ul></section>
    {parts.metaTable([["학습 인정 기간", windowText(data)], ["인정 기준 시간", "콘텐츠마다 적립 시간이 인정 기준 시간 이상이면 그 콘텐츠의 인정 기준을 충족합니다. 출석은 출석부 기록을 따릅니다"]])}
    <Prose text={data.description} /><h2>콘텐츠</h2>
    {progressEntry?.error && !progressEntry.data ? <p className="err" role="alert">진도를 불러오지 못했습니다. {lectureMessage(progressEntry.error)}</p> : null}
    {items.length ? <div className="srv-lecture-items">{items.map((item) => {
      const p = progress.get(item.itemId);
      const action = instructor ? "미리보기" : "학습하기";
      const meta = [item.durationSec != null ? `길이 ${durationText(item.durationSec)}` : null, item.requiredSec != null ? `인정 기준 시간 ${durationText(item.requiredSec)}` : null].filter(Boolean).join(" · ");
      return <article key={item.itemId} className="progress-card srv-lecture-item">
        <div className="progress-pct">{p ? `${p.progressPercent}%` : "—"}</div><div className="srv-lecture-copy"><b>{item.title}</b>
          <p className="tiny"><Badge tone={item.state === "ready" ? "success" : item.state === "processing" ? "neutral" : "danger"}>{VIDEO_STATE[item.state || ""] || "재생할 수 없음"}</Badge>{meta ? ` ${meta}` : ""}</p>
          <p className="tiny">{instructor ? "담당자 미리보기는 시간을 적립하지 않습니다." : !progressEntry || (!progressEntry.data && !progressEntry.error) ? "진도를 불러오는 중입니다." : progressLine(p)}</p>
          {instructor ? null : <ProgressBar progress={p} />}
          <div className="btn-row">{item.state === "ready" ? <Link href={lecturePlayerHref(kj, data.activityId, item.itemId)} className="btn btn-primary" aria-label={`${item.title} ${action}`}>{action}</Link> : <p className="tiny">{item.state === "processing" ? "영상을 변환하고 있습니다. 끝나면 학습할 수 있습니다." : "영상을 재생할 수 없습니다. 담당 교수에게 문의하세요."}</p>}</div>
        </div>
      </article>;
    })}</div> : <p className="tiny">등록된 콘텐츠가 없습니다.</p>}
  </div>;
}

interface HlsInstance {
  destroy(): void;
  startLoad(position: number): void;
  recoverMediaError(): void;
  loadSource(manifest: string): void;
  attachMedia(video: HTMLVideoElement): void;
  on(event: string, listener: (event: string, data: { fatal?: boolean; type?: string }) => void): void;
}
interface HlsConstructor {
  new(options: { enableWorker: boolean; startPosition: number; capLevelToPlayerSize: boolean }): HlsInstance;
  isSupported(): boolean;
  Events: { ERROR: string; FRAG_LOADED: string };
  ErrorTypes: { MEDIA_ERROR: string; NETWORK_ERROR: string };
}
interface AuthState { needed: boolean; challengeId: number | null; redirect: string | null; busy: boolean; note: string; alert: boolean }
interface PlayerSpec { kj: string; courseId: number | string; activityId: number; itemId: number }
interface Controller extends PlayerSpec {
  key: string; phase: string; item: LectureItem | null; video: HTMLVideoElement; session: StartedSession | null;
  lastSeq: number; pendingSeq: number | null; inflight: boolean; failures: number; timer: number;
  progress: ItemProgress | null; creditable: boolean; creditOpen: boolean | null; creditState: string; creditUntil: string | null;
  outcome: string; notice: string; alert: boolean; auth: AuthState; ended: EndedSession | null;
  hls: HlsInstance | null; manifest: string | null; recovered: boolean; sendPosition: boolean; netWarn: boolean; ending: boolean;
  start(options?: { keepPosition?: boolean }): Promise<void>;
  finish(): Promise<void>; leave(reason: string): void; beginAuth(): Promise<void>; confirmAuth(): Promise<void>;
  paint(): void;
}

const PHASE_TEXT: Record<string, string> = {
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
const CLOSED_PHASE: Record<string, string> = { session_superseded: "superseded", session_expired: "expired", session_ended: "stale", session_withdrawn: "withdrawn" };
const phaseOfCode = (code: string | null | undefined) => CLOSED_PHASE[code || ""] || "stale";
// Phases that offer a new session, with the button text. `withdrawn` offers none: the content is gone.
// `idle` is a player that did not start by itself (see `remoteLecturePlayer`).
const RESTART_LABEL: Record<string, string> = { idle: "학습 시작", superseded: "이 화면에서 이어 보기", expired: "마지막 위치에서 다시 시작", stale: "다시 시작", ended: "다시 시작", error: "다시 시작" };

let current: Controller | null = null; // the one live player controller
subscribe((next) => {
  if (current && next.status !== "ready") current.leave("unload");
});

function position(video: HTMLVideoElement, durationSec?: number | null): number | null {
  const at = Number.isFinite(video.currentTime) ? Math.floor(video.currentTime) : null;
  if (at == null || at < 0) return null;
  return durationSec != null && Number.isFinite(durationSec) ? Math.min(at, Math.max(0, Math.floor(durationSec))) : at;
}

/** One session per mounted player. React keeps its video across renders and effect cleanup ends it. */
function createController({ kj, courseId, activityId, itemId }: PlayerSpec, video: HTMLVideoElement, redraw: () => void): Controller {
  const ctl: Controller = {
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
    video, start, finish, leave, beginAuth, confirmAuth, paint,
  };

  function paint(): void { redraw(); }
  const say = (text: string, alert = false) => {
    ctl.notice = text || "";
    ctl.alert = Boolean(alert);
    paint();
  };
  const clear = () => {
    clearTimeout(ctl.timer);
    ctl.timer = 0;
  };
  const schedule = (ms: number) => {
    clear();
    ctl.timer = window.setTimeout(() => { void beat(); }, ms);
  };
  const interval = () => Math.max(10, Math.min(300, Number(ctl.session && ctl.session.heartbeatIntervalSec) || 60)) * 1000;
  let removeNativeListeners: (() => void) | null = null;

  function detachMedia() {
    removeNativeListeners?.();
    removeNativeListeners = null;
    if (ctl.hls) {
      try { ctl.hls.destroy(); } catch { /* already gone */ }
      ctl.hls = null;
    }
    ctl.manifest = null;
    video.pause();
    video.removeAttribute("src");
    try { video.load(); } catch { /* nothing loaded */ }
  }

  function close(phase: string, { unload = false } = {}) {
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

  async function attach(manifest: string, at: number) {
    const start = Number.isFinite(at) && at > 0 ? at : 0;
    if (ctl.manifest === manifest) {
      if (ctl.hls) ctl.hls.startLoad(Math.floor(video.currentTime || start));
      return;
    }
    detachMedia();
    ctl.manifest = manifest;
    ctl.recovered = false;
    const seekOnce = () => {
      if (ctl.manifest === manifest && start > 0.5 && Number.isFinite(video.duration) && start < video.duration - 1) video.currentTime = start;
    };
    const native = typeof video.canPlayType === "function" && video.canPlayType("application/vnd.apple.mpegurl") !== "";
    if (native) {
      video.addEventListener("loadedmetadata", seekOnce, { once: true });
      const fallback = () => {
        // A browser that claims native HLS but cannot play this stream falls back to hls.js.
        if (ctl.manifest === manifest && video.readyState === 0 && !ctl.hls) void attachHls(manifest, start, true);
      };
      video.addEventListener("error", fallback, { once: true });
      removeNativeListeners = () => {
        video.removeEventListener("loadedmetadata", seekOnce);
        video.removeEventListener("error", fallback);
      };
      video.src = manifest;
      return;
    }
    await attachHls(manifest, start, false);
  }

  async function attachHls(manifest: string, start: number, fallback: boolean) {
    let Hls: HlsConstructor;
    try {
      ({ default: Hls } = (await import(/* @vite-ignore */ HLS_MODULE) as { default: HlsConstructor }));
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

  function apply(result: HeartbeatResult) {
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
      ctl.notice = "학습 인증을 확인했습니다. 지금 위치에서 시간 적립을 이어 갑니다.";
      ctl.alert = false;
    }
    paint();
  }

  async function beat({ now = false } = {}) {
    if (ctl.phase !== "active" || !ctl.session) return;
    if (ctl.inflight || ctl.ending) return;
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
      const result = await sendHeartbeat(courseId, session.sessionId, { seq, positionSec: at, playing: document.visibilityState === "visible" && !video.paused && !video.ended && !video.seeking && video.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA });
      if (ctl.session !== session || ctl.phase !== "active" || ctl.ending) return;
      ctl.lastSeq = seq;
      ctl.pendingSeq = null;
      ctl.failures = 0;
      apply(result);
      if (ctl.phase === "active") schedule(interval());
    } catch (error) {
      if (ctl.session !== session || ctl.phase !== "active" || ctl.ending) return;
      failed(error);
    } finally {
      ctl.inflight = false;
      if (now && current === ctl && ctl.phase === "active" && !ctl.ending && !ctl.timer) schedule(interval());
    }
  }

  function failed(error: unknown) {
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

  async function start({ keepPosition = false }: { keepPosition?: boolean } = {}): Promise<void> {
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
        void endSession(courseId, started.sessionId, { reason: "unload" }).catch(() => {});
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
      if (keepPosition && current === ctl && ctl.phase === "active") {
        const playing = video.play();
        if (playing && typeof playing.catch === "function") playing.catch(() => {});
      }
      if (current === ctl && ctl.phase === "active") schedule(interval());
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

  async function finish(): Promise<void> {
    if (ctl.phase !== "active" || !ctl.session || ctl.ending) return;
    ctl.ending = true;
    paint();
    const session = ctl.session;
    clear();
    try {
      const ended = await endSession(courseId, session.sessionId, { reason: "button", positionSec: position(video, ctl.item && ctl.item.durationSec) });
      if (current !== ctl || ctl.session !== session || ctl.phase !== "active") return;
      if (ended && ended.progress) ctl.progress = ended.progress;
      ctl.ended = ended;
      const reason = ended && ended.endReason;
      close(!reason || reason === "button" ? "ended" : phaseOfCode(closedCodeOf(reason)));
    } catch (error) {
      if (current !== ctl || ctl.session !== session || ctl.phase !== "active") return;
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

  function leave(reason: string): void {
    if (current === ctl) current = null;
    window.removeEventListener("beforeunload", onBeforeUnload);
    window.removeEventListener("pagehide", onPageHide);
    window.removeEventListener("pageshow", onPageShow);
    stopAuthChannel();

    const session = ctl.session;
    if (ctl.phase === "active" && session) {
      void endSession(courseId, session.sessionId, { reason, positionSec: position(video, ctl.item && ctl.item.durationSec) }).catch(() => {});
    }
    ctl.session = null;
    close("ended", { unload: true });
  };

  // ---- learning auth ----
  const authNote = (text: string, alert = false) => {
    ctl.auth.note = text || "";
    ctl.auth.alert = Boolean(alert);
    paint();
  };
  async function beginAuth(): Promise<void> {
    if (ctl.auth.busy) return;
    ctl.auth.busy = true;
    authNote("학습 인증을 준비하고 있습니다.");
    try {
      const created = await createChallenge();
      ctl.auth.challengeId = created.challengeId;
      if (created.redirectPath === "/api/auth/reauth") {
        ctl.auth.redirect = reauthHref({ ...location, ...reauthReturnTo() });
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
  async function confirmAuth(): Promise<void> {
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
  const stopAuthChannel = onReauthMessage(({ type }) => {
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
    if (!heartbeatOnUnload(courseId, session.sessionId, { seq, positionSec: at, playing: document.visibilityState === "visible" && !video.paused && !video.ended && !video.seeking && video.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA })) return;
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
  function onPageShow(event: PageTransitionEvent) {
    if (event.persisted && current === ctl) {
      video.pause();
      paint();
    }
  }
  window.addEventListener("beforeunload", onBeforeUnload);
  window.addEventListener("pagehide", onPageHide);
  window.addEventListener("pageshow", onPageShow);
  return ctl;
}


function statusText(ctl: Controller): string {
  if (ctl.phase !== "active") return PHASE_TEXT[ctl.phase] || "";
  if (!ctl.creditable) return "담당자 미리보기입니다. 시간을 적립하지 않습니다.";
  if (ctl.creditState === "reauth_required") return "학습 인증이 끝나 시간 적립을 멈췄습니다. 재생은 계속할 수 있습니다.";
  if (ctl.creditOpen === false) return "학습 인정 기간이 끝났습니다. 재생은 계속할 수 있지만 적립 시간은 늘지 않습니다.";
  return "학습 중입니다. 적립 시간은 서버가 셉니다.";
}


// What this page reported to the learning tab (once per page load): "done" | "failed" | null.
let reported: "done" | "failed" | null = null;

/**
 * The re-authentication tab's result. The IdP comes back to the player with `auth=done`, and a
 * failed attempt adds `reauth=failed` (also caught at module load in lectures-remote.js, which
 * already announced it). A failure is announced as `failed`, never `done`, and the address loses
 * `reauth` and says `auth=failed`, so a reload or a repaint repeats the true result and never
 * starts a session in this tab. Returns null for an ordinary player address.
 */
function reauthReport(params: URLSearchParams): "done" | "failed" | null {
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

export function remoteLecturePlayer(screen: Screen, ctx: ScreenCtx): ReactNode {
  return <LecturePlayer screen={screen} ctx={ctx} />;
}

function LecturePlayer({ screen, ctx }: { screen: Screen; ctx: ScreenCtx }): ReactNode {
  const kj = screen.course || "";
  const activityId = screen.serverLecture || 0;
  const itemId = screen.serverItem;
  const gate = useGate(kj);
  const courseId = courseIdFor(kj);
  const auth = ctx.params.get("auth");
  const failed = auth === "failed" || ctx.params.get("reauth") === "failed" || (auth === "done" && reauthFailedAtLoad);
  const report = failed ? "failed" : auth === "done" ? "done" : null;
  const key = courseId ? detailKey(courseId, activityId) : null;
  const enabled = !gate && !report && Boolean(itemId);
  const load = () => { if (enabled && courseId && key) void lectureDetails.load(key, courseId, activityId); };
  const entry = useResource(lectureDetails, key, enabled ? load : undefined);
  useEffect(() => {
    ensurePlayerChrome();
    if (!gate && report) reauthReport(ctx.params);
  }, [Boolean(gate), report, ctx.params]);
  useEffect(() => {
    if (enabled && courseId && key && !lectureDetails.peek(key)) void lectureDetails.load(key, courseId, activityId);
  }, [enabled, courseId, key, activityId]);
  let body: ReactNode = null;
  const detailLink = <Link href={studentLectureHref(kj, activityId)} className="btn btn-secondary">강의 상세</Link>;
  if (gate) body = gate;
  else if (report === "failed") body = <EmptyState title="학교 계정으로 다시 인증하지 못했습니다" body="이 탭을 닫고 학습하던 탭에서 학습 인증을 다시 시작하세요." actions={detailLink} />;
  else if (report === "done") body = <EmptyState title="학교 계정 재인증을 마쳤습니다" body="이 탭을 닫고 학습하던 탭으로 돌아가세요. 학습하던 탭이 인증을 확인합니다." actions={detailLink} />;
  else if (!itemId) body = <EmptyState title="콘텐츠를 고르세요" body="강의 상세에서 학습할 콘텐츠를 선택하세요." actions={<Link href={studentLectureHref(kj, activityId)} className="btn btn-primary">강의 상세</Link>} />;
  else if (!entry?.data) body = !entry?.error ? <LoadingState title="강의를 불러오는 중입니다" /> : missing(entry.error)
    ? <EmptyState title="강의를 찾을 수 없습니다" body="비공개로 바뀌었거나 삭제되었거나 볼 수 있는 권한이 없습니다." actions={<Link href={listHref(kj)} className="btn btn-secondary">학습활동 목록</Link>} />
    : <ErrorState title="강의를 불러오지 못했습니다" body={lectureMessage(entry.error)} actions={<Retry key={String(entry.error)} reload={load} />} />;
  else {
    const item = entry.data.lectureItems?.find((candidate) => candidate.itemId === itemId);
    if (!item || item.state !== "ready") body = <EmptyState title={item ? "아직 재생할 수 없는 콘텐츠입니다" : "콘텐츠를 찾을 수 없습니다"}
      body={item ? item.state === "processing" ? "영상을 변환하고 있습니다. 끝나면 학습할 수 있습니다." : "영상을 재생할 수 없습니다. 담당 교수에게 문의하세요." : "삭제되었거나 이 강의의 콘텐츠가 아닙니다."} actions={detailLink} />;
    else if (courseId) return <ReadyPlayer key={`${courseId}:${itemId}`} spec={{ kj, courseId, activityId, itemId }} data={entry.data} item={item} />;
  }
  return <section className="player-page is-open srv-player" data-item-open={`srv-${activityId}`}>{body}</section>;
}

function AuthPanel({ ctl }: { ctl: Controller }): ReactNode {
  const state = ctl.auth;
  return <section className="callout srv-player-auth" aria-label="학습 인증" hidden={!state.needed}>
    {state.needed ? <><h2>학습 인증</h2><p>{ctl.phase === "auth"
      ? "이 강의는 학습 전에 본인 인증이 필요합니다. 학교 계정으로 다시 로그인해 인증하면 이어서 학습할 수 있습니다."
      : "학습 인증 시간이 끝나 시간 적립을 멈췄습니다. 영상은 계속 재생됩니다. 다시 인증하면 지금 위치에서 적립을 이어 갑니다. 인증하지 않은 동안의 시간은 적립되지 않습니다."}</p>
      <div className="btn-row">{!state.challengeId
        ? <Button kind="primary" disabled={state.busy} aria-busy={state.busy} onClick={() => { void ctl.beginAuth(); }}>{state.busy ? "준비하는 중…" : "학습 인증 시작"}</Button>
        : <>
          {state.redirect ? <a className="btn btn-primary" href={state.redirect} target="_blank" rel="noopener">학교 계정으로 다시 인증 (새 탭)</a> : null}
          <Button kind={state.redirect ? "secondary" : "primary"} disabled={state.busy} aria-busy={state.busy} onClick={() => { void ctl.confirmAuth(); }}>{state.busy ? "확인하는 중…" : "인증을 마쳤습니다"}</Button>
          <Button kind="ghost" disabled={state.busy} onClick={() => { state.challengeId = null; void ctl.beginAuth(); }}>인증 다시 시작</Button>
        </>}
      </div>
      {state.note ? <p className={state.alert ? "err" : "tiny"} role={state.alert ? "alert" : "status"}>{state.note}</p> : null}
    </> : null}
  </section>;
}

function ReadyPlayer({ spec, data, item }: { spec: PlayerSpec; data: ActivityDetail; item: LectureItem }): ReactNode {
  const video = useRef<HTMLVideoElement>(null);
  const root = useRef<HTMLElement>(null);
  const controller = useRef<Controller | null>(null);
  const [, redraw] = useReducer((version: number) => version + 1, 0);
  const fullscreen = usePlayerFullscreen(root);
  const { kj, courseId, activityId, itemId } = spec;
  useEffect(() => {
    const media = video.current;
    if (!media) return;
    let live = true;
    if (current) current.leave("unload");
    const ctl = createController({ kj, courseId, activityId, itemId }, media, () => { if (live) redraw(); });
    ctl.item = item;
    current = ctl;
    controller.current = ctl;
    redraw();
    // Consume the visit only after this mount has survived React's effect replay.
    queueMicrotask(() => {
      if (live && media.isConnected && freshVisit("lecture-player", ctl.key) === "entry" && ctl.phase === "idle") void ctl.start();
    });
    const confirmOnReturn = () => {
      if (document.visibilityState === "visible" && ctl.auth.challengeId && !ctl.auth.busy) void ctl.confirmAuth();
    };
    window.addEventListener("focus", confirmOnReturn);
    document.addEventListener("visibilitychange", confirmOnReturn);
    return () => {
      live = false;
      window.removeEventListener("focus", confirmOnReturn);
      document.removeEventListener("visibilitychange", confirmOnReturn);
      ctl.leave("unload");
      if (controller.current === ctl) controller.current = null;
    };
  }, [kj, courseId, activityId, itemId]);
  useEffect(() => { if (controller.current) controller.current.item = item; }, [item]);
  const ctl = controller.current;
  const met = ctl?.progress?.status === "met";
  const phase = ctl?.phase || "idle";
  const seekTo = (seconds: number) => {
    const media = video.current;
    if (!media || !Number.isFinite(seconds)) return;
    const limits = [media.duration, item.durationSec].filter((limit): limit is number => typeof limit === "number" && Number.isFinite(limit) && limit >= 0);
    const upper = limits.length ? Math.min(...limits) : Infinity;
    media.currentTime = Math.max(0, Math.min(seconds, upper));
  };
  const player = <section ref={root} className="player-page is-open srv-player" data-item-open={`srv-${activityId}`}>
    <div className="player-top"><h1>{`[${data.weekNo}주] ${item.title}`}</h1>
      <span className={`attend-pill${met ? " ok" : ""}`}>{met ? "인정 기준 충족" : phase === "active" && ctl?.creditable ? "시간 적립 중" : phase === "active" ? "미리보기" : "학습 중 아님"}</span>
    </div>
    <div className="player-stage"><video ref={video} className="lecture-video srv-video" controls playsInline preload="metadata" aria-label={`${data.title} ${item.title}`} onDoubleClick={(event) => { event.preventDefault(); void togglePlayerFullscreen(root.current); }} /></div>
    <div className="player-chrome">
      <p className="tiny" role="status" aria-live="polite">{ctl ? statusText(ctl) : PHASE_TEXT.idle}</p>
      <p className={ctl?.alert ? "err" : "tiny"} role={ctl?.alert ? "alert" : "status"} aria-live="polite">{ctl?.notice || ""}</p>
      <div className="srv-player-progress">
        <p className="tiny">{ctl?.creditable === false ? "미리보기는 진도에 반영되지 않습니다." : progressLine(ctl?.progress)}</p>
        {ctl?.creditable === false ? null : <ProgressBar progress={ctl?.progress} />}
        {ctl?.creditUntil && ctl.creditable ? <p className="tiny">적립 마감 {koDateTime(ctl.creditUntil)}</p> : null}
        {ctl?.ended ? <p className="tiny">이번 종료로 {durationText(ctl.ended.creditedSec)}를 더 적립했습니다.</p> : null}
      </div>
      {ctl ? <AuthPanel ctl={ctl} /> : null}
      <div className="player-tools"><div className="btn-row">
        {phase === "active" ? <Button kind="primary" disabled={ctl?.ending} aria-busy={Boolean(ctl?.ending)} onClick={() => { void ctl?.finish(); }}>{ctl?.ending ? "종료하는 중…" : "학습 종료"}</Button>
          : RESTART_LABEL[phase] ? <Button kind="primary" disabled={!ctl} data-restart={phase} onClick={() => { void ctl?.start({ keepPosition: true }); }}>{RESTART_LABEL[phase]}</Button>
          : phase === "starting" ? <Button kind="primary" disabled aria-busy="true">시작하는 중…</Button> : null}
        <Link href={phase === "withdrawn" ? listHref(kj) : studentLectureHref(kj, activityId)} className="btn btn-secondary">{phase === "withdrawn" ? "학습활동 목록" : "강의 목록으로"}</Link>
        <button type="button" className="btn player-full" data-glyph={fullscreen ? "exit" : "enter"} aria-label={fullscreen ? "전체화면 종료" : "전체화면"} aria-pressed={fullscreen} onClick={() => { void togglePlayerFullscreen(root.current); }}><PlayerIcon name={fullscreen ? "exit" : "enter"} /></button>
      </div></div>
    </div>
  </section>;
  return videoStudy(courseById(kj), { id: `srv-${activityId}`, title: data.title }, player, seekTo, { server: true });
}

