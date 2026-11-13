// Same-origin client for the ASTRA backend behind server.mjs (`/api/**`).
// Only relative paths are accepted. The session cookie is HttpOnly and never read
// here; the CSRF cookie is read only to echo it in X-XSRF-TOKEN (double submit).
// Nothing from a response is written to localStorage, IndexedDB or the console.

export class ApiError extends Error {
  constructor(status, { code = null, detail = "", retryAfter = null, problem = null } = {}) {
    super(detail || `요청을 처리하지 못했습니다 (${status || "network"})`);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.detail = detail;
    this.retryAfter = retryAfter;
    // The whole problem JSON, for codes that carry extra fields (per-row errors, reasons).
    this.problem = problem;
    // 400 `idempotency_key_required` is a client bug the user cannot fix; show a plain sentence.
    if (code === "idempotency_key_required") this.detail = "요청을 처리하지 못했습니다. 다시 시도하세요.";
  }
}

let unauthorized = null;
/** backend.js registers this to flip the connection to `anon` on any 401. */
export function onUnauthorized(handler) {
  unauthorized = typeof handler === "function" ? handler : null;
}

function relative(path) {
  const text = String(path || "");
  if (!text.startsWith("/") || text.startsWith("//") || text.includes("\\")) throw new ApiError(0, { detail: "잘못된 요청 경로입니다." });
  return text;
}

function cookie(name) {
  if (typeof document === "undefined" || typeof document.cookie !== "string") return "";
  for (const part of document.cookie.split(";")) {
    const eq = part.indexOf("=");
    if (eq === -1) continue;
    if (part.slice(0, eq).trim() !== name) continue;
    try {
      return decodeURIComponent(part.slice(eq + 1).trim());
    } catch {
      return part.slice(eq + 1).trim();
    }
  }
  return "";
}

let csrfLoad = null;
function csrfToken(force) {
  if (!force && cookie("XSRF-TOKEN")) return Promise.resolve(cookie("XSRF-TOKEN"));
  if (!force && csrfLoad) return csrfLoad;
  csrfLoad = fetch("/api/auth/csrf", { method: "GET", credentials: "same-origin", cache: "no-store", headers: { Accept: "application/json" } })
    .then(async (response) => {
      if (!response.ok) throw await problem(response);
      return cookie("XSRF-TOKEN");
    })
    .finally(() => { csrfLoad = null; });
  return csrfLoad;
}

/**
 * After a 403 on a request that carried `sent` as its CSRF token: the new token when it differs, else null.
 * The backend answers a CSRF failure and any other denial with the same code (`forbidden`), so only a changed token
 * shows the 403 came from a stale token; with the same token the request is not sent again.
 */
async function rotatedToken(sent) {
  let fresh;
  try {
    fresh = await csrfToken(true);
  } catch {
    return null;
  }
  return fresh && fresh !== sent ? fresh : null;
}

async function problem(response) {
  let body = null;
  try {
    const type = response.headers.get("content-type") || "";
    if (/json/i.test(type)) body = await response.json();
  } catch {
    body = null;
  }
  const retry = Number(response.headers.get("retry-after"));
  return new ApiError(response.status, {
    code: body && typeof body.code === "string" ? body.code : null,
    detail: body && typeof body.detail === "string" ? body.detail : "",
    retryAfter: Number.isFinite(retry) && retry >= 0 ? retry : null,
    problem: body && typeof body === "object" && !Array.isArray(body) ? body : null,
  });
}

async function request(method, path, { json, body, headers = {}, as = "json" } = {}) {
  const url = relative(path);
  if (typeof fetch !== "function") throw new ApiError(0, { detail: "이 환경에서는 서버에 연결할 수 없습니다." });
  const mutating = method !== "GET" && method !== "HEAD";
  const payload = json !== undefined ? JSON.stringify(json) : body;
  const base = { Accept: as === "blob" ? "*/*" : "application/json", ...headers };
  if (json !== undefined) base["Content-Type"] = "application/json";
  let token = mutating ? await csrfToken(false) : null;
  for (let attempt = 0; ; attempt += 1) {
    const sent = { ...base };
    if (mutating) sent["X-XSRF-TOKEN"] = token;
    let response;
    try {
      response = await fetch(url, { method, credentials: "same-origin", cache: "no-store", headers: sent, body: payload });
    } catch {
      throw new ApiError(0, { detail: "ASTRA 서버에 연결하지 못했습니다. 네트워크 상태를 확인하세요." });
    }
    if (response.status === 403 && mutating && attempt === 0) {
      const fresh = await rotatedToken(token);
      if (fresh) { token = fresh; continue; } // the token had rotated: send once more with the new one
    }
    if (response.status === 401 && unauthorized) unauthorized();
    if (!response.ok) throw await problem(response);
    if (as === "blob") return response.blob();
    if (response.status === 204 || method === "HEAD") return null;
    const type = response.headers.get("content-type") || "";
    return /json/i.test(type) ? response.json() : null;
  }
}

export function apiGet(path) {
  return request("GET", path);
}

/** Random request key for `Idempotency-Key` (and upload keys). */
export function newRequestKey() {
  if (globalThis.crypto && typeof crypto.randomUUID === "function") return crypto.randomUUID();
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

/**
 * `idempotencyKey` is sent as `Idempotency-Key`; reuse it only to retry the same request.
 * `ifMatch` is the resource `version` the screen last read, sent as `If-Match: "<version>"`.
 */
export function apiSend(method, path, json, { idempotencyKey, ifMatch } = {}) {
  const verb = String(method || "").toUpperCase();
  if (!["POST", "PUT", "PATCH", "DELETE"].includes(verb)) throw new ApiError(0, { detail: "지원하지 않는 요청입니다." });
  const headers = idempotencyKey ? { "Idempotency-Key": String(idempotencyKey) } : {};
  if (ifMatch != null) {
    const version = Number(ifMatch);
    if (!Number.isInteger(version) || version < 0) throw new ApiError(0, { detail: "자료 버전을 확인하지 못했습니다. 목록을 새로 불러오세요." });
    headers["If-Match"] = `"${version}"`;
  }
  return request(verb, path, json === undefined ? { headers } : { json, headers });
}

export function apiUpload(path, formData, { idempotencyKey } = {}) {
  const headers = {};
  if (idempotencyKey) headers["Idempotency-Key"] = String(idempotencyKey);
  return request("POST", path, { body: formData, headers });
}

export function apiBlob(path) {
  return request("GET", path, { as: "blob" });
}

/**
 * POST without a body that also reports the success status and `Retry-After`, for commands that
 * answer 202 "still working, ask again" (document preview sessions). Resolves `{ status, retryAfter, body }`.
 */
export async function apiPostStatus(path) {
  const url = relative(path);
  if (typeof fetch !== "function") throw new ApiError(0, { detail: "이 환경에서는 서버에 연결할 수 없습니다." });
  let token = await csrfToken(false);
  for (let attempt = 0; ; attempt += 1) {
    const headers = { Accept: "application/json", "X-XSRF-TOKEN": token };
    let response;
    try {
      response = await fetch(url, { method: "POST", credentials: "same-origin", cache: "no-store", headers });
    } catch {
      throw new ApiError(0, { detail: "ASTRA 서버에 연결하지 못했습니다. 네트워크 상태를 확인하세요." });
    }
    if (response.status === 403 && attempt === 0) {
      const fresh = await rotatedToken(token);
      if (fresh) { token = fresh; continue; } // the token had rotated: send once more with the new one
    }
    if (response.status === 401 && unauthorized) unauthorized();
    if (!response.ok) throw await problem(response);
    const retry = Number(response.headers.get("retry-after"));
    const type = response.headers.get("content-type") || "";
    return {
      status: response.status,
      retryAfter: Number.isFinite(retry) && retry > 0 ? retry : null,
      body: response.status !== 204 && /json/i.test(type) ? await response.json() : null,
    };
  }
}

/**
 * A POST that outlives the page (`fetch` with `keepalive`), for the learning-session end on
 * unload. The CSRF cookie is used as it is: there is no time to fetch a new token while the
 * page goes away. Returns false when nothing could be sent; never throws.
 */
export function apiKeepalive(path, json) {
  let url;
  try {
    url = relative(path);
  } catch {
    return false;
  }
  const token = cookie("XSRF-TOKEN");
  if (typeof fetch !== "function" || !token) return false;
  try {
    fetch(url, {
      method: "POST",
      keepalive: true,
      credentials: "same-origin",
      cache: "no-store",
      headers: { Accept: "application/json", "Content-Type": "application/json", "X-XSRF-TOKEN": token },
      body: JSON.stringify(json === undefined ? {} : json),
    }).catch(() => {});
    return true;
  } catch {
    return false;
  }
}

function xhrProblem(xhr) {
  let body = null;
  try {
    if (/json/i.test(xhr.getResponseHeader("content-type") || "")) body = JSON.parse(xhr.responseText);
  } catch {
    body = null;
  }
  const retry = Number(xhr.getResponseHeader("retry-after"));
  return new ApiError(xhr.status, {
    code: body && typeof body.code === "string" ? body.code : null,
    detail: body && typeof body.detail === "string" ? body.detail : "",
    retryAfter: Number.isFinite(retry) && retry >= 0 ? retry : null,
    problem: body && typeof body === "object" && !Array.isArray(body) ? body : null,
  });
}

/**
 * Sends `blob` as the raw request body (`application/octet-stream`; the browser sets
 * `Content-Length`) through XMLHttpRequest, because fetch reports no upload progress.
 * `onProgress(loaded, total)` follows the bytes sent; aborting `signal` stops the transfer and
 * rejects with `ApiError` code `aborted`. A 403 refetches the CSRF token and sends once more only when it changed.
 */
export function apiStreamUpload(path, blob, { idempotencyKey, onProgress, signal } = {}) {
  let url;
  try {
    url = relative(path);
  } catch (error) {
    return Promise.reject(error);
  }
  if (typeof XMLHttpRequest !== "function") return Promise.reject(new ApiError(0, { detail: "이 환경에서는 서버에 연결할 수 없습니다." }));
  const aborted = () => new ApiError(0, { code: "aborted", detail: "업로드를 취소했습니다." });
  const attempt = (token) => new Promise((resolve, reject) => {
    if (signal && signal.aborted) {
      reject(aborted());
      return;
    }
    const xhr = new XMLHttpRequest();
    xhr.open("POST", url);
    xhr.setRequestHeader("Accept", "application/json");
    xhr.setRequestHeader("Content-Type", "application/octet-stream");
    xhr.setRequestHeader("X-XSRF-TOKEN", token);
    if (idempotencyKey) xhr.setRequestHeader("Idempotency-Key", String(idempotencyKey));
    if (onProgress && xhr.upload) {
      xhr.upload.addEventListener("progress", (event) => onProgress(event.loaded, event.lengthComputable ? event.total : blob.size));
    }
    const stop = () => xhr.abort();
    if (signal) signal.addEventListener("abort", stop, { once: true });
    const done = () => { if (signal) signal.removeEventListener("abort", stop); };
    xhr.addEventListener("load", () => { done(); resolve(xhr); });
    xhr.addEventListener("error", () => { done(); reject(new ApiError(0, { detail: "ASTRA 서버에 연결하지 못했습니다. 네트워크 상태를 확인하세요." })); });
    xhr.addEventListener("abort", () => { done(); reject(aborted()); });
    xhr.send(blob);
  });
  return (async () => {
    let token = await csrfToken(false);
    for (let round = 0; ; round += 1) {
      const xhr = await attempt(token);
      if (xhr.status === 403 && round === 0) {
        const fresh = await rotatedToken(token);
        if (fresh) { token = fresh; continue; } // the token had rotated: send once more with the new one
      }
      if (xhr.status === 401 && unauthorized) unauthorized();
      if (xhr.status < 200 || xhr.status >= 300) throw xhrProblem(xhr);
      try {
        return JSON.parse(xhr.responseText);
      } catch {
        return null;
      }
    }
  })();
}
