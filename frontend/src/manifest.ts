import { screens as homeScreens } from "./areas/home/screens.ts";
import { screens as courseScreens } from "./areas/course/screens.ts";
import { screens as learningScreens } from "./areas/learning/screens.ts";
import { screens as mobileScreens } from "./areas/mobile/screens.ts";
import { screens as professorScreens } from "./areas/professor/screens.ts";
import type { Screen } from "./types.ts";

export const screens: Screen[] = [
  ...homeScreens,
  ...courseScreens,
  ...learningScreens,
  ...mobileScreens,
  ...professorScreens,
];

/** 화면 ID로 화면 표의 객체를 찾는다. 주소를 만드는 모든 층이 이 함수를 쓴다. 표에 없는 ID는 프로그램 오류다. */
export function find(id: string): Screen {
  const screen = screens.find((item) => item.id === id);
  if (!screen) throw new Error(`Unknown screen id: ${id}`);
  return screen;
}

export function hrefFor(screen: Screen, extra: Record<string, string | number | null | undefined> = {}) {
  const params = new URLSearchParams();
  const query: Record<string, string | number | null | undefined> = { ...(screen.query || {}), ...extra };
  if (screen.course && !query.kj && !query.id) query.kj = screen.course;
  if (screen.course && screen.kind !== "detail") query.kj = query.kj || screen.course;
  for (const [k, v] of Object.entries(query)) {
    if (v != null && v !== "") params.set(k, String(v));
  }
  const q = params.toString();
  return screen.path + (q ? `?${q}` : "");
}

export function matchScreen(pathname: string, params: URLSearchParams): Screen | null {
  let path = pathname;
  if (path === "/" || path === "/index.html") path = "/ilos/main/main_form.acl";
  let best: Screen | null = null;
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
