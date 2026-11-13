import type { AssistantState } from "./shared/assistant/activity-context.ts";

/** A course card: a sample course here, or a server course view (shared/course-view.ts). */
export interface Course {
  id: string;
  code: string;
  ko: string;
  en: string;
  cardTitle?: string;
  instructor: string;
  credit: string;
  time: string;
  clock?: string;
  room: string;
  progress?: number;
  learners?: number;
  gradient: string;
  cover?: string;
  term: string;
  kind: "regular" | "special";
  enrolled: boolean;
  closed?: boolean;
  open?: boolean;
  owner?: string;
  capacity?: number;
  /** Server course view (backend `ready`). */
  server?: boolean;
  courseId?: number;
  role?: string;
  coverTemplate?: string;
  learningPeriod?: string;
  lectureType?: string;
  schedules?: unknown[];
  instructors?: unknown[];
  /** Backend placeholder for a `kj` the server does not have. */
  missing?: boolean;
}

export type ActivityType = "video" | "material" | "zoom" | "report" | "test" | "survey" | "post";

export interface Activity {
  id: string;
  courseId: string;
  week: number;
  type: ActivityType;
  title: string;
  status: string;
  minutes?: number;
  progress?: number;
  when?: string;
  whenLabel?: string;
  start?: string;
  due?: string;
  points?: number;
  score?: number;
  offline?: boolean;
  body?: string;
  fileName?: string;
  meeting?: string;
  pass?: string;
  clipId?: string;
  attendStart?: string;
  attendEnd?: string;
  /** Written by the professor in this browser (store.ts syncActivities). */
  authored?: boolean;
  /** End of an authored activity's window (professor editor). */
  end?: string;
  /** IndexedDB key of an uploaded video (shared/activity-files.ts). */
  fileId?: string;
  /** Course PDF the material opens in the study room (shared/course-pdfs.ts). */
  pdfId?: string;
}

export interface Notice {
  id: string;
  scope: string;
  audience?: string;
  title: string;
  date: string;
  author: string;
  body: string;
}

export interface CalendarEvent {
  date: string;
  type: string;
  label?: string;
  title?: string;
  activityId?: string;
  courseId?: string;
}

export interface Message {
  id: string;
  folder: string;
  from: string;
  to: string;
  title: string;
  body: string;
  at: string;
  read: boolean;
}

export interface Question {
  id: string;
  bankId: string;
  type: string;
  points: number;
  stem: string;
  choices: string[];
  answer: number | string;
}

export interface Bank {
  id: string;
  title: string;
  count: number;
  updated: string;
  courseId: string;
}

export interface PlayerState {
  id: string;
  seconds: number;
  duration: number;
  playing: boolean;
  speed: number;
  positions?: Record<string, number>;
  /** Activity ids whose video played to the end. */
  watched?: Record<string, boolean>;
}

/**
 * The persisted sample state (store.ts). Areas add the fields they own with declaration merging:
 * `declare module "../../data.ts" { interface AppState { myField?: T } }`.
 */
export interface AppState {
  theme: "dark" | "light";
  themeMode: "dark" | "light" | "system";
  role: "student" | "professor";
  lang: "ko" | "en";
  courseId: string;
  month: string;
  week: number;
  asideOpen: boolean;
  navOpen: boolean;
  toast: string;
  queries: Record<string, string>;
  assistant: AssistantState;
  welcomeSeen: boolean;
  submissions: Record<string, { who: string; title: string; body: string; fileName?: string; at: string }>;
  messages: Message[];
  posts: unknown[];
  communityQna: unknown[];
  surveys: Record<string, unknown>;
  scores: Record<string, Record<string, number | string>>;
  attendance: Record<string, unknown>;
  questions: Question[];
  banks: Bank[];
  madeAssignments: unknown[];
  madeExams: unknown[];
  contents: { id: string; title: string; minutes: number; at: string }[];
  zooms: unknown[];
  noticesExtra: Notice[];
  materialsExtra: unknown[];
  appeals: { id: string; who: string; item: string; body: string; status: string; reply: string }[];
  teams: unknown[];
  teamAsk: string;
  applications: Record<string, unknown>;
  devices: { id: string; name: string; where: string; at: string; current: boolean }[];
  fileBox: { name: string; at: string }[];
  player: PlayerState;
  previewPage: number;
  weights: Record<string, number>;
  tas: { name: string; role: string; courseId: string }[];
  gradePublic: boolean;
  read: string[];
  formError: Record<string, string>;
  /** Unsaved form input of the open screen, keyed by field. */
  draft: Record<string, any>;
  summaryTab: string;
  technyonOpen?: boolean;
  authoredActivities?: Activity[];
  deletedActivityIds?: string[];
}

export const student = { name: "박민준", no: "21101234", dept: "산업정보시스템전공", year: "3학년", avatar: "사용" };
export const professor = { name: "박은주", no: "P-1042", dept: "산업정보시스템전공", avatar: "은주" };

export const people = [
  { id: "minjun", name: "박민준", no: "21101234", role: "student" },
  { id: "seoyeon", name: "김서연", no: "21010978", role: "student" },
  { id: "jieun", name: "이지은", no: "21024561", role: "student" },
  { id: "doyun", name: "이도윤", no: "21031842", role: "student" },
  { id: "haeun", name: "최하은", no: "21045670", role: "student" },
  { id: "woojin", name: "정우진", no: "21058713", role: "student" },
  { id: "harin", name: "한하린", no: "21064220", role: "student" },
  { id: "eunju", name: "박은주", no: "P-1042", role: "professor" },
];

export const courses: Course[] = [
  { id: "algo", code: "109481-31001", ko: "고급웹프로그래밍", en: "Advanced Web Programming", cardTitle: "고급웹프로그래밍", instructor: "아비르", credit: "3", time: "금(6 ~ 9)", clock: "14:00~18:00", room: "-", progress: 62, learners: 40, gradient: "g1", cover: "/app/assets/covers/cover-pale-discs-716x430.png", term: "2026-2", kind: "regular", enrolled: true, owner: "doyun" },
  { id: "ai", code: "109078-31001", ko: "기계학습", en: "Machine Learning", cardTitle: "기계학습", instructor: "한지형", credit: "3", time: "수(6 ~ 8)", clock: "13:00~16:00", room: "미래관 305", progress: 62, learners: 36, gradient: "g2", cover: "/app/assets/covers/cover-purple-orbs-467x280.png", term: "2026-2", kind: "regular", enrolled: true, owner: "seoyeon-prof" },
  { id: "bi", code: "109834-21001", ko: "데이터베이스", en: "Database", cardTitle: "Database", instructor: "김성곤 외 1명", credit: "3", time: "목(6 ~ 8)", clock: "15:00~18:00", room: "창학관 202", progress: 62, learners: 36, gradient: "g3", cover: "/app/assets/covers/cover-magenta-sphere-716x430.png", term: "2026-2", kind: "regular", enrolled: true, owner: "eunju" },
  { id: "db", code: "109086-41001", ko: "블록체인", en: "Blockchain", cardTitle: "블록체인", instructor: "이창훈", credit: "3", time: "화(2 ~ 4)", clock: "10:00~13:00", room: "미래관 201", progress: 62, learners: 32, gradient: "g4", cover: "/app/assets/covers/cover-purple-orbs-467x280.png", term: "2026-2", kind: "regular", enrolled: true, owner: "eunju" },
  { id: "oss", code: "109840-41001", ko: "빅데이터처리", en: "Big Data Processing", cardTitle: "빅데이터처리", instructor: "김성곤 외 3명", credit: "3", time: "월(2 ~ 4)", clock: "10:00~13:00", room: "상상관 110", progress: 62, learners: 33, gradient: "g5", cover: "/app/assets/covers/cover-navy-waves-467x280.png", term: "2026-2", kind: "regular", enrolled: true, owner: "minjae" },
  { id: "se", code: "109285-31001", ko: "사이버보안", en: "Cyber Security", cardTitle: "사이버보안", instructor: "손기욱", credit: "3", time: "화(6 ~ 8)", clock: "15:00~18:00", room: "미래관 109", progress: 62, learners: 29, gradient: "g6", cover: "/app/assets/covers/cover-purple-wing-467x280.png", term: "2026-2", kind: "regular", enrolled: true, owner: "haneul" },
  { id: "secu", code: "109512-41001", ko: "컴퓨터보안", en: "Computer Security", cardTitle: "컴퓨터보안", instructor: "박종혁", credit: "3", time: "수(2 ~ 4)", clock: "10:00~13:00", room: "창학관 110", progress: 62, learners: 28, gradient: "g6", cover: "/app/assets/covers/cover-navy-waves-467x280.png", term: "2026-2", kind: "regular", enrolled: true, owner: "haneul" },
  { id: "ux", code: "109300-21011", ko: "UX 디자인", en: "UX Design", cardTitle: "UX 디자인", instructor: "한지민", credit: "2", time: "수 15:00-17:00", room: "디자인관 203", progress: 0, learners: 24, gradient: "g7", term: "2026-2", kind: "regular", enrolled: false, owner: "jimin" },
  { id: "web1", code: "109160-21003", ko: "웹프로그래밍", en: "Web Programming", instructor: "김도윤", credit: "3", time: "월 13:00-16:00", room: "미래관 220", progress: 100, learners: 41, gradient: "g8", term: "2026-1", kind: "regular", enrolled: true, closed: true, owner: "doyun" },
  { id: "sec", code: "NR-2026-01", ko: "융합보안 입문", en: "Intro to Converged Security", instructor: "박은주", credit: "1", time: "온라인", room: "e-learning", progress: 12, learners: 80, gradient: "g2", term: "2026-2", kind: "special", enrolled: true, owner: "eunju", capacity: 100 },
  { id: "lit", code: "NR-2026-02", ko: "AI 리터러시", en: "AI Literacy", instructor: "이서연", credit: "1", time: "온라인", room: "e-learning", progress: 0, learners: 64, gradient: "g5", term: "2026-2", kind: "special", enrolled: false, owner: "seoyeon-prof", capacity: 120, open: true },
  { id: "civ", code: "NR-2026-03", ko: "디지털 시민성", en: "Digital Citizenship", instructor: "최민재", credit: "1", time: "온라인", room: "e-learning", progress: 0, learners: 40, gradient: "g6", term: "2026-2", kind: "special", enrolled: false, owner: "minjae", capacity: 80 },
  { id: "prv", code: "NR-2026-04", ko: "정보보호와 프라이버시", en: "Privacy and Protection", instructor: "정하늘", credit: "1", time: "온라인", room: "e-learning", progress: 0, learners: 28, gradient: "g1", term: "2026-2", kind: "special", enrolled: false, owner: "haneul", capacity: 60 },
];

const W = (n: number) => {
  const start = new Date(2026, 7, 31 + (n - 1) * 7);
  const end = new Date(start);
  end.setDate(start.getDate() + 6);
  const fmt = (d: Date) => `${d.toLocaleString("en-US", { month: "long" })} ${d.getDate()}`;
  return `${fmt(start)} – ${fmt(end)}`;
};

export const activities: Activity[] = [
  { id: "v1", courseId: "bi", week: 1, type: "video", title: "강의소개", status: "done", minutes: 18 },
  { id: "v2", courseId: "bi", week: 1, type: "video", title: "강의소개 보충", status: "done", minutes: 12 },
  { id: "v3", courseId: "bi", week: 2, type: "video", title: "3주차 슬라이드 강의", status: "done", minutes: 24 },
  { id: "m2", courseId: "bi", week: 2, type: "material", title: "데이터 컨셉 레퍼런스", status: "done" },
  { id: "z3", courseId: "bi", week: 3, type: "zoom", title: "Zoom 리마인드 세션", status: "done", when: "2026-09-16 19:00" },
  { id: "m3", courseId: "bi", week: 3, type: "material", title: "3주차 질의자료", status: "done" },
  { id: "v-open", courseId: "bi", week: 4, type: "video", title: "텍스트마이닝 실습", status: "ongoing", minutes: 32, progress: 40 },
  { id: "v-expired", courseId: "bi", week: 4, type: "video", title: "중간 점검 퀴즈 해설", status: "expired", minutes: 15 },
  { id: "m-1", courseId: "bi", week: 5, type: "material", title: "사례 데이터 묶음", status: "open" },
  { id: "r-open", courseId: "bi", week: 8, type: "report", title: "텍스트마이닝 미션 2", status: "missing", start: "2026-11-10T00:00", due: "2026-11-21T23:50", points: 20, body: "주제에 맞는 텍스트 데이터를 수집하고 전처리·토픽모델링 결과를 보고서로 제출하세요. 제출 형식은 PDF 또는 ZIP입니다." },
  { id: "r-done", courseId: "bi", week: 6, type: "report", title: "중간 사례 보고서", status: "submitted", due: "2026-10-18T23:59", points: 15, body: "관심 산업의 BI 활용 사례를 2쪽 이내로 정리합니다.", fileName: "bi-case-note.pdf" },
  { id: "t-off", courseId: "bi", week: 8, type: "test", title: "중간고사", status: "graded", due: "2026-10-22T12:00", points: 30, score: 24, offline: true, body: "대면 시험이 종료되었습니다. 점수는 성적 공개 이후 강의실 성적에서 확인할 수 있습니다." },
  { id: "t-on", courseId: "oss", week: 13, type: "test", title: "기말 온라인 시험", status: "scheduled", start: "2026-12-10T10:00", due: "2026-12-10T10:50", points: 30, minutes: 50, body: "시험 시작 시각 이전에 입장할 수 없습니다. 시작 후 제한 시간 안에 제출합니다." },
  { id: "z-1", courseId: "bi", week: 9, type: "zoom", title: "프로젝트 중간 멘토링", status: "ongoing", when: "2026-11-12 19:00", meeting: "812 4401 2291", pass: "sample" },
  { id: "s-1", courseId: "bi", week: 10, type: "survey", title: "수업 중간 설문", status: "missing", due: "2026-11-30T23:59" },
  { id: "lec-11-1", courseId: "ai", week: 11, type: "video", title: "11주 1차시", status: "ongoing", minutes: 42, due: "2026-11-13T23:59", whenLabel: "시작됨 ~ 11월 13일 (금) 오후 11:59", body: "기계학습 11주 1차시입니다. 영상 파일은 없고, 재생 위치만 이 브라우저에서 움직입니다." },
  { id: "lec-11-2", courseId: "ai", week: 11, type: "video", title: "11주 2차시", status: "ongoing", minutes: 38, due: "2026-11-11T23:59", whenLabel: "시작됨 ~ 11월 11일 (수) 오후 11:59", body: "기계학습 11주 2차시입니다. 학교 서버로 재생 기록을 보내지 않습니다." },
  { id: "lec-10-1", courseId: "ai", week: 10, type: "video", title: "10주 1차시", status: "ongoing", minutes: 36, due: "2026-11-11T23:59", body: "기계학습 10주 1차시입니다." },
  { id: "lec-10-2", courseId: "ai", week: 10, type: "video", title: "10주 2차시", status: "ongoing", minutes: 36, due: "2026-11-11T23:59", body: "기계학습 10주 2차시입니다." },
  { id: "rep-project", courseId: "bi", week: 12, type: "report", title: "Project", status: "missing", start: "2026-11-20T00:00", due: "2026-12-03T23:59", points: 20, whenLabel: "12월 3일 (목) 오후 11:59", body: "Database 프로젝트입니다. 주제와 결과 표를 이 브라우저 안에서만 제출합니다. 원문 데이터는 넣지 않습니다." },
  { id: "quiz-final", courseId: "oss", week: 15, type: "test", title: "기말고사 (시험시간 : 0분)", status: "scheduled", start: "2026-12-11T00:00", due: "2026-12-11T23:59", points: 30, minutes: 0, whenLabel: "시작됨 ~ 12월 11일 (금) 오후 11:59", body: "빅데이터처리 기말 퀴즈입니다. 시작 시각 전에는 응시 버튼이 열리지 않습니다." },
  { id: "lec-chain", courseId: "db", week: 9, type: "video", title: "블록체인 9주차 실습", status: "ongoing", minutes: 28, body: "블록체인 9주차 실습 차시입니다." },
  { id: "lec-sec", courseId: "se", week: 11, type: "video", title: "사이버보안 실습", status: "ongoing", minutes: 24, body: "사이버보안 실습 차시입니다." },
  { id: "rep-web", courseId: "algo", week: 11, type: "report", title: "고급웹 과제", status: "missing", start: "2026-11-01T00:00", due: "2026-11-13T23:59", points: 10, body: "시맨틱 문서 한 쪽을 이 브라우저에만 제출합니다." },
  { id: "nr-v", courseId: "sec", week: 1, type: "video", title: "보안 위협 개요", status: "ongoing", minutes: 20, progress: 10 },
  { id: "nr-s", courseId: "sec", week: 1, type: "survey", title: "사전 이해도 설문", status: "missing", due: "2026-11-20T23:59" },
];

export const weekCount = 14;
export const weekLabel = W;

export const notices: Notice[] = [
  { id: "n1", scope: "global", audience: "all", title: "2026-2 학습활동 일정 안내", date: "2026-11-04", author: "e-Class", body: "중간고사 이후 과목별 과제 마감이 한 주에 몰려 있습니다. 할 일 목록에서 마감 시각을 확인하세요. 이 글은 샘플 데이터입니다." },
  { id: "n2", scope: "global", audience: "student", title: "ASTRA 앱 미리보기 안내", date: "2026-11-02", author: "학습지원", body: "모바일 홈과 메뉴는 가로 390 화면에서 확인할 수 있습니다. 계정 정보는 샘플입니다." },
  { id: "n3", scope: "global", audience: "professor", title: "성적 입력 마감 리허설", date: "2026-10-28", author: "학사지원", body: "성적부 가중치 합이 100이 아니면 저장되지 않습니다. 샘플 학기 데이터만 수정됩니다." },
  { id: "cn1", scope: "bi", title: "[정정] 중간고사 결과 및 성적문의", date: "2026-10-27", author: "박은주", body: "채점 기준을 공지 본문에 다시 적었습니다. 이의는 강의실 이의신청이 아니라 이 샘플의 교수 화면에서만 기록됩니다." },
  { id: "cn2", scope: "bi", title: "[안내] 프로젝트 팀 구성", date: "2026-10-20", author: "박은주", body: "팀 프로젝트 메뉴는 현재 비어 있습니다. 학생 화면에서는 구성 요청만 저장됩니다." },
  { id: "cn3", scope: "sec", title: "비정규 과정 출석 인정 기준", date: "2026-11-01", author: "박은주", body: "영상 진도 90% 이상이면 해당 차시 출석으로 봅니다." },
  { id: "cn-algo-1", scope: "algo", title: "[과제] 3차 실습: React 상태 나누기", date: "2026-11-05", author: "아비르", body: "useReducer로 장바구니 상태를 나누는 실습입니다. 저장소 주소와 실행 화면 캡처를 함께 제출하세요." },
  { id: "cn-algo-2", scope: "algo", title: "[휴강] 11월 20일 수업은 녹화 강의로 대체", date: "2026-11-02", author: "아비르", body: "학회 일정으로 대면 수업이 없습니다. 녹화 강의는 학습활동 11주차에 올립니다." },
  { id: "cn-ai-1", scope: "ai", title: "[과제] 결정트리 실습 데이터 배포", date: "2026-11-06", author: "한지형", body: "붓꽃 데이터로 깊이별 정확도를 비교합니다. 검증 집합은 따로 떼어 두고 시험 집합은 마지막에 한 번만 쓰세요." },
  { id: "cn-ai-2", scope: "ai", title: "[안내] 기말 프로젝트 주제 제출", date: "2026-10-29", author: "한지형", body: "팀별 주제와 데이터 출처를 한 쪽으로 정리해 11월 19일까지 제출하세요." },
  { id: "cn-db-1", scope: "db", title: "[안내] 테스트넷 지갑 준비", date: "2026-11-04", author: "이창훈", body: "다음 실습 전에 테스트넷 지갑을 만들고 주소만 제출하세요. 개인 키는 누구에게도 보내지 마세요." },
  { id: "cn-db-2", scope: "db", title: "[정정] 해시 함수 퀴즈 3번 정답", date: "2026-10-30", author: "이창훈", body: "3번은 2번 보기도 정답으로 인정합니다. 점수는 다음 주에 다시 반영합니다." },
  { id: "cn-oss-1", scope: "oss", title: "[실습] Spark 클러스터 계정 발급", date: "2026-11-03", author: "김성곤", body: "실습 클러스터 계정을 학번으로 만들었습니다. 첫 접속 때 비밀번호를 바꾸세요." },
  { id: "cn-oss-2", scope: "oss", title: "[안내] 중간 프로젝트 데이터 크기 제한", date: "2026-10-24", author: "김성곤", body: "원본 데이터는 10GB 이하로 맞춰 주세요. 더 크면 표본을 뽑은 방법을 보고서에 적습니다." },
  { id: "cn-se-1", scope: "se", title: "[안내] 모의 해킹 실습 윤리 서약서 제출", date: "2026-11-05", author: "손기욱", body: "실습 서버 밖의 시스템은 절대 점검하지 않습니다. 서약서를 내야 실습 계정이 열립니다." },
  { id: "cn-se-2", scope: "se", title: "[과제] 웹 취약점 분석 보고서 양식", date: "2026-10-28", author: "손기욱", body: "취약점마다 재현 절차, 영향, 대응을 한 쪽씩 적습니다. 양식은 파일모음에 있습니다." },
  { id: "cn-secu-1", scope: "secu", title: "[안내] 버퍼 오버플로 실습 환경 배포", date: "2026-11-07", author: "박종혁", body: "실습용 가상머신 이미지를 올렸습니다. 호스트와 네트워크를 분리한 상태로 실행하세요." },
  { id: "cn-secu-2", scope: "secu", title: "[보강] 11월 18일 휴강 보강 일정", date: "2026-10-31", author: "박종혁", body: "보강은 11월 25일 수요일 10시, 창학관 110에서 합니다." },
];

/** A sample Q&A question (`course-qna`) or open-board post (`open`) of one course. */
export interface CoursePost {
  id: string;
  courseId: string;
  board: "course-qna" | "open";
  title: string;
  body: string;
  author: string;
  at: string;
  reply?: string;
}

// 박민준(샘플 학생)이 쓴 글만 그 학생이 고치거나 지울 수 있다.
export const coursePosts: CoursePost[] = [
  { id: "seed-q", courseId: "bi", board: "course-qna", title: "실습 데이터 형식", body: "CSV와 JSON 중 어떤 형식을 쓰면 되나요?", author: "김서연", at: "2026-11-03", reply: "CSV로 제출해 주세요." },
  { id: "q-bi-2", courseId: "bi", board: "course-qna", title: "3정규형 예제 질문", body: "강의 자료 18쪽 예제에서 학과명이 이행 종속인 이유를 다시 설명해 주실 수 있나요?", author: "박민준", at: "2026-11-07" },
  { id: "q-algo-1", courseId: "algo", board: "course-qna", title: "useEffect 의존성 배열 경고", body: "실습 코드에서 의존성 배열에 함수를 넣으라는 경고가 나옵니다. 그대로 두어도 되나요?", author: "이도윤", at: "2026-11-06", reply: "함수를 useCallback으로 감싸거나 effect 안으로 옮기세요." },
  { id: "q-algo-2", courseId: "algo", board: "course-qna", title: "과제 저장소 공개 범위", body: "과제 저장소를 공개로 올려도 되나요, 비공개로 두고 초대해야 하나요?", author: "최하은", at: "2026-11-04" },
  { id: "q-ai-1", courseId: "ai", board: "course-qna", title: "검증 정확도가 학습보다 높게 나옵니다", body: "드롭아웃을 넣었더니 검증 정확도가 학습 정확도보다 높습니다. 문제가 있는 건가요?", author: "정우진", at: "2026-11-05", reply: "학습 중에만 드롭아웃이 켜지므로 자주 있는 일입니다." },
  { id: "q-ai-2", courseId: "ai", board: "course-qna", title: "과제 제출 형식", body: "결정트리 과제를 ipynb 파일 그대로 내도 되나요?", author: "박민준", at: "2026-11-08" },
  { id: "q-db-1", courseId: "db", board: "course-qna", title: "가스비가 0으로 나오는 이유", body: "테스트넷에서 조회 함수를 호출하면 가스비가 0으로 나옵니다. 정상인가요?", author: "한하린", at: "2026-11-05", reply: "상태를 바꾸지 않는 view 호출은 가스비가 들지 않습니다." },
  { id: "q-db-2", courseId: "db", board: "course-qna", title: "머클 트리 그림 출처", body: "보고서에 강의 자료의 머클 트리 그림을 써도 되나요?", author: "김서연", at: "2026-11-02" },
  { id: "q-oss-1", courseId: "oss", board: "course-qna", title: "Spark 작업이 메모리 부족으로 멈춥니다", body: "groupBy 뒤에 collect를 하면 드라이버 메모리가 부족하다고 나옵니다.", author: "이지은", at: "2026-11-04", reply: "collect 대신 결과를 파일로 쓰거나 집계를 먼저 줄이세요." },
  { id: "q-oss-2", courseId: "oss", board: "course-qna", title: "HDFS 복제 계수", body: "실습 클러스터의 복제 계수를 바꿔도 되나요?", author: "이도윤", at: "2026-11-01" },
  { id: "q-se-1", courseId: "se", board: "course-qna", title: "실습용 가상머신 이미지 링크", body: "윤리 서약서를 냈는데 가상머신 이미지 링크가 보이지 않습니다.", author: "최하은", at: "2026-11-06", reply: "계정이 열리면 파일모음에 링크가 보입니다." },
  { id: "q-se-2", courseId: "se", board: "course-qna", title: "XSS 예제가 브라우저에서 막힙니다", body: "실습 예제의 스크립트가 최신 브라우저에서 실행되지 않습니다.", author: "정우진", at: "2026-11-03" },
  { id: "q-secu-1", courseId: "secu", board: "course-qna", title: "ASLR 끄는 방법", body: "실습 가상머신에서 ASLR을 끄라고 하셨는데 재부팅하면 다시 켜집니다.", author: "한하린", at: "2026-11-08", reply: "실습 스크립트가 시작할 때마다 끄므로 그대로 두면 됩니다." },
  { id: "q-secu-2", courseId: "secu", board: "course-qna", title: "실습 보고서 분량", body: "버퍼 오버플로 실습 보고서는 몇 쪽 정도로 쓰면 되나요?", author: "김서연", at: "2026-11-05" },
  { id: "o-bi-1", courseId: "bi", board: "open", title: "SQL 문제 풀이 스터디 모집", body: "매주 화요일 저녁에 SQL 문제를 같이 풉니다. 관심 있으면 댓글 대신 쪽지 주세요.", author: "이지은", at: "2026-11-02" },
  { id: "o-bi-2", courseId: "bi", board: "open", title: "ERD 도구 추천", body: "과제 ERD는 무료 도구로도 충분했습니다. 써 본 도구를 정리해 둡니다.", author: "박민준", at: "2026-11-06" },
  { id: "o-algo-1", courseId: "algo", board: "open", title: "프로젝트 팀원 구합니다 (프론트엔드)", body: "백엔드 두 명이 있고 화면을 맡을 분을 찾습니다.", author: "정우진", at: "2026-11-03" },
  { id: "o-ai-1", courseId: "ai", board: "open", title: "Kaggle 입문 대회 같이 하실 분", body: "타이타닉 대회로 전처리부터 같이 해 보려고 합니다.", author: "최하은", at: "2026-11-04" },
  { id: "o-db-1", courseId: "db", board: "open", title: "블록체인 세미나 후기", body: "지난주 교내 세미나에서 들은 합의 알고리즘 이야기를 정리했습니다.", author: "이도윤", at: "2026-11-01" },
  { id: "o-oss-1", courseId: "oss", board: "open", title: "Docker로 Spark 로컬 실행하기", body: "클러스터가 붐빌 때 로컬에서 먼저 돌려 본 방법을 정리했습니다.", author: "한하린", at: "2026-11-05" },
  { id: "o-se-1", courseId: "se", board: "open", title: "CTF 대회 참가 팀원 모집", body: "12월 교내 CTF에 나갈 팀원 두 명을 찾습니다.", author: "김서연", at: "2026-11-07" },
  { id: "o-secu-1", courseId: "secu", board: "open", title: "gdb 명령 정리 공유", body: "실습에서 자주 쓴 gdb 명령을 한 쪽으로 정리했습니다.", author: "이도윤", at: "2026-11-06" },
];

/** Order of this term's enrolled courses on home and in the study room. */
export const COURSE_ORDER = ["bi", "algo", "ai", "db", "oss", "se", "secu"];

export const studyMaterials = [
  { id: "sm-db", courseId: "bi", title: "관계형 모델 요약", sentence: "표는 한 가지 대상만 담고, 열 이름이 곧 질문입니다.", points: ["엔터티는 한 행의 대상입니다.", "속성은 그 대상의 열입니다.", "키는 행을 서로 구분합니다.", "관계는 두 표를 연결합니다."] },
  { id: "sm-web", courseId: "algo", title: "시맨틱 문서 구조", sentence: "제목과 구역을 나누면 자료 요약이 원문의 순서를 잃지 않습니다.", points: ["제목 단계는 하나씩만 내려갑니다.", "목록은 순서가 있을 때만 번호를 씁니다.", "표에는 머리글 칸을 둡니다.", "장식 이미지는 대체 글을 비웁니다."] },
  { id: "sm-ml", courseId: "ai", title: "학습 집합과 검증", sentence: "같은 데이터로 배우고 채점하면 새 사례에서 점수가 떨어집니다.", points: ["학습 집합은 규칙을 맞출 때 씁니다.", "검증 집합은 그 규칙을 고를 때 씁니다.", "시험 집합은 마지막에 한 번만 봅니다.", "과적합은 학습만 잘되고 검증은 나쁠 때입니다."] },
  { id: "sm-chain", courseId: "db", title: "블록에 남기는 기록", sentence: "앞 블록의 요약을 다음 블록이 가리키면 중간을 조용히 바꾸기 어렵습니다.", points: ["블록은 기록 묶음입니다.", "해시는 그 묶음의 짧은 지문입니다.", "체인은 이전 지문을 다음에 적습니다.", "합의는 어느 체인을 따를지 정합니다."] },
  { id: "sm-data", courseId: "oss", title: "큰 표 나누기", sentence: "자주 읽는 열과 가끔 읽는 열을 같은 자리에 두면 읽기가 느려집니다.", points: ["행은 사례 하나입니다.", "열 이름은 질문과 같아야 합니다.", "파티션은 표를 구간으로 나눕니다.", "인덱스는 자주 찾는 열의 지름길입니다."] },
  { id: "sm-cyber", courseId: "se", title: "경계와 권한", sentence: "누구인지 확인하는 일과 무엇을 허용하는 일은 따로 적습니다.", points: ["인증은 누구인지 확인합니다.", "인가는 무엇을 허용하는지 정합니다.", "기록은 나중에 길을 되돌릴 때 씁니다.", "최소 권한은 필요한 문만 엽니다."] },
  { id: "sm-comp", courseId: "secu", title: "시스템 호출 경계", sentence: "프로그램이 커널에 부탁하는 문 앞에서 검사하면 안쪽을 덜 믿어도 됩니다.", points: ["프로세스는 자신만의 메모리로 돕니다.", "시스템 호출은 그 밖으로 나가는 문입니다.", "권한 검사는 그 문 앞에 둡니다.", "실패는 거절로 돌려주고 삼키지 않습니다."] },
];

export const events: CalendarEvent[] = [
  { date: "2026-11-01", label: "생활관 식사제도 의…", type: "material" },
  { date: "2026-11-02", label: "2026학년도 2학기 수강철회기간", type: "material" },
  { date: "2026-11-03", label: "2026학년도 2학기 수강철회기간", type: "material" },
  { date: "2026-11-03", label: "2학기 블록체인 중…", type: "video", activityId: "lec-chain", courseId: "db" },
  { date: "2026-11-03", label: "블록체인 9주차 실습", type: "video", activityId: "lec-chain", courseId: "db" },
  { date: "2026-11-04", label: "10주 1차시", type: "video", activityId: "lec-10-1", courseId: "ai" },
  { date: "2026-11-04", label: "10주 2차시", type: "video", activityId: "lec-10-2", courseId: "ai" },
  { date: "2026-11-05", label: "11주 1차시", type: "video", activityId: "lec-11-1", courseId: "ai" },
  { date: "2026-11-05", label: "11주 2차시", type: "video", activityId: "lec-11-2", courseId: "ai" },
  { date: "2026-11-06", label: "2026학년도 2학기 수강철회기간", type: "material" },
  { date: "2026-11-10", courseId: "se", title: "사이버보안 실습", type: "video", activityId: "lec-sec" },
  { date: "2026-11-10", courseId: "algo", title: "고급웹 과제", type: "report", activityId: "rep-web" },
  { date: "2026-11-12", courseId: "bi", title: "멘토링 Zoom", type: "zoom", activityId: "z-1" },
  { date: "2026-11-13", courseId: "algo", title: "고급웹 과제", type: "report", activityId: "rep-web" },
  { date: "2026-11-21", courseId: "bi", title: "텍스트마이닝 미션 2", type: "report", activityId: "r-open" },
  { date: "2026-12-02", courseId: "ai", title: "기말 범위 공지", type: "material", activityId: "m-1" },
  { date: "2026-12-03", courseId: "bi", title: "Project", type: "report", activityId: "rep-project" },
  { date: "2026-12-10", courseId: "oss", title: "온라인 시험", type: "test", activityId: "t-on" },
  { date: "2026-12-11", courseId: "oss", title: "기말고사 (시험시간 : 0분)", type: "test", activityId: "quiz-final" },
];

export const seedMessages: Message[] = [
  { id: "msg1", folder: "inbox", from: "박은주", to: "박민준", title: "미션 2 데이터 범위", body: "공공 데이터만 사용하고, 개인이 식별되는 원문은 넣지 마세요. 이 쪽지는 샘플입니다.", at: "2026-11-08 09:12", read: false },
  { id: "msg2", folder: "inbox", from: "학습지원", to: "박민준", title: "설문 참여 요청", body: "수업 중간 설문은 11월 30일까지입니다.", at: "2026-11-06 14:02", read: true },
  { id: "msg3", folder: "sent", from: "박민준", to: "박은주", title: "팀 구성 문의", body: "그룹 메뉴가 비어 있어 문의드립니다.", at: "2026-11-05 18:40", read: true },
];

/** One sample notification (알림 dialog and the header bell). */
export interface SampleNotification {
  title: string;
  read: boolean;
  activityId?: string;
  kind?: string;
  source?: string;
  when?: string;
}

export const sampleNotifications: SampleNotification[] = [
  { title: "Project 마감이 가까워졌습니다", read: false, activityId: "rep-project" },
  { title: "11주 1차시가 열렸습니다", read: false, activityId: "lec-11-1" },
  { title: "텍스트마이닝 미션 2 마감이 가까워졌습니다", read: false, activityId: "r-open" },
  { title: "주간 일정이 갱신되었습니다", read: true, kind: "일정", source: "주간 일정", when: "11월 2일 (월)" },
];

export const seedQuestions: Question[] = [
  { id: "q1", bankId: "b1", type: "객관식", points: 5, stem: "다음 중 과적합에 해당하는 설명은?", choices: ["훈련 오차는 낮은데 검증 오차가 크다", "훈련과 검증 오차가 함께 크다", "데이터가 전혀 없다", "라벨이 하나뿐이다"], answer: 0 },
  { id: "q2", bankId: "b1", type: "단답", points: 4, stem: "TF-IDF에서 IDF가 낮아지는 단어의 특징을 한 줄로 쓰세요.", choices: [], answer: "여러 문서에 공통으로 등장한다" },
  { id: "q3", bankId: "b2", type: "객관식", points: 3, stem: "중앙값을 뜻하는 용어는?", choices: ["mean", "median", "mode", "variance"], answer: 1 },
];

export const seedBanks: Bank[] = [
  { id: "b1", title: "Business Intelligence 중간고사 은행", count: 24, updated: "2026-03-04", courseId: "bi" },
  { id: "b2", title: "통계 기초 퀴즈 세트", count: 12, updated: "2026-08-28", courseId: "db" },
];

// Backend mode supplies its own course objects (shared/course-view.js): a server course's view for a server `kj`,
// a "not on the server" placeholder for an unknown one. It answers undefined in mock mode and for sample ids it
// leaves to the sample, so mock mode keeps the sample lookup and its fallback below.
let serverCourse: (id: string) => Course | undefined = () => undefined;

export function resolveCoursesWith(fn: (id: string) => Course | undefined) {
  serverCourse = fn;
}

export function courseById(id: string | null | undefined): Course {
  return (id ? serverCourse(id) : undefined) || courses.find((c) => c.id === id) || courses[2];
}

export function activitiesOf(courseId: string) {
  return activities.filter((a) => a.courseId === courseId);
}

export const surveyQuestions = [
  { id: "sq1", prompt: "이번 주 난이도는 적절했나요?", options: ["쉬움", "적절", "어려움"] },
  { id: "sq2", prompt: "실습 자료는 충분했나요?", options: ["예", "아니오"] },
];

export const files = [
  { id: "f1", courseId: "bi", name: "week5-cases.pdf", size: "1.2 MB", week: 5, at: "2026-10-02" },
  { id: "f2", courseId: "bi", name: "dataset-guide.pdf", size: "840 KB", week: 8, at: "2026-11-01" },
  { id: "f3", courseId: "bi", name: "syllabus-bi.pdf", size: "220 KB", week: 1, at: "2026-09-01" },
  { id: "f4", courseId: "algo", name: "week3-react-state.pdf", size: "1.6 MB", week: 3, at: "2026-09-18" },
  { id: "f5", courseId: "algo", name: "project-guide-web.pdf", size: "540 KB", week: 9, at: "2026-11-05" },
  { id: "f6", courseId: "ai", name: "week4-decision-tree.pdf", size: "2.1 MB", week: 4, at: "2026-09-24" },
  { id: "f7", courseId: "ai", name: "iris-dataset-guide.pdf", size: "310 KB", week: 9, at: "2026-11-06" },
  { id: "f8", courseId: "db", name: "week2-hash-basics.pdf", size: "980 KB", week: 2, at: "2026-09-09" },
  { id: "f9", courseId: "db", name: "testnet-wallet-setup.pdf", size: "450 KB", week: 9, at: "2026-11-04" },
  { id: "f10", courseId: "oss", name: "week5-spark-intro.pdf", size: "1.9 MB", week: 5, at: "2026-09-29" },
  { id: "f11", courseId: "oss", name: "cluster-account-guide.pdf", size: "260 KB", week: 9, at: "2026-11-03" },
  { id: "f12", courseId: "se", name: "week6-web-vuln.pdf", size: "1.4 MB", week: 6, at: "2026-10-07" },
  { id: "f13", courseId: "se", name: "vuln-report-template.pdf", size: "180 KB", week: 8, at: "2026-10-28" },
  { id: "f14", courseId: "secu", name: "week7-buffer-overflow.pdf", size: "2.3 MB", week: 7, at: "2026-10-15" },
  { id: "f15", courseId: "secu", name: "lab-vm-guide.pdf", size: "620 KB", week: 9, at: "2026-11-07" },
];

export const gradeItems = [
  { id: "att", name: "출석", weight: 10 },
  { id: "rep", name: "과제", weight: 30 },
  { id: "mid", name: "중간", weight: 30 },
  { id: "fin", name: "기말", weight: 30 },
];

export function seedState(): AppState {
  return {
    theme: "dark",
    themeMode: "system",
    role: "student",
    lang: "ko",
    courseId: "bi",
    month: "2026-11",
    week: 4,
    asideOpen: false,
    navOpen: false,
    toast: "",
    queries: {},
    assistant: { date: "", requests: [] },
    welcomeSeen: true,
    submissions: {
      "r-done": { who: "minjun", title: "중간 사례 보고서", body: "샘플로 이미 제출된 보고서입니다.", fileName: "bi-case-note.pdf", at: "2026-10-17 21:14" },
    },
    messages: seedMessages.map((m) => ({ ...m })),
    posts: [],
    communityQna: [],
    surveys: {},
    scores: {
      seoyeon: { "r-open": 92, att: 10, rep: 27, mid: 24, fin: "" },
      minjun: { att: 9, rep: "", mid: 24, fin: "" },
      jieun: { att: 8, rep: 20, mid: 21, fin: "" },
    },
    attendance: {},
    questions: seedQuestions.map((q) => ({ ...q, choices: [...q.choices] })),
    banks: seedBanks.map((b) => ({ ...b })),
    madeAssignments: [],
    madeExams: [],
    contents: [
      { id: "c1", title: "텍스트마이닝 실습", minutes: 32, at: "2026-09-20" },
      { id: "c2", title: "보안 위협 개요", minutes: 20, at: "2026-09-28" },
    ],
    zooms: [],
    noticesExtra: [],
    materialsExtra: [],
    appeals: [
      { id: "ap1", who: "이지은", item: "중간고사 4번", body: "부분점수를 요청합니다.", status: "대기", reply: "" },
    ],
    teams: [],
    teamAsk: "",
    applications: {},
    devices: [
      { id: "d1", name: "Campus laptop", where: "서울", at: "2026-11-10 08:12", current: true },
      { id: "d2", name: "Mobile browser", where: "서울", at: "2026-11-09 19:40", current: false },
    ],
    fileBox: [
      { name: "bi-case-note.pdf", at: "2026-10-17" },
    ],
    player: { id: "v-open", seconds: 420, duration: 32 * 60, playing: false, speed: 1 },
    previewPage: 1,
    weights: { att: 10, rep: 30, mid: 30, fin: 30 },
    tas: [{ name: "오서준", role: "조교", courseId: "bi" }],
    gradePublic: false,
    read: [],
    formError: {},
    draft: {},
    summaryTab: "summary",
  };
}
