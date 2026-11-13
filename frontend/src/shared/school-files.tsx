// One attachment of an item imported from the school's e-Class (a material, a notice): preview and download only.
// Study tools and summaries stay off (the school publishes none, and an uncopied file has a string id).
import type { ReactNode } from "react";
import { hrefFor, find } from "../manifest.ts";
import { AttachmentRow } from "./attachment-row.tsx";
import { downloadControl, sizeText } from "./uploads-remote.tsx";
import { schoolFileRef, type SchoolFile } from "./school-content.tsx";

export function SchoolFileRow({ kj, courseId, file, schoolUrl }: { kj: string; courseId: number | string; file: SchoolFile; schoolUrl: string | null | undefined }): ReactNode {
  return (
    <AttachmentRow
      fileKey={String(file.fileId)}
      name={file.name}
      extension={file.extension}
      meta={sizeText(file.sizeBytes)}
      preview={hrefFor(find("65_file_preview"), { n: schoolFileRef(file), kj })}
      download={downloadControl(courseId, { fileId: file.fileId, name: file.name, schoolUrl }, "icon")}
    />
  );
}
