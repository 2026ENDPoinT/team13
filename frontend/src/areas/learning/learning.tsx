import { Fragment, cloneElement, isValidElement, useEffect, useRef, useState, type CSSProperties, type ReactNode } from "react";
import { Icon } from "../../ui/icon.tsx";
import { Badge, Button, EmptyState, Field, Tabs } from "../../ui/kit.tsx";
import { Link, GoButton, navigate } from "../../router.tsx";
import { activities, events, courseById, courses, people, files, surveyQuestions } from "../../data.ts";
import type { Activity, AppState, Course, Message } from "../../data.ts";
import type { Screen, ScreenCtx } from "../../types.ts";
import { hrefFor, find } from "../../manifest.ts";
import { typeLabel, attendStatus, statusLabel, dday, playerHref } from "../../shared/format.ts";
import { currentRole, routeFor } from "../../shared/routes.ts";
import { isClosedActivity } from "../../shared/activity-library.ts";
import { richNodes } from "../../shared/rich-text.tsx";
import { getActivityFile } from "../../shared/activity-files.ts";
import { videoStudy } from "./video-study.tsx";
import { getState, save, update, toast } from "../../store.ts";
import { readForm, showErrors, stamp } from "../../forms.ts";
import { mediaFile, mediaForActivity } from "../../media.ts";
import { createDocumentViewer } from "../../shared/document-viewer.tsx";
import { ensurePlayerChrome, PlayerIcon, usePlayControlFade, usePlayerFullscreen, togglePlayerFullscreen } from "../../shared/player-controls.ts";
import { remoteAssignmentDetail } from "./assignments-server.tsx";
import { backendStatus } from "../../shared/backend.ts";
import { backendPanel } from "../../shared/backend-prompt.tsx";
import { serverWeekCalendar, serverTimetable } from "./calendar-server.tsx";
import { usesServerMessages, serverMessages } from "./messages-server.tsx";
import { remoteQuizDetail } from "./quizzes-server.tsx";
import { remoteSurveyDetail } from "./surveys-server.tsx";
import { remoteAttendance, pickAttendanceWeek } from "./attendance-server.tsx";
import { serverPreviewScreen } from "./preview-server.tsx";
import { remoteLectureDetail, remoteLecturePlayer } from "./lectures-server.tsx";
import { remoteMaterialDetail, remoteActivityLink, sampleInServerCourse } from "./activity-server.tsx";
import { describe } from "../../shared/assistant/resolve.ts";
import { hrefFor as assistantHref, nextQueued } from "../../shared/assistant/run.ts";
import { completeStep, completionToken, runState } from "../../shared/assistant/plan.ts";
import { refreshTray, syncPlanProgress } from "../../shared/assistant-tray.tsx";

declare module "../../data.ts" {
  interface PlayerState { captions?: boolean }
  interface Message { course?: string }
  interface AppState {
    calOffset?: number;
    ttOffset?: number;
    playerPanel?: string;
    previewZoom?: number;
    summaryOpen?: boolean;
    summaryChoice?: string;
    summaryFlip?: boolean;
    attendanceWeek?: number;
  }
}

export interface ActivityParts {
  metaTable: (rows: [string, ReactNode][]) => ReactNode;
  typeLine: (type: string, titleTag?: ReactNode, iconName?: string) => ReactNode;
}
export function surveyRecord(value: unknown): Record<string, unknown> {
  return typeof value === "object" && value !== null ? Object.fromEntries(Object.entries(value)) : {};
}

const DOW = ["일", "월", "화", "수", "목", "금", "토"];
const TYPE_ICON: Record<string, string> = { video: "play", zoom: "users", material: "file", report: "file", test: "check", survey: "chat", post: "pen" };

const EXTRA_MAIL = [
  { id: "msg4", folder: "inbox", from: "김서연", to: "박민준", title: "실습 데이터 형식", body: "표는 CSV로 올릴까요? 개인을 가리키는 열은 빼고, 열 이름은 질문과 같게 맞추겠습니다.", at: "2026-11-07 11:20", read: true },
  { id: "msg5", folder: "inbox", from: "학습지원", to: "박민준", title: "출석 인정 기준", body: "영상은 인정 구간을 90% 넘게 보면 출석입니다. 기간이 끝나면 재생 버튼이 잠깁니다. 이 쪽지는 샘플입니다.", at: "2026-11-04 08:40", read: false },
  { id: "msg6", folder: "inbox", from: "이지은", to: "박민준", title: "팀 메모 공유", body: "그룹 메뉴가 비어 있어서 쪽지로 남깁니다. 주제는 공공 데이터 사례 하나로 좁혔습니다.", at: "2026-11-03 16:05", read: true },
  { id: "msg7", folder: "sent", from: "박민준", to: "김서연", title: "데이터는 CSV로", body: "식별되는 열 없이 CSV로 부탁합니다. 빠지는 열은 한 줄로 적어 주세요.", at: "2026-11-07 12:02", read: true },
];

const PAGES = [
  {
    kicker: "경영정보 · 5주차",
    title: "사례 데이터 묶음",
    body: "이 노트는 샘플입니다. 교과서를 베끼지 않았고, 제출 전에 무엇을 가를지만 적습니다. 한 행은 사례 하나, 열 이름은 곧 질문입니다.",
  },
  {
    kicker: "2쪽 · 전처리",
    title: "남길 열과 뺄 열",
    body: "빈 칸과 중복 행을 그대로 두면 집계가 흔들립니다. 빼는 열마다 이유를 한 줄 적고, 사람을 가리키는 원문은 파일에 넣지 않습니다.",
  },
  {
    kicker: "3쪽 · 해석",
    title: "범위 안에서만 결론",
    body: "건수와 기간을 먼저 씁니다. 표본이 작은 주제를 전체인 것처럼 쓰지 않습니다. 결과 문장은 학습자가 직접 작성합니다.",
  },
];

const SUMMARY = {
  summary: [
    { title: "이 자료가 다루는 것", body: "5주차 사례 묶음은 공공 데이터로 주제를 고르고, 개인이 식별되는 원문은 빼는 절차를 적습니다. 오른쪽 탭은 같은 노트의 개념, 퀴즈, 카드입니다." },
    { title: "수집 전에 가를 것", pages: "1쪽", problem: "자주 보는 표와 한 번만 보는 원자료를 한 파일에 두면 나중에 다시 찾기 어렵습니다.", answer: "사례 표, 원자료, 메모를 나누고 쪽 번호를 적습니다." },
    { title: "전처리에서 남길 것", pages: "2쪽", problem: "빈 칸과 중복 행을 지우지 않으면 건수가 흔들립니다.", answer: "빠지는 열의 이유와 남기는 열의 기준을 한 줄씩 적습니다." },
    { title: "해석에서 피할 것", pages: "3쪽", problem: "표본이 작은 주제를 전체인 것처럼 쓰면 결론이 셉니다.", answer: "건수와 기간을 먼저 적고, 결론은 그 범위 안에서만 씁니다." },
  ],
  concepts: [
    { term: "사례 표", body: "한 행이 사례 하나인 표입니다. 열 이름이 질문과 같아야 다시 읽을 수 있습니다." },
    { term: "식별 열", body: "이름이나 연락처처럼 사람을 가리키는 열입니다. 이 샘플 제출에서는 뺍니다." },
    { term: "범위", body: "건수와 기간. 결론보다 먼저 적으면 해석이 그 밖으로 나가지 않습니다." },
  ],
  quiz: {
    q: "사례 표를 제출할 때 이 노트에서 권하는 방법은 어느 쪽인가요?",
    options: [
      { id: "a", text: "식별되는 원문까지 한 파일에 넣는다" },
      { id: "b", text: "사례 표와 원자료를 나누고, 빼는 열의 이유를 적는다" },
      { id: "c", text: "행 수를 적지 않고 결론만 쓴다" },
    ],
    answer: "b",
  },
  card: { front: "사례 표", back: "한 행이 사례 하나이고, 열 이름이 곧 질문인 표." },
};

const BLOCKS = [
  { day: 0, start: 10, span: 2, id: "ai" },
  { day: 1, start: 10, span: 3, id: "se" },
  { day: 1, start: 14, span: 2, id: "algo" },
  { day: 2, start: 10, span: 3, id: "bi" },
  { day: 2, start: 15, span: 2, id: "ux" },
  { day: 3, start: 13, span: 2, id: "db" },
  { day: 4, start: 9, span: 3, id: "oss" },
];

function pad(n: number): string { return String(n).padStart(2, "0"); }
function koDate(date: Date): string { return `${date.getFullYear()}.${pad(date.getMonth() + 1)}.${pad(date.getDate())} (${DOW[date.getDay()]})`; }
function isoOf(date: Date): string { return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`; }
function metaTable(rows: [string, ReactNode][]): ReactNode {
  return <div className="meta-table">{rows.map(([key, value]) => <div className="meta-row" key={key}><div className="meta-k">{key}</div><div className="meta-v">{value == null || value === "" ? "—" : value}</div></div>)}</div>;
}
function koWhen(value?: string): string {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : `${koDate(date)} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
function typeLine(type: string, titleTag?: ReactNode, iconName?: string): ReactNode {
  return <div className="type-line"><span className={`type-ico ${type}`} aria-hidden="true"><Icon name={iconName || TYPE_ICON[type] || "file"} /></span>{titleTag || <span>{typeLabel(type)}</span>}</div>;
}
function fileRow(name: string, meta?: string, href?: string): ReactNode {
  return <div className="file-row" key={name}><Icon name="file" /><span><b>{name}</b><span className="tiny">{meta}</span></span>{href ? <Link className="btn btn-secondary" href={href}>미리보기</Link> : null}</div>;
}
function seedMail(state: AppState): void {
  if (state.draft.mailSeeded) return;
  state.draft.mailSeeded = true;
  for (const message of EXTRA_MAIL) if (!state.messages.some((item) => item.id === message.id)) state.messages.push({ ...message });
}
function shift(field: "calOffset" | "ttOffset", dir: number): void { update((state) => { state[field] = (state[field] || 0) + dir; }); }
function renderWeek(ctx: ScreenCtx): ReactNode {
  if (backendStatus() === "ready") return serverWeekCalendar(ctx);
  const start = new Date(2026, 10, 8 + (ctx.state.calOffset || 0) * 7);
  const days = Array.from({ length: 7 }, (_, i) => new Date(start.getFullYear(), start.getMonth(), start.getDate() + i));
  return <section>
    {backendStatus() === "anon" ? backendPanel({ text: "ASTRA 서버에 로그인하면 내 과목의 마감과 학사일정을 서버에서 불러옵니다." }) : null}
    <div className="cal-head"><h1>{`${days[0].getFullYear()}년 ${days[0].getMonth() + 1}월`}</h1><Tabs items={[{ label: "월", href: hrefFor(find("00_main_home")) }, { label: "주", on: true }]} /><div className="cal-nav">
      <button type="button" className="btn btn-ghost" aria-label="이전 주" onClick={() => shift("calOffset", -1)}>‹</button><button type="button" className="btn btn-ghost" aria-label="다음 주" onClick={() => shift("calOffset", 1)}>›</button>
    </div></div>
    <p className="subhead">{`${koDate(days[0])} – ${koDate(days[6])}`}</p>
    <div className="cal week-cal">{days.map((date) => <div key={`h${isoOf(date)}`} className={`cal-dow${date.getDay() === 0 ? " sun" : date.getDay() === 6 ? " sat" : ""}`}>{DOW[date.getDay()]}</div>)}
      {days.map((date) => {
        const iso = isoOf(date);
        return <GoButton key={iso} className={`cal-day${iso === "2026-11-10" ? " today" : ""}`} href={hrefFor(find("07_home_calendar_week"), { pop: "day", day: iso })}>
          <div className={`day-num${date.getDay() === 0 ? " sun" : date.getDay() === 6 ? " sat" : ""}`}>{date.getDate()}</div>
          {events.filter((item) => item.date === iso).map((ev, i) => <span key={i} className={`pill ${ev.type}`}>{`${courseById(ev.courseId).ko} ${ev.title}`}</span>)}
        </GoButton>;
      })}
    </div>
  </section>;
}
function renderTimetable(ctx: ScreenCtx): ReactNode {
  const close = <div className="page-head"><h1>수업시간표</h1><Link href={hrefFor(find("00_main_home"))} className="btn btn-ghost">닫기</Link></div>;
  if (backendStatus() === "ready") return <section className="timetable">{close}{serverTimetable(ctx)}</section>;
  const monday = new Date(2026, 10, 9 + (ctx.state.ttOffset || 0) * 7);
  const friday = new Date(monday); friday.setDate(monday.getDate() + 4);
  const names = ["월", "화", "수", "목", "금"];
  return <section className="timetable">{close}
    {backendStatus() === "anon" ? backendPanel({ text: "ASTRA 서버에 로그인하면 이번 학기 수업 시간을 서버에서 불러옵니다." }) : null}
    <div className="tt-nav"><button type="button" className="btn btn-ghost" aria-label="이전 주" onClick={() => shift("ttOffset", -1)}>‹</button><strong>{`${koDate(monday)} – ${koDate(friday)}`}</strong><button type="button" className="btn btn-ghost" aria-label="다음 주" onClick={() => shift("ttOffset", 1)}>›</button></div>
    <p className="tiny">칸 색은 과목마다 다릅니다. 이 샘플의 요일과 교시는 학기 내내 같고, 화살표는 표시 주만 바꿉니다.</p>
    <div className="tt" role="grid" aria-label="주간 수업시간표"><div className="tt-h" />{names.map((name) => <div key={name} className="tt-h">{name}</div>)}
      {Array.from({ length: 10 }, (_, i) => i + 9).map((hour) => <Fragment key={hour}><div className="tt-time">{`${pad(hour)}:00`}</div>{names.map((name) => <div key={name} className="tt-slot" />)}</Fragment>)}
      {BLOCKS.map((block) => { const course = courseById(block.id); return <div key={block.id} className={`tt-block ${course.gradient}`} style={{ gridColumn: block.day + 2, gridRow: `${block.start - 7} / span ${block.span}` }}><b>{course.ko}</b><span>{`${pad(block.start)}:00–${pad(block.start + block.span)}:00`}</span><span>{`${course.instructor} · ${course.room}`}</span></div>; })}
    </div>
    <div className="tt-list">{names.map((name, index) => { const mine = BLOCKS.filter((block) => block.day === index); return <section key={name} className="att-card"><h2>{name}</h2>{!mine.length ? <p className="tiny">수업 없음</p> : mine.map((block) => { const course = courseById(block.id); return <div key={block.id} className="tt-line"><span className="rail-dot" /><span><b>{course.ko}</b><span className="tiny">{`${pad(block.start)}:00–${pad(block.start + block.span)}:00 · ${course.room}`}</span></span></div>; })}</section>; })}</div>
  </section>;
}
function videoDetail(a: Activity, course: Course): ReactNode {
  const expired = isClosedActivity(a), progress = a.status === "done" ? 100 : a.progress || 0;
  return <div className="act-body">{typeLine(a.type)}<h1>{a.title}</h1><p className="tiny">{`${course.ko} · ${a.minutes || 0}분`}</p>
    <section className="callout"><h2>주의사항</h2><ul><li>출석으로 인정되려면 영상의 인정 구간을 끝까지 시청해야 합니다.</li><li>학습 기간이 끝나면 재생할 수 없습니다. 기간 밖 시청은 출석에 포함되지 않습니다.</li><li>배속은 이 브라우저에서만 적용되고, 출석 시간은 1배속으로 계산합니다.</li><li>이 샘플은 영상 파일을 받지 않습니다. 재생 위치만 이 기기에서 움직입니다.</li></ul></section>
    {metaTable([["출석부 반영일", "학습을 마친 뒤"], ["학습기간", a.attendStart || a.start || a.due ? `${koWhen(a.attendStart || a.start)} – ${koWhen(a.attendEnd || a.due)}` : `${a.week}주차`]])}
    <div className="flag-row" role="group" aria-label="학습 기간"><span className="flag on">기간 학습</span><span className="flag">기간외 학습</span></div>
    <article className="progress-card"><div className="progress-pct">{progress}%</div><div><b>{a.title}</b><p className="tiny">{`학습시간 ${a.minutes || 0}분 · 출석인정 ${progress >= 90 ? "완료" : "미달"}`}</p><div className="bar" aria-hidden="true"><i style={{ width: `${progress}%` }} /></div></div></article>
    <div className="btn-row">{expired ? <Button kind="primary" disabled>학습 기간 종료</Button> : <Link className="btn btn-primary" href={playerHref(a, course.id)}>학습하기</Link>}</div>
  </div>;
}
function zoomDetail(a: Activity, course: Course): ReactNode {
  return <div className="act-body">{typeLine("zoom")}<h1>{a.title}</h1><p className="lead">{`${course.ko} 실시간 수업입니다. 이 샘플은 외부 회의에 접속하지 않습니다.`}</p>
    {metaTable([["일시", a.when || (a.start ? `${koWhen(a.start)} – ${koWhen(a.end)}` : "—")], ["회의 링크", a.meetingUrl ? <a className="pf-meeting" href={a.meetingUrl} rel="noopener noreferrer">{a.meetingUrl}</a> : "—"], ["회의 번호", a.meeting || "—"], ["비밀번호", a.pass || "—"], ["장소", course.room]])}
    {a.body ? <div className="prose"><p>{a.body}</p></div> : null}<div className="btn-row"><Button kind="primary" disabled>입장</Button></div><p className="tiny">입장 버튼은 꺼 두었습니다. 회의 번호만 확인할 수 있습니다.</p>
  </div>;
}
async function downloadActivity(a: Activity): Promise<void> {
  try {
    const blob = await getActivityFile(a.fileId);
    if (!blob) return toast("이 브라우저에 저장된 파일을 찾을 수 없습니다.");
    const url = URL.createObjectURL(blob), link = document.createElement("a");
    link.href = url; link.download = a.fileName || "download"; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (error) { toast(error instanceof Error ? error.message : "파일을 불러오지 못했습니다."); }
}
function materialDetail(a: Activity, course: Course, ctx: ScreenCtx): ReactNode {
  const owned = files.filter((file) => file.courseId === course.id && (file.week === a.week || file.id === "f1"));
  const preview = hrefFor(find("65_file_preview"), { n: "f1", kj: course.id });
  return <div className="act-body">{typeLine("material")}<h1>{a.title}</h1>{a.body ? <div className="prose"><p>{a.body}</p></div> : null}{a.fileName ? <p className="tiny">{a.fileName}</p> : null}
    {a.fileId ? <div className="btn-row"><Button kind="primary" onClick={() => void downloadActivity(a)}>다운로드</Button></div> : null}
    {metaTable([["공개일", "2026.10.30 (금)"], ["작성자", course.instructor], ["조회수", "130"]])}<h2>첨부파일</h2>
    {owned.length ? owned.map((file) => fileRow(file.name, `${file.size}${file.week ? ` · ${file.week}주` : ""}`, preview)) : fileRow("week5-cases.pdf", "1.2 MB", preview)}
    <div className="btn-row"><Link href={preview} className="btn btn-primary">문서 미리보기</Link></div>{summaryAside(ctx.state, true)}
  </div>;
}
function reportDetail(a: Activity, ctx: ScreenCtx): ReactNode {
  const submitted = ctx.state.submissions[a.id];
  const due = <span className="due-line"><span>{koWhen(a.due)}</span>{a.due ? <span className="dday">{dday(a.due)}</span> : null}</span>;
  const attachments = submitted ? [{ name: submitted.fileName || a.fileName || "제출 파일", size: submitted.at }] : [{ name: "과제 안내.pdf", size: "220 KB" }, { name: "예시 데이터.csv", size: "840 KB" }];
  return <div className="act-body">{typeLine("report")}<h1>{a.title}</h1>{metaTable([["제출방식", "온라인"], ["제출형태", "글 입력 / 파일첨부"], ["공개일", koWhen(a.start)], ["마감일", due], ["배점", a.points != null ? `${a.points}점` : "—"], ["점수공개", "비공개"]])}
    <div className="prose"><p>{a.body || "과제 설명이 없습니다."}</p></div><h2>첨부파일</h2>{attachments.map((file) => fileRow(file.name, file.size))}
    {submitted ? <section className="note ok"><h2>제출됨</h2><p>{`${submitted.fileName || "파일 없음"} · ${submitted.at}`}</p>{submitted.body ? <p>{submitted.body}</p> : null}</section> : isClosedActivity(a) ? <p className="note">마감된 과제는 제출할 수 없습니다.</p> : <div className="btn-row"><Link className="btn btn-primary" href={`/ilos/cls/st/activity/activity_form.acl?m=report&n=${encodeURIComponent(a.id)}&kj=${encodeURIComponent(a.courseId)}&pop=submit`}>제출</Link></div>}
  </div>;
}
function testDetail(a: Activity, course: Course): ReactNode {
  const closed = Boolean(a.offline) || a.status === "graded" || isClosedActivity(a);
  return <div className="act-body">{typeLine("test")}<h1>{a.title}</h1><p className="tiny">{course.ko}</p>
    {metaTable([["시험형태", a.offline ? "대면 시험" : "온라인 시험"], ["응시기간", `${koWhen(a.start)} – ${koWhen(a.due)}`], ["배점", a.points != null ? `${a.points}점` : "—"], ["제한시간", a.minutes ? `${a.minutes}분` : "—"], ["결과공개", a.score != null ? `${a.score} / ${a.points}` : "비공개"]])}
    <div className="prose"><p>{a.body || "시험 안내가 없습니다."}</p></div><div className="btn-row"><Button kind="primary" disabled>{closed ? "응시 종료" : "응시"}</Button></div><p className="tiny">{closed ? "종료된 시험은 다시 열 수 없습니다." : "시작 시각 전에는 응시 버튼이 열리지 않습니다."}</p>
  </div>;
}
function postDetail(a: Activity): ReactNode {
  return <div className="act-body">{typeLine("post")}<h1>{a.title}</h1><div className="prose" data-post={a.id}>{richNodes(a.body || "")}</div>{isClosedActivity(a) ? <p className="note">마감된 글은 읽을 수만 있습니다.</p> : null}</div>;
}
function surveyDetail(a: Activity, ctx: ScreenCtx): ReactNode {
  const value = ctx.state.surveys[a.id];
  const saved = typeof value === "object" && value !== null ? value : null;
  const done = saved && "done" in saved && saved.done;
  const href = hrefFor(find(a.id === "nr-s" ? "71b_nr_survey_question_pop" : "64_activity_survey_detail"), { pop: "survey", n: a.id, kj: a.courseId });
  return <div className="act-body">{typeLine("survey")}<h1>{a.title}</h1>{metaTable([["기간", `마감 ${koWhen(a.due)}`], ["문항", `${surveyQuestions.length}문항`], ["상태", done ? "응답 저장됨" : "미응답"], ["남은 기간", dday(a.due) || "—"]])}
    <ol className="survey-preview">{surveyQuestions.map((q) => <li key={q.id}><b>{q.prompt}</b><span className="tiny">{saved && q.id in saved ? `저장한 답 · ${Reflect.get(saved, q.id)}` : q.options.join(" · ")}</span></li>)}</ol>
    {done ? <p className="note ok">응답이 이 브라우저에 저장되었습니다. 다시 열면 고칠 수 있습니다.</p> : null}<div className="btn-row"><Link className="btn btn-primary" href={href}>{done ? "응답 수정" : "설문 참여"}</Link></div>
  </div>;
}
function renderActivity(screen: Screen, ctx: ScreenCtx): ReactNode {
  const helpers = { metaTable, typeLine };
  if (screen.serverAssignment) return remoteAssignmentDetail(screen, ctx, helpers);
  if (screen.serverQuiz) return remoteQuizDetail(screen, ctx, helpers);
  if (screen.serverSurvey) return remoteSurveyDetail(screen, ctx, helpers);
  if (screen.serverLecture) return remoteLectureDetail(screen, ctx, helpers);
  if (screen.serverMaterial) return remoteMaterialDetail(screen, ctx, helpers);
  if (screen.serverLink) return remoteActivityLink(screen, ctx, helpers);
  const a = activities.find((item) => item.id === screen.activityId);
  if (!a) return <EmptyState title="활동을 찾을 수 없습니다" body="목록에서 다른 활동을 선택해 보세요." />;
  const blocked = sampleInServerCourse(a.courseId); if (blocked) return blocked;
  const course = courseById(a.courseId);
  const body = a.type === "video" ? videoDetail(a, course) : a.type === "zoom" ? zoomDetail(a, course) : a.type === "material" ? materialDetail(a, course, ctx) : a.type === "report" ? reportDetail(a, ctx) : a.type === "test" ? testDetail(a, course) : a.type === "post" ? postDetail(a) : surveyDetail(a, ctx);
  return <section className="bleed act-layout is-open" data-item-open={a.id}><div className="act-main">{body}</div></section>;
}
function clock(total: number): string {
  const seconds = Math.max(0, Math.round(total));
  return `${pad(Math.floor(seconds / 60))}:${pad(seconds % 60)}`;
}
function renderPlayer(screen: Screen, ctx: ScreenCtx): ReactNode {
  if (screen.serverLecture) return remoteLecturePlayer(screen, ctx);
  const a = activities.find((item) => item.id === (screen.activityId || "v-open"));
  const blocked = sampleInServerCourse(a ? a.courseId : screen.course, "영상");
  return blocked || <SamplePlayer key={a?.id || "sample"} activity={a} ctx={ctx} />;
}
/** `단원으로`: the classroom opens on this lecture's week, unfolded. */
function openUnit(week: number) {
  const state = getState();
  state.week = week;
  state.draft.closedWeeks = (state.draft.closedWeeks || []).filter((n: number) => n !== week);
  save();
}
function SamplePlayer({ activity: a, ctx }: { activity?: Activity; ctx: ScreenCtx }) {
  const media = mediaForActivity(a), p = ctx.state.player, id = a?.id || media.id;
  const root = useRef<HTMLElement>(null), video = useRef<HTMLVideoElement>(null), play = useRef<HTMLButtonElement>(null);
  const fade = usePlayControlFade(play), fullscreen = usePlayerFullscreen(root);
  const [resumeLabel, setResumeLabel] = useState(""), [next, setNext] = useState<ReactNode>(null);
  const token = useRef(completionToken(id));
  const ratio = p.duration ? Math.min(100, Math.round(p.seconds / p.duration * 100)) : 0;
  const captionsOn = p.captions !== false, panel = ctx.state.playerPanel || "";
  useEffect(() => { ensurePlayerChrome(); }, []);
  useEffect(() => {
    if (!a?.fileId || isClosedActivity(a)) return;
    let live = true, url: string | undefined;
    void getActivityFile(a.fileId).then((blob) => {
      if (!live || !blob || !video.current) return;
      url = URL.createObjectURL(blob);
      video.current.src = url; video.current.load();
    }).catch((error: unknown) => { if (live) toast(error instanceof Error ? error.message : "파일을 불러오지 못했습니다."); });
    return () => { live = false; if (url) URL.revokeObjectURL(url); };
  }, [a]);
  useEffect(() => {
    const node = video.current; if (!node) return;
    node.playbackRate = p.speed || 1;
    if (node.textTracks[0]) node.textTracks[0].mode = captionsOn ? "showing" : "hidden";
  }, [p.speed, captionsOn]);
  useEffect(() => { fade.present(p.playing); }, [p.playing, fade.present]);
  const sync = () => {
    const node = video.current; if (!node) return;
    update((state) => {
      const player = state.player;
      player.id = id; player.seconds = Number.isFinite(node.currentTime) ? node.currentTime : 0;
      if (Number.isFinite(node.duration) && node.duration > 0) player.duration = node.duration;
      player.playing = !node.paused && !node.ended;
      (player.positions ||= {})[id] = player.seconds;
      if (player.duration && player.seconds / player.duration >= .9) (player.watched ||= {})[id] = true;
    });
    syncPlanProgress(id);
  };
  const loaded = () => {
    const node = video.current; if (!node) return;
    const current = getState().player;
    const saved = Number(current.positions?.[id] ?? current.seconds) || 0;
    node.playbackRate = current.speed || 1;
    if (node.textTracks[0]) node.textTracks[0].mode = current.captions === false ? "hidden" : "showing";
    if (saved > .4 && Number.isFinite(node.duration) && saved < node.duration - .3) node.currentTime = saved;
    sync();
    const params = new URLSearchParams(location.search);
    if (params.get("autoplay") !== "1") return;
    params.delete("autoplay");
    history.replaceState(history.state, "", `${location.pathname}${params.size ? `?${params}` : ""}${location.hash}`);
    void node.play().catch(() => { if (saved > .4) setResumeLabel(`${clock(saved)}부터 이어보기`); play.current?.focus(); });
  };
  const togglePlay = () => {
    const node = video.current; if (!node) return;
    if (node.paused || node.ended) {
      if (node.ended) node.currentTime = 0;
      void node.play().catch(() => {});
    } else node.pause();
  };
  const seek = (seconds: number) => { if (video.current) { video.current.currentTime = Math.min(seconds, p.duration || media.seconds); sync(); } };
  const ended = () => {
    sync();
    if (completeStep(token.current, { navigate, event: "video-ended" })) {
      refreshTray(); if (runState()?.status === "done") setNext("오늘 계획 완료");
    } else if (!token.current) {
      const item = nextQueued(id), href = item && assistantHref(item);
      if (item && href) setNext(<Link href={href}>{`다음: ${describe(item).title} →`}</Link>);
    }
  };
  const changePanel = (name: string) => update((state) => { state.playerPanel = state.playerPanel === name ? "" : name; });
  const ask = () => {
    const data = readForm(), question = String(data.playerQ || "").trim();
    if (!question) return showErrors({ playerQ: "질문을 입력하세요" });
    update((state) => { state.draft.playerNotes = [...(state.draft.playerNotes || []), question]; state.playerPanel = "ask"; state.toast = "질문을 이 브라우저에 남겼습니다"; });
  };
  const player = <section ref={root} className="player-page is-open" data-item-open={a?.id || ""}>
    <div className="player-top"><h1>{a ? `[${a.week}주] ${a.title}` : "온라인 강의"}</h1><span className={`attend-pill${ratio >= 90 ? " ok" : ""}`}>{ratio >= 90 ? "출석 완료" : "출석 진행"}</span></div>
    <div className="player-stage" onClick={(event) => { if (!(event.target instanceof Element) || event.target.closest("button, a, input, textarea, select") || !p.playing) return; fade.present(true); }}>
      <video ref={video} className="lecture-video" poster={mediaFile(media.id, "jpg")} preload="auto" playsInline aria-label={a ? `${media.course} ${a.title}` : media.course}
        onLoadedMetadata={loaded} onTimeUpdate={sync} onPlay={() => { setResumeLabel(""); setNext(null); sync(); }} onPause={sync} onSeeked={sync} onRateChange={sync} onEnded={ended} onDoubleClick={(event) => { event.preventDefault(); void togglePlayerFullscreen(root.current); }}>
        <source src={mediaFile(media.id, "webm")} type="video/webm" /><source src={mediaFile(media.id, "mp4")} type="video/mp4" /><track kind="captions" src={mediaFile(media.id, "vtt")} srcLang="ko" label="한국어" default={captionsOn} />
      </video>
      <button ref={play} type="button" className={`play${fade.classes}`} data-glyph={p.playing ? "pause" : "play"} data-playback={p.playing ? "1" : "0"} aria-label={resumeLabel || (p.playing ? "일시정지" : "재생")} aria-pressed={p.playing} title={resumeLabel || undefined} onClick={togglePlay} onFocus={() => { if (p.playing) fade.present(true); }}><PlayerIcon name={p.playing ? "pause" : "play"} /></button>
      <p className="player-note">샘플 강의 영상입니다. 자막은 슬라이드와 같습니다.</p>{resumeLabel ? <p className="player-resume">{resumeLabel}</p> : null}{next ? <div className="player-next" role="status">{next}</div> : null}
    </div>
    <div className="player-chrome"><div className="player-seek"><span className="tiny">{clock(Math.min(p.seconds, p.duration || media.seconds))}</span><input type="range" min="0" max={p.duration || media.seconds} value={Math.floor(Math.min(p.seconds, p.duration || media.seconds))} aria-label="재생 위치" onChange={(event) => seek(Number(event.target.value))} /><span className="tiny">{clock(p.duration || media.seconds)}</span></div>
      <div className="player-tools">{[1, 1.25, 1.5, 2].map((speed) => <button key={speed} type="button" className={`btn btn-ghost${p.speed === speed ? " on" : ""}`} aria-pressed={p.speed === speed} onClick={() => update((state) => { state.player.speed = speed; })}>{speed}x</button>)}
        <button type="button" className={`btn btn-ghost${captionsOn ? " on" : ""}`} aria-pressed={captionsOn} onClick={() => update((state) => { state.player.captions = state.player.captions === false; })}>자막</button>
        <button type="button" className="btn player-full" data-glyph={fullscreen ? "exit" : "enter"} aria-label={fullscreen ? "전체화면 종료" : "전체화면"} aria-pressed={fullscreen} onClick={() => void togglePlayerFullscreen(root.current)}><PlayerIcon name={fullscreen ? "exit" : "enter"} /></button>
        {fullscreen ? null : <><button type="button" className={`btn btn-ghost${panel === "notes" ? " on" : ""}`} onClick={() => changePanel("notes")}>강의내용</button><button type="button" className={`btn btn-ghost${panel === "ask" ? " on" : ""}`} onClick={() => changePanel("ask")}>질의하기</button></>}{a ? <Link href={routeFor(currentRole(), "classroom", { kj: a.courseId, special: courseById(a.courseId).kind === "special" })} className="btn btn-secondary" onClick={() => openUnit(a.week)}>단원으로</Link> : null}
      </div>
    </div>
    {panel && !fullscreen ? <section className="player-sheet" aria-label={panel === "ask" ? "질의하기" : "강의내용"}><div className="player-sheet-h"><h2>{panel === "ask" ? "질의하기" : "강의내용"}</h2><button type="button" className="x" aria-label="닫기" onClick={() => changePanel(panel)}>×</button></div>
      {panel === "notes" ? <div className="prose"><p>{`${media.course} · ${media.week} · ${media.professor}`}</p>{media.cues.map((cue) => <p key={cue.start}>{`${clock(cue.start)} ${cue.text}`}</p>)}</div> : <div>{(ctx.state.draft.playerNotes || []).map((line: string, i: number) => <p className="note" key={i}>{line}</p>)}<Field label="질문" name="playerQ"><textarea data-name="playerQ" placeholder="이 차시에서 궁금한 점을 적으세요." /></Field><Button kind="primary" onClick={ask}>보내기</Button></div>}
    </section> : null}
  </section>;
  return a ? videoStudy(courseById(a.courseId), a, player, seek) : player;
}
function summaryAside(state: AppState, embed: boolean): ReactNode {
  const tab = state.summaryTab || "summary";
  const tabs = [["summary", "요약"], ["concepts", "핵심 개념"], ["quiz", "퀴즈"], ["cards", "플래시카드"]];
  const setTab = (id: string) => update((s) => { s.summaryTab = id; });
  let body: ReactNode;
  if (tab === "concepts") body = SUMMARY.concepts.map((concept) => <article key={concept.term} className="sum-block"><h3>{concept.term}</h3><p>{concept.body}</p></article>);
  else if (tab === "quiz") body = <><p className="lead">{SUMMARY.quiz.q}</p>{SUMMARY.quiz.options.map((option) => { const picked = state.summaryChoice === option.id; return <Fragment key={option.id}><button type="button" className={`btn btn-secondary quiz-opt${picked ? " on" : ""}`} aria-pressed={picked} onClick={() => update((s) => { s.summaryChoice = option.id; })}>{option.text}</button>{picked ? <p className="tiny">{option.id === SUMMARY.quiz.answer ? "샘플 정답입니다. 점수는 저장하지 않습니다." : "이 보기는 샘플 정답이 아닙니다."}</p> : null}</Fragment>; })}</>;
  else if (tab === "cards") body = <button type="button" className="flash" aria-pressed={Boolean(state.summaryFlip)} onClick={() => update((s) => { s.summaryFlip = !s.summaryFlip; })}><span className="tiny">{state.summaryFlip ? "뒷면" : "앞면"}</span><strong>{state.summaryFlip ? SUMMARY.card.back : SUMMARY.card.front}</strong><span className="tiny">눌러서 뒤집기</span></button>;
  else body = <><p>이 자료는 표를 사례 단위로 읽고, 열 이름을 질문으로 둡니다.</p><ol className="key-points"><li>한 행은 사례 하나입니다.</li><li>열 이름은 다시 읽을 질문입니다.</li><li>자주 보는 열과 가끔 보는 열을 나눕니다.</li><li>원문 문장은 옮기지 않았습니다.</li></ol>{SUMMARY.summary.map((block) => <article className="sum-block" key={block.title}><h3>{block.title}{block.pages ? <span className="page-chip">{block.pages}</span> : null}</h3>{block.body ? <p>{block.body}</p> : null}{block.problem ? <p><b>문제 </b>{block.problem}</p> : null}{block.answer ? <p><b>메모 </b>{block.answer}</p> : null}</article>)}</>;
  return <aside className={`summary-panel${embed ? " embed" : ""}${state.summaryOpen ? " open" : ""}`} aria-label="테크뇽 요약"><div className="sum-h"><Icon name="book" /><div><strong>테크뇽 요약</strong><span className="tiny">원문 3쪽</span></div><Badge tone="success">요약 완료</Badge>{embed ? null : <button type="button" className="x sum-close" aria-label="요약 닫기" onClick={() => update((s) => { s.summaryOpen = !s.summaryOpen; })}>×</button>}</div><div className="sum-tabs" role="tablist">{tabs.map(([id, label]) => <button key={id} type="button" role="tab" className={tab === id ? "on" : ""} aria-selected={tab === id} onClick={() => setTab(id)}>{label}</button>)}</div><div className="sum-b">{body}</div></aside>;
}
function renderPreview(ctx: ScreenCtx): ReactNode {
  const server = serverPreviewScreen(ctx); if (server) return server;
  const blocked = sampleInServerCourse(ctx.params.get("kj") || ctx.state.courseId, "파일"); if (blocked) return blocked;
  const zoom = ctx.state.previewZoom || 1, fileId = ctx.params.get("n") || "f1";
  const changeZoom = (dir: number | "reset") => update((state) => { state.previewZoom = dir === "reset" ? 1 : Math.min(1.4, Math.max(.8, Math.round(((state.previewZoom || 1) + dir * .1) * 10) / 10)); });
  const viewer = createDocumentViewer({ documentId: `lecture-preview:${fileId}`, pageCount: PAGES.length, label: "week5-cases.pdf", initialPage: ctx.state.previewPage || 1,
    onPageChange(page) { if (ctx.state.previewPage !== page) { ctx.state.previewPage = page; save(); } },
    renderPage(number) { const doc = PAGES[number - 1]; return <article className="preview-doc" style={{ "--doc-scale": zoom } as CSSProperties}><p className="kicker">{doc.kicker}</p><h1>{doc.title}</h1><p>{doc.body}</p><p>긴 문단도 줄 길이 안에서 읽히게 두었습니다. 확대는 글자 크기만 바꾸고, 가로 스크롤을 만들지 않습니다.</p></article>; },
    extras: <><span className="tiny">{Math.round(zoom * 100)}%</span><Button kind="ghost" aria-label="축소" onClick={() => changeZoom(-1)}>축소</Button><Button kind="ghost" aria-label="확대" onClick={() => changeZoom(1)}>확대</Button><Button kind="ghost" onClick={() => changeZoom("reset")}>100%</Button><Button aria-expanded={Boolean(ctx.state.summaryOpen)} onClick={() => update((state) => { state.summaryOpen = !state.summaryOpen; })}>테크뇽 요약</Button></>,
  });
  return <section className="bleed preview-layout"><div className="viewer">{viewer}</div>{summaryAside(ctx.state, false)}</section>;
}
function renderAttendance(screen: Screen, ctx: ScreenCtx): ReactNode {
  const course = courseById(ctx.state.courseId), selected = ctx.state.attendanceWeek || 11;
  const title = typeLine("report", <h1>출석</h1>, "check");
  if (backendStatus() !== "off") return remoteAttendance(screen, ctx, <header className="attend-head">{title}</header>);
  const counts: Record<string, number> = { present: 0, late: 0, absent: 0, empty: 0 };
  const cards = Array.from({ length: 14 }, (_, i) => {
    const week = i + 1, status = attendStatus(ctx.state, "minjun", week, screen.section);
    counts[status] = (counts[status] || 0) + 1;
    const start = new Date(2026, 7, 31 + i * 7), end = new Date(start); end.setDate(start.getDate() + 6);
    const sessions = status === "late" ? ["late", "present", "present"] : [status, status, status];
    return <article key={week} className={`att-card${week === selected ? " is-current" : ""}`} id={`att-${week}`} tabIndex={-1}><header className="att-card-h"><h2>{week}주</h2><p className="tiny">{`${koDate(start)} – ${koDate(end)} · ${course.time}${screen.section === "ml" ? " · ML 분반" : ""}`}</p></header>{sessions.map((item, index) => <div key={index} className="att-session"><span className="att-rail" aria-hidden="true"><i /></span><span><b>{index + 1}차시</b><span className="tiny">{course.time}</span></span><span className={`att-status st-${item}`}>{statusLabel(item)}</span></div>)}</article>;
  });
  return <section className="bleed attend"><header className="attend-head">{title}{screen.id.startsWith("41") ? <div className="seg"><Link href={hrefFor(find("41_room_attendance"), { kj: course.id })} className={screen.section === "ml" ? "btn btn-ghost" : "btn btn-secondary"}>본반</Link><Link href={hrefFor(find("41b_room_attendance_ml"), { kj: course.id })} className={screen.section === "ml" ? "btn btn-secondary" : "btn btn-ghost"}>ML 분반</Link></div> : null}</header>
    <p className="subhead">{`${course.ko} · ${course.code}`}</p><div className="att-stats"><span>출석 {counts.present}</span><span>지각 {counts.late}</span><span>결석 {counts.absent}</span><span>미정 {counts.empty}</span></div>
    <div className="attend-weeks" role="group" aria-label="주차">{Array.from({ length: 14 }, (_, i) => i + 1).map((week) => <button key={week} type="button" className={`week-num${week === selected ? " on" : ""}`} aria-pressed={week === selected} onClick={() => pickAttendanceWeek(week)}>{week}</button>)}</div>{cards}
  </section>;
}
function messageHref(folder: string, id?: string): string { return hrefFor(find(folder === "sent" ? "02d_msg_sent_tab" : "02c_msg_view"), id ? { id } : {}); }
function keepMailDraft(): void {
  const state = getState(), data = readForm();
  state.draft.mail = { ...(state.draft.mail || {}), to: data.to || "", title: data.title || "", body: data.body || "", course: data.course || "" };
  save();
}
function sendMail(): void {
  const data = readForm(), errors: Record<string, string> = {};
  keepMailDraft();
  const to = String(data.to || "").trim(), title = String(data.title || "").trim(), body = String(data.body || "").trim();
  if (!to) errors.to = "받는 사람을 입력하세요";
  if (!title) errors.title = "제목을 입력하세요";
  if (body.length < 2) errors.body = "내용을 입력하세요";
  if (body.length > 2000) errors.body = "내용은 2000byte 안으로 적어 주세요";
  if (Object.keys(errors).length) return showErrors(errors);
  const state = getState();
  state.messages.unshift({ id: `msg-${Date.now()}`, folder: "sent", from: state.role === "professor" ? "박은주" : "박민준", to, title, body, course: String(data.course || ""), at: stamp(), read: true });
  state.draft.mail = { to: "", title: "", body: "", course: "" }; state.toast = "쪽지를 보냈습니다"; save();
  navigate(hrefFor(find("02d_msg_sent_tab")));
}
function replyMail(message: Message): void {
  const state = getState();
  state.draft.mail = { to: message.from, title: message.title.startsWith("Re:") ? message.title : `Re: ${message.title}`, body: "", course: message.course || "" };
  save(); navigate(hrefFor(find("02e_msg_compose")));
}
function renderMessages(screen: Screen, ctx: ScreenCtx): ReactNode {
  if (usesServerMessages(ctx.params)) return serverMessages(screen, ctx);
  seedMail(ctx.state);
  const folder = screen.folder || "inbox", query = String(ctx.state.draft.mailQuery || "").trim();
  const source = ctx.state.messages.filter((m) => m.folder === (folder === "write" ? "inbox" : folder));
  const list = source.filter((m) => !query || `${m.title} ${m.from} ${m.body}`.includes(query));
  const currentId = ctx.params.get("id"), current = ctx.state.messages.find((m) => m.id === currentId) || (screen.id === "02c_msg_view" ? list[0] : null);
  const showDetail = folder !== "write" && Boolean(current) && (Boolean(currentId) || screen.id === "02c_msg_view");
  const picked = new Set<string>(ctx.state.draft.picked || []);
  const tab = (id: string, label: string, on: boolean) => <Link href={hrefFor(find(id))} className={on ? "on" : ""} role="tab" aria-selected={on}>{label}</Link>;
  const tabs = <div className="msg-tabs" role="tablist">{tab("02e_msg_compose", "쪽지쓰기", folder === "write")}{tab("02b_msg_window", `받은쪽지 ${ctx.state.messages.filter((m) => m.folder === "inbox").length}`, folder === "inbox")}{tab("02d_msg_sent_tab", `보낸쪽지 ${ctx.state.messages.filter((m) => m.folder === "sent").length}`, folder === "sent")}</div>;
  if (folder === "write") {
    const draft = ctx.state.draft.mail || {};
    return <div className="msg-app">{tabs}<div className="compose">
      <Field label="제목" name="title"><input data-name="title" defaultValue={draft.title || ""} placeholder="제목" maxLength={80} /></Field>
      <div className="compose-to"><Button onClick={() => { keepMailDraft(); navigate(hrefFor(find("02f_msg_recipient"))); }}>받는 사람</Button><Field label="받는 사람" name="to"><input data-name="to" defaultValue={draft.to || ""} placeholder="이름을 입력하거나 아래에서 고르세요" /></Field></div>
      <Field label="과목"><select data-name="course" defaultValue={draft.course || ""}><option value="">선택</option>{courses.filter((course) => course.enrolled).map((course) => <option key={course.id} value={course.ko}>{course.ko}</option>)}</select></Field>
      <Field label="내용" name="body"><textarea data-name="body" maxLength={2000} placeholder="내용을 입력하세요." defaultValue={draft.body || ""} /></Field><p className="tiny byte">{String(draft.body || "").length}/2000byte</p>
      <DropZone /><div className="compose-files"><label className="btn btn-secondary"><Icon name="file" />파일첨부<input type="file" data-name="file" className="file-input" /></label><Button kind="ghost" onClick={() => toast("첨부는 이 브라우저에만 남고 학교 서버로 올리지 않습니다. 파일을 다시 선택해 보세요.")}>업로드에 실패했나요?</Button></div>
      <div className="compose-send"><Button kind="primary" onClick={sendMail}>보내기</Button></div>
    </div></div>;
  }
  const select = (ids: string[]) => update((state) => { state.draft.picked = ids; });
  const deletePicked = () => {
    const data = readForm(), ids = new Set(Array.isArray(data.picked) ? data.picked : data.picked ? [data.picked] : []);
    if (!ids.size) return toast("삭제할 쪽지를 선택하세요");
    update((state) => { state.messages = state.messages.filter((m) => !ids.has(m.id)); state.draft.picked = []; state.toast = "쪽지를 삭제했습니다"; });
  };
  return <div className="msg-app">{tabs}<div className={`msg-pane${showDetail && current ? " has-detail" : ""}`}><div className="msg-list">
    <div className="msg-search"><input key={query} data-name="mailQuery" defaultValue={query} placeholder="검색어를 입력하세요." aria-label="쪽지 검색" /><Button kind="ghost" onClick={() => { const data = readForm(); update((state) => { state.draft.mailQuery = String(data.mailQuery || "").trim(); }); }}>검색</Button></div>
    <div className="msg-tools"><label className="checkline"><input type="checkbox" checked={list.length > 0 && list.every((m) => picked.has(m.id))} onChange={() => select(list.length > 0 && list.every((m) => picked.has(m.id)) ? [] : list.map((m) => m.id))} /><span>전체선택</span></label><Button kind="ghost" onClick={deletePicked}>삭제</Button></div>
    {!list.length ? <p className="msg-empty">쪽지가 없습니다.</p> : list.map((message) => <div key={message.id} className={`msg-item${showDetail && current?.id === message.id ? " on" : ""}`}><input type="checkbox" data-name="picked" value={message.id} aria-label={`${message.title} 선택`} checked={picked.has(message.id)} onChange={(event) => select(event.target.checked ? [...picked, message.id] : [...picked].filter((id) => id !== message.id))} /><Link className="msg-open" href={messageHref(message.folder, message.id)}><span className="msg-avatar">{message.from.slice(0, 1)}</span><span className="msg-copy"><b>{message.title}</b><span className="tiny">{`${message.from} · ${message.at}`}</span></span>{message.read ? null : <span className="unread-dot" aria-label="읽지 않음" />}</Link></div>)}
  </div><div className="msg-main">{showDetail && current ? <article className="msg-read"><Link className="btn btn-ghost msg-back" href={hrefFor(find(folder === "sent" ? "02d_msg_sent_tab" : "02b_msg_window"))}>목록</Link><h2>{current.title}</h2><p className="tiny">{`${current.from} → ${current.to} · ${current.at}`}</p><div className="prose"><p>{current.body}</p></div>{folder === "inbox" ? <Button kind="primary" onClick={() => replyMail(current)}>답장</Button> : null}</article> : <p className="msg-placeholder">목록에서 쪽지를 선택하세요.</p>}</div></div></div>;
}
export function DropZone({ text = "파일을 마우스로 끌어 놓아 업로드하세요." }: { text?: string }) {
  const [label, setLabel] = useState<string | null>(null);
  return <div className="drop" onDragOver={(event) => event.preventDefault()} onDrop={(event) => { event.preventDefault(); const file = event.dataTransfer.files[0]; if (file) { getState().draft.fileName = file.name; save(); setLabel(file.name); } }}>{label || text}</div>;
}
export function render(screen: Screen, ctx: ScreenCtx): ReactNode {
  const view = (() => {
    switch (screen.kind) {
      case "week": return renderWeek(ctx);
      case "timetable": return renderTimetable(ctx);
      case "activity": return renderActivity(screen, ctx);
      case "player": return renderPlayer(screen, ctx);
      case "preview": return renderPreview(ctx);
      case "attendance": return renderAttendance(screen, ctx);
      case "messages": return renderMessages(screen, ctx);
      default: return <EmptyState title="이 화면은 학습 영역이 아닙니다" body={screen.id} />;
    }
  })();
  return isValidElement<{ "data-area"?: string }>(view) ? cloneElement(view, { "data-area": "learning" }) : view;
}
export { people };
