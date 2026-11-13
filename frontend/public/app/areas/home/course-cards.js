// 과목 카드. 샘플 홈·과목 목록(home.js)과 서버 홈의 수강 과목(home-server.js)이 함께 쓴다.
import { el, icon } from "../../dom.js";
import { go } from "../../ui/components.js";
import { coverImage } from "../../ui/assets.js";
import { hrefFor, find } from "../../manifest.js";

const FACE = {
  algo: { weeks: [10, 16], join: [20, 30], attend: [18, 24] },
  ai: { weeks: [10, 16], join: [20, 29], attend: [22, 27] },
  bi: { weeks: [10, 16], join: [35, 36], attend: [24, 25] },
  db: { weeks: [10, 16], join: [16, 27], attend: [14, 16] },
  oss: { weeks: [10, 16], join: [9, 14], attend: [8, 9] },
  se: { weeks: [10, 16], join: [6, 9], attend: [6, 9] },
  secu: { weeks: [10, 16], join: [12, 16], attend: [10, 14] },
  ux: { weeks: [0, 15], join: [0, 24], attend: [0, 14] },
  web1: { weeks: [15, 15], join: [41, 41], attend: [14, 14] },
  sec: { weeks: [2, 8], join: [80, 100], attend: [1, 8] },
  lit: { weeks: [0, 8], join: [64, 120], attend: [0, 8] },
};

function faceOf(course) {
  if (FACE[course.id]) return FACE[course.id];
  const total = 14;
  const done = Math.round(((course.progress || 0) / 100) * total);
  return { weeks: [done, total], join: [course.learners, course.learners], attend: [done, total] };
}

function scheduleLabel(time) {
  const match = String(time).match(/^(\S+)\s+(\d{1,2}):\d{2}-(\d{1,2})/);
  if (!match) return time;
  return `${match[1]}(${Number(match[2])} - ${Number(match[3])})`;
}

export function termLabel(term) {
  if (term === "2026-1") return "2026년 1학기";
  if (term === "2026-2") return "2026년 2학기";
  return term || "";
}

function courseHref(course, mode) {
  const openDetail = mode === "catalog" || !course.enrolled;
  if (openDetail) {
    return hrefFor(find(course.kind === "special" ? "24_nonregular_view" : "25_regular_view"), { id: course.id });
  }
  return hrefFor(find(course.kind === "special" ? "70_nr_room_submain" : "40_room_submain"), { kj: course.id });
}

function courseStudyEntry(course) {
  return go(hrefFor(find("study_home"), { kj: course.id }), "학습실 입장", {
    class: "btn btn-primary course-study-entry",
    "aria-label": `${course.cardTitle || course.ko} 학습실 입장`,
  });
}

const count = (value) => (Number.isInteger(value) && value >= 0 ? value : null);

/**
 * 진행 주·진도·출석 of a server course from its `GET /api/me/home` course record (`currentWeek`, `weekCount`,
 * `progressPercent`, `attendance{attended,total}`). Values the server does not send are left out, never filled in.
 */
function serverFace(stats) {
  const body = [];
  const week = count(stats && stats.currentWeek);
  const weeks = count(stats && stats.weekCount);
  if (week != null && weeks) {
    body.push(
      el("div", { class: "progress-line" }, el("span", { text: "진행 주" }), el("span", { text: `${Math.min(week, weeks)} / ${weeks} 주` })),
      el("div", { class: "bar" }, el("i", { style: `width:${Math.round((Math.min(week, weeks) / weeks) * 100)}%` })),
    );
  }
  const pills = [];
  const progress = count(stats && stats.progressPercent);
  if (progress != null) pills.push(el("span", { class: "act-pill", text: `진도 ${Math.min(progress, 100)}%` }));
  const attendance = stats && stats.attendance;
  const attended = count(attendance && attendance.attended);
  const total = count(attendance && attendance.total);
  if (attended != null && total != null) pills.push(el("span", { class: "act-pill", text: `출석 ${attended} / ${total}` }));
  if (pills.length) body.push(el("div", { class: "act-pills" }, ...pills));
  return body;
}

/**
 * `options.unread`: Map of course id -> unread server notices; `options.stats`: Map of course id -> its `/api/me/home`
 * course record (backend mode only). Server courses (`course.server`) show only server facts.
 */
export function courseCards(list, mode = "home", options = {}) {
  return el("div", { class: "cards" }, ...list.map((course) => {
    const href = courseHref(course, mode);
    const special = course.server && course.kind === "special";
    const body = [el("div", { class: "facts" },
      course.learningPeriod ? el("span", {}, icon("calendar"), el("span", { text: course.learningPeriod })) : null,
      course.time || (!special && !course.learningPeriod)
        ? el("span", {}, icon("calendar"), el("span", { text: scheduleLabel(course.time) || "시간 미정" }))
        : null,
      course.lectureType ? el("span", { text: course.lectureType }) : null,
      course.credit ? el("span", {}, icon("star"), el("span", { text: `${course.credit}학점` })) : null,
    )];
    if (mode === "catalog") body.push(el("div", { class: "tiny", text: termLabel(course.term) }));
    if (mode === "home" && (course.server || course.missing)) body.push(...serverFace(options.stats && options.stats.get(course.id)));
    else if (mode === "home") {
      const face = faceOf(course);
      const [done, total] = face.weeks;
      const width = total ? Math.max(0, Math.min(100, Math.round((done / total) * 100))) : 0;
      body.push(
        el("div", { class: "progress-line" }, el("span", { text: "진행 주" }), el("span", { text: `${done} / ${total} 주` })),
        el("div", { class: "bar" }, el("i", { style: `width:${width}%` })),
        el("div", { class: "act-pills" },
          el("span", { class: "act-pill", text: `참여 ${face.join[0]} / ${face.join[1]}` }),
          el("span", { class: "act-pill", text: `출석 ${face.attend[0]} / ${face.attend[1]}` }),
        ),
      );
    }
    if (mode === "enrolled") body.push(el("span", { class: "act-pill room", text: "강의실" }));
    const unread = options.unread ? options.unread.get(course.id) || 0 : 0;
    if (unread) body.push(el("span", { class: "act-pill unread", text: `안 읽은 공지 ${unread}` }));
    return el("article", { class: "course-card" },
      el("a", { class: "course-card-main", href, "data-go": href },
        el("div", { class: `cover ${course.gradient}` },
          coverImage(course),
          el("b", { text: course.cardTitle || course.ko }),
          el("span", { class: "code", text: course.code }),
          el("span", { class: "instructor", text: course.instructor }),
        ),
        el("div", { class: "card-body" }, ...body),
      ),
      courseStudyEntry(course),
    );
  }));
}

export function specialCard(course) {
  const href = courseHref(course, course.enrolled ? "enrolled" : "catalog");
  const status = course.enrolled ? "수강 중" : course.open ? "모집 중" : "예정";
  const tone = course.enrolled ? "on" : course.open ? "open" : "";
  return el("article", { class: "course-card special-card" },
    el("a", { class: "course-card-main", href, "data-go": href },
      el("div", { class: `cover ${course.gradient}` },
        coverImage(course),
        el("b", { text: course.cardTitle || course.ko }),
        el("span", { class: "code", text: course.code }),
        el("span", { class: "who", text: course.instructor }),
      ),
      el("div", { class: "card-body" },
        el("div", { class: "facts" },
          el("span", { text: "2026.09.01 – 2026.12.19" }),
          el("span", { class: `status ${tone}`, text: status }),
        ),
        el("div", { class: "tiny", text: `신청 ${course.learners} / ${course.capacity || 100}` }),
        el("span", { class: "act-pill room", text: course.enrolled ? "강의실" : "신청하기" }),
      ),
    ),
    courseStudyEntry(course),
  );
}
