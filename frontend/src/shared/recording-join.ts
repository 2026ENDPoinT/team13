// "이어서 녹음" on a server recording: the joined file is uploaded as a new recording, then the original is deleted.
// This is the note shown when that delete did not simply succeed.
import { ApiError } from "./api.ts";

/**
 * The warning after the joined recording was saved and `DELETE` of the original (`title`) ended in `error`; null when
 * the delete succeeded (`error` null). `detail` words any other failure (studyRoomMessage).
 */
export function originalCleanupWarning(error: unknown, title: string, detail: (error: unknown) => string): string | null {
  if (error == null) return null;
  // Deleted in another tab or device while the continuation was recorded: the whole audio is back as a new recording.
  if (error instanceof ApiError && error.status === 404) {
    return `원래 녹음("${title}")은 이어서 녹음하는 동안 다른 탭이나 기기에서 이미 지워졌습니다. 앞부분까지 이어 붙인 전체 녹음을 같은 제목으로 원래 있던 곳에 새로 저장했습니다. 필요 없으면 지우세요.`;
  }
  // Changed (renamed, moved) elsewhere meanwhile: the If-Match version no longer matched (409), so it was left alone.
  if (error instanceof ApiError && error.code === "version_conflict") {
    return `이어 붙인 전체 녹음을 "${title}" 제목으로 새로 저장했습니다. 그 사이 다른 탭이나 기기에서 원래 녹음이 바뀌어(제목 변경 등) 원래 녹음은 지우지 않고 남겨 두었습니다. 원래 녹음에는 앞부분만 있으니 필요 없으면 지우세요.`;
  }
  return `이어 붙인 녹음을 저장했지만 원래 녹음을 지우지 못해 둘 다 남아 있습니다. 앞부분만 있는 "${title}" 녹음을 지우세요. ${detail(error)}`;
}
