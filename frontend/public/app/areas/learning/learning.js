import { el, icon } from "../../dom.js";
import { btn, go, emptyState, mark, field, tabs, badge } from "../../ui/components.js";
import { activities, events, courseById, activitiesOf, courses, people, files, surveyQuestions } from "../../data.js";
import { hrefFor, find } from "../../manifest.js";
import { typeLabel, attendStatus, statusLabel, dday, activityHref, playerHref } from "../../shared/format.js";
import { isClosedActivity } from "../../shared/activity-library.js";
import { richNodes } from "../../shared/rich-text.js";
import { bindUploadedVideo } from "../../shared/activity-files.js";
import { videoStudy } from "./video-study.js";
import { getState, save } from "../../store.js";
import { mediaFile, mediaForActivity } from "../../media.js";
import { createDocumentViewer } from "../../shared/document-viewer.js";
import { ensurePlayerChrome, paintPlayControl, presentPlayControl, svgIcon, syncFullscreenButton, togglePlayerFullscreen } from "../../shared/player-controls.js";
import { remoteAssignmentDetail } from "./assignments-server.js";
import { backendStatus } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { serverWeekCalendar, serverTimetable } from "./calendar-server.js";
import { usesServerMessages, serverMessages } from "./messages-server.js";
import { remoteQuizDetail } from "./quizzes-server.js";
import { remoteSurveyDetail } from "./surveys-server.js";
import { remoteAttendance } from "./attendance-server.js";
import { serverPreviewScreen } from "./preview-server.js";
import { remoteLectureDetail, remoteLecturePlayer } from "./lectures-server.js";
import { remoteMaterialDetail, remoteActivityLink, sampleInServerCourse } from "./activity-server.js";
import { describe } from "../../shared/assistant/resolve.js";
import { hrefFor as assistantHref, nextQueued } from "../../shared/assistant/run.js";
import { completeStep, completionToken, runState } from "../../shared/assistant/plan.js";
import { refreshTray, syncPlanProgress } from "../../shared/assistant-tray.js";

const DOW = ["일", "월", "화", "수", "목", "금", "토"];
const TYPE_ICON = { video: "play", zoom: "users", material: "file", report: "file", test: "check", survey: "chat", post: "pen" };

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

function pad(n) {
  return String(n).padStart(2, "0");
}

function koDate(date) {
  return `${date.getFullYear()}.${pad(date.getMonth() + 1)}.${pad(date.getDate())} (${DOW[date.getDay()]})`;
}

function isoOf(date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function named(node, error) {
  if (error) node.setAttribute("aria-invalid", "true");
  return node;
}

function metaTable(rows) {
  return el("div", { class: "meta-table" }, ...rows.map(([key, value]) => el("div", { class: "meta-row" },
    el("div", { class: "meta-k", text: key }),
    el("div", { class: "meta-v" }, value && value.nodeType ? value : el("span", { text: value == null || value === "" ? "—" : String(value) })),
  )));
}

function koWhen(value) {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return `${date.getFullYear()}.${pad(date.getMonth() + 1)}.${pad(date.getDate())} (${DOW[date.getDay()]}) ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function typeLine(type, titleTag, iconName) {
  const title = titleTag || el("span", { text: typeLabel(type) });
  return el("div", { class: "type-line" },
    el("span", { class: `type-ico ${type}`, "aria-hidden": "true" }, icon(iconName || TYPE_ICON[type] || "file")),
    title,
  );
}

function fileRow(name, meta, href) {
  const main = el("span", {}, el("b", { text: name }), el("span", { class: "tiny", text: meta }));
  const row = el("div", { class: "file-row" }, icon("file"), main);
  if (href) row.append(go(href, "미리보기", { class: "btn btn-secondary" }));
  return row;
}

function seedMail(state) {
  if (state.draft.mailSeeded) return;
  state.draft.mailSeeded = true;
  for (const message of EXTRA_MAIL) {
    if (!state.messages.some((item) => item.id === message.id)) state.messages.push({ ...message });
  }
}

function renderWeek(ctx) {
  // Backend `ready`: the server week (dates and today from the server); `anon`: login strip above the sample.
  if (backendStatus() === "ready") return serverWeekCalendar(ctx);
  const login = backendStatus() === "anon" ? backendPanel({ text: "ASTRA 서버에 로그인하면 내 과목의 마감과 학사일정을 서버에서 불러옵니다." }) : null;
  const offset = ctx.state.calOffset || 0;
  const start = new Date(2026, 10, 8);
  start.setDate(start.getDate() + offset * 7);
  const days = Array.from({ length: 7 }, (_, i) => {
    const date = new Date(start);
    date.setDate(start.getDate() + i);
    return date;
  });
  const label = `${days[0].getFullYear()}년 ${days[0].getMonth() + 1}월`;
  const grid = el("div", { class: "cal week-cal" });
  for (const date of days) {
    const sun = date.getDay() === 0 ? " sun" : date.getDay() === 6 ? " sat" : "";
    grid.append(el("div", { class: `cal-dow${sun}`, text: DOW[date.getDay()] }));
  }
  for (const date of days) {
    const iso = isoOf(date);
    const href = hrefFor(find("07_home_calendar_week"), { pop: "day", day: iso });
    const cell = el("button", { type: "button", class: `cal-day${iso === "2026-11-10" ? " today" : ""}`, "data-go": href });
    const numCls = `day-num${date.getDay() === 0 ? " sun" : date.getDay() === 6 ? " sat" : ""}`;
    cell.append(el("div", { class: numCls, text: String(date.getDate()) }));
    for (const ev of events.filter((item) => item.date === iso)) {
      cell.append(el("span", { class: `pill ${ev.type}`, text: `${courseById(ev.courseId).ko} ${ev.title}` }));
    }
    grid.append(cell);
  }
  return el("section", {},
    login,
    el("div", { class: "cal-head" },
      el("h1", { text: label }),
      tabs([
        { label: "월", href: hrefFor(find("00_main_home")) },
        { label: "주", on: true },
      ]),
      el("div", { class: "cal-nav" },
        el("button", { type: "button", class: "btn btn-ghost", "data-act": "cal-shift", "data-dir": "-1", "aria-label": "이전 주", text: "‹" }),
        el("button", { type: "button", class: "btn btn-ghost", "data-act": "cal-shift", "data-dir": "1", "aria-label": "다음 주", text: "›" }),
      ),
    ),
    el("p", { class: "subhead", text: `${koDate(days[0])} – ${koDate(days[6])}` }),
    grid,
  );
}

function renderTimetable(ctx) {
  // Backend `ready`: the server timetable for the week of `?date=` (this week when absent).
  if (backendStatus() === "ready") {
    return el("section", { class: "timetable" },
      el("div", { class: "page-head" }, el("h1", { text: "수업시간표" }), go(hrefFor(find("00_main_home")), "닫기", { class: "btn btn-ghost" })),
      serverTimetable(ctx),
    );
  }
  const login = backendStatus() === "anon" ? backendPanel({ text: "ASTRA 서버에 로그인하면 이번 학기 수업 시간을 서버에서 불러옵니다." }) : null;
  const offset = ctx.state.ttOffset || 0;
  const monday = new Date(2026, 10, 9);
  monday.setDate(monday.getDate() + offset * 7);
  const friday = new Date(monday);
  friday.setDate(monday.getDate() + 4);
  const grid = el("div", { class: "tt", role: "grid", "aria-label": "주간 수업시간표" });
  grid.append(el("div", { class: "tt-h", text: "" }));
  for (const name of ["월", "화", "수", "목", "금"]) grid.append(el("div", { class: "tt-h", text: name }));
  for (let hour = 9; hour <= 18; hour += 1) {
    grid.append(el("div", { class: "tt-time", text: `${pad(hour)}:00` }));
    for (let day = 0; day < 5; day += 1) grid.append(el("div", { class: "tt-slot" }));
  }
  for (const block of BLOCKS) {
    const course = courseById(block.id);
    grid.append(el("div", {
      class: `tt-block ${course.gradient}`,
      style: `grid-column:${block.day + 2};grid-row:${block.start - 7} / span ${block.span}`,
    },
    el("b", { text: course.ko }),
    el("span", { text: `${pad(block.start)}:00–${pad(block.start + block.span)}:00` }),
    el("span", { text: `${course.instructor} · ${course.room}` }),
    ));
  }
  const list = el("div", { class: "tt-list" });
  for (const [index, name] of ["월", "화", "수", "목", "금"].entries()) {
    const mine = BLOCKS.filter((block) => block.day === index);
    const card = el("section", { class: "att-card" }, el("h2", { text: name }));
    if (!mine.length) card.append(el("p", { class: "tiny", text: "수업 없음" }));
    for (const block of mine) {
      const course = courseById(block.id);
      card.append(el("div", { class: "tt-line" },
        el("span", { class: `rail-dot` }),
        el("span", {}, el("b", { text: course.ko }), el("span", { class: "tiny", text: `${pad(block.start)}:00–${pad(block.start + block.span)}:00 · ${course.room}` })),
      ));
    }
    list.append(card);
  }
  return el("section", { class: "timetable" },
    el("div", { class: "page-head" },
      el("h1", { text: "수업시간표" }),
      go(hrefFor(find("00_main_home")), "닫기", { class: "btn btn-ghost" }),
    ),
    login,
    el("div", { class: "tt-nav" },
      el("button", { type: "button", class: "btn btn-ghost", "data-act": "tt-shift", "data-dir": "-1", "aria-label": "이전 주", text: "‹" }),
      el("strong", { text: `${koDate(monday)} – ${koDate(friday)}` }),
      el("button", { type: "button", class: "btn btn-ghost", "data-act": "tt-shift", "data-dir": "1", "aria-label": "다음 주", text: "›" }),
    ),
    el("p", { class: "tiny", text: "칸 색은 과목마다 다릅니다. 이 샘플의 요일과 교시는 학기 내내 같고, 화살표는 표시 주만 바꿉니다." }),
    grid,
    list,
  );
}

function activityRail(course, current) {
  const list = activitiesOf(course.id);
  const weeks = [...new Set(list.map((item) => item.week))].sort((a, b) => a - b);
  const rail = el("nav", { class: "act-rail", "aria-label": "주차 활동" });
  for (const week of weeks) {
    const group = el("div", { class: "rail-week" }, el("div", { class: "rail-week-h", text: `${week}주` }));
    for (const item of list.filter((entry) => entry.week === week)) {
      const href = activityHref(item, course.id);
      const done = item.status === "done" || item.status === "submitted" || item.status === "graded";
      group.append(el("a", { class: `rail-link${item.id === current.id ? " on" : ""}`, href, "data-go": href },
        el("span", { class: `rail-dot ${item.type}` }),
        el("span", { class: "rail-title", text: item.title }),
        done ? el("span", { class: "check", text: "✓" }) : null,
      ));
    }
    rail.append(group);
  }
  return rail;
}

function videoDetail(a, course) {
  const expired = isClosedActivity(a);
  const progress = a.status === "done" ? 100 : (a.progress || 0);
  return el("div", { class: "act-body" },
    typeLine(a.type),
    el("h1", { text: a.title }),
    el("p", { class: "tiny", text: `${course.ko} · ${a.minutes || 0}분` }),
    el("section", { class: "callout" },
      el("h2", { text: "주의사항" }),
      el("ul", {},
        el("li", { text: "출석으로 인정되려면 영상의 인정 구간을 끝까지 시청해야 합니다." }),
        el("li", { text: "학습 기간이 끝나면 재생할 수 없습니다. 기간 밖 시청은 출석에 포함되지 않습니다." }),
        el("li", { text: "배속은 이 브라우저에서만 적용되고, 출석 시간은 1배속으로 계산합니다." }),
        el("li", { text: "이 샘플은 영상 파일을 받지 않습니다. 재생 위치만 이 기기에서 움직입니다." }),
      ),
    ),
    metaTable([
      ["출석부 반영일", "학습을 마친 뒤"],
      ["학습기간", a.attendStart || a.start || a.due ? `${koWhen(a.attendStart || a.start)} – ${koWhen(a.attendEnd || a.due)}` : `${a.week}주차`],
    ]),
    el("div", { class: "flag-row", role: "group", "aria-label": "학습 기간" },
      el("span", { class: "flag on", text: "기간 학습" }),
      el("span", { class: "flag", text: "기간외 학습" }),
    ),
    el("article", { class: "progress-card" },
      el("div", { class: "progress-pct", text: `${progress}%` }),
      el("div", {},
        el("b", { text: a.title }),
        el("p", { class: "tiny", text: `학습시간 ${a.minutes || 0}분 · 출석인정 ${progress >= 90 ? "완료" : "미달"}` }),
        el("div", { class: "bar", "aria-hidden": "true" }, el("i", { style: `width:${progress}%` })),
      ),
    ),
    expired
      ? el("div", { class: "btn-row" }, btn("학습 기간 종료", { disabled: true }, "primary"))
      : el("div", { class: "btn-row" }, go(playerHref(a, course.id), "학습하기", { class: "btn btn-primary" })),
  );
}

function zoomDetail(a, course) {
  return el("div", { class: "act-body" },
    typeLine("zoom"),
    el("h1", { text: a.title }),
    el("p", { class: "lead", text: `${course.ko} 실시간 수업입니다. 이 샘플은 외부 회의에 접속하지 않습니다.` }),
    metaTable([
      ["일시", a.when || (a.start ? `${koWhen(a.start)} – ${koWhen(a.end)}` : "—")],
      ["회의 링크", a.meetingUrl ? el("a", { class: "pf-meeting", href: a.meetingUrl, rel: "noopener noreferrer", text: a.meetingUrl }) : "—"],
      ["회의 번호", a.meeting || "—"],
      ["비밀번호", a.pass || "—"],
      ["장소", course.room],
    ]),
    a.body ? el("div", { class: "prose" }, el("p", { text: a.body })) : null,
    el("div", { class: "btn-row" }, btn("입장", { disabled: true }, "primary")),
    el("p", { class: "tiny", text: "입장 버튼은 꺼 두었습니다. 회의 번호만 확인할 수 있습니다." }),
  );
}

function materialDetail(a, course, ctx) {
  const owned = files.filter((file) => file.courseId === course.id && (file.week === a.week || file.id === "f1"));
  const preview = hrefFor(find("65_file_preview"), { n: "f1", kj: course.id });
  return el("div", { class: "act-body" },
    typeLine("material"),
    el("h1", { text: a.title }),
    a.body ? el("div", { class: "prose" }, el("p", { text: a.body })) : null,
    a.fileName ? el("p", { class: "tiny", text: a.fileName }) : null,
    a.fileId ? el("div", { class: "btn-row" }, btn("다운로드", { "data-act": "activity-download", "data-file": a.fileId, "data-filename": a.fileName || "download" }, "primary")) : null,
    metaTable([
      ["공개일", "2026.10.30 (금)"],
      ["작성자", course.instructor],
      ["조회수", "130"],
    ]),
    el("h2", { text: "첨부파일" }),
    ...(owned.length ? owned : [{ name: "week5-cases.pdf", size: "1.2 MB" }]).map((file) => fileRow(file.name, `${file.size}${file.week ? ` · ${file.week}주` : ""}`, preview)),
    el("div", { class: "btn-row" }, go(preview, "문서 미리보기", { class: "btn btn-primary" })),
    summaryAside(ctx.state, true),
  );
}

function reportDetail(a, ctx) {
  const submitted = ctx.state.submissions[a.id];
  const due = el("span", { class: "due-line" }, el("span", { text: koWhen(a.due) }), a.due ? el("span", { class: "dday", text: dday(a.due) }) : null);
  const attachments = submitted
    ? [{ name: submitted.fileName || a.fileName || "제출 파일", size: submitted.at }]
    : [{ name: "과제 안내.pdf", size: "220 KB" }, { name: "예시 데이터.csv", size: "840 KB" }];
  return el("div", { class: "act-body" },
    typeLine("report"),
    el("h1", { text: a.title }),
    metaTable([
      ["제출방식", "온라인"],
      ["제출형태", "글 입력 / 파일첨부"],
      ["공개일", koWhen(a.start)],
      ["마감일", due],
      ["배점", a.points != null ? `${a.points}점` : "—"],
      ["점수공개", "비공개"],
    ]),
    el("div", { class: "prose" }, el("p", { text: a.body || "과제 설명이 없습니다." })),
    el("h2", { text: "첨부파일" }),
    ...attachments.map((file) => fileRow(file.name, file.size)),
    submitted
      ? el("section", { class: "note ok" },
        el("h2", { text: "제출됨" }),
        el("p", { text: `${submitted.fileName || "파일 없음"} · ${submitted.at}` }),
        submitted.body ? el("p", { text: submitted.body }) : null,
      )
      : isClosedActivity(a)
        ? el("p", { class: "note", text: "마감된 과제는 제출할 수 없습니다." })
        : el("div", { class: "btn-row" }, go(`/ilos/cls/st/activity/activity_form.acl?m=report&n=${encodeURIComponent(a.id)}&kj=${encodeURIComponent(a.courseId)}&pop=submit`, "제출", { class: "btn btn-primary" })),
  );
}

function testDetail(a, course) {
  const closed = Boolean(a.offline) || a.status === "graded" || isClosedActivity(a);
  return el("div", { class: "act-body" },
    typeLine("test"),
    el("h1", { text: a.title }),
    el("p", { class: "tiny", text: course.ko }),
    metaTable([
      ["시험형태", a.offline ? "대면 시험" : "온라인 시험"],
      ["응시기간", `${koWhen(a.start)} – ${koWhen(a.due)}`],
      ["배점", a.points != null ? `${a.points}점` : "—"],
      ["제한시간", a.minutes ? `${a.minutes}분` : "—"],
      ["결과공개", a.score != null ? `${a.score} / ${a.points}` : "비공개"],
    ]),
    el("div", { class: "prose" }, el("p", { text: a.body || "시험 안내가 없습니다." })),
    el("div", { class: "btn-row" }, btn(closed ? "응시 종료" : "응시", { disabled: true }, "primary")),
    el("p", { class: "tiny", text: closed ? "종료된 시험은 다시 열 수 없습니다." : "시작 시각 전에는 응시 버튼이 열리지 않습니다." }),
  );
}

function postDetail(a) {
  const prose = el("div", { class: "prose", "data-post": a.id });
  prose.append(richNodes(a.body || ""));
  return el("div", { class: "act-body" },
    typeLine("post"),
    el("h1", { text: a.title }),
    prose,
    isClosedActivity(a) ? el("p", { class: "note", text: "마감된 글은 읽을 수만 있습니다." }) : null,
  );
}

function surveyDetail(a, ctx) {
  const saved = ctx.state.surveys[a.id];
  const openId = a.id === "nr-s" ? "71b_nr_survey_question_pop" : "64_activity_survey_detail";
  const href = hrefFor(find(openId), { pop: "survey", n: a.id, kj: a.courseId });
  return el("div", { class: "act-body" },
    typeLine("survey"),
    el("h1", { text: a.title }),
    metaTable([
      ["기간", `마감 ${koWhen(a.due)}`],
      ["문항", `${surveyQuestions.length}문항`],
      ["상태", saved && saved.done ? "응답 저장됨" : "미응답"],
      ["남은 기간", dday(a.due) || "—"],
    ]),
    el("ol", { class: "survey-preview" }, ...surveyQuestions.map((q) => el("li", {},
      el("b", { text: q.prompt }),
      el("span", { class: "tiny", text: saved && saved[q.id] ? `저장한 답 · ${saved[q.id]}` : q.options.join(" · ") }),
    ))),
    saved && saved.done
      ? el("p", { class: "note ok", text: "응답이 이 브라우저에 저장되었습니다. 다시 열면 고칠 수 있습니다." })
      : null,
    el("div", { class: "btn-row" }, go(href, saved && saved.done ? "응답 수정" : "설문 참여", { class: "btn btn-primary" })),
  );
}

function renderActivity(screen, ctx) {
  if (screen.serverAssignment) return remoteAssignmentDetail(screen, ctx, { metaTable, typeLine });
  if (screen.serverQuiz) return remoteQuizDetail(screen, ctx, { metaTable, typeLine });
  if (screen.serverSurvey) return remoteSurveyDetail(screen, ctx, { metaTable, typeLine });
  if (screen.serverLecture) return remoteLectureDetail(screen, ctx, { metaTable, typeLine });
  // 백엔드 모드: 서버 자료 상세와, 활동 ID만 받은 과제·시험·설문의 연결 화면(activity-server.js).
  if (screen.serverMaterial) return remoteMaterialDetail(screen, ctx, { metaTable, typeLine });
  if (screen.serverLink) return remoteActivityLink(screen, ctx, { metaTable, typeLine });
  const a = activities.find((item) => item.id === screen.activityId);
  if (!a) return emptyState("활동을 찾을 수 없습니다", "목록에서 다른 활동을 선택해 보세요.");
  // 연결된 과목에서는 샘플 활동을 서버 것처럼 보이지 않는다.
  const blocked = sampleInServerCourse(a.courseId);
  if (blocked) return blocked;
  const course = courseById(a.courseId);
  let body;
  if (a.type === "video") body = videoDetail(a, course);
  else if (a.type === "zoom") body = zoomDetail(a, course);
  else if (a.type === "material") body = materialDetail(a, course, ctx);
  else if (a.type === "report") body = reportDetail(a, ctx);
  else if (a.type === "test") body = testDetail(a, course);
  else if (a.type === "post") body = postDetail(a);
  else body = surveyDetail(a, ctx);
  return el("section", { class: "bleed act-layout is-open", "data-item-open": a.id }, activityRail(course, a), el("div", { class: "act-main" }, body));
}

function clock(total) {
  const seconds = Math.max(0, Math.round(total));
  return `${pad(Math.floor(seconds / 60))}:${pad(seconds % 60)}`;
}

function syncLectureChrome(video, activityId) {
  const state = getState();
  const duration = Number.isFinite(video.duration) && video.duration > 0 ? video.duration : state.player.duration || 0;
  const seconds = Number.isFinite(video.currentTime) ? video.currentTime : 0;
  state.player.id = activityId;
  state.player.seconds = seconds;
  state.player.duration = duration || state.player.duration;
  state.player.playing = !video.paused && !video.ended;
  state.player.positions = state.player.positions || {};
  state.player.positions[activityId] = seconds;
  // 샘플 영상은 길이가 실제 차시 시간(minutes)보다 짧아 positions/minutes 만으로는 90%에 닿지 못한다. 출석 기준과 같은 영상 길이 비율로도 끝까지 본 것을 남긴다.
  if (duration && seconds / duration >= 0.9) {
    state.player.watched = state.player.watched || {};
    state.player.watched[activityId] = true;
  }
  save();
  syncPlanProgress(activityId);
  const page = video.closest(".player-page");
  if (!page) return;
  const label = page.querySelector(".player-seek .tiny");
  if (label) label.textContent = clock(seconds);
  const end = page.querySelector(".player-seek .tiny:last-child");
  if (end && duration) end.textContent = clock(duration);
  const range = page.querySelector("[data-act=seek]");
  if (range && document.activeElement !== range) {
    if (duration) range.max = String(Math.max(1, Math.floor(duration)));
    range.value = String(Math.min(Math.floor(seconds), Number(range.max) || 0));
  }
  const ratio = duration ? Math.min(100, Math.round((seconds / duration) * 100)) : 0;
  const pill = page.querySelector(".attend-pill");
  if (pill) {
    const done = ratio >= 90;
    pill.textContent = done ? "출석 완료" : "출석 진행";
    pill.classList.toggle("ok", done);
  }
  const play = page.querySelector("button.play");
  if (play) {
    const on = state.player.playing;
    const next = on ? "1" : "0";
    paintPlayControl(play, on, { reveal: play.dataset.playback !== next });
    play.dataset.playback = next;
    // 자동재생이 막혀 "mm:ss부터 이어보기"로 바꾼 라벨은 재생이 시작될 때까지 paintPlayControl이 덮지 못하게 한다.
    if (play.dataset.resumeLabel) {
      if (on) {
        delete play.dataset.resumeLabel;
        play.removeAttribute("title");
        page.querySelector(".player-resume")?.remove();
      } else play.setAttribute("aria-label", play.dataset.resumeLabel);
    }
  }
}

/** 요청함/히어로가 붙인 `autoplay=1`: 저장 위치 복원 뒤 재생을 시도하고, 쿼리는 한 번만 쓰도록 바로 지운다. */
function startRequestedPlayback(video, seconds) {
  const params = new URLSearchParams(location.search);
  if (params.get("autoplay") !== "1" || !video.isConnected) return;
  params.delete("autoplay");
  const query = params.toString();
  history.replaceState(history.state, "", `${location.pathname}${query ? `?${query}` : ""}${location.hash}`);
  const stage = video.closest(".player-stage");
  const play = stage && stage.querySelector("button.play");
  const blocked = () => {
    if (!play) return;
    if (seconds > 0.4) {
      const label = `${clock(seconds)}부터 이어보기`;
      play.dataset.resumeLabel = label;
      play.setAttribute("aria-label", label);
      play.title = label;
      stage.append(el("p", { class: "player-resume", text: label }));
    }
    play.focus();
  };
  const pending = video.play();
  if (pending && typeof pending.catch === "function") pending.catch(blocked);
}

function followLink(href) {
  const link = el("a", { href, "data-go": href });
  document.body.append(link);
  link.click();
  link.remove();
}

/** Only an ended event can finish a plan video; ordinary requests keep their next-up link. */
function showNextUp(video, activityId, token) {
  if (!video.isConnected || !video.ended) return;
  const stage = video.closest(".player-stage");
  if (!stage || stage.querySelector(".player-next")) return;
  if (completeStep(token, { navigate: followLink, event: "video-ended" })) {
    refreshTray();
    if (runState()?.status === "done") {
      stage.append(el("div", { class: "player-next", role: "status", text: "오늘 계획 완료" }));
    }
    return;
  }
  // A paused, replaced, or already-completed plan must not offer another route around its guard.
  if (token) return;
  const next = nextQueued(activityId);
  const href = next && assistantHref(next);
  if (!href) return;
  stage.append(el("div", { class: "player-next", role: "status" },
    el("a", { href, "data-go": href, text: `다음: ${describe(next).title} →` }),
  ));
}

function bindLectureVideo(video, activityId) {
  const token = completionToken(activityId);
  const resume = () => {
    const state = getState();
    const saved = Number((state.player.positions && state.player.positions[activityId]) ?? state.player.seconds) || 0;
    video.playbackRate = state.player.speed || 1;
    const track = video.textTracks && video.textTracks[0];
    if (track) track.mode = state.player.captions === false ? "hidden" : "showing";
    if (saved > 0.4 && Number.isFinite(video.duration) && saved < video.duration - 0.3) {
      video.currentTime = saved;
      startRequestedPlayback(video, saved);
      return;
    }
    syncLectureChrome(video, activityId);
    startRequestedPlayback(video, 0);
  };
  if (typeof video.addEventListener !== "function") return;
  video.addEventListener("loadedmetadata", resume);
  ["timeupdate", "play", "pause", "seeked", "ratechange", "ended"].forEach((name) => {
    video.addEventListener(name, () => syncLectureChrome(video, activityId));
  });
  video.addEventListener("ended", () => showNextUp(video, activityId, token));
  video.addEventListener("play", () => {
    video.closest(".player-stage")?.querySelector(".player-next")?.remove();
  });
  const page = video.closest(".player-page");
  video.addEventListener("dblclick", (event) => {
    event.preventDefault();
    togglePlayerFullscreen(page);
  });
  video.addEventListener("webkitbeginfullscreen", () => syncFullscreenButton(page));
  video.addEventListener("webkitendfullscreen", () => syncFullscreenButton(page));
  const stage = video.closest(".player-stage");
  const play = stage && stage.querySelector("button.play");
  if (stage) {
    stage.addEventListener("click", (event) => {
      if (event.target.closest("button, a, input, textarea, select")) return;
      if (video.paused || video.ended) return;
      presentPlayControl(play, { fade: true });
    });
  }
  if (play) {
    play.addEventListener("focus", () => {
      if (video.paused || video.ended) return;
      presentPlayControl(play, { fade: true });
    });
  }
}

function renderPlayer(screen, ctx) {
  if (screen.serverLecture) return remoteLecturePlayer(screen, ctx);
  const sample = activities.find((item) => item.id === (screen.activityId || "v-open"));
  const blocked = sampleInServerCourse(sample ? sample.courseId : screen.course, "영상");
  if (blocked) return blocked;
  ensurePlayerChrome();
  const a = sample;
  const media = mediaForActivity(a);
  const p = ctx.state.player;
  const ratio = p.duration ? Math.min(100, Math.round((p.seconds / p.duration) * 100)) : 0;
  const panel = ctx.state.playerPanel || "";
  const notes = (ctx.state.draft && ctx.state.draft.playerNotes) || [];
  const askError = (ctx.state.formError || {}).playerQ;
  const captionsOn = p.captions !== false;
  const video = el("video", {
    class: "lecture-video",
    poster: mediaFile(media.id, "jpg"),
    preload: "auto",
    playsinline: true,
    "aria-label": a ? `${media.course} ${a.title}` : media.course,
  });
  video.append(
    el("source", { src: mediaFile(media.id, "webm"), type: "video/webm" }),
    el("source", { src: mediaFile(media.id, "mp4"), type: "video/mp4" }),
    el("track", { kind: "captions", src: mediaFile(media.id, "vtt"), srclang: "ko", label: "한국어", default: captionsOn }),
  );
  bindLectureVideo(video, a ? a.id : media.id);
  if (a && a.fileId && !isClosedActivity(a)) bindUploadedVideo(video, a);
  const sheet = panel
    ? el("section", { class: "player-sheet", "aria-label": panel === "ask" ? "질의하기" : "강의내용" },
      el("div", { class: "player-sheet-h" },
        el("h2", { text: panel === "ask" ? "질의하기" : "강의내용" }),
        el("button", { type: "button", class: "x", "data-act": "player-panel", "data-panel": panel, "aria-label": "닫기", text: "×" }),
      ),
      panel === "notes"
        ? el("div", { class: "prose" },
          el("p", { text: `${media.course} · ${media.week} · ${media.professor}` }),
          ...media.cues.map((cue) => el("p", { text: `${clock(cue.start)} ${cue.text}` })),
        )
        : el("div", {},
          notes.map((line) => el("p", { class: "note", text: line })),
          field("질문", named(el("textarea", { "data-name": "playerQ", placeholder: "이 차시에서 궁금한 점을 적으세요." }), askError), askError),
          btn("보내기", { "data-act": "player-ask" }, "primary"),
        ),
    )
    : null;
  const player = el("section", { class: "player-page is-open", "data-item-open": a ? a.id : "" },
    el("div", { class: "player-top" },
      el("h1", { text: a ? `[${a.week}주] ${a.title}` : "온라인 강의" }),
      el("span", { class: `attend-pill${ratio >= 90 ? " ok" : ""}`, text: ratio >= 90 ? "출석 완료" : "출석 진행" }),
    ),
    el("div", { class: "player-stage" },
      video,
      el("button", {
        type: "button",
        class: "play",
        "data-act": "play",
        "data-glyph": "play",
        "data-playback": "0",
        "aria-label": "재생",
        "aria-pressed": "false",
      }, svgIcon("play")),
      el("p", { class: "player-note", text: "샘플 강의 영상입니다. 자막은 슬라이드와 같습니다." }),
    ),
    el("div", { class: "player-chrome" },
      el("div", { class: "player-seek" },
        el("span", { class: "tiny", text: clock(Math.min(p.seconds, p.duration || media.seconds)) }),
        el("input", { type: "range", min: "0", max: String(p.duration || media.seconds), value: String(Math.floor(Math.min(p.seconds, p.duration || media.seconds))), "data-act": "seek", "aria-label": "재생 위치" }),
        el("span", { class: "tiny", text: clock(p.duration || media.seconds) }),
      ),
      el("div", { class: "player-tools" },
        ...[1, 1.25, 1.5, 2].map((speed) => el("button", {
          type: "button",
          class: `btn btn-ghost${p.speed === speed ? " on" : ""}`,
          "data-act": "speed",
          "data-speed": String(speed),
          "aria-pressed": p.speed === speed ? "true" : "false",
          text: `${speed}x`,
        })),
        el("button", {
          type: "button",
          class: `btn btn-ghost${captionsOn ? " on" : ""}`,
          "data-act": "captions",
          "aria-pressed": captionsOn ? "true" : "false",
          text: "자막",
        }),
        el("button", {
          type: "button",
          class: "btn player-full",
          "data-act": "fullscreen",
          "data-glyph": "enter",
          "aria-label": "전체화면",
          "aria-pressed": "false",
        }, svgIcon("enter")),
        el("button", { type: "button", class: `btn btn-ghost${panel === "notes" ? " on" : ""}`, "data-act": "player-panel", "data-panel": "notes", text: "강의내용" }),
        el("button", { type: "button", class: `btn btn-ghost${panel === "ask" ? " on" : ""}`, "data-act": "player-panel", "data-panel": "ask", text: "질의하기" }),
        a ? go(activityHref(a, a.courseId), "단원으로", { class: "btn btn-secondary" }) : null,
      ),
    ),
    sheet,
  );
  if (!a) return player;
  return videoStudy(courseById(a.courseId), a, player, (seconds) => {
    const input = player.querySelector('[data-act="seek"]');
    const limit = Number(input.max) || p.duration || seconds;
    input.value = String(Math.min(seconds, limit));
    input.dispatchEvent(new Event("change", { bubbles: true }));
  });
}

function summaryAside(state, embed) {
  const tab = state.summaryTab || "summary";
  const tabs = [["summary", "요약"], ["concepts", "핵심 개념"], ["quiz", "퀴즈"], ["cards", "플래시카드"]];
  const body = el("div", { class: "sum-b" });
  if (tab === "concepts") {
    for (const concept of SUMMARY.concepts) {
      body.append(el("article", { class: "sum-block" }, el("h3", { text: concept.term }), el("p", { text: concept.body })));
    }
  } else if (tab === "quiz") {
    const quiz = SUMMARY.quiz;
    body.append(el("p", { class: "lead", text: quiz.q }));
    for (const option of quiz.options) {
      const picked = state.summaryChoice === option.id;
      const good = picked && option.id === quiz.answer;
      const bad = picked && option.id !== quiz.answer;
      body.append(el("button", {
        type: "button",
        class: `btn btn-secondary quiz-opt${picked ? " on" : ""}`,
        "data-act": "summary-choice",
        "data-choice": option.id,
        "aria-pressed": picked ? "true" : "false",
        text: option.text,
      }));
      if (good) body.append(el("p", { class: "tiny", text: "샘플 정답입니다. 점수는 저장하지 않습니다." }));
      if (bad) body.append(el("p", { class: "tiny", text: "이 보기는 샘플 정답이 아닙니다." }));
    }
  } else if (tab === "cards") {
    const card = SUMMARY.card;
    const flipped = Boolean(state.summaryFlip);
    body.append(el("button", {
      type: "button",
      class: "flash",
      "data-act": "flip-card",
      "aria-pressed": flipped ? "true" : "false",
    },
    el("span", { class: "tiny", text: flipped ? "뒷면" : "앞면" }),
    el("strong", { text: flipped ? card.back : card.front }),
    el("span", { class: "tiny", text: "눌러서 뒤집기" }),
    ));
  } else {
    body.append(el("p", { text: "이 자료는 표를 사례 단위로 읽고, 열 이름을 질문으로 둡니다." }));
    body.append(el("ol", { class: "key-points" },
      el("li", { text: "한 행은 사례 하나입니다." }),
      el("li", { text: "열 이름은 다시 읽을 질문입니다." }),
      el("li", { text: "자주 보는 열과 가끔 보는 열을 나눕니다." }),
      el("li", { text: "원문 문장은 옮기지 않았습니다." }),
    ));
    for (const block of SUMMARY.summary) {
      const heading = el("h3", { text: block.title });
      if (block.pages) heading.append(el("span", { class: "page-chip", text: block.pages }));
      const article = el("article", { class: "sum-block" }, heading);
      if (block.body) article.append(el("p", { text: block.body }));
      if (block.problem) article.append(el("p", {}, el("b", { text: "문제 " }), block.problem));
      if (block.answer) article.append(el("p", {}, el("b", { text: "메모 " }), block.answer));
      body.append(article);
    }
  }
  return el("aside", {
    class: `summary-panel${embed ? " embed" : ""}${state.summaryOpen ? " open" : ""}`,
    "aria-label": "테크뇽 요약",
  },
  el("div", { class: "sum-h" },
    icon("book"),
    el("div", {},
      el("strong", { text: "테크뇽 요약" }),
      el("span", { class: "tiny", text: "원문 3쪽" }),
    ),
    badge("요약 완료", "success"),
    embed ? null : el("button", { type: "button", class: "x sum-close", "data-act": "summary-sheet", "aria-label": "요약 닫기", text: "×" }),
  ),
  el("div", { class: "sum-tabs", role: "tablist" }, ...tabs.map(([id, label]) => el("button", {
    type: "button",
    role: "tab",
    class: tab === id ? "on" : "",
    "aria-selected": tab === id ? "true" : "false",
    "data-act": "summary-tab",
    "data-tab": id,
    text: label,
  }))),
  body,
  );
}

function renderPreview(ctx) {
  // Backend mode: `n=srv-<fileId>` opens the server preview session (preview-server.js).
  const server = serverPreviewScreen(ctx);
  if (server) return server;
  const blocked = sampleInServerCourse((ctx.params && ctx.params.get("kj")) || ctx.state.courseId, "파일");
  if (blocked) return blocked;
  const zoom = ctx.state.previewZoom || 1;
  const fileId = (ctx.params && ctx.params.get("n")) || "f1";
  const viewer = createDocumentViewer({
    documentId: `lecture-preview:${fileId}`,
    pageCount: PAGES.length,
    label: "week5-cases.pdf",
    initialPage: ctx.state.previewPage || 1,
    onPageChange(page) {
      if (ctx.state.previewPage === page) return;
      ctx.state.previewPage = page;
      save();
    },
    renderPage(number, host) {
      const doc = PAGES[number - 1];
      host.replaceChildren(el("article", { class: "preview-doc", style: `--doc-scale:${zoom}` },
        el("p", { class: "kicker", text: doc.kicker }),
        el("h1", { text: doc.title }),
        el("p", { text: doc.body }),
        el("p", { text: "긴 문단도 줄 길이 안에서 읽히게 두었습니다. 확대는 글자 크기만 바꾸고, 가로 스크롤을 만들지 않습니다." }),
      ));
    },
    extras: [
      el("span", { class: "tiny", text: `${Math.round(zoom * 100)}%` }),
      btn("축소", { "data-act": "preview-zoom", "data-z": "-1", "aria-label": "축소" }, "ghost"),
      btn("확대", { "data-act": "preview-zoom", "data-z": "1", "aria-label": "확대" }, "ghost"),
      btn("100%", { "data-act": "preview-zoom", "data-z": "reset" }, "ghost"),
      btn("테크뇽 요약", { "data-act": "summary-sheet", "aria-expanded": ctx.state.summaryOpen ? "true" : "false" }, "secondary"),
    ],
  });
  return el("section", { class: "bleed preview-layout" },
    el("div", { class: "viewer" }, viewer),
    summaryAside(ctx.state, false),
  );
}

function renderAttendance(screen, ctx) {
  const course = courseById(screen.course || ctx.state.courseId);
  const selected = ctx.state.attendanceWeek || 11;
  const counts = { present: 0, late: 0, absent: 0, empty: 0 };
  const cards = [];
  for (let week = 1; week <= 14; week += 1) {
    const status = attendStatus(ctx.state, "minjun", week, screen.section);
    counts[status] = (counts[status] || 0) + 1;
    const start = new Date(2026, 7, 31 + (week - 1) * 7);
    const end = new Date(start);
    end.setDate(start.getDate() + 6);
    const sessions = status === "late" ? ["late", "present", "present"] : [status, status, status];
    cards.push(el("article", { class: `att-card${week === selected ? " is-current" : ""}`, id: `att-${week}` },
      el("header", { class: "att-card-h" },
        el("h2", { text: `${week}주` }),
        el("p", { class: "tiny", text: `${koDate(start)} – ${koDate(end)} · ${course.time}${screen.section === "ml" ? " · ML 분반" : ""}` }),
      ),
      ...sessions.map((item, index) => el("div", { class: "att-session" },
        el("span", { class: "att-rail", "aria-hidden": "true" }, el("i")),
        el("span", {}, el("b", { text: `${index + 1}차시` }), el("span", { class: "tiny", text: course.time })),
        el("span", { class: `att-status st-${item}`, text: statusLabel(item) }),
      )),
    ));
  }
  const bar = el("div", { class: "attend-weeks", role: "tablist", "aria-label": "주차" });
  for (let week = 1; week <= 14; week += 1) {
    bar.append(el("button", {
      type: "button",
      class: `week-num${week === selected ? " on" : ""}`,
      "data-act": "attend-week",
      "data-week": String(week),
      "aria-pressed": week === selected ? "true" : "false",
      text: String(week),
    }));
  }
  const head = el("header", { class: "attend-head" },
    typeLine("report", el("h1", { text: "출석" }), "check"),
  );
  // 백엔드 모드: 서버 출석부와 스마트출석(areas/learning/attendance-server.js). 분반 버튼은 샘플이라 붙이지 않는다.
  if (backendStatus() !== "off") return remoteAttendance(screen, ctx, head);
  if (String(screen.id).startsWith("41")) {
    head.append(el("div", { class: "seg" },
      go(hrefFor(find("41_room_attendance")), "본반", { class: screen.section === "ml" ? "btn btn-ghost" : "btn btn-secondary" }),
      go(hrefFor(find("41b_room_attendance_ml")), "ML 분반", { class: screen.section === "ml" ? "btn btn-secondary" : "btn btn-ghost" }),
    ));
  }
  return el("section", { class: "bleed attend" },
    head,
    el("p", { class: "subhead", text: `${course.ko} · ${course.code}` }),
    el("div", { class: "att-stats" },
      el("span", { text: `출석 ${counts.present}` }),
      el("span", { text: `지각 ${counts.late}` }),
      el("span", { text: `결석 ${counts.absent}` }),
      el("span", { text: `미정 ${counts.empty}` }),
    ),
    bar,
    ...cards,
  );
}

function messageHref(folder, id) {
  const screenId = folder === "sent" ? "02d_msg_sent_tab" : "02c_msg_view";
  return hrefFor(find(screenId), id ? { id } : {});
}

function renderMessages(screen, ctx) {
  // Backend mode (or a `srv-` address): server 쪽지 screens (areas/learning/messages-server.js).
  if (usesServerMessages(ctx.params)) return serverMessages(screen, ctx);
  seedMail(ctx.state);
  const folder = screen.folder || "inbox";
  const err = ctx.state.formError || {};
  const query = String(ctx.state.draft.mailQuery || "").trim();
  const source = ctx.state.messages.filter((m) => m.folder === (folder === "write" ? "inbox" : folder));
  const list = source.filter((m) => !query || `${m.title} ${m.from} ${m.body}`.includes(query));
  const currentId = ctx.params && ctx.params.get("id");
  const current = ctx.state.messages.find((m) => m.id === currentId) || (screen.id === "02c_msg_view" ? list[0] : null);
  const showDetail = folder !== "write" && Boolean(current) && (Boolean(currentId) || screen.id === "02c_msg_view");
  const picked = new Set(ctx.state.draft.picked || []);
  const counts = {
    inbox: ctx.state.messages.filter((m) => m.folder === "inbox").length,
    sent: ctx.state.messages.filter((m) => m.folder === "sent").length,
  };
  const tab = (id, label, on) => go(hrefFor(find(id)), label, { class: on ? "on" : "", role: "tab", "aria-selected": on ? "true" : "false" });
  const tabs = el("div", { class: "msg-tabs", role: "tablist" },
    tab("02e_msg_compose", "쪽지쓰기", folder === "write"),
    tab("02b_msg_window", `받은쪽지 ${counts.inbox}`, folder === "inbox"),
    tab("02d_msg_sent_tab", `보낸쪽지 ${counts.sent}`, folder === "sent"),
  );
  if (folder === "write") {
    const draft = ctx.state.draft.mail || {};
    const enrolled = courses.filter((course) => course.enrolled);
    return el("div", { class: "msg-app" },
      tabs,
      el("div", { class: "compose" },
        field("제목", named(el("input", { "data-name": "title", value: draft.title || "", placeholder: "제목", maxlength: "80" }), err.title), err.title),
        el("div", { class: "compose-to" },
          btn("받는 사람", { "data-act": "open-recipients" }, "secondary"),
          field("받는 사람", named(el("input", { "data-name": "to", value: draft.to || "", placeholder: "이름을 입력하거나 아래에서 고르세요" }), err.to), err.to),
        ),
        field("과목", el("select", { "data-name": "course" },
          el("option", { value: "", text: "선택" }),
          ...enrolled.map((course) => el("option", { value: course.ko, text: course.ko, selected: draft.course === course.ko ? true : null })),
        )),
        field("내용", named(el("textarea", { "data-name": "body", maxlength: "2000", placeholder: "내용을 입력하세요.", text: draft.body || "" }), err.body), err.body),
        el("p", { class: "tiny byte", text: `${String(draft.body || "").length}/2000byte` }),
        el("div", { class: "drop", "data-drop": "1", text: "파일을 마우스로 끌어 놓아 업로드하세요." }),
        el("div", { class: "compose-files" },
          el("label", { class: "btn btn-secondary" }, icon("file"), "파일첨부", el("input", { type: "file", "data-name": "file", class: "file-input" })),
          btn("업로드에 실패했나요?", { "data-act": "upload-help" }, "ghost"),
        ),
        el("div", { class: "compose-send" }, btn("보내기", { "data-act": "send-mail" }, "primary")),
      ),
    );
  }
  const side = el("div", { class: "msg-list" },
    el("div", { class: "msg-search" },
      el("input", { "data-name": "mailQuery", value: query, placeholder: "검색어를 입력하세요.", "aria-label": "쪽지 검색" }),
      btn("검색", { "data-act": "mail-search" }, "ghost"),
    ),
    el("div", { class: "msg-tools" },
      el("label", { class: "checkline" },
        el("input", { type: "checkbox", "data-act": "mail-all", "data-folder": folder, checked: list.length > 0 && list.every((m) => picked.has(m.id)) ? true : null }),
        el("span", { text: "전체선택" }),
      ),
      btn("삭제", { "data-act": "delete-mail" }, "ghost"),
    ),
  );
  if (!list.length) side.append(el("p", { class: "msg-empty", text: "쪽지가 없습니다." }));
  for (const message of list) {
    const href = messageHref(message.folder, message.id);
    side.append(el("div", { class: `msg-item${showDetail && current && current.id === message.id ? " on" : ""}` },
      el("input", { type: "checkbox", "data-name": "picked", value: message.id, "aria-label": `${message.title} 선택`, checked: picked.has(message.id) ? true : null }),
      el("a", { class: "msg-open", href, "data-go": href },
        el("span", { class: "msg-avatar", text: message.from.slice(0, 1) }),
        el("span", { class: "msg-copy" },
          el("b", { text: message.title }),
          el("span", { class: "tiny", text: `${message.from} · ${message.at}` }),
        ),
        message.read ? null : el("span", { class: "unread-dot", "aria-label": "읽지 않음" }),
      ),
    ));
  }
  const main = el("div", { class: "msg-main" });
  if (showDetail && current) {
    main.append(el("article", { class: "msg-read" },
      go(hrefFor(find(folder === "sent" ? "02d_msg_sent_tab" : "02b_msg_window")), "목록", { class: "btn btn-ghost msg-back" }),
      el("h2", { text: current.title }),
      el("p", { class: "tiny", text: `${current.from} → ${current.to} · ${current.at}` }),
      el("div", { class: "prose" }, el("p", { text: current.body })),
      folder === "inbox" ? btn("답장", { "data-act": "reply-mail", "data-id": current.id }, "primary") : null,
    ));
  } else main.append(el("p", { class: "msg-placeholder", text: "목록에서 쪽지를 선택하세요." }));
  return el("div", { class: "msg-app" }, tabs, el("div", { class: `msg-pane${showDetail && current ? " has-detail" : ""}` }, side, main));
}

export function render(screen, ctx) {
  const view = (() => {
    switch (screen.kind) {
      case "week": return renderWeek(ctx);
      case "timetable": return renderTimetable(ctx);
      case "activity": return renderActivity(screen, ctx);
      case "player": return renderPlayer(screen, ctx);
      case "preview": return renderPreview(ctx);
      case "attendance": return renderAttendance(screen, ctx);
      case "messages": return renderMessages(screen, ctx);
      default: return emptyState("이 화면은 학습 영역이 아닙니다", screen.id);
    }
  })();
  return mark("learning", view);
}

export { people };
