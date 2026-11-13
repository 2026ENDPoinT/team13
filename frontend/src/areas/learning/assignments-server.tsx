// Student assignment detail and submit dialog from the ASTRA backend (`?m=report&n=srv-<id>`).
// The phase, deadline and receipt time come from the server clock. A score and feedback appear
// only when the response carries `grade` (after release); nothing hints at a hidden score.
// Typed text and picked attachments live in memory per assignment until a submission succeeds.
import { Fragment, useEffect, useReducer, useRef, useState, type ChangeEvent, type ReactNode } from "react";
import { Badge, Button, Dialog, EmptyState, ErrorState, LoadingState } from "../../ui/kit.tsx";
import { Icon } from "../../ui/icon.tsx";
import { Link, closeDialog } from "../../router.tsx";
import { toast } from "../../store.ts";
import { ApiError } from "../../shared/api.ts";
import { backendStatus, backendSettled, connect as connectBackend, courseIdFor, subscribe } from "../../shared/backend.ts";
import { backendPanel } from "../../shared/backend-prompt.tsx";
import { useResource } from "../../shared/remote-cache.ts";
import { koDateTime, ddayAt } from "../../shared/home-remote.ts";
import {
  assignmentDetails, assignmentKey, saveDraft, submitAssignment, submitUncertain, assignmentMessage, isConflict,
  PHASE, STATUS, accepting, scoreText, receiptTime, studentAssignmentHref,
} from "../../shared/assignments-remote.ts";
import type { AssignmentDetail, AssignmentInfo, MySubmission, SubmissionFile, SubmissionReceipt } from "../../shared/assignments-remote.ts";
import {
  uploadFile, awaitScan, getUpload, discardUpload, uploadFailure, downloadControl, sizeText,
} from "../../shared/uploads-remote.tsx";
import type { UploadView } from "../../shared/uploads-remote.tsx";
import { fileboxAttach } from "../../shared/filebox-attach.tsx";
import type { Screen, ScreenCtx } from "../../types.ts";

const LOGIN_TEXT = "ASTRA 서버에 로그인하면 이 과목의 과제를 보고 제출할 수 있습니다.";
const DRAFT_DELAY = 2000;
const MAX_UPLOADS = 5;
const MAX_BODY = 50000;
const ACCEPT = ".pdf,.docx,.pptx,.xlsx,.hwp,.hwpx,.zip,.txt,.png,.jpg,.jpeg";
const UPLOAD_STATE = {
  sending: "올리는 중",
  quarantined: "검사 중",
  clean: "첨부 준비됨",
  rejected: "서버 검사를 통과하지 못했습니다. 이 파일은 제출할 수 없습니다.",
  slow: "검사가 오래 걸리고 있습니다. 잠시 후 다시 올려 보세요.",
  gone: "더 이상 쓸 수 없는 업로드입니다. 다시 올리세요.",
};
const LIMIT_TEXT = `파일은 이어서 내는 것과 새로 올리는 것을 합쳐 ${MAX_UPLOADS}개까지 제출할 수 있습니다.`;

const missing = (error: unknown): boolean => error instanceof ApiError && error.status === 404;

/** learning.tsx 도우미. */
interface DetailParts {
  typeLine(type: string): ReactNode;
  metaTable(rows: [string, ReactNode][]): ReactNode;
}

type UploadState = keyof typeof UPLOAD_STATE | "failed";

interface UploadEntry {
  local: number;
  name: string;
  size: number | undefined;
  state: UploadState;
  uploadId: number | null;
  message: string;
}

interface Compose {
  text: string | null;
  uploads: UploadEntry[];
  /** 다시 제출할 때 지금 제출본에서 빼기로 한 파일이다(나머지는 이어서 낸다). */
  dropped: Set<number | string>;
  receipt: SubmissionReceipt | null;
  receiptEntry: unknown;
  /** Redraws the open submit dialog (uploads finish while it is open). */
  paint: (() => void) | null;
}

// assignmentKey -> typed text, picked uploads, dropped files and the last receipt.
const compose = new Map<string, Compose>();
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  compose.clear();
});
let localIds = 0;

function draftOf(key: string): Compose {
  let memo = compose.get(key);
  if (!memo) {
    memo = { text: null, uploads: [], dropped: new Set(), receipt: null, receiptEntry: null, paint: null };
    compose.set(key, memo);
  }
  return memo;
}

function RetryButton({ reload }: { reload: () => void }) {
  const [busy, setBusy] = useState(false);
  return <Button kind="ghost" disabled={busy} onClick={() => { setBusy(true); reload(); }}>다시 불러오기</Button>;
}

/** Plain text in paragraphs; every piece is text. */
function Prose({ text, empty }: { text: string | null | undefined; empty?: string }) {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  if (!blocks.length) return empty ? <div className="prose"><p className="tiny">{empty}</p></div> : null;
  return (
    <div className="prose">
      {blocks.map((block, index) => (
        <p key={index}>{block.split("\n").map((line, at) => <Fragment key={at}>{at ? <br /> : null}{line}</Fragment>)}</p>
      ))}
    </div>
  );
}

/** One attached file: its name as text and a download through a Blob URL. */
function FileLine({ courseId, file }: { courseId: number | string | null; file: SubmissionFile }) {
  return (
    <div className="file-row">
      <Icon name="file" />
      <span><b>{file.name}</b><span className="tiny">{sizeText(file.sizeBytes)}</span></span>
      {downloadControl(courseId, file)}
    </div>
  );
}

function PhaseBadge({ phase }: { phase: string | undefined }) {
  return <Badge tone={phase === "open" ? "success" : phase === "late" ? "neutral" : "danger"}>{PHASE[phase || ""] || phase}</Badge>;
}

type Gate = { view: ReactNode; off: boolean } | null;

/**
 * Shared gate for the detail and the dialog: what to show instead, or null when ready. A server
 * assignment link opened directly waits for the first backend probe; a change of status re-renders
 * the app, a probe that stays off turns `off` on (the caller shows its own not-connected state).
 */
function useGate(kj: string | undefined): Gate {
  const status = backendStatus();
  const probing = status === "off" && !backendSettled();
  const [off, setOff] = useState(false);
  useEffect(() => {
    if (!probing) return;
    let live = true;
    void connectBackend().then((next) => {
      if (live && next.status === "off") setOff(true);
    });
    return () => {
      live = false;
    };
  }, [probing]);
  if (status === "anon") return { view: backendPanel({ text: LOGIN_TEXT }), off: false };
  if (status === "off") {
    if (off) return { view: null, off: true };
    if (probing) return { view: <LoadingState title="ASTRA 서버에 연결하는 중입니다" />, off: false };
    return { view: <EmptyState title="활동을 찾을 수 없습니다" body="ASTRA 서버에 연결되어 있지 않습니다. 목록에서 다른 활동을 선택해 보세요." />, off: false };
  }
  if (!courseIdFor(kj)) return { view: <EmptyState title="이 과목은 ASTRA 서버에 없습니다" body="서버에 등록된 과목의 과제만 볼 수 있습니다." />, off: false };
  return null;
}

/** The detail screen of `screen.serverAssignment` (backend mode). `parts` are learning.tsx helpers. */
export function remoteAssignmentDetail(screen: Screen, ctx: ScreenCtx, parts: DetailParts): ReactNode {
  return <AssignmentDetailScreen screen={screen} parts={parts} />;
}

function AssignmentDetailScreen({ screen, parts }: { screen: Screen; parts: DetailParts }) {
  const kj = screen.course || "";
  const assignmentId = Number(screen.serverAssignment);
  const gate = useGate(kj);
  const courseId = gate ? null : courseIdFor(kj);
  const key = courseId == null ? null : assignmentKey(courseId, assignmentId);
  const load = () => {
    if (key && courseId != null) void assignmentDetails.load(key, courseId, assignmentId);
  };
  const entry = useResource(assignmentDetails, key, key ? load : undefined);
  useEffect(() => {
    if (key && courseId != null) void assignmentDetails.visit("assignment-detail", key, courseId, assignmentId);
  });
  const listHref = `/ilos/cls/st/activity/activity_list_form.acl?kj=${encodeURIComponent(kj)}`;
  let main: ReactNode;
  if (gate) {
    main = gate.off
      ? <EmptyState title="활동을 찾을 수 없습니다" body="ASTRA 서버에 연결되어 있지 않습니다." />
      : <div className="act-body">{parts.typeLine("report")}{gate.view}</div>;
  } else {
    const data = entry && entry.data;
    if (!data) {
      if (!entry || !entry.error) main = <LoadingState title="과제를 불러오는 중입니다" />;
      else if (missing(entry.error)) main = <EmptyState title="과제를 찾을 수 없습니다" body="아직 공개되지 않았거나 볼 수 있는 권한이 없습니다." actions={<Link href={listHref} className="btn btn-secondary">학습활동 목록</Link>} />;
      else main = <ErrorState title="과제를 불러오지 못했습니다" body={assignmentMessage(entry.error)} actions={<RetryButton reload={load} />} />;
    } else {
      main = <DetailBody data={data} courseId={courseId} kj={kj} assignmentId={assignmentId} parts={parts} staleError={entry ? entry.error : null} />;
    }
  }
  return (
    <section className="bleed act-layout is-open" data-item-open={`srv-${assignmentId}`}>
      <div className="act-main">{main}</div>
    </section>
  );
}

const isSubmitted = (mine: MySubmission | null | undefined): boolean => Boolean(mine && (mine.status === "submitted" || mine.status === "late"));

interface DetailBodyProps {
  data: AssignmentDetail;
  courseId: number | string | null;
  kj: string;
  assignmentId: number;
  parts: DetailParts;
  staleError: unknown;
}

function DetailBody({ data, courseId, kj, assignmentId, parts, staleError }: DetailBodyProps) {
  const info: Partial<AssignmentInfo> = data.assignment || {};
  const phase = data.phase;
  const dday = ddayAt(info.closeAt, data.serverTime);
  const head = (
    <>
      {parts.typeLine("report")}
      <h1>{info.title}</h1>
      {staleError ? <p className="err" role="alert">{assignmentMessage(staleError)}</p> : null}
      {parts.metaTable([
        ["제출 구간", <PhaseBadge phase={phase} />],
        ["시작", info.openAt ? koDateTime(info.openAt) : "바로 시작"],
        ["마감", <span className="due-line"><span>{koDateTime(info.closeAt)}</span>{dday ? <span className="dday">{dday}</span> : null}</span>],
        ["지각 제출", info.latePolicy === "allow_until" && info.lateUntil ? `${koDateTime(info.lateUntil)}까지 받습니다` : "받지 않습니다"],
        ["제출 형태", [info.allowText ? "글 입력" : null, info.allowFile ? "파일 첨부" : null].filter(Boolean).join(" / ")],
        ["배점", `${scoreText(info.maxScore)}점`],
      ])}
      <Prose text={info.description} empty="과제 설명이 없습니다." />
    </>
  );
  const mine = data.submission;
  if (!mine) {
    // The instructor's view of the same assignment: counts, not a submission.
    return (
      <div className="act-body">
        {head}
        <p className="note">{`담당 교수 화면입니다. 제출 ${data.submittedCount ?? 0}명 · 지각 ${data.lateCount ?? 0}명`}</p>
      </div>
    );
  }
  const submitted = isSubmitted(mine);
  const rows: ([string, ReactNode] | null)[] = [
    ["상태", <Badge tone={submitted ? "success" : "danger"}>{STATUS[mine.status] || mine.status}</Badge>],
    submitted ? ["최종 제출", `${koDateTime(mine.submittedAt)} · ${mine.version}번째 제출`] : null,
    submitted && mine.firstSubmittedAt && mine.version > 1 ? ["처음 제출", koDateTime(mine.firstSubmittedAt)] : null,
    mine.draft && mine.draft.savedAt ? ["임시 저장", `${koDateTime(mine.draft.savedAt)} (제출로 치지 않습니다)`] : null,
  ];
  const files = submitted && Array.isArray(mine.files) ? mine.files : [];
  let action: ReactNode;
  if (accepting(phase)) {
    action = (
      <>
        {phase === "late" ? <p className="note">지각 제출 기간입니다. 지금 제출하면 지각으로 기록됩니다.</p> : null}
        <div className="btn-row"><Link href={studentAssignmentHref(kj, assignmentId, { pop: "submit" })} className="btn btn-primary">{submitted ? "다시 제출" : "제출"}</Link></div>
      </>
    );
  } else if (phase === "closed") {
    action = <p className="note">제출 기간이 끝났습니다.</p>;
  } else {
    action = <p className="note">아직 제출 기간이 아닙니다.</p>;
  }
  return (
    <div className="act-body">
      {head}
      <h2>내 제출</h2>
      {parts.metaTable(rows.filter((row): row is [string, ReactNode] => row !== null))}
      {submitted ? <Prose text={mine.body} /> : null}
      {files.map((file) => <FileLine key={file.fileId} courseId={courseId} file={file} />)}
      {/* Released scores only: the server sends `grade` after release and omits it before. */}
      {mine.grade && mine.grade.score != null ? (
        <section className="note ok" aria-label="점수">
          <h2>{`점수 ${scoreText(mine.grade.score)} / ${scoreText(info.maxScore)}점`}</h2>
          <Prose text={mine.grade.feedback} />
          {mine.grade.gradedAt ? <p className="tiny">{`${koDateTime(mine.grade.gradedAt)} 채점`}</p> : null}
        </section>
      ) : null}
      {action}
    </div>
  );
}

// ---------- submit dialog ----------

/** The submit dialog (`?pop=submit`) for a backend assignment. */
export function remoteSubmitDialog(ctx: ScreenCtx): ReactNode {
  return <SubmitDialog screen={ctx.screen} />;
}

function SubmitDialog({ screen }: { screen: Screen }) {
  const kj = screen.course;
  const assignmentId = Number(screen.serverAssignment);
  const gate = useGate(kj);
  const courseId = gate ? null : courseIdFor(kj);
  const key = courseId == null ? null : assignmentKey(courseId, assignmentId);
  const entry = useResource(assignmentDetails, key);
  const [, repaint] = useReducer((n: number) => n + 1, 0);
  // The data the form was built from: a later failed reload keeps the form and shows the error in it.
  const shown = useRef<AssignmentDetail | null>(null);
  const memo = key ? draftOf(key) : null;
  useEffect(() => {
    if (!memo) return;
    memo.paint = repaint;
    return () => {
      if (memo.paint === repaint) memo.paint = null;
    };
  }, [memo]);
  useEffect(() => {
    // Opening the dialog is a new history entry: read the latest version before the next submit.
    if (key && courseId != null) void assignmentDetails.visit("assignment-submit", key, courseId, assignmentId);
  });
  let frame: ReactNode;
  if (gate) {
    frame = gate.off ? <EmptyState title="과제를 찾을 수 없습니다" body="ASTRA 서버에 연결되어 있지 않습니다." /> : gate.view;
  } else if (memo && key && courseId != null) {
    // A receipt belongs to the history entry it was made on; reopening the dialog starts a new form.
    if (memo.receipt && memo.receiptEntry !== history.state) memo.receipt = null;
    const data = (entry && entry.data) || shown.current;
    if (memo.receipt) {
      shown.current = null;
      frame = <ReceiptView receipt={memo.receipt} courseId={courseId} />;
    } else if (!data) {
      if (!entry || !entry.error) frame = <LoadingState title="과제를 불러오는 중입니다" />;
      else if (missing(entry.error)) frame = <EmptyState title="과제를 찾을 수 없습니다" body="아직 공개되지 않았거나 제출할 수 없는 과제입니다." />;
      else frame = <ErrorState title="과제를 불러오지 못했습니다" body={assignmentMessage(entry.error)} actions={<RetryButton reload={() => void assignmentDetails.load(key, courseId, assignmentId)} />} />;
    } else if (!data.submission) {
      shown.current = null;
      frame = <EmptyState title="담당 교수는 제출할 수 없습니다" body="학생 계정으로 로그인하면 제출할 수 있습니다." />;
    } else {
      shown.current = data;
      frame = (
        <SubmitForm
          courseId={courseId}
          assignmentId={assignmentId}
          assignmentKey={key}
          memo={memo}
          data={data}
          loadError={entry && !entry.data ? entry.error : null}
        />
      );
    }
  }
  return (
    <Dialog title="과제 제출" wide>
      <div className="submit-form">{frame}</div>
    </Dialog>
  );
}

function ReceiptView({ receipt, courseId }: { receipt: SubmissionReceipt; courseId: number | string }) {
  const files = Array.isArray(receipt.files) ? receipt.files : [];
  return (
    <div>
      <section className="note ok" role="status">
        <h2>제출했습니다</h2>
        <p>{`서버 접수 시각 ${receiptTime(receipt.submittedAt)}`}</p>
        <p>{`${receipt.version}번째 제출${receipt.late ? " · 지각 제출로 기록되었습니다" : ""}`}</p>
      </section>
      {files.map((file) => <FileLine key={file.fileId} courseId={courseId} file={file} />)}
      <div className="submit-actions"><Button kind="primary" onClick={() => closeDialog()}>닫기</Button></div>
    </div>
  );
}

interface SubmitFormProps {
  courseId: number | string;
  assignmentId: number;
  assignmentKey: string;
  memo: Compose;
  data: AssignmentDetail;
  loadError: unknown;
}

interface Message {
  text: string;
  alert: boolean;
}

/** Form state that async work (draft saves, the submission) reads and changes across renders. */
interface FormRun {
  busy: boolean;
  drafting: boolean;
  draftAgain: boolean;
  draftTimer: number | undefined;
  savedText: string | null;
  text: string;
  draftNote: string;
  message: Message;
  live: boolean;
}

function startText(mine: MySubmission, memo: Compose): string {
  // Typed text survives re-renders; otherwise start from the draft, then the current version.
  if (memo.text != null) return memo.text;
  if (mine.draft && mine.draft.body != null) return mine.draft.body;
  return isSubmitted(mine) && mine.body ? mine.body : "";
}

function SubmitForm({ courseId, assignmentId, assignmentKey: key, memo, data, loadError }: SubmitFormProps) {
  const [, bump] = useReducer((n: number) => n + 1, 0);
  const mine = data.submission as MySubmission; // the dialog draws this form only for a student view
  const run = useRef<FormRun | null>(null);
  if (!run.current) {
    run.current = {
      busy: false,
      drafting: false,
      draftAgain: false,
      draftTimer: undefined,
      savedText: mine.draft && mine.draft.body != null ? mine.draft.body : null,
      text: startText(mine, memo),
      draftNote: mine.draft && mine.draft.savedAt ? `임시 저장 · ${koDateTime(mine.draft.savedAt)}` : "",
      message: { text: "", alert: false },
      live: true,
    };
  }
  const s = run.current;
  // The submission form of an assignment does not change while the dialog is open.
  const [form] = useState(() => ({ allowText: Boolean(data.assignment.allowText), allowFile: Boolean(data.assignment.allowFile) }));
  const latest = useRef(data);
  latest.current = data;

  useEffect(() => {
    s.live = true;
    return () => {
      s.live = false;
      clearTimeout(s.draftTimer);
    };
  }, [s]);

  const say = (text: string, alert = false) => {
    s.message = { text: text || "", alert };
    bump();
  };
  useEffect(() => {
    if (loadError) say(assignmentMessage(loadError), true);
  }, [loadError]);

  const repaint = () => {
    if (memo.paint) memo.paint();
  };
  const activeUploads = () => memo.uploads.filter((entry) => entry.state !== "rejected" && entry.state !== "failed" && entry.state !== "gone").length;
  // 지금 제출본(현재 버전)의 파일. 다시 제출할 때 고른 것만 새 버전에 이어진다(`keepFileIds`).
  const currentFiles = (): SubmissionFile[] => {
    const now = latest.current;
    const current = now.submission;
    return isSubmitted(current) && now.assignment.allowFile && current && Array.isArray(current.files) ? current.files : [];
  };
  const keptIds = () => currentFiles().filter((file) => !memo.dropped.has(file.fileId)).map((file) => file.fileId);
  const room = () => MAX_UPLOADS - activeUploads() - keptIds().length;
  const pendingUploads = () => memo.uploads.some((entry) => entry.state === "sending" || entry.state === "quarantined");
  const scanState = (state: string): UploadState => (state === "clean" || state === "quarantined" || state === "rejected" ? state : "gone");

  const track = (entry: UploadEntry, view: UploadView) => {
    entry.uploadId = view.uploadId;
    entry.state = scanState(view.state);
    if (entry.state !== "quarantined") return;
    awaitScan(view, {
      alive: () => memo.uploads.includes(entry),
      onState: (next) => {
        entry.state = scanState(next.state);
        repaint();
      },
    }).then((last) => {
      if (last && last.state === "quarantined") entry.state = "slow";
      repaint();
    }, (error: unknown) => {
      entry.state = "failed";
      entry.message = uploadFailure(error);
      repaint();
    });
  };

  const addEntry = (name: string, size: number | undefined): UploadEntry => {
    const entry: UploadEntry = { local: (localIds += 1), name, size, state: "sending", uploadId: null, message: "" };
    memo.uploads.push(entry);
    return entry;
  };

  const onPick = (event: ChangeEvent<HTMLInputElement>) => {
    // Handled here: the app-wide file listener would remember the name in saved sample state.
    event.stopPropagation();
    const input = event.target;
    const picked = [...(input.files || [])];
    input.value = "";
    const left = room();
    if (picked.length > left) say(LIMIT_TEXT, true);
    for (const file of picked.slice(0, Math.max(0, left))) {
      const entry = addEntry(String(file.name || "file"), file.size);
      uploadFile(file, courseId, "assignment_submission").then((view) => {
        track(entry, view);
        repaint();
      }, (error: unknown) => {
        entry.state = "failed";
        entry.message = uploadFailure(error);
        repaint();
      });
    }
    repaint();
  };

  const queueDraft = () => {
    clearTimeout(s.draftTimer);
    s.draftTimer = window.setTimeout(() => {
      if (s.live && s.text !== s.savedText) void saveText(false);
    }, DRAFT_DELAY);
  };

  async function saveText(explicit: boolean) {
    const now = latest.current;
    if (!now.assignment.allowText || !accepting(now.phase)) return;
    if (s.drafting) {
      s.draftAgain = true;
      return;
    }
    clearTimeout(s.draftTimer);
    s.drafting = true;
    const text = s.text;
    s.draftNote = "임시 저장하는 중입니다.";
    bump();
    try {
      const draft = await saveDraft(courseId, assignmentId, text);
      s.savedText = text;
      s.draftNote = draft && draft.savedAt ? `임시 저장했습니다 · ${koDateTime(draft.savedAt)}` : "임시 저장한 글을 지웠습니다.";
    } catch (error) {
      s.draftNote = `임시 저장하지 못했습니다. ${assignmentMessage(error)}`;
      if (!explicit) s.draftAgain = false;
    } finally {
      s.drafting = false;
      if (s.live) bump();
      if (s.draftAgain && s.live) {
        s.draftAgain = false;
        queueDraft();
      }
    }
  }

  async function send() {
    const now = latest.current;
    const current = now.submission;
    if (s.busy || !current) return;
    if (!accepting(now.phase)) return say("제출 기간이 아닙니다.", true);
    if (pendingUploads()) return say("첨부 파일 검사가 끝난 뒤 제출하세요.", true);
    const allowText = now.assignment.allowText;
    const text = allowText ? s.text : "";
    if (text.length > MAX_BODY) return say(`글은 ${MAX_BODY.toLocaleString("ko-KR")}자 이하입니다.`, true);
    const uploadIds = memo.uploads.filter((entry) => entry.state === "clean" && entry.uploadId != null).map((entry) => entry.uploadId as number);
    // 다시 제출이면 지금 제출본에서 고른 파일을 `keepFileIds`로 함께 보낸다. 글만 받는 과제에는 보내지 않는다.
    const keepFileIds = now.assignment.allowFile && isSubmitted(current) ? keptIds() : undefined;
    const kept = keepFileIds ? keepFileIds.length : 0;
    if (!text.trim() && !uploadIds.length && !kept) return say(allowText ? "글을 쓰거나 파일을 첨부하세요." : "제출할 파일을 첨부하세요.", true);
    if (uploadIds.length + kept > MAX_UPLOADS) return say(`${LIMIT_TEXT} 몇 개를 빼고 다시 제출하세요.`, true);
    clearTimeout(s.draftTimer);
    s.busy = true;
    say("ASTRA 서버에 제출하고 있습니다.");
    try {
      const receipt = await submitAssignment(courseId, assignmentId, {
        expectedVersion: current.version,
        body: allowText ? text : null,
        uploadIds,
        keepFileIds,
      });
      memo.text = null;
      memo.uploads = [];
      memo.dropped.clear();
      memo.receipt = receipt;
      memo.receiptEntry = history.state;
      toast("과제를 제출했습니다");
      repaint();
      void assignmentDetails.load(key, courseId, assignmentId);
    } catch (error) {
      if (isConflict(error, "version_conflict") || isConflict(error, "deadline_passed") || missing(error)) {
        say(assignmentMessage(error), true);
        void assignmentDetails.load(key, courseId, assignmentId);
      } else if (isConflict(error, "attachment_not_ready")) {
        // 검사가 덜 끝난 업로드이거나 이어받을 수 없는 파일(지금 제출본에 없는 파일)이다. 아무것도 저장되지 않았으니
        // 업로드 상태와 최신 제출본을 다시 읽는다. 입력한 글과 고른 파일은 그대로 둔다.
        say(assignmentMessage(error), true);
        recheck();
        void assignmentDetails.load(key, courseId, assignmentId);
      } else if (submitUncertain(courseId, assignmentId)) {
        say("제출 결과를 확인하지 못했습니다. 네트워크를 확인한 뒤 같은 내용으로 다시 제출하세요. 이미 접수되었다면 같은 접수 결과를 돌려받습니다.", true);
      } else say(assignmentMessage(error), true);
    } finally {
      s.busy = false;
      if (s.live) bump();
    }
  }

  // After 409 attachment_not_ready: read each upload's state again.
  function recheck() {
    for (const entry of memo.uploads) {
      if (!entry.uploadId || entry.state !== "clean") continue;
      getUpload(entry.uploadId).then((view) => {
        track(entry, view);
        repaint();
      }, (error: unknown) => {
        entry.state = missing(error) ? "gone" : "failed";
        entry.message = uploadFailure(error);
        repaint();
      });
    }
  }

  const info = data.assignment;
  const submitted = isSubmitted(mine);
  const dday = ddayAt(info.closeAt, data.serverTime);
  const open = accepting(data.phase);
  const current = currentFiles();
  return (
    <div className="srv-submit">
      <div>
        <p className="tiny">{info.title}</p>
        <p className="due-line"><PhaseBadge phase={data.phase} /><span>{`마감 ${koDateTime(info.closeAt)}`}</span>{dday ? <span className="dday">{dday}</span> : null}</p>
        {data.phase === "late" ? <p className="note">지각 제출 기간입니다. 지금 제출하면 지각으로 기록됩니다.</p> : null}
        {!open ? <p className="note">제출 기간이 아니어서 제출할 수 없습니다. 입력한 글은 이 창을 닫기 전까지 남아 있습니다.</p> : null}
        {submitted ? <p className="tiny">{`${koDateTime(mine.submittedAt)}에 ${mine.version}번째로 제출했습니다. 다시 제출하면 새 버전이 됩니다.${info.allowFile ? " 지금 제출본의 파일은 체크한 것만 이어지고, 새로 올린 파일이 더해집니다." : ""}`}</p> : null}
      </div>
      {form.allowText ? (
        <div className="editor">
          <label className="field">
            <span className="field-label">글</span>
            <textarea
              rows={10}
              maxLength={MAX_BODY}
              placeholder="제출할 글을 입력하세요."
              aria-label="제출 글"
              readOnly={s.busy}
              value={s.text}
              onChange={(event) => {
                s.text = event.target.value;
                memo.text = s.text;
                queueDraft();
                bump();
              }}
            />
          </label>
          <div className="compose-files">
            <Button kind="ghost" disabled={s.busy || s.drafting || !open} onClick={() => void saveText(true)}>임시 저장</Button>
            <span className="tiny" role="status" aria-live="polite">{s.draftNote}</span>
          </div>
        </div>
      ) : null}
      {form.allowFile ? (
        <div>
          <div className="compose-files">
            <label className="btn btn-secondary">
              <Icon name="file" />
              파일 첨부
              <input type="file" className="file-input" multiple accept={ACCEPT} aria-label="첨부 파일 고르기" disabled={s.busy || !open} onChange={onPick} />
            </label>
            {/* Files picked from the file box join the same list and go through the same scan wait and removal. */}
            {fileboxAttach({
              courseId,
              purpose: "assignment_submission",
              limit: MAX_UPLOADS,
              room,
              disabled: s.busy || !open,
              onAttach: (views) => {
                for (const view of views) track(addEntry(String(view.name || "file"), view.sizeBytes), view);
                repaint();
              },
            })}
            <span className="tiny">{`이어서 내는 파일과 새로 올리는 파일을 합쳐 ${MAX_UPLOADS}개까지, 50MB 이하. 검사를 통과한 파일만 제출됩니다.`}</span>
          </div>
          <div className="stack">
            {current.length ? <p className="tiny">지금 제출본의 파일입니다. 체크를 풀면 다시 제출할 때 빠지고, 체크한 파일은 새 버전에 그대로 이어집니다.</p> : null}
            {current.map((file) => {
              const keep = !memo.dropped.has(file.fileId);
              return (
                <div className="file-row" key={file.fileId}>
                  <Icon name="file" />
                  <span><b>{file.name}</b><span className="tiny">{[sizeText(file.sizeBytes), keep ? "이어서 제출" : "이번 제출에서 뺌"].filter(Boolean).join(" · ")}</span></span>
                  <label className="choice">
                    <input
                      type="checkbox"
                      data-keep-file={String(file.fileId)}
                      checked={keep}
                      disabled={s.busy}
                      onChange={(event) => {
                        if (event.target.checked) memo.dropped.delete(file.fileId);
                        else memo.dropped.add(file.fileId);
                        if (room() < 0) say(LIMIT_TEXT, true);
                        else bump();
                      }}
                    />
                    <span>이어서 제출</span>
                  </label>
                </div>
              );
            })}
          </div>
          <div className="stack">
            {memo.uploads.map((entry) => (
              <div className="file-row" key={entry.local}>
                <Icon name="file" />
                <span>
                  <b>{entry.name}</b>
                  <span className="tiny">{[sizeText(entry.size), entry.state === "failed" ? entry.message : UPLOAD_STATE[entry.state] || UPLOAD_STATE.gone].filter(Boolean).join(" · ")}</span>
                </span>
                <Button
                  kind="ghost"
                  aria-label={`${entry.name} 빼기`}
                  disabled={s.busy}
                  onClick={() => {
                    memo.uploads = memo.uploads.filter((item) => item !== entry);
                    // An unattached upload expires on its own; deleting it early is a courtesy.
                    if (entry.uploadId) void discardUpload(entry.uploadId);
                    bump();
                  }}
                >
                  빼기
                </Button>
              </div>
            ))}
          </div>
        </div>
      ) : null}
      <p className="tiny" role={s.message.alert ? "alert" : "status"} aria-live="polite">{s.message.text}</p>
      <div className="submit-actions">
        <Button kind="ghost" onClick={() => closeDialog()}>취소</Button>
        <Button kind="primary" disabled={s.busy || !open || pendingUploads()} onClick={() => void send()}>{s.busy ? "제출하는 중…" : "제출"}</Button>
      </div>
    </div>
  );
}
