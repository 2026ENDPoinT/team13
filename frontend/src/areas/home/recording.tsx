import { useEffect, useRef, useState, type ReactNode } from "react";
import { courseById, type Course } from "../../data.ts";
import { hrefFor, find } from "../../manifest.ts";
import type { ScreenCtx } from "../../types.ts";
import { getState, render, toast } from "../../store.ts";
import { Link, navigate } from "../../router.tsx";
import { showErrors } from "../../forms.ts";
import { Icon } from "../../ui/icon.tsx";
import { Button, ErrorState, LoadingState } from "../../ui/kit.tsx";
import { ApiError } from "../../shared/api.ts";
import {
  activeRecording, getRecording, importRecording, formatDuration, recordingExtension, onRecordingEvent,
  startRecording, pauseRecording, resumeRecording, stopRecording, deleteRecording, moveRecordingToAccount, WARN_SHARE,
  unsavedAudio, onUnsavedChange, forgetUnsaved,
  type ActiveRecording, type LocalRecording, type RecordingRecord, type RecordingSource,
} from "../../shared/course-recordings.ts";
import { backendState, backendSettled } from "../../shared/backend.ts";
import { useStudyRoomStore, leftByOthers } from "../../shared/study-folders.ts";
import { saveBlob } from "../../shared/uploads-remote.tsx";
import { serverStudyCourse, studyRoomOnServer, peekCourseView, studyRoomMessage, formatBytes, usageText } from "../../shared/study-room-remote.ts";
import { folderHref } from "./study-files.tsx";
import { serverStudyLecture } from "./study-server.tsx";

const SOURCES: { id: RecordingSource; label: string; hint: string }[] = [
  { id: "mic", label: "마이크", hint: "음성 메모나 강의실 수업을 녹음해요." },
  { id: "tab", label: "브라우저 탭", hint: "온라인 강의·Zoom 탭을 고르고 ‘탭 오디오도 공유’를 켜 주세요." },
  { id: "audio", label: "오디오 파일 업로드", hint: "이미 녹음해 둔 MP3·M4A 파일을 올려요." },
];
const dateTime = new Intl.DateTimeFormat("ko-KR", { month: "long", day: "numeric", hour: "2-digit", minute: "2-digit" });
// How often a recording the server is still scanning is asked for again.
const SCAN_POLL_MS = 4000;

const messageOf = (error: unknown) => (error instanceof Error ? error.message : String(error));

/** Last start/stop failure, shown once by the next source choices; `errorVersion` remounts them to show it. */
let lastError = "";
let errorVersion = 0;
export function setRecordingError(message: string): void {
  lastError = message;
  errorVersion += 1;
}

const sourceLabel = (source: string | undefined) => SOURCES.find((item) => item.id === source)?.label || "마이크";

function SourceIcon({ source }: { source: RecordingSource }) {
  if (source === "mic") return <Icon name="mic" />;
  return (
    <span className="ico">
      <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinejoin="round" strokeLinecap="round" aria-hidden="true">
        {source === "tab"
          ? <><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M3 9h18M7 6.5h.01M10 6.5h.01" /><path d="m10 12 5 3-5 3Z" /></>
          : <path d="M12 15V3m-4 4 4-4 4 4M4 14v5a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-5" />}
      </svg>
    </span>
  );
}

/** Where the audio is (or will be) stored, in the sentence the screens show. */
const storedWhere = (onServer: boolean) => (onServer ? "ASTRA 서버의 내 학습실에 저장되며 나만 들을 수 있습니다" : "이 브라우저에만 저장됩니다");

function TranscriptNote({ source, onServer }: { source?: RecordingSource; onServer: boolean }) {
  if (source === "audio") {
    return (
      <div className="rec-transcript-status" role="status">
        <strong>전사 준비 · 목업</strong>
        <p>오디오 파일이 저장되어 재생할 수 있습니다. 전사 API는 연결되지 않아 실제 전사 결과는 생성되지 않습니다.</p>
      </div>
    );
  }
  return <p className="rec-note">{`전사(글자 변환)는 아직 연결되지 않았습니다. 녹음 파일은 ${storedWhere(onServer)}.`}</p>;
}

// MediaRecorder WebM files carry no duration, so the player shows 0:00 and cannot seek.
// Seeking far past the end once makes the browser scan the file and learn the real length.
function PlayableAudio({ url, title }: { url: string; title: string }) {
  const checked = useRef(false);
  return (
    <audio
      className="rec-audio" controls preload="metadata" src={url} aria-label={`${title} 재생`}
      onLoadedMetadata={(event) => {
        if (checked.current) return;
        checked.current = true;
        const audio = event.currentTarget;
        if (Number.isFinite(audio.duration)) return;
        audio.addEventListener("durationchange", () => { audio.currentTime = 0; }, { once: true });
        audio.currentTime = Number.MAX_SAFE_INTEGER;
      }}
    />
  );
}

/** Server study room of the course: usage against the quota, and a warning when no new recording fits. */
function ServerUsage({ courseId }: { courseId: number | string }) {
  useStudyRoomStore();
  const { view } = peekCourseView(courseId);
  const usage = view && view.usage;
  if (!usage) return null;
  const full = usage.usedBytes >= usage.quotaBytes
    ? "서버의 녹음 저장 공간이 가득 찼습니다. 새 녹음은 서버에 저장되지 않고 이 브라우저에 보관됩니다. 오래된 녹음을 지워 공간을 비우세요."
    : usage.recordingCount >= usage.maxRecordings
      ? `녹음은 ${usage.maxRecordings}개까지 저장할 수 있습니다. 새 녹음은 서버에 저장되지 않고 이 브라우저에 보관됩니다. 오래된 녹음을 지우세요.`
      : "";
  return (
    <>
      <p className="rec-note">{`${usageText(usage)} · 녹음 ${usage.recordingCount}/${usage.maxRecordings}개${usage.maxFileBytes ? ` · 한 파일 최대 ${formatBytes(usage.maxFileBytes)}` : ""}`}</p>
      {full ? <p className="err" role="alert">{full}</p> : null}
    </>
  );
}

/** Picking a source acts at once: mic and tab start recording, a chosen audio file is stored and opened. */
function SetupView({ course }: { course: Course }) {
  const [error, setError] = useState(lastError);
  // While one source connects or saves, every card is locked and that card shows `text` in place of its hint.
  const [locked, setLocked] = useState<{ source: RecordingSource; text: string } | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const server = serverStudyCourse(course.id);
  useEffect(() => {
    lastError = "";
  }, []);

  const start = (source: "mic" | "tab") => {
    setLocked({ source, text: "녹음 연결 중…" });
    // Called inside the click: browsers open the mic prompt or tab picker only for a user gesture.
    startRecording({ courseId: course.id, source })
      .catch((failure: unknown) => setRecordingError(messageOf(failure)))
      .then(() => render());
  };

  const onFile = async () => {
    const input = fileInput.current;
    const file = input?.files?.[0];
    if (input) input.value = "";
    if (!file) return;
    setError("");
    setLocked({ source: "audio", text: server != null ? "서버에 올리는 중…" : "파일 저장 중…" });
    try {
      // The "saved" event in the recorder screen opens the stored file.
      await importRecording({ courseId: course.id, file });
    } catch (failure) {
      setLocked(null);
      setError(messageOf(failure));
    }
  };

  return (
    <div className="rec-panel">
      <div className="rec-source-grid">
        {SOURCES.map((source) => (
          <button
            key={source.id} type="button" className="rec-source-card" data-source={source.id} disabled={Boolean(locked)}
            aria-busy={locked && locked.source === source.id ? "true" : undefined}
            onClick={() => (source.id === "audio" ? fileInput.current?.click() : start(source.id))}
          >
            <span className={`rec-source-icon rec-source-icon-${source.id}`} aria-hidden="true"><SourceIcon source={source.id} /></span>
            <strong>{source.label}</strong>
            <span className="rec-source-hint">{locked && locked.source === source.id ? locked.text : source.hint}</span>
          </button>
        ))}
      </div>
      <p className="err" role="alert" hidden={!error}>{error}</p>
      <UnsavedList />
      <p className="rec-note">{`녹음 전 교수님의 허락을 받아 주세요. 오디오는 ${storedWhere(server != null)}. 자동 전사는 연결되지 않았습니다.`}</p>
      {server != null ? <ServerUsage courseId={server} /> : null}
      <input
        ref={fileInput} type="file" accept="audio/*,.mp3,.m4a,.wav,.ogg,.opus,.webm,.weba,.aac,.flac" hidden aria-label="저장할 오디오 파일 선택"
        onChange={() => void onFile()}
      />
    </div>
  );
}

/** Recordings that neither this browser's storage nor the server took: in this page's memory until saved as a file. */
function UnsavedList() {
  const [, setVersion] = useState(0);
  useEffect(() => onUnsavedChange(() => setVersion((n) => n + 1)), []);
  const items = unsavedAudio();
  if (!items.length) return null;
  return (
    <div className="rec-transcript-status" role="alert">
      <strong>아직 저장되지 않은 녹음</strong>
      <p>브라우저 저장소와 서버 모두에 저장하지 못한 녹음입니다. 이 페이지를 닫으면 사라지니 파일로 내려받으세요.</p>
      {items.map((item) => (
        <div key={item.id} className="btn-row">
          <span>{item.title}</span>
          <Button kind="secondary" onClick={() => saveBlob(item.blob, item.fileName)}>내려받기</Button>
          <Button kind="ghost" onClick={() => forgetUnsaved(item.id)}>내려받았으면 목록에서 지우기</Button>
        </div>
      ))}
    </div>
  );
}

function LiveView({ live }: { live: ActiveRecording }) {
  const [now, setNow] = useState(live);
  const [saving, setSaving] = useState(false);
  useEffect(() => {
    const tick = window.setInterval(() => {
      const next = activeRecording();
      if (!next) {
        window.clearInterval(tick);
        return;
      }
      setNow(next);
    }, 500);
    return () => window.clearInterval(tick);
  }, []);
  const onServer = serverStudyCourse(live.courseId) != null && !live.browserOnly;
  const near = now.limitBytes != null && now.bytes >= now.limitBytes * WARN_SHARE;
  return (
    <div className={`rec-panel rec-live${live.paused ? " paused" : ""}`}>
      <div className="rec-state">
        <span className="rec-dot" aria-hidden="true" />
        <span>{`${live.paused ? "일시정지" : "녹음 중"} · ${sourceLabel(live.source)}`}</span>
      </div>
      <strong className="rec-clock">{formatDuration(now.elapsedMs)}</strong>
      {now.limitBytes ? <p className="rec-note">{`파일 크기 ${formatBytes(now.bytes)} / 서버 한도 ${formatBytes(now.limitBytes)}`}</p> : null}
      {near ? <p className="err" role="alert">곧 서버의 파일 크기 한도에 닿습니다. 한도에 닿으면 녹음을 멈추고 저장합니다. 이어서 녹음하려면 그 뒤에 새로 시작하세요.</p> : null}
      <div className="btn-row">
        {live.paused
          ? <Button kind="secondary" onClick={() => { resumeRecording(); render(); }}>다시 시작</Button>
          : <Button kind="secondary" onClick={() => { pauseRecording(); render(); }}>일시정지</Button>}
        <Button
          kind="primary" disabled={saving}
          onClick={() => {
            setSaving(true);
            // The recorder screen follows the "saved" or "error" event to the next view.
            stopRecording().catch(() => {});
          }}
        >
          {saving ? "저장 중…" : "중지하고 저장"}
        </Button>
      </div>
      <p className="rec-note">다른 화면으로 이동해도 녹음은 계속됩니다. 저장하기 전에 이 탭을 닫으면 녹음이 사라집니다.</p>
      {live.browserOnly && studyRoomOnServer()
        ? <p className="rec-note">이 브라우저에만 있는 폴더에서 시작한 녹음이라 이 브라우저에만 저장됩니다. 서버로 옮기려면 저장한 뒤 ‘내 계정으로 옮기기’를 누르세요.</p>
        : null}
      <TranscriptNote onServer={onServer} />
    </div>
  );
}

function DeleteButton({ record, course, onStale }: { record: RecordingRecord; course: Course | null; onStale: () => void }) {
  const [armed, setArmed] = useState(false);
  const [deleting, setDeleting] = useState(false);
  return (
    <Button
      kind="ghost" disabled={deleting}
      onClick={() => {
        if (!armed) {
          setArmed(true);
          return;
        }
        setDeleting(true);
        deleteRecording(record).then(() => {
          getState().toast = "녹음을 삭제했습니다";
          navigate(course ? folderHref(course.id) : hrefFor(find("study_home")));
        }, (error: unknown) => {
          setDeleting(false);
          setArmed(false);
          showErrors({ "rec-delete": messageOf(error) });
          // Changed elsewhere (409 version_conflict): read it again so the next press sends the new version.
          const cause = error instanceof Error ? error.cause : null;
          if (cause instanceof ApiError && cause.code === "version_conflict") onStale();
        });
      }}
    >
      {armed ? "한 번 더 누르면 삭제" : "삭제"}
    </Button>
  );
}

/** A recording still only in this browser while the study room is on the server: why, and the explicit move. */
function MoveToAccount({ record, course }: { record: LocalRecording; course: Course }) {
  const [moving, setMoving] = useState(false);
  const [error, setError] = useState(record.uploadError || "");
  const name = backendState().me?.name;
  const account = `지금 로그인한 계정${name ? `(${name})` : ""}`;
  const others = leftByOthers(record.ownerUserId);
  return (
    <div className="rec-transcript-status" role="note">
      <strong>{others ? "이 브라우저에 남아 있던 녹음" : "이 브라우저에만 저장된 녹음"}</strong>
      <p>
        {others
          ? `로그아웃한 상태였거나 다른 계정으로 로그인했을 때 이 브라우저에 저장된 녹음입니다. 이 컴퓨터를 함께 쓰는 다른 사람이 남긴 것일 수 있습니다. 내 녹음이 맞을 때만 옮기세요. 옮기면 ${account}의 학습실에 저장되고, 서버가 받은 뒤에만 이 브라우저에서 지워집니다.`
          : `옮기면 ${account}의 학습실에 저장되고, 서버가 받은 뒤에만 이 브라우저에서 지워집니다.`}
      </p>
      {error ? <p className="err" role="alert">{`지난번에 옮기지 못한 이유: ${error}`}</p> : null}
      <Button
        kind="secondary" disabled={moving}
        onClick={() => {
          setMoving(true);
          moveRecordingToAccount(record).then((result) => {
            if (result.error || !result.record.remote) {
              setMoving(false);
              setError(result.error || "");
              return;
            }
            toast("녹음을 내 계정으로 옮겼습니다");
            navigate(hrefFor(find("study_lecture"), { kj: course.id, rec: result.record.id }));
          }, (failure: unknown) => {
            setMoving(false);
            setError(messageOf(failure));
          });
        }}
      >
        {moving ? "옮기는 중…" : error ? (others ? "다시 옮기기" : "다시 올리기") : "내 계정으로 옮기기"}
      </Button>
    </div>
  );
}

type Playback = { kind: "loading" } | { kind: "missing" } | { kind: "failed"; message: string } | { kind: "ready"; record: RecordingRecord };

function PlaybackView({ course, id }: { course: Course | null; id: string }) {
  const kj = course ? course.id : "";
  const [playback, setPlayback] = useState<Playback>({ kind: "loading" });
  const [url, setUrl] = useState("");
  const [reads, setReads] = useState(0);
  // Read once the backend connection settled, and again when the study room moves to the server.
  const onServer = studyRoomOnServer();
  const settled = backendSettled();
  useEffect(() => {
    if (!settled) return;
    let live = true;
    getRecording(id).then((record) => {
      if (live) setPlayback(record && record.courseId === kj ? { kind: "ready", record } : { kind: "missing" });
    }).catch((error: unknown) => {
      if (live) setPlayback({ kind: "failed", message: error instanceof ApiError ? studyRoomMessage(error) : "" });
    });
    return () => {
      live = false;
    };
  }, [id, kj, reads, onServer, settled]);
  const record = playback.kind === "ready" ? playback.record : null;
  useEffect(() => {
    if (!record) return;
    if (record.remote) {
      setUrl(record.state === "ready" ? record.url : "");
      return;
    }
    const next = URL.createObjectURL(record.blob);
    setUrl(next);
    return () => URL.revokeObjectURL(next);
  }, [record]);
  // A recording the server is still scanning is read again until its state changes.
  const scanning = Boolean(record && record.remote && record.state === "scanning");
  useEffect(() => {
    if (!scanning) return;
    const timer = window.setTimeout(() => setReads((n) => n + 1), SCAN_POLL_MS);
    return () => window.clearTimeout(timer);
  }, [scanning, reads]);

  if (playback.kind === "loading") {
    return <div className="rec-panel" aria-busy="true"><LoadingState title="녹음을 불러오는 중입니다" /></div>;
  }
  if (playback.kind === "missing") {
    return (
      <div className="rec-panel">
        <ErrorState
          title="녹음을 찾을 수 없습니다" body={!course || serverStudyCourse(course.id) != null ? "삭제되었거나 이 과목의 녹음이 아닙니다." : "삭제되었거나 다른 브라우저에서 만든 녹음입니다."}
          actions={<Link href={course ? folderHref(course.id) : hrefFor(find("study_home"))} className="btn btn-secondary">{course ? "폴더로" : "학습실로"}</Link>}
        />
      </div>
    );
  }
  if (playback.kind === "failed") {
    return (
      <div className="rec-panel">
        <ErrorState
          title="녹음을 불러오지 못했습니다"
          body={playback.message || "이 브라우저의 저장소에 접근할 수 없습니다. 사이트 데이터 접근 설정을 확인해 주세요."}
          actions={<Button kind="ghost" onClick={() => setReads((n) => n + 1)}>다시 불러오기</Button>}
        />
      </div>
    );
  }
  const ready = playback.record;
  const state = ready.remote ? ready.state : "ready";
  const fileName = ready.fileName || `${ready.title.replace(/[\\/:*?"<>|]/g, "-")}.${recordingExtension(ready.mimeType)}`;
  return (
    <div className="rec-panel">
      <h2 className="rec-title">{ready.title}</h2>
      <p className="rec-meta">{`${sourceLabel(ready.source)} · ${formatDuration(ready.durationMs)} · ${dateTime.format(new Date(ready.createdAt))}`}</p>
      {state === "scanning" ? <p className="rec-note" role="status">바이러스 검사 중입니다. 검사가 끝나면 재생하고 내려받을 수 있습니다.</p> : null}
      {state === "rejected" ? <p className="err" role="alert">검사에서 악성 코드가 발견되어 재생하거나 내려받을 수 없습니다. 이 녹음을 삭제하세요.</p> : null}
      {course && !ready.remote && serverStudyCourse(course.id) != null ? <MoveToAccount record={ready} course={course} /> : null}
      {url ? <PlayableAudio url={url} title={ready.title} /> : null}
      <div className="btn-row">
        {url ? <a className="btn btn-secondary" href={url} download={fileName}>다운로드</a> : null}
        <DeleteButton record={ready} course={course} onStale={() => setReads((n) => n + 1)} />
      </div>
      <TranscriptNote source={ready.source} onServer={Boolean(ready.remote)} />
    </div>
  );
}

export function renderRecording(ctx: ScreenCtx): ReactNode {
  // `lecture=srv-<activityId>`: the server video lecture (study-server.tsx); recordings stay as they are.
  const server = serverStudyLecture(ctx, () => renderRecording(ctx));
  if (server) return server;
  const params = ctx.params || new URLSearchParams();
  const courseId = params.get("kj");
  const rec = params.get("rec");
  // A server recording of a course I no longer take (listed on the study-room home) has no `kj`.
  if (!courseId && rec && (!backendSettled() || studyRoomOnServer())) {
    return (
      <section className="study rec" data-area="home">
        <p className="term"><Link href={hrefFor(find("study_home"))}>학습실</Link>{" · 지금 듣지 않는 과목"}</p>
        <h1><Icon name="mic" /><span>녹음 파일</span></h1>
        {backendSettled() ? <PlaybackView key={rec} course={null} id={rec} /> : <div className="rec-panel" aria-busy="true"><LoadingState title="녹음을 불러오는 중입니다" /></div>}
      </section>
    );
  }
  const course = courseById(courseId);
  if (!courseId || course.missing || course.id !== courseId) {
    return (
      <section className="study rec" data-area="home">
        <h1>녹음</h1>
        <ErrorState
          title="과목을 찾을 수 없습니다" body="학습실에서 과목 폴더를 열고 새 녹음을 누르세요."
          actions={<Link href={hrefFor(find("study_home"))} className="btn btn-secondary">학습실로</Link>}
        />
      </section>
    );
  }
  return <Recording course={course} id={rec} />;
}

function Recording({ course, id }: { course: Course; id: string | null }) {
  useEffect(() => {
    if (id) return;
    // A share ended from the browser bar saves on its own; follow it to the saved file.
    const off = onRecordingEvent((event) => {
      if (event.type === "saved" && event.record.courseId === course.id) {
        off();
        navigate(hrefFor(find("study_lecture"), { kj: course.id, rec: event.record.id }));
      } else if (event.type === "error") {
        off();
        setRecordingError(messageOf(event.error));
        navigate(hrefFor(find("study_lecture"), { kj: course.id }));
      }
    });
    return off;
  }, [id, course.id]);

  const live = activeRecording();
  let body: ReactNode;
  if (id) body = <PlaybackView key={id} course={course} id={id} />;
  else if (live && live.courseId !== course.id) {
    const other = courseById(live.courseId);
    body = (
      <div className="rec-panel">
        <p>{`${other && !other.missing && other.id === live.courseId ? other.cardTitle || other.ko : "다른 과목"}에서 녹음 중입니다. 한 번에 하나만 녹음할 수 있습니다.`}</p>
        <Link href={hrefFor(find("study_lecture"), { kj: live.courseId })} className="btn btn-primary">진행 중인 녹음으로</Link>
      </div>
    );
  } else body = live ? <LiveView live={live} /> : <SetupView key={`${course.id}:${errorVersion}`} course={course} />;
  return (
    <section className="study rec" data-area="home">
      <p className="term"><Link href={hrefFor(find("study_home"))}>학습실</Link>{" · "}<Link href={folderHref(course.id)}>{course.cardTitle || course.ko}</Link></p>
      <h1><Icon name="mic" /><span>{id ? "녹음 파일" : "강의 녹음"}</span></h1>
      {body}
    </section>
  );
}
