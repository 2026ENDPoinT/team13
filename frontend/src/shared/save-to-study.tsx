// 자료 "내려받기" 오른편의 "학습실에 저장" 버튼과 폴더 고르는 창. **목업**이다: 서버에도 이 브라우저에도 저장하지
// 않고, 누른 파일 표시(`saved`)만 이 탭의 메모리에 둔다. 저장 API가 생기면 `confirm`만 바꾼다.
import { useState, useSyncExternalStore } from "react";
import { Button, Dialog } from "../ui/kit.tsx";
import { Icon } from "../ui/icon.tsx";
import { toast } from "../store.ts";
import { listFolders } from "./study-folders.ts";

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

/** "학습실에 저장" 버튼. 저장하면 같은 자리가 "학습실에 저장됨"으로 바뀐다. */
export function SaveToStudy({ kj, file, courseName }: { kj: string; file: SaveFile; courseName?: string }) {
  useSyncExternalStore(subscribe, getVersion);
  const [open, setOpen] = useState(false);
  const key = `${kj}:${file.fileId}`;
  const where = saved.get(key);
  return (
    <>
      <Button
        kind="secondary" className="save-study-btn" aria-label={`${file.name} ${where == null ? "학습실에 저장" : "학습실에 저장됨, 폴더 바꾸기"}`}
        onClick={() => setOpen(true)}
      >
        <Icon name={where == null ? "folder" : "check"} />
        <span>{where == null ? "학습실에 저장" : "학습실에 저장됨"}</span>
      </Button>
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
