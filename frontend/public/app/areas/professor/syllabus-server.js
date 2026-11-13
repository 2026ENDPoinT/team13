// 담당 교수의 강의계획서 편집(백엔드 `ready`). `GET /api/courses/{courseId}/syllabus`로 읽고 두 시트로 고친다.
// (a) 강의계획서: 서술 항목과 주차별 계획을 통째로 바꾼다(`pf-syllabus-save`). (b) 내 강사 정보: 로그인한 교수 자신의 연락처다
// (`pf-syllabus-card-save`). 로그인한 사용자가 계획서의 담당자 명단(`instructors[].userId`)에 없으면(조교 등) 강사 정보 폼 대신
// 쓸 수 없다는 문장만 보인다. 둘 다 `If-Match`에 화면이 마지막으로 읽은 version을 보내고(저장한 적이 없으면 "0"), 성공하면
// syllabus-remote가 응답을 캐시에 넣고 화면은 그 값으로 폼을 다시 채운다. 409/428이면 입력한 값은 그대로 두고 다시 읽어 서버의 최신
// 내용을 옆에 보여 준다. 다음 저장은 다시 읽은 version을 쓴다. 입력은 과목별 메모리에만 두고 세션이 바뀌면 버린다. 서버와 사용자
// 텍스트는 모두 텍스트 노드로 그린다.
import { el } from "../../dom.js";
import { btn, field, emptyState, loadingState, errorState } from "../../ui/components.js";
import { ApiError } from "../../shared/api.js";
import { backendState, backendStatus, courseIdFor, roleIn, subscribe } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { freshVisit } from "../../shared/remote-cache.js";
import {
  syllabi, syllabusKey, syllabusMessage, OUTCOME, OUTCOME_LEVELS, SYLLABUS_LIMITS as LIMIT,
  updateSyllabus, updateMyInstructorCard, isStaleVersion, syllabusEditMessage, isListedInstructor, NOT_LISTED_TEXT,
} from "../../shared/syllabus-remote.js";

// "syllabus:<courseId>" | "card:<courseId>" -> 입력 메모리
const drafts = new Map();
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  drafts.clear();
});

let rowIds = 0;

const TEXT_FIELDS = [
  ["overview", "과목 개요"],
  ["objectives", "수업 목표"],
  ["textbooks", "교재"],
  ["equipment", "준비물"],
  ["gradingPolicy", "평가 방법"],
];

const CARD_FIELDS = [
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

const versionOf = (value) => (Number.isInteger(value) && value >= 0 ? value : null);

/** 서버 LearningText와 같이 제어 문자를 찾는다. 여러 줄 항목은 줄바꿈과 탭을 허용한다. */
function hasControl(text, multiline) {
  for (const char of text) {
    const cp = char.codePointAt(0);
    const control = cp <= 0x1f || (cp >= 0x7f && cp <= 0x9f) || cp === 0x2028 || cp === 0x2029;
    if (control && !(multiline && (cp === 10 || cp === 13 || cp === 9))) return true;
  }
  return false;
}

/** 선택 한 줄 값: 앞뒤 공백을 지우고 비면 null. 문제가 있으면 `{ error }`. */
function oneLine(value, max, label) {
  const text = String(value || "").trim();
  if (!text) return { value: null };
  if (text.length > max) return { error: `${label}은(는) ${max.toLocaleString("ko-KR")}자 이하로 적어 주세요.` };
  if (hasControl(text, false)) return { error: `${label}에 쓸 수 없는 문자가 있습니다.` };
  return { value: text };
}

/** 선택 여러 줄 값: 공백뿐이면 null, 아니면 쓴 그대로. */
function multiLine(value, max, label) {
  const text = String(value || "");
  if (!text.trim()) return { value: null };
  if (text.length > max) return { error: `${label}은(는) ${max.toLocaleString("ko-KR")}자 이하로 적어 주세요.` };
  if (hasControl(text, true)) return { error: `${label}에 쓸 수 없는 문자가 있습니다.` };
  return { value: text };
}

function syllabusMemo(data) {
  const weeks = Array.isArray(data.weeks) ? data.weeks : [];
  return {
    version: versionOf(data.version),
    classTimeRoom: data.classTimeRoom || "",
    outcomeRelevance: OUTCOME_LEVELS.includes(data.outcomeRelevance) ? data.outcomeRelevance : "",
    ...Object.fromEntries(TEXT_FIELDS.map(([name]) => [name, data[name] || ""])),
    weeks: weeks.map((week) => ({ rid: ++rowIds, weekNo: String(week.weekNo), content: week.content || "", note: week.note || "" })),
    errors: {},
    focus: null,
    note: "",
    alert: false,
    busy: false,
    latest: null,
  };
}

function cardMemo(card) {
  return {
    version: card ? versionOf(card.version) : 0,
    ...Object.fromEntries(CARD_FIELDS.map(([name]) => [name, (card && card[name]) || ""])),
    errors: {},
    focus: null,
    note: "",
    alert: false,
    busy: false,
    latest: null,
  };
}

function myCard(data) {
  const me = backendState().me;
  const instructors = Array.isArray(data.instructors) ? data.instructors : [];
  return me ? instructors.find((person) => Number(person.userId) === Number(me.userId)) || null : null;
}

/** 서버 검사와 같은 규칙으로 계획서 본문을 만든다. `{ errors, body }`. */
function checkSyllabus(memo) {
  const errors = {};
  const body = {};
  const room = oneLine(memo.classTimeRoom, LIMIT.classTimeRoom, "수업 시간·강의실");
  if (room.error) errors.classTimeRoom = room.error;
  body.classTimeRoom = room.value;
  if (memo.outcomeRelevance && !OUTCOME_LEVELS.includes(memo.outcomeRelevance)) errors.outcomeRelevance = "역량 연관도는 L1, L2, L3 중에서 고르세요.";
  body.outcomeRelevance = memo.outcomeRelevance || null;
  for (const [name, label] of TEXT_FIELDS) {
    const checked = multiLine(memo[name], LIMIT.text, label);
    if (checked.error) errors[name] = checked.error;
    body[name] = checked.value;
  }
  if (memo.weeks.length > LIMIT.weeks) errors.weeks = `주차별 계획은 ${LIMIT.weeks}개까지입니다.`;
  const seen = new Set();
  body.weeks = memo.weeks.map((row) => {
    const raw = String(row.weekNo).trim();
    const weekNo = Number(raw);
    if (!raw || !Number.isInteger(weekNo) || weekNo < 1 || weekNo > LIMIT.weeks) errors[`w${row.rid}:no`] = `주차는 1부터 ${LIMIT.weeks} 사이입니다.`;
    else if (seen.has(weekNo)) errors[`w${row.rid}:no`] = `${weekNo}주차가 이미 있습니다.`;
    else seen.add(weekNo);
    const content = multiLine(row.content, LIMIT.weekContent, "내용");
    if (content.error) errors[`w${row.rid}:content`] = content.error;
    const note = oneLine(row.note, LIMIT.weekNote, "비고");
    if (note.error) errors[`w${row.rid}:note`] = note.error;
    return { weekNo, content: content.value, note: note.value };
  });
  body.weeks.sort((a, b) => a.weekNo - b.weekNo);
  return { errors, body };
}

function checkCard(memo) {
  const errors = {};
  const body = {};
  for (const [name, label] of CARD_FIELDS) {
    const checked = oneLine(memo[name], LIMIT[name], label);
    if (checked.error) errors[name] = checked.error;
    body[name] = checked.value;
  }
  if (!errors.email && body.email && !EMAIL.test(body.email)) errors.email = "이메일 형식을 확인하세요. 예: name@example.com";
  if (!errors.homepage && body.homepage && !HOMEPAGE.test(body.homepage)) errors.homepage = "홈페이지는 http 또는 https로 시작하는 전체 주소로 적어 주세요.";
  return { errors, body };
}

function lines(text, className = "pf-sub") {
  const p = el("p", { class: className });
  String(text).split("\n").forEach((line, index) => {
    if (index) p.append(el("br", {}));
    p.append(line);
  });
  return p;
}

function statusLine(memo) {
  return el("p", { class: memo.alert ? "err" : "pf-sub", role: memo.alert ? "alert" : "status", "aria-live": "polite", text: memo.note });
}

function versionText(version) {
  return version ? `저장된 버전 ${version}` : "아직 서버에 저장한 적이 없습니다";
}

/** 입력 하나: 값과 오류를 메모리에 묶고 field()로 라벨을 붙인다. */
function bind(memo, controls, name, label, control, target = memo, key = name) {
  if (control.tagName === "TEXTAREA" || control.tagName === "SELECT") control.value = target[key];
  control.disabled = memo.busy;
  const error = memo.errors[name];
  if (error) control.setAttribute("aria-invalid", "true");
  control.addEventListener(control.tagName === "SELECT" ? "change" : "input", () => { target[key] = control.value; });
  controls.set(name, control);
  return field(label, control, error);
}

function focusPending(memo, controls) {
  if (!memo.focus) return;
  const control = controls.get(memo.focus);
  memo.focus = null;
  if (control && control.isConnected) control.focus();
}

function latestSyllabus(data) {
  const weeks = Array.isArray(data.weeks) ? data.weeks : [];
  const rows = [
    ["수업 시간·강의실", data.classTimeRoom],
    ["역량 연관도", data.outcomeRelevance ? `${data.outcomeRelevance} ${OUTCOME[data.outcomeRelevance] || ""}`.trim() : null],
    ...TEXT_FIELDS.map(([name, label]) => [label, data[name]]),
  ];
  return {
    version: versionOf(data.version) || 0,
    rows,
    weeks: weeks.map((week) => [`${week.weekNo}주`, [week.content, week.note].filter(Boolean).join(" · ")]),
  };
}

function latestCard(card) {
  return {
    version: card ? versionOf(card.version) || 0 : 0,
    rows: CARD_FIELDS.map(([name, label]) => [label, card ? card[name] : null]),
    weeks: null,
  };
}

function latestView(latest) {
  if (latest.pending) return el("p", { class: "pf-sub", role: "status", text: "서버의 최신 내용을 불러오는 중입니다." });
  return el("div", { class: "pf-server-pdf", "aria-label": "서버의 최신 내용" },
    el("p", { class: "pf-sub" }, el("b", { text: "서버의 최신 내용" }), ` · ${versionText(latest.version)}`),
    ...latest.rows.map(([label, value]) => el("div", {},
      el("p", { class: "pf-sub" }, el("b", { text: label })),
      value ? lines(value) : el("p", { class: "pf-sub", text: "—" }),
    )),
    latest.weeks
      ? el("div", {},
        el("p", { class: "pf-sub" }, el("b", { text: "주차별 계획" })),
        ...(latest.weeks.length ? latest.weeks.map(([week, text]) => lines(`${week}: ${text || "—"}`)) : [el("p", { class: "pf-sub", text: "—" })]))
      : null,
  );
}

function toast(api, text) {
  api.getState().toast = text;
  api.paintToast();
}

const controllers = new WeakMap(); // sheet node -> { save(api) }

/** 강의계획서 편집 화면(백엔드 모드): 계획서 시트와 내 강사 정보 시트. `course`는 샘플 과목 객체다(`.id` = 과목 키). */
export function serverSyllabusEditor(course) {
  if (backendStatus() === "anon") return backendPanel({ text: "ASTRA 서버에 로그인하면 이 과목의 강의계획서를 서버에서 고칠 수 있습니다." });
  const courseId = courseIdFor(course.id);
  if (!courseId) return emptyState("이 과목은 ASTRA 서버에 없습니다", "서버에 등록된 과목을 강의실에서 고른 뒤 다시 열어 주세요.");
  if (roleIn(course.id) !== "INSTRUCTOR") return emptyState("강의계획서를 고칠 수 없습니다", "이 과목의 담당 교수만 강의계획서를 고칠 수 있습니다.");

  const key = syllabusKey(courseId);
  const load = () => syllabi.load(key, courseId);
  // 저장하면 syllabus-remote가 응답을 캐시에 넣는다. 입력 메모리를 버리고 캐시로 다시 그려 저장된 내용과 새 version으로 폼을 채운다.
  // 캐시에 넣지 못했으면(그 과목 캐시를 버렸으면) 다시 읽는다.
  const afterSave = (slot) => {
    drafts.delete(slot);
    if (!root.isConnected) return;
    const entry = syllabi.peek(key);
    if (entry && entry.data) paint(entry);
    else load();
  };
  const root = el("div", { class: "stack", "aria-live": "polite" });
  const syllabusSheet = el("section", { class: "sheet pf-syllabus-edit", "aria-label": "강의계획서 고치기" });
  const cardSheet = el("section", { class: "sheet pf-syllabus-card", "aria-label": "내 강사 정보 고치기" });
  const head = el("p", { class: "pf-sub", text: `과목: ${course.ko || course.id}. 저장하면 학생이 보는 강의계획서에 바로 반영됩니다.` });
  let current = null; // 마지막으로 받은 계획서 응답

  const syllabusSlot = `syllabus:${courseId}`;
  const cardSlot = `card:${courseId}`;

  // ---------- (a) 강의계획서 ----------
  const paintSyllabus = () => {
    const memo = drafts.get(syllabusSlot);
    const controls = new Map();
    const say = (text, alert) => {
      memo.note = text || "";
      memo.alert = Boolean(alert);
      paintSyllabus();
    };
    const room = el("input", { maxlength: String(LIMIT.classTimeRoom), value: memo.classTimeRoom, autocomplete: "off" });
    const outcome = el("select", {},
      el("option", { value: "", text: "고르지 않음" }),
      ...OUTCOME_LEVELS.map((level) => el("option", { value: level, text: `${level} ${OUTCOME[level]}` })),
    );
    const texts = TEXT_FIELDS.map(([name, label]) => el("div", { class: "span-2" },
      bind(memo, controls, name, `${label} (${LIMIT.text.toLocaleString("ko-KR")}자 이하)`, el("textarea", { rows: "4", maxlength: String(LIMIT.text) }))));
    const rows = memo.weeks.map((row) => {
      const no = el("input", { type: "number", min: "1", max: String(LIMIT.weeks), step: "1", inputmode: "numeric", value: row.weekNo });
      const content = el("textarea", { rows: "2", maxlength: String(LIMIT.weekContent) });
      const note = el("input", { maxlength: String(LIMIT.weekNote), value: row.note, autocomplete: "off" });
      const remove = btn("이 주차 빼기", { "aria-label": `${row.weekNo || "새"}주차 계획 빼기` }, "ghost");
      remove.disabled = memo.busy;
      remove.addEventListener("click", () => {
        const index = memo.weeks.indexOf(row);
        if (index < 0) return;
        memo.weeks.splice(index, 1);
        const next = memo.weeks[index] || memo.weeks[index - 1];
        memo.focus = next ? `w${next.rid}:no` : "add-week";
        paintSyllabus();
      });
      return el("div", { class: "pf-lecture-upload", role: "group", "aria-label": `${row.weekNo || "새"}주차 계획` },
        el("div", { class: "pf-form" },
          bind(memo, controls, `w${row.rid}:no`, `주차 (1~${LIMIT.weeks})`, no, row, "weekNo"),
          bind(memo, controls, `w${row.rid}:note`, `비고 (${LIMIT.weekNote}자 이하)`, note, row, "note"),
          el("div", { class: "span-2" }, bind(memo, controls, `w${row.rid}:content`, `내용 (${LIMIT.weekContent.toLocaleString("ko-KR")}자 이하)`, content, row, "content")),
          el("div", { class: "span-2" }, el("div", { class: "pf-actions" }, remove)),
        ),
      );
    });
    const add = btn("주차 추가", {}, "secondary");
    add.disabled = memo.busy || memo.weeks.length >= LIMIT.weeks;
    controls.set("add-week", add);
    add.addEventListener("click", () => {
      if (memo.weeks.length >= LIMIT.weeks) return;
      const used = new Set(memo.weeks.map((row) => Number(row.weekNo)));
      let weekNo = 1;
      while (used.has(weekNo) && weekNo < LIMIT.weeks) weekNo += 1;
      const row = { rid: ++rowIds, weekNo: used.has(weekNo) ? "" : String(weekNo), content: "", note: "" };
      memo.weeks.push(row);
      memo.focus = `w${row.rid}:content`;
      paintSyllabus();
    });
    const save = btn(memo.busy ? "저장하는 중…" : "강의계획서 저장", { "data-act": "pf-syllabus-save", "aria-busy": memo.busy ? "true" : "false" }, "primary");
    save.disabled = memo.busy;
    syllabusSheet.setAttribute("aria-busy", memo.busy ? "true" : "false");
    syllabusSheet.replaceChildren(
      el("h2", { text: "강의계획서" }),
      el("p", { class: "pf-sub", text: `${versionText(memo.version)}. 과목명·학점 같은 학사 정보는 학사 시스템 값이라 여기서 고치지 않습니다. 비워 둔 항목은 저장하면 지워집니다.` }),
      el("div", { class: "pf-form" },
        el("div", { class: "span-2" }, bind(memo, controls, "classTimeRoom", `수업 시간·강의실 (${LIMIT.classTimeRoom}자 이하)`, room)),
        bind(memo, controls, "outcomeRelevance", "역량 연관도", outcome),
        ...texts,
        el("div", { class: "span-2" },
          el("h3", { text: "주차별 계획" }),
          el("p", { class: "pf-sub", text: `1주부터 ${LIMIT.weeks}주까지 주차마다 한 행입니다. 뺀 주차는 저장하면 지워집니다.` }),
          memo.errors.weeks ? el("p", { class: "err", text: memo.errors.weeks }) : null,
          ...(rows.length ? rows : [el("p", { class: "pf-sub", text: "아직 주차별 계획이 없습니다." })]),
          el("div", { class: "pf-actions" }, add),
        ),
        memo.latest ? el("div", { class: "span-2" }, latestView(memo.latest)) : null,
        el("div", { class: "span-2" }, statusLine(memo)),
        el("div", { class: "span-2" }, el("div", { class: "pf-actions" }, save)),
      ),
    );
    focusPending(memo, controls);
    controllers.set(syllabusSheet, {
      async save(api) {
        if (memo.busy || drafts.get(syllabusSlot) !== memo) return;
        const { errors, body } = checkSyllabus(memo);
        memo.errors = errors;
        const first = Object.keys(errors)[0];
        if (first) {
          memo.focus = first === "weeks" ? "add-week" : first;
          return say("표시한 항목을 고친 뒤 저장하세요.", true);
        }
        memo.busy = true;
        say("ASTRA 서버에 저장하고 있습니다.");
        try {
          await updateSyllabus(courseId, memo.version, body);
          afterSave(syllabusSlot);
          toast(api, "강의계획서를 저장했습니다");
        } catch (error) {
          memo.busy = false;
          if (isStaleVersion(error)) {
            memo.latest = { pending: true };
            say(syllabusEditMessage(error, "강의계획서"), true);
            load();
          } else say(syllabusEditMessage(error, "강의계획서"), true);
        }
      },
    });
  };

  // ---------- (b) 내 강사 정보 ----------
  const paintCard = () => {
    const memo = drafts.get(cardSlot);
    const controls = new Map();
    const say = (text, alert) => {
      memo.note = text || "";
      memo.alert = Boolean(alert);
      paintCard();
    };
    const me = backendState().me;
    // 담당자 명단에 없는 담당자(조교 등)는 강사 정보를 쓸 수 없다(409 `not_listed_instructor`). 폼 대신 그 문장만 보인다.
    if (!isListedInstructor(current, me && me.userId)) {
      controllers.delete(cardSheet);
      cardSheet.setAttribute("aria-busy", "false");
      cardSheet.replaceChildren(
        el("h2", { text: "내 강사 정보" }),
        el("p", { class: "pf-sub", role: "status", text: NOT_LISTED_TEXT }),
      );
      return;
    }
    const inputs = CARD_FIELDS.map(([name, label, type]) => {
      const control = el("input", { type, maxlength: String(LIMIT[name]), value: memo[name], autocomplete: "off", inputmode: type === "tel" ? "tel" : null });
      const node = bind(memo, controls, name, `${label} (${LIMIT[name]}자 이하)`, control);
      return name === "homepage" || name === "officeHours" ? el("div", { class: "span-2" }, node) : node;
    });
    const save = btn(memo.busy ? "저장하는 중…" : "내 강사 정보 저장", { "data-act": "pf-syllabus-card-save", "aria-busy": memo.busy ? "true" : "false" }, "primary");
    save.disabled = memo.busy;
    cardSheet.setAttribute("aria-busy", memo.busy ? "true" : "false");
    cardSheet.replaceChildren(
      el("h2", { text: "내 강사 정보" }),
      el("p", { class: "pf-sub", text: `${me && me.name ? `${me.name} 교수님의 정보입니다. ` : ""}${versionText(memo.version)}. 과목 구성원에게만 연락처가 보이고, 구성원이 아닌 사람에게는 이름과 소속만 보입니다.` }),
      el("div", { class: "pf-form" },
        ...inputs,
        memo.latest ? el("div", { class: "span-2" }, latestView(memo.latest)) : null,
        el("div", { class: "span-2" }, statusLine(memo)),
        el("div", { class: "span-2" }, el("div", { class: "pf-actions" }, save)),
      ),
    );
    focusPending(memo, controls);
    controllers.set(cardSheet, {
      async save(api) {
        if (memo.busy || drafts.get(cardSlot) !== memo) return;
        const { errors, body } = checkCard(memo);
        memo.errors = errors;
        const first = Object.keys(errors)[0];
        if (first) {
          memo.focus = first;
          return say("표시한 항목을 고친 뒤 저장하세요.", true);
        }
        memo.busy = true;
        say("ASTRA 서버에 저장하고 있습니다.");
        try {
          await updateMyInstructorCard(courseId, memo.version, body);
          afterSave(cardSlot);
          toast(api, "내 강사 정보를 저장했습니다");
        } catch (error) {
          memo.busy = false;
          if (isStaleVersion(error)) {
            memo.latest = { pending: true };
            say(syllabusEditMessage(error, "강사 정보"), true);
            load();
          } else {
            say(syllabusEditMessage(error, "강사 정보"), true);
            // 화면이 가진 명단과 서버 명단이 어긋났다. 다시 읽어 명단에 없으면 폼을 거둔다.
            if (error instanceof ApiError && error.code === "not_listed_instructor") load();
          }
        }
      },
    });
  };

  // 다시 읽기가 끝나면 충돌 때 기다리던 시트에 최신 내용과 새 version을 넣는다. 입력한 값은 그대로 둔다.
  const settle = (entry, slot, snapshot, versionFrom) => {
    const memo = drafts.get(slot);
    if (!memo || !memo.latest || !memo.latest.pending || entry.loading) return;
    if (entry.error) {
      memo.latest = null;
      memo.note = "서버의 최신 내용을 불러오지 못했습니다. 위의 다시 불러오기를 누른 뒤 저장하세요.";
      memo.alert = true;
      return;
    }
    memo.latest = snapshot();
    memo.version = versionFrom();
  };

  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      current = null;
      if (!entry || !entry.error) root.replaceChildren(loadingState("강의계획서를 불러오는 중입니다"));
      else if (entry.error instanceof ApiError && entry.error.status === 404) root.replaceChildren(emptyState("강의계획서를 고칠 수 없습니다", "이 과목의 강의계획서를 볼 권한이 없습니다. 담당 교수만 고칠 수 있습니다."));
      else root.replaceChildren(errorState("강의계획서를 불러오지 못했습니다", syllabusMessage(entry.error), retry(load)));
      return;
    }
    current = data;
    if (!drafts.has(syllabusSlot)) drafts.set(syllabusSlot, syllabusMemo(data));
    if (!drafts.has(cardSlot)) drafts.set(cardSlot, cardMemo(myCard(data)));
    settle(entry, syllabusSlot, () => latestSyllabus(data), () => versionOf(data.version));
    settle(entry, cardSlot, () => latestCard(myCard(data)), () => {
      const card = myCard(data);
      return card ? versionOf(card.version) : 0;
    });
    paintSyllabus();
    paintCard();
    const failed = entry.error && !entry.loading
      ? el("div", { class: "stack" }, el("p", { class: "err", role: "alert", text: syllabusMessage(entry.error) }), retry(load))
      : null;
    root.replaceChildren(head, failed || "", syllabusSheet, cardSheet);
  };

  paint(syllabi.peek(key));
  queueMicrotask(() => {
    if (!root.isConnected) return;
    syllabi.watch(root, key, paint, null);
    if (freshVisit("pf-syllabus", key) === "entry" || !syllabi.peek(key)) load();
  });
  return root;
}

function retry(reload) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    reload();
  });
  return button;
}

/** `pf-syllabus-save` (professor/actions.js). */
export function saveSyllabus(node, api) {
  const sheet = node.closest(".pf-syllabus-edit");
  const controller = sheet && controllers.get(sheet);
  if (controller) void controller.save(api);
}

/** `pf-syllabus-card-save` (professor/actions.js). */
export function saveInstructorCard(node, api) {
  const sheet = node.closest(".pf-syllabus-card");
  const controller = sheet && controllers.get(sheet);
  if (controller) void controller.save(api);
}
