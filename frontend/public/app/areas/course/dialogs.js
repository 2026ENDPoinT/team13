import { el, icon } from "../../dom.js";
import { btn, field, dialogShell, table, emptyState } from "../../ui/components.js";
import { courseById, courses, activitiesOf, professor, weekCount } from "../../data.js";
import { courseRows } from "./actions.js";
import { readQuery } from "../../shared/query.js";
import { routeFor } from "../../shared/routes.js";
import { backendStatus, courseIdFor } from "../../shared/backend.js";
import { serverCourseViews } from "../../shared/course-view.js";
import { remoteNoticeDialog } from "./notices-server.js";
import { remoteQnaDialog, remoteQnaWriteDialog, remoteOpenWriteDialog, remoteOpenPostDialog } from "./boards-server.js";
import { remoteSyllabusDialog } from "./syllabus-server.js";

function termLabel(term) {
  // 서버 과목의 학기는 서버의 학기 이름 그대로다(shared/course-view.js).
  if (term && !/^\d{4}-\d+$/.test(term)) return term;
  const [year, sem] = String(term || "2026-2").split("-");
  return `${year}학년도 ${sem}학기`;
}

function weekRange(n) {
  const start = new Date(2026, 7, 31 + (n - 1) * 7);
  const end = new Date(start);
  end.setDate(start.getDate() + 6);
  const fmt = (d) => `${d.getMonth() + 1}월 ${d.getDate()}일`;
  return `${fmt(start)} – ${fmt(end)}`;
}

function meta(pairs, layout) {
  return el("dl", { class: layout ? `crs-meta ${layout}` : "crs-meta" }, ...pairs.flatMap(([label, value]) => [
    el("dt", { text: label }),
    el("dd", { text: value || "—" }),
  ]));
}

function courseDialog(title, body, options = {}) {
  return dialogShell(title, body, { ...options, className: "crs-modal" });
}

function compose(title, board, err, existing) {
  const editing = Boolean(existing);
  const heading = editing ? title.replace("작성", "수정") : title;
  return courseDialog(heading === title && editing ? `${title} 수정` : heading, el("div", { class: "crs-dialog" },
    field("제목", el("input", { "data-name": "title", value: existing ? existing.title : "", placeholder: "제목을 입력하세요" }), err.title),
    field("내용", el("textarea", { "data-name": "body", placeholder: "내용을 4자 이상 입력하세요", text: existing ? existing.body : "" }), err.body),
    el("div", { class: "drop", text: "파일 첨부와 글 서식은 이 샘플에 저장되지 않습니다." }),
    el("div", { class: "crs-form-actions" }, btn("저장", {
      "data-act": editing ? "update-post" : "save-post",
      "data-board": board,
      "data-id": existing ? existing.id : null,
    }, "primary")),
  ));
}

function syllabus(ctx) {
  const course = courseById(ctx.state.courseId);
  const special = course.kind === "special";
  const weeks = [];
  for (let n = 1; n <= weekCount; n += 1) {
    const titles = activitiesOf(course.id).filter((item) => item.week === n).map((item) => item.title);
    if (!titles.length) continue;
    weeks.push([special ? `${n}회차` : `${n}주`, weekRange(n), titles.join(" · ")]);
  }
  const overview = special
    ? "비교과 과정은 영상과 짧은 확인 활동으로 이루어집니다. 진도 기준과 설문은 강의실에 적힌 일정을 따릅니다. 이 문장은 샘플이며 학교 안내문 원문을 옮기지 않았습니다."
    : "경영 의사결정에 필요한 데이터를 모으고, 정리하고, 읽는 수업입니다. 도구 화면을 외우기보다, 어떤 질문을 데이터로 바꿀 수 있는지 구분하는 연습을 합니다. 이 문장은 샘플이며 학교 강의계획서 원문을 옮기지 않았습니다.";
  return courseDialog("강의계획서", el("div", { class: "crs-dialog" },
    el("div", { class: "crs-prof" },
      el("span", { class: "crs-avatar", text: course.instructor.slice(0, 1) }),
      el("div", {},
        el("strong", { text: course.instructor }),
        el("span", { class: "tiny", text: `${professor.dept} · 담당 교수` }),
      ),
    ),
    el("h3", { text: "강의 계획" }),
    meta([
      ["년도/학기", termLabel(course.term)],
      ["교과목명", course.ko],
      ["교과목번호", course.code],
      ["이수구분", special ? "비교과" : "전공"],
      ["학점", course.credit],
      ["강의 시간", course.time],
      ["강의실", course.room],
      ["영문명", course.en],
      ["담당 교수", course.instructor],
      ["소속", professor.dept],
      ["수업 형태", special ? "온라인" : "대면 · 온라인"],
      ["수강 인원", `${course.learners}명`],
    ]),
    el("h3", { text: "과목 개요" }),
    el("p", { class: "crs-prose", text: overview }),
    el("h3", { text: "수업 목표" }),
    el("ul", { class: "crs-goals" },
      el("li", { text: "문제와 데이터 범위를 한 문단으로 적는다." }),
      el("li", { text: "정리 전과 후의 차이를 표로 설명한다." }),
      el("li", { text: "결과의 한계를 보고 문장에 남긴다." }),
    ),
    el("h3", { text: "주차별 계획" }),
    weeks.length ? table(["주차", "기간", "활동"], weeks) : el("p", { class: "tiny", text: "등록된 주차가 없습니다." }),
  ), { wide: true });
}

function switcher(ctx) {
  const q = readQuery(ctx.state, "course:switch").trim().toLowerCase();
  const current = ctx.state.courseId;
  const professorMode = ctx.state.role === "professor";
  // 백엔드 `ready`: 서버의 내 과목(교수 화면은 담당, 학생 화면은 수강)만, 서버 사실로 보인다.
  const ready = backendStatus() === "ready";
  const mine = ready
    ? serverCourseViews([professorMode ? "INSTRUCTOR" : "STUDENT"]).map(({ course }) => course)
    : professorMode
      ? courses.filter((course) => ["bi", "db", "sec"].includes(course.id))
      : courses.filter((course) => course.enrolled);
  const isCurrent = (course) => course.id === current || (ready && courseIdFor(current) != null && courseIdFor(current) === course.courseId);
  const hit = (course) => !q || [course.ko, course.en, course.code, course.instructor].filter(Boolean).join(" ").toLowerCase().includes(q);
  const groups = [
    ["정규과목", mine.filter((course) => course.kind !== "special" && hit(course))],
    ["비정규과목", mine.filter((course) => course.kind === "special" && hit(course))],
  ];
  const body = el("div", { class: "crs-dialog" },
    el("div", { class: "crs-search" },
      icon("search"),
      el("input", {
        type: "text",
        "data-name": "surfaceQuery",
        "data-scope": "course:switch",
        "data-focus": "course:switch",
        value: readQuery(ctx.state, "course:switch"),
        placeholder: "과목, 코드",
        "aria-label": "과목 검색",
        autocomplete: "off",
      }),
    ),
    el("p", { class: "tiny", text: termLabel(courseById(current).term) }),
  );
  let count = 0;
  for (const [label, list] of groups) {
    if (!list.length) continue;
    count += list.length;
    body.append(el("h3", { text: label }));
    for (const course of list) {
      const href = routeFor(professorMode ? "professor" : "student", "classroom", { kj: course.id, special: course.kind === "special" });
      body.append(el("button", { type: "button", class: `crs-pick${isCurrent(course) ? " on" : ""}`, "data-go": href },
        el("span", { class: `crs-pick-bar ${course.gradient || "g3"}`, "aria-hidden": "true" }),
        el("span", { class: "crs-pick-main" },
          el("b", { text: course.code ? `${course.ko} (${course.code})` : course.ko }),
          el("span", { class: "tiny", text: [course.time, course.room].filter(Boolean).join(" · ") || "시간 미정" }),
        ),
        isCurrent(course) ? el("span", { class: "crs-kind", text: "현재" }) : null,
      ));
    }
  }
  if (!count) body.append(emptyState("해당하는 과목이 없습니다", "이름이나 코드의 일부를 입력해 보세요."));
  return courseDialog("과목 변경", body);
}

export function renderDialog(name, ctx) {
  const err = ctx.state.formError || {};
  const draft = ctx.state.draft || {};
  const id = ctx.params && ctx.params.get("id");
  if (name === "notice") {
    if (ctx.screen.board !== "course") return null;
    if (backendStatus() !== "off") return remoteNoticeDialog(ctx, courseById(ctx.state.courseId));
    const rows = courseRows({ board: "course", course: ctx.state.courseId }, ctx.state);
    const row = rows.find((item) => item.id === id) || rows[0];
    if (!row) return null;
    return courseDialog("공지사항", el("div", { class: "crs-dialog" },
      el("h3", { class: "crs-dialog-title", text: row.title }),
      meta([
        ["작성자", row.author || "담당 교수"],
        ["게시일", row.date || ""],
        ["공지 기한", "상시"],
      ], "pairs"),
      el("div", { class: "crs-prose" }, el("p", { text: row.body || "" })),
      el("div", { class: "crs-form-actions" }, btn("공지를 확인했습니다", { "data-act": "close" }, "primary")),
    ));
  }
  if (name === "syllabus" && backendStatus() !== "off") return remoteSyllabusDialog(courseById(ctx.state.courseId));
  if (name === "syllabus") return syllabus(ctx);
  if (name === "switch") return switcher(ctx);
  // Backend mode: Q&A and open board dialogs (areas/course/boards-server.js); `pf_qna` shares the Q&A ones.
  if (backendStatus() !== "off" && (ctx.screen.board === "course-qna" || ctx.screen.kind === "pf-qna")) {
    if (name === "qna" || name === "qna-reply") return remoteQnaDialog(ctx, courseById(ctx.state.courseId), name);
    if (name === "qna-write") return remoteQnaWriteDialog(ctx, courseById(ctx.state.courseId));
  }
  if (backendStatus() !== "off" && ctx.screen.board === "open") {
    if (name === "board-write") return remoteOpenWriteDialog(ctx, courseById(ctx.state.courseId));
    if (name === "board-post") return remoteOpenPostDialog(ctx, courseById(ctx.state.courseId));
  }
  if (name === "qna" || name === "qna-reply") {
    const rows = courseRows({ board: "course-qna" }, ctx.state);
    const row = rows.find((item) => item.id === id) || rows[0];
    const reply = row && (row.reply || (draft.replies && draft.replies[row.id]));
    const body = el("div", { class: "crs-dialog" });
    if (!row) {
      body.append(emptyState("질문을 찾을 수 없습니다", "목록으로 돌아가 다시 선택해 주세요."));
      return courseDialog("질의응답", body);
    }
    body.append(el("div", { class: "crs-prof" },
      el("span", { class: "crs-avatar", text: (row.author || "질").slice(0, 1) }),
      el("div", {},
        el("strong", { text: row.author || "학생" }),
        el("span", { class: "tiny", text: row.at || "" }),
      ),
    ));
    body.append(el("h3", { class: "crs-dialog-title", text: row.title }));
    body.append(el("div", { class: "crs-prose" }, el("p", { text: row.body || "" })));
    if (reply) body.append(el("div", { class: "sheet crs-reply" }, el("b", { text: "답변" }), el("p", { text: reply })));
    else body.append(el("p", { class: "tiny", text: "아직 답변이 없습니다." }));
    if (name === "qna-reply") {
      body.append(field("답글", el("textarea", { "data-name": "reply", placeholder: "답글을 입력하세요" }), err.reply));
      body.append(el("div", { class: "crs-form-actions" }, btn("답글 저장", { "data-act": "save-reply", "data-id": row.id }, "primary")));
    } else {
      body.append(el("div", { class: "crs-form-actions" },
        el("a", {
          class: "btn btn-secondary",
          href: `/ilos/cls/st/qna/qna_list_form.acl?pop=qna-write&id=${row.id}&kj=${ctx.state.courseId}`,
          "data-go": `/ilos/cls/st/qna/qna_list_form.acl?pop=qna-write&id=${row.id}&kj=${ctx.state.courseId}`,
          text: "수정",
        }),
        btn("삭제", { "data-act": "delete-post", "data-id": row.id, "data-dialog": "1" }, "ghost"),
        el("a", {
          class: "btn btn-secondary",
          href: `/ilos/cls/st/qna/qna_list_form.acl?pop=qna-reply&id=${row.id}&kj=${ctx.state.courseId}`,
          "data-go": `/ilos/cls/st/qna/qna_list_form.acl?pop=qna-reply&id=${row.id}&kj=${ctx.state.courseId}`,
          text: "답글 쓰기",
        }),
      ));
    }
    return courseDialog("질의응답", body);
  }
  if (name === "qna-write" && ctx.screen.board === "course-qna") {
    const existing = id ? courseRows({ board: "course-qna" }, ctx.state).find((row) => row.id === id) : null;
    return compose("질문 작성", "course-qna", err, existing);
  }
  if (name === "board-write") {
    const existing = id ? courseRows({ board: "open" }, ctx.state).find((row) => row.id === id) : null;
    return compose("열린게시판", "open", err, existing);
  }
  return null;
}
