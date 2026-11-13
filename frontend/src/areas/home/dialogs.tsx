import { useRef, type ReactNode } from "react";
import { Link, navigate, closeDialog } from "../../router.tsx";
import { getState, save, render } from "../../store.ts";
import { readForm, showErrors, stamp } from "../../forms.ts";
import { Icon } from "../../ui/icon.tsx";
import { Button, Dialog, Field } from "../../ui/kit.tsx";
import { notices, activities, courseById, student, professor, sampleNotifications } from "../../data.ts";
import type { Activity, SampleNotification } from "../../data.ts";
import { boardRows, dday, typeLabel, typeIcon, activityHref, openTodos, myDayGroups } from "../../shared/format.ts";
import type { BoardRow, MyDayGroup, MyDayGroups } from "../../shared/format.ts";
import { currentRole, routeFor } from "../../shared/routes.ts";
import { backendPanel } from "../../shared/backend-prompt.tsx";
import { backendState, backendStatus } from "../../shared/backend.ts";
import { profiles, peekAccount, useAccount } from "../../shared/account-remote.ts";
import type { Role, ScreenCtx } from "../../types.ts";
import { serverTodoList, homeLoginPanel } from "./home-server.tsx";
import { serverApplyBody } from "./catalog-server.tsx";
import { serverNotifications } from "./notifications-server.tsx";
import { remoteMaterialDialog, remoteInquiryDialog, remoteInquiryWriteDialog, remoteMaterialWriteDialog } from "./community-server.tsx";
import { communitySurveyDialog } from "./surveys-server.tsx";
import { serverAccountDialog } from "./account-server.tsx";
import { serverSiteNoticeDialog, serverSiteNoticeWriteDialog, serverEntryNotices } from "./site-notices-server.tsx";

const MINE = new Set(["welcome", "settings", "display", "profile", "myinfo", "notify", "notify-unread", "todo", "todo-more", "external", "related", "apply", "chat", "qna-write"]);

interface ChatMessage {
  from: string;
  text: string;
}

const CHAT_GREETING: ChatMessage = { from: "서포터즈", text: "샘플 상담입니다. 계정 비밀번호는 받지 않습니다." };

/** A home dialog window (`home-dialog` plus `className`). */
function HomeDialog({ title, className, wide, children }: { title: string; className?: string; wide?: boolean; children: ReactNode }) {
  return <Dialog title={title} wide={wide} className={["home-dialog", className].filter(Boolean).join(" ")}>{children}</Dialog>;
}

function koWhen(value: string | null | undefined): string {
  if (!value) return "기간 없음";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${date.getMonth() + 1}월 ${date.getDate()}일 ${p(date.getHours())}:${p(date.getMinutes())}`;
}

function koDue(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  const h = date.getHours();
  const m = String(date.getMinutes()).padStart(2, "0");
  return `${date.getMonth() + 1}월 ${date.getDate()}일 (${"일월화수목금토"[date.getDay()]}) ${h < 12 ? "오전" : "오후"} ${h % 12 || 12}:${m}`;
}

function NotifyRow({ item }: { item: SampleNotification }) {
  const activity = item.activityId ? activities.find((row) => row.id === item.activityId) : undefined;
  const course = activity ? courseById(activity.courseId) : null;
  const href = activity ? activityHref(activity) : "";
  const kind = activity ? typeLabel(activity.type) : item.kind;
  const due = activity ? dday(activity.due) : "";
  const when = activity ? activity.whenLabel || koDue(activity.due || "") : item.when;
  const className = `ntf-row${item.read ? " read" : ""}`;
  const body = (
    <>
      {item.read
        ? <span className="ntf-dot" aria-hidden="true" />
        : <span className="ntf-dot" role="img" aria-label="새 알림" />}
      <span className="ntf-copy">
        <b>{`[${kind}] ${item.title}`}</b>
        <span className="ntf-src">{course ? `${course.cardTitle || course.ko}(${course.code})` : item.source}</span>
        <span className="ntf-when">
          {due ? <em>{due}</em> : null}
          <span>{when}</span>
        </span>
      </span>
    </>
  );
  return (
    <li>
      {href && activity
        ? <Link className={className} href={href} data-item={activity.id} data-todo={activity.type}>{body}</Link>
        : <div className={className}>{body}</div>}
    </li>
  );
}

function NotifyChip({ href, label, count, on }: { href: string; label: string; count: number; on: boolean }) {
  return (
    <Link className={`chip all${on ? " on" : ""}`} href={href} aria-current={on ? "page" : undefined}>
      <span>{label}</span>
      <span className="ntf-count">{String(count)}</span>
    </Link>
  );
}

interface Person {
  name: string;
  dept?: string;
  avatar?: string;
  no?: string;
  year?: string;
  real?: boolean;
}

function person(role: Role): Person {
  const session = backendState();
  if (session.status !== "ready" || !session.me) return role === "professor" ? professor : student;
  const entry = peekAccount(profiles);
  const profile = entry && entry.data;
  const own = profile && String(profile.userId) === String(session.me.userId) ? profile : null;
  return { name: session.me.name || "", dept: (own && own.departmentName) || "", real: true };
}

function TaskCard({ item }: { item: Activity }) {
  const course = courseById(item.courseId);
  return (
    <Link className="task-row todo-link" href={activityHref(item)} data-item={item.id} data-todo={item.type}>
      <div className="task-title">
        <span className={`task-kind ${item.type}`} role="img" aria-label={typeLabel(item.type)} title={typeLabel(item.type)}><Icon name={typeIcon(item.type)} /></span>
        <b>{item.title}</b>
      </div>
      <div className="task-course">
        <span>{course.cardTitle || course.ko}</span>
        <span className="task-code">{`(${course.code})`}</span>
      </div>
      <div className="task-when">
        {item.due ? <strong className="task-due">{dday(item.due)}</strong> : null}
        <span>{item.whenLabel || (item.due ? `마감 ${koDue(item.due)}` : item.when ? koWhen(item.when) : "진행 중 · 마감일 없음")}</span>
      </div>
    </Link>
  );
}

function MenuLink({ href, label }: { href: string; label: string }) {
  return <Link className="menu-item" href={href}><span>{label}</span></Link>;
}

// ---------- actions ----------

function toggleLang(): void {
  const state = getState();
  state.lang = state.lang === "ko" ? "en" : "ko";
  save();
  render();
}

function applyCourse(id: string): void {
  const state = getState();
  const motive = String(readForm().motive || "").trim();
  if (!motive) return showErrors({ motive: "한 줄을 입력하세요" });
  state.applications[id] = motive;
  state.toast = "신청을 저장했습니다";
  save();
  navigate(`/ilos/main/nr/non_regular_view_form.acl?id=${encodeURIComponent(id)}&enrolled=1`);
}

function sendChat(): void {
  const state = getState();
  const chat = String(readForm().chat || "").trim();
  if (!chat) return showErrors({ chat: "메시지를 입력하세요" });
  const thread: ChatMessage[] = state.draft.chat || [CHAT_GREETING];
  thread.push({ from: "박민준", text: chat });
  thread.push({ from: "서포터즈", text: "확인했습니다. 이 대화는 브라우저 안에만 있습니다." });
  state.draft.chat = thread;
  state.toast = "전송했습니다";
  save();
  render();
}

function savePost(board: string): void {
  const state = getState();
  const data = readForm();
  const title = String(data.title || "").trim();
  const body = String(data.body || "").trim();
  const errors: Record<string, string> = {};
  if (!title) errors.title = "제목을 입력하세요";
  if (!body || body.length < 4) errors.body = "내용을 4자 이상 입력하세요";
  if (Object.keys(errors).length) return showErrors(errors);
  const post = { id: `p-${Date.now()}`, title, body, author: "박민준", at: stamp(), board, reply: "" };
  if (board === "community") state.communityQna.unshift(post);
  else state.posts.unshift(post);
  state.toast = "등록했습니다";
  save();
  closeDialog();
}

// ---------- dialogs ----------

function ProfileMenu({ ctx }: { ctx: ScreenCtx }) {
  const card = useRef<HTMLDivElement>(null);
  useAccount(profiles, "profile-menu");
  const who = person(ctx.state.role);
  return (
    <div className="modal-back menu-back" onClick={(event) => { if (event.target === event.currentTarget) closeDialog(); }}>
      <div ref={card} className="profile-pop home-dialog" role="dialog" aria-modal="true" aria-label="내 메뉴">
        <h2>{who.name}</h2>
        <p className="tiny center" hidden={!who.dept}>{who.dept || ""}</p>
        <div className="menu-list">
          <MenuLink href={routeFor(currentRole(), "pop", { pop: "myinfo" })} label="내 정보" />
          <MenuLink href={routeFor(currentRole(), "pop", { pop: "settings" })} label="설정" />
          <Link className="menu-item" href="/ilos/lo/logout.acl" logout><span>로그아웃</span></Link>
        </div>
        {backendPanel({ compact: true, text: "ASTRA 서버에 로그인하면 서버에 등록된 자료와 쪽별 요약을 사용할 수 있습니다." })}
      </div>
    </div>
  );
}

function todoDialog(ctx: ScreenCtx): ReactNode {
  // `todo-view` comes from a 나의 하루 card or the hero's soon count: list exactly the items it counted.
  const groups = myDayGroups(ctx.state);
  const viewKey = ctx.params.get("todo-view") || "";
  const view: MyDayGroup | null = Object.hasOwn(groups, viewKey) ? groups[viewKey as keyof MyDayGroups] : null; // checked by hasOwn
  const list = view ? view.items : openTodos();
  const allParams = new URLSearchParams(ctx.params);
  allParams.delete("todo-view");
  const allHref = `${location.pathname}?${allParams}`;
  return (
    <HomeDialog title="할 일" className="todo-modal">
      <div className="tasks">
        {backendStatus() === "anon" ? homeLoginPanel(true) : null}
        {view
          ? (
            <div className="task-scope">
              <p><b>{view.label}</b><span className="task-count">{` ${list.length}`}</span></p>
              <Link className="task-scope-all" href={allHref}>{`전체 할 일 ${openTodos().length}`}</Link>
            </div>
          )
          : null}
        {list.length
          ? <ul className="task-list">{list.map((item) => <li key={item.id}><TaskCard item={item} /></li>)}</ul>
          : <p className="task-empty" role="status">남은 할 일이 없습니다.</p>}
      </div>
    </HomeDialog>
  );
}

export function renderDialog(name: string, ctx: ScreenCtx): ReactNode | null {
  // A server 알림마당 survey (`?pop=survey&id=srv-<id>`, backend mode).
  if (name === "survey") {
    const server = communitySurveyDialog(ctx);
    if (server) return server;
  }
  if (name === "notice") {
    if (ctx.screen.board === "course") return null;
    // Backend mode: 알림마당 공지 (`?pop=notice&id=srv-<noticeId>`, areas/home/site-notices-server.tsx).
    if (backendStatus() !== "off") return serverSiteNoticeDialog(ctx);
    return notice(ctx);
  }
  if (name === "qna-write" && ctx.screen.board !== "qna") return null;
  // Backend mode: 알림마당 자료실·1:1 문의 dialogs (areas/home/community-server.tsx).
  if (backendStatus() !== "off" && ctx.screen.board === "material" && name === "material") return remoteMaterialDialog(ctx);
  if (backendStatus() !== "off" && ctx.screen.board === "qna" && name === "inquiry") return remoteInquiryDialog(ctx);
  if (backendStatus() !== "off" && ctx.screen.board === "material" && name === "material-write") return remoteMaterialWriteDialog(ctx);
  if (backendStatus() !== "off" && ctx.screen.board === "qna" && name === "qna-write") return remoteInquiryWriteDialog();
  if (backendStatus() !== "off" && ctx.screen.board === "global" && name === "notice-write") return serverSiteNoticeWriteDialog(ctx);
  if (!MINE.has(name)) return null;
  const draft = ctx.state.draft || {};
  const who = person(ctx.state.role);
  // Backend mode: account dialogs read the server (account-server.tsx).
  const server = serverAccountDialog(name, ctx);
  if (server) return server;
  // Backend `ready`: the entry popup lists the unread pinned 알림마당 notices (`noticesForMe`).
  if (name === "welcome" && backendStatus() === "ready") return serverEntryNotices();
  if (name === "welcome") {
    return (
      <HomeDialog title="e-Class 안내">
        <div>
          <p>2026학년도 2학기 샘플 화면입니다. 제출과 설정은 이 브라우저에만 남고, 학교 서버로는 전송되지 않습니다.</p>
          <Button kind="primary" onClick={() => closeDialog()}>확인</Button>
        </div>
      </HomeDialog>
    );
  }
  if (name === "settings") {
    return (
      <HomeDialog title="설정">
        <div>
          <div className="menu-list">
            <button type="button" className="set-row" onClick={toggleLang}>
              <span>언어</span>
              <span className="hint">{ctx.state.lang === "ko" ? "한국어" : "English"}</span>
            </button>
          </div>
          <p className="note">언어만 이 브라우저에 저장됩니다. 밝은 화면은 기기 설정이 라이트일 때만 열리고, 테마 단추는 없습니다.</p>
        </div>
      </HomeDialog>
    );
  }
  if (name === "display") {
    return <HomeDialog title="화면 모드"><p className="note">밝은 화면은 기기가 라이트일 때만 열립니다. 따로 고르는 단추는 없습니다.</p></HomeDialog>;
  }
  if (name === "profile") return <ProfileMenu ctx={ctx} />;
  if (name === "myinfo") {
    return (
      <HomeDialog title="내 정보">
        <div className="who">
          <span className="avatar lg">{String(draft.avatar || who.avatar || "").slice(0, 2)}</span>
          <b>{who.name}</b>
          {who.no ? <span className="tiny">{who.no}</span> : null}
          {who.dept ? <p>{who.dept}</p> : null}
          <p className="tiny">{ctx.state.role === "professor" ? "교수" : [who.year, "학생"].filter(Boolean).join(" · ")}</p>
          <p className="note">전화번호와 메일은 이 샘플에 두지 않습니다. 학사 시스템에서 확인하세요.</p>
        </div>
      </HomeDialog>
    );
  }
  if (name === "notify" || name === "notify-unread") {
    if (backendStatus() !== "off") return <HomeDialog title="알림" className="ntf-modal">{serverNotifications(name)}</HomeDialog>;
    const unread = sampleNotifications.filter((item) => !item.read);
    const items = name === "notify" ? sampleNotifications : unread;
    return (
      <HomeDialog title="알림" className="ntf-modal">
        <div className="ntf">
          <nav className="filters ntf-filters" aria-label="알림 보기">
            <NotifyChip href={routeFor(currentRole(), "pop", { pop: "notify" })} label="전체" count={sampleNotifications.length} on={name === "notify"} />
            <NotifyChip href={routeFor(currentRole(), "notify", { pop: "notify-unread" })} label="안 읽음" count={unread.length} on={name === "notify-unread"} />
          </nav>
          {items.length
            ? <ul className="ntf-list">{items.map((item) => <NotifyRow key={item.title} item={item} />)}</ul>
            : <p className="note">읽지 않은 알림이 없습니다.</p>}
        </div>
      </HomeDialog>
    );
  }
  if (name === "todo" || name === "todo-more") {
    if (backendStatus() === "ready") return <HomeDialog title="할 일" className="todo-modal">{serverTodoList(ctx.params.get("todo-view") || "")}</HomeDialog>;
    return todoDialog(ctx);
  }
  if (name === "chat") {
    const thread: ChatMessage[] = draft.chat || [CHAT_GREETING];
    return (
      <HomeDialog title="서포터즈">
        <div>
          {thread.map((message, index) => (
            <div key={index} className={`bubble${message.from === "박민준" ? " mine" : ""}`}>
              <b>{message.from}</b>
              {message.text}
            </div>
          ))}
          <Field label="메시지" name="chat"><input data-name="chat" placeholder="메시지를 입력하세요" /></Field>
          <Button kind="primary" onClick={sendChat}>보내기</Button>
        </div>
      </HomeDialog>
    );
  }
  // Backend `ready`: the server application preview and the apply command (Idempotency-Key).
  if (name === "apply" && backendStatus() === "ready") return <HomeDialog title="수강 신청">{serverApplyBody(ctx)}</HomeDialog>;
  if (name === "apply") {
    const course = courseById((ctx.params && ctx.params.get("id")) || "lit");
    return (
      <HomeDialog title="수강 신청">
        <div>
          <p>{`${course.ko} (${course.code})에 신청합니다. 정원과 별도로 이 브라우저에만 기록됩니다.`}</p>
          <Field label="한 줄 각오" name="motive"><input data-name="motive" placeholder="신청 이유를 한 줄로" /></Field>
          <Button kind="primary" onClick={() => applyCourse(course.id)}>신청</Button>
        </div>
      </HomeDialog>
    );
  }
  if (name === "qna-write") {
    return (
      <HomeDialog title="1:1 질문하기">
        <div>
          <Field label="제목" name="title"><input data-name="title" defaultValue={draft.title || ""} /></Field>
          <Field label="내용" name="body"><textarea data-name="body" placeholder="질문 내용을 적어 주세요" /></Field>
          <Field label="공개">
            <select data-name="open">
              <option value="open">공개</option>
              <option value="private">비공개</option>
            </select>
          </Field>
          <Button kind="primary" onClick={() => savePost("community")}>등록</Button>
        </div>
      </HomeDialog>
    );
  }
  if (name === "external") return <HomeDialog title="학력증진"><p className="note">역량 강화와 외부 사이트는 이 샘플에 연결하지 않습니다.</p></HomeDialog>;
  if (name === "related") return <HomeDialog title="관련 사이트"><p className="note">포털, 도서관, 학사 시스템은 이 샘플 밖에 있습니다.</p></HomeDialog>;
  return null;
}

function notice(ctx: ScreenCtx): ReactNode {
  const id = ctx.params && ctx.params.get("id");
  const row: BoardRow = boardRows({ ...ctx.screen, board: ctx.screen.board || "global", course: ctx.state.courseId }, ctx.state).find((item) => item.id === id)
    || notices.find((item) => item.id === id)
    || notices[0]!;
  return (
    <HomeDialog title={row.title} wide>
      <div>
        <p className="tiny">{[row.author, row.date || row.at].filter(Boolean).join(" · ")}</p>
        <p>{row.body || "본문이 없습니다."}</p>
      </div>
    </HomeDialog>
  );
}
