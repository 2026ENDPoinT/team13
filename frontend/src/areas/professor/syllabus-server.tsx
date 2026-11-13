// 담당 교수의 강의계획서 편집(백엔드 `ready`). `GET /api/courses/{courseId}/syllabus`로 읽고 두 시트로 고친다.
// (a) 강의계획서: 서술 항목과 주차별 계획을 통째로 바꾼다. (b) 내 강사 정보: 로그인한 교수 자신의 연락처다.
// 로그인한 사용자가 계획서의 담당자 명단(`instructors[].userId`)에 없으면(조교 등) 강사 정보 폼 대신 쓸 수 없다는 문장만 보인다.
// 둘 다 `If-Match`에 화면이 마지막으로 읽은 version을 보내고(저장한 적이 없으면 "0"), 성공하면 syllabus-remote가 응답을 캐시에
// 넣고 화면은 그 값으로 폼을 다시 채운다. 409/428이면 입력한 값은 그대로 두고 다시 읽어 서버의 최신 내용을 옆에 보여 준다.
// 다음 저장은 다시 읽은 version을 쓴다. 입력은 과목별 메모리에만 두고 세션이 바뀌면 버린다. 서버와 사용자 텍스트는 모두 텍스트로 그린다.
import { useEffect, useReducer, useRef, useState, type ReactNode } from "react";
import type { Course } from "../../data.ts";
import { Button, EmptyState, ErrorState, Field, LoadingState } from "../../ui/kit.tsx";
import { ApiError } from "../../shared/api.ts";
import { backendState, backendStatus, courseIdFor, roleIn, subscribe } from "../../shared/backend.ts";
import { SIGNED_OUT } from "../../shared/backend-prompt.tsx";
import { freshVisit, useResource, type Entry } from "../../shared/remote-cache.ts";
import { toast } from "../../store.ts";
import {
  syllabi, syllabusKey, syllabusMessage, OUTCOME, OUTCOME_LEVELS, SYLLABUS_LIMITS as LIMIT,
  updateSyllabus, updateMyInstructorCard, isStaleVersion, syllabusEditMessage, isListedInstructor, NOT_LISTED_TEXT,
  type InstructorCardBody, type OutcomeLevel, type Syllabus, type SyllabusBody, type SyllabusInstructor,
} from "../../shared/syllabus-remote.ts";

type TextField = "overview" | "objectives" | "textbooks" | "equipment" | "gradingPolicy";
type CardField = keyof InstructorCardBody;

const TEXT_FIELDS: readonly (readonly [TextField, string])[] = [
  ["overview", "과목 개요"],
  ["objectives", "수업 목표"],
  ["textbooks", "교재"],
  ["equipment", "준비물"],
  ["gradingPolicy", "평가 방법"],
];

const CARD_FIELDS: readonly (readonly [CardField, string, "text" | "email" | "tel" | "url"])[] = [
  ["department", "소속", "text"],
  ["email", "이메일", "email"],
  ["phone", "연락처", "tel"],
  ["office", "연구실", "text"],
  ["officeHours", "면담 시간", "text"],
  ["homepage", "홈페이지", "url"],
];

// 서버(Java)의 \s와 같은 공백 문자만 쓴다.
const EMAIL = /^[^ \t\n\x0B\f\r@]{1,64}@[^ \t\n\x0B\f\r@]{1,190}$/;
const HOMEPAGE = /^https?:\/\/[^ \t\n\x0B\f\r]{1,290}$/;

interface WeekRow {
  rid: number;
  weekNo: string;
  content: string;
  note: string;
}

/** 충돌 뒤 옆에 보여 주는 서버의 최신 내용. `pending`이면 다시 읽는 중이다. */
type Latest =
  | { pending: true }
  | { pending: false; version: number; rows: [string, string | null | undefined][]; weeks: [string, string][] | null };

interface SheetState {
  version: number | null;
  errors: Record<string, string>;
  focus: string | null;
  note: string;
  alert: boolean;
  busy: boolean;
  latest: Latest | null;
}

interface SyllabusMemo extends SheetState {
  classTimeRoom: string;
  outcomeRelevance: OutcomeLevel | "";
  texts: Record<TextField, string>;
  weeks: WeekRow[];
}

interface CardMemo extends SheetState {
  values: Record<CardField, string>;
}

// 입력 메모리. 세션이 바뀌면 버린다.
const syllabusDrafts = new Map<string, SyllabusMemo>();
const cardDrafts = new Map<string, CardMemo>();
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  syllabusDrafts.clear();
  cardDrafts.clear();
});

let rowIds = 0;

const versionOf = (value: unknown): number | null => (typeof value === "number" && Number.isInteger(value) && value >= 0 ? value : null);
const isOutcome = (value: unknown): value is OutcomeLevel => OUTCOME_LEVELS.some((level) => level === value);

/** 서버 LearningText와 같이 제어 문자를 찾는다. 여러 줄 항목은 줄바꿈과 탭을 허용한다. */
function hasControl(text: string, multiline: boolean): boolean {
  for (const char of text) {
    const cp = char.codePointAt(0) ?? 0;
    const control = cp <= 0x1f || (cp >= 0x7f && cp <= 0x9f) || cp === 0x2028 || cp === 0x2029;
    if (control && !(multiline && (cp === 10 || cp === 13 || cp === 9))) return true;
  }
  return false;
}

type Checked = { value: string | null; error?: undefined } | { value?: undefined; error: string };

/** 선택 한 줄 값: 앞뒤 공백을 지우고 비면 null. */
function oneLine(value: string, max: number, label: string): Checked {
  const text = value.trim();
  if (!text) return { value: null };
  if (text.length > max) return { error: `${label}은(는) ${max.toLocaleString("ko-KR")}자 이하로 적어 주세요.` };
  if (hasControl(text, false)) return { error: `${label}에 쓸 수 없는 문자가 있습니다.` };
  return { value: text };
}

/** 선택 여러 줄 값: 공백뿐이면 null, 아니면 쓴 그대로. */
function multiLine(value: string, max: number, label: string): Checked {
  if (!value.trim()) return { value: null };
  if (value.length > max) return { error: `${label}은(는) ${max.toLocaleString("ko-KR")}자 이하로 적어 주세요.` };
  if (hasControl(value, true)) return { error: `${label}에 쓸 수 없는 문자가 있습니다.` };
  return { value };
}

const blankSheet = (): Omit<SheetState, "version"> => ({ errors: {}, focus: null, note: "", alert: false, busy: false, latest: null });

function syllabusMemo(data: Syllabus): SyllabusMemo {
  const weeks = Array.isArray(data.weeks) ? data.weeks : [];
  return {
    version: versionOf(data.version),
    classTimeRoom: data.classTimeRoom || "",
    outcomeRelevance: isOutcome(data.outcomeRelevance) ? data.outcomeRelevance : "",
    texts: {
      overview: data.overview || "",
      objectives: data.objectives || "",
      textbooks: data.textbooks || "",
      equipment: data.equipment || "",
      gradingPolicy: data.gradingPolicy || "",
    },
    weeks: weeks.map((week) => ({ rid: ++rowIds, weekNo: String(week.weekNo), content: week.content || "", note: week.note || "" })),
    ...blankSheet(),
  };
}

function cardMemo(card: SyllabusInstructor | null): CardMemo {
  return {
    version: card ? versionOf(card.version) : 0,
    values: {
      department: card?.department || "",
      email: card?.email || "",
      phone: card?.phone || "",
      office: card?.office || "",
      officeHours: card?.officeHours || "",
      homepage: card?.homepage || "",
    },
    ...blankSheet(),
  };
}

function myCard(data: Syllabus): SyllabusInstructor | null {
  const me = backendState().me;
  const instructors = Array.isArray(data.instructors) ? data.instructors : [];
  return me ? instructors.find((person) => Number(person.userId) === Number(me.userId)) || null : null;
}

/** 서버 검사와 같은 규칙으로 계획서 본문을 만든다. */
function checkSyllabus(memo: SyllabusMemo): { errors: Record<string, string>; body: SyllabusBody } {
  const errors: Record<string, string> = {};
  const room = oneLine(memo.classTimeRoom, LIMIT.classTimeRoom, "수업 시간·강의실");
  if (room.error) errors.classTimeRoom = room.error;
  const texts = {} as Record<TextField, string | null>;
  for (const [name, label] of TEXT_FIELDS) {
    const checked = multiLine(memo.texts[name], LIMIT.text, label);
    if (checked.error) errors[name] = checked.error;
    texts[name] = checked.value ?? null;
  }
  if (memo.weeks.length > LIMIT.weeks) errors.weeks = `주차별 계획은 ${LIMIT.weeks}개까지입니다.`;
  const seen = new Set<number>();
  const weeks = memo.weeks.map((row) => {
    const raw = row.weekNo.trim();
    const weekNo = Number(raw);
    if (!raw || !Number.isInteger(weekNo) || weekNo < 1 || weekNo > LIMIT.weeks) errors[`w${row.rid}:no`] = `주차는 1부터 ${LIMIT.weeks} 사이입니다.`;
    else if (seen.has(weekNo)) errors[`w${row.rid}:no`] = `${weekNo}주차가 이미 있습니다.`;
    else seen.add(weekNo);
    const content = multiLine(row.content, LIMIT.weekContent, "내용");
    if (content.error) errors[`w${row.rid}:content`] = content.error;
    const note = oneLine(row.note, LIMIT.weekNote, "비고");
    if (note.error) errors[`w${row.rid}:note`] = note.error;
    return { weekNo, content: content.value ?? null, note: note.value ?? null };
  });
  weeks.sort((a, b) => a.weekNo - b.weekNo);
  return {
    errors,
    body: { classTimeRoom: room.value ?? null, outcomeRelevance: memo.outcomeRelevance || null, ...texts, weeks },
  };
}

function checkCard(memo: CardMemo): { errors: Record<string, string>; body: InstructorCardBody } {
  const errors: Record<string, string> = {};
  const body = {} as InstructorCardBody; // every CARD_FIELDS key is filled below
  for (const [name, label] of CARD_FIELDS) {
    const checked = oneLine(memo.values[name], LIMIT[name], label);
    if (checked.error) errors[name] = checked.error;
    body[name] = checked.value ?? null;
  }
  if (!errors.email && body.email && !EMAIL.test(body.email)) errors.email = "이메일 형식을 확인하세요. 예: name@example.com";
  if (!errors.homepage && body.homepage && !HOMEPAGE.test(body.homepage)) errors.homepage = "홈페이지는 http 또는 https로 시작하는 전체 주소로 적어 주세요.";
  return { errors, body };
}

function Lines({ text }: { text: string }) {
  return <p className="pf-sub">{text.split("\n").flatMap((line, index) => (index ? [<br key={index} />, line] : [line]))}</p>;
}

function StatusLine({ memo }: { memo: SheetState }) {
  return <p className={memo.alert ? "err" : "pf-sub"} role={memo.alert ? "alert" : "status"} aria-live="polite">{memo.note}</p>;
}

function versionText(version: number | null): string {
  return version ? `저장된 버전 ${version}` : "아직 서버에 저장한 적이 없습니다";
}

function latestSyllabus(data: Syllabus): Latest {
  const weeks = Array.isArray(data.weeks) ? data.weeks : [];
  return {
    pending: false,
    version: versionOf(data.version) || 0,
    rows: [
      ["수업 시간·강의실", data.classTimeRoom],
      ["역량 연관도", data.outcomeRelevance ? `${data.outcomeRelevance} ${OUTCOME[data.outcomeRelevance] || ""}`.trim() : null],
      ...TEXT_FIELDS.map(([name, label]): [string, string | null | undefined] => [label, data[name]]),
    ],
    weeks: weeks.map((week): [string, string] => [`${week.weekNo}주`, [week.content, week.note].filter(Boolean).join(" · ")]),
  };
}

function latestCard(card: SyllabusInstructor | null): Latest {
  return {
    pending: false,
    version: card ? versionOf(card.version) || 0 : 0,
    rows: CARD_FIELDS.map(([name, label]): [string, string | null | undefined] => [label, card ? card[name] : null]),
    weeks: null,
  };
}

function LatestView({ latest }: { latest: Latest }) {
  if (latest.pending) return <p className="pf-sub" role="status">서버의 최신 내용을 불러오는 중입니다.</p>;
  return (
    <div className="pf-server-pdf" aria-label="서버의 최신 내용">
      <p className="pf-sub"><b>서버의 최신 내용</b>{` · ${versionText(latest.version)}`}</p>
      {latest.rows.map(([label, value]) => (
        <div key={label}>
          <p className="pf-sub"><b>{label}</b></p>
          {value ? <Lines text={value} /> : <p className="pf-sub">—</p>}
        </div>
      ))}
      {latest.weeks ? (
        <div>
          <p className="pf-sub"><b>주차별 계획</b></p>
          {latest.weeks.length ? latest.weeks.map(([week, text]) => <Lines key={week} text={`${week}: ${text || "—"}`} />) : <p className="pf-sub">—</p>}
        </div>
      ) : null}
    </div>
  );
}

/** 다시 읽기가 끝나면 충돌 때 기다리던 시트에 최신 내용과 새 version을 넣는다. 입력한 값은 그대로 둔다. */
function settle(memo: SheetState | undefined, entry: Entry<Syllabus>, snapshot: () => Latest, version: () => number | null): void {
  if (!memo || !memo.latest || !memo.latest.pending || entry.loading) return;
  if (entry.error) {
    memo.latest = null;
    memo.note = "서버의 최신 내용을 불러오지 못했습니다. 위의 다시 불러오기를 누른 뒤 저장하세요.";
    memo.alert = true;
    return;
  }
  memo.latest = snapshot();
  memo.version = version();
}

/** 컨트롤 하나: 이름으로 등록해 저장 실패 때 첫 오류 칸으로 포커스를 옮긴다. */
function useControls(memo: SheetState) {
  const controls = useRef(new Map<string, HTMLElement>());
  useEffect(() => {
    if (!memo.focus) return;
    const control = controls.current.get(memo.focus);
    memo.focus = null;
    control?.focus();
  });
  return (name: string) => (node: HTMLElement | null) => {
    if (node) controls.current.set(name, node);
    else controls.current.delete(name);
  };
}

function RetryButton({ reload }: { reload: () => void }) {
  const [pressed, setPressed] = useState(false);
  return <Button kind="ghost" disabled={pressed} onClick={() => { setPressed(true); reload(); }}>다시 불러오기</Button>;
}

interface SheetProps {
  courseId: number | string;
  slot: string;
  reload: () => void;
  afterSave: () => void;
}

function SyllabusSheet({ memo, courseId, slot, reload, afterSave }: SheetProps & { memo: SyllabusMemo }) {
  const [, repaint] = useReducer((n: number) => n + 1, 0);
  const register = useControls(memo);
  const say = (text: string, alert = false) => {
    memo.note = text;
    memo.alert = alert;
    repaint();
  };
  const edit = (change: () => void) => {
    change();
    repaint();
  };
  const save = async () => {
    if (memo.busy || syllabusDrafts.get(slot) !== memo) return;
    const { errors, body } = checkSyllabus(memo);
    memo.errors = errors;
    const first = Object.keys(errors)[0];
    if (first) {
      memo.focus = first === "weeks" ? "add-week" : first;
      say("표시한 항목을 고친 뒤 저장하세요.", true);
      return;
    }
    memo.busy = true;
    say("ASTRA 서버에 저장하고 있습니다.");
    try {
      await updateSyllabus(courseId, memo.version, body);
      afterSave();
      toast("강의계획서를 저장했습니다");
    } catch (error) {
      memo.busy = false;
      if (isStaleVersion(error)) {
        memo.latest = { pending: true };
        say(syllabusEditMessage(error, "강의계획서"), true);
        reload();
      } else say(syllabusEditMessage(error, "강의계획서"), true);
    }
  };
  const addWeek = () => {
    if (memo.weeks.length >= LIMIT.weeks) return;
    const used = new Set(memo.weeks.map((row) => Number(row.weekNo)));
    let weekNo = 1;
    while (used.has(weekNo) && weekNo < LIMIT.weeks) weekNo += 1;
    const row: WeekRow = { rid: ++rowIds, weekNo: used.has(weekNo) ? "" : String(weekNo), content: "", note: "" };
    memo.weeks.push(row);
    memo.focus = `w${row.rid}:content`;
    repaint();
  };
  const removeWeek = (row: WeekRow) => {
    const index = memo.weeks.indexOf(row);
    if (index < 0) return;
    memo.weeks.splice(index, 1);
    const next = memo.weeks[index] || memo.weeks[index - 1];
    memo.focus = next ? `w${next.rid}:no` : "add-week";
    repaint();
  };
  const invalid = (name: string) => (memo.errors[name] ? true : undefined);
  return (
    <section className="sheet pf-syllabus-edit" aria-label="강의계획서 고치기" aria-busy={memo.busy ? "true" : "false"}>
      <h2>강의계획서</h2>
      <p className="pf-sub">{`${versionText(memo.version)}. 과목명·학점 같은 학사 정보는 학사 시스템 값이라 여기서 고치지 않습니다. 비워 둔 항목은 저장하면 지워집니다.`}</p>
      <div className="pf-form">
        <div className="span-2">
          <Field label={`수업 시간·강의실 (${LIMIT.classTimeRoom}자 이하)`} error={memo.errors.classTimeRoom}>
            <input ref={register("classTimeRoom")} maxLength={LIMIT.classTimeRoom} autoComplete="off" disabled={memo.busy} aria-invalid={invalid("classTimeRoom")}
              value={memo.classTimeRoom} onChange={(event) => edit(() => { memo.classTimeRoom = event.target.value; })} />
          </Field>
        </div>
        <Field label="역량 연관도" error={memo.errors.outcomeRelevance}>
          <select ref={register("outcomeRelevance")} disabled={memo.busy} aria-invalid={invalid("outcomeRelevance")} value={memo.outcomeRelevance}
            onChange={(event) => edit(() => { memo.outcomeRelevance = isOutcome(event.target.value) ? event.target.value : ""; })}>
            <option value="">고르지 않음</option>
            {OUTCOME_LEVELS.map((level) => <option key={level} value={level}>{`${level} ${OUTCOME[level]}`}</option>)}
          </select>
        </Field>
        {TEXT_FIELDS.map(([name, label]) => (
          <div className="span-2" key={name}>
            <Field label={`${label} (${LIMIT.text.toLocaleString("ko-KR")}자 이하)`} error={memo.errors[name]}>
              <textarea ref={register(name)} rows={4} maxLength={LIMIT.text} disabled={memo.busy} aria-invalid={invalid(name)}
                value={memo.texts[name]} onChange={(event) => edit(() => { memo.texts[name] = event.target.value; })} />
            </Field>
          </div>
        ))}
        <div className="span-2">
          <h3>주차별 계획</h3>
          <p className="pf-sub">{`1주부터 ${LIMIT.weeks}주까지 주차마다 한 행입니다. 뺀 주차는 저장하면 지워집니다.`}</p>
          {memo.errors.weeks ? <p className="err">{memo.errors.weeks}</p> : null}
          {memo.weeks.length ? memo.weeks.map((row) => (
            <div key={row.rid} className="pf-lecture-upload" role="group" aria-label={`${row.weekNo || "새"}주차 계획`}>
              <div className="pf-form">
                <Field label={`주차 (1~${LIMIT.weeks})`} error={memo.errors[`w${row.rid}:no`]}>
                  <input ref={register(`w${row.rid}:no`)} type="number" min={1} max={LIMIT.weeks} step={1} inputMode="numeric" disabled={memo.busy}
                    aria-invalid={invalid(`w${row.rid}:no`)} value={row.weekNo} onChange={(event) => edit(() => { row.weekNo = event.target.value; })} />
                </Field>
                <Field label={`비고 (${LIMIT.weekNote}자 이하)`} error={memo.errors[`w${row.rid}:note`]}>
                  <input ref={register(`w${row.rid}:note`)} maxLength={LIMIT.weekNote} autoComplete="off" disabled={memo.busy}
                    aria-invalid={invalid(`w${row.rid}:note`)} value={row.note} onChange={(event) => edit(() => { row.note = event.target.value; })} />
                </Field>
                <div className="span-2">
                  <Field label={`내용 (${LIMIT.weekContent.toLocaleString("ko-KR")}자 이하)`} error={memo.errors[`w${row.rid}:content`]}>
                    <textarea ref={register(`w${row.rid}:content`)} rows={2} maxLength={LIMIT.weekContent} disabled={memo.busy}
                      aria-invalid={invalid(`w${row.rid}:content`)} value={row.content} onChange={(event) => edit(() => { row.content = event.target.value; })} />
                  </Field>
                </div>
                <div className="span-2">
                  <div className="pf-actions">
                    <Button kind="ghost" disabled={memo.busy} aria-label={`${row.weekNo || "새"}주차 계획 빼기`} onClick={() => removeWeek(row)}>이 주차 빼기</Button>
                  </div>
                </div>
              </div>
            </div>
          )) : <p className="pf-sub">아직 주차별 계획이 없습니다.</p>}
          <div className="pf-actions">
            <Button ref={register("add-week")} disabled={memo.busy || memo.weeks.length >= LIMIT.weeks} onClick={addWeek}>주차 추가</Button>
          </div>
        </div>
        {memo.latest ? <div className="span-2"><LatestView latest={memo.latest} /></div> : null}
        <div className="span-2"><StatusLine memo={memo} /></div>
        <div className="span-2">
          <div className="pf-actions">
            <Button kind="primary" disabled={memo.busy} aria-busy={memo.busy ? "true" : "false"} onClick={() => void save()}>{memo.busy ? "저장하는 중…" : "강의계획서 저장"}</Button>
          </div>
        </div>
      </div>
    </section>
  );
}

function CardSheet({ memo, listed, courseId, slot, reload, afterSave }: SheetProps & { memo: CardMemo; listed: boolean }) {
  const [, repaint] = useReducer((n: number) => n + 1, 0);
  const register = useControls(memo);
  const me = backendState().me;
  // 담당자 명단에 없는 담당자(조교 등)는 강사 정보를 쓸 수 없다(409 `not_listed_instructor`). 폼 대신 그 문장만 보인다.
  if (!listed) {
    return (
      <section className="sheet pf-syllabus-card" aria-label="내 강사 정보 고치기" aria-busy="false">
        <h2>내 강사 정보</h2>
        <p className="pf-sub" role="status">{NOT_LISTED_TEXT}</p>
      </section>
    );
  }
  const say = (text: string, alert = false) => {
    memo.note = text;
    memo.alert = alert;
    repaint();
  };
  const save = async () => {
    if (memo.busy || cardDrafts.get(slot) !== memo) return;
    const { errors, body } = checkCard(memo);
    memo.errors = errors;
    const first = Object.keys(errors)[0];
    if (first) {
      memo.focus = first;
      say("표시한 항목을 고친 뒤 저장하세요.", true);
      return;
    }
    memo.busy = true;
    say("ASTRA 서버에 저장하고 있습니다.");
    try {
      await updateMyInstructorCard(courseId, memo.version, body);
      afterSave();
      toast("내 강사 정보를 저장했습니다");
    } catch (error) {
      memo.busy = false;
      if (isStaleVersion(error)) {
        memo.latest = { pending: true };
        say(syllabusEditMessage(error, "강사 정보"), true);
        reload();
      } else {
        say(syllabusEditMessage(error, "강사 정보"), true);
        // 화면이 가진 명단과 서버 명단이 어긋났다. 다시 읽어 명단에 없으면 폼을 거둔다.
        if (error instanceof ApiError && error.code === "not_listed_instructor") reload();
      }
    }
  };
  return (
    <section className="sheet pf-syllabus-card" aria-label="내 강사 정보 고치기" aria-busy={memo.busy ? "true" : "false"}>
      <h2>내 강사 정보</h2>
      <p className="pf-sub">{`${me && me.name ? `${me.name} 교수님의 정보입니다. ` : ""}${versionText(memo.version)}. 과목 구성원에게만 연락처가 보이고, 구성원이 아닌 사람에게는 이름과 소속만 보입니다.`}</p>
      <div className="pf-form">
        {CARD_FIELDS.map(([name, label, type]) => {
          const field = (
            <Field key={name} label={`${label} (${LIMIT[name]}자 이하)`} error={memo.errors[name]}>
              <input ref={register(name)} type={type} maxLength={LIMIT[name]} autoComplete="off" inputMode={type === "tel" ? "tel" : undefined} disabled={memo.busy}
                aria-invalid={memo.errors[name] ? true : undefined} value={memo.values[name]}
                onChange={(event) => { memo.values[name] = event.target.value; repaint(); }} />
            </Field>
          );
          return name === "homepage" || name === "officeHours" ? <div className="span-2" key={name}>{field}</div> : field;
        })}
        {memo.latest ? <div className="span-2"><LatestView latest={memo.latest} /></div> : null}
        <div className="span-2"><StatusLine memo={memo} /></div>
        <div className="span-2">
          <div className="pf-actions">
            <Button kind="primary" disabled={memo.busy} aria-busy={memo.busy ? "true" : "false"} onClick={() => void save()}>{memo.busy ? "저장하는 중…" : "내 강사 정보 저장"}</Button>
          </div>
        </div>
      </div>
    </section>
  );
}

function SyllabusEditor({ course, courseId }: { course: Course; courseId: number | string }) {
  const key = syllabusKey(courseId);
  const reload = () => { void syllabi.load(key, courseId); };
  const entry = useResource(syllabi, key);
  const [generation, bump] = useReducer((n: number) => n + 1, 0);
  useEffect(() => {
    if (freshVisit("pf-syllabus", key) === "entry" || !syllabi.peek(key)) reload();
  }, [key]);
  const syllabusSlot = `syllabus:${courseId}`;
  const cardSlot = `card:${courseId}`;
  // 저장하면 syllabus-remote가 응답을 캐시에 넣는다. 입력 메모리를 버리고 캐시로 다시 그려 저장된 내용과 새 version으로 폼을 채운다.
  // 캐시에 넣지 못했으면(그 과목 캐시를 버렸으면) 다시 읽는다.
  const afterSave = (drop: () => void) => () => {
    drop();
    if (syllabi.peek(key)?.data) bump();
    else reload();
  };
  const data = entry?.data;
  if (!entry || !data) {
    if (!entry || !entry.error) return <LoadingState title="강의계획서를 불러오는 중입니다" />;
    if (entry.error instanceof ApiError && entry.error.status === 404) return <EmptyState title="강의계획서를 고칠 수 없습니다" body="이 과목의 강의계획서를 볼 권한이 없습니다. 담당 교수만 고칠 수 있습니다." />;
    return <ErrorState title="강의계획서를 불러오지 못했습니다" body={syllabusMessage(entry.error)} actions={<RetryButton reload={reload} />} />;
  }
  let syllabusMemoNow = syllabusDrafts.get(syllabusSlot);
  if (!syllabusMemoNow) syllabusDrafts.set(syllabusSlot, syllabusMemoNow = syllabusMemo(data));
  let cardMemoNow = cardDrafts.get(cardSlot);
  if (!cardMemoNow) cardDrafts.set(cardSlot, cardMemoNow = cardMemo(myCard(data)));
  settle(syllabusMemoNow, entry, () => latestSyllabus(data), () => versionOf(data.version));
  settle(cardMemoNow, entry, () => latestCard(myCard(data)), () => {
    const card = myCard(data);
    return card ? versionOf(card.version) : 0;
  });
  const failed = entry.error && !entry.loading;
  return (
    <div className="stack" aria-live="polite">
      <p className="pf-sub">{`과목: ${course.ko || course.id}. 저장하면 학생이 보는 강의계획서에 바로 반영됩니다.`}</p>
      {failed ? <div className="stack"><p className="err" role="alert">{syllabusMessage(entry.error)}</p><RetryButton reload={reload} /></div> : null}
      <SyllabusSheet key={`s${generation}`} memo={syllabusMemoNow} courseId={courseId} slot={syllabusSlot} reload={reload}
        afterSave={afterSave(() => syllabusDrafts.delete(syllabusSlot))} />
      <CardSheet key={`c${generation}`} memo={cardMemoNow} listed={isListedInstructor(data, backendState().me?.userId)} courseId={courseId} slot={cardSlot} reload={reload}
        afterSave={afterSave(() => cardDrafts.delete(cardSlot))} />
    </div>
  );
}

/** 강의계획서 편집 화면(백엔드 모드): 계획서 시트와 내 강사 정보 시트. `course`는 샘플 과목 객체다(`.id` = 과목 키). */
export function serverSyllabusEditor(course: Course): ReactNode {
  if (backendStatus() === "anon") return SIGNED_OUT;
  const courseId = courseIdFor(course.id);
  if (!courseId) return <EmptyState title="이 과목은 ASTRA 서버에 없습니다" body="서버에 등록된 과목을 강의실에서 고른 뒤 다시 열어 주세요." />;
  if (roleIn(course.id) !== "INSTRUCTOR") return <EmptyState title="강의계획서를 고칠 수 없습니다" body="이 과목의 담당 교수만 강의계획서를 고칠 수 있습니다." />;
  return <SyllabusEditor course={course} courseId={courseId} />;
}
