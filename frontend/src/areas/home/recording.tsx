import { useEffect, useRef, useState, useSyncExternalStore, type CSSProperties, type ReactNode } from "react";
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
  startRecording, pauseRecording, resumeRecording, stopRecording, deleteRecording, moveRecordingToAccount, appendBlocked, WARN_SHARE,
  unsavedAudio, onUnsavedChange, forgetUnsaved,
  type ActiveRecording, type LocalRecording, type RecordingRecord, type RecordingSource,
} from "../../shared/course-recordings.ts";
import { backendState, backendSettled, backendStatus } from "../../shared/backend.ts";
import { useStudyRoomStore, ownerGroup } from "../../shared/study-folders.ts";
import { saveBlob } from "../../shared/uploads-remote.tsx";
import { serverStudyCourse, studyRoomOnServer, peekCourseView, studyRoomMessage, formatBytes, usageText } from "../../shared/study-room-remote.ts";
import { folderHref, recordingHref } from "./study-files.tsx";
import { serverStudyLecture } from "./study-server.tsx";
import { RecordingStudyView, demoRecordingStudy } from "./recording-study.tsx";
import { printSummaryPdf } from "./recording-summary-pdf.ts";
import { liveTranscript, onLiveTranscriptChange } from "../../shared/live-transcript.ts";
import { clock } from "./recording-study.tsx";
import { recordingJobPending } from "../../shared/recording-study.ts";

const SOURCES: { id: RecordingSource; label: string; hint: string }[] = [
  { id: "mic", label: "마이크", hint: "음성 메모나 강의실 수업을 녹음해요." },
  { id: "tab", label: "브라우저 탭", hint: "온라인 강의·Zoom 탭을 고르고 ‘탭 오디오도 공유’를 켜 주세요." },
  { id: "audio", label: "오디오 파일 업로드", hint: "이미 녹음해 둔 MP3·M4A 파일을 올려요." },
];
const dateTime = new Intl.DateTimeFormat("ko-KR", { month: "long", day: "numeric", hour: "2-digit", minute: "2-digit" });
// Poll only while the server is scanning audio or generating a transcript/summary.
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

function TranscriptNote({ onServer }: { onServer: boolean }) {
  return <p className="rec-note">{onServer ? "녹음 파일은 ASTRA 서버의 내 학습실에 저장됩니다. 전사·요약은 서버에서 제공하는 경우 처리 후 표시됩니다." : "녹음 파일은 이 브라우저에만 저장됩니다. 서버에 저장하기 전에는 실제 전사·요약을 받을 수 없습니다."}</p>;
}

/** Remounts the recording page body (recording-study.tsx) when the recording changes. */
const viewKey = (record: RecordingRecord) => `${record.remote ? "srv" : "local"}-${record.id}`;

/** Server study room of the course: usage against the quota, and a warning when no new recording fits. */
function ServerUsage({ courseId }: { courseId: number | string }) {
  useStudyRoomStore();
  const { view } = peekCourseView(courseId);
  const usage = view && view.usage;
  if (!usage) return null;
  const full = usage.usedBytes >= usage.quotaBytes
    ? "서버의 학습실 저장 공간이 가득 찼습니다. 새 녹음은 서버에 저장되지 않고 이 브라우저에 보관됩니다. 오래된 녹음이나 PDF를 지워 공간을 비우세요."
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
      <p className="rec-note">{`녹음 전 교수님의 허락을 받아 주세요. 오디오는 ${storedWhere(server != null)}. 마이크로 녹음하면 녹음 중 화면에 임시 실시간 전사가 표시됩니다(저장되지 않음).`}</p>
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

/**
 * 녹음 중 왼쪽 아래에 쭉 이어지는 실시간 전사(브라우저 음성 인식). 저장된 녹음 화면의 전사 목록과 같은 모양이다.
 * 맨 아래에 있으면 새 줄을 따라가고, 위로 올려 읽으면 멈추고 "최신 전사" 버튼을 보인다.
 */
function LiveTranscript({ source, elapsedMs }: { source: RecordingSource; elapsedMs: number }) {
  const live = useSyncExternalStore(onLiveTranscriptChange, liveTranscript);
  const box = useRef<HTMLOListElement>(null);
  const [follow, setFollow] = useState(true);
  useEffect(() => {
    const list = box.current;
    if (list && follow) list.scrollTop = list.scrollHeight;
  }, [live.lines, live.interim, follow]);
  const state = (title: string, body: string) => (
    <div className="rs-transcript is-empty">
      <p className="lv-state" role="status"><strong>{title}</strong>{body}</p>
    </div>
  );
  if (source !== "mic") return state("탭 소리는 실시간 전사를 지원하지 않습니다", "저장한 뒤 서버가 전사를 제공하면 녹음 화면에 표시됩니다.");
  if (!live.supported) return state("이 브라우저는 실시간 전사를 지원하지 않습니다", "데스크톱 Chrome 또는 Edge에서 열어 주세요. 녹음은 그대로 됩니다.");
  if (!live.lines.length && !live.interim) {
    if (live.error) return state("실시간 전사가 멈췄습니다", live.error);
    return live.listening ? state("듣는 중입니다", "말씀하시면 여기에 글로 나타납니다.") : state("일시정지 중입니다", "다시 시작하면 이어서 전사합니다.");
  }
  return (
    <div className="rs-transcript">
      <ol
        ref={box} className="rs-lines" aria-label="실시간 전사"
        onScroll={(event) => {
          const list = event.currentTarget;
          setFollow(list.scrollHeight - list.scrollTop - list.clientHeight < 24);
        }}
      >
        {live.lines.map((line, index) => (
          <li key={index}><div className="rs-line"><time>{clock(line.at / 1000)}</time><span>{line.text}</span></div></li>
        ))}
        {live.interim ? <li><div className="rs-line" aria-current="true"><time>{clock(elapsedMs / 1000)}</time><span>{live.interim}</span></div></li> : null}
      </ol>
      {live.error ? <p className="err" role="alert">{live.error}</p> : null}
      {!follow ? (
        <button type="button" className="rs-follow" onClick={() => setFollow(true)}>
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M12 5v14M6 13l6 6 6-6" /></svg>
          최신 전사
        </button>
      ) : null}
    </div>
  );
}

/** 녹음 중 화면: 저장된 녹음 화면과 같은 배치(왼쪽 녹음 카드와 전사, 오른쪽 요약 자리)에서 전사가 바로 아래로 이어진다. */
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
  const pause = () => {
    if (live.paused) resumeRecording();
    else pauseRecording();
    render();
  };
  return (
    <>
      <header className="rs-head">
        <div>
          <h1>{live.title}</h1>
          <p className="tiny">{`${sourceLabel(live.source)} · ${formatDuration(now.elapsedMs)} · ${dateTime.format(live.startedAt)}`}</p>
        </div>
        <div className="rs-menu">
          <button type="button" className="rs-menu-btn" disabled aria-label="다운로드(저장한 뒤 사용할 수 있습니다)" title="저장한 뒤 내려받을 수 있습니다.">
            <Icon name="download" />
          </button>
        </div>
      </header>
      <div className="rs">
        <div className="rs-left">
          <section className={`rs-player rec-live${live.paused ? " paused" : ""}`} aria-label="녹음 중">
            <div className="rs-player-top">
              <h2 className="rec-state"><span className="rec-dot" aria-hidden="true" />{live.paused ? "일시정지" : live.appendTo ? "이어서 녹음 중" : "녹음 중"}</h2>
              <span role="status">{live.paused ? "일시정지" : "녹음 중"}</span>
            </div>
            <input
              type="range" className="rs-seek" min={0} max={1} step={0.1} value={1} disabled readOnly
              aria-label="재생 위치(녹음 중에는 쓸 수 없음)" style={{ "--rs-fill": "100%" } as CSSProperties}
            />
            <div className="rs-times"><span>{clock(0)}</span><span>{clock(now.elapsedMs / 1000)}</span></div>
            <div className="rs-controls">
              <button type="button" className="rs-rate" disabled aria-label="재생 속도(녹음 중에는 쓸 수 없음)">1×</button>
              <div className="rs-transport">
                <span className="rs-skip" aria-hidden="true" />
                <button type="button" className="rs-play" aria-label={live.paused ? "다시 시작" : "일시정지"} onClick={pause}>
                  <svg viewBox="0 0 24 24" width="26" height="26" fill="currentColor" aria-hidden="true">
                    {live.paused
                      ? <path d="M8 5.2v13.6a.8.8 0 0 0 1.2.7l10.6-6.8a.8.8 0 0 0 0-1.4L9.2 4.5A.8.8 0 0 0 8 5.2Z" />
                      : <path d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z" />}
                  </svg>
                </button>
                <span className="rs-skip" aria-hidden="true" />
              </div>
              <span className="rs-rate-spacer" aria-hidden="true" />
            </div>
            <button
              type="button" className="btn btn-primary rs-stop" disabled={saving}
              onClick={() => {
                setSaving(true);
                // The recorder screen follows the "saved" or "error" event to the next view.
                stopRecording().catch(() => {});
              }}
            >
              {saving ? "저장 중…" : "중지하고 저장"}
            </button>
            {now.limitBytes ? <p className="rec-note">{`파일 크기 ${formatBytes(now.bytes)} / 서버 한도 ${formatBytes(now.limitBytes)}`}</p> : null}
            {near ? <p className="err" role="alert">곧 서버의 파일 크기 한도에 닿습니다. 한도에 닿으면 녹음을 멈추고 저장합니다. 이어서 녹음하려면 그 뒤에 새로 시작하세요.</p> : null}
          </section>
        <LiveTranscript source={live.source} elapsedMs={now.elapsedMs} />
      </div>
      <section className="rs-tools" aria-label="요약">
        <div className="lv-panel rs-panel">
          <p className="lv-state" role="status"><strong>요약은 녹음을 저장한 뒤 만들어집니다</strong>서버에서 제공하는 경우 처리 후 이 자리에 표시됩니다.</p>
        </div>
        <div className="rs-notes">
          {live.appendTo ? <p className="rec-note">{`"${live.appendTo.title}" 뒤에 이어 녹음합니다. 중지하면 한 파일로 저장됩니다.`}</p> : null}
          <p className="rec-note">다른 화면으로 이동해도 녹음은 계속됩니다. 저장하기 전에 이 탭을 닫으면 녹음이 사라집니다.</p>
          {live.browserOnly && studyRoomOnServer()
            ? <p className="rec-note">이 브라우저에만 있는 폴더에서 시작한 녹음이라 이 브라우저에만 저장됩니다. 서버로 옮기려면 저장한 뒤 ‘내 계정으로 옮기기’를 누르세요.</p>
            : null}
          <TranscriptNote onServer={onServer} />
          {live.source === "mic" ? <p className="rec-note">실시간 전사는 브라우저의 음성 인식으로 만든 임시 전사입니다. 이 화면에만 보이고 저장되지 않으며, Chrome·Edge는 음성을 브라우저 제공사 서버로 보내 인식합니다. 틀린 부분이 있을 수 있습니다.</p> : null}
        </div>
      </section>
    </div>
    </>
  );
}

/**
 * 머리 오른쪽 버튼들: 다운로드 메뉴(원본 음성, 서버가 만든 전사문 TXT·요약 PDF)와 그 왼쪽의 휴지통 삭제 버튼.
 * 삭제는 한 번 더 눌러야 지운다(다른 곳을 누르거나 Esc·4초가 지나면 되돌아간다).
 */
function DownloadMenu({ record, course, url, fileName, onStale }: { record: RecordingRecord; course: Course | null; url: string; fileName: string; onStale: () => void }) {
  const [open, setOpen] = useState(false);
  const [armed, setArmed] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const wrap = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const trash = useRef<HTMLButtonElement>(null);
  const transcription = record.remote && record.state === "ready" && record.transcription?.status === "ready" ? record.transcription.result : null;
  const summary = record.remote && record.state === "ready" && record.summary?.status === "ready" ? record.summary.result : null;
  useEffect(() => {
    if (!open) return;
    wrap.current?.querySelector<HTMLElement>('[role="menuitem"]:not([aria-disabled="true"])')?.focus();
    const onPointer = (event: PointerEvent) => {
      if (!wrap.current || !wrap.current.contains(event.target as Node)) setOpen(false);
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      setOpen(false);
      trigger.current?.focus();
    };
    document.addEventListener("pointerdown", onPointer);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("pointerdown", onPointer);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);
  useEffect(() => {
    if (open) setArmed(false);
  }, [open]);
  // 한 번 눌러 "한 번 더" 상태가 된 삭제는 4초 뒤, 다른 곳을 누르거나 Esc를 누르면 되돌아간다.
  useEffect(() => {
    if (!armed) return;
    const timer = window.setTimeout(() => setArmed(false), 4000);
    const onPointer = (event: PointerEvent) => {
      if (!trash.current || !trash.current.contains(event.target as Node)) setArmed(false);
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") setArmed(false);
    };
    document.addEventListener("pointerdown", onPointer);
    document.addEventListener("keydown", onKey);
    return () => {
      window.clearTimeout(timer);
      document.removeEventListener("pointerdown", onPointer);
      document.removeEventListener("keydown", onKey);
    };
  }, [armed]);
  const remove = () => {
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
  };
  return (
    <div className="rs-actions">
      <button
        ref={trash} type="button" className={`rs-menu-btn is-danger${armed ? " is-armed" : ""}`} disabled={deleting}
        aria-label={armed ? "한 번 더 누르면 녹음 삭제" : "녹음 삭제"} title={armed ? "한 번 더 누르면 삭제" : "삭제"} onClick={remove}
      >
        <Icon name="trash" />{armed ? <span>한 번 더 누르면 삭제</span> : null}
      </button>
      <div ref={wrap} className="rs-menu">
        <button
          ref={trigger} type="button" className="rs-menu-btn" aria-haspopup="menu" aria-expanded={open ? "true" : "false"} aria-label="다운로드"
          onClick={() => setOpen(!open)}
        >
          <Icon name="download" />
        </button>
        {open ? (
          <div className="study-new-menu" role="menu" aria-label="다운로드">
            {url ? (
              <a role="menuitem" className="study-new-item" href={url} download={fileName} onClick={() => setOpen(false)}>
                <Icon name="download" /><span>원본 음성</span>
              </a>
            ) : (
              <span role="menuitem" aria-disabled="true" className="study-new-item is-disabled"><Icon name="download" /><span>원본 음성</span></span>
            )}
            {transcription ? (
              <button type="button" role="menuitem" className="study-new-item" onClick={() => {
                saveBlob(new Blob([transcription.text], { type: "text/plain;charset=utf-8" }), `${record.title.replace(/[\\/:*?"<>|]/g, "-")}.txt`);
                setOpen(false);
              }}>
                <Icon name="file" /><span>전사문 TXT</span>
              </button>
            ) : (
              <span role="menuitem" aria-disabled="true" className="study-new-item is-disabled" title="서버에서 전사가 완료되면 내려받을 수 있습니다.">
                <Icon name="file" /><span>전사문 TXT</span>
              </span>
            )}
            {summary ? (
              <button type="button" role="menuitem" className="study-new-item" onClick={() => {
                printSummaryPdf(record.title, summary);
                setOpen(false);
              }}>
                <Icon name="file" /><span>요약 PDF</span>
              </button>
            ) : (
              <span role="menuitem" aria-disabled="true" className="study-new-item is-disabled" title="서버에서 요약이 완료되면 내려받을 수 있습니다.">
                <Icon name="file" /><span>요약 PDF</span>
              </span>
            )}
          </div>
        ) : null}
      </div>
    </div>
  );
}

/** A recording still only in this browser while the study room is on the server: why, and the explicit move. */
function MoveToAccount({ record: initial, course }: { record: LocalRecording; course: Course }) {
  const [moving, setMoving] = useState(false);
  // The record as the last try left it: after a key conflict it carries the new `uploadKey` the next try must use.
  const [record, setRecord] = useState(initial);
  const [error, setError] = useState(initial.uploadError || "");
  const name = backendState().me?.name;
  const account = `지금 로그인한 계정${name ? `(${name})` : ""}`;
  const group = ownerGroup(record.ownerUserId);
  const moveText = `옮기면 ${account}의 학습실에 저장되고, 서버가 받은 뒤에만 이 브라우저에서 지워집니다.`;
  return (
    <div className="rec-transcript-status" role="note">
      <strong>{group === "other" ? "다른 계정으로 저장된 녹음" : group === "before-login" ? "로그인 전에 이 브라우저에 저장된 녹음" : "이 브라우저에만 저장된 녹음"}</strong>
      <p>
        {group === "other"
          ? `다른 계정으로 로그인했을 때 이 브라우저에 저장된 녹음입니다. 이 컴퓨터를 함께 쓰는 다른 사람의 것일 수 있습니다. 내 녹음이 맞을 때만 옮기세요. ${moveText}`
          : group === "before-login" ? `로그인하지 않은 상태에서 이 브라우저에 저장된 녹음입니다. ${moveText}` : moveText}
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
              if (!result.record.remote) setRecord(result.record);
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
        {moving ? "옮기는 중…" : error ? (group === "mine" ? "다시 올리기" : "다시 옮기기") : "내 계정으로 옮기기"}
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
  const [refreshError, setRefreshError] = useState("");
  // Read once the backend connection settled, and again when the study room moves to the server.
  const onServer = studyRoomOnServer();
  const settled = backendSettled();
  useEffect(() => {
    if (!settled) return;
    let live = true;
    getRecording(id).then((record) => {
      if (live) {
        setPlayback(record && record.courseId === kj ? { kind: "ready", record } : { kind: "missing" });
        setRefreshError("");
      }
    }).catch((error: unknown) => {
      if (!live) return;
      const message = error instanceof ApiError ? studyRoomMessage(error) : "녹음 상태를 불러오지 못했습니다.";
      setRefreshError(message);
      setPlayback((previous) => previous.kind === "ready" && !(error instanceof ApiError && [401, 403, 404].includes(error.status))
        ? previous : { kind: "failed", message });
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
  // Keep the player mounted while the backend generates results. Terminal/missing jobs never poll.
  const pending = Boolean(record?.remote && (record.state === "scanning" || (record.state === "ready"
    && (recordingJobPending(record.transcription) || recordingJobPending(record.summary)))));
  useEffect(() => {
    if (!pending || refreshError) return;
    const timer = window.setTimeout(() => setReads((n) => n + 1), SCAN_POLL_MS);
    return () => window.clearTimeout(timer);
  }, [pending, refreshError, reads]);
  const refresh = () => {
    setRefreshError("");
    setReads((n) => n + 1);
  };

  if (playback.kind === "loading") {
    return <><PlaybackHeading /><div className="rec-panel" aria-busy="true"><LoadingState title="녹음을 불러오는 중입니다" /></div></>;
  }
  if (playback.kind === "missing") {
    return (
      <>
        <PlaybackHeading />
        <div className="rec-panel">
          <ErrorState
            title="녹음을 찾을 수 없습니다" body={!course || serverStudyCourse(course.id) != null ? "삭제되었거나 이 과목의 녹음이 아닙니다." : "삭제되었거나 다른 브라우저에서 만든 녹음입니다."}
            actions={<Link href={course ? folderHref(course.id) : hrefFor(find("study_home"))} className="btn btn-secondary">{course ? "폴더로" : "학습실로"}</Link>}
          />
        </div>
      </>
    );
  }
  if (playback.kind === "failed") {
    return (
      <>
        <PlaybackHeading />
        <div className="rec-panel">
          <ErrorState
            title="녹음을 불러오지 못했습니다"
            body={playback.message || "이 브라우저의 저장소에 접근할 수 없습니다. 사이트 데이터 접근 설정을 확인해 주세요."}
            actions={<Button kind="ghost" onClick={refresh}>다시 불러오기</Button>}
          />
        </div>
      </>
    );
  }
  const ready = playback.record;
  const state = ready.remote ? ready.state : "ready";
  const fileName = ready.fileName || `${ready.title.replace(/[\\/:*?"<>|]/g, "-")}.${recordingExtension(ready.mimeType)}`;
  // Only an explicitly offline demo gets sample results; server mode never substitutes samples.
  const study = backendStatus() === "off" ? demoRecordingStudy() : ready.remote && ready.state === "ready"
    ? { transcription: ready.transcription, summary: ready.summary } : {};
  return (
    <>
      <header className="rs-head">
        <div>
          <h1>{ready.title}</h1>
          <p className="tiny">{`${sourceLabel(ready.source)} · ${formatDuration(ready.durationMs)} · ${dateTime.format(new Date(ready.createdAt))}`}</p>
        </div>
        <DownloadMenu record={ready} course={course} url={url} fileName={fileName} onStale={() => setReads((n) => n + 1)} />
      </header>
      {state === "scanning" ? <p className="rec-note rs-notice" role="status">바이러스 검사 중입니다. 검사가 끝나면 재생하고 내려받을 수 있습니다.</p> : null}
      {state === "rejected" ? <p className="err rs-notice" role="alert">검사에서 악성 코드가 발견되어 재생하거나 내려받을 수 없습니다. 이 녹음을 삭제하세요.</p> : null}
      {refreshError ? <div className="rs-notice" role="alert"><p>{refreshError}</p><Button kind="ghost" onClick={refresh}>상태 다시 확인</Button></div> : null}
      {course && !ready.remote && serverStudyCourse(course.id) != null ? <div className="rs-notice"><MoveToAccount record={ready} course={course} /></div> : null}
      <RecordingStudyView
        id={viewKey(ready)} url={url} durationMs={ready.durationMs}
        blocked={state === "scanning" ? "바이러스 검사가 끝나면 재생할 수 있습니다." : state === "rejected" ? "악성 코드가 발견되어 재생할 수 없습니다." : ""}
        transcription={study.transcription} summary={study.summary}
        resume={course ? {
          blocked: appendBlocked(ready),
          start: () => startRecording({ courseId: course.id, source: ready.source === "tab" ? "tab" : "mic", appendTo: ready }).then(() => navigate(recordingHref(course.id))),
        } : null}
      />
    </>
  );
}

function PlaybackHeading() {
  return <h1><Icon name="mic" /><span>녹음 파일</span></h1>;
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
        {backendSettled() ? <PlaybackView key={`${backendStatus()}:${backendState().me?.userId ?? ""}:${rec}`} course={null} id={rec} /> : <><PlaybackHeading /><div className="rec-panel" aria-busy="true"><LoadingState title="녹음을 불러오는 중입니다" /></div></>}
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
  if (id) body = <PlaybackView key={`${backendStatus()}:${backendState().me?.userId ?? ""}:${id}`} course={course} id={id} />;
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
      {id || (live && live.courseId === course.id) ? null : <h1><Icon name="mic" /><span>강의 녹음</span></h1>}
      {body}
    </section>
  );
}
