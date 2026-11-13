// Course Q&A (질의응답) and open board (열린게시판) from the ASTRA backend (status `anon` or
// `ready`). Lists, details, answers and comments are server data only; sample posts are never
// mixed in. The server decides visibility: a private question is simply absent for other
// students (404 on its detail), and an author name appears only when the response carries it.
// Typed text and picked attachments live in memory per form until the save succeeds or the
// session changes. Editing a question, an answer or an open-board post keeps or removes its files
// and adds new ones (`attachments: { keep, add }`); a comment's author edits its text in place.
// The Q&A list and the post cards are shared with the professor `pf_qna` (shared/board-list.tsx).
import { useEffect, useReducer, useRef, useState, useSyncExternalStore, type ReactNode } from "react";
import type { Course } from "../../data.ts";
import type { Screen, ScreenCtx } from "../../types.ts";
import { getState, render } from "../../store.ts";
import { Link, closeDialog, navigate } from "../../router.tsx";
import { Badge, Button, Dialog, EmptyState, ErrorState, LoadingState, Tabs, type ButtonKind } from "../../ui/kit.tsx";
import { Icon } from "../../ui/icon.tsx";
import { hrefFor, find } from "../../manifest.ts";
import { readQuery } from "../../shared/query.tsx";
import { ApiError } from "../../shared/api.ts";
import { courseIdFor, roleIn, subscribe } from "../../shared/backend.ts";
import { freshVisit, useResource, type Entry } from "../../shared/remote-cache.ts";
import { koDateTime } from "../../shared/home-remote.ts";
import { downloadControl, sizeText } from "../../shared/uploads-remote.tsx";
import { attachPicker, type AttachMemo, type AttachPickerHandle, type ExistingFile } from "../../shared/attach-picker.tsx";
import { QNA_LOGIN, kindTag, statusTag, authorText, postCard, gate, visitList, qnaSection } from "../../shared/board-list.tsx";
import {
  MAX_UPLOADS, MAX_TITLE, MAX_BODY, COMMENT_BYTES,
  openListKey, postKey, qnaDetails, openLists, openDetails, answerLists, commentLists,
  moreOpenPosts, moreAnswers, moreComments, isAppending,
  createQuestion, createOpenPost, updatePost, deletePost, setLike, markPostRead, setQuestionStatus,
  createAnswer, updateAnswer, deleteAnswer, createComment, updateComment, deleteComment, createUncertain,
  isCode, boardMessage, legacyBytes,
  type BoardAnswer, type BoardAttachment, type BoardComment, type BoardKind, type BoardPost, type CursorList, type Id,
} from "../../shared/boards-remote.ts";
import type { CourseParts } from "./course.tsx";
import { RetryButton } from "./groups-server.tsx";

const OPEN_LOGIN = "ASTRA 서버에 로그인하면 이 과목의 열린게시판을 서버에서 불러옵니다.";

const missing = (error: unknown) => error instanceof ApiError && error.status === 404;

// 서버의 `canEdit`/`canDelete`만 믿지 않고 화면에서도 가린다: 고치기는 작성자(`mine`)만, 지우기는 작성자나 담당 교수만.
const editable = (item: { canEdit?: boolean; mine?: boolean }): boolean => Boolean(item.canEdit && item.mine);
const deletable = (item: { canDelete?: boolean; mine?: boolean }, instructor: boolean): boolean => Boolean(item.canDelete && (item.mine || instructor));

// ---------- memory per form (cleared when the session changes) ----------

interface Memo extends AttachMemo {
  title: string;
  body: string;
  anonymous: boolean;
  secret: boolean;
  parent: number | null;
  loaded: boolean;
  /** An error that survives the re-render a 409 reload causes. */
  notice?: string;
  /** Focus the field once on the next render. */
  focus?: boolean;
  /** File ids of the existing files this form last saw. */
  seen?: Set<number>;
}

type TextField = "title" | "body";
type FlagField = "anonymous" | "secret";

const memos = new Map<string, Memo>();
const editing = new Map<string, number>(); // postKey -> answerId being edited inline
const commentEditing = new Map<string, number>(); // postKey -> commentId being edited inline
let refocusComment: number | null = null; // the comment whose "수정" button gets focus back
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  memos.clear();
  editing.clear();
  commentEditing.clear();
});

function memoOf(key: string): Memo {
  let memo = memos.get(key);
  if (!memo) {
    memo = { title: "", body: "", anonymous: false, secret: false, uploads: [], parent: null, loaded: false, repaint: null };
    memos.set(key, memo);
  }
  return memo;
}

// ---------- commands ----------

// Operation keys being sent; every button of the same operation stays disabled meanwhile.
const inflight = new Set<string>();
const inflightListeners = new Set<() => void>();
let inflightVersion = 0;

function setInflight(op: string, on: boolean) {
  if (on) inflight.add(op);
  else inflight.delete(op);
  inflightVersion += 1;
  for (const listener of [...inflightListeners]) listener();
}

function subscribeInflight(listener: () => void) {
  inflightListeners.add(listener);
  return () => {
    inflightListeners.delete(listener);
  };
}

function useLive() {
  const live = useRef(true);
  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
    };
  }, []);
  return live;
}

interface CmdProps {
  children: ReactNode;
  kind: ButtonKind;
  op: string;
  /** Label while this button's command runs (none: keep the label). */
  busyText?: string;
  run: () => Promise<unknown> | unknown;
  /** Runs first; returning true stops the command (e.g. arming a delete). */
  intercept?: () => boolean;
  disabled?: boolean;
  label?: string;
  pressed?: boolean;
}

/** A server command button: disabled with `aria-busy` while its operation runs; one operation never runs twice at once. */
function CmdButton({ children, kind, op, busyText, run, intercept, disabled, label, pressed }: CmdProps) {
  useSyncExternalStore(subscribeInflight, () => inflightVersion);
  const [mine, setMine] = useState(false);
  const live = useLive();
  const busy = inflight.has(op);
  const click = async () => {
    if (disabled || inflight.has(op)) return;
    if (intercept && intercept()) return;
    setInflight(op, true);
    setMine(true);
    try {
      await run();
    } finally {
      setInflight(op, false);
      if (live.current) setMine(false);
    }
  };
  return (
    <Button
      kind={kind}
      disabled={Boolean(disabled) || busy}
      aria-busy={busy ? "true" : undefined}
      aria-label={label}
      aria-pressed={pressed == null ? undefined : pressed ? "true" : "false"}
      onClick={() => void click()}
    >
      {mine && busyText ? busyText : children}
    </Button>
  );
}

/** Two presses: the first arms the button, the second runs `work`. */
function DeleteButton({ label, op, work }: { label: string; op: string; work: () => Promise<unknown> }) {
  const [armed, setArmed] = useState(false);
  return (
    <CmdButton
      kind="ghost"
      op={op}
      busyText="삭제하는 중…"
      label={armed ? `${label} 삭제, 한 번 더 누르면 삭제` : `${label} 삭제`}
      intercept={() => {
        if (armed) return false;
        setArmed(true);
        return true;
      }}
      run={work}
    >
      {armed ? "한 번 더 누르면 삭제" : "삭제"}
    </CmdButton>
  );
}

function toast(text: string) {
  getState().toast = text;
  render();
}

// ---------- small views ----------

/** Plain text in paragraphs; every piece is text. */
function prose(text: string | null | undefined, empty = "본문이 없습니다."): ReactNode {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  if (!blocks.length) return empty ? <div className="crs-prose"><p className="crs-note">{empty}</p></div> : null;
  return (
    <div className="crs-prose">
      {blocks.map((block, n) => <p key={n}>{block.split("\n").flatMap((line, index) => (index ? [<br key={index} />, line] : [line]))}</p>)}
    </div>
  );
}

function files(courseId: Id, list: BoardAttachment[] | undefined): ReactNode {
  const items = Array.isArray(list) ? list : [];
  if (!items.length) return null;
  return (
    <div className="crs-files" aria-label="첨부 파일">
      {items.map((file) => (
        <div key={file.fileId} className="file-row">
          <Icon name="file" />
          <span><b>{file.name}</b><span className="tiny">{sizeText(file.sizeBytes)}</span></span>
          {downloadControl(courseId, file)}
        </div>
      ))}
    </div>
  );
}

/** The current address with `changes` applied (null removes a parameter). */
function here(ctx: ScreenCtx, changes: Record<string, string | null>): string {
  const params = new URLSearchParams(ctx.params ? ctx.params.toString() : "");
  for (const [name, value] of Object.entries(changes)) {
    if (value == null || value === "") params.delete(name);
    else params.set(name, String(value));
  }
  const q = params.toString();
  return `${location.pathname}${q ? `?${q}` : ""}`;
}

function idParam(ctx: ScreenCtx): number | null {
  const id = Number(ctx.params && ctx.params.get("id"));
  return Number.isInteger(id) && id > 0 ? id : null;
}

function wideShell(title: string, body: ReactNode): ReactNode {
  return <Dialog title={title} className="crs-modal" wide>{body}</Dialog>;
}

// ---------- attachments ----------

function existingOf(list: BoardAttachment[] | undefined): ExistingFile[] {
  return (Array.isArray(list) ? list : []).map((file) => ({ fileId: Number(file.fileId), name: file.name, sizeBytes: file.sizeBytes }));
}

/**
 * Keeps the form's keep/remove choices in step with the files the server now lists: a file that is
 * gone leaves the choice, a file someone else attached since starts kept.
 */
function syncKeep(memo: Memo, existing: ExistingFile[]) {
  const ids = existing.map((file) => file.fileId);
  if (memo.keep instanceof Set && memo.seen instanceof Set) {
    const seen = memo.seen;
    memo.keep = new Set([...memo.keep].filter((fileId) => ids.includes(fileId)));
    for (const fileId of ids) if (!seen.has(fileId)) memo.keep.add(fileId);
  }
  memo.seen = new Set(ids);
}

/** `post_attachment` picker of one form; `existing` is the post's or answer's current files (edit). */
function boardPicker(memo: Memo, courseId: Id, existing: ExistingFile[], onChange: () => void): AttachPickerHandle {
  syncKeep(memo, existing);
  return attachPicker({ memo, courseId, purpose: "post_attachment", max: MAX_UPLOADS, existing, onChange });
}

/** The edit body's `attachments`, only when the user changed the files (leaving it out keeps them). */
function attachmentChange(picker: AttachPickerHandle) {
  return picker.changed() ? { attachments: picker.attachments() } : {};
}

interface TextInputProps {
  memo: Memo;
  field: TextField;
  label: string;
  multiline?: boolean;
  rows?: number;
  max?: number;
  placeholder?: string;
  onInput?: () => void;
  inputRef?: React.Ref<HTMLTextAreaElement>;
}

function TextInput({ memo, field, label, multiline, rows = 8, max, placeholder = "", onInput, inputRef }: TextInputProps) {
  const [, repaint] = useReducer((n: number) => n + 1, 0);
  const change = (value: string) => {
    memo[field] = value;
    repaint();
    if (onInput) onInput();
  };
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      {multiline
        ? <textarea ref={inputRef} rows={rows} maxLength={max} placeholder={placeholder} aria-label={label} value={memo[field] || ""} onChange={(event) => change(event.target.value)} />
        : <input type="text" maxLength={max} placeholder={placeholder} aria-label={label} value={memo[field] || ""} onChange={(event) => change(event.target.value)} />}
    </label>
  );
}

function Checkbox({ memo, field, label, hint }: { memo: Memo; field: FlagField; label: string; hint: string }) {
  const [, repaint] = useReducer((n: number) => n + 1, 0);
  return (
    <label className="crs-check">
      <input type="checkbox" checked={Boolean(memo[field])} onChange={(event) => { memo[field] = event.target.checked; repaint(); }} />
      <span><b>{label}</b>{hint ? <span className="tiny">{hint}</span> : null}</span>
    </label>
  );
}

interface Said {
  text: string;
  alert: boolean;
}

function SayLine({ said }: { said: Said }) {
  return <p className={said.alert ? "err" : "tiny"} role={said.alert ? "alert" : "status"} aria-live="polite">{said.text}</p>;
}

const QUIET: Said = { text: "", alert: false };
const alertOf = (text: string): Said => ({ text, alert: true });

function errorView(entry: Entry<unknown> | null, noun: string, loading: string, notFound: [string, string], failed: string, reload: () => void): ReactNode {
  if (!entry || !entry.error) return <LoadingState title={loading} />;
  if (missing(entry.error)) return <EmptyState title={notFound[0]} body={notFound[1]} />;
  return <ErrorState title={failed} body={boardMessage(entry.error, noun)} actions={<RetryButton reload={reload} />} />;
}

// ---------- Q&A list ----------

/** The course Q&A screen (`45_room_qna_list`) in backend mode. `parts` are course.tsx builders. */
export function remoteQnaBoard(screen: Screen, ctx: ScreenCtx, course: Course, parts: CourseParts): ReactNode {
  const student = roleIn(course.id) === "STUDENT";
  const write = student ? <Link href={hrefFor(find("45b_room_qna_insert_pop"), { kj: course.id })} className="btn btn-primary">질문 작성</Link> : null;
  return (
    <section className="crs" data-area="course">
      {parts.head("질의응답", "chat", null, write)}
      {qnaSection(ctx, course, screen.id)}
    </section>
  );
}

// ---------- Q&A detail, answers, answer form ----------

/** `?pop=qna&id=` (45d) and `?pop=qna-reply&id=` (45e, the answer form open) in backend mode. */
export function remoteQnaDialog(ctx: ScreenCtx, course: Course, name: string): ReactNode {
  const blocked = gate(course, QNA_LOGIN);
  const id = idParam(ctx);
  const courseId = courseIdFor(course.id);
  if (blocked || !id || courseId == null) {
    return wideShell("질의응답", <div className="crs-dialog">{blocked || <EmptyState title="질문을 찾을 수 없습니다" body="목록으로 돌아가 다시 선택해 주세요." />}</div>);
  }
  return wideShell("질의응답", <QnaDetail ctx={ctx} courseId={courseId} id={id} name={name} instructor={roleIn(course.id) === "INSTRUCTOR"} />);
}

/** Sends `read` for an unread post each time it is (re)loaded; returns the failure note. */
function useReadMark(kind: BoardKind, courseId: Id, id: number, post: BoardPost | null | undefined, noun?: string): string {
  const [note, setNote] = useState("");
  useEffect(() => {
    if (!post || post.read) return;
    let live = true;
    markPostRead(kind, courseId, id).catch((error: unknown) => {
      if (live) setNote(`읽음 표시를 남기지 못했습니다. ${boardMessage(error, noun)}`);
    });
    return () => {
      live = false;
    };
  }, [post, kind, courseId, id, noun]);
  return note;
}

function QnaDetail({ ctx, courseId, id, name, instructor }: { ctx: ScreenCtx; courseId: Id; id: number; name: string; instructor: boolean }) {
  const key = postKey(courseId, "qna", id);
  const load = () => void qnaDetails.load(key, courseId, id);
  const loadAnswers = () => void answerLists.load(key, courseId, id);
  const entry = useResource(qnaDetails, key);
  const answers = useResource(answerLists, key);
  useEffect(() => {
    const fresh = freshVisit("qna-detail", key) === "entry";
    if (fresh || !qnaDetails.peek(key)) void qnaDetails.load(key, courseId, id);
    if (fresh || !answerLists.peek(key)) void answerLists.load(key, courseId, id);
  });
  const post = entry && entry.data;
  const readNote = useReadMark("qna", courseId, id, post, "질문");
  return (
    <div className="crs-dialog">
      <div aria-live="polite">
        {post
          ? questionView(post, courseId, id, ctx, entry.error, instructor)
          : errorView(entry, "질문", "질문을 불러오는 중입니다", ["질문을 찾을 수 없습니다", "삭제되었거나 볼 수 있는 권한이 없습니다."], "질문을 불러오지 못했습니다", load)}
      </div>
      <p className="crs-note" role="status">{readNote}</p>
      <section className="crs-thread" aria-label="답글" hidden={!post}>
        {answersView(answers, courseId, id, key, loadAnswers)}
      </section>
      <div>
        {!post ? null : name === "qna-reply"
          ? <AnswerForm courseId={courseId} id={id} ctx={ctx} instructor={instructor} />
          : <div className="crs-form-actions"><Link href={here(ctx, { pop: "qna-reply" })} className="btn btn-primary">답글 쓰기</Link></div>}
      </div>
    </div>
  );
}

function questionView(post: BoardPost, courseId: Id, id: number, ctx: ScreenCtx, staleError: unknown, instructor: boolean): ReactNode {
  const actions: ReactNode[] = [
    <CmdButton
      key="like"
      kind="secondary"
      op={`like:${courseId}:qna:${id}`}
      pressed={Boolean(post.liked)}
      run={async () => {
        try {
          await setLike("qna", courseId, id, !post.liked);
        } catch (error) {
          toast(boardMessage(error, "질문"));
        }
      }}
    >
      {post.liked ? `추천 취소 · ${post.likeCount}` : `추천 · ${post.likeCount}`}
    </CmdButton>,
  ];
  if (post.canSetSolved) {
    actions.push(
      <CmdButton
        key="solved"
        kind="secondary"
        op={`status:${courseId}:${id}`}
        busyText="바꾸는 중…"
        run={async () => {
          try {
            await setQuestionStatus(courseId, id, { solved: !post.solved });
            toast(post.solved ? "미해결로 되돌렸습니다" : "해결로 표시했습니다");
          } catch (error) {
            toast(boardMessage(error, "질문"));
          }
        }}
      >
        {post.solved ? "미해결로 되돌리기" : "해결로 표시"}
      </CmdButton>,
    );
  }
  if (post.canSetFaq) {
    actions.push(
      <CmdButton
        key="faq"
        kind="secondary"
        op={`status:${courseId}:${id}`}
        busyText="바꾸는 중…"
        run={async () => {
          try {
            await setQuestionStatus(courseId, id, { faq: !post.faq });
            toast(post.faq ? "FAQ에서 뺐습니다" : "FAQ로 지정했습니다");
          } catch (error) {
            toast(boardMessage(error, "질문"));
          }
        }}
      >
        {post.faq ? "FAQ 해제" : "FAQ로 지정"}
      </CmdButton>,
    );
  }
  if (editable(post)) actions.push(<Link key="edit" href={here(ctx, { pop: "qna-write" })} className="btn btn-secondary">수정</Link>);
  if (deletable(post, instructor)) {
    actions.push(
      <DeleteButton
        key="delete"
        label={post.title}
        op={`delete:${courseId}:qna:${id}`}
        work={async () => {
          try {
            await deletePost("qna", courseId, id);
            getState().toast = "질문을 삭제했습니다";
            closeDialog();
          } catch (error) {
            if (missing(error)) {
              getState().toast = "이미 삭제되었거나 찾을 수 없는 질문입니다";
              closeDialog();
              return;
            }
            toast(boardMessage(error, "질문"));
            if (isCode(error, 409, "already_answered")) void qnaDetails.load(postKey(courseId, "qna", id), courseId, id);
          }
        }}
      />,
    );
  }
  const locked = post.mine && !post.canEdit && (post.answerCount ?? 0) > 0;
  const pairs: [string, string][] = [
    ["작성자", authorText(post) || "—"],
    ["작성일", koDateTime(post.createdAt)],
    ...(post.edited === true ? [["수정", "수정됨"] as [string, string]] : []),
    ["조회 · 추천 · 답글", `${post.viewCount} · ${post.likeCount} · ${post.answerCount ?? 0}`],
  ];
  return (
    <article className="crs-question">
      <h3 className="crs-dialog-title">{post.title}</h3>
      <div className="crs-post-meta">{statusTag(post)}{post.secret ? kindTag("비공개") : null}{post.anonymous ? kindTag("익명") : null}</div>
      {staleError ? <p className="err" role="alert">{boardMessage(staleError, "질문")}</p> : null}
      <dl className="crs-meta pairs">{pairs.flatMap(([label, value]) => [<dt key={`${label}-t`}>{label}</dt>, <dd key={`${label}-d`}>{value}</dd>])}</dl>
      {post.secret ? <p className="crs-note">비공개 질문입니다. 작성자와 담당 교수만 봅니다.</p> : null}
      {prose(post.body)}
      {files(courseId, post.attachments)}
      <div className="crs-post-actions">{actions}</div>
      {locked ? <p className="crs-note">답글이 달린 질문은 고치거나 지울 수 없습니다.</p> : null}
    </article>
  );
}

function answersView(entry: Entry<CursorList<BoardAnswer>> | null, courseId: Id, id: number, key: string, reload: () => void): ReactNode {
  const data = entry && entry.data;
  const title = <h3>{data ? `답글 ${data.items.length}${data.nextCursor != null ? "+" : ""}` : "답글"}</h3>;
  if (!data) {
    if (!entry || !entry.error) return <>{title}<LoadingState title="답글을 불러오는 중입니다" /></>;
    if (missing(entry.error)) return null;
    return <>{title}<ErrorState title="답글을 불러오지 못했습니다" body={boardMessage(entry.error, "답글")} actions={<RetryButton reload={reload} />} /></>;
  }
  return (
    <>
      {title}
      {entry.error ? <p className="err" role="alert">{boardMessage(entry.error, "답글")}</p> : null}
      {data.items.length ? null : <p className="crs-note">아직 답글이 없습니다.</p>}
      {data.items.map((answer) => <AnswerView key={answer.answerId} answer={answer} courseId={courseId} id={id} listKey={key} />)}
      {data.nextCursor != null ? (
        <CmdButton
          kind="ghost"
          op={`more:${key}`}
          busyText="불러오는 중…"
          run={async () => {
            try {
              await moreAnswers(courseId, id);
            } catch (error) {
              toast(boardMessage(error, "답글"));
            }
          }}
        >
          {isAppending(key) ? "불러오는 중…" : "답글 더 보기"}
        </CmdButton>
      ) : null}
    </>
  );
}

function AnswerView({ answer, courseId, id, listKey }: { answer: BoardAnswer; courseId: Id; id: number; listKey: string }) {
  const author = answer.authorName ? (answer.anonymous ? `${answer.authorName} (익명)` : answer.authorName) : answer.anonymous ? "익명" : "";
  const editingThis = editing.get(listKey) === answer.answerId && Boolean(answer.canEdit);
  const actions: ReactNode[] = [];
  if (answer.canEdit && !editingThis) {
    actions.push(
      <Button
        key="edit"
        kind="secondary"
        aria-label="답글 수정"
        onClick={() => {
          editing.set(listKey, answer.answerId);
          const memoKey = `answer-edit:${listKey}:${answer.answerId}`;
          memos.delete(memoKey);
          const memo = memoOf(memoKey);
          memo.body = answer.body || "";
          memo.loaded = true;
          render();
        }}
      >
        수정
      </Button>,
    );
  }
  if (answer.canDelete) {
    actions.push(
      <DeleteButton
        key="delete"
        label="답글"
        op={`delete-answer:${listKey}:${answer.answerId}`}
        work={async () => {
          try {
            await deleteAnswer(courseId, id, answer.answerId);
            toast("답글을 삭제했습니다");
          } catch (error) {
            toast(missing(error) ? "이미 삭제되었거나 찾을 수 없는 답글입니다" : boardMessage(error, "답글"));
          }
        }}
      />,
    );
  }
  return (
    <article className={`crs-answer${answer.instructor ? " is-instructor" : ""}`}>
      <div className="crs-post-meta">
        {answer.instructor ? kindTag("담당 교수", " crs-kind-faq") : null}
        {author ? <b>{author}</b> : null}
        <span>{koDateTime(answer.createdAt)}</span>
        {answer.edited === true ? <span>수정됨</span> : null}
      </div>
      {editingThis ? <AnswerEditForm answer={answer} courseId={courseId} id={id} listKey={listKey} /> : prose(answer.body, "")}
      {editingThis ? null : files(courseId, answer.attachments)}
      {actions.length ? <div className="crs-post-actions">{actions}</div> : null}
    </article>
  );
}

/** Inline answer edit: text plus keep/remove of its files and new ones. */
function AnswerEditForm({ answer, courseId, id, listKey }: { answer: BoardAnswer; courseId: Id; id: number; listKey: string }) {
  const memoKey = `answer-edit:${listKey}:${answer.answerId}`;
  const memo = memoOf(memoKey);
  const [, repaint] = useReducer((n: number) => n + 1, 0);
  // The message survives the re-render that a 409 reload causes.
  const tell = (text: string) => {
    memo.notice = text;
    repaint();
  };
  const picker = boardPicker(memo, courseId, existingOf(answer.attachments), repaint);
  const save = async () => {
    memo.notice = "";
    if (!memo.body.trim()) return tell("답글 내용을 입력하세요.");
    if (picker.pending()) return tell("첨부 파일 검사가 끝난 뒤 저장하세요.");
    try {
      await updateAnswer(courseId, id, answer.answerId, { body: memo.body, anonymous: Boolean(answer.anonymous), version: answer.version, ...attachmentChange(picker) });
      editing.delete(listKey);
      memos.delete(memoKey);
      toast("답글을 고쳤습니다");
    } catch (error) {
      tell(boardMessage(error, "답글"));
      if (isCode(error, 409, "attachment_not_ready")) picker.recheck();
      if (isCode(error, 409) || missing(error)) void answerLists.load(listKey, courseId, id);
    }
    return undefined;
  };
  return (
    <div className="crs-form">
      <TextInput memo={memo} field="body" label="답글" multiline rows={5} max={MAX_BODY} />
      <div className="crs-attach">{picker.node}</div>
      <SayLine said={memo.notice ? alertOf(memo.notice) : QUIET} />
      <div className="crs-post-actions">
        <Button
          kind="ghost"
          onClick={() => {
            editing.delete(listKey);
            memos.delete(memoKey);
            render();
          }}
        >
          취소
        </Button>
        <CmdButton kind="primary" op={memoKey} busyText="저장하는 중…" disabled={picker.pending()} run={save}>저장</CmdButton>
      </div>
    </div>
  );
}

function AnswerForm({ courseId, id, ctx, instructor }: { courseId: Id; id: number; ctx: ScreenCtx; instructor: boolean }) {
  const key = `answer:${postKey(courseId, "qna", id)}`;
  const memo = memoOf(key);
  const [said, say] = useState<Said>(QUIET);
  const [, repaint] = useReducer((n: number) => n + 1, 0);
  const picker = boardPicker(memo, courseId, [], repaint);
  const submit = async () => {
    if (!memo.body.trim()) return say(alertOf("답글 내용을 입력하세요."));
    if (picker.pending()) return say(alertOf("첨부 파일 검사가 끝난 뒤 등록하세요."));
    try {
      await createAnswer(courseId, id, { body: memo.body, anonymous: instructor ? false : Boolean(memo.anonymous), uploadIds: picker.uploadIds() });
      memos.delete(key);
      getState().toast = "답글을 등록했습니다";
      navigate(here(ctx, { pop: "qna" }));
    } catch (error) {
      if (isCode(error, 409, "attachment_not_ready")) picker.recheck();
      if (createUncertain(`answer:${courseId}:${id}`)) say(alertOf("등록 결과를 확인하지 못했습니다. 네트워크를 확인한 뒤 같은 내용으로 다시 등록하세요. 이미 등록되었다면 같은 답글을 돌려받습니다."));
      else say(alertOf(boardMessage(error, "답글")));
    }
    return undefined;
  };
  return (
    <section className="crs-form" aria-label="답글 쓰기">
      <h3>답글 쓰기</h3>
      <TextInput memo={memo} field="body" label="답글" multiline rows={6} max={MAX_BODY} placeholder="답글을 입력하세요" />
      {instructor ? null : <Checkbox memo={memo} field="anonymous" label="익명" hint="다른 학생에게 이름을 숨깁니다. 담당 교수에게는 보입니다." />}
      <div className="crs-attach">{picker.node}</div>
      <SayLine said={said} />
      <div className="crs-post-actions">
        <Link href={here(ctx, { pop: "qna" })} className="btn btn-ghost">취소</Link>
        <CmdButton kind="primary" op={`create:${key}`} busyText="등록하는 중…" disabled={picker.pending()} run={submit}>답글 등록</CmdButton>
      </div>
    </section>
  );
}

// ---------- question write / edit (45b), open board write / edit ----------

/** `?pop=qna-write` (new) and `?pop=qna-write&id=` (edit) in backend mode. */
export function remoteQnaWriteDialog(ctx: ScreenCtx, course: Course): ReactNode {
  const id = idParam(ctx);
  const title = id ? "질문 수정" : "질문 작성";
  const blocked = gate(course, QNA_LOGIN);
  const courseId = courseIdFor(course.id);
  if (blocked || courseId == null) return wideShell(title, <div className="crs-dialog">{blocked}</div>);
  if (!id && roleIn(course.id) !== "STUDENT") {
    return wideShell(title, <div className="crs-dialog"><EmptyState title="질문은 수강생만 쓸 수 있습니다" body="담당 교수는 질문 상세에서 답글을 씁니다." /></div>);
  }
  return wideShell(title, <PostForm key={`qna:${courseId}:${id || "new"}`} ctx={ctx} courseId={courseId} kind="qna" id={id} noun="질문" />);
}

/** `?pop=board-write` (new) and `?pop=board-write&id=` (edit) in backend mode. */
export function remoteOpenWriteDialog(ctx: ScreenCtx, course: Course): ReactNode {
  const id = idParam(ctx);
  const title = id ? "글 수정" : "글쓰기";
  const blocked = gate(course, OPEN_LOGIN);
  const courseId = courseIdFor(course.id);
  if (blocked || courseId == null) return wideShell(title, <div className="crs-dialog">{blocked}</div>);
  return wideShell(title, <PostForm key={`open:${courseId}:${id || "new"}`} ctx={ctx} courseId={courseId} kind="open" id={id} noun="글" />);
}

function PostForm({ ctx, courseId, kind, id, noun }: { ctx: ScreenCtx; courseId: Id; kind: BoardKind; id: number | null; noun: string }) {
  const qna = kind === "qna";
  const details = qna ? qnaDetails : openDetails;
  const memoKey = `${kind}-form:${courseId}:${id || "new"}`;
  const memo = memoOf(memoKey);
  const viewPop = qna ? "qna" : "board-post";
  const key = id ? postKey(courseId, kind, id) : null;
  const [said, say] = useState<Said>(QUIET);
  const [, repaint] = useReducer((n: number) => n + 1, 0);
  const entry = useResource(details, key);
  // The post being edited, as last read (a failed reload keeps it).
  const last = useRef<BoardPost | null>(null);
  if (entry && entry.data) last.current = entry.data;
  const current = last.current;
  useEffect(() => {
    // The edit form always starts from the latest version.
    if (key && id && (freshVisit(`${kind}-edit`, key) === "entry" || !details.peek(key))) void details.load(key, courseId, id);
  });
  if (current && !memo.loaded) {
    // Typed text survives re-renders and 409 reloads; the server copy only fills an untouched form.
    Object.assign(memo, { title: current.title || "", body: current.body || "", anonymous: Boolean(current.anonymous), secret: Boolean(current.secret), loaded: true });
  }
  const picker = boardPicker(memo, courseId, id ? existingOf(current ? current.attachments : []) : [], repaint);

  if (id && !current) {
    const reload = () => {
      if (key) void details.load(key, courseId, id);
    };
    return (
      <div className="crs-dialog crs-form">
        {errorView(entry, noun, `${noun}을 불러오는 중입니다`, [`${noun}을 찾을 수 없습니다`, "삭제되었거나 고칠 수 있는 권한이 없습니다."], `${noun}을 불러오지 못했습니다`, reload)}
      </div>
    );
  }

  // 주소로 바로 연 남의 글: 고칠 양식을 내지 않는다. 내 글이 잠긴 경우(답글이 달린 질문)는 아래 양식에 이유를 적는다.
  if (id && current && !current.mine) {
    return (
      <div className="crs-dialog">
        <EmptyState
          title={`다른 사람이 쓴 ${noun}은 고칠 수 없습니다`}
          body="작성자 본인만 고칠 수 있습니다."
          actions={<Link href={here(ctx, { pop: viewPop })} className="btn btn-secondary">{`${noun} 보기`}</Link>}
        />
      </div>
    );
  }

  const save = async () => {
    const title = memo.title.trim();
    if (!title) return say(alertOf("제목을 입력하세요."));
    if (picker.pending()) return say(alertOf("첨부 파일 검사가 끝난 뒤 저장하세요."));
    const text = { title, body: memo.body.trim() ? memo.body : "", ...(qna ? { anonymous: Boolean(memo.anonymous), secret: Boolean(memo.secret) } : {}) };
    try {
      let post: BoardPost;
      if (id) {
        if (!current) return say(alertOf(`${noun}을 불러온 뒤 저장하세요.`));
        post = await updatePost(kind, courseId, id, { ...text, version: current.version, ...attachmentChange(picker) });
      } else {
        const payload = { ...text, uploadIds: picker.uploadIds() };
        post = qna ? await createQuestion(courseId, payload) : await createOpenPost(courseId, payload);
      }
      memos.delete(memoKey);
      getState().toast = id ? `${noun}을 고쳤습니다` : `${noun}을 등록했습니다`;
      navigate(here(ctx, { pop: viewPop, id: String(post.postId) }));
    } catch (error) {
      if (id && key && (isCode(error, 409) || missing(error))) void details.load(key, courseId, id);
      if (isCode(error, 409, "attachment_not_ready")) picker.recheck();
      const op = qna ? `qna:${courseId}` : `open:${courseId}`;
      if (!id && createUncertain(op)) say(alertOf("저장 결과를 확인하지 못했습니다. 네트워크를 확인한 뒤 같은 내용으로 다시 저장하세요. 이미 등록되었다면 같은 글을 돌려받습니다."));
      else say(alertOf(boardMessage(error, noun)));
    }
    return undefined;
  };

  const locked = Boolean(id && current && !editable(current));
  let shown = said;
  if (!shown.text && current && entry && !entry.data && entry.error) shown = alertOf(boardMessage(entry.error, noun));
  if (!shown.text && locked && current) shown = alertOf(qna && (current.answerCount ?? 0) > 0 ? "답글이 달린 질문은 고칠 수 없습니다." : `이 ${noun}을 고칠 수 없습니다.`);
  return (
    <div className="crs-dialog crs-form">
      <TextInput memo={memo} field="title" label="제목" max={MAX_TITLE} placeholder="제목을 입력하세요" />
      <TextInput memo={memo} field="body" label="내용" multiline rows={10} max={MAX_BODY} placeholder="내용을 입력하세요" />
      {qna ? <Checkbox memo={memo} field="anonymous" label="익명" hint="다른 학생에게 이름을 숨깁니다. 담당 교수에게는 보입니다." /> : null}
      {qna ? <Checkbox memo={memo} field="secret" label="비공개" hint="작성자와 담당 교수만 이 질문을 봅니다. 다른 학생의 목록과 검색에도 나오지 않습니다." /> : null}
      <div><div className="crs-attach">{picker.node}</div></div>
      <SayLine said={shown} />
      <div className="crs-post-actions">
        <Button kind="ghost" onClick={closeDialog}>취소</Button>
        <CmdButton kind="primary" op={`save:${memoKey}`} busyText="저장하는 중…" disabled={picker.pending() || locked} run={save}>저장</CmdButton>
      </div>
    </div>
  );
}

// ---------- open board list ----------

/** The open board screen (`47_room_open_board_list`) in backend mode. */
export function remoteOpenBoard(screen: Screen, ctx: ScreenCtx, course: Course, parts: CourseParts): ReactNode {
  const blocked = gate(course, OPEN_LOGIN);
  const courseId = courseIdFor(course.id);
  if (blocked || courseId == null) return <section className="crs" data-area="course">{parts.head("열린게시판", "layers", null)}{blocked}</section>;
  return <OpenBoard screen={screen} ctx={ctx} kj={course.id} courseId={courseId} parts={parts} />;
}

function OpenBoard({ screen, ctx, kj, courseId, parts }: { screen: Screen; ctx: ScreenCtx; kj: string; courseId: Id; parts: CourseParts }) {
  const q = readQuery(ctx.state, parts.scope).trim().slice(0, 100);
  const key = openListKey(courseId, q);
  const load = () => void openLists.load(key, courseId, q);
  // On focus, re-read only a list still on its first page; a longer one keeps what was read.
  const entry = useResource(openLists, key, () => {
    const got = openLists.peek(key);
    if (!got || !got.data || got.data.pages === 1) load();
  });
  useEffect(() => visitList(openLists, "course-open", key, () => void openLists.load(key, courseId, q)));
  const itemHref = (postId: number) => hrefFor(find(screen.id), { kj, pop: "board-post", id: String(postId) });
  const data = entry && entry.data;
  let body: ReactNode;
  if (!data) {
    if (!entry || !entry.error) body = <LoadingState title="글을 불러오는 중입니다" />;
    else if (missing(entry.error)) body = <EmptyState title="열린게시판을 볼 수 없습니다" body="이 과목의 구성원이 아니거나 과목을 찾을 수 없습니다." />;
    else body = <ErrorState title="글을 불러오지 못했습니다" body={boardMessage(entry.error)} actions={<RetryButton reload={load} />} />;
  } else {
    const stale = entry.error ? <p className="err" role="alert">{boardMessage(entry.error)}</p> : null;
    body = !data.items.length ? (
      <>
        {stale}
        {q ? <EmptyState title="검색 결과가 없습니다" body="제목에 들어 있는 다른 단어로 찾아 보세요." /> : <EmptyState title="조회된 자료가 없습니다." body="첫 글을 남기면 이 목록에 표시됩니다." />}
      </>
    ) : (
      <>
        {stale}
        <div className="crs-posts">{data.items.map((post) => <Slot key={post.postId}>{postCard(post, itemHref(post.postId), false)}</Slot>)}</div>
        {data.nextCursor != null ? (
          <div className="crs-form-actions">
            <CmdButton
              kind="secondary"
              op={`more:${key}`}
              busyText="불러오는 중…"
              run={async () => {
                try {
                  await moreOpenPosts(courseId, q);
                } catch (error) {
                  toast(boardMessage(error));
                }
              }}
            >
              {isAppending(key) ? "불러오는 중…" : "더 보기"}
            </CmdButton>
          </div>
        ) : null}
      </>
    );
  }
  return (
    <section className="crs" data-area="course">
      {parts.head(
        "열린게시판",
        "layers",
        data ? <Badge tone="neutral">{String(data.total ?? data.items.length)}</Badge> : null,
        <Link key="write" href={hrefFor(find("47b_room_open_board_insert_pop"), { kj })} className="btn btn-primary">글쓰기</Link>,
      )}
      <div className="crs-toolbar">
        <Tabs items={[{ label: "전체", on: true }]} />
        {parts.searchBox("열린게시판 제목 검색", ctx, "open")}
      </div>
      <div className="crs-remote" aria-live="polite">{body}</div>
    </section>
  );
}

/** A keyed slot for a ReactNode in a list. */
function Slot({ children }: { children: ReactNode }) {
  return <>{children}</>;
}

// ---------- open board post, comments ----------

/** `?pop=board-post&id=` over the open board in backend mode. */
export function remoteOpenPostDialog(ctx: ScreenCtx, course: Course): ReactNode {
  const blocked = gate(course, OPEN_LOGIN);
  const id = idParam(ctx);
  const courseId = courseIdFor(course.id);
  if (blocked || !id || courseId == null) {
    return wideShell("열린게시판", <div className="crs-dialog">{blocked || <EmptyState title="글을 찾을 수 없습니다" body="목록으로 돌아가 다시 선택해 주세요." />}</div>);
  }
  return wideShell("열린게시판", <OpenPost ctx={ctx} courseId={courseId} id={id} instructor={roleIn(course.id) === "INSTRUCTOR"} />);
}

function OpenPost({ ctx, courseId, id, instructor }: { ctx: ScreenCtx; courseId: Id; id: number; instructor: boolean }) {
  const key = postKey(courseId, "open", id);
  const load = () => void openDetails.load(key, courseId, id);
  const loadComments = () => void commentLists.load(key, courseId, id);
  const entry = useResource(openDetails, key);
  const comments = useResource(commentLists, key);
  useEffect(() => {
    const fresh = freshVisit("open-detail", key) === "entry";
    if (fresh || !openDetails.peek(key)) void openDetails.load(key, courseId, id);
    if (fresh || !commentLists.peek(key)) void commentLists.load(key, courseId, id);
  });
  const post = entry && entry.data;
  const readNote = useReadMark("open", courseId, id, post);
  return (
    <div className="crs-dialog">
      <div aria-live="polite">
        {post
          ? openPostView(post, courseId, id, ctx, entry.error, instructor)
          : errorView(entry, "글", "글을 불러오는 중입니다", ["글을 찾을 수 없습니다", "삭제되었거나 볼 수 있는 권한이 없습니다."], "글을 불러오지 못했습니다", load)}
      </div>
      <p className="crs-note" role="status">{readNote}</p>
      <section className="crs-thread" aria-label="댓글" hidden={!post}>
        <CommentsThread entry={comments} courseId={courseId} id={id} listKey={key} reload={loadComments} instructor={instructor} />
      </section>
    </div>
  );
}

function openPostView(post: BoardPost, courseId: Id, id: number, ctx: ScreenCtx, staleError: unknown, instructor: boolean): ReactNode {
  const actions: ReactNode[] = [
    <CmdButton
      key="like"
      kind="secondary"
      op={`like:${courseId}:open:${id}`}
      pressed={Boolean(post.liked)}
      run={async () => {
        try {
          await setLike("open", courseId, id, !post.liked);
        } catch (error) {
          toast(boardMessage(error));
        }
      }}
    >
      {post.liked ? `추천 취소 · ${post.likeCount}` : `추천 · ${post.likeCount}`}
    </CmdButton>,
  ];
  if (editable(post)) actions.push(<Link key="edit" href={here(ctx, { pop: "board-write" })} className="btn btn-secondary">수정</Link>);
  if (deletable(post, instructor)) {
    actions.push(
      <DeleteButton
        key="delete"
        label={post.title}
        op={`delete:${courseId}:open:${id}`}
        work={async () => {
          try {
            await deletePost("open", courseId, id);
            getState().toast = "글을 삭제했습니다";
            closeDialog();
          } catch (error) {
            if (missing(error)) {
              getState().toast = "이미 삭제되었거나 찾을 수 없는 글입니다";
              closeDialog();
              return;
            }
            toast(boardMessage(error));
          }
        }}
      />,
    );
  }
  const pairs: [string, string][] = [
    ["작성자", post.authorName || "—"],
    ["작성일", koDateTime(post.createdAt)],
    ...(post.edited === true ? [["수정", "수정됨"] as [string, string]] : []),
    ["조회 · 추천 · 댓글", `${post.viewCount} · ${post.likeCount} · ${post.commentCount ?? 0}`],
  ];
  return (
    <article className="crs-question">
      <h3 className="crs-dialog-title">{post.title}</h3>
      {staleError ? <p className="err" role="alert">{boardMessage(staleError)}</p> : null}
      <dl className="crs-meta pairs">{pairs.flatMap(([label, value]) => [<dt key={`${label}-t`}>{label}</dt>, <dd key={`${label}-d`}>{value}</dd>])}</dl>
      {prose(post.body)}
      {files(courseId, post.attachments)}
      <div className="crs-post-actions">{actions}</div>
    </article>
  );
}

function CommentsThread({ entry, courseId, id, listKey, reload, instructor }: { entry: Entry<CursorList<BoardComment>> | null; courseId: Id; id: number; listKey: string; reload: () => void; instructor: boolean }) {
  const data = entry && entry.data;
  const title = <h3>댓글</h3>;
  if (!data) {
    if (!entry || !entry.error) return <>{title}<LoadingState title="댓글을 불러오는 중입니다" /></>;
    if (missing(entry.error)) return null;
    return <>{title}<ErrorState title="댓글을 불러오지 못했습니다" body={boardMessage(entry.error, "댓글")} actions={<RetryButton reload={reload} />} /></>;
  }
  const memo = memoOf(`comment:${listKey}`);
  const tops = data.items.filter((item) => item.parentCommentId == null);
  const topIds = new Set(tops.map((item) => item.commentId));
  const replies = new Map<number, BoardComment[]>();
  const orphans: BoardComment[] = [];
  for (const item of data.items) {
    if (item.parentCommentId == null) continue;
    if (!topIds.has(item.parentCommentId)) orphans.push(item);
    else replies.set(item.parentCommentId, [...(replies.get(item.parentCommentId) || []), item]);
  }
  const view = (comment: BoardComment) => <CommentView comment={comment} courseId={courseId} id={id} listKey={listKey} memo={memo} instructor={instructor} />;
  return (
    <>
      {title}
      {entry.error ? <p className="err" role="alert">{boardMessage(entry.error, "댓글")}</p> : null}
      {data.items.length ? (
        <ul className="crs-comments">
          {tops.map((top) => {
            const children = replies.get(top.commentId) || [];
            return (
              <li key={top.commentId}>
                {view(top)}
                {children.length ? <ul className="crs-comments crs-replies">{children.map((reply) => <li key={reply.commentId}>{view(reply)}</li>)}</ul> : null}
              </li>
            );
          })}
          {orphans.map((orphan) => <li key={orphan.commentId}>{view(orphan)}</li>)}
        </ul>
      ) : <p className="crs-note">아직 댓글이 없습니다.</p>}
      {data.nextCursor != null ? (
        <CmdButton
          kind="ghost"
          op={`more:${listKey}`}
          busyText="불러오는 중…"
          run={async () => {
            try {
              await moreComments(courseId, id);
            } catch (error) {
              toast(boardMessage(error, "댓글"));
            }
          }}
        >
          {isAppending(listKey) ? "불러오는 중…" : "댓글 더 보기"}
        </CmdButton>
      ) : null}
      <CommentForm courseId={courseId} id={id} listKey={listKey} memo={memo} items={data.items} />
    </>
  );
}

function CommentView({ comment, courseId, id, listKey, memo, instructor }: { comment: BoardComment; courseId: Id; id: number; listKey: string; memo: Memo; instructor: boolean }) {
  const editButton = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (refocusComment === comment.commentId && editButton.current) {
      refocusComment = null;
      editButton.current.focus();
    }
  });
  if (comment.deleted) {
    return <div className="crs-comment is-deleted"><p className="crs-note">삭제된 댓글입니다.</p></div>;
  }
  const editingThis = editable(comment) && commentEditing.get(listKey) === comment.commentId;
  const actions: ReactNode[] = [];
  if (comment.parentCommentId == null && !editingThis) {
    actions.push(
      <Button
        key="reply"
        kind="ghost"
        aria-label="이 댓글에 답글 달기"
        onClick={() => {
          memo.parent = comment.commentId;
          memo.focus = true;
          render();
        }}
      >
        답글
      </Button>,
    );
  }
  // Only the author edits a comment; an instructor may delete it but not change it.
  if (editable(comment) && !editingThis) {
    actions.push(
      <Button
        key="edit"
        ref={editButton}
        kind="ghost"
        aria-label="댓글 수정"
        data-comment-edit={String(comment.commentId)}
        onClick={() => {
          const memoKey = `comment-edit:${listKey}:${comment.commentId}`;
          memos.delete(memoKey);
          Object.assign(memoOf(memoKey), { body: comment.body || "", loaded: true, focus: true });
          commentEditing.set(listKey, comment.commentId);
          render();
        }}
      >
        수정
      </Button>,
    );
  }
  if (deletable(comment, instructor) && !editingThis) {
    actions.push(
      <DeleteButton
        key="delete"
        label="댓글"
        op={`delete-comment:${listKey}:${comment.commentId}`}
        work={async () => {
          try {
            await deleteComment(courseId, id, comment.commentId);
            if (memo.parent === comment.commentId) memo.parent = null;
            toast("댓글을 삭제했습니다");
          } catch (error) {
            toast(missing(error) ? "이미 삭제되었거나 찾을 수 없는 댓글입니다" : boardMessage(error, "댓글"));
          }
        }}
      />,
    );
  }
  // 서버가 준 `edited`(작성 뒤 본문을 고친 적이 있는지)로만 정한다. 지운 댓글에는 없다.
  const edited = comment.edited === true;
  return (
    <div className="crs-comment">
      <div className="crs-post-meta">
        {comment.authorName ? <b>{comment.authorName}</b> : null}
        {comment.mine ? kindTag("내 댓글") : null}
        <span>{koDateTime(comment.createdAt)}</span>
        {edited ? <span className="crs-edited">{comment.updatedAt ? `수정됨 ${koDateTime(comment.updatedAt)}` : "수정됨"}</span> : null}
      </div>
      {editingThis ? <CommentEditForm comment={comment} courseId={courseId} id={id} listKey={listKey} /> : prose(comment.body, "")}
      {actions.length ? <div className="crs-post-actions">{actions}</div> : null}
    </div>
  );
}

function byteLine(memo: Memo): ReactNode {
  return <span className="tiny" aria-live="polite">{`${legacyBytes(memo.body.trim())} / ${COMMENT_BYTES} byte`}</span>;
}

/** Focuses `ref` once when `memo.focus` is set. */
function useMemoFocus(memo: Memo, ref: React.RefObject<HTMLTextAreaElement | null>) {
  useEffect(() => {
    if (memo.focus && ref.current) {
      memo.focus = false;
      ref.current.focus();
    }
  });
}

/** Inline comment edit by its author: `{ body, version }`, the same byte rule as a new comment. */
function CommentEditForm({ comment, courseId, id, listKey }: { comment: BoardComment; courseId: Id; id: number; listKey: string }) {
  const memoKey = `comment-edit:${listKey}:${comment.commentId}`;
  const memo = memoOf(memoKey);
  const [, repaint] = useReducer((n: number) => n + 1, 0);
  const field = useRef<HTMLTextAreaElement>(null);
  useMemoFocus(memo, field);
  const tell = (text: string) => {
    memo.notice = text;
    repaint();
  };
  const close = () => {
    commentEditing.delete(listKey);
    memos.delete(memoKey);
    refocusComment = comment.commentId;
    render();
  };
  const save = async () => {
    memo.notice = "";
    const text = memo.body.trim();
    if (!text) return tell("댓글 내용을 입력하세요.");
    if (legacyBytes(text) > COMMENT_BYTES) return tell(`댓글은 ${COMMENT_BYTES} byte까지 쓸 수 있습니다.`);
    try {
      await updateComment(courseId, id, comment.commentId, { body: text, version: comment.version });
      toast("댓글을 고쳤습니다");
      close();
    } catch (error) {
      tell(boardMessage(error, "댓글"));
      // Stale or gone: read the thread again; the typed text stays for the next save.
      if (isCode(error, 409, "version_conflict") || missing(error)) void commentLists.load(listKey, courseId, id);
    }
    return undefined;
  };
  return (
    <div className="crs-form">
      <TextInput memo={memo} field="body" label="댓글 수정" multiline rows={3} onInput={repaint} inputRef={field} />
      {byteLine(memo)}
      <SayLine said={memo.notice ? alertOf(memo.notice) : QUIET} />
      <div className="crs-post-actions">
        <Button kind="ghost" onClick={close}>취소</Button>
        <CmdButton kind="primary" op={memoKey} busyText="저장하는 중…" run={save}>저장</CmdButton>
      </div>
    </div>
  );
}

function CommentForm({ courseId, id, listKey, memo, items }: { courseId: Id; id: number; listKey: string; memo: Memo; items: BoardComment[] }) {
  const [said, say] = useState<Said>(QUIET);
  const [, repaint] = useReducer((n: number) => n + 1, 0);
  const field = useRef<HTMLTextAreaElement>(null);
  useMemoFocus(memo, field);
  const parent = memo.parent != null ? items.find((item) => item.commentId === memo.parent && !item.deleted) : null;
  if (memo.parent != null && !parent) memo.parent = null;
  const submit = async () => {
    const text = memo.body.trim();
    if (!text) return say(alertOf("댓글 내용을 입력하세요."));
    if (legacyBytes(text) > COMMENT_BYTES) return say(alertOf(`댓글은 ${COMMENT_BYTES} byte까지 쓸 수 있습니다.`));
    try {
      await createComment(courseId, id, { body: text, parentCommentId: memo.parent != null ? memo.parent : null });
      memo.body = "";
      memo.parent = null;
      say(QUIET);
      toast("댓글을 등록했습니다");
    } catch (error) {
      if (createUncertain(`comment:${courseId}:${id}`)) say(alertOf("등록 결과를 확인하지 못했습니다. 같은 내용으로 다시 등록하면 이미 등록된 댓글을 돌려받습니다."));
      else say(alertOf(boardMessage(error, "댓글")));
    }
    return undefined;
  };
  return (
    <section className="crs-form" aria-label="댓글 쓰기">
      {parent ? (
        <p className="crs-note">
          {`${parent.authorName || "댓글"}에게 답글을 답니다. `}
          <Button kind="ghost" onClick={() => { memo.parent = null; render(); }}>답글 취소</Button>
        </p>
      ) : null}
      <TextInput memo={memo} field="body" label={memo.parent ? "답글" : "댓글"} multiline rows={3} onInput={repaint} inputRef={field} />
      {byteLine(memo)}
      <SayLine said={said} />
      <div className="crs-post-actions">
        <CmdButton kind="primary" op={`create:comment:${listKey}`} busyText="등록하는 중…" run={submit}>등록</CmdButton>
      </div>
    </section>
  );
}
