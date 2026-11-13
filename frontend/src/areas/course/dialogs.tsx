import type { ReactNode } from "react";
import { courseById, courses, activitiesOf, professor, weekCount } from "../../data.ts";
import type { Course } from "../../data.ts";
import type { Screen, ScreenCtx } from "../../types.ts";
import { getState, save, render } from "../../store.ts";
import { GoButton, Link, closeDialog } from "../../router.tsx";
import { readForm, showErrors, stamp } from "../../forms.ts";
import { Button, Dialog, EmptyState, Field, Table } from "../../ui/kit.tsx";
import { Icon } from "../../ui/icon.tsx";
import type { BoardRow } from "../../shared/format.ts";
import { readQuery, writeQuery } from "../../shared/query.tsx";
import { routeFor } from "../../shared/routes.ts";
import { backendStatus, courseIdFor } from "../../shared/backend.ts";
import { serverCourseViews } from "../../shared/course-view.ts";
import { courseRows, deletePost, updatePost } from "./course.tsx";
import { remoteNoticeDialog } from "./notices-server.tsx";
import { remoteQnaDialog, remoteQnaWriteDialog, remoteOpenWriteDialog, remoteOpenPostDialog } from "./boards-server.tsx";
import { remoteSyllabusDialog } from "./syllabus-server.tsx";

function termLabel(term: string | undefined): string {
  // 서버 과목의 학기는 서버의 학기 이름 그대로다(shared/course-view.js).
  if (term && !/^\d{4}-\d+$/.test(term)) return term;
  const [year, sem] = String(term || "2026-2").split("-");
  return `${year}학년도 ${sem}학기`;
}

function weekRange(n: number): string {
  const start = new Date(2026, 7, 31 + (n - 1) * 7);
  const end = new Date(start);
  end.setDate(start.getDate() + 6);
  const fmt = (d: Date) => `${d.getMonth() + 1}월 ${d.getDate()}일`;
  return `${fmt(start)} – ${fmt(end)}`;
}

function meta(pairs: [string, string | undefined][], layout?: string): ReactNode {
  return (
    <dl className={layout ? `crs-meta ${layout}` : "crs-meta"}>
      {pairs.flatMap(([label, value]) => [<dt key={`${label}-t`}>{label}</dt>, <dd key={`${label}-d`}>{value || "—"}</dd>])}
    </dl>
  );
}

function courseDialog(title: string, body: ReactNode, wide = false): ReactNode {
  return <Dialog title={title} wide={wide} className="crs-modal">{body}</Dialog>;
}

/** A screen that only names a sample board (and its course), for `courseRows`. */
function boardScreen(board: string, course?: string): Screen {
  return { id: "", role: "student", title: "", path: "", nav: "", kind: "board", board, course };
}

/** `save-post`: adds a sample post to `board`. */
function savePost(board: string) {
  const state = getState();
  const data = readForm();
  const errors: Record<string, string> = {};
  if (!String(data.title || "").trim()) errors.title = "제목을 입력하세요";
  if (!String(data.body || "").trim() || String(data.body).trim().length < 4) errors.body = "내용을 4자 이상 입력하세요";
  if (Object.keys(errors).length) {
    showErrors(errors);
    return;
  }
  const post: BoardRow = { id: `p-${Date.now()}`, title: String(data.title).trim(), body: String(data.body).trim(), author: "박민준", at: stamp(), board, reply: "" };
  if (board === "community") state.communityQna.unshift(post);
  else state.posts.unshift(post);
  state.toast = "등록했습니다";
  save();
  closeDialog();
}

/** `save-reply`: stores the answer of a sample question. */
function saveReply(id: string) {
  const state = getState();
  const data = readForm();
  const reply = String(data.reply || "").trim();
  if (!reply) {
    showErrors({ reply: "답글을 입력하세요" });
    return;
  }
  // AppState.posts is untyped; saved sample posts carry BoardRow fields.
  const post = (state.posts as BoardRow[]).find((p) => p.id === id);
  if (post) post.reply = reply;
  else state.draft.replies = { ...(state.draft.replies || {}), [id]: reply };
  state.toast = "답글을 저장했습니다";
  save();
  closeDialog();
}

function compose(title: string, board: string, err: Record<string, string>, existing: BoardRow | null | undefined): ReactNode {
  const editing = Boolean(existing);
  const heading = editing ? title.replace("작성", "수정") : title;
  return courseDialog(heading === title && editing ? `${title} 수정` : heading, (
    <div className="crs-dialog" key={existing ? existing.id : "new"}>
      <Field label="제목" error={err.title}><input data-name="title" defaultValue={existing ? existing.title : ""} placeholder="제목을 입력하세요" /></Field>
      <Field label="내용" error={err.body}><textarea data-name="body" placeholder="내용을 4자 이상 입력하세요" defaultValue={existing ? existing.body : ""} /></Field>
      <div className="drop">파일 첨부와 글 서식은 이 샘플에 저장되지 않습니다.</div>
      <div className="crs-form-actions">
        <Button kind="primary" onClick={() => (existing ? updatePost(existing.id) : savePost(board))}>저장</Button>
      </div>
    </div>
  ));
}

function syllabus(ctx: ScreenCtx): ReactNode {
  const course = courseById(ctx.state.courseId);
  const special = course.kind === "special";
  const weeks: string[][] = [];
  for (let n = 1; n <= weekCount; n += 1) {
    const titles = activitiesOf(course.id).filter((item) => item.week === n).map((item) => item.title);
    if (!titles.length) continue;
    weeks.push([special ? `${n}회차` : `${n}주`, weekRange(n), titles.join(" · ")]);
  }
  const overview = special
    ? "비교과 과정은 영상과 짧은 확인 활동으로 이루어집니다. 진도 기준과 설문은 강의실에 적힌 일정을 따릅니다. 이 문장은 샘플이며 학교 안내문 원문을 옮기지 않았습니다."
    : "경영 의사결정에 필요한 데이터를 모으고, 정리하고, 읽는 수업입니다. 도구 화면을 외우기보다, 어떤 질문을 데이터로 바꿀 수 있는지 구분하는 연습을 합니다. 이 문장은 샘플이며 학교 강의계획서 원문을 옮기지 않았습니다.";
  return courseDialog("강의계획서", (
    <div className="crs-dialog">
      <div className="crs-prof">
        <span className="crs-avatar">{course.instructor.slice(0, 1)}</span>
        <div>
          <strong>{course.instructor}</strong>
          <span className="tiny">{`${professor.dept} · 담당 교수`}</span>
        </div>
      </div>
      <h3>강의 계획</h3>
      {meta([
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
      ])}
      <h3>과목 개요</h3>
      <p className="crs-prose">{overview}</p>
      <h3>수업 목표</h3>
      <ul className="crs-goals">
        <li>문제와 데이터 범위를 한 문단으로 적는다.</li>
        <li>정리 전과 후의 차이를 표로 설명한다.</li>
        <li>결과의 한계를 보고 문장에 남긴다.</li>
      </ul>
      <h3>주차별 계획</h3>
      {weeks.length ? <Table headers={["주차", "기간", "활동"]} rows={weeks} /> : <p className="tiny">등록된 주차가 없습니다.</p>}
    </div>
  ), true);
}

function switcher(ctx: ScreenCtx): ReactNode {
  const q = readQuery(ctx.state, "course:switch").trim().toLowerCase();
  const current = ctx.state.courseId;
  const professorMode = ctx.state.role === "professor";
  // 백엔드 `ready`: 서버의 내 과목(교수 화면은 담당, 학생 화면은 수강)만, 서버 사실로 보인다.
  const ready = backendStatus() === "ready";
  const mine: Course[] = ready
    ? serverCourseViews([professorMode ? "INSTRUCTOR" : "STUDENT"]).map(({ course }) => course).filter((course): course is Course => Boolean(course))
    : professorMode
      ? courses.filter((course) => ["bi", "db", "sec"].includes(course.id))
      : courses.filter((course) => course.enrolled);
  const isCurrent = (course: Course) => course.id === current || (ready && courseIdFor(current) != null && courseIdFor(current) === course.courseId);
  const hit = (course: Course) => !q || [course.ko, course.en, course.code, course.instructor].filter(Boolean).join(" ").toLowerCase().includes(q);
  const groups: [string, Course[]][] = [
    ["정규과목", mine.filter((course) => course.kind !== "special" && hit(course))],
    ["비정규과목", mine.filter((course) => course.kind === "special" && hit(course))],
  ];
  const count = groups.reduce((sum, [, list]) => sum + list.length, 0);
  return courseDialog("과목 변경", (
    <div className="crs-dialog">
      <div className="crs-search">
        <Icon name="search" />
        <input
          type="text"
          data-name="surfaceQuery"
          data-scope="course:switch"
          data-focus="course:switch"
          value={readQuery(ctx.state, "course:switch")}
          onChange={(event) => {
            writeQuery(getState(), "course:switch", event.target.value);
            render();
          }}
          placeholder="과목, 코드"
          aria-label="과목 검색"
          autoComplete="off"
        />
      </div>
      <p className="tiny">{termLabel(courseById(current).term)}</p>
      {groups.filter(([, list]) => list.length).map(([label, list]) => [
        <h3 key={label}>{label}</h3>,
        ...list.map((course) => {
          const href = routeFor(professorMode ? "professor" : "student", "classroom", { kj: course.id, special: course.kind === "special" });
          return (
            <GoButton key={course.id} href={href} className={`crs-pick${isCurrent(course) ? " on" : ""}`}>
              <span className={`crs-pick-bar ${course.gradient || "g3"}`} aria-hidden="true" />
              <span className="crs-pick-main">
                <b>{course.code ? `${course.ko} (${course.code})` : course.ko}</b>
                <span className="tiny">{[course.time, course.room].filter(Boolean).join(" · ") || "시간 미정"}</span>
              </span>
              {isCurrent(course) ? <span className="crs-kind">현재</span> : null}
            </GoButton>
          );
        }),
      ])}
      {!count ? <EmptyState title="해당하는 과목이 없습니다" body="이름이나 코드의 일부를 입력해 보세요." /> : null}
    </div>
  ));
}

function qnaDialog(ctx: ScreenCtx, name: string, err: Record<string, string>, id: string | null): ReactNode {
  const draft = ctx.state.draft || {};
  const rows = courseRows(boardScreen("course-qna"), ctx.state);
  const row = rows.find((item) => item.id === id) || rows[0];
  if (!row) {
    return courseDialog("질의응답", <div className="crs-dialog"><EmptyState title="질문을 찾을 수 없습니다" body="목록으로 돌아가 다시 선택해 주세요." /></div>);
  }
  const reply: string | undefined = row.reply || (draft.replies && draft.replies[row.id]);
  const editHref = `/ilos/cls/st/qna/qna_list_form.acl?pop=qna-write&id=${row.id}&kj=${ctx.state.courseId}`;
  const replyHref = `/ilos/cls/st/qna/qna_list_form.acl?pop=qna-reply&id=${row.id}&kj=${ctx.state.courseId}`;
  return courseDialog("질의응답", (
    <div className="crs-dialog">
      <div className="crs-prof">
        <span className="crs-avatar">{(row.author || "질").slice(0, 1)}</span>
        <div>
          <strong>{row.author || "학생"}</strong>
          <span className="tiny">{row.at || ""}</span>
        </div>
      </div>
      <h3 className="crs-dialog-title">{row.title}</h3>
      <div className="crs-prose"><p>{row.body || ""}</p></div>
      {reply ? <div className="sheet crs-reply"><b>답변</b><p>{reply}</p></div> : <p className="tiny">아직 답변이 없습니다.</p>}
      {name === "qna-reply" ? (
        <>
          <Field label="답글" error={err.reply}><textarea data-name="reply" placeholder="답글을 입력하세요" /></Field>
          <div className="crs-form-actions"><Button kind="primary" onClick={() => saveReply(row.id)}>답글 저장</Button></div>
        </>
      ) : (
        <div className="crs-form-actions">
          <Link className="btn btn-secondary" href={editHref}>수정</Link>
          <Button kind="ghost" onClick={() => deletePost(row.id, true)}>삭제</Button>
          <Link className="btn btn-secondary" href={replyHref}>답글 쓰기</Link>
        </div>
      )}
    </div>
  ));
}

export function renderDialog(name: string, ctx: ScreenCtx): ReactNode {
  const err = ctx.state.formError || {};
  const id = ctx.params && ctx.params.get("id");
  if (name === "notice") {
    if (ctx.screen.board !== "course") return null;
    if (backendStatus() !== "off") return remoteNoticeDialog(ctx, courseById(ctx.state.courseId));
    const rows = courseRows(boardScreen("course", ctx.state.courseId), ctx.state);
    const row = rows.find((item) => item.id === id) || rows[0];
    if (!row) return null;
    return courseDialog("공지사항", (
      <div className="crs-dialog">
        <h3 className="crs-dialog-title">{row.title}</h3>
        {meta([
          ["작성자", row.author || "담당 교수"],
          ["게시일", row.date || ""],
          ["공지 기한", "상시"],
        ], "pairs")}
        <div className="crs-prose"><p>{row.body || ""}</p></div>
        <div className="crs-form-actions"><Button kind="primary" onClick={closeDialog}>공지를 확인했습니다</Button></div>
      </div>
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
  if (name === "qna" || name === "qna-reply") return qnaDialog(ctx, name, err, id);
  if (name === "qna-write" && ctx.screen.board === "course-qna") {
    const existing = id ? courseRows(boardScreen("course-qna"), ctx.state).find((row) => row.id === id) : null;
    return compose("질문 작성", "course-qna", err, existing);
  }
  if (name === "board-write") {
    const existing = id ? courseRows(boardScreen("open"), ctx.state).find((row) => row.id === id) : null;
    return compose("열린게시판", "open", err, existing);
  }
  return null;
}
