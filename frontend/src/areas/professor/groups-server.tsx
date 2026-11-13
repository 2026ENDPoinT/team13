// 교수 팀 프로젝트(pf_team)의 서버 버전. 그룹 만들기(`Idempotency-Key`), 이름·설명 바꾸기와 삭제(`If-Match`), 수강생 넣기·빼기,
// 다른 그룹으로 옮기기(서버의 한 transaction)를 한다. 넣을 수강생은 담당자용 수강생 명단(`courseStudents`, `GET …/students`)을
// 검색어와 쪽으로 찾는다.
import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { Button, Badge, EmptyState, LoadingState, ErrorState, type ButtonKind } from "../../ui/kit.tsx";
import { toast } from "../../store.ts";
import type { Course } from "../../data.ts";
import type { ScreenCtx } from "../../types.ts";
import { ApiError } from "../../shared/api.ts";
import { backendStatus, courseIdFor, roleIn, subscribe } from "../../shared/backend.ts";
import { SIGNED_OUT } from "../../shared/backend-prompt.tsx";
import { freshVisit, useResource } from "../../shared/remote-cache.ts";
import {
  GROUP_TYPE, groupLists, groupsKey, courseStudents, studentsKey, createGroup, renameGroup, deleteGroup, addMember, removeMember, moveMember, groupMessage,
  type Group, type GroupMember, type CourseStudent,
} from "../../shared/groups-remote.ts";

interface Finder {
  q: string;
  page: number;
}

// 과목별 수강생 찾기 상태 `{ q, page }`. 다시 그려도 남고, session이 바뀌면 지운다.
const finders = new Map<string, Finder>();
subscribe(() => finders.clear());
const finderOf = (courseId: number | string): Finder => finders.get(String(courseId)) || { q: "", page: 1 };

interface GroupDraft {
  name: string;
  description: string;
  /** 저장에 보낼 기준 버전. */
  version: number;
  conflict: boolean;
  open: boolean;
}

// 그룹의 이름·설명 폼에 입력 중인 값. 409 재조회로 목록을 다시 그려도 남고, 저장에는 초안의 `version`을 보낸다.
// 세션이 바뀌면 버린다. 안내는 목록 밖의 상태 줄(`data-team-status`)에 있어 다시 그려도 남는다.
const groupDrafts = new Map<string, GroupDraft>();
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  groupDrafts.clear();
});
const groupDraftKey = (courseId: number | string, groupId: number) => `${courseId}:${groupId}`;

// 목록 밖의 상태 줄. 명령이 쓰고 다시 그려도 남는다.
let status: { text: string; alert: boolean } = { text: "", alert: false };
const statusListeners = new Set<() => void>();

function say(text: string, alert = false) {
  status = { text, alert };
  for (const listener of statusListeners) listener();
}

const missing = (error: unknown) => error instanceof ApiError && error.status === 404;

function Retry({ reload }: { reload: () => void }) {
  const [pressed, setPressed] = useState(false);
  return <Button kind="ghost" disabled={pressed} onClick={() => { setPressed(true); reload(); }}>다시 불러오기</Button>;
}

const who = (member: GroupMember) => (member.studentNo ? `${member.name || "이름 없음"} (${member.studentNo})` : member.name || "이름 없음");
const candidateText = (student: CourseStudent) => `${who(student)}${student.role === "AUDITOR" ? " · 청강" : ""}`;

function done(text: string) {
  say(text, false);
  toast(text);
}

function failed(courseId: number, error: unknown) {
  say(groupMessage(error), true);
  // 버전 충돌이나 없어진 그룹·학생이면 지금 목록을 다시 받는다.
  if (error instanceof ApiError && (error.status === 409 || error.status === 404 || error.status === 428)) void groupLists.load(groupsKey(courseId), courseId);
}

/** 누르는 동안 꺼지고 `busyLabel`을 보이는 명령 단추. */
function CmdButton({ label, busyLabel, kind, run, ...rest }: {
  label: ReactNode;
  busyLabel: string;
  kind: ButtonKind;
  run: () => Promise<void>;
  "aria-label"?: string;
}) {
  const [busy, setBusy] = useState(false);
  const live = useRef(true);
  // `busy` disables the button only after React repaints; this blocks a second press in the same moment.
  const running = useRef(false);
  useEffect(() => { live.current = true; return () => { live.current = false; }; }, []);
  return (
    <Button
      kind={kind}
      disabled={busy}
      aria-busy={busy ? "true" : undefined}
      {...rest}
      onClick={() => {
        if (running.current) return;
        running.current = true;
        setBusy(true);
        void run().finally(() => {
          running.current = false;
          if (live.current) setBusy(false);
        });
      }}
    >
      {busy ? busyLabel : label}
    </Button>
  );
}

function MemberRow({ courseId, group, member, groups }: { courseId: number; group: Group; member: GroupMember; groups: Group[] }) {
  const others = groups.filter((item) => item.groupId !== group.groupId);
  const target = useRef<HTMLSelectElement>(null);
  const studentId = Number(member.userId) || 0;
  return (
    <li className="pf-team-member" data-member={String(member.userId)}>
      <span className="pf-activity-copy">{who(member)}</span>
      <div className="pf-inline">
        {others.length ? (
          <select ref={target} data-field="toGroupId" aria-label={`${who(member)} 옮길 그룹`}>
            {others.map((item) => <option key={item.groupId} value={String(item.groupId)}>{item.name}</option>)}
          </select>
        ) : null}
        {others.length ? (
          <CmdButton kind="ghost" label="옮기기" busyLabel="옮기는 중…" run={async () => {
            try {
              const moved = await moveMember(courseId, group.groupId, studentId, Number(target.current ? target.current.value : 0));
              done(`${moved.name}(으)로 옮겼습니다`);
            } catch (error) {
              failed(courseId, error);
            }
          }} />
        ) : null}
        <CmdButton kind="ghost" label="빼기" busyLabel="빼는 중…" aria-label={`${who(member)} 빼기`} run={async () => {
          try {
            await removeMember(courseId, group.groupId, studentId);
            done("구성원을 뺐습니다");
          } catch (error) {
            failed(courseId, error);
          }
        }} />
      </div>
    </li>
  );
}

function GroupCard({ courseId, group, groups, students }: { courseId: number; group: Group; groups: Group[]; students: CourseStudent[] | null }) {
  const [, setTick] = useState(0);
  const repaint = () => setTick((n) => n + 1);
  const members = Array.isArray(group.members) ? group.members : [];
  const inGroup = new Set(members.map((member) => member.userId));
  const candidates = (students || []).filter((student) => !inGroup.has(student.userId));
  const draftKey = groupDraftKey(courseId, group.groupId);
  const draft = groupDrafts.get(draftKey) || null;
  const pick = useRef<HTMLSelectElement>(null);
  const typed = (patch: Partial<GroupDraft>) => {
    const current: GroupDraft = groupDrafts.get(draftKey)
      || { name: group.name, description: group.description || "", version: group.version, conflict: false, open: true };
    groupDrafts.set(draftKey, { ...current, ...patch });
    repaint();
  };

  async function rename() {
    const current: GroupDraft = groupDrafts.get(draftKey)
      || { name: group.name, description: group.description || "", version: group.version, conflict: false, open: true };
    // 바꾸기는 누를 때의 값을 초안에 담아 409 재조회로 목록을 다시 그려도 남긴다. 저장에는 초안의 기준 버전을 보낸다.
    groupDrafts.set(draftKey, current);
    const name = current.name.trim();
    const description = current.description.trim();
    if (!name) {
      say("그룹 이름을 입력하세요.", true);
      return;
    }
    try {
      await renameGroup(courseId, group.groupId, { name, description, version: current.version });
      if (groupDrafts.get(draftKey) === current) groupDrafts.delete(draftKey);
      done("그룹을 바꿨습니다");
    } catch (error) {
      const stale = error instanceof ApiError && ((error.status === 409 && error.code === "version_conflict") || error.status === 428);
      if (stale) current.conflict = true;
      // 지워진 그룹의 바꾸기는 다시 보낼 곳이 없어 초안을 버린다.
      if (missing(error)) groupDrafts.delete(draftKey);
      failed(courseId, error);
      if (!stale) return;
      // 입력은 두고 버전만 새로 받는다. 다시 읽지 못하면 기준 버전을 그대로 두어 다음 저장도 충돌로 알린다.
      await groupLists.peek(groupsKey(courseId))?.loading;
      const entry = groupLists.peek(groupsKey(courseId));
      const fresh = entry && !entry.error && Array.isArray(entry.data) ? entry.data.find((item) => item.groupId === group.groupId) : null;
      if (fresh && groupDrafts.get(draftKey) === current) current.version = fresh.version;
    }
  }

  return (
    <article className="sheet pf-team" data-group={String(group.groupId)}>
      <div className="pf-week-head">
        <h2>{group.name}</h2>
        <div className="pf-inline"><Badge tone="neutral">{GROUP_TYPE[group.type] || group.type}</Badge><Badge tone="neutral">{`${members.length}명`}</Badge></div>
      </div>
      {group.description ? <p className="pf-sub">{group.description}</p> : null}
      {members.length
        ? <ul className="pf-team-members">{members.map((member) => <MemberRow key={String(member.userId)} courseId={courseId} group={group} member={member} groups={groups} />)}</ul>
        : <p className="pf-sub">아직 구성원이 없습니다.</p>}
      {students == null ? <p className="pf-sub">수강생 명단을 불러오는 중입니다.</p>
        : candidates.length ? (
          <div className="pf-inline" data-add-member="">
            <select ref={pick} data-field="studentId" aria-label={`${group.name}에 넣을 수강생`}>
              {candidates.map((student) => <option key={student.userId} value={String(student.userId)}>{candidateText(student)}</option>)}
            </select>
            <CmdButton kind="secondary" label="넣기" busyLabel="넣는 중…" run={async () => {
              const target = Number(pick.current ? pick.current.value : 0);
              if (!target) {
                say("넣을 수강생을 고르세요.", true);
                return;
              }
              try {
                await addMember(courseId, group.groupId, target);
                done("구성원을 넣었습니다");
              } catch (error) {
                failed(courseId, error);
              }
            }} />
          </div>
        ) : <p className="pf-sub">찾은 수강생 가운데 이 그룹에 넣을 사람이 없습니다. 위에서 다른 수강생을 찾아 보세요.</p>}
      {/* 입력 중이던 값은 초안에서 되살리고, 409 뒤에는 서버의 최신 이름·설명을 옆에 보인다. 고치던 칸은 펼친 채로 둔다. */}
      <details
        open={Boolean(draft && draft.open !== false)}
        onToggle={(event) => {
          const current = groupDrafts.get(draftKey);
          if (current) current.open = event.currentTarget.open;
        }}
      >
        <summary>이름·설명 바꾸기, 삭제</summary>
        <div className="pf-form" data-group-form={String(group.groupId)} data-draft={draftKey}>
          <label className="field">
            <span className="field-label">그룹 이름</span>
            <input data-field="name" maxLength={100} value={draft ? draft.name : group.name} onChange={(event) => typed({ name: event.target.value })} />
          </label>
          <div className="span-2">
            <label className="field">
              <span className="field-label">설명 (일반 텍스트)</span>
              <textarea data-field="description" rows={2} maxLength={1000} value={draft ? draft.description : group.description || ""} onChange={(event) => typed({ description: event.target.value })} />
            </label>
          </div>
          <div className="pf-actions span-2">
            <CmdButton kind="primary" label="저장" busyLabel="저장하는 중…" run={rename} />
            <DeleteGroup courseId={courseId} group={group} />
          </div>
          {draft && draft.conflict
            ? <p className="pf-sub span-2" data-draft-latest="">{`서버의 최신 내용 · 버전 ${group.version}: ${group.name}${group.description ? ` · ${group.description}` : " · 설명 없음"}`}</p>
            : null}
        </div>
      </details>
    </article>
  );
}

/** 두 번 눌러 확인하는 그룹 삭제. */
function DeleteGroup({ courseId, group }: { courseId: number; group: Group }) {
  const [armed, setArmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const live = useRef(true);
  useEffect(() => { live.current = true; return () => { live.current = false; }; }, []);
  async function press() {
    if (busy) return;
    if (!armed) {
      setArmed(true);
      return;
    }
    setArmed(false);
    setBusy(true);
    try {
      await deleteGroup(courseId, group.groupId, group.version);
      groupDrafts.delete(groupDraftKey(courseId, group.groupId));
      done("그룹을 삭제했습니다");
    } catch (error) {
      failed(courseId, error);
    } finally {
      if (live.current) setBusy(false);
    }
  }
  return (
    <Button kind="danger" disabled={busy} aria-busy={busy ? "true" : undefined} onClick={() => { void press(); }}>
      {busy ? "삭제하는 중…" : armed ? "한 번 더 누르면 삭제" : "그룹 삭제"}
    </Button>
  );
}

function CreateForm({ courseId }: { courseId: number }) {
  const form = useRef<HTMLDivElement>(null);
  // A successful create remounts the fields empty, so a second press cannot create the same group again.
  const [round, setRound] = useState(0);
  async function create() {
    const root = form.current;
    if (!root) return;
    const value = (name: string) => root.querySelector<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>(`[data-field="${name}"]`)?.value || "";
    const name = value("name").trim();
    const description = value("description").trim();
    if (!name) {
      say("그룹 이름을 입력하세요.", true);
      return;
    }
    try {
      await createGroup(courseId, { name, type: value("type"), description });
      setRound((n) => n + 1);
      done("그룹을 만들었습니다");
    } catch (error) {
      failed(courseId, error);
    }
  }
  return (
    <section className="sheet" aria-label="그룹 만들기">
      <h2>그룹 만들기</h2>
      <div key={round} ref={form} className="pf-form" data-group-form="new">
        <label className="field"><span className="field-label">그룹 이름</span><input data-field="name" maxLength={100} /></label>
        <label className="field">
          <span className="field-label">종류</span>
          <select data-field="type">{Object.entries(GROUP_TYPE).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select>
        </label>
        <div className="span-2">
          <label className="field"><span className="field-label">설명 (일반 텍스트)</span><textarea data-field="description" rows={2} maxLength={1000} /></label>
        </div>
        <div className="pf-actions span-2"><CmdButton kind="primary" label="그룹 만들기" busyLabel="저장하는 중…" run={create} /></div>
      </div>
    </section>
  );
}

/** 넣을 수강생 찾기 줄. 검색어와 쪽은 `finders`에 둔다. */
function StudentFinder({ courseId, search, data, error, onChange }: {
  courseId: number;
  search: Finder;
  data: { total: number; size: number } | null;
  error: unknown;
  onChange: () => void;
}) {
  const input = useRef<HTMLInputElement>(null);
  const line = useRef<HTMLParagraphElement>(null);
  const [focusLine, setFocusLine] = useState(false);
  useEffect(() => {
    if (focusLine) line.current?.focus();
  }, [focusLine]);
  const go = (next: Finder, focus: "input" | "line") => {
    finders.set(String(courseId), next);
    onChange();
    // 같은 자리로 초점을 돌린다. 쪽 단추는 새 쪽을 받는 동안 꺼져 있어 안내 줄로 보낸다.
    if (focus === "input") input.current?.focus();
    else {
      setFocusLine(true);
      line.current?.focus();
    }
  };
  const find = () => go({ q: String((input.current && input.current.value) || "").trim().slice(0, 50), page: 1 }, "input");
  const step = (dir: number) => go({ q: search.q, page: Math.max(1, search.page + dir) }, "line");
  const onKey = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key !== "Enter" || event.nativeEvent.isComposing) return;
    event.preventDefault();
    find();
  };
  const { q, page } = search;
  let text: string;
  let alert = false;
  let pages = 1;
  if (!data) {
    alert = Boolean(error);
    text = error
      ? (missing(error) ? "이 과목의 수강생 명단을 볼 수 없습니다." : `수강생 명단을 불러오지 못했습니다. ${groupMessage(error)}`)
      : "수강생 명단을 불러오는 중입니다.";
  } else {
    pages = Math.max(1, Math.ceil(data.total / data.size));
    text = data.total
      ? `${q ? `"${q}" ` : ""}수강생 ${data.total}명 · ${page}/${pages}쪽. 각 그룹의 목록에서 고릅니다.`
      : q ? `"${q}"에 맞는 수강생이 없습니다.` : "이 과목에 수강생이 없습니다.";
  }
  return (
    <section className="sheet srv-team-finder" aria-label="넣을 수강생 찾기">
      <h2>넣을 수강생 찾기</h2>
      <div className="pf-inline srv-team-finder__row">
        <label className="field">
          <span className="field-label">검색어</span>
          <input ref={input} type="search" data-field="studentQuery" defaultValue={q} maxLength={50} placeholder="이름 또는 학번" onKeyDown={onKey} />
        </label>
        <Button kind="secondary" onClick={find}>찾기</Button>
      </div>
      <p ref={line} className="pf-sub" role={alert ? "alert" : "status"} data-team-found="" tabIndex={focusLine ? -1 : undefined}>{text}</p>
      <div className="pf-inline">
        <Button kind="ghost" disabled={!data || page <= 1} onClick={() => step(-1)}>이전 쪽</Button>
        <Button kind="ghost" disabled={!data || page >= pages} onClick={() => step(1)}>다음 쪽</Button>
      </div>
    </section>
  );
}

/** `pf_team`(백엔드 모드) 본문. */
export function serverTeams(_ctx: ScreenCtx, course: Course): ReactNode {
  if (backendStatus() === "anon") return SIGNED_OUT;
  const courseId = courseIdFor(course.id);
  if (!courseId) return <div className="stack"><EmptyState title="이 과목은 ASTRA 서버에 없습니다" body="서버에 등록된 과목만 그룹을 관리할 수 있습니다." /></div>;
  if (roleIn(course.id) !== "INSTRUCTOR") return <div className="stack"><p className="pf-sub">이 과목을 담당하는 교수 계정으로 로그인해야 그룹을 관리할 수 있습니다.</p></div>;
  return <Teams courseId={Number(courseId)} />;
}

function Teams({ courseId }: { courseId: number }) {
  // A new visit starts with an empty status line.
  useState(() => { status = { text: "", alert: false }; });
  const [, setTick] = useState(0);
  const repaint = () => setTick((n) => n + 1);
  useEffect(() => {
    statusListeners.add(repaint);
    return () => { statusListeners.delete(repaint); };
  }, []);
  const key = groupsKey(courseId);
  const load = () => { void groupLists.load(key, courseId); };
  const search = finderOf(courseId);
  const skey = studentsKey(courseId, search.q, search.page);
  const entry = useResource(groupLists, key, load);
  const people = useResource(courseStudents, skey);
  useEffect(() => {
    if (freshVisit("pf-team", key) === "entry" || !groupLists.peek(key)) void groupLists.load(key, courseId);
  }, [key, courseId]);
  useEffect(() => {
    if (!courseStudents.peek(skey)) void courseStudents.load(skey, courseId, search.q, search.page);
  }, [skey, courseId, search.q, search.page]);

  const data = entry && entry.data;
  let body: ReactNode;
  if (!data) {
    if (!entry || !entry.error) body = <LoadingState title="그룹을 불러오는 중입니다" />;
    else if (missing(entry.error)) body = <EmptyState title="그룹을 볼 수 없습니다" body="이 과목의 담당 교수가 아니거나 과목을 찾을 수 없습니다." />;
    else body = <ErrorState title="그룹을 불러오지 못했습니다" body={groupMessage(entry.error)} actions={<Retry reload={load} />} />;
  } else {
    // 명단을 못 읽었으면 빈 후보로 그리고, 이유는 찾기 줄에 둔다.
    const students = people && people.data ? people.data.items : people && people.error ? [] : null;
    const groups = Array.isArray(data) ? data : [];
    body = (
      <>
        {entry.error ? <p className="err" role="alert">{groupMessage(entry.error)}</p> : null}
        {groups.length
          ? groups.map((group) => <GroupCard key={group.groupId} courseId={courseId} group={group} groups={groups} students={students} />)
          : <EmptyState title="그룹이 없습니다" body="아래에서 그룹을 만들면 학생 그룹 화면에 나옵니다." />}
      </>
    );
  }
  return (
    <div className="stack">
      <p className={status.alert ? "err" : "pf-sub"} role={status.alert ? "alert" : "status"} data-team-status="">{status.text}</p>
      <StudentFinder courseId={courseId} search={search} data={people && people.data} error={people && people.error} onChange={repaint} />
      <div className="stack" aria-live="polite">{body}</div>
      <CreateForm courseId={courseId} />
    </div>
  );
}
