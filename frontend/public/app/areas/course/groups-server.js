// 강의실 그룹 화면(42)의 서버 버전(`GET /api/courses/{courseId}/groups`). 학생은 자기가 속한 그룹과 그 구성원의 이름만 받는다.
// 학번과 사용자 ID는 담당자에게만 오므로, 응답에 있을 때만 그린다.
import { el } from "../../dom.js";
import { btn, emptyState, loadingState, errorState } from "../../ui/components.js";
import { ApiError } from "../../shared/api.js";
import { backendStatus, courseIdFor } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { freshVisit } from "../../shared/remote-cache.js";
import { groupLists, groupsKey, groupMessage, GROUP_TYPE } from "../../shared/groups-remote.js";

const LOGIN = "ASTRA 서버에 로그인하면 내가 속한 그룹을 서버에서 불러옵니다.";

function retry(reload) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    reload();
  });
  return button;
}

function groupCard(group) {
  const members = Array.isArray(group.members) ? group.members : [];
  return el("article", { class: "crs-team", "data-group": String(group.groupId) },
    el("h2", { text: group.name }),
    el("span", { class: "crs-kind", text: GROUP_TYPE[group.type] || group.type }),
    group.description ? el("p", { text: group.description }) : null,
    el("p", { class: "tiny", text: `구성원 ${members.length}명` }),
    members.length ? el("ul", { class: "crs-members" }, ...members.map((member) => el("li", {
      text: member.studentNo ? `${member.name || "이름 없음"} (${member.studentNo})` : member.name || "이름 없음",
    }))) : null,
  );
}

/** 강의실 그룹 화면(백엔드 모드). `head`는 course.js의 제목 줄 함수다. */
export function remoteGroups(course, head) {
  const title = el("span", {});
  const root = el("section", { class: "crs" }, head("그룹활동", "users", null));
  root.querySelector(".crs-head-title").append(title);
  if (backendStatus() === "anon") {
    root.append(backendPanel({ text: LOGIN }));
    return root;
  }
  const courseId = courseIdFor(course.id);
  if (!courseId) {
    root.append(emptyState("이 과목은 ASTRA 서버에 없습니다", "서버에 등록된 과목만 그룹을 볼 수 있습니다."));
    return root;
  }
  const key = groupsKey(courseId);
  const load = () => groupLists.load(key, courseId);
  const body = el("div", { class: "crs-remote", "aria-live": "polite" });
  root.append(body);
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      title.replaceChildren();
      if (!entry || !entry.error) body.replaceChildren(loadingState("그룹을 불러오는 중입니다"));
      else if (entry.error instanceof ApiError && entry.error.status === 404) body.replaceChildren(emptyState("그룹을 볼 수 없습니다", "이 과목의 구성원이 아니거나 과목을 찾을 수 없습니다."));
      else body.replaceChildren(errorState("그룹을 불러오지 못했습니다", groupMessage(entry.error), retry(load)));
      return;
    }
    const groups = Array.isArray(data) ? data : [];
    title.replaceChildren(el("span", { class: "badge badge-neutral", text: String(groups.length) }));
    body.replaceChildren(
      entry.error ? el("p", { class: "err", role: "alert", text: groupMessage(entry.error) }) : "",
      ...(groups.length ? groups.map(groupCard) : [emptyState("속한 그룹이 없습니다", "담당 교수가 그룹을 만들고 나를 넣으면 여기에 표시됩니다.")]),
    );
  };
  paint(groupLists.peek(key));
  queueMicrotask(() => {
    if (!root.isConnected) return;
    groupLists.watch(root, key, paint, load);
    if (freshVisit("course-groups", key) === "entry" || !groupLists.peek(key)) load();
  });
  return root;
}
