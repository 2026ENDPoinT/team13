// 서버 첨부 고르기. 새 파일은 `uploadFile`(과목 밖 용도는 `courseId` null)로 올리고 검사가 끝날 때까지
// 기다린다. 이미 붙은 파일(`existing`)은 빼기·되돌리기로 남길 것만 고른다. 고른 상태는 부르는 쪽의 `memo`
// 객체에 두어 다시 그리기와 409 재조회 뒤에도 남는다. 저장 본문은 새 글이면 `uploadIds()`, 수정이면
// `attachments()`(`{ keep: [fileId], add: [uploadId] }`)다. 과목이 있거나 과목 밖 용도(공지·자료실·문의·쪽지
// 첨부)면 파일함에서도 고를 수 있다.
//
// React: `attachPicker(options)` returns a handle whose `node` is the `<AttachPicker>` element to render;
// the other methods read the caller's `memo`, so a handle made on any render answers for the same picker.
// `memo.repaint()` re-renders the mounted picker (set while it is mounted).
import { useEffect, useLayoutEffect, useReducer, useRef, useState, type ChangeEvent, type ReactNode } from "react";
import { Button } from "../ui/kit.tsx";
import { Icon } from "../ui/icon.tsx";
import { ApiError } from "./api.ts";
import { uploadFile, awaitScan, getUpload, discardUpload, uploadFailure, sizeText } from "./uploads-remote.tsx";
import { fileboxAttach } from "./filebox-attach.tsx";

/** Where one picked file stands: the scan states plus local ones. */
export type AttachState = "sending" | "quarantined" | "clean" | "rejected" | "slow" | "gone" | "failed";

export const UPLOAD_STATE: Record<Exclude<AttachState, "failed">, string> = {
  sending: "올리는 중",
  quarantined: "검사 중",
  clean: "첨부 준비됨",
  rejected: "서버 검사를 통과하지 못했습니다. 이 파일은 첨부할 수 없습니다.",
  slow: "검사가 오래 걸리고 있습니다. 빼고 다시 올려 보세요.",
  gone: "더 이상 쓸 수 없는 업로드입니다. 다시 올리세요.",
};

/** One file picked in this form (a new upload or one made from the file box). */
export interface AttachEntry {
  local: number;
  name: string;
  size: number | null;
  state: AttachState;
  uploadId: number | null;
  /** Failure text when `state` is "failed". */
  message: string;
}

/** The caller-owned attachment state of one form. */
export interface AttachMemo {
  uploads?: AttachEntry[];
  /** File ids of `existing` files that stay attached; null: start again from `existing`. */
  keep?: Set<number> | null;
  /** Re-renders the mounted picker. */
  repaint?: (() => void) | null;
}

/** A file already attached to what is being edited. */
export interface ExistingFile {
  fileId: number;
  name?: string | null;
  sizeBytes?: number | null;
}

export interface AttachPickerOptions {
  memo: AttachMemo;
  courseId?: number | string | null;
  purpose: string;
  max?: number;
  existing?: readonly ExistingFile[] | null;
  hint?: string;
  /** Runs after every change of the picked files (and once when the picker first shows). */
  onChange?: () => void;
}

export interface AttachPickerHandle {
  node: ReactNode;
  /** True while a file is still uploading or being scanned. */
  pending(): boolean;
  uploadIds(): number[];
  keepIds(): number[];
  attachments(): { keep: number[]; add: number[] };
  /** 남길 파일이 처음과 다르거나 새 파일이 있으면 true. */
  changed(): boolean;
  /** 409 `attachment_not_ready` 뒤: 업로드마다 상태를 다시 읽는다. */
  recheck(): void;
}

const DEAD: readonly AttachState[] = ["rejected", "failed", "gone"];
const scanState = (state: string): AttachState => (state === "clean" || state === "quarantined" || state === "rejected" ? state : "gone");
let localIds = 0;

/** 첨부 memo를 처음 상태로 비운다(저장이 끝난 뒤). 올린 업로드는 이미 붙었으므로 지우지 않는다. */
export function resetAttachments(memo: AttachMemo): void {
  memo.uploads = [];
  memo.keep = null;
}

function uploadsOf(memo: AttachMemo): AttachEntry[] {
  if (!Array.isArray(memo.uploads)) memo.uploads = [];
  return memo.uploads;
}

// After `resetAttachments` the same picker starts again from `existing`.
function keptOf(memo: AttachMemo, current: readonly ExistingFile[]): Set<number> {
  uploadsOf(memo);
  if (!(memo.keep instanceof Set)) memo.keep = new Set(current.map((file) => file.fileId));
  return memo.keep;
}

const repaint = (memo: AttachMemo) => memo.repaint?.();

/** Follows one upload (`view` from `uploadFile`, `getUpload` or the file box) until its scan is over. */
function track(memo: AttachMemo, entry: AttachEntry, view: { uploadId: number; state: string }) {
  entry.uploadId = view.uploadId;
  entry.state = scanState(view.state);
  if (entry.state !== "quarantined") return;
  awaitScan({ uploadId: view.uploadId, state: "quarantined", name: entry.name, sizeBytes: entry.size ?? 0 }, {
    alive: () => uploadsOf(memo).includes(entry),
    onState: (next) => { entry.state = scanState(next.state); repaint(memo); },
  }).then((last) => {
    if (last && last.state === "quarantined") entry.state = "slow";
    repaint(memo);
  }, (error: unknown) => {
    entry.state = "failed";
    entry.message = uploadFailure(error);
    repaint(memo);
  });
}

interface AttachPickerProps {
  memo: AttachMemo;
  current: readonly ExistingFile[];
  courseId: number | string | null;
  purpose: string;
  max: number;
  hint: string;
  onChange: () => void;
}

function AttachPicker({ memo, current, courseId, purpose, max, hint, onChange }: AttachPickerProps) {
  const [version, bump] = useReducer((n: number) => n + 1, 0);
  const [note, setNote] = useState("");
  const latestChange = useRef(onChange);
  latestChange.current = onChange;

  useLayoutEffect(() => {
    const paint = () => bump();
    memo.repaint = paint;
    return () => {
      if (memo.repaint === paint) memo.repaint = null;
    };
  }, [memo]);

  useEffect(() => {
    latestChange.current();
  }, [version]);

  const uploads = uploadsOf(memo);
  const kept = keptOf(memo, current);
  const live = uploads.filter((entry) => !DEAD.includes(entry.state)).length;
  const room = () => Math.max(0, max - keptOf(memo, current).size - uploadsOf(memo).filter((entry) => !DEAD.includes(entry.state)).length);
  const free = Math.max(0, max - kept.size - live);

  const add = (name: string, size: number | null): AttachEntry => {
    const entry: AttachEntry = { local: (localIds += 1), name, size, state: "sending", uploadId: null, message: "" };
    uploadsOf(memo).push(entry);
    return entry;
  };

  const pickFiles = (event: ChangeEvent<HTMLInputElement>) => {
    const input = event.currentTarget;
    const picked = [...(input.files || [])];
    input.value = "";
    const space = room();
    setNote(picked.length > space ? `파일은 ${max}개까지 첨부할 수 있습니다.` : "");
    for (const file of picked.slice(0, space)) {
      const entry = add(String(file.name || "file"), file.size);
      uploadFile(file, courseId, purpose).then((view) => {
        track(memo, entry, view);
        repaint(memo);
      }, (error: unknown) => {
        entry.state = "failed";
        entry.message = uploadFailure(error);
        repaint(memo);
      });
    }
    repaint(memo);
  };

  // 파일함 다시 쓰기(`/api/uploads/from-filebox`): 과목 업로드와 과목 밖 용도(`OUTSIDE_COURSE_PURPOSES`)만 된다.
  // 과목이 없는 다른 용도면 `fileboxAttach`가 null이라 버튼이 없다.
  const fromBox = fileboxAttach({
    courseId,
    purpose,
    limit: max,
    room,
    disabled: free <= 0,
    onAttach: (views) => {
      for (const view of views) track(memo, add(String(view.name || "file"), view.sizeBytes ?? null), view);
      setNote("");
      repaint(memo);
    },
  });

  return (
    <div className="srv-attach">
      <div className="compose-files">
        <label className="btn btn-secondary">
          <Icon name="file" />
          파일 첨부
          <input type="file" className="file-input" multiple aria-label="첨부 파일 고르기" onChange={pickFiles} />
        </label>
        {fromBox}
        <span className="tiny">{hint || `${max}개까지, 50MB 이하. 검사를 통과한 파일만 첨부됩니다.`}</span>
        <span className="tiny" role="status" aria-live="polite">{note}</span>
      </div>
      <div className="stack srv-attach-list">
        {current.map((file) => {
          const on = kept.has(file.fileId);
          const label = on ? "빼기" : "되돌리기";
          return (
            <div key={`keep-${file.fileId}`} className={`file-row${on ? "" : " is-removed"}`}>
              <Icon name="file" />
              <span>
                <b>{file.name || "파일"}</b>
                <span className="tiny">{[sizeText(file.sizeBytes), on ? "붙어 있음" : "저장하면 빠짐"].filter(Boolean).join(" · ")}</span>
              </span>
              <Button
                kind="ghost"
                aria-label={`${file.name} ${label}`}
                onClick={() => {
                  if (on) kept.delete(file.fileId);
                  else if (room() > 0) kept.add(file.fileId);
                  else setNote(`파일은 ${max}개까지 첨부할 수 있습니다.`);
                  repaint(memo);
                }}
              >
                {label}
              </Button>
            </div>
          );
        })}
        {uploads.map((entry) => (
          <div key={`up-${entry.local}`} className="file-row">
            <Icon name="file" />
            <span>
              <b>{entry.name}</b>
              <span className="tiny">{[sizeText(entry.size), entry.state === "failed" ? entry.message : UPLOAD_STATE[entry.state]].filter(Boolean).join(" · ")}</span>
            </span>
            <Button
              kind="ghost"
              aria-label={`${entry.name} 빼기`}
              onClick={() => {
                memo.uploads = uploadsOf(memo).filter((item) => item !== entry);
                // 붙이지 않은 업로드는 스스로 만료되지만 먼저 지워 둔다.
                if (entry.uploadId) void discardUpload(entry.uploadId);
                repaint(memo);
              }}
            >
              빼기
            </Button>
          </div>
        ))}
      </div>
    </div>
  );
}

/**
 * `{ memo, courseId, purpose, max, existing, hint, onChange }` → `{ node, pending(), uploadIds(),
 * keepIds(), attachments(), changed(), recheck() }`. `existing`는 `[{ fileId, name, sizeBytes }]`.
 */
export function attachPicker({ memo, courseId = null, purpose, max = 5, existing = [], hint = "", onChange = () => {} }: AttachPickerOptions): AttachPickerHandle {
  uploadsOf(memo);
  const current = Array.isArray(existing) ? existing.filter((file) => file && file.fileId != null) : [];
  keptOf(memo, current);
  const uploadIds = () => uploadsOf(memo).flatMap((entry) => (entry.state === "clean" && entry.uploadId != null ? [entry.uploadId] : []));
  const keepIds = () => current.filter((file) => keptOf(memo, current).has(file.fileId)).map((file) => file.fileId);
  return {
    node: <AttachPicker memo={memo} current={current} courseId={courseId} purpose={purpose} max={max} hint={hint} onChange={onChange} />,
    pending: () => uploadsOf(memo).some((entry) => entry.state === "sending" || entry.state === "quarantined"),
    uploadIds,
    keepIds,
    attachments: () => ({ keep: keepIds(), add: uploadIds() }),
    changed: () => uploadIds().length > 0 || current.some((file) => !keptOf(memo, current).has(file.fileId)),
    recheck() {
      for (const entry of uploadsOf(memo)) {
        if (!entry.uploadId || entry.state !== "clean") continue;
        getUpload(entry.uploadId).then((view) => { track(memo, entry, view); repaint(memo); }, (error: unknown) => {
          entry.state = error instanceof ApiError && error.status === 404 ? "gone" : "failed";
          entry.message = uploadFailure(error);
          repaint(memo);
        });
      }
    },
  };
}
