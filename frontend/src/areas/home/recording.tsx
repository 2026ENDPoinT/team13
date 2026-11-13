import { useEffect, useRef, useState, type ReactNode } from "react";
import { courseById, type Course } from "../../data.ts";
import { hrefFor, find } from "../../manifest.ts";
import type { ScreenCtx } from "../../types.ts";
import { getState, render } from "../../store.ts";
import { Link, navigate } from "../../router.tsx";
import { showErrors } from "../../forms.ts";
import { Icon } from "../../ui/icon.tsx";
import { Button, ErrorState, LoadingState } from "../../ui/kit.tsx";
import {
  activeRecording, getRecording, importRecording, formatDuration, recordingExtension, onRecordingEvent,
  startRecording, pauseRecording, resumeRecording, stopRecording, deleteRecording,
  type ActiveRecording, type RecordingRecord, type RecordingSource,
} from "../../shared/course-recordings.ts";
import { folderHref } from "./study-files.tsx";
import { serverStudyLecture } from "./study-server.tsx";

const SOURCES: { id: RecordingSource; label: string; hint: string }[] = [
  { id: "mic", label: "마이크", hint: "음성 메모나 강의실 수업을 녹음해요." },
  { id: "tab", label: "브라우저 탭", hint: "온라인 강의·Zoom 탭을 고르고 ‘탭 오디오도 공유’를 켜 주세요." },
  { id: "audio", label: "오디오 파일 업로드", hint: "이미 녹음해 둔 MP3·M4A 파일을 올려요." },
];
const dateTime = new Intl.DateTimeFormat("ko-KR", { month: "long", day: "numeric", hour: "2-digit", minute: "2-digit" });

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

function TranscriptNote({ source }: { source?: RecordingSource }) {
  if (source === "audio") {
    return (
      <div className="rec-transcript-status" role="status">
        <strong>전사 준비 · 목업</strong>
        <p>오디오 파일이 저장되어 재생할 수 있습니다. 전사 API는 연결되지 않아 실제 전사 결과는 생성되지 않습니다.</p>
      </div>
    );
  }
  return <p className="rec-note">전사(글자 변환)는 아직 연결되지 않았습니다. 녹음 파일만 이 브라우저에 저장됩니다.</p>;
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

/** Picking a source acts at once: mic and tab start recording, a chosen audio file is stored and opened. */
function SetupView({ course }: { course: Course }) {
  const [error, setError] = useState(lastError);
  // While one source connects or saves, every card is locked and that card shows `text` in place of its hint.
  const [locked, setLocked] = useState<{ source: RecordingSource; text: string } | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
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
    setLocked({ source: "audio", text: "파일 저장 중…" });
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
      <p className="rec-note">녹음 전 교수님의 허락을 받아 주세요. 오디오는 이 브라우저에만 저장되며, 자동 전사는 연결되지 않았습니다.</p>
      <input
        ref={fileInput} type="file" accept="audio/*,.mp3,.m4a,.wav,.ogg,.webm,.aac,.flac" hidden aria-label="저장할 오디오 파일 선택"
        onChange={() => void onFile()}
      />
    </div>
  );
}

function LiveView({ live }: { live: ActiveRecording }) {
  const [elapsed, setElapsed] = useState(live.elapsedMs);
  const [saving, setSaving] = useState(false);
  useEffect(() => {
    const tick = window.setInterval(() => {
      const now = activeRecording();
      if (!now) {
        window.clearInterval(tick);
        return;
      }
      setElapsed(now.elapsedMs);
    }, 500);
    return () => window.clearInterval(tick);
  }, []);
  return (
    <div className={`rec-panel rec-live${live.paused ? " paused" : ""}`}>
      <div className="rec-state">
        <span className="rec-dot" aria-hidden="true" />
        <span>{`${live.paused ? "일시정지" : "녹음 중"} · ${sourceLabel(live.source)}`}</span>
      </div>
      <strong className="rec-clock">{formatDuration(elapsed)}</strong>
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
      <p className="rec-note">다른 화면으로 이동해도 녹음은 계속됩니다. 이 탭을 닫으면 녹음이 사라집니다.</p>
      <TranscriptNote />
    </div>
  );
}

function DeleteButton({ record, course }: { record: RecordingRecord; course: Course }) {
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
        deleteRecording(record.id).then(() => {
          getState().toast = "녹음을 삭제했습니다";
          navigate(folderHref(course.id));
        }, (error: unknown) => {
          setDeleting(false);
          showErrors({ "rec-delete": messageOf(error) });
        });
      }}
    >
      {armed ? "한 번 더 누르면 삭제" : "삭제"}
    </Button>
  );
}

type Playback = { kind: "loading" } | { kind: "missing" } | { kind: "failed" } | { kind: "ready"; record: RecordingRecord };

function PlaybackView({ course, id }: { course: Course; id: string }) {
  const [playback, setPlayback] = useState<Playback>({ kind: "loading" });
  const [url, setUrl] = useState("");
  useEffect(() => {
    let live = true;
    getRecording(id).then((record) => {
      if (live) setPlayback(record && record.courseId === course.id ? { kind: "ready", record } : { kind: "missing" });
    }).catch(() => {
      if (live) setPlayback({ kind: "failed" });
    });
    return () => {
      live = false;
    };
  }, [id, course.id]);
  const record = playback.kind === "ready" ? playback.record : null;
  useEffect(() => {
    if (!record) return;
    const next = URL.createObjectURL(record.blob);
    setUrl(next);
    return () => URL.revokeObjectURL(next);
  }, [record]);

  if (playback.kind === "loading") {
    return <div className="rec-panel" aria-busy="true"><LoadingState title="녹음을 불러오는 중입니다" /></div>;
  }
  if (playback.kind === "missing") {
    return (
      <div className="rec-panel">
        <ErrorState
          title="녹음을 찾을 수 없습니다" body="삭제되었거나 다른 브라우저에서 만든 녹음입니다."
          actions={<Link href={folderHref(course.id)} className="btn btn-secondary">폴더로</Link>}
        />
      </div>
    );
  }
  if (playback.kind === "failed") {
    return (
      <div className="rec-panel">
        <ErrorState title="녹음을 불러오지 못했습니다" body="이 브라우저의 저장소에 접근할 수 없습니다. 사이트 데이터 접근 설정을 확인해 주세요." />
      </div>
    );
  }
  const ready = playback.record;
  return (
    <div className="rec-panel">
      <h2 className="rec-title">{ready.title}</h2>
      <p className="rec-meta">{`${sourceLabel(ready.source)} · ${formatDuration(ready.durationMs)} · ${dateTime.format(new Date(ready.createdAt))}`}</p>
      {url ? <PlayableAudio url={url} title={ready.title} /> : null}
      <div className="btn-row">
        <a className="btn btn-secondary" href={url || undefined} download={ready.fileName || `${ready.title.replace(/[\\/:*?"<>|]/g, "-")}.${recordingExtension(ready.mimeType)}`}>다운로드</a>
        <DeleteButton record={ready} course={course} />
      </div>
      <TranscriptNote source={ready.source} />
    </div>
  );
}

export function renderRecording(ctx: ScreenCtx): ReactNode {
  // `lecture=srv-<activityId>`: the server video lecture (study-server.tsx); recordings stay as they are.
  const server = serverStudyLecture(ctx, () => renderRecording(ctx));
  if (server) return server;
  const params = ctx.params || new URLSearchParams();
  const courseId = params.get("kj");
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
  return <Recording course={course} id={params.get("rec")} />;
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
