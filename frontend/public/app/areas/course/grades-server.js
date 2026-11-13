// 학생 성적 화면(43, 43b, 43c)의 서버 버전. 공개 여부는 서버가 항목마다 정하므로 세 경로가 같은 서버 화면을 그린다.
// 출석 요약은 `GET …/attendance`, 평가 활동은 `GET …/grades/me?type=&q=`다. 공개하지 않은 항목에는 점수·의견·채점 일시
// 속성이 아예 없으므로 화면은 속성이 있을 때만 그리고, 합계는 서버가 준 값만 쓴다(직접 더하지 않는다).
import { el } from "../../dom.js";
import { btn, emptyState, loadingState, errorState, table } from "../../ui/components.js";
import { ApiError } from "../../shared/api.js";
import { backendStatus, courseIdFor } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { readQuery } from "../../shared/query.js";
import { freshVisit } from "../../shared/remote-cache.js";
import { koDateTime } from "../../shared/home-remote.js";
import { studentAssignmentHref } from "../../shared/assignments-remote.js";
import { myGrades, myGradesKey, GRADE_TYPES, TYPE_LABEL, points, totalText, gradeMessage } from "../../shared/grades-remote.js";
import { attendanceViews, attendanceKey, attendanceMessage } from "../../shared/attendance-remote.js";

const LOGIN = "ASTRA 서버에 로그인하면 이 과목의 성적을 서버에서 불러옵니다.";
const SEARCH_DELAY = 300;

const missing = (error) => error instanceof ApiError && error.status === 404;
const has = (object, key) => Object.prototype.hasOwnProperty.call(object, key);

function retry(reload) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    reload();
  });
  return button;
}

/** 이 항목의 점수 칸: 공개 전 "미공개 / 배점", 공개했고 점수 없음 "- / 배점", 점수 "획득 / 배점". */
function scoreCell(item) {
  const max = `${points(item.maxScore)}점`;
  if (!item.released) return el("span", { class: "crs-eval-score" }, el("span", { class: "tiny", text: "미공개" }), ` / ${max}`);
  if (!has(item, "score")) return el("span", { class: "crs-eval-score", text: `- / ${max}` });
  return el("span", { class: "crs-eval-score", text: `${points(item.score)} / ${max}` });
}

function itemRow(item, kj) {
  const meta = [
    TYPE_LABEL[item.type] || item.type,
    item.closeAt ? `마감 ${koDateTime(item.closeAt)}` : "",
    item.weight != null ? `반영 ${points(item.weight)}%` : "",
    has(item, "gradedAt") ? `채점 ${koDateTime(item.gradedAt)}` : "",
  ].filter(Boolean).join(" · ");
  const title = el("h3", { text: item.name });
  const main = el("div", {},
    item.assignmentId ? el("a", { href: studentAssignmentHref(kj, item.assignmentId), "data-go": studentAssignmentHref(kj, item.assignmentId) }, title) : title,
    el("p", { class: "tiny", text: meta }),
    has(item, "feedback") ? el("p", { class: "crs-note", text: `의견: ${item.feedback}` }) : null,
  );
  return el("article", { class: "crs-eval-row", "data-grade-item": String(item.itemId) },
    el("span", { class: "crs-kind", text: TYPE_LABEL[item.type] || item.type }),
    main,
    scoreCell(item),
    el("span", { class: `crs-eval-check${item.submitted ? " done" : ""}`, "aria-label": item.submitted ? "제출함" : null, text: item.submitted ? "✓" : "" }),
  );
}

function attendanceBlock(courseId) {
  const key = attendanceKey(courseId);
  const load = () => attendanceViews.load(key, courseId);
  const body = el("div", {});
  const block = el("section", { class: "crs-block" }, el("h2", { text: "출석" }), body);
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      if (!entry || !entry.error) body.replaceChildren(loadingState("출석을 불러오는 중입니다"));
      else if (missing(entry.error)) body.replaceChildren(el("p", { class: "crs-note", text: "출석 정보를 볼 수 없습니다." }));
      else body.replaceChildren(errorState("출석을 불러오지 못했습니다", attendanceMessage(entry.error), retry(load)));
      return;
    }
    const s = data.summary;
    if (!s) {
      body.replaceChildren(el("p", { class: "crs-note", text: "학생 본인의 출석 요약만 보여 줍니다." }));
      return;
    }
    const settings = data.settings || {};
    body.replaceChildren(
      el("p", { class: "crs-note", text: settings.latesPerAbsence > 0
        ? `지각 ${settings.latesPerAbsence}번을 결석 1번으로 칩니다. 결석 환산 ${s.absenceEquivalent}번.`
        : "출석 기준은 담당 교수의 설정을 따릅니다." }),
      table(["전체", "출석", "지각", "결석", "유고"], [[String(s.sessions), String(s.present), String(s.late), String(s.absent), String(s.excused)]]),
    );
  };
  paint(attendanceViews.peek(key));
  queueMicrotask(() => {
    if (!block.isConnected) return;
    attendanceViews.watch(block, key, paint, load);
    if (freshVisit("grades-attendance", key) === "entry" || !attendanceViews.peek(key)) load();
  });
  return block;
}

/** 강의실 성적 화면(백엔드 모드). `parts`는 course.js의 head·searchBox와 검색어 범위다. */
export function remoteGrades(screen, ctx, course, parts) {
  const root = el("section", { class: "crs crs-grades" }, parts.head("성적", "chart"));
  if (backendStatus() === "anon") {
    root.append(backendPanel({ text: LOGIN }));
    return root;
  }
  const courseId = courseIdFor(course.id);
  if (!courseId) {
    root.append(emptyState("이 과목은 ASTRA 서버에 없습니다", "서버에 등록된 과목만 성적을 볼 수 있습니다."));
    return root;
  }
  const type = GRADE_TYPES.some(([key]) => key === ctx.state.draft.srvGradeType) ? ctx.state.draft.srvGradeType : "all";
  const q = readQuery(ctx.state, parts.scope).trim().slice(0, 100);
  const key = myGradesKey(courseId, type, q);
  const load = () => myGrades.load(key, courseId, type, q);
  const totalSlot = el("div", { class: "crs-grade-total", "aria-live": "polite" });
  const body = el("div", { class: "crs-remote", "aria-live": "polite" });
  const toolbar = el("div", { class: "crs-toolbar" }, el("h2", { text: "평가 활동" }), parts.searchBox("평가 항목 검색", ctx, "grades"));
  const filters = el("div", { class: "crs-filter-row", role: "toolbar", "aria-label": "평가 종류" },
      ...GRADE_TYPES.map(([value, label]) => el("button", {
        type: "button",
        class: `chip${type === value ? " on" : ""}`,
        "data-act": "srv-grade-filter",
        "data-value": value,
        "aria-pressed": type === value ? "true" : "false",
        text: label,
      })),
  );
  const evalBlock = el("section", { class: "crs-block" }, toolbar, filters, totalSlot, body);
  root.append(attendanceBlock(courseId), evalBlock);

  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      totalSlot.replaceChildren();
      if (!entry || !entry.error) body.replaceChildren(loadingState("성적을 불러오는 중입니다"));
      else if (missing(entry.error)) body.replaceChildren(emptyState("성적을 볼 수 없습니다", "이 과목의 수강생 계정으로 로그인해야 성적을 볼 수 있습니다."));
      else body.replaceChildren(errorState("성적을 불러오지 못했습니다", gradeMessage(entry.error), retry(load)));
      return;
    }
    // 평가 활동 비공개 과목(43c): 목록과 합계가 응답에 없다.
    filters.hidden = !data.activitiesVisible;
    toolbar.querySelector(".crs-search").hidden = !data.activitiesVisible;
    if (!data.activitiesVisible) {
      totalSlot.replaceChildren();
      body.replaceChildren(el("p", { class: "crs-note", text: "담당 교수가 평가 활동을 공개하지 않은 과목입니다." }));
      return;
    }
    totalSlot.replaceChildren(data.total
      ? el("p", { class: "crs-note" }, el("b", { text: `합계 ${totalText(data.total)}` }), " · 공개한 항목 가운데 점수를 받은 것만 더했습니다.")
      : el("p", { class: "crs-note", text: "공개된 점수가 아직 없어 합계가 없습니다." }));
    const items = Array.isArray(data.items) ? data.items : [];
    if (!items.length) {
      body.replaceChildren(q || type !== "all"
        ? emptyState("해당하는 항목이 없습니다", "다른 종류를 선택하거나 검색어를 지워 보세요.")
        : emptyState("평가 항목이 없습니다", "담당 교수가 성적 항목을 만들면 여기에 표시됩니다."));
      return;
    }
    body.replaceChildren(
      entry.error ? el("p", { class: "err", role: "alert", text: gradeMessage(entry.error) }) : "",
      el("div", { class: "crs-eval" }, ...items.map((item) => itemRow(item, course.id))),
    );
  };
  paint(myGrades.peek(key));
  queueMicrotask(() => {
    if (!root.isConnected) return;
    myGrades.watch(root, key, paint, load);
    const fresh = freshVisit("course-grades", key);
    if (fresh === "entry") load();
    else if (!myGrades.peek(key)) {
      if (fresh === "key" && q) setTimeout(() => { if (root.isConnected && !myGrades.peek(key)) load(); }, SEARCH_DELAY);
      else load();
    }
  });
  return root;
}
