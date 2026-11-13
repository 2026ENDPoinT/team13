// 서버 첨부 폼의 "파일함에서 첨부". 내 파일함을 폴더별로 보여 주고(`listings`, 이름순, "더 보기"로 다음 쪽),
// 고른 파일만 `uploadsFromFilebox`로 그 과목·용도의 업로드로 만든다. 과목 밖 용도(`OUTSIDE_COURSE_PURPOSES`:
// 알림마당 공지·자료실, 1:1 문의, 쪽지 첨부)는 과목 없이 만든다. 받은 업로드 view는 부르는 쪽이
// 보통 업로드 결과와 같은 첨부 목록에 넣는다(`quarantined`는 `awaitScan`, 빼면 `discardUpload`).
// 거부되면 창 안에 이유를 보이고 아무것도 넘기지 않는다. 백엔드가 `ready`가 아니면 버튼이 없다.
import { useEffect, useRef, useState, type ReactNode } from "react";
import { Badge, Button, EmptyState, ErrorState, LoadingState } from "../ui/kit.tsx";
import { ApiError } from "./api.ts";
import { backendStatus } from "./backend.ts";
import { retryLater, useResource } from "./remote-cache.ts";
import { sizeText } from "./uploads-remote.tsx";
import { listings, listKey, SCAN_STATE, uploadsFromFilebox, fileboxMessage, OUTSIDE_COURSE_PURPOSES } from "./filebox-remote.ts";
import type { FileboxItem, FileboxListing, FileboxUpload } from "./filebox-remote.ts";

const SORT = "name";
const ORDER = "asc";
let dialogIds = 0;

type FolderId = number | null;

const keyOf = (folder: FolderId) => listKey(folder, SORT, ORDER, "");
const load = (folder: FolderId, previous: FileboxListing | null = null) => listings.load(keyOf(folder), folder, SORT, ORDER, "", previous);
const uncertain = (error: unknown) => error instanceof ApiError && [0, 502, 503, 504].includes(error.status);

/** from-filebox가 거부한 이유. `course`가 false면 과목 밖 용도의 요청이다. 그 밖의 실패는 `fileboxMessage`를 쓴다. */
export function attachRefusal(error: unknown, { course = true }: { course?: boolean } = {}): string {
  if (!(error instanceof ApiError)) return fileboxMessage(error);
  switch (error.code) {
    case "folder_not_allowed": return "폴더는 첨부할 수 없습니다. 파일만 고르세요.";
    case "not_ready": return "검사를 마치지 않았거나 검사를 통과하지 못한 파일이 있습니다. 잠시 후 다시 고르세요.";
    case "not_active": return "휴지통에 넣은 파일이 섞여 있습니다. 목록을 새로 불러왔습니다.";
    case "extension_not_allowed": return "이곳에 첨부할 수 없는 형식의 파일이 있습니다.";
    case "too_large": return "이곳에 첨부하기에는 너무 큰 파일이 있습니다.";
    case "idempotency_conflict": return "같은 요청이 다른 내용으로 처리 중입니다. 잠시 후 다시 시도하세요.";
    case "purpose_not_allowed": return "이 과목에서 고른 용도로는 파일함 파일을 쓸 수 없습니다.";
    default: break;
  }
  switch (error.status) {
    case 400: return error.detail || "요청을 처리하지 못했습니다. 다시 고르세요.";
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 404: return course
      ? "찾을 수 없는 파일이 있거나 이 과목에 첨부할 권한이 없습니다. 목록을 새로 불러왔습니다."
      : "찾을 수 없는 파일이 있거나 이곳에 파일을 첨부할 권한이 없습니다. 목록을 새로 불러왔습니다.";
    case 413: return "이곳에 첨부하기에는 너무 큰 파일이 있습니다.";
    case 422: return "이곳에 첨부할 수 없는 파일이 있습니다.";
    case 429: return retryLater(error);
    default: break;
  }
  if (uncertain(error)) return "첨부 결과를 확인하지 못했습니다. 고른 그대로 다시 첨부하면 한 번만 붙습니다.";
  return fileboxMessage(error);
}

export interface FileboxAttachOptions {
  /** Backend course id; null/undefined for a course-less purpose. */
  courseId: number | string | null | undefined;
  purpose: string;
  /** Most files the form takes (shown in the hints). */
  limit: number;
  /** How many more files the form takes right now. */
  room: () => number;
  onAttach: (uploads: FileboxUpload[]) => void;
  /** Disables the button and closes an open window (no room left). */
  disabled?: boolean;
}

/** Whether the file box button can exist for this course and purpose (backend `ready`). */
function available(courseId: FileboxAttachOptions["courseId"], purpose: string): boolean {
  if (backendStatus() !== "ready") return false;
  return courseId != null || OUTSIDE_COURSE_PURPOSES.has(purpose);
}

/** The "파일함에서 첨부" button and its file box window. */
export function FileboxAttach({ courseId, purpose, limit, room, onAttach, disabled = false }: FileboxAttachOptions) {
  const course = courseId != null;
  const [id] = useState(() => `fba-${(dialogIds += 1)}`);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const openRef = useRef<HTMLButtonElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);
  const [folder, setFolder] = useState<FolderId>(null);
  const [busy, setBusy] = useState(false);
  const [selected, setSelected] = useState<ReadonlyMap<number, FileboxItem>>(() => new Map());
  const [message, setMessage] = useState({ text: "", alert: false });
  const [hint, setHint] = useState("");
  const entry = useResource(listings, open ? keyOf(folder) : null);

  const say = (text: string, alert = false) => setMessage({ text, alert });
  const left = () => Math.max(0, Number(room()) || 0);

  const finish = () => {
    const dialog = dialogRef.current;
    if (dialog && dialog.open) dialog.close();
  };

  useEffect(() => {
    if (disabled) finish();
  }, [disabled]);

  // 지금 폴더를 읽는다. `previous`가 있으면 다음 쪽을 붙인다. 그린 것은 `useResource`가 따라간다.
  const refresh = (previous: FileboxListing | null = null) => {
    void load(folder, previous);
  };

  const show = (target: FolderId) => {
    const next = target || null;
    setFolder(next);
    void load(next);
    listRef.current?.focus();
  };

  const openWindow = () => {
    const dialog = dialogRef.current;
    if (disabled || !dialog || dialog.open) return;
    setSelected(new Map());
    say("");
    setHint(limit
      ? `검사를 통과한 파일만 고를 수 있습니다. 이 글에는 ${limit}개까지 첨부합니다.`
      : "검사를 통과한 파일만 고를 수 있습니다.");
    dialog.showModal();
    setOpen(true);
    show(folder);
  };

  const toggle = (item: FileboxItem, on: boolean) => {
    if (on) {
      if (selected.size >= left()) {
        say(limit ? `파일은 ${limit}개까지 첨부할 수 있습니다. 더 고르려면 고른 파일을 빼세요.` : "더 첨부할 수 없습니다.", true);
        return;
      }
      setSelected(new Map(selected).set(item.id, item));
    } else {
      const next = new Map(selected);
      next.delete(item.id);
      setSelected(next);
    }
    say("");
  };

  const confirm = async () => {
    if (busy) return;
    const ids = [...selected.keys()].sort((a, b) => Number(a) - Number(b));
    if (!ids.length) return say("첨부할 파일을 고르세요.", true);
    if (ids.length > left()) {
      return say(limit ? `파일은 ${limit}개까지 첨부할 수 있습니다. 지금은 ${left()}개까지 고를 수 있습니다.` : "더 첨부할 수 없습니다.", true);
    }
    setBusy(true);
    say(course ? "파일함의 파일을 이 과목에 첨부하고 있습니다." : "파일함의 파일을 첨부하고 있습니다.");
    let stale = false;
    try {
      const uploads = await uploadsFromFilebox(ids, course ? courseId : null, purpose);
      setSelected(new Map());
      say("");
      onAttach(uploads);
      finish();
    } catch (error) {
      say(attachRefusal(error, { course }), true);
      // 목록과 서버가 어긋났다. 고른 것을 비우고 지금 폴더를 다시 읽는다. 응답을 못 받은 경우는 고른 그대로 둔다.
      stale = error instanceof ApiError && (error.status === 404 || ["not_active", "not_ready", "folder_not_allowed"].includes(error.code || ""));
      if (stale) setSelected(new Map());
    } finally {
      setBusy(false);
      if (stale && dialogRef.current?.open) refresh();
    }
  };

  const free = left();
  const data = entry ? entry.data : null;
  const shownMessage = message.text || (data && entry && entry.error ? fileboxMessage(entry.error) : "");
  const alert = message.text ? message.alert : Boolean(shownMessage);
  const trail = data && Array.isArray(data.path) ? data.path : [];

  let body: ReactNode;
  if (!data) {
    body = !entry || !entry.error
      ? <LoadingState title="파일함을 불러오는 중입니다" />
      : <ErrorState title="파일함을 불러오지 못했습니다" body={fileboxMessage(entry.error)} actions={<Button kind="ghost" onClick={() => refresh()}>다시 불러오기</Button>} />;
  } else {
    const loadingMore = Boolean(entry && entry.loading);
    body = (
      <>
        {data.items.length
          ? (
            <ul className="fba-rows">
              {data.items.map((item) => {
                if (item.kind === "folder") {
                  return (
                    <li key={item.id} className="fba-row">
                      <Button kind="ghost" className="fba-folder" aria-label={`${item.name} 폴더 열기`} onClick={() => show(item.id)}>{item.name}</Button>
                      <span className="tiny">폴더</span>
                    </li>
                  );
                }
                const ready = item.state === "ready";
                const meta = [(item.extension || "파일").toUpperCase(), sizeText(item.sizeBytes)].filter(Boolean).join(" · ");
                const scan = item.state ? SCAN_STATE[item.state] : "";
                return (
                  <li key={item.id} className="fba-row">
                    <label className="choice fba-file">
                      <input
                        type="checkbox"
                        aria-describedby={`${id}-i${item.id}`}
                        checked={selected.has(item.id)}
                        disabled={busy || !ready}
                        onChange={(event) => toggle(item, event.currentTarget.checked)}
                      />
                      <span className="fba-name"><b>{item.name}</b><span id={`${id}-i${item.id}`} className="tiny">{meta}</span></span>
                    </label>
                    {scan ? <Badge tone={item.state === "rejected" ? "danger" : "neutral"}>{scan}</Badge> : null}
                  </li>
                );
              })}
            </ul>
          )
          : folder == null
            // 파일함에 파일을 넣는 화면은 없어졌다. 기기의 파일은 이 창 옆의 직접 올리기로 붙인다.
            ? <EmptyState title="파일함이 비어 있습니다" body="고를 파일이 없습니다. 기기에 있는 파일은 이 창을 닫고 첨부 칸에서 바로 올리세요." />
            : <EmptyState title="이 폴더에 항목이 없습니다" body="다른 폴더를 고르세요. 기기에 있는 파일은 이 창을 닫고 첨부 칸에서 바로 올리세요." />}
        {data.nextCursor
          ? (
            <div className="btn-row">
              <Button kind="secondary" disabled={loadingMore} aria-busy={loadingMore ? "true" : undefined} onClick={() => refresh(data)}>
                {loadingMore ? "불러오는 중…" : "더 보기"}
              </Button>
            </div>
          )
          : null}
      </>
    );
  }

  return (
    <span className="fba">
      <button ref={openRef} type="button" className="btn btn-secondary" aria-haspopup="dialog" disabled={disabled} onClick={openWindow}>파일함에서 첨부</button>
      <dialog
        ref={dialogRef}
        className="fba-dialog"
        aria-labelledby={`${id}-title`}
        aria-describedby={`${id}-hint`}
        onClick={(event) => {
          if (event.target === event.currentTarget) finish();
        }}
        onKeyDown={(event) => {
          // App의 Escape(바깥 `?pop=` 창 닫기)가 이 창의 입력을 받지 않게 한다.
          event.stopPropagation();
          if (event.key !== "Escape") return;
          event.preventDefault();
          finish();
        }}
        onCancel={(event) => {
          event.preventDefault();
          finish();
        }}
        onClose={() => {
          setOpen(false);
          openRef.current?.focus();
        }}
      >
        <div className="fba-body">
          <div className="fba-head">
            <h2 id={`${id}-title`}>파일함에서 첨부</h2>
            <Button kind="ghost" onClick={finish}>닫기</Button>
          </div>
          <p id={`${id}-hint`} className="tiny">{hint}</p>
          <nav className="fba-crumbs" aria-label="파일함 위치">
            {data
              ? (
                <ol>
                  {folder ? <li><Button kind="ghost" className="fba-crumb" onClick={() => show(null)}>파일함</Button></li> : <li><span aria-current="location">파일함</span></li>}
                  {trail.map((step, index) => (
                    <li key={step.id}>
                      {index === trail.length - 1 && folder != null
                        ? <span aria-current="location">{step.name}</span>
                        : <Button kind="ghost" className="fba-crumb" onClick={() => show(step.id)}>{step.name}</Button>}
                    </li>
                  ))}
                </ol>
              )
              : null}
          </nav>
          <div ref={listRef} className="fba-list" tabIndex={-1} aria-label="파일함 항목">{body}</div>
          <p className={alert ? "srv-msg is-alert" : "srv-msg"} role={alert ? "alert" : "status"} aria-live="polite">{shownMessage}</p>
          <div className="fba-foot">
            <span className="tiny" role="status" aria-live="polite">
              {selected.size ? `${selected.size}개 고름 · ${free}개까지 첨부할 수 있습니다` : `${free}개까지 첨부할 수 있습니다`}
            </span>
            <span className="fba-actions">
              <Button kind="ghost" onClick={finish}>취소</Button>
              <Button kind="primary" disabled={busy || !selected.size} aria-busy={busy ? "true" : undefined} onClick={() => void confirm()}>
                {busy ? "첨부하는 중…" : "첨부"}
              </Button>
            </span>
          </div>
        </div>
      </dialog>
    </span>
  );
}

/**
 * "파일함에서 첨부" 버튼과 고르기 창. `room()`은 지금 더 붙일 수 있는 수, `limit`은 안내에 쓰는 최대 수다.
 * `onAttach(uploads)`는 `[{ uploadId, itemId, state, name, sizeBytes, expiresAt }]`를 받는다. `courseId`가
 * null이면 과목 밖 용도일 때만 버튼을 만든다. `ready`가 아니거나 버튼을 만들 수 없으면 `null`.
 * 파일 입력 옆에 그대로 넣는다. `disabled`(더 붙일 자리가 없음)면 버튼을 끄고 열린 창을 닫는다.
 */
export function fileboxAttach(options: FileboxAttachOptions): ReactNode {
  if (!available(options.courseId, options.purpose)) return null;
  return <FileboxAttach {...options} />;
}
