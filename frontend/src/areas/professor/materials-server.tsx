// The professor's server material list (강의자료 → 자료 등록, backend `ready`): one entry per
// material with edit (title, week, description, publish, attachment set keep/add) and delete,
// both with `If-Match` from the last read, and the summary job panel of each of its PDFs.
// Typed edits live in memory per material, so a 409/428 reload keeps them.
import { useEffect, useRef, useState, type ReactNode } from "react";
import { ApiError } from "../../shared/api.ts";
import { courseIdFor, roleIn, subscribe } from "../../shared/backend.ts";
import { SIGNED_OUT } from "../../shared/backend-prompt.tsx";
import { freshVisit, shownRows, useResource, type Entry } from "../../shared/remote-cache.ts";
import { rememberMaterials, saveRemoteMaterialFromUploads, type RemotePdfRecord } from "../../shared/course-pdfs-remote.ts";
import {
  materialLists, materialKey, materialsOf, materialChange, updateMaterial, deleteMaterial, isStaleVersion, materialMessage,
  type Material, type MaterialList,
} from "../../shared/materials-remote.ts";
import { forgetMaterialList } from "../../shared/materials-remote.ts";
import { uploadFile, awaitScan, discardUpload, uploadFailure, sizeText, type UploadView } from "../../shared/uploads-remote.tsx";
import { fileboxAttach, type FileboxAttachOptions } from "../../shared/filebox-attach.tsx";
import { backendStatus, backendState } from "../../shared/backend.ts";
import { routeFor } from "../../shared/routes.ts";
import { koDateTime } from "../../shared/home-remote.ts";
import { downloadControl } from "../../shared/uploads-remote.tsx";
import { serverPreview } from "../../shared/preview-remote.tsx";
import { courseById, type Course } from "../../data.ts";
import { getState, render } from "../../store.ts";
import { showErrors } from "../../forms.ts";
import { Link } from "../../router.tsx";
import { Button, Field, EmptyState, LoadingState, ErrorState } from "../../ui/kit.tsx";
import { Icon } from "../../ui/icon.tsx";
import type { ScreenCtx } from "../../types.ts";
import { llmConsentSection } from "./llm-consent-server.tsx";

type SummaryJobPanel = (record: RemotePdfRecord) => ReactNode;
type FileboxUpload = Parameters<FileboxAttachOptions["onAttach"]>[0][number];

const MAX_FILES = 10;
const UPLOAD_STATE: Record<string, string> = {
  sending: "올리는 중",
  quarantined: "검사 중",
  clean: "첨부 준비됨",
  rejected: "서버 검사를 통과하지 못했습니다. 이 파일은 붙일 수 없습니다.",
  slow: "검사가 오래 걸리고 있습니다. 잠시 후 다시 올려 보세요.",
  gone: "더 이상 쓸 수 없는 업로드입니다. 다시 올리세요.",
};

type PickState = "sending" | "quarantined" | "clean" | "rejected" | "slow" | "gone" | "failed";

interface Pick {
  name: string;
  size: number | undefined;
  state: PickState;
  uploadId: number | null;
  message: string;
}

interface Latest {
  pending?: boolean;
  title: string;
  weekNo?: number;
  published?: boolean;
  version?: number;
  files?: string[];
}

interface EditMemo {
  open: boolean;
  title: string;
  weekNo: string;
  description: string;
  published: boolean;
  keep: Set<number>;
  adds: Pick[];
  note: string;
  alert: boolean;
  latest: Latest | null;
  /** Re-renders the editor now showing this memo (uploads and scans outlive the editor that started them). */
  repaint: (() => void) | null;
}

interface RegistrationMemo {
  uploads: Pick[];
  busy: boolean;
  repaint: (() => void) | null;
}

// activityId -> typed edit state
const edits = new Map<number, EditMemo>();
const openEdits = new Set<number>(); // 수정 칸을 펼친 채로 열 자료(activityId)
// New material registration (자료 등록 form): file box picks per course key, kept across re-renders.
const registrations = new Map<string, RegistrationMemo>();
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  edits.clear();
  registrations.clear();
  openEdits.clear();
});

function fresh(material: Material): EditMemo {
  return {
    open: false,
    title: material.title,
    weekNo: String(material.weekNo),
    description: material.description || "",
    published: material.published !== false,
    keep: new Set((material.files || []).map((file) => file.fileId)),
    adds: [],
    note: "",
    alert: false,
    latest: null,
    repaint: null,
  };
}

const known = (state: string): PickState => (state === "clean" || state === "quarantined" || state === "rejected" ? state : "gone");
const live = (entry: Pick) => !["rejected", "failed", "gone"].includes(entry.state);

/** Waits for the scan of a quarantined pick, repainting on each state. */
function scanPick(entry: Pick, view: UploadView, alive: () => boolean, repaint: () => void) {
  awaitScan(view, {
    alive,
    onState: (next) => { entry.state = known(next.state); repaint(); },
  }).then((last) => {
    if (last && last.state === "quarantined") entry.state = "slow";
    repaint();
  }, (error: unknown) => {
    entry.state = "failed";
    entry.message = uploadFailure(error);
    repaint();
  });
}

function pickOf(view: FileboxUpload): Pick {
  return { name: String(view.name || "file"), size: view.sizeBytes, state: known(view.state), uploadId: view.uploadId, message: "" };
}

function PickRows({ picks, busy, onRemove }: { picks: Pick[]; busy: boolean; onRemove: (entry: Pick) => void }) {
  return picks.map((entry, index) => {
    const state = entry.state === "failed" ? entry.message : UPLOAD_STATE[entry.state] || UPLOAD_STATE.gone;
    return (
      <div className="pf-activity" key={`${entry.uploadId ?? "p"}-${index}`}>
        <span className="pf-activity-copy"><Icon name="file" />
          <b>{entry.name}</b><span className="pf-meta">{` · ${[sizeText(entry.size), state].filter(Boolean).join(" · ")}`}</span></span>
        <Button kind="ghost" aria-label={`${entry.name} 빼기`} disabled={busy} onClick={() => onRemove(entry)}>빼기</Button>
      </div>
    );
  });
}

/**
 * The list section. `summaryJobPanel(record)` is professor.tsx's per-PDF summary control.
 * `open`(activityId)은 강의실 주별 보기의 `수정`에서 온 자료로, 그 자료와 수정 칸을 펼쳐 둔다.
 */
export function serverMaterialList(course: Course, summaryJobPanel: SummaryJobPanel, { open }: { open?: string | null } = {}): ReactNode {
  return <ServerMaterialList course={course} summaryJobPanel={summaryJobPanel} open={open} />;
}

function ServerMaterialList({ course, summaryJobPanel, open }: { course: Course; summaryJobPanel: SummaryJobPanel; open?: string | null }) {
  const courseId = courseIdFor(course.id);
  const instructor = roleIn(course.id) === "INSTRUCTOR";
  const key = courseId ? materialKey(courseId) : null;
  const entry = useResource(materialLists, key);
  const [opened, setOpened] = useState(() => {
    const set = new Set<number>();
    const openId = Number(open);
    if (Number.isInteger(openId) && openId > 0) {
      set.add(openId);
      const memo = edits.get(openId);
      if (memo) memo.open = true;
      else openEdits.add(openId);
    }
    return set;
  });
  const [first, setFirst] = useState(() => opened.size === 0);
  const [retrying, setRetrying] = useState(false);
  const load = () => { if (key && courseId) void materialLists.load(key, courseId); };

  useEffect(() => {
    if (!key || !courseId) return;
    if (freshVisit("pf-materials", key) === "entry" || !materialLists.peek(key)) void materialLists.load(key, courseId);
  }, [key, courseId]);

  // After a write the list reloads; its previous rows stay on screen for display only (no editor or summary panel).
  const { rows, stale } = shownRows(entry);
  const materials = materialsOf(rows);
  useEffect(() => {
    if (!first || !materials.length) return;
    setFirst(false);
    if (!opened.size) setOpened(new Set([materials[0].activityId]));
  });
  useEffect(() => { if (!entry?.loading) setRetrying(false); }, [entry?.loading]);

  let body: ReactNode;
  if (!courseId) body = <p className="pf-sub">이 과목은 ASTRA 서버에 없습니다.</p>;
  else if (!rows) {
    body = !entry || !entry.error
      ? <p className="pf-sub" role="status">서버에 등록된 자료를 불러오는 중입니다.</p>
      : <>
          <p className="err" role="alert">{materialMessage(entry.error)}</p>
          <Button kind="ghost" disabled={retrying} onClick={() => { setRetrying(true); load(); }}>다시 불러오기</Button>
        </>;
  } else {
    const records = stale ? [] : rememberMaterials(materials, course.id, courseId);
    const failed = entry && entry.error
      ? <>
          <p className="err" role="alert">{materialMessage(entry.error)}</p>
          {stale ? <Button kind="ghost" disabled={retrying} onClick={() => { setRetrying(true); load(); }}>다시 불러오기</Button> : null}
        </>
      : stale ? <p className="pf-sub" role="status">저장한 내용을 반영한 목록을 불러오는 중입니다.</p> : null;
    body = !materials.length
      ? <>{failed}<p className="pf-sub">아직 서버에 등록된 자료가 없습니다.</p></>
      : <>
          {failed}
          {materials.map((material) => {
            const files = Array.isArray(material.files) ? material.files : [];
            const isOpen = opened.has(material.activityId);
            return (
              <details className="pf-server-pdf" key={material.activityId} open={isOpen} onToggle={(event) => {
                const now = event.currentTarget.open;
                if (now === opened.has(material.activityId)) return;
                const next = new Set(opened);
                if (now) next.add(material.activityId);
                else next.delete(material.activityId);
                setOpened(next);
              }}>
                <summary>
                  <b>{material.title}</b>
                  <span className="pf-meta">{` · ${material.weekNo}주차 · 파일 ${files.length}개${material.published ? "" : " · 비공개"}`}</span>
                </summary>
                {isOpen && stale ? <p className="pf-sub">이 자료의 최신 내용을 불러온 뒤 수정할 수 있습니다.</p> : null}
                {isOpen && !stale ? <>
                  {instructor ? <MaterialEditor courseId={courseId} material={material} reload={load} /> : null}
                  {records.filter((item) => item.activityId === material.activityId).map((record) => (
                    <div className="pf-summary" key={record.id}><h3>{record.fileName}</h3>{summaryJobPanel(record)}</div>
                  ))}
                </> : null}
              </details>
            );
          })}
        </>;
  }

  return (
    <section className="sheet" aria-label="ASTRA 서버에 등록된 자료">
      <h2>서버에 등록된 자료</h2>
      <p className="pf-sub">{instructor
        ? "수정과 삭제는 마지막으로 불러온 버전을 기준으로 합니다. 그 사이 다른 곳에서 고쳤으면 아무것도 바꾸지 않고 최신 내용을 불러옵니다. 삭제한 자료의 PDF는 학생 학습실에서도 바로 사라집니다."
        : "이 과목을 담당하는 교수 계정으로 로그인해야 자료를 등록·수정하고 요약을 만들 수 있습니다."}</p>
      <div className="stack" aria-live="polite">{body}</div>
    </section>
  );
}

function memoFor(material: Material): EditMemo {
  const id = material.activityId;
  let memo = edits.get(id);
  if (!memo) {
    memo = fresh(material);
    if (openEdits.delete(id)) memo.open = true;
    edits.set(id, memo);
  }
  return memo;
}

function MaterialEditor({ courseId, material, reload }: { courseId: number | string; material: Material; reload: () => void }) {
  const id = material.activityId;
  const memo = memoFor(material);
  const files = Array.isArray(material.files) ? material.files : [];
  // A file the server no longer has cannot be kept.
  for (const fileId of [...memo.keep]) if (!files.some((file) => file.fileId === fileId)) memo.keep.delete(fileId);
  // A conflict reload: show what the server has now, next to the kept typed text.
  if (memo.latest && memo.latest.pending && !materialLists.peek(materialKey(courseId))?.loading) {
    memo.latest = { title: material.title, weekNo: material.weekNo, published: material.published, version: material.version, files: files.map((file) => file.name) };
  }
  const [, setTick] = useState(0);
  useEffect(() => {
    const own = () => setTick((n) => n + 1);
    memo.repaint = own;
    return () => { if (memo.repaint === own) memo.repaint = null; };
  }, [memo]);
  const repaint = () => memo.repaint?.();
  const [busy, setBusy] = useState(false);
  const [armed, setArmed] = useState(false);
  const [saveLabel, setSaveLabel] = useState("수정 저장");
  const [removeLabel, setRemoveLabel] = useState("자료 삭제");
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const alive = () => edits.get(id) === memo;

  const say = (text: string, alert = false) => {
    memo.note = text || "";
    memo.alert = alert;
    repaint();
  };
  const pending = () => memo.adds.some((entry) => entry.state === "sending" || entry.state === "quarantined");

  const pickFiles = (input: HTMLInputElement) => {
    const picked = [...(input.files || [])];
    input.value = "";
    for (const file of picked) {
      const entry: Pick = { name: String(file.name || "file"), size: file.size, state: "sending", uploadId: null, message: "" };
      memo.adds.push(entry);
      uploadFile(file, courseId, "lecture_material").then((view) => {
        entry.uploadId = view.uploadId;
        entry.state = known(view.state);
        repaint();
        if (entry.state === "quarantined") scanPick(entry, view, () => memo.adds.includes(entry), repaint);
      }).catch((error: unknown) => {
        entry.state = "failed";
        entry.message = uploadFailure(error);
        repaint();
      });
    }
    repaint();
  };

  // After a stale write: read the list again; the typed state stays in `edits`.
  const reloadKeeping = (message: string) => {
    say(message, true);
    reload();
  };

  const saveEdit = async () => {
    if (busy) return;
    const text = memo.title.trim();
    const weekNo = Number(memo.weekNo);
    if (!text || text.length > 300) return say("자료명은 1자 이상 300자 이하입니다.", true);
    if (!Number.isInteger(weekNo) || weekNo < 1 || weekNo > 60) return say("주차는 1부터 60 사이입니다.", true);
    if (memo.description.length > 10000) return say("설명은 10,000자 이하입니다.", true);
    if (pending()) return say("추가한 파일의 검사가 끝난 뒤 저장하세요.", true);
    const add = memo.adds.filter((entry) => entry.state === "clean" && entry.uploadId != null).map((entry) => entry.uploadId as number);
    if (memo.keep.size + add.length > MAX_FILES) return say(`첨부는 ${MAX_FILES}개까지입니다.`, true);
    setBusy(true);
    setSaveLabel("저장하는 중…");
    say("ASTRA 서버에 저장하고 있습니다.");
    try {
      await updateMaterial(courseId, id, material.version, materialChange(material, {
        title: text, weekNo, description: memo.description, published: memo.published, keep: memo.keep, add,
      }));
      edits.delete(id);
      getState().toast = "자료를 수정했습니다";
      render();
      reload();
    } catch (error) {
      if (isStaleVersion(error)) {
        memo.latest = { title: "", pending: true };
        reloadKeeping(materialMessage(error));
      } else if (error instanceof ApiError && error.status === 404) reloadKeeping(materialMessage(error));
      else say(materialMessage(error), true);
    } finally {
      if (mounted.current) {
        setBusy(false);
        setSaveLabel("수정 저장");
      }
    }
  };

  /** Two presses: the first arms the button, the second sends DELETE with the last-read version. */
  const removeEntry = async () => {
    if (busy) return;
    if (!armed) {
      setArmed(true);
      setRemoveLabel("한 번 더 누르면 삭제");
      say("삭제하면 이 자료와 파일, 쪽별 요약이 학생과 교수 모두에게서 바로 사라지고 되돌릴 수 없습니다.", true);
      return;
    }
    setBusy(true);
    setRemoveLabel("삭제하는 중…");
    try {
      await deleteMaterial(courseId, id, material.version);
      edits.delete(id);
      getState().toast = "자료를 삭제했습니다. 학생 학습실에서도 사라졌습니다.";
      render();
      reload();
    } catch (error) {
      if (isStaleVersion(error)) reloadKeeping("다른 곳에서 이 자료를 먼저 고쳤습니다. 최신 내용을 불러왔으니 확인한 뒤 다시 삭제하세요.");
      else if (error instanceof ApiError && error.status === 404) {
        edits.delete(id);
        getState().toast = "이미 삭제된 자료입니다. 목록을 새로 불러왔습니다.";
        render();
        reload();
      } else say(materialMessage(error), true);
    } finally {
      if (mounted.current) {
        setBusy(false);
        setArmed(false);
        setRemoveLabel("자료 삭제");
      }
    }
  };

  // Files picked from the file box join the same add list, wait for the scan like an upload and go out
  // as `attachments.add`; kept files count against MAX_FILES too.
  const fromBox = fileboxAttach({
    courseId,
    purpose: "lecture_material",
    limit: MAX_FILES,
    disabled: busy,
    room: () => MAX_FILES - memo.keep.size - memo.adds.filter(live).length,
    onAttach: (views) => {
      for (const view of views) {
        const entry = pickOf(view);
        memo.adds.push(entry);
        if (entry.state === "quarantined") scanPick(entry, view, () => memo.adds.includes(entry), repaint);
      }
      repaint();
    },
  });

  return (
    <details className="pf-material-edit" open={memo.open} aria-busy={busy ? "true" : "false"} onToggle={(event) => { memo.open = event.currentTarget.open; }}>
      <summary><b>자료 수정·삭제</b><span className="pf-meta">{` · 버전 ${material.version}`}</span></summary>
      <div className="pf-form">
        <div className="span-2"><Field label="자료명">
          <input maxLength={300} defaultValue={memo.title} aria-label="자료명" disabled={busy} onInput={(event) => { memo.title = event.currentTarget.value; }} />
        </Field></div>
        <Field label="주차">
          <input type="number" min="1" max="60" defaultValue={memo.weekNo} aria-label="주차" disabled={busy} onInput={(event) => { memo.weekNo = event.currentTarget.value; }} />
        </Field>
        <label className="choice pf-check">
          <input type="checkbox" defaultChecked={memo.published} disabled={busy} onChange={(event) => { memo.published = event.currentTarget.checked; }} />
          <span>학생에게 공개</span>
        </label>
        <div className="span-2"><Field label="설명">
          <textarea rows={4} maxLength={10000} aria-label="설명" defaultValue={memo.description} disabled={busy} onInput={(event) => { memo.description = event.currentTarget.value; }} />
        </Field></div>
        <div className="span-2">
          <p className="pf-sub">첨부 파일 (체크를 풀면 저장할 때 이 자료에서 빠지고 파일도 지워집니다)</p>
          <div className="stack">
            {files.map((file) => (
              <label className="choice pf-check" key={file.fileId}>
                <input type="checkbox" defaultChecked={memo.keep.has(file.fileId)} disabled={busy} onChange={(event) => {
                  if (event.currentTarget.checked) memo.keep.add(file.fileId);
                  else memo.keep.delete(file.fileId);
                }} />
                <span>{`${file.name} · ${sizeText(file.sizeBytes)} 유지`}</span>
              </label>
            ))}
          </div>
        </div>
        <div className="span-2">
          <Field label="파일 추가">
            <input type="file" multiple aria-label="추가할 파일" disabled={busy} onChange={(event) => {
              // Handled here: the app-wide file listener would remember the name in saved sample state.
              event.stopPropagation();
              pickFiles(event.currentTarget);
            }} />
          </Field>
          {fromBox}
          <div className="stack">
            <PickRows picks={memo.adds} busy={busy} onRemove={(entry) => {
              memo.adds = memo.adds.filter((item) => item !== entry);
              if (entry.uploadId) void discardUpload(entry.uploadId);
              repaint();
            }} />
          </div>
        </div>
        <div className="span-2"><div data-material-latest="">{memo.latest ? <LatestView latest={memo.latest} /> : null}</div></div>
        <div className="span-2">
          <p className={memo.alert ? "err" : "pf-sub"} role={memo.alert ? "alert" : "status"} aria-live="polite">{memo.note}</p>
        </div>
        <div className="span-2"><div className="pf-actions">
          <Button kind="primary" disabled={busy} onClick={() => { if (alive()) void saveEdit(); }}>{saveLabel}</Button>
          <Button kind="danger" aria-label={`${material.title} 삭제`} disabled={busy} onClick={() => { if (alive()) void removeEntry(); }}>{removeLabel}</Button>
        </div></div>
      </div>
    </details>
  );
}

function LatestView({ latest }: { latest: Latest }) {
  if (latest.pending) return <p className="pf-sub">서버의 최신 내용을 불러오는 중입니다.</p>;
  const files = latest.files || [];
  return (
    <div className="pf-server-pdf">
      <p className="pf-sub"><b>서버의 최신 내용</b>{` · 버전 ${latest.version}`}</p>
      <p className="pf-sub">{`${latest.title} · ${latest.weekNo}주차 · ${latest.published ? "공개" : "비공개"}`}</p>
      <p className="pf-sub">{files.length ? `파일: ${files.join(", ")}` : "파일 없음"}</p>
    </div>
  );
}

// ---------- new material from file box files (자료 등록) ----------

function registrationOf(kj: string): RegistrationMemo {
  let memo = registrations.get(kj);
  if (!memo) {
    memo = { uploads: [], busy: false, repaint: null };
    registrations.set(kj, memo);
  }
  return memo;
}

/**
 * "파일함에서 첨부" for the new-material form (professor.tsx `materialFields`). Null unless the backend is
 * `ready` and this account teaches the course. Picks stay in memory per course until registered.
 */
export function registrationAttach(course: Course | null | undefined): ReactNode {
  const kj = course && course.id;
  const courseId = courseIdFor(kj);
  if (!kj || !courseId || roleIn(kj) !== "INSTRUCTOR" || backendStatus() !== "ready") return null;
  return <RegistrationAttach kj={kj} courseId={courseId} />;
}

function RegistrationAttach({ kj, courseId }: { kj: string; courseId: number | string }) {
  const memo = registrationOf(kj);
  const [, setTick] = useState(0);
  const repaint = () => setTick((n) => n + 1);
  useEffect(() => {
    const own = () => setTick((n) => n + 1);
    memo.repaint = own;
    return () => { if (memo.repaint === own) memo.repaint = null; };
  }, [memo]);
  const notify = () => { if (memo.repaint) memo.repaint(); };
  const active = () => memo.uploads.filter(live).length;
  return (
    <div className="span-2 stack">
      <div className="pf-actions">
        {fileboxAttach({
          courseId,
          purpose: "lecture_material",
          limit: MAX_FILES,
          disabled: memo.busy,
          room: () => MAX_FILES - active(),
          onAttach: (views) => {
            for (const view of views) {
              const entry = pickOf(view);
              memo.uploads.push(entry);
              if (entry.state === "quarantined") scanPick(entry, view, () => memo.uploads.includes(entry), notify);
            }
            repaint();
          },
        })}
        <span className="pf-sub">{`파일함의 파일을 ${MAX_FILES}개까지 이 자료에 붙입니다. 파일함 파일을 고르면 위의 파일 칸은 비워 두세요.`}</span>
      </div>
      <div className="stack" data-material-picks="">
        <PickRows picks={memo.uploads} busy={memo.busy} onRemove={(entry) => {
          memo.uploads = memo.uploads.filter((item) => item !== entry);
          if (entry.uploadId) void discardUpload(entry.uploadId);
          repaint();
        }} />
      </div>
    </div>
  );
}

/** True when the new-material form of course `kj` holds file box picks (professor.tsx `saveMaterial`). */
export function registrationPicked(kj: string): boolean {
  const memo = registrations.get(kj);
  return Boolean(memo && memo.uploads.length);
}

/**
 * `pf-save` (material form) with file box picks: one server material with every clean pick
 * (`saveRemoteMaterialFromUploads`). `say` sets the form's status line; the caller keeps its controls
 * disabled and its button labelled "서버에 등록하는 중…" while this runs. True once the material was created:
 * the caller then clears its form and repaints.
 */
export async function saveRegistrationFromFilebox({ title, file }: { title: string; file: File | null }, say: (text: string) => void): Promise<boolean> {
  const state = getState();
  const kj = state.courseId;
  const memo = registrationOf(kj);
  if (memo.busy) return false;
  const fail = (errors: Record<string, string>) => {
    say(Object.values(errors).join(". "));
    showErrors(errors);
    return false;
  };
  if (!title) return fail({ title: "자료명을 입력하세요" });
  if (file) return fail({ file: "직접 고른 파일과 파일함 파일은 따로 등록하세요. 한쪽을 비운 뒤 다시 등록하세요." });
  if (memo.uploads.some((entry) => entry.state === "sending" || entry.state === "quarantined")) return fail({ file: "파일함 파일의 검사가 끝난 뒤 등록하세요." });
  const ready = memo.uploads.filter((entry) => entry.state === "clean" && entry.uploadId != null);
  if (!ready.length) return fail({ file: "등록할 수 있는 파일함 파일이 없습니다. 빼고 다시 고르세요." });
  if (ready.length > MAX_FILES) return fail({ file: `파일은 ${MAX_FILES}개까지 등록할 수 있습니다.` });
  memo.busy = true;
  if (memo.repaint) memo.repaint();
  say("파일함 파일로 이 과목의 자료를 등록하고 있습니다.");
  try {
    const { records } = await saveRemoteMaterialFromUploads({
      uploads: ready.map((entry) => ({ uploadId: entry.uploadId as number, state: "clean", name: entry.name, sizeBytes: entry.size ?? 0 })),
      courseId: kj,
      title,
      weekNo: state.week || 1,
    });
    memo.uploads = [];
    state.toast = records.length
      ? "파일함 파일로 자료를 등록했습니다. 아래 목록에서 쪽별 요약을 만들 수 있습니다."
      : "파일함 파일로 자료를 등록했습니다";
    const backendCourseId = courseIdFor(kj);
    if (backendCourseId != null) forgetMaterialList(backendCourseId);
    return true;
  } catch (error) {
    const message = (error instanceof Error && error.message) || "자료를 등록하지 못했습니다. 잠시 후 다시 시도하세요.";
    say(message);
    showErrors({ file: message });
    return false;
  } finally {
    memo.busy = false;
    if (memo.repaint) memo.repaint();
  }
}

// ---------- 자료 미리보기 (`pf_pdf`)와 학습실 (`pf_study`) ----------

const pfHref = (screenId: string, extra?: Record<string, string>) => routeFor("professor", "screen", { screenId, ...(extra || {}) });

function PlainText({ text }: { text: string }) {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  return (
    <div className="pf-prose">
      {blocks.map((block, index) => (
        <p key={index}>{block.split("\n").map((line, at) => <span key={at}>{at ? <br /> : null}{line}</span>)}</p>
      ))}
    </div>
  );
}

function materialGate(course: Course, text: string): ReactNode {
  if (backendStatus() === "anon") return SIGNED_OUT;
  if (!courseIdFor(course.id)) return <EmptyState title="이 과목은 ASTRA 서버에 없습니다" body="서버에 등록된 과목의 자료만 볼 수 있습니다." />;
  if (roleIn(course.id) !== "INSTRUCTOR") return <div className="stack"><p className="pf-sub">이 과목을 담당하는 교수 계정으로 로그인해야 자료와 요약을 관리할 수 있습니다.</p></div>;
  return null;
}

/**
 * `pf_pdf`(서버 과목): `?material=<activityId>`(강의실에서 온 자료) 또는 `?n=srv-<fileId>`(그 파일이 든 자료)의 상세.
 * 파일마다 내려받기, 첫 PDF의 서버 미리보기, PDF별 쪽별 요약 작업과 게시 상태, 이 과목의 외부 AI 처리 동의를 보인다.
 */
export function serverMaterialDetail(ctx: ScreenCtx, course: Course, summaryJobPanel: SummaryJobPanel): ReactNode {
  const blocked = materialGate(course, "ASTRA 서버에 로그인하면 이 과목의 자료와 쪽별 요약을 볼 수 있습니다.");
  if (blocked) return blocked;
  const courseId = courseIdFor(course.id);
  if (courseId == null) return null;
  const activityId = Number(ctx.params && ctx.params.get("material"));
  const fileRef = /^srv-([1-9]\d{0,15})$/.exec(String((ctx.params && ctx.params.get("n")) || ""));
  const fileId = fileRef ? Number(fileRef[1]) : null;
  if (!(Number.isInteger(activityId) && activityId > 0) && !fileId) {
    return <div className="stack"><p className="pf-sub">아래 목록이나 강의실 주별 보기에서 자료를 고르세요.</p>{serverMaterialList(course, summaryJobPanel)}</div>;
  }
  return <ServerMaterialDetail course={course} courseId={courseId} activityId={activityId} fileId={fileId} summaryJobPanel={summaryJobPanel} />;
}

function ServerMaterialDetail({ course, courseId, activityId, fileId, summaryJobPanel }: {
  course: Course; courseId: number | string; activityId: number; fileId: number | null; summaryJobPanel: SummaryJobPanel;
}) {
  const key = materialKey(courseId);
  const load = () => { void materialLists.load(key, courseId); };
  const entry: Entry<MaterialList> | null = useResource(materialLists, key, load);
  const [retrying, setRetrying] = useState(false);
  useEffect(() => {
    if (freshVisit("pf-material-detail", key) === "entry" || !materialLists.peek(key)) load();
  }, [key]);
  useEffect(() => { if (!entry?.loading) setRetrying(false); }, [entry?.loading]);

  let body: ReactNode;
  const data = entry && entry.data;
  if (!data) {
    if (!entry || !entry.error) body = <LoadingState title="자료를 불러오는 중입니다" />;
    else if (entry.error instanceof ApiError && entry.error.status === 404) body = <EmptyState title="자료를 볼 수 없습니다" body="이 과목을 담당하는 교수만 볼 수 있습니다." />;
    else {
      body = <ErrorState title="자료를 불러오지 못했습니다" body={materialMessage(entry.error)}
        actions={<Button kind="ghost" disabled={retrying} onClick={() => { setRetrying(true); load(); }}>다시 불러오기</Button>} />;
    }
  } else {
    const materials = materialsOf(data);
    const records = rememberMaterials(materials, course.id, courseId);
    const material = materials.find((item) => (activityId > 0 && item.activityId === activityId)
      || (fileId && (item.files || []).some((file) => file.fileId === fileId)));
    if (!material) {
      body = <EmptyState title="자료를 찾을 수 없습니다" body="이미 삭제되었거나 이 과목의 자료가 아닙니다."
        actions={<Link href={pfHref("pf_material_write", { kj: course.id })} className="btn btn-secondary">자료 목록</Link>} />;
    } else {
      const files = Array.isArray(material.files) ? material.files : [];
      const firstPdf = files.find((file) => file.pdf) || null;
      body = <>
        <h2>{material.title}</h2>
        <p className="pf-sub">{[`${material.weekNo}주차`, material.published ? "공개" : "비공개", material.openAt ? `${koDateTime(material.openAt)}부터` : "바로 공개", `파일 ${files.length}개`, `버전 ${material.version}`].join(" · ")}</p>
        {material.description ? <PlainText text={material.description} /> : null}
        <div className="pf-actions">
          <Link href={pfHref("pf_material_write", { kj: course.id, open: String(material.activityId) })} className="btn btn-secondary">수정·삭제</Link>
          <Link href={pfHref("pf_room_week", { kj: course.id })} className="btn btn-ghost">강의실</Link>
        </div>
        {files.length
          ? <ul className="pf-lecture-items">{files.map((file) => (
            <li key={file.fileId}>
              <b>{file.name}</b>
              <span className="pf-meta">{` · ${sizeText(file.sizeBytes)}${file.pdf ? " · PDF" : ""}`}</span>{" "}
              {downloadControl(courseId, file)}
            </li>
          ))}</ul>
          : <p className="pf-sub">첨부한 파일이 없습니다.</p>}
        {firstPdf ? <div className="stack"><h3>{`미리보기 · ${firstPdf.name}`}</h3>{serverPreview({ courseId, fileId: firstPdf.fileId, label: firstPdf.name, compact: true })}</div> : null}
        {records.filter((record) => record.activityId === material.activityId).map((record) => (
          <div className="pf-summary" key={record.id}><h3>{`쪽별 요약 · ${record.fileName}`}</h3>{summaryJobPanel(record)}</div>
        ))}
        {llmConsentSection(course)}
      </>;
    }
  }
  return <section className="sheet" aria-label="자료 상세">{body}</section>;
}

/** `pf_study`(서버): 담당 과목 고르기, 고른 과목의 외부 AI 처리 동의와 서버 자료(PDF별 쪽별 요약 작업). */
export function serverStudyRoom(ctx: ScreenCtx, summaryJobPanel: SummaryJobPanel, { frame }: { frame: (course: Course | null, body: ReactNode) => ReactNode }): ReactNode {
  if (backendStatus() === "anon") return frame(null, SIGNED_OUT);
  const taught = (backendState().courses || [])
    .filter((item) => item.role === "INSTRUCTOR" && item.key && courseById(item.key).id === item.key)
    .map((item) => courseById(item.key as string));
  const kj = (ctx.params && ctx.params.get("kj")) || "";
  const course = taught.find((item) => item.id === kj) || null;
  const picker = taught.length
    ? <div className="filters" role="group" aria-label="담당 과목">{taught.map((item) => {
      const on = Boolean(course && course.id === item.id);
      return <Link key={item.id} href={pfHref("pf_study", { kj: item.id })} className={`chip all${on ? " on" : ""}`} aria-current={on ? "page" : undefined}>{item.ko}</Link>;
    })}</div>
    : <EmptyState title="담당 과목이 없습니다" body="ASTRA 서버에 이 계정이 담당하는 과목이 없습니다." />;
  if (!course) return frame(null, <div className="stack">{picker}{taught.length ? <p className="pf-sub">과목을 고르면 그 과목의 서버 자료와 쪽별 요약, 외부 AI 처리 동의를 봅니다.</p> : null}</div>);
  return frame(course, <div className="stack">
    {picker}
    {llmConsentSection(course)}
    {serverMaterialList(course, summaryJobPanel)}
  </div>);
}
