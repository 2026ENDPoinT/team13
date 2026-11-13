// One attachment of an item imported from the school's e-Class (a material, a notice): preview and download, and for a
// material's PDF the "학습실에 저장" upload icon next to them. A school file has a string id (`school-file-<n>`) until the
// backend has copied it (the first content request does that); only a copied file (a native number) can go into the
// study room, so the icon copies it first and then opens the folder dialog. Summaries stay off (the school publishes none).
import { useEffect, useRef, useState, type ReactNode } from "react";
import { hrefFor, find } from "../manifest.ts";
import { ApiError } from "./api.ts";
import { AttachmentRow } from "./attachment-row.tsx";
import { downloadControl, fileBlob, sizeText } from "./uploads-remote.tsx";
import { materialKey, materialLists } from "./materials-remote.ts";
import { SaveToStudy, SaveTrigger } from "./save-to-study.tsx";
import { schoolFileRef, schoolMaterialsOf, schoolProblemOf, whileScanning, SchoolProblemNote, type SchoolFile } from "./school-content.tsx";

const isPdf = (file: SchoolFile): boolean => file.pdf === true || String(file.extension || file.name.split(".").pop() || "").toLowerCase() === "pdf";

/** The native id the backend gave `file` once it copied it: the same-named file of the same material in the materials list. */
async function copiedId(courseId: number | string, materialId: string, file: SchoolFile): Promise<number | null> {
  const key = materialKey(courseId);
  await materialLists.load(key, courseId);
  const material = schoolMaterialsOf(materialLists.peek(key)?.data).find((item) => item.activityId === materialId);
  const found = material?.files?.find((item) => item.name === file.name && typeof item.fileId === "number");
  return found && typeof found.fileId === "number" ? found.fileId : null;
}

function SchoolSaveToStudy({ kj, courseId, materialId, file, schoolUrl }: {
  kj: string; courseId: number | string; materialId: string; file: SchoolFile; schoolUrl: string | null | undefined;
}): ReactNode {
  const [copied, setCopied] = useState<number | null>(typeof file.fileId === "number" ? file.fileId : null);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState("");
  const [problem, setProblem] = useState<unknown>(null);
  // Opens the folder dialog once the copy finished, never for a file that was already copied when the page showed.
  const asked = useRef(false);
  const live = useRef(true);
  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
    };
  }, []);
  if (copied != null) return <SaveToStudy variant="icon" kj={kj} file={{ fileId: copied, name: file.name }} autoOpen={asked.current} />;
  const copy = () => {
    asked.current = true;
    setBusy(true);
    setProblem(null);
    setNote("학교에서 파일을 가져오는 중입니다.");
    whileScanning(async () => ((await fileBlob(courseId, file.fileId)) ? copiedId(courseId, materialId, file) : "gone" as const), {
      alive: () => live.current,
      onWait: () => setNote("학교에서 받은 파일을 검사하는 중입니다. 잠시 기다려 주세요."),
    }).then((id) => {
      if (!live.current) return;
      if (id === "gone") {
        setNote("파일을 찾을 수 없거나 받을 권한이 없습니다.");
        return;
      }
      if (id == null) {
        setNote("파일을 가져왔지만 학습실에 넣을 수 있는 파일로 확인하지 못했습니다. 잠시 후 다시 시도하세요.");
        return;
      }
      setNote("");
      setCopied(id);
    }, (error: unknown) => {
      if (!live.current) return;
      if (schoolProblemOf(error)) {
        setNote("");
        setProblem(error);
        return;
      }
      setNote(error instanceof ApiError && error.status === 404 ? "파일을 찾을 수 없거나 받을 권한이 없습니다." : "파일을 가져오지 못했습니다. 잠시 후 다시 시도하세요.");
    }).finally(() => {
      if (live.current) setBusy(false);
    });
  };
  return (
    <>
      <SaveTrigger variant="icon" label={`${file.name} 학습실에 저장`} saved={false} onClick={copy} disabled={busy} />
      <span className="tiny" role="status" aria-live="polite">{note}</span>
      {problem ? <SchoolProblemNote error={problem} schoolUrl={schoolUrl} retry={copy} compact /> : null}
    </>
  );
}

/** `materialId`: the imported material (`school-<n>`) the file belongs to; given, a PDF gets the "학습실에 저장" icon. */
export function SchoolFileRow({ kj, courseId, file, schoolUrl, materialId }: {
  kj: string; courseId: number | string; file: SchoolFile; schoolUrl: string | null | undefined; materialId?: string;
}): ReactNode {
  return (
    <AttachmentRow
      fileKey={String(file.fileId)}
      name={file.name}
      extension={file.extension}
      meta={sizeText(file.sizeBytes)}
      preview={hrefFor(find("65_file_preview"), { n: schoolFileRef(file), kj })}
      download={downloadControl(courseId, { fileId: file.fileId, name: file.name, schoolUrl }, "icon")}
      study={materialId && isPdf(file) ? <SchoolSaveToStudy kj={kj} courseId={courseId} materialId={materialId} file={file} schoolUrl={schoolUrl} /> : null}
    />
  );
}
