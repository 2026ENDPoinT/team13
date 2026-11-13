// Folders the student makes inside a course's 학습실. One level only.
// Backend `ready`: new folders and placements are the signed-in user's rows on the server (study-room-remote.ts).
// Folders still in this browser's localStorage are shown next to them (marked `local`) until the student moves
// them to the account; nothing local is hidden or deleted because the backend is `ready`.
// An item with no placement sits in the course root.
import { useSyncExternalStore } from "react";
import { ApiError } from "./api.ts";
import { courseIdFor } from "./backend.ts";
import {
  courseViews, peekCourseView, cachedViews, createServerFolder, putPlacement, refreshCourseView, loadCourseView,
  serverStudyCourse, studyRoomOnServer, studyRoomMessage, isMissingEndpoint, type ServerFolder, type PlacementKind,
} from "./study-room-remote.ts";

const KEY = "astra-study-folders";

/** A student-made folder in a course's 학습실. `version` is set for server folders, `local` for browser folders while `ready`. */
export interface StudyFolder {
  id: string;
  courseId: string;
  name: string;
  createdAt: string;
  version?: number;
  local?: true;
}

/** What localStorage holds under `astra-study-folders`. */
interface FolderData {
  folders: StudyFolder[];
  /** Item id -> folder id. */
  placement: Record<string, string>;
}

function read(): FolderData {
  try {
    // Whatever an older build stored; every field is checked below.
    const value = JSON.parse(localStorage.getItem(KEY) || "null") as Partial<FolderData> | null;
    return {
      folders: Array.isArray(value?.folders) ? value.folders : [],
      placement: value?.placement && typeof value.placement === "object" ? value.placement : {},
    };
  } catch {
    return { folders: [], placement: {} };
  }
}

function write(data: FolderData): void {
  try {
    if (!data.folders.length && !Object.keys(data.placement).length) localStorage.removeItem(KEY);
    else localStorage.setItem(KEY, JSON.stringify(data));
  } catch {
    throw new Error("이 브라우저에 폴더를 저장할 수 없습니다. 사이트 데이터 설정을 확인하세요.");
  }
}

const fromServer = (folder: ServerFolder, kj: string): StudyFolder => ({
  id: String(folder.id), courseId: kj, name: folder.name, createdAt: folder.createdAt, version: folder.version,
});

/** Re-renders the caller whenever a server course view changes (folders, placements, recordings). */
export function useStudyRoomStore(): void {
  useSyncExternalStore(courseViews.subscribe, courseViews.version);
}

/**
 * Whether the folders of `courseId` can be read now: "ready" (browser store, or the server view is loaded),
 * "loading" (the server view is on its way; a load starts when none is cached) or "failed".
 */
export function folderState(courseId: string): { state: "ready" | "loading" | "failed"; error: unknown } {
  const server = serverStudyCourse(courseId);
  if (server == null) return { state: "ready", error: null };
  const { view, error } = peekCourseView(server);
  if (view) return { state: "ready", error: null };
  // A backend without the study room (404): the browser store is used from now on.
  if (error && isMissingEndpoint(error)) return { state: "ready", error: null };
  return error ? { state: "failed", error } : { state: "loading", error: null };
}

/** Folders of this browser for `courseId` (whatever the backend state). */
export const localFolders = (courseId: string): StudyFolder[] => read().folders.filter((folder) => folder.courseId === courseId);

/** Every folder of this browser, all courses, in their order. */
export const browserFolders = (): StudyFolder[] => read().folders;

/** Server folders first (server order), then this browser's folders of the course marked `local`. */
export function listFolders(courseId: string): StudyFolder[] {
  const server = serverStudyCourse(courseId);
  if (server == null) return localFolders(courseId);
  const { view } = peekCourseView(server);
  return [
    ...(view ? view.folders.map((folder) => fromServer(folder, courseId)) : []),
    ...localFolders(courseId).map((folder): StudyFolder => ({ ...folder, local: true })),
  ];
}

export const getFolder = (id: string, courseId: string): StudyFolder | null => listFolders(courseId).find((folder) => folder.id === id) || null;

/** Item id -> folder id for every placed item (server placements and this browser's). Read once per render. */
export function placements(): Record<string, string> {
  const local = read().placement;
  if (!studyRoomOnServer()) return local;
  const placed: Record<string, string> = { ...local };
  for (const view of cachedViews()) {
    for (const item of view.placements) placed[item.kind === "course_file" ? `srv-${item.itemId}` : String(item.itemId)] = String(item.folderId);
  }
  return placed;
}

export const folderOf = (itemId: string): string => placements()[itemId] || "";

export async function createFolder(courseId: string, name: string | null | undefined): Promise<StudyFolder> {
  const label = String(name || "").trim();
  if (!label) throw new Error("폴더 이름을 입력하세요.");
  const server = serverStudyCourse(courseId);
  if (server != null) {
    try {
      return fromServer(await createServerFolder(server, label), courseId);
    } catch (error) {
      throw new Error(studyRoomMessage(error));
    }
  }
  const data = read();
  if (data.folders.some((folder) => folder.courseId === courseId && folder.name === label)) {
    throw new Error("같은 이름의 폴더가 이미 있습니다.");
  }
  const folder: StudyFolder = { id: crypto.randomUUID(), courseId, name: label, createdAt: new Date().toISOString() };
  data.folders.push(folder);
  write(data);
  return folder;
}

/** The server placement target of a study-room item id: `srv-<fileId>` (course PDF) or a server recording id. */
function serverItem(itemId: string): { kind: PlacementKind; id: string; placementKind: string } | null {
  const pdf = /^srv-([1-9][0-9]*)$/.exec(itemId);
  if (pdf) return { kind: "course-files", id: pdf[1], placementKind: "course_file" };
  return /^[1-9][0-9]*$/.test(itemId) ? { kind: "recordings", id: itemId, placementKind: "recording" } : null;
}

function placementVersion(placementKind: string, id: string): number {
  for (const view of cachedViews()) {
    const found = view.placements.find((item) => item.kind === placementKind && String(item.itemId) === id);
    if (found) return found.version;
  }
  return 0;
}

const isLocalFolder = (folderId: string) => read().folders.some((folder) => folder.id === folderId);

/** Records a placement in this browser only (an item or folder that is not on the server yet). */
export function placeLocally(itemId: string, folderId: string): void {
  const data = read();
  data.placement[itemId] = folderId;
  write(data);
}

/**
 * Puts an item into a folder. A browser folder, or an item that is only in this browser, keeps the placement here;
 * otherwise `PUT …/placements` with the version the view holds, retried once after a reload on 409.
 */
export async function placeItem(itemId: string, folderId: string | null | undefined): Promise<void> {
  if (!folderId) return;
  const item = serverItem(itemId);
  if (!studyRoomOnServer() || !item || isLocalFolder(folderId)) {
    placeLocally(itemId, folderId);
    return;
  }
  const course = cachedViews().find((view) => view.folders.some((folder) => String(folder.id) === folderId));
  try {
    try {
      await putPlacement(item.kind, item.id, folderId, placementVersion(item.placementKind, item.id));
    } catch (error) {
      if (!(error instanceof ApiError && error.code === "version_conflict") || !course) throw error;
      await loadCourseView(course.courseId);
      await putPlacement(item.kind, item.id, folderId, placementVersion(item.placementKind, item.id));
    }
  } catch (error) {
    throw new Error(studyRoomMessage(error));
  } finally {
    if (course) void refreshCourseView(course.courseId);
  }
}

// ---------- moving browser folders to the account ----------

/** The browser folder `folderId` when it is one, for the move. */
export const localFolder = (folderId: string): StudyFolder | null => read().folders.find((folder) => folder.id === folderId) || null;

/** This browser's placements (item id -> folder id). */
export const localPlacements = (): Record<string, string> => read().placement;

/**
 * Makes the server folder for a browser folder: `POST …/folders` with `Idempotency-Key: import-folder-<localId>`,
 * or on 409 `name_conflict` the server folder with exactly the same name. Resolves the server folder id.
 * The browser folder stays until `forgetMoved` finds nothing left in it.
 */
export async function moveFolderToAccount(folder: StudyFolder): Promise<string> {
  const courseId = courseIdFor(folder.courseId);
  if (courseId == null) throw new Error("이 과목은 ASTRA 서버에 없어 폴더를 옮길 수 없습니다.");
  try {
    return String((await createServerFolder(courseId, folder.name, `import-folder-${folder.id}`)).id);
  } catch (error) {
    if (error instanceof ApiError && error.code === "name_conflict") {
      const same = (await loadCourseView(courseId)).folders.find((item) => item.name === folder.name);
      if (same) return String(same.id);
    }
    throw error;
  }
}

/**
 * Removes from this browser what is now on the server: the placements of `itemIds`, and the browser folders of
 * `movedFolders` that no remaining placement points to. Everything else stays.
 */
export function forgetMoved(itemIds: Iterable<string>, movedFolders: Iterable<string>): void {
  const data = read();
  for (const id of itemIds) delete data.placement[id];
  const used = new Set(Object.values(data.placement));
  const moved = new Set(movedFolders);
  data.folders = data.folders.filter((folder) => !moved.has(folder.id) || used.has(folder.id));
  write(data);
}
