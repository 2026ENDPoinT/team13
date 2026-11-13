// 강의실 게시판 목록 조각. 학생 질의응답(course/boards-server)과 교수 `pf_qna`(professor/professor)가
// 질의응답 목록(`qnaSection`)을 함께 쓰고, 열린게시판도 글 카드(`postCard`)와 상태 표시를 같이 쓴다.
// 목록은 서버 데이터만 그린다. 서버 문자열은 텍스트로만 넣는다.
import { useEffect, useState, type ReactNode } from "react";
import { getState, render, save } from "../store.ts";
import { GoButton, Link } from "../router.tsx";
import { Button, EmptyState, ErrorState, LoadingState, Pagination } from "../ui/kit.tsx";
import { Icon } from "../ui/icon.tsx";
import { hrefFor, find } from "../manifest.ts";
import { readQuery, writeQuery } from "./query.tsx";
import { ApiError } from "./api.ts";
import { backendStatus, courseIdFor } from "./backend.ts";
import { SIGNED_OUT } from "./backend-prompt.tsx";
import { freshVisit, useResource, type Resource } from "./remote-cache.ts";
import { koDate } from "./home-remote.ts";
import { QNA_PAGE, QNA_STATUS, qnaListKey, qnaLists, boardMessage } from "./boards-remote.ts";
import type { BoardPost, Id, QnaFilter } from "./boards-remote.ts";
import type { Course } from "../data.ts";
import type { ScreenCtx } from "../types.ts";

const SEARCH_DELAY = 300;
const STATUS_LABEL: Record<string, string> = { all: "전체", faq: "FAQ", solved: "해결", unsolved: "미해결" };
const COUNT_FIELD: Record<string, string> = { all: "total", faq: "faq", solved: "solved", unsolved: "unsolved" };

function RetryButton({ reload }: { reload: () => void }) {
  const [pressed, setPressed] = useState(false);
  return (
    <Button
      kind="ghost"
      disabled={pressed}
      onClick={() => {
        setPressed(true);
        reload();
      }}
    >
      다시 불러오기
    </Button>
  );
}

export function kindTag(text: string, extra = ""): ReactNode {
  return <span className={`crs-kind${extra}`}>{text}</span>;
}

export function statusTag(post: Pick<BoardPost, "faq" | "solved">): ReactNode {
  if (post.faq) return kindTag("FAQ", " crs-kind-faq");
  return post.solved ? kindTag("해결", " crs-kind-ok") : kindTag("미해결");
}

/** The author line: the name only when the server sent it; otherwise "익명" for anonymous posts. */
export function authorText(post: Pick<BoardPost, "authorName" | "anonymous">): string {
  if (post.authorName) return post.anonymous ? `${post.authorName} (익명)` : post.authorName;
  return post.anonymous ? "익명" : "";
}

export function postCard(post: BoardPost, href: string, qna?: boolean): ReactNode {
  return <PostCard post={post} href={href} qna={Boolean(qna)} />;
}

function PostCard({ post, href, qna }: { post: BoardPost; href: string; qna: boolean }) {
  const author = authorText(post);
  return (
    <article className={`crs-post${post.read ? " is-read" : ""}`}>
      <Link className="crs-post-link" href={href}>
        <div>
          <h2>{post.title}</h2>
          {post.excerpt ? <p>{post.excerpt}</p> : null}
          <div className="crs-post-meta">
            {qna ? statusTag(post) : null}
            {post.secret ? kindTag("비공개") : null}
            {post.mine ? kindTag("내 글") : null}
            {post.read ? null : kindTag("안 읽음", " crs-kind-new")}
            {author ? <span>{author}</span> : null}
            <span>{koDate(post.createdAt)}</span>
            <span>{`조회 ${post.viewCount ?? 0}`}</span>
            <span>{`추천 ${post.likeCount ?? 0}`}</span>
            {post.answerCount != null ? <span>{`답글 ${post.answerCount}`}</span> : null}
            {post.commentCount != null ? <span>{`댓글 ${post.commentCount}`}</span> : null}
          </div>
        </div>
      </Link>
    </article>
  );
}

/** `clear-query`: empties the search of `scope` and redraws. */
function clearQuery(scope: string) {
  writeQuery(getState(), scope || "course:course", "");
  save();
  render();
}

function SearchField({ label, ctx, scope }: { label: string; ctx: ScreenCtx; scope: string }) {
  const value = readQuery(ctx.state, scope);
  return (
    <div className="crs-search">
      <Icon name="search" />
      <input
        type="text"
        value={value}
        onChange={(event) => {
          writeQuery(ctx.state, scope, event.target.value);
          render();
        }}
        placeholder="검색"
        aria-label={label}
        autoComplete="off"
      />
      {value
        ? <button type="button" className="crs-search-clear" aria-label="검색어 지우기" onClick={() => clearQuery(scope)}>×</button>
        : null}
    </div>
  );
}

/** What to show instead of server data (`anon`, unlinked course), or null. */
export function gate(course: Pick<Course, "id">): ReactNode | null {
  if (backendStatus() === "anon") return SIGNED_OUT;
  if (!courseIdFor(course.id)) return <EmptyState title="이 과목은 ASTRA 서버에 없습니다" body="서버에 등록된 과목만 게시판을 볼 수 있습니다." />;
  return null;
}

/**
 * Loads on a new history entry, a new key (search typing waits for a pause), or an empty cache.
 * Call it from an effect that runs after every render.
 */
export function visitList<T>(resource: Resource<T>, scope: string, key: string, load: () => void): void {
  const fresh = freshVisit(scope, key);
  if (fresh === "entry") load();
  else if (!resource.peek(key)) {
    if (fresh === "key") setTimeout(() => { if (!resource.peek(key)) load(); }, SEARCH_DELAY);
    else load();
  }
}

function qnaFilter(ctx: ScreenCtx, scope: string): QnaFilter {
  const params = ctx.params || new URLSearchParams();
  const status = params.get("status");
  return {
    status: status && QNA_STATUS.includes(status) ? status : "all",
    mine: params.get("mine") === "1",
    answered: params.get("answered") === "1",
    q: readQuery(ctx.state, scope).trim().slice(0, 100),
  };
}

function filterParams(f: QnaFilter, page: number): Record<string, string | null> {
  return {
    status: f.status !== "all" ? f.status : null,
    mine: f.mine ? "1" : null,
    answered: f.answered ? "1" : null,
    page: page > 1 ? String(page) : null,
  };
}

/**
 * Filters, search, cards and page links of the course Q&A. `screenId` is the list screen
 * (`45_room_qna_list` for students, `pf_qna` for the instructor); the detail opens over it.
 */
export function qnaSection(ctx: ScreenCtx, course: Course, screenId: string): ReactNode {
  const blocked = gate(course);
  return (
    <div className="crs-remote-board">
      {blocked ?? <QnaBoard ctx={ctx} course={course} courseId={courseIdFor(course.id) ?? ""} screenId={screenId} />}
    </div>
  );
}

function QnaBoard({ ctx, course, courseId, screenId }: { ctx: ScreenCtx; course: Course; courseId: Id; screenId: string }) {
  const scope = "course:course-qna";
  const f = qnaFilter(ctx, scope);
  const page = Math.max(1, Math.floor(Number(ctx.params && ctx.params.get("page"))) || 1);
  const key = qnaListKey(courseId, f, page);
  const load = () => {
    void qnaLists.load(key, courseId, f, page);
  };
  const entry = useResource(qnaLists, key, load);
  useEffect(() => {
    visitList(qnaLists, "course-qna", key, load);
  });
  const listHref = (next: QnaFilter, n = 1) => hrefFor(find(screenId), { kj: course.id, ...filterParams(next, n) });
  const itemHref = (id: number | string) => hrefFor(find(screenId), { kj: course.id, ...filterParams(f, page), pop: "qna", id: String(id) });
  const data = entry && entry.data;
  const counts = data ? data.counts : null;

  let body: ReactNode;
  if (!data) {
    if (!entry || !entry.error) body = <LoadingState title="질문을 불러오는 중입니다" />;
    else if (entry.error instanceof ApiError && entry.error.status === 404) body = <EmptyState title="질의응답을 볼 수 없습니다" body="이 과목의 구성원이 아니거나 과목을 찾을 수 없습니다." />;
    else body = <ErrorState title="질문을 불러오지 못했습니다" body={boardMessage(entry.error, "질문")} actions={<RetryButton reload={load} />} />;
  } else {
    const items = Array.isArray(data.items) ? data.items : [];
    const pages = Math.max(1, Math.ceil(Number(data.total) / (Number(data.size) || QNA_PAGE)));
    const stale = entry.error ? <p className="err" role="alert">{boardMessage(entry.error, "질문")}</p> : null;
    if (!items.length) {
      const filtered = f.q || f.status !== "all" || f.mine || f.answered;
      body = (
        <>
          {stale}
          {page > 1 && Number(data.total) > 0
            ? <EmptyState title="이 쪽에는 질문이 없습니다" actions={<Link href={listHref(f)} className="btn btn-secondary">첫 쪽으로</Link>} />
            : filtered ? <EmptyState title="조건에 맞는 질문이 없습니다" body="다른 상태를 고르거나 검색어를 지워 보세요." />
              : <EmptyState title="조회할 자료가 없습니다" body="첫 질문을 남기면 이 목록에 표시됩니다." />}
        </>
      );
    } else {
      body = (
        <>
          {stale}
          <div className="crs-posts">
            {items.map((post) => <PostCard key={post.postId} post={post} href={itemHref(post.postId)} qna />)}
          </div>
          <Pagination page={page} pages={pages} hrefForPage={(n) => listHref(f, n)} />
        </>
      );
    }
  }

  return (
    <>
      <div className="crs-toolbar">
        <div className="crs-filters">
          <div className="crs-filter-row" role="toolbar" aria-label="질문 상태">
            {QNA_STATUS.map((status) => (
              <GoButton
                key={status}
                href={listHref({ ...f, status })}
                className={`chip${f.status === status ? " on" : ""}`}
                aria-pressed={f.status === status ? "true" : "false"}
              >
                {counts ? `${STATUS_LABEL[status]} ${counts[COUNT_FIELD[status]] ?? 0}` : STATUS_LABEL[status]}
              </GoButton>
            ))}
          </div>
          <div className="crs-filter-row" role="toolbar" aria-label="내 질문과 답변">
            {(["mine", "answered"] as const).map((name) => (
              <GoButton
                key={name}
                href={listHref({ ...f, [name]: !f[name] })}
                className={`chip${f[name] ? " on" : ""}`}
                aria-pressed={f[name] ? "true" : "false"}
              >
                {name === "mine" ? "나의 질문" : "나의 답변"}
              </GoButton>
            ))}
          </div>
        </div>
        <SearchField label="질의응답 제목 검색" ctx={ctx} scope={scope} />
      </div>
      <div className="crs-remote" aria-live="polite">{body}</div>
    </>
  );
}
