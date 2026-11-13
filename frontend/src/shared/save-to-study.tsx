// 자료 "내려받기" 오른편의 "학습실에 저장" 버튼과 폴더 고르는 창(PDF 첨부만).
// 백엔드 `ready`(학습실이 서버에 있을 때): 이 과목의 PDF는 학습실 과목 화면에 이미 들어 있으므로, 저장은 그 PDF를
// 학생의 서버 폴더에 넣는 것(`PUT /api/me/study-room/placements/course-files/{fileId}`, study-folders.ts `placeItem`)이다.
// 버튼은 서버가 아는 위치(어느 폴더에 있는지)를 보여 준다. 폴더에서 빼는 함수가 아직 없어 "폴더 없이"는 고를 수 없다.
// 백엔드 없음(샘플), 또는 학습실 엔드포인트가 없는 백엔드(과목 view 404): **목업**이다. 서버에도 이 브라우저에도
// 저장하지 않고, 누른 파일 표시(`saved`)만 이 탭의 메모리에 둔다.
import { useEffect, useState, useSyncExternalStore, type FormEvent } from "react";
import { Button, Dialog } from "../ui/kit.tsx";
import { Icon } from "../ui/icon.tsx";
import { toast } from "../store.ts";
import { createFolder, folderState, getFolder, listFolders, placeItem, serverFolderOf, useStudyRoomStore, type StudyFolder } from "./study-folders.ts";
import { peekCourseView, refreshCourseView, serverStudyCourse, studyRoomMessage } from "./study-room-remote.ts";

const saved = new Map<string, string>();
const listeners = new Set<() => void>();
const subscribe = (fn: () => void) => {
  listeners.add(fn);
  return () => void listeners.delete(fn);
};
let version = 0;
const getVersion = () => version;

export interface SaveFile {
  fileId: number | string;
  name: string;
}

const ROOT = "";

/** `icon`: a blue upload icon button for attachment rows; `button`: the labelled button next to "내려받기". */
export type SaveVariant = "button" | "icon";

export function SaveTrigger({ variant, label, title, saved, where, disabled = false, onClick }: {
  variant: SaveVariant; label: string; title?: string; saved: boolean; where?: string; disabled?: boolean; onClick: () => void;
}) {
  if (variant === "icon") {
    return (
      <button type="button" className="icon-btn save-study-icon" aria-label={label} title={title ?? (saved ? "학습실에 저장됨" : "학습실에 저장")} data-saved={saved ? "" : undefined} disabled={disabled} onClick={onClick}>
        <Icon name={saved ? "check" : "upload"} />
      </button>
    );
  }
  return (
    <Button kind="secondary" className="save-study-btn" aria-label={label} title={title} disabled={disabled} onClick={onClick}>
      <Icon name={saved ? "check" : "folder"} />
      <span>{saved ? "학습실에 저장됨" : "학습실에 저장"}</span>
      {where ? <span className="save-study-where">{where}</span> : null}
    </Button>
  );
}

/** "학습실에 저장" 버튼. 서버 학습실이면 실제 폴더 배치, 아니면 목업. */
/** `autoOpen`: the folder dialog is open when it first shows (after a step the click already started, e.g. copying a school file). */
export function SaveToStudy({ kj, file, courseName, variant = "button", autoOpen = false }: { kj: string; file: SaveFile; courseName?: string; variant?: SaveVariant; autoOpen?: boolean }) {
  useStudyRoomStore();
  // Reading the folder state loads the course view; a 404 there marks the study-room endpoints missing, and
  // `serverStudyCourse` then answers null, so the button turns into the mock as soon as that is known.
  if (serverStudyCourse(kj) != null) folderState(kj);
  return serverStudyCourse(kj) != null
    ? <ServerSaveToStudy kj={kj} file={file} courseName={courseName} variant={variant} autoOpen={autoOpen} />
    : <MockSaveToStudy kj={kj} file={file} courseName={courseName} variant={variant} autoOpen={autoOpen} />;
}

// ---------- server: the student's folders on the ASTRA backend ----------

function ServerSaveToStudy({ kj, file, courseName, variant, autoOpen }: { kj: string; file: SaveFile; courseName?: string; variant: SaveVariant; autoOpen: boolean }) {
  const [open, setOpen] = useState(autoOpen);
  const itemId = `srv-${file.fileId}`;
  const where = serverFolderOf(kj, itemId);
  const folder = where ? getFolder(where, kj) : null;
  return (
    <>
      <SaveTrigger
        variant={variant}
        label={folder ? `${file.name} 학습실 ${folder.name} 폴더에 저장됨, 다른 폴더로 옮기기` : `${file.name} 학습실에 저장`}
        title={folder ? `학습실 · ${folder.name}` : undefined}
        saved={folder != null}
        where={folder?.name}
        onClick={() => setOpen(true)}
      />
      {open ? <ServerSaveDialog kj={kj} file={file} itemId={itemId} courseName={courseName} onClose={() => setOpen(false)} /> : null}
    </>
  );
}

function ServerSaveDialog({ kj, file, itemId, courseName, onClose }: {
  kj: string; file: SaveFile; itemId: string; courseName?: string; onClose: () => void;
}) {
  useStudyRoomStore();
  const { state, error } = folderState(kj);
  const server = serverStudyCourse(kj);
  // The cached view may be older than a move on another device: the dialog reloads it and shows the folders
  // only once that reload has answered.
  const [checking, setChecking] = useState(true);
  const check = () => {
    if (server == null) return;
    setChecking(true);
    void refreshCourseView(server).finally(() => setChecking(false));
  };
  useEffect(check, []);
  // A failed reload keeps the older view (still safe to save: a stale version answers 409).
  const staleError = !checking && server != null ? peekCourseView(server).error : null;
  const where = serverFolderOf(kj, itemId) || "";
  // Folders made here, shown and selectable before the background reload lists them.
  const [made, setMade] = useState<StudyFolder[]>([]);
  // Server folders only: a browser folder would keep the placement in this browser.
  const listed = listFolders(kj).filter((folder) => !folder.local);
  const folders = [...listed, ...made.filter((folder) => !listed.some((item) => item.id === folder.id))];
  const current = folders.find((folder) => folder.id === where) || null;
  const [choice, setChoice] = useState("");
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const selected = folders.find((folder) => folder.id === (choice || where)) || null;

  const save = async () => {
    if (!selected || selected.id === where) return;
    setBusy(true);
    setMessage("");
    try {
      await placeItem(itemId, selected.id, { retryOnConflict: false });
      toast(current
        ? `${file.name}을(를) 학습실 · ${selected.name} 폴더로 옮겼습니다.`
        : `${file.name}을(를) 학습실 · ${selected.name} 폴더에 저장했습니다.`);
      onClose();
    } catch (failure) {
      setMessage(failure instanceof Error ? failure.message : studyRoomMessage(failure));
      setBusy(false);
    }
  };
  const create = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setMessage("");
    try {
      const folder = await createFolder(kj, name);
      setMade((list) => [...list, folder]);
      setChoice(folder.id);
      setName("");
    } catch (failure) {
      setMessage(failure instanceof Error ? failure.message : studyRoomMessage(failure));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog title="학습실에 저장" label={`${file.name} 학습실에 저장`} className="save-study-dialog" onClose={onClose}>
      <div className="save-study">
        <div className="save-study-file">
          <Icon name="file" />
          <span><b>{file.name}</b>{courseName ? <span className="tiny">{courseName}</span> : null}</span>
        </div>
        {state === "loading" || (state === "ready" && checking)
          ? <p className="tiny" role="status">학습실에서 지금 위치를 확인하는 중입니다.</p>
          : null}
        {state === "failed" ? (
          <div className="save-study-failed" role="alert">
            <p className="tiny">{studyRoomMessage(error)}</p>
            <Button kind="ghost" onClick={check}>다시 불러오기</Button>
          </div>
        ) : null}
        {state === "ready" && !checking && staleError ? (
          <div className="save-study-failed" role="alert">
            <p className="tiny">{`최신 위치를 확인하지 못해 전에 불러온 내용을 보여 줍니다. ${studyRoomMessage(staleError)}`}</p>
            <Button kind="ghost" onClick={check}>다시 불러오기</Button>
          </div>
        ) : null}
        {state === "ready" && !checking ? (
          <>
            <p className="tiny save-study-note">
              {current
                ? `지금 ${current.name} 폴더에 있습니다. 다른 폴더를 고르면 그 폴더로 옮깁니다.`
                : "이 과목의 PDF는 학습실 과목 화면에 이미 있습니다. 폴더를 고르면 그 폴더 안에 넣습니다."}
            </p>
            <fieldset className="save-study-folders" disabled={busy}>
              <legend>저장할 폴더</legend>
              {folders.length ? folders.map((folder) => (
                <label key={folder.id}>
                  <input type="radio" name="save-study-folder" checked={selected?.id === folder.id} onChange={() => setChoice(folder.id)} />
                  <Icon name="folder" />{folder.name}{folder.id === where ? <span className="tiny">(지금 위치)</span> : null}
                </label>
              )) : <p className="tiny">이 과목 학습실에 폴더가 없습니다. 아래에서 새 폴더를 만드세요.</p>}
            </fieldset>
            <form className="save-study-new" onSubmit={(event) => void create(event)}>
              <input
                type="text" maxLength={60} placeholder="새 폴더 이름" aria-label="새 폴더 이름" value={name} disabled={busy}
                onChange={(event) => setName(event.target.value)}
              />
              <Button kind="secondary" type="submit" disabled={busy || !name.trim()}>폴더 만들기</Button>
            </form>
          </>
        ) : null}
        {message ? <p className="tiny save-study-error" role="alert">{message}</p> : null}
        <div className="btn-row save-study-actions">
          <Button kind="ghost" onClick={onClose}>취소</Button>
          <Button kind="primary" disabled={busy || checking || !selected || selected.id === where} onClick={() => void save()}>
            {current ? "옮기기" : "저장"}
          </Button>
        </div>
      </div>
    </Dialog>
  );
}

// ---------- sample mode (backend off): mock ----------

function MockSaveToStudy({ kj, file, courseName, variant, autoOpen }: { kj: string; file: SaveFile; courseName?: string; variant: SaveVariant; autoOpen: boolean }) {
  useSyncExternalStore(subscribe, getVersion);
  const [open, setOpen] = useState(autoOpen);
  const key = `${kj}:${file.fileId}`;
  const where = saved.get(key);
  return (
    <>
      <SaveTrigger
        variant={variant}
        label={`${file.name} ${where == null ? "학습실에 저장" : "학습실에 저장됨, 폴더 바꾸기"}`}
        saved={where != null}
        onClick={() => setOpen(true)}
      />
      {open ? (
        <SaveDialog
          kj={kj} file={file} courseName={courseName} current={where}
          onClose={() => setOpen(false)}
          onSave={(folderName) => {
            saved.set(key, folderName);
            version += 1;
            for (const fn of listeners) fn();
            setOpen(false);
            toast(`${file.name}을(를) 학습실${folderName ? ` · ${folderName}` : ""}에 저장했습니다.`);
          }}
        />
      ) : null}
    </>
  );
}

function SaveDialog({ kj, file, courseName, current, onClose, onSave }: {
  kj: string; file: SaveFile; courseName?: string; current: string | undefined; onClose: () => void; onSave: (folderName: string) => void;
}) {
  const folders = listFolders(kj);
  const [folder, setFolder] = useState(current ?? ROOT);
  return (
    <Dialog title="학습실에 저장" label={`${file.name} 학습실에 저장`} className="save-study-dialog" onClose={onClose}>
      <div className="save-study">
        <div className="save-study-file">
          <Icon name="file" />
          <span><b>{file.name}</b>{courseName ? <span className="tiny">{courseName}</span> : null}</span>
        </div>
        <fieldset className="save-study-folders">
          <legend>저장할 폴더</legend>
          <label><input type="radio" name="save-study-folder" checked={folder === ROOT} onChange={() => setFolder(ROOT)} />폴더 없이 학습실에 바로 저장</label>
          {folders.map((item) => (
            <label key={item.id}>
              <input type="radio" name="save-study-folder" checked={folder === item.name} onChange={() => setFolder(item.name)} />
              <Icon name="folder" />{item.name}
            </label>
          ))}
        </fieldset>
        <p className="tiny save-study-note" role="note">목업 화면입니다. 실제로는 아직 저장되지 않습니다.</p>
        <div className="btn-row save-study-actions">
          <Button kind="ghost" onClick={onClose}>취소</Button>
          <Button kind="primary" onClick={() => onSave(folder)}>저장</Button>
        </div>
      </div>
    </Dialog>
  );
}
