import { screens as homeScreens } from "./areas/home/screens.js";
import { screens as courseScreens } from "./areas/course/screens.js";
import { screens as learningScreens } from "./areas/learning/screens.js";
import { screens as mobileScreens } from "./areas/mobile/screens.js";
import { screens as professorScreens } from "./areas/professor/screens.js";

export const screens = [
  ...homeScreens,
  ...courseScreens,
  ...learningScreens,
  ...mobileScreens,
  ...professorScreens,
];

/** 화면 ID로 화면 표의 객체를 찾는다. 주소를 만드는 모든 층이 이 함수를 쓴다. */
export function find(id) {
  return screens.find((screen) => screen.id === id);
}

export function hrefFor(screen, extra = {}) {
  const params = new URLSearchParams();
  const query = { ...(screen.query || {}), ...extra };
  if (screen.course && !query.kj && !query.id) query.kj = screen.course;
  if (screen.course && screen.kind !== "detail") query.kj = query.kj || screen.course;
  for (const [k, v] of Object.entries(query)) {
    if (v != null && v !== "") params.set(k, String(v));
  }
  const q = params.toString();
  return screen.path + (q ? `?${q}` : "");
}

export function matchScreen(pathname, params) {
  let path = pathname;
  if (path === "/" || path === "/index.html") path = "/ilos/main/main_form.acl";
  let best = null;
  let score = -1;
  for (const screen of screens) {
    if (screen.path !== path) continue;
    const query = screen.query || {};
    const keys = Object.keys(query);
    if (!keys.every((k) => params.get(k) === String(query[k]))) continue;
    if (keys.length > score) {
      best = screen;
      score = keys.length;
    }
  }
  return best;
}
