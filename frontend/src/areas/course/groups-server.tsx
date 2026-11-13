// 강의실 그룹 화면(42)의 서버 버전(`GET /api/courses/{courseId}/groups`). 학생은 자기가 속한 그룹과 그 구성원의 이름만 받는다.
// 학번과 사용자 ID는 담당자에게만 오므로, 응답에 있을 때만 그린다.
import { useEffect, useState, type ReactNode } from "react";
import type { Course } from "../../data.ts";
import { Badge, Button, EmptyState, ErrorState, LoadingState } from "../../ui/kit.tsx";
import { ApiError } from "../../shared/api.ts";
import { backendStatus, courseIdFor } from "../../shared/backend.ts";
import { backendPanel } from "../../shared/backend-prompt.tsx";
import { freshVisit, useResource } from "../../shared/remote-cache.ts";
import { groupLists, groupsKey, groupMessage, GROUP_TYPE, type Group } from "../../shared/groups-remote.ts";
import type { HeadFn } from "./course.tsx";

const LOGIN = "ASTRA 서버에 로그인하면 내가 속한 그룹을 서버에서 불러옵니다.";

export function RetryButton({ reload }: { reload: () => void }) {
  const [pressed, setPressed] = useState(false);
  return <Button kind="ghost" disabled={pressed} onClick={() => { setPressed(true); reload(); }}>다시 불러오기</Button>;
}

function groupCard(group: Group): ReactNode {
  const members = Array.isArray(group.members) ? group.members : [];
  return (
    <article key={group.groupId} className="crs-team" data-group={String(group.groupId)}>
      <h2>{group.name}</h2>
      <span className="crs-kind">{GROUP_TYPE[group.type] || group.type}</span>
      {group.description ? <p>{group.description}</p> : null}
      <p className="tiny">{`구성원 ${members.length}명`}</p>
      {members.length ? (
        <ul className="crs-members">
          {members.map((member, index) => (
            <li key={member.userId ?? index}>{member.studentNo ? `${member.name || "이름 없음"} (${member.studentNo})` : member.name || "이름 없음"}</li>
          ))}
        </ul>
      ) : null}
    </article>
  );
}

/** 강의실 그룹 화면(백엔드 모드). `head`는 course.tsx의 제목 줄 함수다. */
export function remoteGroups(course: Course, head: HeadFn): ReactNode {
  if (backendStatus() === "anon") {
    return <section className="crs" data-area="course">{head("그룹활동", "users", null)}{backendPanel({ text: LOGIN })}</section>;
  }
  const courseId = courseIdFor(course.id);
  if (!courseId) {
    return (
      <section className="crs" data-area="course">
        {head("그룹활동", "users", null)}
        <EmptyState title="이 과목은 ASTRA 서버에 없습니다" body="서버에 등록된 과목만 그룹을 볼 수 있습니다." />
      </section>
    );
  }
  return <RemoteGroups courseId={courseId} head={head} />;
}

function RemoteGroups({ courseId, head }: { courseId: number | string; head: HeadFn }) {
  const key = groupsKey(courseId);
  const load = () => void groupLists.load(key, courseId);
  const entry = useResource(groupLists, key, load);
  useEffect(() => {
    if (freshVisit("course-groups", key) === "entry" || !groupLists.peek(key)) void groupLists.load(key, courseId);
  });
  const data = entry && entry.data;
  let body: ReactNode;
  if (!data) {
    if (!entry || !entry.error) body = <LoadingState title="그룹을 불러오는 중입니다" />;
    else if (entry.error instanceof ApiError && entry.error.status === 404) body = <EmptyState title="그룹을 볼 수 없습니다" body="이 과목의 구성원이 아니거나 과목을 찾을 수 없습니다." />;
    else body = <ErrorState title="그룹을 불러오지 못했습니다" body={groupMessage(entry.error)} actions={<RetryButton reload={load} />} />;
  } else {
    const groups = Array.isArray(data) ? data : [];
    body = (
      <>
        {entry.error ? <p className="err" role="alert">{groupMessage(entry.error)}</p> : null}
        {groups.length ? groups.map(groupCard) : <EmptyState title="속한 그룹이 없습니다" body="담당 교수가 그룹을 만들고 나를 넣으면 여기에 표시됩니다." />}
      </>
    );
  }
  return (
    <section className="crs" data-area="course">
      {head("그룹활동", "users", data ? <Badge tone="neutral">{String(Array.isArray(data) ? data.length : 0)}</Badge> : null)}
      <div className="crs-remote" aria-live="polite">{body}</div>
    </section>
  );
}
