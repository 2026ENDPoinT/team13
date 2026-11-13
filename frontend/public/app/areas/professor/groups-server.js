// 교수 팀 프로젝트(pf_team)의 서버 버전. 그룹 만들기(`Idempotency-Key`), 이름·설명 바꾸기와 삭제(`If-Match`), 수강생 넣기·빼기,
// 다른 그룹으로 옮기기(서버의 한 transaction)를 한다. 넣을 수강생은 담당자용 수강생 명단(`courseStudents`, `GET …/students`)을
// 검색어와 쪽으로 찾는다. 명령은 `data-act="pf-team-cmd"`(professor/actions.js)의 `data-cmd`로 온다.
import { el } from "../../dom.js";
import { btn, field, badge, emptyState, loadingState, errorState } from "../../ui/components.js";
import { ApiError } from "../../shared/api.js";
import { backendStatus, courseIdFor, roleIn, subscribe } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { freshVisit } from "../../shared/remote-cache.js";
import { sessionDrafts, keepDraft, bindDraft } from "../../shared/session-drafts.js";
import {
  GROUP_TYPE, groupLists, groupsKey, courseStudents, studentsKey, createGroup, renameGroup, deleteGroup, addMember, removeMember, moveMember, groupMessage,
} from "../../shared/groups-remote.js";

// 과목별 수강생 찾기 상태 `{ q, page }`. 다시 그려도 남고, session이 바뀌면 지운다.
const finders = new Map();
subscribe(() => finders.clear());
const finderOf = (courseId) => finders.get(String(courseId)) || { q: "", page: 1 };

// 그룹의 이름·설명 폼에 입력 중인 값(`shared/session-drafts.js`의 초안). 409 재조회로 목록을 다시 그려도 남고, 저장에는
// 초안의 `version`을 보낸다. 세션이 바뀌면 버린다. 안내는 목록 밖의 상태 줄(`data-team-status`)에 있어 다시 그려도 남는다.
const groupDrafts = sessionDrafts(); // groupDraftKey -> { values, version, note, conflict, open }
const groupDraftKey = (courseId, groupId) => `${courseId}:${groupId}`;

const missing = (error) => error instanceof ApiError && error.status === 404;
const stack = (...nodes) => el("div", { class: "stack" }, ...nodes);
const note = (text) => el("p", { class: "pf-sub", text });
const LOGIN = "ASTRA 서버에 로그인하면 이 과목의 그룹을 서버에서 관리할 수 있습니다.";

function retry(reload) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    reload();
  });
  return button;
}

function gate(course) {
  if (backendStatus() === "anon") return stack(backendPanel({ text: LOGIN }));
  if (!courseIdFor(course.id)) return stack(backendPanel(), emptyState("이 과목은 ASTRA 서버에 없습니다", "서버에 등록된 과목만 그룹을 관리할 수 있습니다."));
  if (roleIn(course.id) !== "INSTRUCTOR") return stack(backendPanel(), note("이 과목을 담당하는 교수 계정으로 로그인해야 그룹을 관리할 수 있습니다."));
  return null;
}

const who = (member) => (member.studentNo ? `${member.name || "이름 없음"} (${member.studentNo})` : member.name || "이름 없음");
const candidateText = (student) => `${who(student)}${student.role === "AUDITOR" ? " · 청강" : ""}`;

function memberRow(courseId, group, member, groups) {
  const others = groups.filter((item) => item.groupId !== group.groupId);
  return el("li", { class: "pf-team-member", "data-member": String(member.userId) },
    el("span", { class: "pf-activity-copy", text: who(member) }),
    el("div", { class: "pf-inline" },
      others.length ? el("select", { "data-field": "toGroupId", "aria-label": `${who(member)} 옮길 그룹` },
        ...others.map((item) => el("option", { value: String(item.groupId), text: item.name }))) : null,
      others.length ? btn("옮기기", { "data-act": "pf-team-cmd", "data-cmd": "move", "data-course": String(courseId), "data-group": String(group.groupId), "data-student": String(member.userId) }, "ghost") : null,
      btn("빼기", { "data-act": "pf-team-cmd", "data-cmd": "remove", "data-course": String(courseId), "data-group": String(group.groupId), "data-student": String(member.userId), "aria-label": `${who(member)} 빼기` }, "ghost"),
    ),
  );
}

function groupCard(courseId, group, groups, students) {
  const members = Array.isArray(group.members) ? group.members : [];
  const inGroup = new Set(members.map((member) => member.userId));
  const candidates = (students || []).filter((student) => !inGroup.has(student.userId));
  const name = el("input", { "data-field": "name", maxlength: "100", value: group.name });
  const description = el("textarea", { "data-field": "description", rows: "2", maxlength: "1000", text: group.description || "" });
  const draftKey = groupDraftKey(courseId, group.groupId);
  const form = el("div", { class: "pf-form", "data-group-form": String(group.groupId), "data-draft": draftKey },
    field("그룹 이름", name),
    el("div", { class: "span-2" }, field("설명 (일반 텍스트)", description)),
    el("div", { class: "pf-actions span-2" },
      btn("저장", { "data-act": "pf-team-cmd", "data-cmd": "rename", "data-course": String(courseId), "data-group": String(group.groupId), "data-version": String(group.version) }, "primary"),
      btn("그룹 삭제", { "data-act": "pf-team-cmd", "data-cmd": "delete", "data-course": String(courseId), "data-group": String(group.groupId), "data-version": String(group.version) }, "danger"),
    ),
  );
  // 입력 중이던 값은 초안에서 되살리고, 409 뒤에는 서버의 최신 이름·설명을 옆에 보인다. 고치던 칸은 펼친 채로 둔다.
  const draft = bindDraft(groupDrafts, draftKey, form, group.version);
  if (draft && draft.conflict) {
    form.append(el("p", { class: "pf-sub span-2", "data-draft-latest": "", text: `서버의 최신 내용 · 버전 ${group.version}: ${group.name}${group.description ? ` · ${group.description}` : " · 설명 없음"}` }));
  }
  const edit = el("details", { open: draft && draft.open !== false ? true : null }, el("summary", { text: "이름·설명 바꾸기, 삭제" }), form);
  edit.addEventListener("toggle", () => {
    const current = groupDrafts.get(draftKey);
    if (current) current.open = edit.open;
  });
  return el("article", { class: "sheet pf-team", "data-group": String(group.groupId) },
    el("div", { class: "pf-week-head" },
      el("h2", { text: group.name }),
      el("div", { class: "pf-inline" }, badge(GROUP_TYPE[group.type] || group.type, "neutral"), badge(`${members.length}명`, "neutral")),
    ),
    group.description ? note(group.description) : null,
    members.length ? el("ul", { class: "pf-team-members" }, ...members.map((member) => memberRow(courseId, group, member, groups))) : note("아직 구성원이 없습니다."),
    students == null ? note("수강생 명단을 불러오는 중입니다.")
      : candidates.length ? el("div", { class: "pf-inline", "data-add-member": "" },
        el("select", { "data-field": "studentId", "aria-label": `${group.name}에 넣을 수강생` }, ...candidates.map((student) => el("option", { value: String(student.userId), text: candidateText(student) }))),
        btn("넣기", { "data-act": "pf-team-cmd", "data-cmd": "add", "data-course": String(courseId), "data-group": String(group.groupId) }, "secondary"),
      ) : note("찾은 수강생 가운데 이 그룹에 넣을 사람이 없습니다. 위에서 다른 수강생을 찾아 보세요."),
    edit,
  );
}

function createForm(courseId) {
  return el("section", { class: "sheet", "aria-label": "그룹 만들기" },
    el("h2", { text: "그룹 만들기" }),
    el("div", { class: "pf-form", "data-group-form": "new" },
      field("그룹 이름", el("input", { "data-field": "name", maxlength: "100" })),
      field("종류", el("select", { "data-field": "type" }, ...Object.entries(GROUP_TYPE).map(([value, label]) => el("option", { value, text: label })))),
      el("div", { class: "span-2" }, field("설명 (일반 텍스트)", el("textarea", { "data-field": "description", rows: "2", maxlength: "1000" }))),
      el("div", { class: "pf-actions span-2" }, btn("그룹 만들기", { "data-act": "pf-team-cmd", "data-cmd": "create", "data-course": String(courseId) }, "primary")),
    ),
  );
}

/** 넣을 수강생 찾기 줄. 검색어와 쪽은 `finders`에 두고, 명단이 오면 `update(entry)`로 안내와 쪽 단추만 바꾼다. */
function studentFinder(courseId, { q, page }) {
  const input = el("input", { type: "search", "data-field": "studentQuery", value: q, maxlength: "50", placeholder: "이름 또는 학번" });
  const find = btn("찾기", { "data-act": "pf-team-cmd", "data-cmd": "find", "data-course": String(courseId) }, "secondary");
  input.addEventListener("keydown", (event) => {
    if (event.key !== "Enter" || event.isComposing) return;
    event.preventDefault();
    find.click();
  });
  const line = el("p", { class: "pf-sub", role: "status", "data-team-found": "" });
  const step = (dir, label) => btn(label, { "data-act": "pf-team-cmd", "data-cmd": "page", "data-dir": dir, "data-course": String(courseId), disabled: true }, "ghost");
  const prev = step("prev", "이전 쪽");
  const next = step("next", "다음 쪽");
  const node = el("section", { class: "sheet srv-team-finder", "aria-label": "넣을 수강생 찾기" },
    el("h2", { text: "넣을 수강생 찾기" }),
    el("div", { class: "pf-inline srv-team-finder__row" }, field("검색어", input), find),
    line,
    el("div", { class: "pf-inline" }, prev, next),
  );
  const update = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      line.textContent = entry && entry.error
        ? (missing(entry.error) ? "이 과목의 수강생 명단을 볼 수 없습니다." : `수강생 명단을 불러오지 못했습니다. ${groupMessage(entry.error)}`)
        : "수강생 명단을 불러오는 중입니다.";
      line.setAttribute("role", entry && entry.error ? "alert" : "status");
      prev.disabled = true;
      next.disabled = true;
      return;
    }
    const pages = Math.max(1, Math.ceil(data.total / data.size));
    line.setAttribute("role", "status");
    line.textContent = data.total
      ? `${q ? `"${q}" ` : ""}수강생 ${data.total}명 · ${page}/${pages}쪽. 각 그룹의 목록에서 고릅니다.`
      : q ? `"${q}"에 맞는 수강생이 없습니다.` : "이 과목에 수강생이 없습니다.";
    prev.disabled = page <= 1;
    next.disabled = page >= pages;
  };
  return { node, update };
}

/** `pf_team`(백엔드 모드) 본문. */
export function serverTeams(ctx, course) {
  const blocked = gate(course);
  if (blocked) return blocked;
  const courseId = courseIdFor(course.id);
  const key = groupsKey(courseId);
  const load = () => groupLists.load(key, courseId);
  const search = finderOf(courseId);
  const skey = studentsKey(courseId, search.q, search.page);
  const loadStudents = () => courseStudents.load(skey, courseId, search.q, search.page);
  const finder = studentFinder(courseId, search);
  const body = el("div", { class: "stack", "aria-live": "polite" });
  const status = el("p", { class: "pf-sub", role: "status", "data-team-status": "" });
  let drawn = "";
  const paint = () => {
    const people = courseStudents.peek(skey);
    finder.update(people);
    const entry = groupLists.peek(key);
    const data = entry && entry.data;
    if (!data) {
      drawn = "";
      if (!entry || !entry.error) body.replaceChildren(loadingState("그룹을 불러오는 중입니다"));
      else if (missing(entry.error)) body.replaceChildren(emptyState("그룹을 볼 수 없습니다", "이 과목의 담당 교수가 아니거나 과목을 찾을 수 없습니다."));
      else body.replaceChildren(errorState("그룹을 불러오지 못했습니다", groupMessage(entry.error), retry(load)));
      return;
    }
    // 명단을 못 읽었으면 빈 후보로 그리고, 이유는 찾기 줄에 둔다.
    const students = people && people.data ? people.data.items : people && people.error ? [] : null;
    const sig = JSON.stringify([data, students, entry.error && entry.error.message]);
    if (sig === drawn) return;
    drawn = sig;
    const groups = Array.isArray(data) ? data : [];
    body.replaceChildren(
      entry.error ? el("p", { class: "err", role: "alert", text: groupMessage(entry.error) }) : "",
      ...(groups.length ? groups.map((group) => groupCard(courseId, group, groups, students)) : [emptyState("그룹이 없습니다", "아래에서 그룹을 만들면 학생 그룹 화면에 나옵니다.")]),
    );
  };
  paint();
  const root = stack(backendPanel(), status, finder.node, body, createForm(courseId));
  queueMicrotask(() => {
    if (!root.isConnected) return;
    groupLists.watch(root, key, paint, load);
    courseStudents.watch(root, skey, paint, null);
    if (freshVisit("pf-team", key) === "entry" || !groupLists.peek(key)) load();
    if (!courseStudents.peek(skey)) loadStudents();
  });
  return root;
}

// ---------- 명령 ----------

function say(text, alert) {
  const line = document.querySelector("[data-team-status]");
  if (!line) return;
  line.className = alert ? "err" : "pf-sub";
  line.setAttribute("role", alert ? "alert" : "status");
  line.textContent = text;
}

async function withBusy(node, label, work) {
  const text = node.textContent;
  node.disabled = true;
  node.setAttribute("aria-busy", "true");
  node.textContent = label;
  try {
    return await work();
  } finally {
    if (node.isConnected) {
      node.disabled = false;
      node.removeAttribute("aria-busy");
      node.textContent = text;
    }
  }
}

/** `pf-team-cmd`: find·page(넣을 수강생 찾기), create, rename, delete(두 번 눌러 확인), add, remove, move. */
export async function teamCommand(node, api) {
  if (node.disabled) return undefined;
  const courseId = Number(node.dataset.course);
  const groupId = Number(node.dataset.group) || null;
  const studentId = Number(node.dataset.student) || null;
  const cmd = node.dataset.cmd;
  const done = (text) => {
    api.getState().toast = text;
    api.paintToast();
    say(text, false);
  };
  const failed = (error) => {
    say(groupMessage(error), true);
    // 버전 충돌이나 없어진 그룹·학생이면 지금 목록을 다시 받는다.
    if (error instanceof ApiError && (error.status === 409 || error.status === 404 || error.status === 428)) groupLists.load(groupsKey(courseId), courseId);
  };
  if (cmd === "find" || cmd === "page") {
    const now = finderOf(courseId);
    const box = document.querySelector('[data-field="studentQuery"]');
    const next = cmd === "find"
      ? { q: String((box && box.value) || "").trim().slice(0, 50), page: 1 }
      : { q: now.q, page: Math.max(1, now.page + (node.dataset.dir === "prev" ? -1 : 1)) };
    finders.set(String(courseId), next);
    api.render();
    // 다시 그린 화면에서 같은 자리로 초점을 돌린다. 쪽 단추는 새 쪽을 받는 동안 꺼져 있어 안내 줄로 보낸다.
    const again = cmd === "find" ? document.querySelector('[data-field="studentQuery"]') : document.querySelector("[data-team-found]");
    if (again) {
      if (cmd !== "find") again.setAttribute("tabindex", "-1");
      again.focus();
    }
    return undefined;
  }
  if (cmd === "create" || cmd === "rename") {
    const form = node.closest("[data-group-form]");
    const name = form.querySelector('[data-field="name"]').value.trim();
    const description = form.querySelector('[data-field="description"]').value.trim();
    // 바꾸기는 누를 때의 값을 초안에 담아 409 재조회로 목록을 다시 그려도 남긴다. 저장에는 초안의 기준 버전을 보낸다.
    const draftKey = cmd === "rename" ? form.dataset.draft : null;
    const draft = draftKey ? keepDraft(groupDrafts, draftKey, form, Number(node.dataset.version)) : null;
    if (!name) return say("그룹 이름을 입력하세요.", true);
    return withBusy(node, "저장하는 중…", async () => {
      try {
        if (cmd === "create") {
          await createGroup(courseId, { name, type: form.querySelector('[data-field="type"]').value, description });
          done("그룹을 만들었습니다");
        } else {
          await renameGroup(courseId, groupId, { name, description, version: draft.version });
          if (groupDrafts.get(draftKey) === draft) groupDrafts.delete(draftKey);
          // 응답으로 목록을 고칠 때는 초안이 아직 있었으므로, 남은 서버 최신 내용 줄을 걷는다.
          const latest = document.querySelector(`[data-draft="${draftKey}"] [data-draft-latest]`);
          if (latest) latest.remove();
          done("그룹을 바꿨습니다");
        }
      } catch (error) {
        const stale = draft && error instanceof ApiError && ((error.status === 409 && error.code === "version_conflict") || error.status === 428);
        if (stale) draft.conflict = true;
        // 지워진 그룹의 바꾸기는 다시 보낼 곳이 없어 초안을 버린다.
        if (draft && missing(error)) groupDrafts.delete(draftKey);
        failed(error);
        if (!stale) return;
        // 입력은 두고 버전만 새로 받는다. 다시 읽지 못하면 기준 버전을 그대로 두어 다음 저장도 충돌로 알린다.
        await groupLists.peek(groupsKey(courseId))?.loading;
        const entry = groupLists.peek(groupsKey(courseId));
        const fresh = entry && !entry.error && Array.isArray(entry.data) ? entry.data.find((item) => item.groupId === groupId) : null;
        if (fresh && groupDrafts.get(draftKey) === draft) draft.version = fresh.version;
      }
    });
  }
  if (cmd === "delete") {
    if (node.dataset.armed !== "1") {
      node.dataset.armed = "1";
      node.textContent = "한 번 더 누르면 삭제";
      return undefined;
    }
    node.dataset.armed = "";
    return withBusy(node, "삭제하는 중…", async () => {
      try {
        await deleteGroup(courseId, groupId, Number(node.dataset.version));
        groupDrafts.delete(groupDraftKey(courseId, groupId));
        done("그룹을 삭제했습니다");
      } catch (error) {
        failed(error);
      }
    }).finally(() => {
      if (node.isConnected) node.textContent = "그룹 삭제";
    });
  }
  if (cmd === "add") {
    const select = node.closest("[data-add-member]").querySelector('[data-field="studentId"]');
    const target = Number(select.value);
    if (!target) return say("넣을 수강생을 고르세요.", true);
    return withBusy(node, "넣는 중…", async () => {
      try {
        await addMember(courseId, groupId, target);
        done("구성원을 넣었습니다");
      } catch (error) {
        failed(error);
      }
    });
  }
  if (cmd === "remove") {
    return withBusy(node, "빼는 중…", async () => {
      try {
        await removeMember(courseId, groupId, studentId);
        done("구성원을 뺐습니다");
      } catch (error) {
        failed(error);
      }
    });
  }
  if (cmd === "move") {
    const select = node.closest("[data-member]").querySelector('[data-field="toGroupId"]');
    return withBusy(node, "옮기는 중…", async () => {
      try {
        const moved = await moveMember(courseId, groupId, studentId, Number(select.value));
        done(`${moved.name}(으)로 옮겼습니다`);
      } catch (error) {
        failed(error);
      }
    });
  }
  return undefined;
}
