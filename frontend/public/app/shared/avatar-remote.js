// 서버 사람의 아바타(ADR-0038). 사진은 `GET /api/users/{userId}/avatar`이고, 본인과 폐강하지 않은 과목을 함께
// 듣거나 가르치는 사람만 받는다. 그 밖과 사진이 없는 사람은 같은 404라 이니셜과 색으로 둔다. 처음 보는 주소는
// `apiBlob`으로 한 번 확인하고, 사진이 있을 때만 `<img>`를 넣는다. `Retry-After` 없는 404는 세션 동안 기억해
// 다시 묻지 않으므로 사진 없는 사람은 세션에 요청 한 번이다. 첫 사진이 아직 처리 중이면 그 404에 `Retry-After`가
// 붙어 그 뒤에 다시 확인한다(최대 `PHOTO_TRIES`번). 연결 실패·5xx·429는 기억하지 않아 다음에 그릴 때 다시 확인한다.
// `<img>`는 같은 출처 상대 경로만 쓴다(CSP `img-src 'self'`). 아무것도 저장하지 않는다.
import { el } from "../dom.js";
import { apiBlob, ApiError } from "./api.js";
import { backendStatus, subscribe } from "./backend.js";
import { isAvatarColor } from "./account-remote.js";

const USER_ID = /^[1-9]\d{0,17}$/;
/** 처리 중인 사진(404 + `Retry-After`)을 다시 확인하는 최대 횟수. */
export const PHOTO_TRIES = 3;

// 사진 주소 → 진행 중인 확인. 같은 주소를 그리는 노드들이 요청 하나를 나눈다.
const probes = new Map();
// 사진 주소 → "photo"(받힘) | "missing"(`Retry-After` 없는 404). 이 세션에서는 다시 확인하지 않는다.
const known = new Map();
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  probes.clear();
  known.clear();
});

/** `/api/users/{userId}/avatar`. `version`(본인 `avatar.version`)이 있으면 바뀐 사진을 새로 받도록 `?v=`를 붙인다. */
export function avatarPhotoPath(userId, version) {
  const id = String(userId == null ? "" : userId);
  if (!USER_ID.test(id)) return null;
  return `/api/users/${id}/avatar${version ? `?v=${encodeURIComponent(String(version))}` : ""}`;
}

/**
 * 주소를 한 번 확인한다. 사진이 있으면 "photo", 처리 중(404 + `Retry-After`)이면 기다릴 초, 그 밖이면 null로 끝난다.
 * 기억하는 것은 사진이 있는 주소와 `Retry-After` 없는 404뿐이다.
 */
function probe(src) {
  if (probes.has(src)) return probes.get(src);
  const owner = session;
  const remember = (state) => { if (owner === session) known.set(src, state); };
  const pending = apiBlob(src).then(() => {
    remember("photo");
    return "photo";
  }, (error) => {
    if (!(error instanceof ApiError) || error.status !== 404) return null;
    if (error.retryAfter > 0) return error.retryAfter;
    remember("missing");
    return null;
  }).finally(() => {
    if (probes.get(src) === pending) probes.delete(src);
  });
  probes.set(src, pending);
  return pending;
}

/** `node` 안에 사진 `<img>`를 넣는다. 그사이 읽히지 않으면 빼서 이니셜이 보이고, 다음에 그릴 때 다시 확인한다. */
function attachPhoto(node, src) {
  const img = el("img", { class: "person-avatar__img", src, alt: "", "aria-hidden": "true", loading: "lazy", decoding: "async" });
  img.addEventListener("load", () => node.classList.add("has-photo"), { once: true });
  img.addEventListener("error", () => {
    img.remove();
    node.classList.remove("has-photo");
    if (known.get(src) === "photo") known.delete(src);
  }, { once: true });
  node.append(img);
}

/** 확인이 끝나면 사진을 넣는다. 처리 중이면 그 초 뒤 노드가 화면에 있을 때 다시 확인한다. */
function settle(node, src, tries) {
  void probe(src).then((result) => {
    if (result === "photo") {
      if (node.isConnected && backendStatus() === "ready") attachPhoto(node, src);
      return;
    }
    if (result == null || tries >= PHOTO_TRIES) return;
    setTimeout(() => {
      if (node.isConnected && backendStatus() === "ready") settle(node, src, tries + 1);
    }, result * 1000);
  });
}

/**
 * 서버 사람 한 명의 아바타. 이니셜(약칭, 없으면 이름 첫 글자)과 24색 배경 위에 사진 `<img>`를 겹친다. 사진이 있는지
 * 이 세션에서 아직 모르면 먼저 확인하고(같은 주소는 요청 하나를 나눈다), 있다고 알면 바로 `<img>`를 넣고, 없다고
 * 알면 요청하지 않는다. 첫 사진이 처리 중(404 + `Retry-After`)이면 그 초 뒤에 다시 확인한다(`PHOTO_TRIES`번까지,
 * 화면에서 빠지면 멈춘다). 접속이 `ready`가 아니거나 `photo`가 false면 사진을 요청하지 않는다. 장식이라
 * `aria-hidden`이다(이름은 옆 글자로 읽힌다). `className`은 크기를 정하는 기존 클래스다.
 */
export function personAvatar({ userId, name, color, shortName, className = "avatar", version = null, photo = true } = {}) {
  const letters = shortName || String(name || "").trim().slice(0, 1) || "?";
  const tone = isAvatarColor(color) ? ` avatar-bg-${color}` : "";
  const node = el("span", { class: `${className} person-avatar${tone}`, "aria-hidden": "true" },
    el("span", { class: "person-avatar__text", text: letters }));
  const src = photo && backendStatus() === "ready" ? avatarPhotoPath(userId, version) : null;
  if (!src) return node;
  const state = known.get(src);
  if (state === "photo") attachPhoto(node, src);
  else if (state !== "missing") settle(node, src, 0);
  return node;
}
