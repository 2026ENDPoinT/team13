// 강의계획서 팝업(40b)의 서버 버전(`GET /api/courses/{courseId}/syllabus`). 서술 항목과 주별 계획은 일반 텍스트이고, 줄바꿈만
// 살려 텍스트로 그린다. 비어 있는 항목은 "—"로 둔다. 담당자 연락처는 응답에 있을 때만 그린다. 담당자 사진은 구성원에게 오는
// `userId`로 `GET /api/users/{userId}/avatar`를 읽고, 없거나 볼 수 없으면 이름 첫 글자다. 학교 과목의 학생은 학교 e-Class의 계획서를
// 받는다(`origin: "school"`, 담당자에 `userId` 없음: 사진을 찾지 않고 이름 첫 글자). 학교 연결이 끝났으면(409) 다시 로그인을,
// 학교가 답하지 않으면(502) 다시 시도를, 학교에 계획서가 없는 과목(404 `syllabus_not_provided`)은 그 문장을 보인다.
import { useEffect, type ReactNode } from "react";
import type { Course } from "../../data.ts";
import { Dialog, EmptyState, ErrorState, LoadingState, Table } from "../../ui/kit.tsx";
import { ApiError } from "../../shared/api.ts";
import { backendStatus, courseIdFor } from "../../shared/backend.ts";
import { freshVisit, useResource } from "../../shared/remote-cache.ts";
import { syllabi, syllabusKey, syllabusMessage, OUTCOME, type Syllabus, type SyllabusInstructor, type OutcomeLevel } from "../../shared/syllabus-remote.ts";
import { personAvatar } from "../../shared/avatar-remote.tsx";
import { RetryButton } from "./groups-server.tsx";
import { schoolProblemOf, SchoolProblemNote } from "../../shared/school-content.tsx";


function meta(pairs: [string, unknown][]): ReactNode {
  return (
    <dl className="crs-meta">
      {pairs.flatMap(([label, value]) => [
        <dt key={`${label}-t`}>{label}</dt>,
        <dd key={`${label}-d`}>{value == null || value === "" ? "—" : String(value)}</dd>,
      ])}
    </dl>
  );
}

/** Text with its line breaks kept as `<br>`. */
function lines(text: string): ReactNode {
  return <p className="crs-prose">{String(text).split("\n").flatMap((line, index) => (index ? [<br key={index} />, line] : [line]))}</p>;
}

function section(title: string, text: string | null | undefined): ReactNode {
  return (
    <>
      <h3>{title}</h3>
      {text ? lines(text) : <p className="tiny">—</p>}
    </>
  );
}

function instructor(person: SyllabusInstructor, index: number): ReactNode {
  const contact = ([
    ["소속", person.department],
    ["이메일", person.email],
    ["연락처", person.phone],
    ["연구실", person.office],
    ["면담 시간", person.officeHours],
    ["홈페이지", person.homepage],
  ] as const).filter(([, value]) => value);
  return (
    <div key={person.userId ?? `name-${index}-${person.name || ""}`} className="crs-prof">
      {person.userId != null
        ? personAvatar({ userId: person.userId, name: person.name, className: "crs-avatar" })
        : personAvatar({ name: person.name, className: "crs-avatar", photo: false })}
      <div>
        <strong>{person.name || "담당 교수"}</strong>
        {contact.map(([label, value]) => <span key={label} className="tiny">{`${label} ${value}`}</span>)}
      </div>
    </div>
  );
}

function view(data: Syllabus): ReactNode {
  const instructors = Array.isArray(data.instructors) ? data.instructors : [];
  const weeks = Array.isArray(data.weeks) ? data.weeks : [];
  const level = data.outcomeRelevance ? String(data.outcomeRelevance) : "";
  return (
    <>
      {data.origin === "school" ? <p className="tiny" data-syllabus-origin="school">학교 e-Class의 강의계획서입니다.</p> : null}
      {instructors.map(instructor)}
      <h3>강의 계획</h3>
      {meta([
        ["년도/학기", data.termName],
        ["교과목명", data.courseName],
        ["교과목번호", data.courseCode],
        ["분반", data.section],
        ["이수구분", data.completionType],
        ["학점", data.credit],
        ["수업 시간·강의실", data.classTimeRoom],
        ["역량 연관도", level ? `${level} ${OUTCOME[level as OutcomeLevel] || ""}`.trim() : null],
      ])}
      {section("과목 개요", data.overview)}
      {section("수업 목표", data.objectives)}
      {section("교재", data.textbooks)}
      {section("준비물", data.equipment)}
      {section("평가 방법", data.gradingPolicy)}
      <h3>주차별 계획</h3>
      {weeks.length
        ? <Table headers={["주차", "내용", "비고"]} rows={weeks.map((week) => [`${week.weekNo}주`, week.content ? lines(week.content) : "—", week.note || "—"])} />
        : <p className="tiny">등록된 주차별 계획이 없습니다.</p>}
    </>
  );
}

function shell(body: ReactNode): ReactNode {
  return <Dialog title="강의계획서" wide className="crs-modal">{body}</Dialog>;
}

/** 강의계획서 팝업(백엔드 모드). */
export function remoteSyllabusDialog(course: Course): ReactNode {
  if (backendStatus() === "anon") return shell(<div className="crs-dialog" aria-live="polite"></div>);
  const courseId = courseIdFor(course.id);
  if (!courseId) {
    return shell(<div className="crs-dialog" aria-live="polite"><EmptyState title="이 과목은 ASTRA 서버에 없습니다" body="서버에 등록된 과목만 강의계획서를 볼 수 있습니다." /></div>);
  }
  return shell(<SyllabusBody courseId={courseId} />);
}

function SyllabusBody({ courseId }: { courseId: number | string }) {
  const key = syllabusKey(courseId);
  const load = () => void syllabi.load(key, courseId);
  const entry = useResource(syllabi, key);
  useEffect(() => {
    if (freshVisit("course-syllabus", key) === "entry" || !syllabi.peek(key)) void syllabi.load(key, courseId);
  });
  const data = entry && entry.data;
  let body: ReactNode;
  const problem = entry && entry.error && schoolProblemOf(entry.error) ? <SchoolProblemNote error={entry.error} retry={load} /> : null;
  if (data) body = <>{problem}{view(data)}</>;
  else if (!entry || !entry.error) body = <LoadingState title="강의계획서를 불러오는 중입니다" />;
  else if (problem) body = problem;
  else if (entry.error instanceof ApiError && entry.error.status === 404) body = <EmptyState title="강의계획서를 볼 수 없습니다" body={syllabusMessage(entry.error)} />;
  else body = <ErrorState title="강의계획서를 불러오지 못했습니다" body={syllabusMessage(entry.error)} actions={<RetryButton reload={load} />} />;
  return <div className="crs-dialog" aria-live="polite">{body}</div>;
}
