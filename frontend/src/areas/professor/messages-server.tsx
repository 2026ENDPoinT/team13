// Professor 쪽지 발송 (`pf_message`) in backend mode: pick course members through
// `GET /api/messages/recipients?courseId=` and send one message with `POST /api/messages`
// (Idempotency-Key; a retry of the same content, attachments included, after a lost response
// reuses it). Attachments are `message_attachment` uploads without a course (`messageFiles`).
// Below the form, the professor's own 보낸 쪽지 box (`GET /api/messages/sent`, every course):
// search, "더 보기" paging, selected delete and 전체 삭제 (shared/message-box.tsx `serverSentBox`).
// Typed text, the chosen people and picked files stay in memory per course until a send succeeds.
import { useEffect, useRef, useState, type ReactNode } from "react";
import { Button, EmptyState } from "../../ui/kit.tsx";
import { toast } from "../../store.ts";
import type { Course } from "../../data.ts";
import type { ScreenCtx } from "../../types.ts";
import { backendStatus, courseIdFor, roleIn, subscribe } from "../../shared/backend.ts";
import { backendPanel } from "../../shared/backend-prompt.tsx";
import { resetAttachments, type AttachMemo } from "../../shared/attach-picker.tsx";
import {
  sendMessage, isNotAllowed, isUncertain, isAttachmentNotReady, messageMessage, screenBytes, ROLE_LABEL, MAX_TITLE, MAX_BODY_BYTES, MAX_RECIPIENTS,
  type MessagePerson,
} from "../../shared/messages-remote.ts";
import { recipientSearch, serverSentBox, messageFiles, filesProblem, SCOPE_TEXT } from "../../shared/message-box.tsx";

interface Memo {
  title: string;
  body: string;
  chosen: Map<number, MessagePerson>;
  files: AttachMemo;
  receipt: { recipients: MessagePerson[] } | null;
}

const memos = new Map<number | string, Memo>(); // backend courseId -> memo
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  memos.clear();
});

function memoOf(courseId: number | string): Memo {
  let memo = memos.get(courseId);
  if (!memo) {
    memo = { title: "", body: "", chosen: new Map(), files: {}, receipt: null };
    memos.set(courseId, memo);
  }
  return memo;
}

/** The `pf_message` body in backend mode. `_parts` (professor.tsx helpers) is not needed. */
export function serverMessageFields(_ctx: ScreenCtx, course: Course, _parts?: unknown): ReactNode {
  if (backendStatus() === "anon") {
    // The sample form is hidden: a message kept only in this browser would look sent.
    return <div className="stack">{backendPanel({ text: "ASTRA 서버에 로그인하면 이 과목의 수강생에게 쪽지를 보낼 수 있습니다." })}</div>;
  }
  const courseId = courseIdFor(course.id);
  if (!courseId) return <div className="stack">{backendPanel()}<EmptyState title="이 과목은 ASTRA 서버에 없습니다" body="서버에 등록된 과목의 구성원에게만 쪽지를 보낼 수 있습니다." /></div>;
  if (roleIn(course.id) !== "INSTRUCTOR") {
    return <div className="stack">{backendPanel()}<p className="pf-sub">이 과목을 담당하는 교수 계정으로 로그인해야 과목 구성원에게 쪽지를 보낼 수 있습니다.</p></div>;
  }
  return <MessageScreen courseId={Number(courseId)} />;
}

function MessageScreen({ courseId }: { courseId: number }) {
  const [sentKey, setSentKey] = useState(0);
  return (
    <div className="stack">
      {backendPanel()}
      <MessageForm courseId={courseId} onSent={() => setSentKey((n) => n + 1)} />
      <div className="sheet" data-pf-sent-box="">
        <h2>보낸 쪽지</h2>
        <div className="pf-form">
          <p className="pf-sub span-2">모든 과목에서 보낸 쪽지입니다. 여기서 지우면 보낸 쪽지함에서만 사라지고 받는 사람의 쪽지는 그대로입니다.</p>
          <div className="span-2" key={sentKey}>{serverSentBox()}</div>
        </div>
      </div>
    </div>
  );
}

interface Status {
  text: string;
  alert: boolean;
}

function MessageForm({ courseId, onSent }: { courseId: number; onSent: () => void }) {
  const memo = memoOf(courseId);
  const [, setTick] = useState(0);
  const repaint = () => setTick((n) => n + 1);
  const [status, setStatus] = useState<Status>({ text: "", alert: false });
  const [sending, setSending] = useState(false);
  const titleRef = useRef<HTMLInputElement>(null);
  const live = useRef(true);
  useEffect(() => { live.current = true; return () => { live.current = false; }; }, []);
  const say = (text: string, alert = false) => setStatus({ text, alert });
  const picker = messageFiles(memo.files);
  const bytes = screenBytes(memo.body.trim());
  const people = [...memo.chosen.values()];

  async function send() {
    if (sending) return;
    const text = memo.title.trim();
    const problems: string[] = [];
    if (!memo.chosen.size) problems.push("받는 사람을 한 명 이상 고르세요.");
    else if (memo.chosen.size > MAX_RECIPIENTS) problems.push(`받는 사람은 ${MAX_RECIPIENTS}명까지입니다.`);
    if (!text) problems.push("제목을 입력하세요.");
    if (!memo.body.trim()) problems.push("내용을 입력하세요.");
    else if (screenBytes(memo.body.trim()) > MAX_BODY_BYTES) problems.push(`내용은 ${MAX_BODY_BYTES}byte 이하로 적어 주세요.`);
    const fileProblem = filesProblem(picker, memo.files);
    if (fileProblem) problems.push(fileProblem);
    if (problems.length) {
      say(problems.join(" "), true);
      return;
    }
    setSending(true);
    say("ASTRA 서버로 보내고 있습니다.");
    try {
      const sent = await sendMessage({ title: text, body: memo.body, courseId, recipientIds: [...memo.chosen.keys()], uploadIds: picker.uploadIds() });
      memo.title = "";
      memo.body = "";
      memo.chosen.clear();
      resetAttachments(memo.files);
      memo.receipt = { recipients: Array.isArray(sent.recipients) ? sent.recipients : [] };
      onSent();
      if (live.current) say("");
      toast("쪽지를 보냈습니다");
    } catch (error) {
      if (isNotAllowed(error)) say(messageMessage(error), true);
      else if (isAttachmentNotReady(error)) {
        picker.recheck();
        say(messageMessage(error), true);
      } else if (isUncertain(error)) say("보낸 결과를 확인하지 못했습니다. 네트워크를 확인한 뒤 같은 내용으로 다시 보내세요. 이미 보내졌다면 두 번 보내지 않습니다.", true);
      else say(messageMessage(error), true);
    } finally {
      if (live.current) setSending(false);
    }
  }

  return (
    <div className="sheet" data-message-form="">
      <h2>쪽지</h2>
      <div className="pf-form">
        <label className="field span-2">
          <span className="field-label">제목</span>
          <input ref={titleRef} maxLength={MAX_TITLE} value={memo.title} data-pf-msg-title="" onChange={(event) => { memo.title = event.target.value; repaint(); }} />
        </label>
        <label className="field span-2">
          <span className="field-label">내용</span>
          <textarea rows={8} value={memo.body} data-pf-msg-body="" onChange={(event) => { memo.body = event.target.value; repaint(); }} />
        </label>
        <p className={bytes > MAX_BODY_BYTES ? "pf-sub err span-2" : "pf-sub span-2"} aria-live="polite">{`${bytes}/${MAX_BODY_BYTES}byte`}</p>
        <div className="span-2">
          <p className="pf-kicker">{`받는 사람 (${MAX_RECIPIENTS}명까지)`}</p>
          <div className="msg-chips" aria-label="고른 받는 사람">
            {people.length
              ? people.map((person) => (
                <span className="msg-chip" key={person.userId}>
                  <span>{`${person.name} · ${(person.role && ROLE_LABEL[person.role]) || "구성원"}`}</span>
                  <Button
                    kind="ghost"
                    className="msg-chip__x"
                    aria-label={`${person.name} 빼기`}
                    onClick={() => {
                      memo.chosen.delete(person.userId);
                      repaint();
                      titleRef.current?.focus();
                    }}
                  >
                    ×
                  </Button>
                </span>
              ))
              : <span className="pf-sub">아래에서 받는 사람을 고르세요.</span>}
          </div>
        </div>
        <p className="pf-sub span-2" data-msg-scope="">{SCOPE_TEXT}</p>
        <div className="span-2">{recipientSearch({ courseId, chosen: memo.chosen, onChange: repaint })}</div>
        <div className="span-2">
          <p className="pf-kicker">첨부 파일</p>
          <div data-pf-msg-files="">{picker.node}</div>
        </div>
        <p className="pf-sub span-2" role={status.alert ? "alert" : "status"} aria-live="polite" data-pf-msg-status="">{status.text}</p>
        <div className="span-2">
          {memo.receipt ? (
            <section className="note ok" role="status" data-pf-msg-receipt="">
              <p>{`${memo.receipt.recipients.length}명에게 쪽지를 보냈습니다.`}</p>
              <p className="pf-sub">{memo.receipt.recipients.map((person) => person.name).join(", ")}</p>
            </section>
          ) : null}
        </div>
        <div className="pf-actions span-2">
          <Button kind="primary" disabled={sending} aria-busy={sending ? "true" : undefined} onClick={() => { void send(); }}>
            {sending ? "보내는 중…" : "발송"}
          </Button>
        </div>
      </div>
    </div>
  );
}
