// 녹음 중 화면 하단에 쭉 보이는 실시간 전사. 브라우저의 음성 인식(Web Speech API, 한국어)을 쓴다.
// 화면에만 표시하고 저장하거나 서버로 보내지 않는다. 저장된 녹음의 전사·요약은 서버가 만든 결과(recording-study.ts)다.
// Chrome·Edge의 음성 인식은 음성을 브라우저 제공사 서버로 보내 처리한다. 마이크 녹음에서만 쓴다(탭 소리는 인식기에 넣을 수 없다).

export interface LiveLine {
  /** 녹음 시작부터의 밀리초. */
  at: number;
  text: string;
}

export interface LiveTranscript {
  /** 이 브라우저에 음성 인식이 있는가. */
  supported: boolean;
  /** 인식기가 켜져 있어 듣는 중인가(일시정지·오류·정지 중에는 false). */
  listening: boolean;
  lines: readonly LiveLine[];
  /** 아직 확정되지 않은, 지금 말하는 중인 문장. */
  interim: string;
  /** 인식기가 멈춘 이유(마이크 권한·네트워크). 비어 있으면 정상. */
  error: string;
}

interface RecognitionAlternative { transcript: string }
interface RecognitionResult { isFinal: boolean; 0: RecognitionAlternative }
interface RecognitionEvent { resultIndex: number; results: ArrayLike<RecognitionResult> }
interface Recognition {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  onresult: ((event: RecognitionEvent) => void) | null;
  onerror: ((event: { error: string }) => void) | null;
  onend: (() => void) | null;
  start(): void;
  stop(): void;
  abort(): void;
}
type RecognitionCtor = new () => Recognition;

const RESTART_MS = 300;

const ERRORS: Record<string, string> = {
  "not-allowed": "브라우저가 음성 인식을 허용하지 않아 실시간 전사를 멈췄습니다. 녹음은 계속됩니다.",
  "service-not-allowed": "브라우저가 음성 인식을 허용하지 않아 실시간 전사를 멈췄습니다. 녹음은 계속됩니다.",
  "network": "음성 인식 서비스에 연결하지 못해 실시간 전사를 멈췄습니다. 녹음은 계속됩니다.",
  "audio-capture": "음성 인식이 마이크를 쓰지 못해 실시간 전사를 멈췄습니다. 녹음은 계속됩니다.",
  "language-not-supported": "이 브라우저의 음성 인식이 한국어를 지원하지 않아 실시간 전사를 멈췄습니다. 녹음은 계속됩니다.",
};

const ctor = (): RecognitionCtor | null => {
  const scope = globalThis as unknown as { SpeechRecognition?: RecognitionCtor; webkitSpeechRecognition?: RecognitionCtor };
  return scope.SpeechRecognition || scope.webkitSpeechRecognition || null;
};

const IDLE: LiveTranscript = { supported: false, listening: false, lines: [], interim: "", error: "" };

let state: LiveTranscript = IDLE;
let recognition: Recognition | null = null;
/** 켜져 있어야 하는가: 시작했고 일시정지·멈춤이 아님. 인식기가 스스로 끝나도(침묵 뒤) 이 값이 true면 다시 켠다. */
let wanted = false;
let clock: () => number = () => 0;
let restart = 0;
const listeners = new Set<() => void>();

function set(next: Partial<LiveTranscript>): void {
  state = { ...state, ...next };
  for (const listener of listeners) listener();
}

/** 현재 상태. 바뀔 때만 새 객체라 useSyncExternalStore에 그대로 쓴다. */
export const liveTranscript = (): LiveTranscript => state;

export function onLiveTranscriptChange(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** 이 브라우저에서 실시간 전사를 쓸 수 있는가(시작 전 화면 안내용). */
export const liveTranscriptSupported = (): boolean => ctor() != null;

function listen(): void {
  if (!wanted || recognition) return;
  const Ctor = ctor();
  if (!Ctor) return;
  const next = new Ctor();
  next.lang = "ko-KR";
  next.continuous = true;
  next.interimResults = true;
  next.onresult = (event) => {
    if (recognition !== next) return;
    const lines: LiveLine[] = [];
    let interim = "";
    for (let i = event.resultIndex; i < event.results.length; i++) {
      const result = event.results[i];
      const text = result[0].transcript.trim();
      if (!text) continue;
      if (result.isFinal) lines.push({ at: clock(), text });
      else interim += (interim ? " " : "") + text;
    }
    set({ lines: lines.length ? [...state.lines, ...lines] : state.lines, interim });
  };
  next.onerror = (event) => {
    if (recognition !== next) return;
    // no-speech·aborted는 침묵·재시작으로 생기는 일이라 onend가 다시 켠다.
    const message = ERRORS[event.error];
    if (!message) return;
    wanted = false;
    set({ listening: false, interim: "", error: message });
  };
  next.onend = () => {
    if (recognition !== next) return;
    recognition = null;
    if (!wanted) {
      set({ listening: false, interim: "" });
      return;
    }
    // 침묵 등으로 인식기가 끝났다: 말이 끊긴 줄로 남기지 않고 잠깐 뒤 다시 켠다.
    set({ interim: "" });
    restart = window.setTimeout(() => {
      restart = 0;
      listen();
    }, RESTART_MS);
  };
  recognition = next;
  try {
    next.start();
    set({ listening: true, error: "" });
  } catch {
    recognition = null;
    wanted = false;
    set({ listening: false, error: "실시간 전사를 시작하지 못했습니다. 녹음은 계속됩니다." });
  }
}

function halt(): void {
  wanted = false;
  window.clearTimeout(restart);
  restart = 0;
  const current = recognition;
  recognition = null;
  // 확정 전의 마지막 말은 abort로 버린다(저장되지 않는 화면용 전사라 끊긴 반쪽 문장을 남기지 않는다).
  if (current) {
    try {
      current.abort();
    } catch {
      // 이미 끝난 인식기.
    }
  }
}

/** 녹음 시작과 함께. `now`는 녹음 시작부터의 밀리초(줄의 시각). 지원하지 않는 브라우저면 `supported: false`로 둔다. */
export function startLiveTranscript(now: () => number): void {
  halt();
  clock = now;
  state = { ...IDLE, supported: ctor() != null };
  wanted = state.supported;
  set({});
  listen();
}

export function pauseLiveTranscript(): void {
  if (!wanted) return;
  halt();
  set({ listening: false, interim: "" });
}

export function resumeLiveTranscript(): void {
  if (wanted || !state.supported || state.error) return;
  wanted = true;
  listen();
}

/** 녹음이 끝나면 줄도 지운다. */
export function stopLiveTranscript(): void {
  halt();
  state = IDLE;
  for (const listener of listeners) listener();
}
