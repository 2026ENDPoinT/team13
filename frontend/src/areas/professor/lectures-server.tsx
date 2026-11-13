// The professor's server video lectures (콘텐츠관리 `pf_contents` and the video type of 활동 등록
// `pf_week_add`, backend `ready`): register a lecture whose items are videos sent through the
// stream upload (progress and cancel per file), list every lecture from `GET …/lectures` (all pages,
// 100 at a time), watch each item's transcoding state (the list is read again with a growing delay
// while any item is `processing`), and edit (keep/add items) or delete with `If-Match`. The edit
// form reads that one lecture's detail when it opens, because the list has no description and a
// full update without it would clear it. Typed edits and running uploads live in memory per course
// and lecture, so a re-render or a 409 reload keeps them; a session change drops them.
import { useEffect, useRef, useState, type ReactNode } from "react";
import { ApiError, newRequestKey } from "../../shared/api.ts";
import { backendStatus, courseIdFor, roleIn, subscribe } from "../../shared/backend.ts";
import { SIGNED_OUT } from "../../shared/backend-prompt.tsx";
import { freshVisit, shownRows, useResource } from "../../shared/remote-cache.ts";
import { discardUpload, sizeText } from "../../shared/uploads-remote.tsx";
import {
  lectureCatalog, lectureDetails, lectureForEdit, lectureItemsOf, detailKey, courseKey, uploadLectureVideo, createLecture,
  updateLecture, deleteLecture, isStaleVersion, lectureMessage, serverTime, localInput, durationText, windowText, VIDEO_STATE,
  VIDEO_EXTENSIONS, type LectureEntry, type LectureItem, type ItemProgress, type RosterStudent,
} from "../../shared/lectures-remote.ts";
import { routeFor } from "../../shared/routes.ts";
import { koDateTime } from "../../shared/home-remote.ts";
import { lectureRoster, rosterKey, ROSTER_PAGE, lectureIdOf, lectureRef, PROGRESS_STATE } from "../../shared/lectures-remote.ts";
import { getState, render } from "../../store.ts";
import { Link } from "../../router.tsx";
import { Badge, Button, Field, EmptyState, LoadingState, ErrorState, Table, Pagination } from "../../ui/kit.tsx";
import type { Course } from "../../data.ts";
import type { ScreenCtx } from "../../types.ts";

type Id = number | string;

const MAX_ITEMS = 20;
const MAX_TITLE = 300;
const MAX_REQUIRED_MIN = 240;
const POLL_FIRST_MS = 3000;
const POLL_MAX_MS = 60000;
const ACCEPT = VIDEO_EXTENSIONS.map((ext) => `.${ext}`).join(",");

type UploadStatus = "sending" | "ready" | "failed" | "cancelled";

interface VideoUpload {
  local: number;
  name: string;
  size: number;
  file: File | null;
  total: number;
  loaded: number;
  title: string;
  requiredMin: string;
  state: UploadStatus;
  uploadId: number | null;
  message: string;
  key: string;
  controller: AbortController | null;
}

interface FormMemo {
  title: string;
  weekNo: string;
  description: string;
  openAt: string;
  closeAt: string;
  published: boolean;
  uploads: VideoUpload[];
  note: string;
  alert: boolean;
  busy: boolean;
  repaint: (() => void) | null;
}

interface LatestLecture {
  pending?: boolean;
  title?: string;
  weekNo?: number;
  version?: number;
  items?: string[];
  published?: boolean;
}

interface EditMemo extends FormMemo {
  open: boolean;
  keep: Map<number, { title: string; requiredMin: string }>;
  armed: boolean;
  latest: LatestLecture | null;
  version: number;
}

// "create:<courseId>" | "edit:<activityId>" -> form memory (fields, uploads, notes)
const drafts = new Map<string, FormMemo>();
const edits = new Map<string, EditMemo>();
const openEdits = new Set<number>(); // 수정 칸을 펼친 채로 열 강의(activityId)
interface Poller { timer: number | undefined; delay: number; signature: string; alive: () => boolean }
const pollers = new Map<Id, Poller>(); // courseId -> poll state
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  for (const memo of [...drafts.values(), ...edits.values()]) for (const entry of memo.uploads) if (entry.controller) entry.controller.abort();
  drafts.clear();
  edits.clear();
  openEdits.clear();
  for (const poll of pollers.values()) clearTimeout(poll.timer);
  pollers.clear();
});

const missing = (error: unknown) => error instanceof ApiError && error.status === 404;
const uncertain = (error: unknown) => error instanceof ApiError && [0, 502, 503, 504].includes(error.status) && error.code !== "aborted";
let localIds = 0;

function createMemo(weekNo: number | string | null | undefined): FormMemo {
  return { title: "", weekNo: String(weekNo || 1), description: "", openAt: "", closeAt: "", published: true, uploads: [], note: "", alert: false, busy: false, repaint: null };
}

/** 활동 등록에서 고른 주차를 이 과목의 새 강의 폼에 넣는다. 쓰는 중이거나 올린 영상이 있으면 그대로 둔다. */
export function presetLectureWeek(courseId: Id | null | undefined, weekNo: number | string): void {
  if (!courseId) return;
  const slotKey = `create:${courseId}`;
  const memo = drafts.get(slotKey);
  if (!memo) drafts.set(slotKey, createMemo(weekNo));
  else if (!memo.busy && !memo.title && !memo.uploads.length) memo.weekNo = String(weekNo);
}

function editMemo(lecture: LectureEntry): EditMemo {
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
    version: lecture.version,
    repaint: null,
  };
}

/** Re-renders the caller when `memo.repaint()` is called (uploads and late answers outlive the component). */
function useMemoRepaint(memo: FormMemo): () => void {
  const [, setTick] = useState(0);
  const repaint = () => setTick((n) => n + 1);
  useEffect(() => {
    const own = () => setTick((n) => n + 1);
    memo.repaint = own;
    return () => { if (memo.repaint === own) memo.repaint = null; };
  }, [memo]);
  return repaint;
}

function StateBadge({ state }: { state: string | undefined }) {
  return <Badge tone={state === "ready" ? "success" : state === "processing" ? "neutral" : "danger"}>{VIDEO_STATE[state || ""] || "재생할 수 없음"}</Badge>;
}

/** Minutes text → seconds, null for empty; `false` when invalid. */
function requiredSec(text: string): number | null | false {
  const raw = String(text || "").trim();
  if (!raw) return null;
  const minutes = Number(raw);
  if (!Number.isInteger(minutes) || minutes < 1 || minutes > MAX_REQUIRED_MIN) return false;
  return minutes * 60;
}

function checkFields(memo: FormMemo): string | null {
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

function addedItems(memo: FormMemo): { title: string; uploadId: number | null; requiredSec: number | null }[] | string {
  const out = [];
  for (const entry of memo.uploads) {
    const title = entry.title.trim();
    if (!title || title.length > MAX_TITLE) return `${entry.name}: 콘텐츠 제목은 1자 이상 300자 이하입니다.`;
    const required = requiredSec(entry.requiredMin);
    if (required === false) return `${entry.name}: 인정 기준 시간은 1분부터 ${MAX_REQUIRED_MIN}분 사이입니다.`;
    out.push({ title, uploadId: entry.uploadId, requiredSec: required });
  }
  return out;
}

function toast(text: string) {
  getState().toast = text;
  render();
}

// ---------- uploads ----------

const UPLOAD_TEXT: Record<UploadStatus, string> = { sending: "올리는 중", ready: "올림 완료", failed: "실패", cancelled: "취소함" };

function sendUpload(entry: VideoUpload, courseId: Id, memo: FormMemo) {
  if (!entry.file) return;
  entry.state = "sending";
  entry.loaded = 0;
  entry.message = "";
  const controller = new AbortController();
  entry.controller = controller;
  const repaint = () => { if (memo.repaint) memo.repaint(); };
  repaint();
  uploadLectureVideo(entry.file, courseId, {
    idempotencyKey: entry.key,
    signal: controller.signal,
    onProgress: (loaded, total) => {
      entry.loaded = loaded;
      entry.total = total || entry.size;
      repaint();
    },
  }).then((view) => {
    entry.controller = null;
    if (!memo.uploads.includes(entry)) {
      if (view && view.uploadId) void discardUpload(view.uploadId);
      return;
    }
    entry.uploadId = view ? view.uploadId : null;
    entry.state = "ready";
    entry.file = null; // the bytes are on the server now
    repaint();
  }, (error: unknown) => {
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

function Uploader({ memo, courseId, repaint }: { memo: FormMemo; courseId: Id; repaint: () => void }) {
  const disabled = memo.busy;
  return (
    <div className="stack">
      <Field label="영상 추가 (mp4, mov, webm, mkv · 2GB까지)">
        <input type="file" multiple accept={ACCEPT} aria-label="강의 영상 파일" disabled={disabled} onChange={(event) => {
          event.stopPropagation(); // the app-wide file listener would keep the name in saved sample state
          const picker = event.currentTarget;
          const picked = [...(picker.files || [])];
          picker.value = "";
          for (const file of picked) {
            const ext = (String(file.name || "").split(".").pop() || "").toLowerCase();
            const entry: VideoUpload = {
              local: (localIds += 1), name: String(file.name || "video"), size: file.size, file, total: file.size, loaded: 0,
              title: String(file.name || "").replace(/\.[^.]+$/, "").slice(0, MAX_TITLE), requiredMin: "",
              state: "failed", uploadId: null, message: "", key: newRequestKey(), controller: null,
            };
            memo.uploads.push(entry);
            if (!VIDEO_EXTENSIONS.includes(ext)) entry.message = "mp4, mov, webm, mkv 영상만 올릴 수 있습니다.";
            else sendUpload(entry, courseId, memo);
          }
          repaint();
        }} />
      </Field>
      <div className="stack pf-lecture-uploads" aria-live="polite">
        {memo.uploads.map((entry) => {
          const removeLabel = entry.state === "sending" ? "업로드 취소" : "빼기";
          const percent = entry.total ? Math.round((entry.loaded / entry.total) * 100) : 0;
          const again = entry.state === "failed" && entry.file && !entry.message.startsWith("mp4");
          const stateText = entry.state === "failed" || entry.state === "cancelled" ? [UPLOAD_TEXT[entry.state], entry.message].filter(Boolean).join(" · ") : UPLOAD_TEXT[entry.state];
          return (
            <div className="pf-lecture-upload" key={entry.local}>
              <p className="pf-sub"><b>{entry.name}</b>{` · ${sizeText(entry.size)} · ${stateText}`}</p>
              {entry.state === "sending" ? <progress max="100" value={percent} aria-label={`${entry.name} 업로드 진행률`}>{`${percent}%`}</progress> : null}
              <div className="pf-form">
                <Field label="콘텐츠 제목">
                  <input maxLength={MAX_TITLE} value={entry.title} aria-label={`${entry.name} 콘텐츠 제목`} disabled={disabled}
                    onChange={(event) => { entry.title = event.currentTarget.value; repaint(); }} />
                </Field>
                <Field label="인정 기준 시간(분, 비우면 영상 길이)">
                  <input type="number" min="1" max={String(MAX_REQUIRED_MIN)} value={entry.requiredMin} aria-label={`${entry.name} 인정 기준 시간(분)`} placeholder="영상 길이" disabled={disabled}
                    onChange={(event) => { entry.requiredMin = event.currentTarget.value; repaint(); }} />
                </Field>
              </div>
              <div className="pf-actions">
                {again ? <Button kind="secondary" aria-label={`${entry.name} 다시 올리기`} disabled={disabled} onClick={() => sendUpload(entry, courseId, memo)}>다시 올리기</Button> : null}
                <Button kind="ghost" aria-label={`${entry.name} ${removeLabel}`} disabled={disabled} onClick={() => {
                  if (entry.controller) entry.controller.abort();
                  memo.uploads = memo.uploads.filter((item) => item !== entry);
                  if (entry.uploadId) void discardUpload(entry.uploadId);
                  repaint();
                }}>{removeLabel}</Button>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

// ---------- shared field block ----------

function FieldBlock({ memo, prefix, repaint }: { memo: FormMemo; prefix: string; repaint: () => void }) {
  const disabled = memo.busy;
  const set = (name: "title" | "weekNo" | "description" | "openAt" | "closeAt", value: string) => {
    memo[name] = value;
    repaint();
  };
  return <>
    <div className="span-2"><Field label="강의 제목">
      <input maxLength={MAX_TITLE} value={memo.title} aria-label={`${prefix}강의 제목`} disabled={disabled} onChange={(event) => set("title", event.currentTarget.value)} />
    </Field></div>
    <Field label="주차">
      <input type="number" min="1" max="60" value={memo.weekNo} aria-label={`${prefix}주차`} disabled={disabled} onChange={(event) => set("weekNo", event.currentTarget.value)} />
    </Field>
    <label className="choice pf-check">
      <input type="checkbox" checked={memo.published} disabled={disabled} onChange={(event) => { memo.published = event.currentTarget.checked; repaint(); }} />
      <span>학생에게 공개</span>
    </label>
    <Field label="공개 시각 (비우면 바로)">
      <input type="datetime-local" value={memo.openAt} aria-label={`${prefix}공개 시각`} disabled={disabled} onChange={(event) => set("openAt", event.currentTarget.value)} />
    </Field>
    <Field label="학습 인정 종료 (비우면 제한 없음)">
      <input type="datetime-local" value={memo.closeAt} aria-label={`${prefix}학습 인정 종료`} disabled={disabled} onChange={(event) => set("closeAt", event.currentTarget.value)} />
    </Field>
    <div className="span-2"><Field label="설명">
      <textarea rows={3} maxLength={10000} value={memo.description} aria-label={`${prefix}설명`} disabled={disabled} onChange={(event) => set("description", event.currentTarget.value)} />
    </Field></div>
    <p className="pf-sub span-2">학생의 적립 시간이 콘텐츠의 인정 기준 시간 이상이면 그 콘텐츠의 인정 기준을 충족합니다. 출석은 온라인 출석 차시에 연결된 공개 강의의 콘텐츠를 모두 충족했을 때만 그 차시로 자동 인정됩니다. 그 차시는 그 주의 휴강하지 않은 유일한 온라인 차시이거나 강의가 직접 지정한 차시여야 하고, 출석부에 이미 정해진 출석은 바꾸지 않습니다. 그 밖에는 출석부 기록이 정합니다.</p>
  </>;
}

function StatusLine({ memo }: { memo: FormMemo }) {
  return <p className={memo.alert ? "err" : "pf-sub"} role={memo.alert ? "alert" : "status"} aria-live="polite">{memo.note}</p>;
}

/** True when the course's video lectures live on the server (`anon` shows the login strip instead of the sample form). */
export function serverVideoCourse(course: Course | null | undefined): boolean {
  const status = backendStatus();
  return Boolean(course) && (status === "anon" || (status === "ready" && Boolean(courseIdFor(course?.id))));
}

// ---------- create ----------

interface CreateOptions {
  onCreated?: (created: LectureEntry) => void;
  weekNo?: number | string;
}

/** The registration sheet for course `course` (also the video panel of 활동 등록). */
export function serverLectureCreate(course: Course, { onCreated, weekNo }: CreateOptions = {}): ReactNode {
  const courseId = courseIdFor(course.id);
  if (backendStatus() === "anon") return SIGNED_OUT;
  if (!courseId) return <p className="pf-sub">이 과목은 ASTRA 서버에 없습니다. 서버에 등록된 과목에서만 강의 영상을 올릴 수 있습니다.</p>;
  if (roleIn(course.id) !== "INSTRUCTOR") return <p className="pf-sub">이 과목을 담당하는 교수 계정으로 로그인해야 강의 영상을 등록할 수 있습니다.</p>;
  const slotKey = `create:${courseId}`;
  let memo = drafts.get(slotKey);
  if (!memo) {
    memo = createMemo(weekNo);
    drafts.set(slotKey, memo);
  }
  return <LectureCreate key={slotKey} slotKey={slotKey} memo={memo} courseId={courseId} onCreated={onCreated} />;
}

function LectureCreate({ slotKey, memo, courseId, onCreated }: { slotKey: string; memo: FormMemo; courseId: Id; onCreated?: (created: LectureEntry) => void }) {
  const repaint = useMemoRepaint(memo);
  const say = (text: string, alert = false) => {
    memo.note = text || "";
    memo.alert = alert;
    repaint();
  };
  const submit = async () => {
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
      // The parent re-renders with the fresh memo (`render()` in toast).
      drafts.set(slotKey, createMemo(memo.weekNo));
      void lectureCatalog.load(courseKey(courseId), courseId);
      if (onCreated) onCreated(created);
      toast("강의를 등록했습니다. 영상 변환이 끝나면 학생이 학습할 수 있습니다.");
    } catch (error) {
      memo.busy = false;
      say(lectureMessage(error), true);
    }
  };
  return (
    <div className="sheet pf-lecture-create">
      <h2>새 영상 강의</h2>
      <p className="pf-sub">영상은 올린 뒤 서버에서 HLS로 변환합니다. 변환이 끝나야 학생이 학습할 수 있고, 변환 상태는 아래 목록에 표시됩니다.</p>
      <div className="pf-form">
        <FieldBlock memo={memo} prefix="새 강의 " repaint={repaint} />
        <div className="span-2"><Uploader memo={memo} courseId={courseId} repaint={repaint} /></div>
        <div className="span-2"><StatusLine memo={memo} /></div>
        <div className="span-2"><div className="pf-actions">
          <Button kind="primary" aria-busy={memo.busy ? "true" : "false"} disabled={memo.busy} onClick={() => void submit()}>
            {memo.busy ? "등록하는 중…" : "ASTRA 서버에 등록"}
          </Button>
        </div></div>
      </div>
    </div>
  );
}

// ---------- list, polling, edit ----------

function processing(lecture: LectureEntry): boolean {
  return lectureItemsOf(lecture).some((item) => item.state === "processing");
}

/**
 * While any lecture has a `processing` item, reads the lecture list again (every page, one request for
 * up to 100 lectures) after 3s, 6s, 12s … up to 60s, while the list is on screen (`alive`). No per-lecture reads.
 */
function poll(courseId: Id, alive: () => boolean) {
  const key = courseKey(courseId);
  let state = pollers.get(courseId);
  const current = lectureCatalog.peek(key);
  const signature = current && Array.isArray(current.data) ? current.data.filter(processing).map((item) => `${item.activityId}:${item.version}`).join(",") : "";
  // New or edited lectures with processing items start over at the short delay.
  if (state && state.signature !== signature) {
    clearTimeout(state.timer);
    pollers.delete(courseId);
    state = undefined;
  }
  if (state) {
    state.alive = alive; // the list was drawn again; keep polling for the new one
    return;
  }
  if (!current || !Array.isArray(current.data) || !current.data.some(processing)) return;
  const own: Poller = { timer: undefined, delay: POLL_FIRST_MS, signature, alive };
  pollers.set(courseId, own);
  const tick = async () => {
    const latest = lectureCatalog.peek(key);
    const waiting = latest && Array.isArray(latest.data) ? latest.data.filter(processing) : [];
    if (!own.alive() || !waiting.length) {
      pollers.delete(courseId);
      return;
    }
    // The list read re-renders the list, which calls poll() again with the new signature.
    await lectureCatalog.load(key, courseId);
    if (pollers.get(courseId) !== own) return;
    own.delay = Math.min(POLL_MAX_MS, own.delay * 2);
    own.timer = window.setTimeout(tick, own.delay);
  };
  own.timer = window.setTimeout(tick, own.delay);
}

/** 콘텐츠관리: login strip, the registration sheet and the server lecture list of `course`. `open` 강의는 펼쳐 둔다. */
export function serverLectureManager(course: Course, { weekNo, open }: { weekNo?: number | string; open?: string | null } = {}): ReactNode {
  const status = backendStatus();
  if (status === "anon") return SIGNED_OUT;
  const courseId = courseIdFor(course.id);
  if (!courseId) return <EmptyState title="이 과목은 ASTRA 서버에 없습니다" body="서버에 등록된 과목을 강의실에서 고른 뒤 다시 열어 주세요." />;
  return (
    <div className="stack">
      <p className="pf-sub">{`과목: ${course.ko}. 다른 과목은 강의실에서 과목을 바꾼 뒤 열면 됩니다.`}</p>
      {serverLectureCreate(course, { weekNo })}
      <LectureList course={course} courseId={courseId} open={open} />
    </div>
  );
}

function LectureList({ course, courseId, open }: { course: Course; courseId: Id; open?: string | null }) {
  const instructor = roleIn(course.id) === "INSTRUCTOR";
  const key = courseKey(courseId);
  const load = () => { void lectureCatalog.load(key, courseId); };
  const entry = useResource(lectureCatalog, key);
  const [opened, setOpened] = useState(() => {
    const set = new Set<number>();
    // 강의실 주별 보기의 `수정`(`?open=<activityId>`)은 그 강의의 수정 칸을 펼친 채로 연다.
    const openId = Number(open);
    if (Number.isInteger(openId) && openId > 0) {
      set.add(openId);
      const memo = edits.get(`edit:${openId}`);
      if (memo) memo.open = true;
      else openEdits.add(openId);
    }
    return set;
  });
  const [retrying, setRetrying] = useState(false);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);
  useEffect(() => {
    if (freshVisit("pf-lectures", key) === "entry" || !lectureCatalog.peek(key)) load();
  }, [key]);
  useEffect(() => { if (!entry?.loading) setRetrying(false); }, [entry?.loading]);
  useEffect(() => {
    if (entry?.data) poll(courseId, () => mounted.current);
  });

  let body: ReactNode;
  // After a write the list reloads; its previous rows stay on screen for display only (no editor).
  const { rows: data, stale } = shownRows(entry);
  if (!data) {
    body = !entry || !entry.error
      ? <p className="pf-sub" role="status">서버에 등록된 강의를 불러오는 중입니다.</p>
      : <>
          <p className="err" role="alert">{lectureMessage(entry.error)}</p>
          <Button kind="ghost" disabled={retrying} onClick={() => { setRetrying(true); load(); }}>다시 불러오기</Button>
        </>;
  } else {
    const lectures = Array.isArray(data) ? data : [];
    body = <>
      {entry?.error ? <>
        <p className="err" role="alert">{lectureMessage(entry.error)}</p>
        {stale ? <Button kind="ghost" disabled={retrying} onClick={() => { setRetrying(true); load(); }}>다시 불러오기</Button> : null}
      </> : stale ? <p className="pf-sub" role="status">저장한 내용을 반영한 목록을 불러오는 중입니다.</p> : null}
      {lectures.length
        ? lectures.map((lecture) => (
          <LectureRowView key={lecture.activityId} courseId={courseId} lecture={lecture} instructor={instructor} stale={stale}
            open={opened.has(lecture.activityId)} reload={load}
            onToggle={(now) => {
              if (now === opened.has(lecture.activityId)) return;
              const next = new Set(opened);
              if (now) next.add(lecture.activityId);
              else next.delete(lecture.activityId);
              setOpened(next);
            }} />
        ))
        : <p className="pf-sub">아직 서버에 등록된 영상 강의가 없습니다.</p>}
    </>;
  }
  return (
    <section className="sheet" aria-label="ASTRA 서버의 영상 강의">
      <h2>서버에 등록된 영상 강의</h2>
      <p className="pf-sub">변환 중인 영상은 상태가 바뀔 때까지 자동으로 다시 확인합니다. 수정과 삭제는 마지막으로 불러온 버전을 기준으로 하고, 그 사이 다른 곳에서 고쳤으면 아무것도 바꾸지 않고 최신 내용을 불러옵니다.</p>
      <div className="stack" aria-live="polite">{body}</div>
    </section>
  );
}

function ItemMeta({ item, failText }: { item: LectureItem; failText: string }) {
  return <>
    <StateBadge state={item.state} />{" "}
    <span>{item.title}</span>
    <span className="pf-meta">{[item.durationSec != null ? ` · 길이 ${durationText(item.durationSec)}` : "", item.requiredSec != null ? ` · 인정 기준 시간 ${durationText(item.requiredSec)}` : ""].join("")}</span>
    {item.state === "failed" ? <span className="pf-meta">{failText}</span> : null}
  </>;
}

function LectureRowView({ courseId, lecture, instructor, stale, open, reload, onToggle }: {
  courseId: Id; lecture: LectureEntry; instructor: boolean; stale: boolean; open: boolean; reload: () => void; onToggle: (open: boolean) => void;
}) {
  const items = lectureItemsOf(lecture);
  return (
    <details className="pf-server-pdf pf-lecture-entry" open={open} onToggle={(event) => onToggle(event.currentTarget.open)}>
      <summary>
        <b>{lecture.title}</b>
        <span className="pf-meta">{` · ${lecture.weekNo}주차 · 콘텐츠 ${items.length}개${lecture.published ? "" : " · 비공개"}${processing(lecture) ? " · 변환 중" : ""}`}</span>
      </summary>
      <p className="pf-sub">{`학습 인정 기간: ${windowText(lecture)}`}</p>
      <ul className="pf-lecture-items">
        {items.map((item) => <li key={item.itemId}><ItemMeta item={item} failText=" · 변환에 실패했습니다. 이 콘텐츠를 빼고 영상을 다시 올리세요." /></li>)}
      </ul>
      {instructor && stale ? <p className="pf-sub">이 강의의 최신 내용을 불러온 뒤 수정할 수 있습니다.</p> : null}
      {instructor && !stale ? <LectureEditor courseId={courseId} lecture={lecture} reload={reload} /> : null}
    </details>
  );
}

function editMemoFor(lecture: LectureEntry): EditMemo {
  const slotKey = `edit:${lecture.activityId}`;
  let memo = edits.get(slotKey);
  if (!memo) {
    // 목록 항목에 설명(`description`)까지 있으므로 상세를 따로 읽지 않고 바로 채운다.
    memo = editMemo(lecture);
    if (openEdits.delete(lecture.activityId)) memo.open = true;
    edits.set(slotKey, memo);
  }
  return memo;
}

function LectureEditor({ courseId, lecture, reload }: { courseId: Id; lecture: LectureEntry; reload: () => void }) {
  const id = lecture.activityId;
  const slotKey = `edit:${id}`;
  const memo = editMemoFor(lecture);
  const repaint = useMemoRepaint(memo);
  const items = lectureItemsOf(lecture);
  // An item the server no longer has cannot be kept.
  for (const itemId of [...memo.keep.keys()]) if (!items.some((item) => item.itemId === itemId)) memo.keep.delete(itemId);
  const say = (text: string, alert = false) => {
    memo.note = text || "";
    memo.alert = alert;
    repaint();
  };
  // 409·428: keep what was typed, read the server's latest detail, and save against its version next time.
  const reloadKeeping = (message: string) => {
    memo.latest = { pending: true };
    memo.busy = false;
    memo.armed = false;
    say(message, true);
    reload();
    lectureForEdit(courseId, id, memo.version + 1).then((detail) => {
      if (edits.get(slotKey) !== memo) return;
      const latestItems = lectureItemsOf(detail);
      memo.version = detail.version;
      memo.latest = { title: detail.title, weekNo: detail.weekNo, version: detail.version, items: latestItems.map((item) => item.title), published: detail.published };
      for (const itemId of [...memo.keep.keys()]) if (!latestItems.some((item) => item.itemId === itemId)) memo.keep.delete(itemId);
    }, () => {
      if (edits.get(slotKey) === memo) memo.latest = null;
    }).finally(() => { if (memo.repaint) memo.repaint(); });
  };
  const submit = async () => {
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
      if (required === false) return say(`인정 기준 시간은 1분부터 ${MAX_REQUIRED_MIN}분 사이입니다.`, true);
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
      edits.delete(slotKey);
      toast("강의를 수정했습니다");
      reload();
    } catch (error) {
      if (isStaleVersion(error)) reloadKeeping(lectureMessage(error));
      else if (missing(error)) {
        edits.delete(slotKey);
        toast("이미 삭제된 강의입니다. 목록을 새로 불러왔습니다.");
        reload();
      } else {
        memo.busy = false;
        say(lectureMessage(error), true);
      }
    }
  };
  const destroy = async () => {
    if (memo.busy || (memo.latest && memo.latest.pending)) return;
    if (!memo.armed) {
      memo.armed = true;
      say("삭제하면 이 강의와 영상이 학생과 교수 모두에게서 바로 사라집니다. 학습 기록은 남습니다.", true);
      return;
    }
    memo.busy = true;
    repaint();
    try {
      // 저장과 같은 버전: 409·428 뒤에는 최신 상세의 버전이 `memo.version`에 들어 있고, 목록의 `lecture`는 낡았을 수 있다.
      await deleteLecture(courseId, id, memo.version);
      edits.delete(slotKey);
      toast("강의를 삭제했습니다. 학생 화면에서도 사라졌습니다.");
      reload();
    } catch (error) {
      if (isStaleVersion(error)) reloadKeeping("다른 곳에서 이 강의를 먼저 고쳤습니다. 최신 내용을 불러왔으니 확인한 뒤 다시 삭제하세요.");
      else if (missing(error)) {
        edits.delete(slotKey);
        toast("이미 삭제된 강의입니다. 목록을 새로 불러왔습니다.");
        reload();
      } else {
        memo.busy = false;
        memo.armed = false;
        say(lectureMessage(error), true);
      }
    }
  };
  const saving = memo.busy && !memo.armed;
  const deleting = memo.busy && memo.armed;
  return (
    <details className="pf-material-edit pf-lecture-edit" open={memo.open} aria-busy={memo.busy ? "true" : "false"}
      onToggle={(event) => { memo.open = event.currentTarget.open; }}>
      <summary><b>강의 수정·삭제</b><span className="pf-meta">{` · 버전 ${memo.version}`}</span></summary>
      <div className="pf-form">
        <FieldBlock memo={memo} prefix="" repaint={repaint} />
        <div className="span-2">
          <p className="pf-sub">콘텐츠 (체크를 풀면 저장할 때 이 강의에서 빠지고 그 영상은 지워집니다. 학습 중인 학생의 학습도 끝납니다)</p>
          <div className="stack">
            {items.map((item) => <KeepRow key={item.itemId} memo={memo} item={item} repaint={repaint} />)}
          </div>
        </div>
        <div className="span-2"><Uploader memo={memo} courseId={courseId} repaint={repaint} /></div>
        {memo.latest ? <div className="span-2"><LatestView latest={memo.latest} /></div> : null}
        <div className="span-2"><StatusLine memo={memo} /></div>
        <div className="span-2"><div className="pf-actions">
          <Button kind="primary" aria-busy={saving ? "true" : "false"} disabled={memo.busy} onClick={() => void submit()}>{saving ? "저장하는 중…" : "수정 저장"}</Button>
          {/* 409·428 뒤 최신 상세를 받는 동안은 보낼 버전이 아직 낡았으므로 삭제를 막는다. */}
          <Button kind="danger" aria-label={`${lecture.title} 삭제`} aria-busy={deleting ? "true" : "false"}
            disabled={memo.busy || Boolean(memo.latest && memo.latest.pending)} onClick={() => void destroy()}>
            {memo.armed ? (memo.busy ? "삭제하는 중…" : "한 번 더 누르면 삭제") : "강의 삭제"}
          </Button>
        </div></div>
      </div>
    </details>
  );
}

function KeepRow({ memo, item, repaint }: { memo: EditMemo; item: LectureItem; repaint: () => void }) {
  // Values typed while the item is unchecked stay here so checking it again keeps them.
  const [title, setTitle] = useState(() => memo.keep.get(item.itemId)?.title ?? item.title);
  const [required, setRequired] = useState(() => memo.keep.get(item.itemId)?.requiredMin ?? "");
  const kept = memo.keep.get(item.itemId);
  return (
    <div className="pf-lecture-upload">
      <label className="choice pf-check">
        <input type="checkbox" checked={Boolean(kept)} disabled={memo.busy} onChange={(event) => {
          if (event.currentTarget.checked) memo.keep.set(item.itemId, { title: title || item.title, requiredMin: required });
          else memo.keep.delete(item.itemId);
          repaint();
        }} />
        <span>유지 · <StateBadge state={item.state} />{` ${item.title}`}</span>
      </label>
      <div className="pf-form">
        <Field label="콘텐츠 제목">
          <input maxLength={MAX_TITLE} value={kept ? kept.title : title} aria-label={`${item.title} 콘텐츠 제목`} disabled={memo.busy || !kept}
            onChange={(event) => { setTitle(event.currentTarget.value); if (kept) kept.title = event.currentTarget.value; }} />
        </Field>
        <Field label="인정 기준 시간(분)">
          <input type="number" min="1" max={String(MAX_REQUIRED_MIN)} value={kept ? kept.requiredMin : required} placeholder="영상 길이" aria-label={`${item.title} 인정 기준 시간(분)`} disabled={memo.busy || !kept}
            onChange={(event) => { setRequired(event.currentTarget.value); if (kept) kept.requiredMin = event.currentTarget.value; }} />
        </Field>
      </div>
    </div>
  );
}

function LatestView({ latest }: { latest: LatestLecture }) {
  if (latest.pending) return <p className="pf-sub">서버의 최신 내용을 불러오는 중입니다.</p>;
  const items = latest.items || [];
  return (
    <div className="pf-server-pdf">
      <p className="pf-sub"><b>서버의 최신 내용</b>{` · 버전 ${latest.version}`}</p>
      <p className="pf-sub">{`${latest.title} · ${latest.weekNo}주차 · ${latest.published ? "공개" : "비공개"}`}</p>
      <p className="pf-sub">{items.length ? `콘텐츠: ${items.join(", ")}` : "콘텐츠 없음"}</p>
    </div>
  );
}

// ---------- 강의 미리보기 (`pf_lecture?n=srv-<activityId>`) ----------

const pfHref = (screenId: string, extra?: Record<string, string>) => routeFor("professor", "screen", { screenId, ...(extra || {}) });

function PlainText({ text }: { text: string }) {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  return (
    <div className="pf-prose">
      {blocks.map((block, index) => (
        <p key={index}>{block.split("\n").map((line, at) => <span key={at}>{at ? <br /> : null}{line}</span>)}</p>
      ))}
    </div>
  );
}

function ReloadButton({ load, loading }: { load: () => void; loading: boolean }) {
  const [pressed, setPressed] = useState(false);
  useEffect(() => { if (!loading) setPressed(false); }, [loading]);
  return <Button kind="ghost" disabled={pressed} onClick={() => { setPressed(true); load(); }}>다시 불러오기</Button>;
}

/**
 * `pf_lecture`(서버 과목): `?n=srv-<activityId>`의 강의 상세(콘텐츠와 영상 상태)와 수강생별 진도.
 * 진도는 `GET …/lecture-progress/students?activityId=&page=&size=`로 이 강의의 열만, 20명씩 서버가 나눈 쪽으로 받는다(`?page=`).
 */
export function serverLectureDetail(ctx: ScreenCtx, course: Course): ReactNode {
  if (backendStatus() === "anon") return SIGNED_OUT;
  const courseId = courseIdFor(course.id);
  if (!courseId) return <EmptyState title="이 과목은 ASTRA 서버에 없습니다" body="서버에 등록된 과목의 강의만 볼 수 있습니다." />;
  if (roleIn(course.id) !== "INSTRUCTOR") return <div className="stack"><p className="pf-sub">이 과목을 담당하는 교수 계정으로 로그인해야 수강생 진도를 볼 수 있습니다.</p></div>;
  const activityId = lectureIdOf(ctx.params && ctx.params.get("n"));
  if (!activityId) {
    return (
      <div className="stack">
        <p className="pf-sub">강의실 주별 보기나 아래 목록에서 강의를 고르세요.</p>
        <LectureList course={course} courseId={courseId} />
      </div>
    );
  }
  const page = Math.max(1, Math.floor(Number(ctx.params && ctx.params.get("page"))) || 1);
  return (
    <div className="stack">
      <LectureHead course={course} courseId={courseId} activityId={activityId} />
      <RosterSection course={course} courseId={courseId} activityId={activityId} page={page} />
    </div>
  );
}

function LectureHead({ course, courseId, activityId }: { course: Course; courseId: Id; activityId: number }) {
  const key = detailKey(courseId, activityId);
  const load = () => { void lectureDetails.load(key, courseId, activityId); };
  const entry = useResource(lectureDetails, key, load);
  useEffect(() => { void lectureDetails.visit("pf-lecture-detail", key, courseId, activityId); });
  const roomLink = <Link href={pfHref("pf_room_week", { kj: course.id })} className="btn btn-secondary">강의실</Link>;
  let body: ReactNode;
  const data = entry && entry.data;
  if (!data) {
    if (!entry || !entry.error) body = <LoadingState title="강의를 불러오는 중입니다" />;
    else if (missing(entry.error)) body = <EmptyState title="강의를 찾을 수 없습니다" body="이 과목의 강의가 아니거나 삭제되었습니다." actions={roomLink} />;
    else body = <ErrorState title="강의를 불러오지 못했습니다" body={lectureMessage(entry.error)} actions={<ReloadButton load={load} loading={Boolean(entry.loading)} />} />;
  } else if (data.type && data.type !== "lecture_weeks") {
    body = <EmptyState title="영상 강의가 아닙니다" body="강의실 주별 보기에서 다시 고르세요." actions={roomLink} />;
  } else {
    const items = lectureItemsOf(data);
    body = <>
      <h2>{data.title}</h2>
      <p className="pf-sub">{`${data.weekNo}주차 · ${data.published ? "공개" : "비공개"} · 학습 인정 기간 ${windowText(data)} · 버전 ${data.version}`}</p>
      {data.description ? <PlainText text={data.description} /> : null}
      <h3>{`콘텐츠 ${items.length}개`}</h3>
      {items.length
        ? <ul className="pf-lecture-items">{items.map((item) => <li key={item.itemId}><ItemMeta item={item} failText=" · 변환에 실패했습니다. 콘텐츠관리에서 이 콘텐츠를 빼고 다시 올리세요." /></li>)}</ul>
        : <p className="pf-sub">콘텐츠가 없습니다.</p>}
      <div className="pf-actions">
        <Link href={pfHref("pf_contents", { kj: course.id, open: String(activityId) })} className="btn btn-secondary">수정·삭제</Link>
        <Link href={pfHref("pf_attend", { kj: course.id })} className="btn btn-ghost">출석부</Link>
      </div>
    </>;
  }
  return <section className="sheet" aria-label="강의 정보">{body}</section>;
}

/** A field of a roster cell (`ItemProgress` keeps extra server fields as `unknown`). */
const cellText = (p: ItemProgress, field: string): string => (p[field] == null ? "" : String(p[field]));

function RosterSection({ course, courseId, activityId, page }: { course: Course; courseId: Id; activityId: number; page: number }) {
  const key = rosterKey(courseId, activityId, page);
  const load = () => { void lectureRoster.load(key, courseId, activityId, page); };
  const entry = useResource(lectureRoster, key, load);
  useEffect(() => { void lectureRoster.visit("pf-lecture-roster", key, courseId, activityId, page); });
  const href = (n: number) => pfHref("pf_lecture", n > 1 ? { kj: course.id, n: lectureRef(activityId), page: String(n) } : { kj: course.id, n: lectureRef(activityId) });
  const head = <h2>수강생별 진도</h2>;
  const data = entry && entry.data;
  let body: ReactNode;
  if (!data) {
    if (!entry || !entry.error) body = <LoadingState title="수강생 진도를 불러오는 중입니다" />;
    else if (missing(entry.error)) body = <EmptyState title="수강생 진도를 볼 수 없습니다" body="이 과목을 담당하는 교수만 볼 수 있고, 영상 강의가 아니면 진도가 없습니다." />;
    else body = <ErrorState title="수강생 진도를 불러오지 못했습니다" body={lectureMessage(entry.error)} actions={<ReloadButton load={load} loading={Boolean(entry.loading)} />} />;
  } else {
    // 서버가 이 강의의 콘텐츠만 열로 준다.
    const columns = (Array.isArray(data.items) ? data.items : []).map((item, index) => ({ item, index }));
    const students = Array.isArray(data.students) ? data.students : [];
    const total = Math.max(0, Number(data.totalStudents) || 0);
    const size = Number(data.size) || ROSTER_PAGE;
    const pages = Math.max(1, Math.ceil(total / size));
    if (!columns.length) body = <p className="pf-sub">이 강의에는 진도를 셀 콘텐츠가 없습니다.</p>;
    else if (!total) body = <EmptyState title="수강생이 없습니다" body="수강이 확정된 학생이 생기면 진도가 여기에 모입니다." />;
    else if (!students.length) {
      body = <EmptyState title="이 쪽에는 학생이 없습니다" body={`수강생 ${total}명은 ${pages}쪽까지 있습니다.`}
        actions={<Link href={href(pages)} className="btn btn-secondary">마지막 쪽</Link>} />;
    } else {
      const cellOf = (student: RosterStudent, index: number) => (Array.isArray(student.items) ? student.items[index] : null) || null;
      const percentOf = (p: ItemProgress | null) => Number(p ? p.progressPercent : 0) || 0;
      const met = (student: RosterStudent) => columns.filter(({ index }) => cellOf(student, index)?.status === "met").length;
      const all = students.filter((student) => met(student) === columns.length).length;
      const average = Math.round(students.reduce((sum, student) => sum + columns.reduce((s, { index }) => s + percentOf(cellOf(student, index)), 0) / columns.length, 0) / students.length);
      const first = (page - 1) * size + 1;
      const headers = ["학번", "이름", ...columns.map(({ item }) => item.title), "인정 기준 충족"];
      const rows: ReactNode[][] = students.map((student) => [
        student.studentNo || "—",
        student.name || "이름 없음",
        ...columns.map(({ index }) => {
          const p = cellOf(student, index);
          if (!p) return "—";
          const status = cellText(p, "status");
          const attendedAt = cellText(p, "attendedAt");
          return (
            <span>
              <b>{`${cellText(p, "progressPercent")}%`}</b>{" "}
              <Badge tone={status === "met" ? "success" : status === "under_required" ? "danger" : "neutral"}>{PROGRESS_STATE[status] || status}</Badge>
              <span className="pf-meta">{` · ${durationText(p.creditedSec)}${p.requiredSec != null ? ` / ${durationText(p.requiredSec)}` : ""}${attendedAt ? ` · ${koDateTime(attendedAt)} 충족` : ""}`}</span>
            </span>
          );
        }),
        `${met(student)}/${columns.length}`,
      ]);
      body = <>
        <div className="stat-row cols-3">
          <div className="stat pf-stat"><span className="tiny">수강생</span><b>{String(total)}</b></div>
          <div className="stat pf-stat"><span className="tiny">이 쪽 모두 인정 기준 충족</span><b>{`${all}명`}</b></div>
          <div className="stat pf-stat"><span className="tiny">이 쪽 평균 진도</span><b>{`${average}%`}</b></div>
        </div>
        <p className="pf-sub">{`적립 시간은 서버가 학습 인정 기간 안에서만 셉니다. ${total}명 가운데 ${first}–${first + students.length - 1}번째입니다.`}</p>
        <Table headers={headers} rows={rows} layout="scroll" />
        <Pagination page={Math.min(page, pages)} pages={pages} hrefForPage={href} />
      </>;
    }
  }
  return <section className="sheet" aria-label="수강생별 진도">{head}{body}</section>;
}
