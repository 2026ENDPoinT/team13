/** 챗봇 모드: 홈 히어로 입력창을 한 번이라도 쓰면, 다른 화면에서도 우측 하단 FAB 위에 입력창을 둔다. */
const KEY = "stack.assistant.chat";

function stored(): boolean {
  try {
    return sessionStorage.getItem(KEY) === "1";
  } catch {
    return false;
  }
}

let used = stored();
let shut = false;

export const chatUsed = (): boolean => used;

/** 입력창을 접어 둔 상태(계획·요청함 패널이 없을 때만 FAB 눌러서 토글). */
export const chatShut = (): boolean => shut;

/** 히어로(또는 도크)에서 입력을 보낼 때 켠다. 접어 둔 입력창은 다시 편다. */
export function markChatUsed(): void {
  shut = false;
  if (used) return;
  used = true;
  try {
    sessionStorage.setItem(KEY, "1");
  } catch {
    // 저장 못 해도 이 탭에서는 모듈 변수로 유지된다.
  }
}

export function toggleChatShut(): void {
  shut = !shut;
}

/** 홈 히어로 입력창이 화면 밖으로 스크롤됐는지. 그때는 홈에서도 FAB·입력창을 보인다. */
let heroOut = false;

export const heroInputOut = (): boolean => heroOut;

/** 바뀌었으면 true(다시 그려야 한다). */
export function setHeroInputOut(next: boolean): boolean {
  if (heroOut === next) return false;
  heroOut = next;
  return true;
}
