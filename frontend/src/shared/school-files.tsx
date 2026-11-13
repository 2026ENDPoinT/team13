// One attachment of an item imported from the school's e-Class (a material, a notice): preview and download only.
// Study tools and summaries stay off (the school publishes none, and an uncopied file has a string id).
import type { ReactNode } from "react";
import { Link } from "../router.tsx";
import { hrefFor, find } from "../manifest.ts";
import { downloadControl, sizeText } from "./uploads-remote.tsx";
import { schoolFileRef, type SchoolFile } from "./school-content.tsx";

export function SchoolFileRow({ kj, courseId, file, schoolUrl }: { kj: string; courseId: number | string; file: SchoolFile; schoolUrl: string | null | undefined }): ReactNode {
  const preview = hrefFor(find("65_file_preview"), { n: schoolFileRef(file), kj });
  return (
    <article className="file-row srv-material-file" data-file={String(file.fileId)}>
      <div>
        <b>{file.name}</b>
        <p className="tiny">{[String(file.extension || "").toUpperCase(), sizeText(file.sizeBytes)].filter(Boolean).join(" · ")}</p>
      </div>
      <div className="btn-row">
        <Link href={preview} className="btn btn-secondary">미리보기</Link>
        {downloadControl(courseId, { fileId: file.fileId, name: file.name, schoolUrl })}
      </div>
    </article>
  );
}
