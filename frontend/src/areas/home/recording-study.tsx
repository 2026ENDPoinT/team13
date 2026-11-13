// 녹음 파일 화면의 본문: 왼쪽은 "녹음 다시 듣기" 플레이어와 시각별 전사, 오른쪽은 요약.
// 전사 줄과 요약의 시각 버튼을 누르면 그 시각부터 듣는다.
// 서버가 만든 전사·요약과 처리 상태를 표시한다. 백엔드 `off`에서만 명시적인 목업을 넣는다.
import { useEffect, useRef, useState, type CSSProperties } from "react";
import { Icon } from "../../ui/icon.tsx";
import { Jump, type Locate } from "../../shared/lesson-viewer.tsx";
import type {
  TranscriptLine, StudySection, RecordingJob, RecordingSummaryResult, RecordingStudyResults,
} from "../../shared/recording-study.ts";

const RATES = [1, 1.25, 1.5, 2, 0.75];
const SKIP_SECONDS = 10;

/** 재생 시각 `m:ss` / `h:mm:ss`. */
export function clock(seconds: number): string {
  const total = Math.max(0, Math.floor(Number.isFinite(seconds) ? seconds : 0));
  const p = (n: number) => String(n).padStart(2, "0");
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  return h ? `${h}:${p(m)}:${p(total % 60)}` : `${m}:${p(total % 60)}`;
}

const timeAt: Locate = (at, what) => ({ text: clock(at), label: `${what} ${clock(at)}부터 듣기` });

export interface RecordingStudyProps extends RecordingStudyResults {
  /** 바뀌면 재생 위치를 처음부터 다시 잡는다. */
  id: string;
  /** 재생할 주소. 빈 문자열: 아직 재생할 수 없다(`blocked`가 이유). */
  url: string;
  blocked: string;
  durationMs: number;
  /** "이어서 녹음": `blocked` is why it cannot start now ("" when it can); `start` must run inside the click. */
  resume: { blocked: string; start: () => Promise<void> } | null;
}

export function RecordingStudyView(props: RecordingStudyProps) {
  return <StudyBody key={props.id} {...props} />;
}

function StudyBody({ url, blocked, durationMs, transcription, summary, resume }: RecordingStudyProps) {
  const audio = useRef<HTMLAudioElement>(null);
  const [resuming, setResuming] = useState(false);
  const [resumeError, setResumeError] = useState("");
  // MediaRecorder WebM 파일은 길이가 없어 처음 한 번 끝 너머로 감아 브라우저가 길이를 알게 한다. 그동안 시각은 무시한다.
  const probing = useRef(false);
  const probed = useRef(false);
  const [time, setTime] = useState(0);
  const [duration, setDuration] = useState(durationMs / 1000);
  const [playing, setPlaying] = useState(false);
  const [rate, setRate] = useState(1);

  const seek = (at: number) => {
    const node = audio.current;
    const next = Math.min(Math.max(0, at), duration || at);
    setTime(next);
    if (node && url) node.currentTime = next;
  };
  const toggle = () => {
    const node = audio.current;
    if (!node || !url) return;
    if (node.paused) node.play().catch(() => setPlaying(false));
    else node.pause();
  };
  const nextRate = () => {
    const next = RATES[(RATES.indexOf(rate) + 1) % RATES.length];
    setRate(next);
    if (audio.current) audio.current.playbackRate = next;
  };
  const lines = transcription?.status === "ready" ? transcription.result.segments : [];
  let current = -1;
  for (let i = 0; i < lines.length && lines[i].at <= time; i++) current = i;

  return (
    <div className="rs">
      <div className="rs-left">
        <section className="rs-player" aria-label="녹음 다시 듣기">
          {url ? (
            <audio
              ref={audio} src={url} preload="metadata"
              onLoadedMetadata={(event) => {
                const node = event.currentTarget;
                node.playbackRate = rate;
                if (Number.isFinite(node.duration)) {
                  setDuration(node.duration);
                  return;
                }
                if (probed.current) return;
                probed.current = true;
                probing.current = true;
                node.addEventListener("durationchange", () => {
                  node.currentTime = 0;
                  probing.current = false;
                }, { once: true });
                node.currentTime = Number.MAX_SAFE_INTEGER;
              }}
              onDurationChange={(event) => {
                if (Number.isFinite(event.currentTarget.duration)) setDuration(event.currentTarget.duration);
              }}
              onTimeUpdate={(event) => {
                if (!probing.current) setTime(event.currentTarget.currentTime);
              }}
              onPlay={() => setPlaying(true)}
              onPause={() => setPlaying(false)}
              onEnded={() => setPlaying(false)}
            />
          ) : null}
          <div className="rs-player-top">
            <h2>녹음 다시 듣기</h2>
            <span role="status">{!url ? "재생할 수 없음" : playing ? "재생 중" : time > 0 ? "일시정지" : "정지"}</span>
          </div>
          <input
            type="range" className="rs-seek" min={0} max={duration || 0} step={0.1} value={Math.min(time, duration || 0)} disabled={!url}
            aria-label="재생 위치" aria-valuetext={`${clock(time)} / ${clock(duration)}`}
            style={{ "--rs-fill": `${duration ? (Math.min(time, duration) / duration) * 100 : 0}%` } as CSSProperties}
            onChange={(event) => seek(Number(event.currentTarget.value))}
          />
          <div className="rs-times"><span>{clock(time)}</span><span>{clock(duration)}</span></div>
          <div className="rs-controls">
            <button type="button" className="rs-rate" disabled={!url} aria-label={`재생 속도 ${rate}배, 눌러서 바꾸기`} onClick={nextRate}>{`${rate}×`}</button>
            <div className="rs-transport">
              <button type="button" className="rs-skip" disabled={!url} aria-label={`${SKIP_SECONDS}초 뒤로`} onClick={() => seek(time - SKIP_SECONDS)}>
                <SkipIcon back /><span aria-hidden="true">{SKIP_SECONDS}</span>
              </button>
              <button type="button" className="rs-play" disabled={!url} aria-label={playing ? "일시정지" : "재생"} onClick={toggle}>
                <svg viewBox="0 0 24 24" width="26" height="26" fill="currentColor" aria-hidden="true">
                  {playing ? <path d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z" /> : <path d="M8 5.2v13.6a.8.8 0 0 0 1.2.7l10.6-6.8a.8.8 0 0 0 0-1.4L9.2 4.5A.8.8 0 0 0 8 5.2Z" />}
                </svg>
              </button>
              <button type="button" className="rs-skip" disabled={!url} aria-label={`${SKIP_SECONDS}초 앞으로`} onClick={() => seek(time + SKIP_SECONDS)}>
                <SkipIcon /><span aria-hidden="true">{SKIP_SECONDS}</span>
              </button>
            </div>
            <span className="rs-rate-spacer" aria-hidden="true" />
          </div>
          {resume ? (
            <>
              <button
                type="button" className="rs-new" disabled={Boolean(resume.blocked) || resuming}
                onClick={() => {
                  audio.current?.pause();
                  setResuming(true);
                  setResumeError("");
                  // Called in the click itself: the mic and tab pickers open only for a user gesture.
                  resume.start().catch((error: unknown) => {
                    setResuming(false);
                    setResumeError(error instanceof Error ? error.message : String(error));
                  });
                }}
              >
                <Icon name="mic" /><span>{resuming ? "녹음을 준비하는 중…" : "이어서 녹음"}</span>
              </button>
              {resumeError || resume.blocked ? <p className="rec-note" role={resumeError ? "alert" : undefined}>{resumeError || resume.blocked}</p> : null}
            </>
          ) : null}
          {!url && blocked ? <p className="rec-note">{blocked}</p> : null}
        </section>
        <Transcript lines={lines} current={current} onSeek={seek} job={transcription} />
      </div>
      <Summary job={summary} time={time} onSeek={seek} />
    </div>
  );
}

function SkipIcon({ back = false }: { back?: boolean }) {
  return (
    <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {back
        ? <><path d="M4 12a8 8 0 1 0 2.4-5.7" /><path d="M4 4v4.5h4.5" /></>
        : <><path d="M20 12a8 8 0 1 1-2.4-5.7" /><path d="M20 4v4.5h-4.5" /></>}
    </svg>
  );
}

// ---------- 전사 ----------

function ResultState({ job, label }: { job: RecordingJob<unknown> | null | undefined; label: string }) {
  const title = job?.status === "queued" ? `${label} 대기 중입니다`
    : job?.status === "processing" ? `${label} 생성 중입니다`
    : job?.status === "failed" ? `${label} 생성에 실패했습니다`
    : job?.status === "ready" ? `${label} 결과가 비어 있습니다`
    : `아직 ${label} 결과가 없습니다`;
  const body = job?.status === "queued" || job?.status === "processing"
    ? "서버에서 처리하고 있습니다. 완료되면 자동으로 표시됩니다."
    : job?.status === "failed" ? job.message || "서버에서 처리하지 못했습니다."
    : job?.status === "ready" ? "서버가 반환한 결과에 표시할 내용이 없습니다."
    : "서버에서 제공한 결과가 없습니다. 녹음은 위에서 들을 수 있습니다.";
  return <p className="lv-state" role={job?.status === "failed" ? "alert" : "status"}><strong>{title}</strong>{body}</p>;
}

/** 시각별 전사. 재생 중인 줄을 따라 내려가다 직접 스크롤해 그 줄이 안 보이면 멈추고 "현재 재생 위치" 버튼을 보인다. */
function Transcript({ lines, current, onSeek, job }: { lines: TranscriptLine[]; current: number; onSeek: (at: number) => void; job: RecordingStudyResults["transcription"] }) {
  const box = useRef<HTMLOListElement>(null);
  const [follow, setFollow] = useState(true);
  const rowOf = (index: number) => box.current?.querySelector<HTMLElement>(`[data-line="${index}"]`) || null;
  const visible = (index: number) => {
    const list = box.current;
    const row = rowOf(index);
    if (!list || !row) return true;
    return row.offsetTop + row.offsetHeight > list.scrollTop && row.offsetTop < list.scrollTop + list.clientHeight;
  };
  useEffect(() => {
    const list = box.current;
    const row = rowOf(current);
    if (!follow || !list || !row) return;
    // 줄이 화면 아래 30% 안으로 들어오면 위쪽 1/4 자리로 올린다(바로 이동: 부드러운 스크롤은 따라가기 판정을 흔든다).
    if (row.offsetTop < list.scrollTop || row.offsetTop + row.offsetHeight > list.scrollTop + list.clientHeight * 0.7) {
      list.scrollTop = row.offsetTop - list.clientHeight * 0.25;
    }
  }, [current, follow]);

  if (!lines.length) {
    return (
      <div className="rs-transcript is-empty">
        <ResultState job={job} label="전사" />
      </div>
    );
  }
  return (
    <div className="rs-transcript">
      <ol ref={box} className="rs-lines" aria-label="전사" onScroll={() => setFollow(visible(current))}>
        {lines.map((line, index) => (
          <li key={index} data-line={index}>
            <button type="button" className="rs-line" aria-current={index === current ? "true" : undefined} onClick={() => onSeek(line.at)}>
              <time>{clock(line.at)}</time>
              <span>{line.text}</span>
            </button>
          </li>
        ))}
      </ol>
      {!follow && current >= 0 ? (
        <button type="button" className="rs-follow" onClick={() => setFollow(true)}>
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M12 5v14M6 13l6 6 6-6" /></svg>
          현재 재생 위치
        </button>
      ) : null}
    </div>
  );
}

// ---------- 요약 ----------

function Summary({ job, time, onSeek }: { job: RecordingStudyResults["summary"]; time: number; onSeek: (at: number) => void }) {
  return (
    <section className="rs-tools" aria-label="요약">
      <div className="lv-panel rs-panel">
        {job?.status === "ready" ? <SummaryTab study={job.result} time={time} onSeek={onSeek} /> : <ResultState job={job} label="요약" />}
      </div>
      {job?.status === "ready" && job.result.note ? <p className="lv-note">{job.result.note}</p> : null}
    </section>
  );
}

/** 마지막으로 시작한 구간(재생 위치 기준). */
const sectionAt = (sections: StudySection[], time: number) => {
  let at = -1;
  sections.forEach((section, index) => { if (section.at <= time) at = index; });
  return at;
};

function SummaryTab({ study, time, onSeek }: { study: RecordingSummaryResult; time: number; onSeek: (at: number) => void }) {
  const live = sectionAt(study.sections, time);
  return (
    <div className="rs-summary">
      <h2>요약</h2>
      <p className="rs-overview">{study.overview}</p>
      {study.sections.map((section, index) => (
        <section key={index} className={`rs-section${index === live ? " is-current" : ""}`}>
          <div className="lv-beat-head">
            <h3>{section.title}</h3>
            <Jump to={section.at} {...timeAt(section.at, section.title)} current={index === live} onJump={onSeek} />
          </div>
          <ul>
            {section.points.map((point, pi) => (
              <li key={pi}>
                <strong>{point.term}</strong>{`: ${point.text} `}
                <Jump to={point.at} {...timeAt(point.at, point.term)} onJump={onSeek} />
              </li>
            ))}
          </ul>
          {section.table ? (
            <div className="rs-table">
              <table>
                <thead><tr>{section.table.head.map((cell) => <th key={cell} scope="col">{cell}</th>)}</tr></thead>
                <tbody>
                  {section.table.rows.map((row, ri) => (
                    <tr key={ri}>{row.map((cell, ci) => (ci === 0 ? <th key={ci} scope="row">{cell}</th> : <td key={ci}>{cell}</td>))}</tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : null}
        </section>
      ))}
    </div>
  );
}

// ---------- 목업 ----------

/** 백엔드 `off`의 목업 전사·요약: 컴퓨터구조 파이프라인 강의 한 토막. 실제 녹음과 무관하다. */
export function demoRecordingStudy(): RecordingStudyResults {
  const transcript: TranscriptLine[] = [
    { at: 0, text: "자, 오늘 볼 내용은 지난 시간에 배운 파이프라인입니다." },
    { at: 12, text: "파이프라인 단계가 다섯 개 있었죠. 첫 번째가 IF, 명령어 인출입니다." },
    { at: 31, text: "그다음 ID 명령어 해독, EX 실행, MEM 메모리 접근, WB 레지스터 쓰기입니다. 이 다섯 단계는 외워 두세요." },
    { at: 58, text: "한 명령어가 다음 단계로 넘어가면 비는 자리에 다음 명령어가 들어옵니다." },
    { at: 68, text: "그래서 한 시점에 여러 명령어가 서로 다른 단계에서 동시에 실행됩니다. 이게 파이프라인 실행이에요." },
    { at: 86, text: "그런데 한 번에 하나씩 실행할 때는 없던 문제가 여기서 생깁니다." },
    { at: 107, text: "어떤 명령어가 예정된 사이클에 실행되지 못하는 경우, 이걸 해저드라고 합니다." },
    { at: 125, text: "해저드는 세 가지예요. 구조적 해저드, 데이터 해저드, 제어 해저드." },
    { at: 150, text: "구조적 해저드는 두 명령어가 같은 하드웨어 자원을 동시에 쓰려고 할 때 생깁니다. 명령어 인출과 데이터 메모리 접근이 같은 메모리를 쓰면 부딪히죠." },
    { at: 178, text: "그래서 명령어 메모리와 데이터 메모리를 나누면 이 문제를 피할 수 있어요." },
    { at: 200, text: "데이터 해저드는 앞 명령어의 결과가 레지스터에 쓰이기 전에 뒤 명령어가 그 값을 읽으려 할 때 생깁니다." },
    { at: 232, text: "가장 단순한 해결은 기다리는 거예요. 버블, 그러니까 스톨을 넣습니다." },
    { at: 255, text: "더 좋은 방법은 포워딩입니다. EX 단계의 결과를 레지스터에 쓰기 전에 바로 다음 명령어로 넘겨 줘요." },
    { at: 284, text: "그래도 load 바로 뒤에 그 값을 쓰는 명령어가 오면 한 사이클은 멈춰야 합니다. load-use 해저드라고 해요." },
    { at: 310, text: "컴파일러가 명령어 순서를 바꿔 이 빈자리를 메우기도 합니다. 코드 스케줄링이라고 하죠." },
    { at: 340, text: "제어 해저드는 분기 명령어 때문에 생깁니다. 다음에 실행할 명령어의 주소를 아직 모르는 거예요." },
    { at: 365, text: "분기 결과를 예측해서 일단 실행하고, 틀리면 그 명령어들을 버립니다. 분기 예측입니다." },
    { at: 392, text: "시험에는 파이프라인 실행 과정을 그리고 해저드가 생기는 곳을 표시하는 문제가 나올 수 있어요." },
  ];
  const sections: StudySection[] = [
    {
      title: "5단계 파이프라인",
      at: 12,
      points: [
        { term: "5단계", text: "명령어 인출(IF), 해독(ID), 실행(EX), 메모리 접근(MEM), 레지스터 쓰기(WB)로 구성됨. 단계 이름은 외워야 할 기본 사항으로 강조됨.", at: 31 },
        { term: "파이프라인 실행", text: "한 명령어가 다음 단계로 넘어가면 다음 명령어가 그 단계에 들어와, 여러 명령어가 한 시점에 겹쳐 실행됨.", at: 68 },
      ],
    },
    {
      title: "해저드와 세 가지 종류",
      at: 86,
      points: [
        { term: "해저드(hazard)", text: "명령어들이 겹쳐 실행되면서 어떤 명령어가 예정된 사이클에 실행되지 못하는 경우.", at: 107 },
        { term: "세 가지 해저드", text: "구조적·데이터·제어 해저드로 나뉨. 강의 전반부는 구조적·데이터 해저드를 다룸.", at: 125 },
      ],
      table: {
        head: ["해저드 종류", "발생 원인", "강의에서 든 상황"],
        rows: [
          ["구조적 해저드", "하드웨어 자원 충돌", "명령어 인출과 데이터 메모리 접근이 같은 메모리를 동시에 요구함"],
          ["데이터 해저드", "명령어 사이의 데이터 의존성", "앞 명령어의 결과가 레지스터에 쓰이기 전에 뒤 명령어가 그 값을 읽으려 함"],
          ["제어 해저드", "다음 명령어 주소를 아직 모름", "분기 명령어의 분기 여부와 대상 주소가 정해지기 전임"],
        ],
      },
    },
    {
      title: "데이터 해저드 해결",
      at: 200,
      points: [
        { term: "스톨(버블)", text: "결과가 준비될 때까지 뒤 명령어를 기다리게 하는 가장 단순한 방법.", at: 232 },
        { term: "포워딩", text: "EX 단계의 결과를 레지스터에 쓰기 전에 다음 명령어로 바로 넘겨 기다림을 줄임.", at: 255 },
        { term: "load-use 해저드", text: "load 바로 뒤에 그 값을 쓰면 포워딩으로도 한 사이클은 멈춰야 함.", at: 284 },
        { term: "코드 스케줄링", text: "컴파일러가 명령어 순서를 바꿔 멈추는 자리를 다른 명령어로 메움.", at: 310 },
      ],
    },
    {
      title: "제어 해저드와 시험 안내",
      at: 340,
      points: [
        { term: "분기 예측", text: "분기 결과를 예측해 먼저 실행하고, 틀리면 실행한 명령어를 버림.", at: 365 },
        { term: "시험", text: "파이프라인 실행 과정을 그리고 해저드가 생기는 곳을 표시하는 문제가 나올 수 있음.", at: 392 },
      ],
    },
  ];
  return {
    transcription: { status: "ready", result: { segments: transcript, text: transcript.map((line) => line.text).join("\n") } },
    summary: {
      status: "ready",
      result: {
        overview: "5단계 파이프라인의 동작과 구조적·데이터·제어 해저드, 데이터 해저드를 줄이는 스톨·포워딩·코드 스케줄링을 다룸. 시험에서 파이프라인 실행과 해저드를 표시하는 문제가 나올 수 있음을 강조함.",
        sections,
        note: "목업 전사·요약입니다. 이 녹음을 실제로 전사하거나 요약한 결과가 아닙니다.",
      },
    },
  };
}
