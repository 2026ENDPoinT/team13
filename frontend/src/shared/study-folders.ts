// Folders the student makes inside a course's 학습실. One level only.
// Backend `ready`: new folders and placements are the signed-in user's rows on the server (study-room-remote.ts).
// Folders still in this browser's localStorage stay visible until the student moves them to the account; nothing
// local is hidden or deleted because the backend is `ready`. An item with no placement sits in the course root.
import { useSyncExternalStore } from "react";
import { ApiError } from "./api.ts";
import { courseIdFor, currentUserId } from "./backend.ts";
import {
  courseViews, peekCourseView, cachedViews, createServerFolder, deleteServerFolder, putPlacement, deletePlacement, refreshCourseView, loadCourseView,
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
  /** The account signed in when a browser folder was made while `ready` (none for folders made signed out or before). */
  ownerUserId?: string;
  /** The server folder this browser folder was moved to; it stays here only for items that could not move. */
  movedTo?: string;
}

/** What localStorage holds under `astra-study-folders`. */
interface FolderData {
  folders: StudyFolder[];
  /** Item id -> folder id. */
  placement: Record<string, string>;
  /** Item id -> why its placement cannot move to the account (the server answered 404); not sent again. */
  unmovable: Record<string, string>;
}

const objectOr = (value: unknown): Record<string, string> => (value && typeof value === "object" ? value as Record<string, string> : {});

function read(): FolderData {
  try {
    // Whatever an older build stored; every field is checked below.
    const value = JSON.parse(localStorage.getItem(KEY) || "null") as Partial<FolderData> | null;
    return {
      folders: Array.isArray(value?.folders) ? value.folders : [],
      placement: objectOr(value?.placement),
      unmovable: objectOr(value?.unmovable),
    };
  } catch {
    return { folders: [], placement: {}, unmovable: {} };
  }
}

function write(data: FolderData): void {
  try {
    for (const id of Object.keys(data.unmovable)) if (!(id in data.placement)) delete data.unmovable[id];
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
 * Whose a browser item (folder or recording) is while the study room is on the server: "mine" (made by the
 * signed-in user), "before-login" (made with nobody signed in, or before items carried an owner) or "other" (made
 * by another account). Offline, everything is "mine", as before.
 */
export type OwnerGroup = "mine" | "before-login" | "other";

export function ownerGroup(ownerUserId: string | undefined): OwnerGroup {
  if (!studyRoomOnServer()) return "mine";
  if (!ownerUserId) return "before-login";
  return ownerUserId === currentUserId() ? "mine" : "other";
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

/** Browser folders of a server course, marked `local` (offline: the plain browser folders). */
function markedLocalFolders(courseId: string): StudyFolder[] {
  return localFolders(courseId).map((folder): StudyFolder => ({ ...folder, local: true }));
}

/** The course's folders in the main grid: server folders (server order), then the signed-in user's own browser folders. */
export function listFolders(courseId: string): StudyFolder[] {
  const server = serverStudyCourse(courseId);
  if (server == null) return localFolders(courseId);
  const { view } = peekCourseView(server);
  return [
    ...(view ? view.folders.map((folder) => fromServer(folder, courseId)) : []),
    ...markedLocalFolders(courseId).filter((folder) => ownerGroup(folder.ownerUserId) === "mine"),
  ];
}

/** Browser folders of the course that belong to `group` ("before-login" or "other"), shown apart from the user's own. */
export const groupFolders = (courseId: string, group: OwnerGroup): StudyFolder[] => (serverStudyCourse(courseId) == null
  ? []
  : markedLocalFolders(courseId).filter((folder) => ownerGroup(folder.ownerUserId) === group));

/** Any folder of the course, the separate groups included, so a saved link to one keeps working. */
export function getFolder(id: string, courseId: string): StudyFolder | null {
  return listFolders(courseId).find((folder) => folder.id === id)
    || (serverStudyCourse(courseId) == null ? null : markedLocalFolders(courseId).find((folder) => folder.id === id) || null);
}

/** Item id -> folder id for every placed item (server placements and this browser's). Read once per render. */
export function placements(): Record<string, string> {
  const local = read().placement;
  if (!studyRoomOnServer()) return local;
  const placed: Record<string, string> = { ...local };
  for (const view of cachedViews()) {
    for (const item of view.placements) placed[serverItemId(item.kind, item.itemId)] = String(item.folderId);
  }
  return placed;
}

/** Item id -> why its placement could not move to the account. */
export const unmovablePlacements = (): Record<string, string> => read().unmovable;

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
  const owner = currentUserId();
  const folder: StudyFolder = { id: crypto.randomUUID(), courseId, name: label, createdAt: new Date().toISOString(), ...(owner ? { ownerUserId: owner } : {}) };
  data.folders.push(folder);
  write(data);
  return folder;
}

/** The study-room item id of a server placement: `srv-<fileId>`, `doc-<documentId>` or the recording id. */
function serverItemId(kind: string, itemId: number): string {
  return kind === "course_file" ? `srv-${itemId}` : kind === "document" ? `doc-${itemId}` : String(itemId);
}

/** The server placement target of a study-room item id: `srv-<fileId>` (course PDF), `doc-<documentId>` (the student's own PDF) or a server recording id. */
function serverItem(itemId: string): { kind: PlacementKind; id: string; placementKind: string } | null {
  const pdf = /^srv-([1-9][0-9]*)$/.exec(itemId);
  if (pdf) return { kind: "course-files", id: pdf[1], placementKind: "course_file" };
  const own = /^doc-([1-9][0-9]*)$/.exec(itemId);
  if (own) return { kind: "documents", id: own[1], placementKind: "document" };
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

/** Thrown by `placeItem` when the server folder is gone (deleted in another tab or on another device). */
export const FOLDER_GONE = "이 폴더는 다른 탭이나 기기에서 지웠습니다. 다시 불러왔습니다. 다른 폴더를 고르세요.";

/**
 * Where the server holds `itemId` (`srv-<fileId>` or a recording id) in course `courseId`: its folder id, or "" in
 * the course root. Null while the study room is not on the server, for an id the server cannot place, or before the
 * course view is loaded (a load starts). Browser placements are not read.
 */
export function serverFolderOf(courseId: string, itemId: string): string | null {
  const server = serverStudyCourse(courseId);
  const item = serverItem(itemId);
  if (server == null || !item) return null;
  const { view } = peekCourseView(server);
  if (!view) return null;
  const found = view.placements.find((placement) => placement.kind === item.placementKind && String(placement.itemId) === item.id);
  return found ? String(found.folderId) : "";
}

/**
 * Puts an item into a folder. A browser folder, or an item that is only in this browser, keeps the placement here;
 * otherwise `PUT …/placements` with the version the view holds. On 409 the view is reloaded and, with
 * `retryOnConflict` (default), the PUT is sent once more; without it the conflict sentence is thrown, for a caller
 * whose user chose with the old placement on screen. A 404 for a folder the reloaded view no longer has throws
 * `FOLDER_GONE`.
 */
export async function placeItem(itemId: string, folderId: string | null | undefined, { retryOnConflict = true }: { retryOnConflict?: boolean } = {}): Promise<void> {
  if (!folderId) return;
  const item = serverItem(itemId);
  if (!studyRoomOnServer() || !item || isLocalFolder(folderId)) {
    placeLocally(itemId, folderId);
    return;
  }
  const course = cachedViews().find((view) => view.folders.some((folder) => String(folder.id) === folderId));
  // True while the cached view is newer than the last write (no refresh needed afterwards).
  let reloaded = false;
  const reload = async (courseId: number) => {
    await loadCourseView(courseId, true);
    reloaded = true;
  };
  try {
    try {
      await putPlacement(item.kind, item.id, folderId, placementVersion(item.placementKind, item.id));
    } catch (error) {
      if (!(error instanceof ApiError && error.code === "version_conflict") || !course) throw error;
      await reload(course.courseId);
      if (!retryOnConflict) throw error;
      reloaded = false;
      await putPlacement(item.kind, item.id, folderId, placementVersion(item.placementKind, item.id));
    }
  } catch (error) {
    if (course && error instanceof ApiError && error.status === 404) {
      if (!reloaded) await reload(course.courseId).catch(() => undefined);
      const view = cachedViews().find((candidate) => candidate.courseId === course.courseId);
      if (reloaded && view && !view.folders.some((folder) => String(folder.id) === folderId)) throw new Error(FOLDER_GONE);
    }
    throw new Error(studyRoomMessage(error));
  } finally {
    if (course && !reloaded) void refreshCourseView(course.courseId);
  }
}

/**
 * Takes a server item out of its folder back to the course root: `DELETE …/placements` with the version the view
 * holds. A 409 reloads the view and throws the conflict sentence (the user chose with the old placement on screen).
 * Nothing is sent for an item the view has in the root already.
 */
export async function unplaceItem(courseId: string, itemId: string): Promise<void> {
  const server = serverStudyCourse(courseId);
  const item = serverItem(itemId);
  if (server == null || !item) return;
  const version = placementVersion(item.placementKind, item.id);
  if (!version) return;
  let reloaded = false;
  try {
    await deletePlacement(item.kind, item.id, version);
  } catch (error) {
    if (error instanceof ApiError && (error.code === "version_conflict" || error.status === 404)) {
      await loadCourseView(server, true).catch(() => undefined);
      reloaded = true;
    }
    throw new Error(studyRoomMessage(error));
  } finally {
    if (!reloaded) void refreshCourseView(server);
  }
}

/** Takes an item that is only in this browser out of its folder, back to the course root. */
export function leaveFolder(itemId: string): void {
  const data = read();
  if (!(itemId in data.placement)) return;
  delete data.placement[itemId];
  write(data);
}

/**
 * Deletes a folder. A browser folder is removed here; a server folder with `DELETE …/folders/{id}`. Either way the
 * browser placements into it are dropped, so those items sit in the course root again.
 */
export async function deleteFolder(folder: StudyFolder): Promise<void> {
  if (!folder.local && folder.version != null) {
    const server = serverStudyCourse(folder.courseId);
    if (server == null) throw new Error("ASTRA 서버에 연결되어 있지 않아 이 폴더를 지울 수 없습니다.");
    try {
      await deleteServerFolder({ id: folder.id, version: folder.version, courseId: server });
    } catch (error) {
      throw new Error(studyRoomMessage(error));
    }
  }
  const data = read();
  const before = data.folders.length;
  data.folders = data.folders.filter((item) => item.id !== folder.id);
  const orphaned = Object.keys(data.placement).filter((itemId) => data.placement[itemId] === folder.id);
  for (const itemId of orphaned) delete data.placement[itemId];
  if (data.folders.length !== before || orphaned.length) write(data);
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
  for (let attempt = 0; ; attempt += 1) {
    try {
      return String((await createServerFolder(courseId, folder.name, `import-folder-${folder.id}`)).id);
    } catch (error) {
      // The first request with this key is still running (another tab): ask again with the same key.
      if (error instanceof ApiError && error.code === "idempotency_in_progress" && attempt < 3) {
        await new Promise<void>((resolve) => { window.setTimeout(resolve, 1500); });
        continue;
      }
      if (error instanceof ApiError && error.code === "name_conflict") {
        const same = (await loadCourseView(courseId, true)).folders.find((item) => item.name === folder.name);
        if (same) return String(same.id);
      }
      // 410 `folder_deleted`: made by an earlier move and deleted on the server since; the browser folder stays.
      throw error;
    }
  }
}

/** Keeps a placement that can never move (the server answered 404) with its reason, so it is not sent again. */
export function markUnmovable(itemId: string, reason: string): void {
  const data = read();
  if (!(itemId in data.placement)) return;
  data.unmovable[itemId] = reason;
  write(data);
}

/**
 * Removes from this browser what is now on the server: the placements of `itemIds`, and the browser folders of
 * `movedFolders` (local id -> server id) that no remaining placement points to; a moved folder that still holds
 * something that could not move stays, marked `movedTo`. Everything else stays.
 */
export function forgetMoved(itemIds: Iterable<string>, movedFolders: Map<string, string>): void {
  const data = read();
  for (const id of itemIds) delete data.placement[id];
  const used = new Set(Object.values(data.placement));
  data.folders = data.folders
    .filter((folder) => !movedFolders.has(folder.id) || used.has(folder.id))
    .map((folder) => (movedFolders.has(folder.id) ? { ...folder, movedTo: movedFolders.get(folder.id) } : folder));
  write(data);
}
