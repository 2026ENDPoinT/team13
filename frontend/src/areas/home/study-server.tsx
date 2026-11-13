// 학습실의 백엔드 화면. `study_summary?doc=srv-<fileId>&kj=`는 서버 자료의 게시된 쪽별 요약과 파일
// 요약, 테크뇽 학습 대화로 들어가는 버튼을 보이고, `study_lecture?kj=&lecture=srv-<activityId>`는
// 영상 강의의 콘텐츠와 본인 진도, 플레이어 링크, 함께 볼 서버 강의자료를 보인다. 연결된 과목에서는
// 샘플 요약을 실제 자료처럼 보이지 않는다. `off`이면 null을 돌려주어 샘플 요약과 녹음 화면이 그대로
// 그려진다. 응답은 메모리 캐시(remote-cache.ts)에만 두고 세션이 바뀌면 버린다.
import { useEffect, useRef, useState, type ReactNode } from "react";
import { courseById, studyMaterials, type Course } from "../../data.ts";
import { hrefFor, find } from "../../manifest.ts";
import type { ScreenCtx } from "../../types.ts";
import { getState, save, render } from "../../store.ts";
import { Link } from "../../router.tsx";
import { Icon } from "../../ui/icon.tsx";
import { Badge, Button, EmptyState, LoadingState, ErrorState } from "../../ui/kit.tsx";
import { ApiError } from "../../shared/api.ts";
import { backendStatus, backendSettled, connect as connectBackend, courseIdFor } from "../../shared/backend.ts";
import { backendPanel } from "../../shared/backend-prompt.tsx";
import { createResource, freshVisit, useResource, type Entry } from "../../shared/remote-cache.ts";
import { listCoursePdfs, type CoursePdfRecord } from "../../shared/course-pdfs.ts";
import { getPublishedSummary, summaryMessage, type ServerSummary } from "../../shared/course-pdfs-remote.ts";
import { fileOverview, studyMessage, type FileOverview } from "../../shared/study-chat-remote.ts";
import { summaryView, markdown } from "../../shared/page-summary.tsx";
import {
  lectureDetails, lectureProgress, detailKey, courseKey, progressOf, lectureIdOf, lectureMessage, studentLectureHref,
  lecturePlayerHref, VIDEO_STATE, PROGRESS_STATE, durationText, windowText,
  type ActivityDetail, type ItemProgress, type LectureItem, type ProgressWeek,
} from "../../shared/lectures-remote.ts";
import { wallFormat } from "../../shared/home-remote.ts";

type Id = number | string;

const LOGIN_SUMMARY = "ASTRA 서버에 로그인하면 이 과목의 자료 요약을 볼 수 있습니다.";
const LOGIN_LECTURE = "ASTRA 서버에 로그인하면 이 과목의 영상 강의와 진도를 볼 수 있습니다.";

/** A published summary as the server sends it: the page-image base comes with it. */
interface PublishedSummary extends ServerSummary {
  pageImageBase?: string | null;
}

/** 서버 강의자료 PDF 레코드 목록(`course-pdfs.ts listCoursePdfs`). 키는 샘플 과목 ID다. */
const pdfLists = createResource<CoursePdfRecord[], [kj: string]>((kj) => listCoursePdfs(kj));
/** 게시된 쪽별 요약. 게시본이 없으면 null. 키는 `${backendCourseId}:${fileId}`. */
const summaries = createResource<PublishedSummary | null, [courseId: Id, fileId: Id]>((courseId, fileId) => getPublishedSummary({ backendCourseId: courseId, fileId }));
/** 파일 요약(`…/summary/overview`). 게시본이 없으면 null. 키는 summaries와 같다. */
const overviews = createResource<FileOverview | null, [courseId: Id, fileId: Id]>((courseId, fileId) => fileOverview(courseId, fileId));

const studyHomeHref = () => hrefFor(find("study_home"));
/** `kj`의 과목: 서버 과목 보기를 먼저, 없으면 샘플 과목. 어느 쪽에도 없으면 undefined. */
function courseOf(kj: string): Course | undefined {
  const course = courseById(kj);
  return course.missing || course.id !== kj ? undefined : course;
}
const folderHref = (kj: string) => hrefFor(find("study_home"), { kj });
const documentHref = (kj: string, id: string) => hrefFor(find("study_home"), { kj, doc: id });

/** 서버 자료의 요약 화면. `doc` 이름은 `shared/study-chat-panel.tsx`가 테크뇽 패널에서 읽는다. */
export const studySummaryHref = (kj: string, id: string): string => hrefFor(find("study_summary"), { doc: id, kj });

/** 학습실에서 서버 영상 강의 하나를 보는 화면. */
export const studyLectureHref = (kj: string, activityId: Id): string => hrefFor(find("study_lecture"), { kj, lecture: `srv-${activityId}` });

const fileIdOf = (ref: string | null) => {
  const match = /^srv-([1-9][0-9]{0,17})$/.exec(String(ref || ""));
  return match ? match[1] : null;
};

const missing = (error: unknown) => error instanceof ApiError && error.status === 404;

/** 서버가 보낸 detail이 있으면 그 문장, 없으면 기능별 문구다. */
function detailOf(error: unknown, fallback: (error: unknown) => string): string {
  return error instanceof ApiError && error.detail ? error.detail : fallback(error);
}

const courseName = (course: Course) => course.cardTitle || course.ko;

function Crumbs({ course }: { course: Course }) {
  return <p className="term"><Link href={studyHomeHref()}>학습실</Link>{" · "}<Link href={folderHref(course.id)}>{courseName(course)}</Link></p>;
}

function FolderLink({ kj }: { kj: string }) {
  return <Link href={folderHref(kj)} className="btn btn-secondary">과목 폴더로</Link>;
}

function Retry({ load }: { load: () => Promise<void> }) {
  const [busy, setBusy] = useState(false);
  return (
    <Button kind="ghost" disabled={busy} onClick={() => { setBusy(true); void load().finally(() => setBusy(false)); }}>다시 불러오기</Button>
  );
}

/** 첫 연결 확인 전의 서버 주소: 확인이 끝나 `off`이면 목업 화면으로 바꾼다. 다른 상태는 앱 전체가 다시 그린다. */
function Waiting({ fallback }: { fallback: () => ReactNode }) {
  const [off, setOff] = useState(false);
  useEffect(() => {
    let live = true;
    void connectBackend().then((next) => {
      if (live && next.status === "off") setOff(true);
    });
    return () => {
      live = false;
    };
  }, []);
  if (off) return fallback();
  return <section className="study" aria-busy="true" data-area="home"><LoadingState title="ASTRA 서버에 연결하는 중입니다" /></section>;
}

// ---------- study_summary ----------

/**
 * `study_summary`의 백엔드 화면, 또는 샘플 요약을 그대로 둘 때 null. `fallback()`은 샘플 요약이며
 * 연결 확인이 `off`로 끝났을 때만 부른다.
 */
export function serverStudySummary(ctx: ScreenCtx, fallback: () => ReactNode): ReactNode {
  const params = ctx.params || new URLSearchParams();
  const fileId = fileIdOf(params.get("doc"));
  const status = backendStatus();
  if (status === "off") return fileId && !backendSettled() ? <Waiting fallback={fallback} /> : null;
  if (!fileId) {
    const item = studyMaterials.find((row) => row.id === params.get("m")) || studyMaterials[0];
    const course = courseOf(item.courseId);
    if (status === "anon") return <SummaryShell course={course}>{backendPanel({ text: LOGIN_SUMMARY })}</SummaryShell>;
    if (!courseIdFor(item.courseId)) return null;
    // 연결된 과목의 샘플 ID: 샘플 문장을 실제 자료처럼 보이지 않는다.
    return (
      <SummaryShell course={course}>
        <EmptyState
          title="자료를 찾을 수 없습니다" body="이 과목의 자료는 ASTRA 서버에 등록된 강의자료만 볼 수 있습니다. 학습실 폴더에서 자료를 다시 골라 주세요."
          actions={course ? <FolderLink kj={course.id} /> : null}
        />
      </SummaryShell>
    );
  }
  const kj = params.get("kj") || "";
  const course = courseOf(kj);
  if (!course) {
    return (
      <section className="study" data-area="home">
        <h1>자료 요약</h1>
        <ErrorState title="과목을 찾을 수 없습니다" body="학습실에서 과목 폴더를 다시 골라 주세요." actions={<Link href={studyHomeHref()} className="btn btn-secondary">학습실로</Link>} />
      </section>
    );
  }
  if (status === "anon") return <SummaryShell course={course}>{backendPanel({ text: LOGIN_SUMMARY })}</SummaryShell>;
  const courseId = courseIdFor(kj);
  if (!courseId) {
    return (
      <SummaryShell course={course}>
        <EmptyState title="이 과목은 ASTRA 서버에 없습니다" body="서버에 등록된 과목의 자료만 요약을 볼 수 있습니다." actions={<FolderLink kj={kj} />} />
      </SummaryShell>
    );
  }
  return <ServerSummaryView key={`${kj}:${fileId}`} open={Boolean(ctx.state && ctx.state.technyonOpen)} course={course} courseId={courseId} fileId={fileId} />;
}

function SummaryShell({ course, children }: { course: Course | undefined; children: ReactNode }) {
  return (
    <section className="study study-summary" data-area="home">
      {course ? <Crumbs course={course} /> : null}
      <h1>자료 요약</h1>
      {children}
    </section>
  );
}

/** `technyon`: 테크뇽 대화 패널을 열고 닫는다. */
function toggleTechnyon() {
  const state = getState();
  state.technyonOpen = !state.technyonOpen;
  save();
  render();
}

function ServerSummaryView({ open, course, courseId, fileId }: { open: boolean; course: Course; courseId: Id; fileId: string }) {
  const kj = course.id;
  const docId = `srv-${fileId}`;
  const key = `${courseId}:${fileId}`;
  const loadList = () => pdfLists.load(kj, kj);
  const loadSummary = () => summaries.load(key, courseId, fileId);
  const loadOverview = () => overviews.load(key, courseId, fileId);

  const list = useResource(pdfLists, kj, () => void loadList());
  const record = list && list.data ? list.data.find((item) => item.id === docId) : undefined;
  // 목록이 게시본이 있다고 할 때만 요약을 읽는다. 없으면 묻지 않고 "게시된 요약이 없습니다"다.
  const hasSummary = Boolean(record && record.remote && record.summaryPublished === true);
  const summaryEntry = useResource(summaries, key, () => { if (hasSummary) void loadSummary(); });
  const overviewEntry = useResource(overviews, key, () => { if (hasSummary) void loadOverview(); });

  // 새 화면 이동이면 게시본도 새로 읽는다. 읽기는 목록의 `summaryPublished`를 본 뒤 한 번 시작한다.
  const wantSummary = useRef(true);
  const freshSummary = useRef(false);
  useEffect(() => {
    if (freshVisit("study-summary", key) === "entry") {
      freshSummary.current = true;
      wantSummary.current = true;
      void loadList();
    } else if (!pdfLists.peek(kj)) void loadList();
  });
  useEffect(() => {
    if (!hasSummary || !wantSummary.current) return;
    wantSummary.current = false;
    if (freshSummary.current || !summaries.peek(key)) void loadSummary();
    if (freshSummary.current || !overviews.peek(key)) void loadOverview();
    freshSummary.current = false;
  });

  let body: ReactNode;
  if (!list || (!list.data && !list.error)) body = <LoadingState title="자료를 불러오는 중입니다" />;
  else if (!list.data) {
    body = missing(list.error)
      ? <NotFound kj={kj} />
      : <ErrorState title="자료를 불러오지 못했습니다" body={detailOf(list.error, () => "ASTRA 서버에서 자료 목록을 받지 못했습니다. 잠시 후 다시 시도해 주세요.")} actions={<Retry load={loadList} />} />;
  } else if (!record) body = <NotFound kj={kj} />;
  else {
    const summary: Entry<PublishedSummary | null> | null = hasSummary ? summaryEntry : { data: null, error: null, loading: null };
    const overview: Entry<FileOverview | null> | null = hasSummary ? overviewEntry : { data: null, error: null, loading: null };
    const published = summary && summary.data;
    body = (
      <>
        <article className="summary-doc" aria-label={`${record.title} 자료 요약`}>
          <div className="sum-h">
            <Icon name="book" />
            <div>
              <strong>{record.title}</strong>
              <span className="tiny">{`${courseName(course)} · ${record.fileName}`}</span>
            </div>
          </div>
          <div className="btn-row study-summary__actions">
            {published ? <Button kind="primary" aria-expanded={open ? "true" : "false"} onClick={toggleTechnyon}>{open ? "테크뇽 대화 닫기" : "테크뇽에게 이 자료 묻기"}</Button> : null}
            <Link href={documentHref(kj, docId)} className="btn btn-secondary">문서 보기</Link>
          </div>
          {published ? <p className="tiny">테크뇽은 교수님이 게시한 이 자료의 요약으로 답합니다. 대화는 ASTRA 서버에 남습니다.</p> : null}
        </article>
        <OverviewSection entry={overview} load={loadOverview} />
        <PageSummarySection entry={summary} load={loadSummary} courseId={courseId} fileId={fileId} />
      </>
    );
  }
  return (
    <SummaryShell course={course}>
      <div className="study-summary__body" aria-live="polite">{body}</div>
    </SummaryShell>
  );
}

function NotFound({ kj }: { kj: string }) {
  return <EmptyState title="자료를 찾을 수 없습니다" body="삭제되었거나 아직 공개되지 않았거나 볼 수 있는 권한이 없습니다." actions={<FolderLink kj={kj} />} />;
}

// 게시본 없음(null)도 답이다. 데이터 없이 아직 받는 중인 항목만 기다린다.
function pending<T>(entry: Entry<T>): boolean {
  return (entry.data == null && !entry.error && Boolean(entry.loading));
}

function OverviewSection({ entry, load }: { entry: Entry<FileOverview | null> | null; load: () => Promise<void> }) {
  let body: ReactNode;
  if (!entry || pending(entry)) body = <p className="tiny">파일 요약을 불러오는 중입니다.</p>;
  else if (entry.error && !entry.data) body = <><p className="err" role="alert">{detailOf(entry.error, studyMessage)}</p><Retry load={load} /></>;
  else if (!entry.data) body = <p className="tiny">게시된 파일 요약이 없습니다.</p>;
  else body = markdown(entry.data.markdown || "");
  return (
    <section className="study-section" aria-label="파일 요약">
      <h2>파일 요약</h2>
      <div className="study-summary__overview">{body}</div>
    </section>
  );
}

const publishedFormat = wallFormat("ko-KR", { year: "numeric", month: "long", day: "numeric", hour: "2-digit", minute: "2-digit" });

function PageSummarySection({ entry, load, courseId, fileId }: { entry: Entry<PublishedSummary | null> | null; load: () => Promise<void>; courseId: Id; fileId: string }) {
  let body: ReactNode;
  if (!entry || pending(entry)) body = <LoadingState title="쪽별 요약을 불러오는 중입니다" />;
  else if (entry.error && !entry.data) body = <ErrorState title="쪽별 요약을 불러오지 못했습니다" body={detailOf(entry.error, summaryMessage)} actions={<Retry load={load} />} />;
  else if (!entry.data) body = <EmptyState title="게시된 요약이 없습니다" body="교수님이 이 자료의 쪽별 요약을 게시하면 여기에서 보고 테크뇽에게 물어볼 수 있습니다." />;
  else {
    const published = entry.data;
    const at = publishedFormat.format(published.publishedAt);
    body = (
      <>
        <p className="tiny">{`${at ? `${at} 게시 · ` : ""}교수님이 검토해 게시한 AI 요약입니다. 원문과 함께 확인해 주세요.`}</p>
        {summaryView(published, {
          pageImageBase: published.pageImageBase,
          refreshImages: async () => {
            const fresh: PublishedSummary | null = await getPublishedSummary({ backendCourseId: courseId, fileId });
            return fresh ? fresh.pageImageBase ?? null : null;
          },
        })}
      </>
    );
  }
  return (
    <section className="study-section" aria-label="쪽별 요약">
      <h2>쪽별 요약</h2>
      <div className="study-summary__pages">{body}</div>
    </section>
  );
}

// ---------- study_lecture ----------

/**
 * `study_lecture?lecture=srv-<activityId>`의 백엔드 화면, 또는 녹음 화면을 그대로 둘 때 null.
 * `fallback()`은 녹음 화면이며 연결 확인이 `off`로 끝났을 때만 부른다.
 */
export function serverStudyLecture(ctx: ScreenCtx, fallback: () => ReactNode): ReactNode {
  const params = ctx.params || new URLSearchParams();
  const activityId = lectureIdOf(params.get("lecture"));
  if (activityId == null) return null;
  const status = backendStatus();
  if (status === "off") return backendSettled() ? null : <Waiting fallback={fallback} />;
  const kj = params.get("kj") || "";
  const course = courseOf(kj);
  if (!course) {
    return (
      <section className="study rec" data-area="home">
        <h1>영상 강의</h1>
        <ErrorState title="과목을 찾을 수 없습니다" body="학습실에서 과목 폴더를 다시 골라 주세요." actions={<Link href={studyHomeHref()} className="btn btn-secondary">학습실로</Link>} />
      </section>
    );
  }
  if (status === "anon") return <LectureShell course={course}>{backendPanel({ text: LOGIN_LECTURE })}</LectureShell>;
  const courseId = courseIdFor(kj);
  if (!courseId) {
    return (
      <LectureShell course={course}>
        <EmptyState title="이 과목은 ASTRA 서버에 없습니다" body="서버에 등록된 과목의 강의만 볼 수 있습니다." actions={<FolderLink kj={kj} />} />
      </LectureShell>
    );
  }
  return <ServerLecture key={`${kj}:${activityId}`} course={course} courseId={courseId} activityId={activityId} />;
}

function LectureShell({ course, children }: { course: Course; children: ReactNode }) {
  return (
    <section className="study rec study-lecture" data-area="home">
      <Crumbs course={course} />
      <h1><Icon name="play" /><span>영상 강의</span></h1>
      {children}
    </section>
  );
}

function ServerLecture({ course, courseId, activityId }: { course: Course; courseId: Id; activityId: number }) {
  const kj = course.id;
  const key = detailKey(courseId, activityId);
  const pkey = courseKey(courseId);
  const load = () => lectureDetails.load(key, courseId, activityId);
  const loadProgress = () => lectureProgress.load(pkey, courseId);
  const loadList = () => pdfLists.load(kj, kj);
  const entry = useResource(lectureDetails, key, () => void load());
  const progressEntry = useResource(lectureProgress, pkey, () => void loadProgress());
  const pdfEntry = useResource(pdfLists, kj, () => void loadList());
  useEffect(() => {
    if (freshVisit("study-lecture", key) === "entry") {
      void load();
      void loadProgress();
      void loadList();
    } else {
      if (!lectureDetails.peek(key)) void load();
      if (!lectureProgress.peek(pkey)) void loadProgress();
      if (!pdfLists.peek(kj)) void loadList();
    }
  });

  const data = entry && entry.data;
  let main: ReactNode;
  if (!data) {
    if (!entry || !entry.error) main = <LoadingState title="강의를 불러오는 중입니다" />;
    else if (missing(entry.error)) main = <EmptyState title="강의를 찾을 수 없습니다" body="아직 공개되지 않았거나 삭제되었거나 볼 수 있는 권한이 없습니다." actions={<FolderLink kj={kj} />} />;
    else main = <ErrorState title="강의를 불러오지 못했습니다" body={detailOf(entry.error, lectureMessage)} actions={<Retry load={load} />} />;
  } else if (data.type !== "lecture_weeks") {
    main = <EmptyState title="영상 강의가 아닙니다" body="학습실 폴더에서 영상 강의를 다시 골라 주세요." actions={<FolderLink kj={kj} />} />;
  } else main = <LectureBody data={data} kj={kj} staleError={entry.error} progressEntry={progressEntry} />;

  return (
    <LectureShell course={course}>
      <div className="study-lecture__main" aria-live="polite">{main}</div>
      <section className="study-section" aria-label="함께 볼 강의자료">
        <h2>함께 볼 강의자료</h2>
        <div className="study-lecture__pdfs"><LecturePdfs entry={pdfEntry} kj={kj} load={loadList} /></div>
      </section>
    </LectureShell>
  );
}

function progressLine(progress: ItemProgress | undefined): string {
  if (!progress) return "아직 학습 기록이 없습니다.";
  const required = progress.requiredSec == null ? "인정 기준 시간 없음" : `인정 기준 시간 ${durationText(progress.requiredSec)}`;
  const status = String(progress.status);
  return `적립 ${durationText(progress.creditedSec)} / ${required} · ${PROGRESS_STATE[status] || status}`;
}

function ProgressBar({ progress }: { progress: ItemProgress | undefined }) {
  const percent = progress ? Math.max(0, Math.min(100, Number(progress.progressPercent) || 0)) : 0;
  return <div className="bar" role="img" aria-label={`진도 ${percent}%`}><i style={{ width: `${percent}%` }} /></div>;
}

function LectureBody({ data, kj, staleError, progressEntry }: { data: ActivityDetail; kj: string; staleError: unknown; progressEntry: Entry<ProgressWeek[]> | null }) {
  const items = Array.isArray(data.lectureItems) ? data.lectureItems : [];
  const progress = progressEntry && progressEntry.data ? progressOf(progressEntry.data, data.activityId) : new Map<number, ItemProgress>();
  const progressLoading = !progressEntry || (!progressEntry.data && !progressEntry.error);
  const progressFailed = progressEntry && progressEntry.error && !progressEntry.data;
  return (
    <div className="study-lecture__body">
      <h2 className="rec-title">{data.title}</h2>
      <p className="rec-meta tiny">{`${data.weekNo}주차 · 학습 인정 기간 ${windowText(data)} · 콘텐츠 ${items.length}개`}</p>
      {staleError ? <p className="err" role="alert">{detailOf(staleError, lectureMessage)}</p> : null}
      {progressFailed ? <p className="err" role="alert">{`진도를 불러오지 못했습니다. ${detailOf(progressEntry.error, lectureMessage)}`}</p> : null}
      {items.length
        ? <div className="srv-lecture-items">{items.map((item) => <ItemCard key={item.itemId} item={item} kj={kj} activityId={data.activityId} progress={progress.get(item.itemId)} loading={progressLoading} />)}</div>
        : <EmptyState title="등록된 콘텐츠가 없습니다" body="교수님이 영상을 올리면 여기에서 학습할 수 있습니다." />}
      <div className="btn-row"><Link href={studentLectureHref(kj, data.activityId)} className="btn btn-secondary">강의실 활동 상세</Link></div>
    </div>
  );
}

function ItemCard({ item, kj, activityId, progress, loading }: { item: LectureItem; kj: string; activityId: number; progress: ItemProgress | undefined; loading: boolean }) {
  const meta = [item.durationSec != null ? `길이 ${durationText(item.durationSec)}` : null, item.requiredSec != null ? `인정 기준 시간 ${durationText(item.requiredSec)}` : null].filter(Boolean).join(" · ");
  const state = item.state || "";
  return (
    <article className="progress-card srv-lecture-item">
      <div className="progress-pct">{progress ? `${String(progress.progressPercent)}%` : "—"}</div>
      <div className="srv-lecture-copy">
        <b>{item.title}</b>
        <p className="tiny">
          <Badge tone={state === "ready" ? "success" : state === "processing" ? "neutral" : "danger"}>{VIDEO_STATE[state] || "재생할 수 없음"}</Badge>
          {meta ? ` ${meta}` : ""}
        </p>
        <p className="tiny">{loading ? "진도를 불러오는 중입니다." : progressLine(progress)}</p>
        <ProgressBar progress={progress} />
        <div className="btn-row">
          {state === "ready"
            ? <Link href={lecturePlayerHref(kj, activityId, item.itemId)} className="btn btn-primary" aria-label={`${item.title} 학습하기`}>학습하기</Link>
            : <p className="tiny">{state === "processing" ? "영상을 변환하고 있습니다. 끝나면 학습할 수 있습니다." : "영상을 재생할 수 없습니다. 담당 교수에게 문의하세요."}</p>}
        </div>
      </div>
    </article>
  );
}

function LecturePdfs({ entry, kj, load }: { entry: Entry<CoursePdfRecord[]> | null; kj: string; load: () => Promise<void> }) {
  if (!entry || (!entry.data && !entry.error)) return <p className="tiny">강의자료를 불러오는 중입니다.</p>;
  if (!entry.data) {
    if (missing(entry.error)) return <p className="tiny">이 과목의 강의자료를 볼 수 없습니다.</p>;
    return <><p className="err" role="alert">{detailOf(entry.error, () => "강의자료 목록을 받지 못했습니다. 잠시 후 다시 시도해 주세요.")}</p><Retry load={load} /></>;
  }
  if (!entry.data.length) return <p className="tiny">서버에 등록된 PDF 강의자료가 없습니다.</p>;
  // 요약 게시본이 있는 자료는 요약 화면으로, 없는 자료는 문서 보기로 간다.
  return (
    <ul className="study-lecture__pdf-list">
      {entry.data.map((record) => (
        <li key={record.id}>
          {record.remote && record.summaryPublished
            ? <Link href={studySummaryHref(kj, record.id)} aria-label={`${record.title} 자료 요약`}>{record.title}</Link>
            : <Link href={documentHref(kj, record.id)} aria-label={`${record.title} 문서 보기`}>{record.title}</Link>}
          <span className="tiny">{` · ${record.remote ? record.weekNo : undefined}주차 · ${record.fileName}`}</span>
        </li>
      ))}
    </ul>
  );
}
