// My account on the ASTRA backend (`/api/me/profile`, `/profile/image`, `/profile/sync`, `/contact`,
// `/preferences`, `/sessions`, `/devices`, `/login-events`) and the re-authentication the backend asks for before sensitive
// commands (403 `step_up_required`, ADR-0029). Everything is the signed-in user's own data and
// stays in memory only; nothing here is written to storage, the URL or the console.
import { apiGet, apiSend, apiUpload, ApiError } from "./api.js";
import { backendState, backendStatus, connect, schoolLoginHref } from "./backend.js";
import { createResource, retryLater } from "./remote-cache.js";

export const ACCOUNT_PAGE = 20;

/** The backend's avatar colour allowlist (ProfileController.AVATAR_COLORS) with the original Korean names. */
export const AVATAR_COLORS = [
  ["tomato", "토마토"], ["tangerine", "귤"], ["pumpkin", "호박"], ["mango", "망고"], ["banana", "바나나"],
  ["chick", "병아리"], ["avocado", "아보카도"], ["pistachio", "피스타치오"], ["basil", "바질"],
  ["eucalyptus", "유칼립투스"], ["sage", "세이지"], ["peacock", "공작"], ["cobalt", "코발트"],
  ["blueberry", "블루베리"], ["lavender", "라벤더"], ["light-purple", "연보라"], ["amethyst", "자수정"],
  ["grape", "포도"], ["purple", "자주"], ["cherry-blossoms", "벚꽃"], ["light-pink", "연분홍"],
  ["cocoa", "코코아"], ["graphite", "흑연"], ["birch-tree", "자작나무"],
];
const COLOR_KEYS = new Set(AVATAR_COLORS.map(([key]) => key));

export function isAvatarColor(value) {
  return COLOR_KEYS.has(value);
}

const ONE = "me";

/** `GET /api/me/profile`. Every profile response (also the PUT/POST/DELETE ones below) carries `registrarSyncAvailable`. */
export const profiles = createResource(() => apiGet("/api/me/profile"));
export const preferences = createResource(() => apiGet("/api/me/preferences"));
export const sessions = createResource(() => apiGet("/api/me/sessions"));

// Cursor lists: every loaded page appended, `nextCursor` of the last one.
function pages(path) {
  return createResource(async (previous) => {
    const params = new URLSearchParams({ size: String(ACCOUNT_PAGE) });
    if (previous && previous.nextCursor) params.set("cursor", previous.nextCursor);
    const page = await apiGet(`${path}?${params}`);
    const items = Array.isArray(page && page.items) ? page.items : [];
    return { items: previous ? [...previous.items, ...items] : items, nextCursor: (page && page.nextCursor) || null };
  });
}
export const devices = pages("/api/me/devices");
export const loginEvents = pages("/api/me/login-events");

function live() {
  return backendStatus() === "ready";
}

/** Paints `node` from `resource` while attached; loads once per navigation and on focus. */
export function watchAccount(resource, scope, node, paint) {
  if (!live() || !node.isConnected) return;
  const reload = () => (resource === devices || resource === loginEvents ? resource.load(ONE, null) : resource.load(ONE));
  resource.watch(node, ONE, paint, reload);
  resource.visit(scope, ONE, null);
}

export function reloadAccount(resource) {
  if (!live()) return Promise.resolve();
  return resource === devices || resource === loginEvents ? resource.load(ONE, null) : resource.load(ONE);
}

/** Next page of a cursor list (devices, login events). */
export function loadMore(resource) {
  const entry = resource.peek(ONE);
  if (!live() || !entry || !entry.data || !entry.data.nextCursor || entry.loading) return Promise.resolve();
  return resource.load(ONE, entry.data);
}

export function peekAccount(resource) {
  return resource.peek(ONE);
}

function replace(resource, data) {
  resource.patch((key) => key === ONE, (current) => {
    for (const key of Object.keys(current)) delete current[key];
    Object.assign(current, data);
  });
}

/** Avatar colour and short name. `version` is `avatar.version` from the profile the dialog showed. */
export async function saveAvatar({ avatarColor, shortName, version }) {
  const profile = await apiSend("PUT", "/api/me/profile", { avatarColor, shortName, version });
  replace(profiles, profile);
  return profile;
}

/** Phone and e-mail. 403 `step_up_required` without a recent re-authentication. */
export async function saveContact({ phone, email }) {
  const profile = await apiSend("PUT", "/api/me/contact", { phone, email });
  replace(profiles, profile);
  return profile;
}

/** Full replacement with the `version` the form was loaded with (409 `version_conflict`). */
export async function savePreferences(body) {
  const saved = await apiSend("PUT", "/api/me/preferences", body);
  replace(preferences, saved);
  return saved;
}

export async function revokeSession(sessionId) {
  try {
    await apiSend("POST", `/api/me/sessions/${encodeURIComponent(sessionId)}/revoke`);
  } finally {
    reloadAccount(sessions);
  }
}

/** Ends every other session. 403 `step_up_required` without a recent re-authentication. */
export async function revokeOtherSessions() {
  const result = await apiSend("POST", "/api/me/sessions/revoke-others");
  reloadAccount(sessions);
  reloadAccount(devices);
  return result;
}

/**
 * Revokes a device and its sessions. When it was this browser the session cookie is gone, so the
 * connection is re-probed (it becomes `anon` and every screen shows the login strip).
 */
export async function revokeDevice(deviceId) {
  const result = await apiSend("DELETE", `/api/me/devices/${encodeURIComponent(deviceId)}`);
  if (result && result.currentSessionRevoked) {
    await connect();
    return result;
  }
  reloadAccount(devices);
  reloadAccount(sessions);
  return result;
}

/** Registrar sync (`POST /api/me/profile/sync`): the profile re-read from the school system goes into the cache. */
export async function syncProfile() {
  const profile = await apiSend("POST", "/api/me/profile/sync");
  replace(profiles, profile);
  return profile;
}

/** 503 `not_configured`: the registrar connection went away after the profile was read (a race); shown as a plain note, not as an error. */
export function isSyncUnavailable(error) {
  return error instanceof ApiError && error.status === 503 && error.code === "not_configured";
}

export function syncMessage(error) {
  if (isSyncUnavailable(error)) return "학사 시스템 연동이 아직 설정되지 않았습니다. 이 서버에서는 학사 정보를 동기화할 수 없습니다.";
  if (error instanceof ApiError) {
    if (error.code === "registrar_unavailable") return "학사 시스템에 연결하지 못했습니다. 잠시 후 다시 시도하세요.";
    if (error.code === "registrar_record_not_found") return "학사 시스템에서 내 정보를 찾지 못했습니다. 학사 담당 부서에 문의하세요.";
    if (error.status === 404) return "이 계정은 학사 정보와 연결되어 있지 않아 동기화할 수 없습니다.";
  }
  return accountMessage(error);
}

// ---------- avatar photo (ADR-0038) ----------

export const PHOTO_MAX_BYTES = 5 * 1024 * 1024;
export const PHOTO_TYPES = new Set(["image/png", "image/jpeg"]);

/**
 * Uploads one photo cropped on the page (`POST /api/me/profile/image`, multipart `file`, CSRF via
 * api.js). A replacing command, so no `Idempotency-Key`. After the 202 `{state:"processing"}` the
 * profile is re-read for `avatar.photo` and the new `avatar.version`.
 */
export async function uploadAvatarPhoto(blob, name) {
  const form = new FormData();
  form.append("file", blob, name);
  const result = await apiUpload("/api/me/profile/image", form);
  await reloadAccount(profiles);
  return result;
}

/** Removes the photo (`DELETE /api/me/profile/image`); the returned colour-avatar profile goes into the cache. */
export async function removeAvatarPhoto() {
  const profile = await apiSend("DELETE", "/api/me/profile/image");
  replace(profiles, profile);
  return profile;
}

/** `avatar.photo` (`{ state, url, errorCode }`) of the cached profile, or null. */
export function avatarPhoto() {
  const entry = profiles.peek(ONE);
  const avatar = entry && entry.data && entry.data.avatar;
  return (avatar && avatar.photo) || null;
}

const PHOTO_WAIT = [1000, 2000, 3000, 5000, 8000];
const PHOTO_WAIT_MAX = 120_000;

/**
 * Re-reads the profile while the last photo is `processing` and `node` is on screen (after 1, 2, 3,
 * 5 s, then every 8 s, for at most 2 minutes). Resolves to the final state (`ready`, `failed`, null
 * when the photo is gone), or `processing` when the wait ran out or the node left the document.
 */
export async function awaitAvatarPhoto(node) {
  const started = Date.now();
  for (let round = 0; ; round += 1) {
    const photo = avatarPhoto();
    if (!photo || photo.state !== "processing") return photo ? photo.state : null;
    if (!live() || !node.isConnected || Date.now() - started > PHOTO_WAIT_MAX) return "processing";
    await new Promise((resolve) => setTimeout(resolve, PHOTO_WAIT[Math.min(round, PHOTO_WAIT.length - 1)]));
    if (!live() || !node.isConnected) return "processing";
    await profiles.load(ONE);
  }
}

const PHOTO_ERROR = {
  not_image: "PNG나 JPEG 그림이 아니어서 쓸 수 없습니다. 다른 사진을 고르세요.",
  too_large: "사진이 너무 큽니다. 5MB 이하의 더 작은 사진을 고르세요.",
  rejected: "보안 검사를 통과하지 못해 이 사진은 쓸 수 없습니다.",
  unavailable: "사진 처리 서버에 연결하지 못했습니다. 잠시 후 다시 올리세요.",
  failed: "사진을 처리하지 못했습니다. 다른 사진으로 다시 시도하세요.",
};

/** Korean text for `avatar.photo.errorCode` or a failed upload request (400 `not_image`, 413 `too_large`, 429). */
export function photoMessage(problem) {
  if (typeof problem === "string" || problem == null) return PHOTO_ERROR[problem] || PHOTO_ERROR.failed;
  if (problem instanceof ApiError && PHOTO_ERROR[problem.code]) return PHOTO_ERROR[problem.code];
  if (problem instanceof ApiError && problem.status === 413) return PHOTO_ERROR.too_large;
  return accountMessage(problem);
}

// ---------- re-authentication (step-up) ----------

export function isStepUpRequired(error) {
  return error instanceof ApiError && error.status === 403 && error.code === "step_up_required";
}

/**
 * How this session can re-authenticate, from `GET /api/me` `stepUpMethod`: `["totp"]` (break-glass),
 * `["idp"]` (school IdP, a page navigation that comes back to `returnTo`), `["dev"]` (local backend
 * only), or `[]` when the server offers none for this session.
 */
export function stepUpMethods() {
  const { me } = backendState();
  const method = me && me.stepUpMethod;
  return method === "totp" || method === "idp" || method === "dev" ? [method] : [];
}

/**
 * A failed school IdP re-authentication comes back to the page it started from with `reauth=failed`
 * (the dialog address with `step=<command>`). Returns that `step` ("" when absent) once and removes
 * `reauth` and `step` from the address without a new history entry; null when the address has no marker.
 */
export function takeReauthFailure() {
  const params = new URLSearchParams(location.search);
  if (params.get("reauth") !== "failed") return null;
  const step = params.get("step") || "";
  params.delete("reauth");
  params.delete("step");
  const query = params.toString();
  history.replaceState(history.state, "", `${location.pathname}${query ? `?${query}` : ""}${location.hash}`);
  return step;
}

/** `/api/auth/reauth?returnTo=…` for `path` (this page by default), with the same checks as the login link. */
export function reauthHref(path) {
  const href = path ? schoolLoginHref({ pathname: path.split("?")[0], search: path.includes("?") ? path.slice(path.indexOf("?")) : "" }) : schoolLoginHref();
  return href.replace(/^\/api\/auth\/login\?/, "/api/auth/reauth?");
}

/** School IdP re-authentication: a top-level navigation; the backend returns to `path`. */
export function startReauth(path) {
  location.assign(reauthHref(path));
}

/** Local backend only: records a re-authentication on this session (`POST /api/me/step-up/dev`). */
export function stepUpDev() {
  return apiSend("POST", "/api/me/step-up/dev");
}

/** Break-glass session: TOTP re-authentication (`POST /api/me/step-up/totp`). */
export function stepUpTotp(code) {
  return apiSend("POST", "/api/me/step-up/totp", { code: String(code || "").trim() });
}

// ---------- labels and messages ----------

export function isVersionConflict(error) {
  return error instanceof ApiError && error.status === 409 && error.code === "version_conflict";
}

/** Korean message for a failed account request. */
export function accountMessage(error) {
  if (!(error instanceof ApiError)) return error && error.message ? error.message : "요청을 처리하지 못했습니다.";
  if (error.code === "step_up_required") return "본인 확인이 필요합니다. 다시 인증한 뒤 저장하세요.";
  if (error.code === "contact_managed_by_registrar") return "학사 시스템에서 관리하는 연락처입니다. 학사 시스템의 개인정보 변경에서 바꾸세요.";
  if (error.code === "current_session") return "지금 쓰는 세션은 로그아웃으로 끝내 주세요.";
  if (error.code === "verification_failed") return "인증 코드가 맞지 않습니다.";
  if (error.code === "locked") return retryLater(error);
  switch (error.status) {
    case 400: return error.detail || "입력한 값을 확인하세요.";
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 404: return "찾을 수 없습니다. 이미 끝났거나 지운 항목일 수 있습니다.";
    case 409: return error.code === "version_conflict"
      ? "다른 창에서 먼저 바꾸었습니다. 최신 값을 불러왔습니다. 고른 내용은 그대로 두었으니 확인한 뒤 다시 저장하세요."
      : error.detail || "요청을 처리하지 못했습니다. 새로 불러온 뒤 다시 시도하세요.";
    case 429: return retryLater(error);
    case 0: return error.message;
    default: return "요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.";
  }
}

export const THEME_LABEL = { system: "기기 기본값", light: "라이트", dark: "다크" };
export const LANGUAGE_LABEL = { ko: "한국어", en: "English" };
export const TIME_LABEL = { 12: "12시간제", 24: "24시간제" };
export const CLIENT_LABEL = { browser: "브라우저", app: "앱" };
