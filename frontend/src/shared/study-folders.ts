// Folders the student makes inside a course's 학습실. One level only.
// Folders and item placement live in this browser's localStorage; PDFs and recordings stay in
// IndexedDB and are placed by id, so an item with no placement sits in the course root.
const KEY = "astra-study-folders";

/** A student-made folder in a course's 학습실. */
export interface StudyFolder {
  id: string;
  courseId: string;
  name: string;
  createdAt: string;
}

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
    localStorage.setItem(KEY, JSON.stringify(data));
  } catch {
    throw new Error("이 브라우저에 폴더를 저장할 수 없습니다. 사이트 데이터 설정을 확인하세요.");
  }
}

export const listFolders = (courseId: string): StudyFolder[] => read().folders.filter((folder) => folder.courseId === courseId);

export const getFolder = (id: string, courseId: string): StudyFolder | null => read().folders.find((folder) => folder.id === id && folder.courseId === courseId) || null;

/** Item id -> folder id for every placed item. Read once per render instead of per item. */
export const placements = (): Record<string, string> => read().placement;

export const folderOf = (itemId: string): string => placements()[itemId] || "";

export function createFolder(courseId: string, name: string | null | undefined): StudyFolder {
  const label = String(name || "").trim();
  if (!label) throw new Error("폴더 이름을 입력하세요.");
  const data = read();
  if (data.folders.some((folder) => folder.courseId === courseId && folder.name === label)) {
    throw new Error("같은 이름의 폴더가 이미 있습니다.");
  }
  const folder: StudyFolder = { id: crypto.randomUUID(), courseId, name: label, createdAt: new Date().toISOString() };
  data.folders.push(folder);
  write(data);
  return folder;
}

export function placeItem(itemId: string, folderId: string | null | undefined): void {
  if (!folderId) return;
  const data = read();
  data.placement[itemId] = folderId;
  write(data);
}
