// 강의계획서 팝업(40b)의 서버 버전(`GET /api/courses/{courseId}/syllabus`). 서술 항목과 주별 계획은 일반 텍스트이고, 줄바꿈만
// 살려 텍스트 노드로 그린다. 비어 있는 항목은 "—"로 둔다. 담당자 연락처는 응답에 있을 때만 그린다. 담당자 사진은 구성원에게 오는
// `userId`로 `GET /api/users/{userId}/avatar`를 읽고, 없거나 볼 수 없으면 이름 첫 글자다.
import { el } from "../../dom.js";
import { btn, dialogShell, emptyState, loadingState, errorState, table } from "../../ui/components.js";
import { ApiError } from "../../shared/api.js";
import { backendStatus, courseIdFor } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { freshVisit } from "../../shared/remote-cache.js";
import { syllabi, syllabusKey, syllabusMessage, OUTCOME } from "../../shared/syllabus-remote.js";
import { personAvatar } from "../../shared/avatar-remote.js";

const LOGIN = "ASTRA 서버에 로그인하면 이 과목의 강의계획서를 서버에서 불러옵니다.";

function retry(reload) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    reload();
  });
  return button;
}

function meta(pairs) {
  return el("dl", { class: "crs-meta" }, ...pairs.flatMap(([label, value]) => [
    el("dt", { text: label }),
    el("dd", { text: value == null || value === "" ? "—" : String(value) }),
  ]));
}

function lines(text) {
  const p = el("p", { class: "crs-prose" });
  String(text).split("\n").forEach((line, index) => {
    if (index) p.append(el("br", {}));
    p.append(line);
  });
  return p;
}

function section(title, text) {
  return [el("h3", { text: title }), text ? lines(text) : el("p", { class: "tiny", text: "—" })];
}

function instructor(person) {
  const contact = [
    ["소속", person.department],
    ["이메일", person.email],
    ["연락처", person.phone],
    ["연구실", person.office],
    ["면담 시간", person.officeHours],
    ["홈페이지", person.homepage],
  ].filter(([, value]) => value);
  return el("div", { class: "crs-prof" },
    personAvatar({ userId: person.userId, name: person.name, className: "crs-avatar" }),
    el("div", {},
      el("strong", { text: person.name || "담당 교수" }),
      ...contact.map(([label, value]) => el("span", { class: "tiny", text: `${label} ${value}` })),
    ),
  );
}

function view(data) {
  const instructors = Array.isArray(data.instructors) ? data.instructors : [];
  const weeks = Array.isArray(data.weeks) ? data.weeks : [];
  return [
    ...instructors.map(instructor),
    el("h3", { text: "강의 계획" }),
    meta([
      ["년도/학기", data.termName],
      ["교과목명", data.courseName],
      ["교과목번호", data.courseCode],
      ["분반", data.section],
      ["이수구분", data.completionType],
      ["학점", data.credit],
      ["수업 시간·강의실", data.classTimeRoom],
      ["역량 연관도", data.outcomeRelevance ? `${data.outcomeRelevance} ${OUTCOME[data.outcomeRelevance] || ""}`.trim() : null],
    ]),
    ...section("과목 개요", data.overview),
    ...section("수업 목표", data.objectives),
    ...section("교재", data.textbooks),
    ...section("준비물", data.equipment),
    ...section("평가 방법", data.gradingPolicy),
    el("h3", { text: "주차별 계획" }),
    weeks.length
      ? table(["주차", "내용", "비고"], weeks.map((week) => [`${week.weekNo}주`, week.content ? lines(week.content) : "—", week.note || "—"]))
      : el("p", { class: "tiny", text: "등록된 주차별 계획이 없습니다." }),
  ];
}

/** 강의계획서 팝업(백엔드 모드). */
export function remoteSyllabusDialog(course) {
  const shell = (body) => dialogShell("강의계획서", body, { wide: true, className: "crs-modal" });
  const body = el("div", { class: "crs-dialog", "aria-live": "polite" });
  if (backendStatus() === "anon") {
    body.append(backendPanel({ text: LOGIN }));
    return shell(body);
  }
  const courseId = courseIdFor(course.id);
  if (!courseId) {
    body.append(emptyState("이 과목은 ASTRA 서버에 없습니다", "서버에 등록된 과목만 강의계획서를 볼 수 있습니다."));
    return shell(body);
  }
  const key = syllabusKey(courseId);
  const load = () => syllabi.load(key, courseId);
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      if (!entry || !entry.error) body.replaceChildren(loadingState("강의계획서를 불러오는 중입니다"));
      else if (entry.error instanceof ApiError && entry.error.status === 404) body.replaceChildren(emptyState("강의계획서를 볼 수 없습니다", syllabusMessage(entry.error)));
      else body.replaceChildren(errorState("강의계획서를 불러오지 못했습니다", syllabusMessage(entry.error), retry(load)));
      return;
    }
    body.replaceChildren(...view(data));
  };
  paint(syllabi.peek(key));
  queueMicrotask(() => {
    if (!body.isConnected) return;
    syllabi.watch(body, key, paint, null);
    if (freshVisit("course-syllabus", key) === "entry" || !syllabi.peek(key)) load();
  });
  return shell(body);
}
