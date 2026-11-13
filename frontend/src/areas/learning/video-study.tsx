import { useEffect, useReducer, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { listCoursePdfs, ensureCoursePdfText, type CoursePdfRecord } from "../../shared/course-pdfs.ts";
import { createCoursePdfViewer } from "../../shared/document-viewer.tsx";
import { mediaForActivity } from "../../media.ts";
import type { Activity, Course } from "../../data.ts";

type Phase = "transcribing" | "summarizing" | "done";
type StudyTab = "summary" | "transcript" | "materials";

interface StudySession {
  phase: Phase;
  tab: StudyTab;
  listeners: Set<() => void>;
}

// Demo jobs are keyed by professor-published activity, not a student upload. They keep running across navigation.
const sessions = new Map<string, StudySession>();
const DEMO = [
  { at: 0, text: "이번 강의에서는 핵심 개념을 정의하고, 예제를 통해 적용 과정을 살펴봅니다." },
  { at: 18, text: "문제를 풀기 전에 입력 조건과 가정을 먼저 확인해야 합니다." },
  { at: 42, text: "같은 방법을 다른 사례에 적용하고 결과의 차이를 비교해 보세요." },
];

function demoSession(activityId: string): StudySession {
  const known = sessions.get(activityId);
  if (known) return known;
  const session: StudySession = { phase: "transcribing", tab: "summary", listeners: new Set() };
  sessions.set(activityId, session);
  for (const [delay, phase] of [[2500, "summarizing"], [5500, "done"]] as const) {
    setTimeout(() => {
      session.phase = phase;
      session.listeners.forEach((update) => update());
    }, delay);
  }
  return session;
}

interface OverviewPoint {
  page: number;
  text: string;
}

const isPoint = (value: unknown): value is OverviewPoint =>
  typeof value === "object" && value !== null && "text" in value && typeof value.text === "string" && "page" in value;

const isPage = (value: unknown): value is { number: number; text: string } =>
  typeof value === "object" && value !== null && "number" in value && typeof value.number === "number" && "text" in value && typeof value.text === "string";

/** The activity the study layout is about: a sample activity, or a backend lecture (`srv-<id>`, no clip). */
export interface StudyActivity {
  id: Activity["id"];
  title: string;
  courseId?: Activity["courseId"];
  clipId?: Activity["clipId"];
}

export interface VideoStudyOptions {
  /** A backend lecture: only the course's real linked PDFs; the demo transcript and summary would pass for server data. */
  server?: boolean;
}

// `{ server: true }`: a backend lecture. Only the real linked PDFs of the course are shown; the
// demo transcript and summary are sample text and would pass for server data.
export function videoStudy(course: Course, activity: StudyActivity, player: ReactNode, seekTo: (seconds: number) => void, { server = false }: VideoStudyOptions = {}): ReactNode {
  return <VideoStudy key={activity.id} course={course} activity={activity} player={player} seekTo={seekTo} server={server} />;
}

function VideoStudy({ course, activity, player, seekTo, server }: { course: Course; activity: StudyActivity; player: ReactNode; seekTo: (seconds: number) => void; server: boolean }) {
  const intro = server ? "교수님이 등록한 강의 영상 · 오른쪽에서 이 과목의 PDF 자료를 함께 볼 수 있습니다." : "교수님이 등록한 강의 영상 · 전사·요약은 외부 API 없는 목업입니다.";
  const [session] = useState<StudySession>(() => (server ? { phase: "done", tab: "materials", listeners: new Set() } : demoSession(activity.id)));
  const [message, setMessage] = useState(intro);
  const [tab, setTab] = useState<StudyTab>(server ? "materials" : session.tab);
  const [docs, setDocs] = useState<CoursePdfRecord[]>([]);
  const [selectedDoc, setSelectedDoc] = useState("");
  const [, redraw] = useReducer((n: number) => n + 1, 0);
  const tabList = useRef<HTMLDivElement>(null);
  const focusTab = useRef(false);

  useEffect(() => {
    session.listeners.add(redraw);
    return () => {
      session.listeners.delete(redraw);
    };
  }, [session]);

  useEffect(() => {
    let live = true;
    listCoursePdfs(course.id).then((records) => {
      if (live) setDocs(records.filter((doc) => doc.courseId === course.id));
    }).catch(() => {
      if (live) setMessage("연결 자료를 불러오지 못했습니다. 브라우저 저장소 접근 설정을 확인해 주세요.");
    });
    return () => {
      live = false;
    };
  }, [course.id]);

  useEffect(() => {
    if (!focusTab.current) return;
    focusTab.current = false;
    tabList.current?.querySelector<HTMLElement>('[aria-selected="true"]')?.focus();
  }, [tab]);

  const doc = docs.find((item) => item.id === selectedDoc) || docs[0];
  const reading = tab === "materials" && doc !== undefined && doc.remote === true && doc.pageCount == null;
  useEffect(() => {
    if (!reading || !doc) return;
    let live = true;
    // Server PDFs are read in this tab on first use, then the panel is drawn again.
    ensureCoursePdfText(doc).then(() => {
      if (live) redraw();
    }, () => {
      if (doc.pageCount == null) Object.assign(doc, { pageCount: 0, status: "no-text" });
      if (live) redraw();
    });
    return () => {
      live = false;
    };
  }, [reading, doc]);

  const tabs: [StudyTab, string][] = server ? [["materials", "연결된 PDF"]] : [["summary", "영상 요약"], ["transcript", "전사"], ["materials", "연결된 PDF"]];
  const choose = (key: StudyTab) => {
    session.tab = key;
    focusTab.current = true;
    setTab(key);
  };
  const onTabKey = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    const next = event.key === "Home" ? 0 : event.key === "End" ? tabs.length - 1 : (index + (event.key === "ArrowRight" ? 1 : -1) + tabs.length) % tabs.length;
    choose(tabs[next][0]);
  };
  const ready = server || session.phase === "done";

  let body: ReactNode;
  if (tab === "materials") {
    if (!doc) body = <p className="vs-note">이 과목에 연결된 PDF가 없습니다. 교수 화면에서 등록한 자료가 여기에 표시됩니다.</p>;
    else {
      const select = (
        <select aria-label="연결 자료 선택" value={doc.id} onChange={(event) => setSelectedDoc(event.target.value)}>
          {docs.map((item) => <option key={item.id} value={item.id}>{item.title}</option>)}
        </select>
      );
      if (reading) {
        body = <>{select}<p className="vs-note" role="status">PDF 원문을 읽는 중입니다.</p></>;
      } else {
        const overview: readonly unknown[] = doc.overview;
        const pages: readonly unknown[] = doc.pages;
        const points = overview.filter(isPoint);
        body = (
          <>
            {select}
            {createCoursePdfViewer({ id: doc.id, pageCount: doc.pageCount, title: doc.title, fileName: doc.fileName, pages: pages.filter(isPage) }, { compact: true })}
            <p className="vs-note">자료 개요 · PDF 원문 발췌 (AI 요약 아님)</p>
            {points.length
              ? <ul className="vs-points">{points.map((point, index) => <li key={index}>{point.text}<small>{`${point.page}쪽`}</small></li>)}</ul>
              : <p className="vs-note">텍스트를 추출할 수 없는 자료입니다. 뷰어에서 원본 쪽을 확인하세요.</p>}
          </>
        );
      }
    }
  } else if (!ready) {
    body = (
      <>
        <h3>{session.phase === "transcribing" ? "백그라운드에서 전사 중" : "핵심 내용을 정리하는 중"}</h3>
        <progress max={100} value={session.phase === "transcribing" ? 35 : 75} aria-label="데모 처리 진행률" />
        <p className="vs-note">영상을 재생하거나 연결 자료를 보면서 기다릴 수 있습니다. 화면을 이동해도 데모 처리는 계속됩니다.</p>
      </>
    );
  } else if (tab === "transcript") {
    const cues = mediaForActivity({ id: activity.id, courseId: activity.courseId ?? "", clipId: activity.clipId }).cues.map((cue) => ({ at: cue.start, text: cue.text }));
    const lines = cues.length ? cues : DEMO;
    body = (
      <>
        <p className="vs-note">{cues.length ? "자막과 같은 문장입니다. 실제 음성 분석은 하지 않습니다." : "아래는 강의 음성과 무관한 목업 전사입니다."}</p>
        {lines.map((line, index) => (
          <div key={index} className="vs-transcript">
            <button type="button" className="vs-time" aria-label={`${line.at}초로 이동`} onClick={() => seekTo(line.at)}>
              {`${String(Math.floor(line.at / 60)).padStart(2, "0")}:${String(line.at % 60).padStart(2, "0")}`}
            </button>
            <p>{line.text}</p>
          </div>
        ))}
      </>
    );
  } else {
    body = (
      <>
        <h3>핵심 요약</h3>
        <p className="vs-note">실제 강의 분석 결과가 아닌 목업 요약입니다.</p>
        <ul className="vs-points">{DEMO.map((line) => <li key={line.at}>{line.text}</li>)}</ul>
        <h3>학습 체크</h3>
        <p>핵심 개념을 자신의 말로 설명하고, 연결 자료의 예제와 비교해 보세요.</p>
      </>
    );
  }

  return (
    <section className="video-study" aria-label="강의 영상 학습모드" data-item-open={activity.id}>
      <p className="vs-note" role="status">{message}</p>
      <div className="vs-stage">
        <div className="vs-layout">
          <div className="vs-main">
            <div className="vs-mode"><span className="vs-mode-label">학습모드</span><span>{activity.title}</span></div>
            {player}
            <p className="vs-note">전체화면에서는 강의 플레이어만 표시됩니다. Esc로 돌아오면 학습 노트를 계속 볼 수 있습니다.</p>
          </div>
          <aside className="vs-side" aria-label="영상 및 연결 자료 요약">
            <header className="vs-side-header"><strong>학습 노트</strong>{server ? null : <span className="vs-demo">MOCK</span>}</header>
            <div className="vs-tabs" role="tablist" aria-label="학습 정보" ref={tabList}>
              {tabs.map(([key, label], index) => (
                <button
                  key={key}
                  type="button"
                  role="tab"
                  id={`vs-${course.id}-${key}`}
                  aria-controls={`vs-${course.id}-panel`}
                  tabIndex={tab === key ? 0 : -1}
                  aria-selected={tab === key ? "true" : "false"}
                  className={`vs-tab${tab === key ? " on" : ""}`}
                  onClick={() => choose(key)}
                  onKeyDown={(event) => onTabKey(event, index)}
                >{label}</button>
              ))}
            </div>
            {server ? null : <p className="vs-status" role="status" aria-live="polite">{ready ? "데모 전사·요약 완료" : session.phase === "transcribing" ? "전사 중 · 데모" : "요약 중 · 데모"}</p>}
            <div className="vs-side-body" id={`vs-${course.id}-panel`} role="tabpanel" aria-labelledby={`vs-${course.id}-${tab}`}>{body}</div>
          </aside>
        </div>
      </div>
    </section>
  );
}
