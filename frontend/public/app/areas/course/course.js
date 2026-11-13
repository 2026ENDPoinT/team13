import { el, icon } from "../../dom.js";
import { go, emptyState, loadingState, table, mark, field, btn, pagination, tabs, badge, courseName } from "../../ui/components.js";
import { files, weekCount, courseById, activitiesOf } from "../../data.js";
import { hrefFor, find } from "../../manifest.js";
import { passFilter, attendStatus, activityHref, typeLabel } from "../../shared/format.js";
import { activityRow } from "../../shared/activity-row.js";
import { readQuery } from "../../shared/query.js";
import { courseRows } from "./actions.js";
import { remoteNoticeBoard } from "./notices-server.js";
import { backendStatus } from "../../shared/backend.js";
import { remoteRoom, remoteActivityList, remoteFiles, serverOwnsActivities } from "./activities-server.js";
import { remoteQnaBoard, remoteOpenBoard } from "./boards-server.js";
import { remoteGrades } from "./grades-server.js";
import { remoteGroups } from "./groups-server.js";

const PAGE = 8;

const ROOM_TYPES = [
  ["video", "영상"],
  ["material", "자료"],
  ["report", "과제"],
  ["test", "시험"],
  ["zoom", "Zoom"],
  ["post", "글"],
  ["survey", "설문"],
];

const LIST_TYPES = [
  ["all", "전체", "50_activity_all"],
  ["video", "영상", "51_activity_filter_video"],
  ["material", "자료", "52_activity_filter_material"],
  ["report", "과제", "53_activity_filter_report"],
  ["test", "시험", "54_activity_filter_test"],
];

const LIST_STATUS = [
  ["ongoing", "진행중", "55_activity_filter_ongoing"],
  ["missing", "미제출", "56_activity_filter_not_submit"],
];

function termLabel(term) {
  // 서버 과목의 학기는 서버의 학기 이름 그대로다(shared/course-view.js).
  if (term && !/^\d{4}-\d+$/.test(term)) return term;
  const [year, sem] = String(term || "2026-2").split("-");
  return `${year}학년도 ${sem}학기`;
}

// 백엔드 `ready`인데 서버에 없는 과목(서버에 없는 샘플 ID, 모르는 kj): 공지·Q&A·성적과 같은 안내만 보이고 샘플 활동·파일을 그리지 않는다.
function absentState(what) {
  return emptyState("이 과목은 ASTRA 서버에 없습니다", `서버에 등록된 과목의 ${what}만 볼 수 있습니다.`);
}

function weekRange(n) {
  const start = new Date(2026, 7, 31 + (n - 1) * 7);
  const end = new Date(start);
  end.setDate(start.getDate() + 6);
  const fmt = (d) => `${d.getMonth() + 1}월 ${d.getDate()}일`;
  return `${fmt(start)} – ${fmt(end)}`;
}

function excerpt(text, n = 96) {
  const value = String(text || "").replace(/\s+/g, " ").trim();
  if (value.length <= n) return value;
  return `${value.slice(0, n)}…`;
}

function courseScope(scope) {
  return `course:${scope}`;
}

function queryOf(ctx, scope) {
  return readQuery(ctx.state, courseScope(scope)).trim().toLowerCase();
}

function matches(q, ...parts) {
  if (!q) return true;
  return parts.some((part) => String(part || "").toLowerCase().includes(q));
}

function searchBox(label, ctx, scope) {
  const key = courseScope(scope);
  const value = readQuery(ctx.state, key);
  return el("div", { class: "crs-search" },
    icon("search"),
    el("input", {
      type: "text",
      "data-name": "surfaceQuery",
      "data-scope": key,
      "data-focus": key,
      value,
      placeholder: "검색",
      "aria-label": label,
      autocomplete: "off",
    }),
    value ? el("button", { type: "button", class: "crs-search-clear", "data-act": "clear-query", "data-scope": key, "aria-label": "검색어 지우기", text: "×" }) : null,
  );
}

function head(title, iconName, count, ...actions) {
  return el("div", { class: "crs-head" },
    el("div", { class: "crs-head-title" },
      el("span", { class: "crs-mark", "aria-hidden": "true" }, icon(iconName)),
      el("h1", { text: title }),
      count != null ? badge(String(count), "neutral") : null,
    ),
    actions.length ? el("div", { class: "crs-head-actions" }, ...actions) : null,
  );
}

function quiet(title, body, kind, ...actions) {
  return el("div", { class: "empty crs-empty", role: "status" },
    el("div", { class: "crs-empty-art", "aria-hidden": "true" }, icon("layers")),
    el("strong", { text: title }),
    body ? el("p", { text: body }) : null,
    el("div", { class: "btn-row" },
      ...actions,
      btn("다시 불러오기", { "data-act": "course-reload", "data-kind": kind }, "ghost"),
    ),
  );
}

function weekMark(list, week) {
  const items = list.filter((item) => item.week === week);
  if (!items.length) return "";
  const done = items.filter((item) => item.status === "done" || item.status === "submitted" || item.status === "graded").length;
  if (!done) return "";
  if (done === items.length) return "✓";
  return `${done}/${items.length}`;
}

function roomCourse(screen, ctx) {
  const queryId = ctx.params && ctx.params.get("kj");
  if (queryId && courseById(queryId).id === queryId) return courseById(queryId);
  if (ctx.state.courseId && courseById(ctx.state.courseId).id === ctx.state.courseId) return courseById(ctx.state.courseId);
  return courseById(screen.course);
}

function renderRoom(screen, ctx) {
  const course = roomCourse(screen, ctx);
  // 백엔드 모드: 연결된 과목은 서버 주차·활동·공지·할 일만 그린다(areas/course/activities-server.js).
  if (backendStatus() !== "off") {
    return serverOwnsActivities(course) ? remoteRoom(screen, ctx, course) : el("section", { class: "crs" }, absentState("강의실"));
  }
  const all = activitiesOf(course.id);
  const draft = ctx.state.draft || {};
  const type = screen.focus === "video" ? "video" : (draft.roomType || "");
  const status = draft.roomStatus || "";
  let shown = all;
  if (type) shown = shown.filter((item) => item.type === type);
  if (status) shown = shown.filter((item) => passFilter(item, status, ctx.state));
  const week = ctx.state.week || 1;
  const unit = course.kind === "special" ? "회차" : "주";
  const next = all.find((item) => item.status === "ongoing") || all[0];
  const tags = [];
  if (course.kind === "special") tags.push("비교과", "온라인");
  else {
    if (all.some((item) => item.type === "video" || item.type === "zoom")) tags.push("온라인");
    if (course.room && course.room !== "e-learning") tags.push("대면");
  }
  if (course.credit) tags.push(`${course.credit}학점`);
  const presentTypes = new Set(all.map((item) => item.type));
  const planHref = hrefFor(find("40b_room_lecplan_pop"), { kj: course.id });
  const closed = new Set(draft.closedWeeks || []);
  const root = el("section", { class: "crs" },
    el("article", { class: "crs-hero" },
      el("div", { class: "crs-hero-copy" },
        el("p", { class: "crs-kicker", text: termLabel(course.term) }),
        courseName(course, "h1", course.ko),
        el("p", { class: "crs-hero-meta", text: [course.en, course.time, course.code].filter(Boolean).join(" · ") }),
        el("div", { class: "crs-tags" }, ...tags.map((tag) => el("span", { class: "crs-tag", text: tag }))),
      ),
      next ? go(activityHref(next, course.id), "이어서 학습하기", { class: "btn btn-primary" }) : null,
    ),
    el("a", { class: "crs-plan", href: planHref, "data-go": planHref },
      el("span", { class: "crs-plan-label" }, icon("file"), el("span", { text: "강의계획서" })),
      icon("chev"),
    ),
    el("div", { class: "crs-filters" },
      el("div", { class: "crs-filter-row", role: "toolbar", "aria-label": "활동 종류" },
        ...ROOM_TYPES.filter(([key]) => presentTypes.has(key)).map(([key, label]) => el("button", {
          type: "button",
          class: `chip ${key}${type === key ? " on" : ""}`,
          "data-act": "room-filter",
          "data-kind": "type",
          "data-value": key,
          "aria-pressed": type === key ? "true" : "false",
          text: label,
        })),
      ),
      el("div", { class: "crs-filter-row", role: "toolbar", "aria-label": "진행 상태" },
        ...[["ongoing", "진행중"], ["missing", "미제출"]].map(([key, label]) => el("button", {
          type: "button",
          class: `chip${status === key ? " on" : ""}`,
          "data-act": "room-filter",
          "data-kind": "status",
          "data-value": key,
          "aria-pressed": status === key ? "true" : "false",
          text: label,
        })),
      ),
    ),
    el("div", { class: "crs-progress", role: "tablist", "aria-label": "주차" },
      ...Array.from({ length: weekCount }, (_, index) => {
        const n = index + 1;
        const markText = weekMark(all, n);
        return el("button", {
          type: "button",
          class: `crs-wk${week === n ? " on" : ""}`,
          "data-act": "week",
          "data-week": String(n),
          "aria-pressed": week === n ? "true" : "false",
          "aria-label": `${n}${unit}`,
        },
        el("span", { text: String(n) }),
        el("span", { class: "crs-wk-mark", text: markText || " " }),
        );
      }),
    ),
  );
  const weeks = [...new Set(shown.map((item) => item.week))].sort((a, b) => a - b);
  if (!weeks.length) {
    root.append(quiet("해당하는 활동이 없습니다", "다른 종류나 진행 상태를 선택해 보세요.", "room"));
    return root;
  }
  for (const n of weeks) {
    const items = shown.filter((item) => item.week === n);
    const isClosed = closed.has(n);
    root.append(el("section", { class: `crs-week${isClosed ? "" : " open"}${week === n ? " current" : ""}` },
      el("button", {
        type: "button",
        class: "crs-week-h",
        "data-act": "toggle-week",
        "data-week": String(n),
        "aria-expanded": isClosed ? "false" : "true",
      },
      el("span", { class: "crs-week-label" },
        el("strong", { text: `${n}${unit}` }),
        el("span", { text: weekRange(n) }),
      ),
      icon("chev"),
      ),
      isClosed ? null : el("div", { class: "crs-week-b" }, ...items.map((item) => activityRow(item, ctx, course.id))),
    ));
  }
  return root;
}

function renderActivityList(screen, ctx) {
  const linked = roomCourse(screen, ctx);
  // 백엔드 모드: 연결된 과목은 서버 활동만 필터에 맞춰 그린다(areas/course/activities-server.js).
  const server = backendStatus() !== "off" && serverOwnsActivities(linked);
  const absent = backendStatus() !== "off" && !server;
  const course = server || absent ? linked : courseById(screen.course || ctx.state.courseId);
  const filter = screen.filter || "all";
  const rows = server || absent ? [] : activitiesOf(course.id).filter((item) => passFilter(item, filter, ctx.state));
  const pages = Math.max(1, Math.ceil(rows.length / PAGE));
  const page = Math.min(pages, Math.max(1, Number(ctx.params && ctx.params.get("page")) || 1));
  const slice = rows.slice((page - 1) * PAGE, page * PAGE);
  const weeks = [...new Set(slice.map((item) => item.week))].sort((a, b) => a - b);
  const rail = el("div", { class: "crs-rail" },
    el("div", { class: "crs-filters" },
      el("div", { class: "crs-filter-row", role: "toolbar", "aria-label": "활동 종류" },
        ...LIST_TYPES.map(([key, label, id]) => go(hrefFor(find(id), { kj: course.id }), label, { class: `chip ${key}${filter === key ? " on" : ""}` })),
      ),
      el("div", { class: "crs-filter-row", role: "toolbar", "aria-label": "진행 상태" },
        ...LIST_STATUS.map(([key, label, id]) => go(hrefFor(find(id), { kj: course.id }), label, { class: `chip${filter === key ? " on" : ""}` })),
      ),
    ),
  );
  if (server) rail.append(remoteActivityList(screen, ctx, course, filter));
  else if (absent) rail.append(absentState("활동"));
  else if (!slice.length) rail.append(quiet("해당하는 활동이 없습니다", "다른 필터를 선택해 보세요.", "activity-list"));
  else {
    for (const n of weeks) {
      rail.append(el("section", { class: "crs-week open" },
        el("h2", { class: "crs-rail-week", text: `${n}주  ${weekRange(n)}` }),
        ...slice.filter((item) => item.week === n).map((item) => activityRow(item, ctx, course.id)),
      ));
    }
  }
  if (!server && !absent) {
    rail.append(pagination({
      page,
      pages,
      hrefForPage: (n) => hrefFor(find(screen.id), n > 1 ? { kj: course.id, page: String(n) } : { kj: course.id }),
    }));
  }
  return el("section", { class: "crs crs-split" },
    rail,
    el("div", { class: "crs-stage" }, el("p", { text: "활동을 선택하세요." })),
  );
}

function renderGroups(ctx) {
  const teams = ctx.state.teams || [];
  const root = el("section", { class: "crs" }, head("그룹활동", "users", teams.length || null));
  if (teams.length) {
    for (const team of teams) {
      root.append(el("article", { class: "crs-team" },
        el("h2", { text: team.name }),
        el("p", { text: team.topic }),
        el("span", { class: "tiny", text: `인원 ${team.size}` }),
      ));
    }
  } else {
    root.append(quiet(
      "생성된 그룹이 없습니다.",
      "팀 프로젝트 등 그룹 활동이 열리면 이 목록에 표시됩니다.",
      "groups",
      ctx.state.draft.teamOpen ? null : btn("구성 요청", { "data-act": "open-team-ask" }, "primary"),
    ));
  }
  if (ctx.state.teamAsk) {
    root.append(el("div", { class: "sheet crs-ask" },
      el("h2", { text: "구성 요청" }),
      el("p", { text: ctx.state.teamAsk }),
    ));
  } else if (ctx.state.draft.teamOpen) {
    root.append(el("div", { class: "sheet crs-ask" },
      field("요청 내용", el("textarea", { "data-name": "teamAsk", placeholder: "희망 주제와 인원" })),
      btn("요청 저장", { "data-act": "save-team-ask" }, "primary"),
    ));
  }
  return root;
}

function attendanceCounts(state) {
  const counts = { present: 0, late: 0, absent: 0, total: weekCount };
  for (let week = 1; week <= weekCount; week += 1) {
    const status = attendStatus(state, "minjun", week, "main");
    if (status === "late") counts.late += 1;
    else if (status === "absent") counts.absent += 1;
    else if (status === "present" || status === "excused") counts.present += 1;
  }
  return counts;
}

function renderGrades(screen, ctx) {
  // 백엔드 모드: 공개 여부는 서버가 항목마다 정하므로 세 경로가 같은 서버 화면이다(areas/course/grades-server.js).
  if (backendStatus() !== "off") return remoteGrades(screen, ctx, roomCourse(screen, ctx), { head, searchBox, scope: courseScope("grades") });
  const mode = screen.gradesMode || (ctx.state.gradePublic ? "public" : "partial");
  const kind = (ctx.state.draft && ctx.state.draft.gradeKind) || "all";
  const q = queryOf(ctx, "grades");
  const root = el("section", { class: "crs crs-grades" },
    head("성적", "chart"),
    tabs([
      { label: "집계", href: hrefFor(find("43_room_eval_result")), on: mode === "partial" },
      { label: "공개", href: hrefFor(find("43b_room_eval_public")), on: mode === "public" },
      { label: "비공개", href: hrefFor(find("43c_room_eval_private")), on: mode === "private" },
    ]),
  );
  if (mode === "private") {
    root.append(quiet("성적이 비공개입니다", "담당 교수가 공개로 전환하기 전에는 점수를 보여 주지 않습니다.", "grades"));
    return root;
  }
  const counts = attendanceCounts(ctx.state);
  const mine = ctx.state.scores.minjun || {};
  const course = courseById(screen.course || ctx.state.courseId);
  const items = [];
  const attendanceScore = mode === "public" ? `${mine.att ?? "—"} / 10` : "공개 예정";
  items.push({ key: "att", type: "출석", title: "출석", meta: `${weekCount}주`, score: attendanceScore, done: mode === "public" && mine.att != null, filter: "att" });
  for (const activity of activitiesOf(course.id)) {
    if (activity.type !== "report" && activity.type !== "test") continue;
    let score = "—";
    let done = false;
    if (activity.type === "report") {
      if (ctx.state.submissions[activity.id]) {
        score = mode === "public" ? String(mine.rep || "채점 중") : "제출";
        done = true;
      } else score = "미제출";
    } else if (activity.score != null) {
      score = mode === "public" ? `${activity.score} / ${activity.points}` : String(activity.score);
      done = activity.status === "graded";
    }
    items.push({
      key: activity.id,
      type: typeLabel(activity.type),
      title: activity.title,
      meta: `${activity.week}주 · ${activity.due ? activity.due.slice(0, 10) : "일정 없음"}`,
      score,
      done,
      filter: activity.type,
    });
  }
  const visible = items.filter((item) => (kind === "all" || item.filter === kind) && matches(q, item.title, item.type));
  root.append(el("section", { class: "crs-block" },
    el("h2", { text: "출석" }),
    el("p", { class: "crs-note", text: "출석 기준은 담당 교수의 설정을 따릅니다." }),
    table(["전체", "출석", "지각", "결석"], [[String(counts.total), String(counts.present), String(counts.late), String(counts.absent)]]),
  ));
  root.append(el("section", { class: "crs-block" },
    el("div", { class: "crs-toolbar" },
      el("h2", { text: "평가 항목" }),
      searchBox("평가 항목 검색", ctx, "grades"),
    ),
    el("div", { class: "crs-filter-row", role: "toolbar", "aria-label": "평가 종류" },
      ...[["all", "전체"], ["report", "과제"], ["test", "시험"]].map(([key, label]) => el("button", {
        type: "button",
        class: `chip${kind === key ? " on" : ""}`,
        "data-act": "grade-filter",
        "data-value": key,
        "aria-pressed": kind === key ? "true" : "false",
        text: label,
      })),
    ),
    visible.length ? el("div", { class: "crs-eval" }, ...visible.map((item) => el("article", { class: "crs-eval-row" },
      el("span", { class: "crs-kind", text: item.type }),
      el("div", {},
        el("h3", { text: item.title }),
        el("p", { class: "tiny", text: item.meta }),
      ),
      el("span", { class: "crs-eval-score", text: item.score }),
      el("span", { class: `crs-eval-check${item.done ? " done" : ""}`, text: item.done ? "✓" : "" }),
    ))) : quiet("해당하는 항목이 없습니다", "다른 종류를 선택하거나 검색어를 지워 보세요.", "grades"),
  ));
  return root;
}

function postCard(row, href, expand, editHref) {
  const body = el("div", {},
    el("h2", { text: row.title }),
    el("p", { text: expand ? (row.body || "") : excerpt(row.body || row.reply || "") }),
    el("div", { class: "crs-post-meta" },
      row.author ? el("span", { text: row.author }) : null,
      el("span", { text: row.date || row.at || "" }),
      row.reply ? el("span", { class: "crs-kind", text: "답변" }) : null,
    ),
  );
  const actions = editHref ? el("div", { class: "crs-post-actions" },
    go(editHref, "수정", { class: "btn btn-secondary" }),
    btn("삭제", { "data-act": "delete-post", "data-id": row.id }, "ghost"),
  ) : null;
  const main = href ? el("a", { class: "crs-post-link", href, "data-go": href }, body) : body;
  return el("article", { class: "crs-post" }, main, actions);
}

function renderBoard(screen, ctx) {
  if (screen.board === "course" && backendStatus() !== "off") {
    return remoteNoticeBoard(screen, ctx, roomCourse(screen, ctx), { head, searchBox, scope: courseScope("course") });
  }
  // Backend mode: Q&A and open board come from the server (areas/course/boards-server.js).
  if (screen.board === "course-qna" && backendStatus() !== "off") return remoteQnaBoard(screen, ctx, roomCourse(screen, ctx), { head, searchBox });
  if (screen.board === "open" && backendStatus() !== "off") return remoteOpenBoard(screen, ctx, roomCourse(screen, ctx), { head, searchBox });
  const titles = { course: "공지사항", "course-qna": "질의응답", open: "열린게시판" };
  const icons = { course: "info", "course-qna": "chat", open: "layers" };
  const title = titles[screen.board] || screen.title;
  const all = courseRows(screen, ctx.state);
  const q = queryOf(ctx, screen.board);
  const rows = all.filter((row) => matches(q, row.title, row.body, row.author));
  const canWrite = screen.board === "course-qna" || screen.board === "open";
  const writeId = screen.board === "course-qna" ? "45b_room_qna_insert_pop" : "47b_room_open_board_insert_pop";
  const root = el("section", { class: "crs" },
    head(title, icons[screen.board] || "info", all.length, canWrite ? go(hrefFor(find(writeId)), screen.board === "open" ? "글쓰기" : "질문 작성", { class: "btn btn-primary" }) : null),
    el("div", { class: "crs-toolbar" },
      tabs([{ label: `전체 ${all.length}`, on: true }]),
      searchBox(`${title} 검색`, ctx, screen.board),
    ),
  );
  if (!all.length) {
    const copy = screen.board === "open"
      ? ["조회된 자료가 없습니다.", "첫 글을 남기면 이 목록에 표시됩니다."]
      : ["게시글이 없습니다.", canWrite ? "첫 글을 남기면 이 목록에 표시됩니다." : "등록된 자료가 없습니다."];
    root.append(quiet(copy[0], copy[1], "board"));
    return root;
  }
  if (!rows.length) {
    root.append(quiet("검색 결과가 없습니다", "다른 단어로 찾아 보세요.", "board"));
    return root;
  }
  const list = el("div", { class: "crs-posts" });
  for (const row of rows) {
    let href = "";
    let editHref = "";
    if (screen.board === "course") href = hrefFor(find("44d_room_notice_view_pop"), { id: row.id });
    else if (screen.board === "course-qna") {
      href = hrefFor(find("45d_room_qna_view_pop"), { id: row.id });
      editHref = hrefFor(find("45b_room_qna_insert_pop"), { id: row.id });
    } else if (screen.board === "open") editHref = hrefFor(find("47b_room_open_board_insert_pop"), { id: row.id });
    list.append(postCard(row, href, screen.expand, editHref));
  }
  root.append(list);
  return root;
}

function fileKind(name) {
  const ext = String(name).split(".").pop().toLowerCase();
  if (ext === "pdf") return "자료";
  if (ext === "ppt" || ext === "pptx") return "영상";
  return "파일";
}

function renderFiles(screen, ctx) {
  const linked = roomCourse(screen, ctx);
  // 백엔드 모드: 연결된 과목의 파일은 서버 자료의 첨부다(areas/course/activities-server.js).
  if (backendStatus() !== "off") {
    return serverOwnsActivities(linked)
      ? remoteFiles(ctx, linked, { head, searchBox, query: queryOf(ctx, "files") })
      : el("section", { class: "crs" }, head("파일모음", "file"), absentState("파일"));
  }
  const courseId = ctx.state.courseId;
  const course = courseById(courseId);
  const q = queryOf(ctx, "files");
  const all = files.filter((file) => file.courseId === courseId);
  const rows = all.filter((file) => matches(q, file.name, fileKind(file.name)));
  const root = el("section", { class: "crs" },
    head("파일모음", "file", all.length),
    el("div", { class: "crs-toolbar" },
      el("p", { class: "crs-note", text: "주차별로 모은 강의 파일입니다." }),
      searchBox("파일 검색", ctx, "files"),
    ),
  );
  if (!all.length) {
    root.append(quiet("파일이 없습니다", "강의 자료가 올라오면 주차별로 모입니다.", "files"));
    return root;
  }
  if (!rows.length) {
    root.append(quiet("검색 결과가 없습니다", "파일 이름의 일부만 입력해 보세요.", "files"));
    return root;
  }
  const weeks = [...new Set(rows.map((file) => file.week))].sort((a, b) => a - b);
  for (const week of weeks) {
    const block = el("section", { class: "crs-week open" },
      el("h2", { class: "crs-rail-week", text: `${week}주  ${weekRange(week)}` }),
    );
    for (const file of rows.filter((item) => item.week === week)) {
      const href = hrefFor(find("65_file_preview"), { n: file.id, kj: course.id });
      block.append(el("article", { class: "crs-file" },
        el("span", { class: "crs-kind", text: fileKind(file.name) }),
        el("div", { class: "crs-file-main" },
          el("h3", { text: file.name }),
          el("p", { class: "tiny", text: `${file.size}${file.at ? ` · ${file.at}` : ""}` }),
        ),
        el("div", { class: "crs-file-actions" },
          el("a", { class: "icon-btn", href, "data-go": href, "aria-label": `${file.name} 미리보기` }, icon("search")),
          el("button", { type: "button", class: "icon-btn", "data-act": "file-sample", "data-file": file.name, "aria-label": `${file.name} 내려받기` }, icon("file")),
        ),
      ));
    }
    root.append(block);
  }
  return root;
}

export function render(screen, ctx) {
  if (ctx.state.draft && ctx.state.draft.loading === screen.kind) {
    return mark("course", el("section", { class: "crs" }, loadingState("불러오는 중")));
  }
  // 백엔드 모드: 서버 그룹(areas/course/groups-server.js).
  if (screen.kind === "groups" && backendStatus() !== "off") return mark("course", remoteGroups(roomCourse(screen, ctx), head));
  const view = (() => {
    switch (screen.kind) {
      case "room": return renderRoom(screen, ctx);
      case "activity-list": return renderActivityList(screen, ctx);
      case "groups": return renderGroups(ctx);
      case "grades": return renderGrades(screen, ctx);
      case "board": return renderBoard(screen, ctx);
      case "files": return renderFiles(screen, ctx);
      default: return emptyState("이 화면은 강의실 영역이 아닙니다", screen.id);
    }
  })();
  return mark("course", view);
}
