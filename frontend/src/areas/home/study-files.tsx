import { useEffect, useRef, useState, type ReactNode } from "react";
import { courses, courseById, studyMaterials, COURSE_ORDER, type Course } from "../../data.ts";
import { hrefFor, find } from "../../manifest.ts";
import type { ScreenCtx } from "../../types.ts";
import { Link } from "../../router.tsx";
import { Icon } from "../../ui/icon.tsx";
import { Button, EmptyState, LoadingState, ErrorState } from "../../ui/kit.tsx";
import { readQuery, pageSearch } from "../../shared/query.tsx";
import { listCoursePdfs, type CoursePdfRecord } from "../../shared/course-pdfs.ts";
import { backendStatus, backendState, courseIdFor, kjOfCourseId, subscribe as onBackendChange } from "../../shared/backend.ts";
import { listRecordings, activeRecording, onRecordingEvent, formatDuration, moveAllToAccount, fromServer, type RecordingRecord } from "../../shared/course-recordings.ts";
import { listFolders, createFolder, placements, useStudyRoomStore, leftByOthers, foldersLeftByOthers, type StudyFolder } from "../../shared/study-folders.ts";
import { serverStudyCourse, studyRoomOnServer, studyRoomMessage, studyUsage, usageText, myRecordings } from "../../shared/study-room-remote.ts";
import { serverCourseViews } from "../../shared/course-view.ts";
import { thumbnailImage } from "../../shared/preview-remote.tsx";
import { ApiError } from "../../shared/api.ts";
import { freshVisit, useResource } from "../../shared/remote-cache.ts";
import {
  courseWeeks, lectureProgress, courseKey, lectureRows, weeksOf, lectureMessage, windowText, PROGRESS_STATE, ACTIVITY_PHASE,
  type LectureRow, type ProgressItem, type ProgressWeek,
} from "../../shared/lectures-remote.ts";
import { serverStudySummary, studyLectureHref } from "./study-server.tsx";
import { wallFormat } from "../../shared/home-remote.ts";

const REFRESH_MS = 30000;
const shortDate = wallFormat("ko-KR", { month: "long", day: "numeric" });

type StudyMaterial = (typeof studyMaterials)[number];

export function studyCourses(): Course[] {
  if (backendStatus() === "ready") return serverCourseViews().flatMap(({ course }) => (course ? [course] : []));
  const rank = (id: string) => (COURSE_ORDER.includes(id) ? COURSE_ORDER.indexOf(id) : COURSE_ORDER.length);
  return courses
    .filter((course) => course.kind === "regular" && course.enrolled && course.term === "2026-2")
    .sort((a, b) => rank(a.id) - rank(b.id));
}

const courseName = (course: Course | null | undefined) => course && !course.missing ? course.cardTitle || course.ko : "과목 정보 없음";
export const folderHref = (courseId: string, folderId?: string): string => hrefFor(find("study_home"), { kj: courseId, f: folderId });
const pdfHref = (record: CoursePdfRecord) => hrefFor(find("study_home"), { kj: record.courseId, doc: record.id });
// A recording of a course I no longer take has no `kj`: its page is addressed by `rec` alone.
export const recordingHref = (courseId: string, id?: string): string => hrefFor(find("study_lecture"), { kj: courseId || undefined, rec: id });
const sampleHref = (item: StudyMaterial) => hrefFor(find("study_summary"), { m: item.id });
// A course linked on the server (backend `ready`) shows server files only, never the samples.
const serverOwned = (courseId: string) => backendStatus() === "ready" && Boolean(courseIdFor(courseId));
const samplesOf = (courseId: string): StudyMaterial[] => (backendStatus() === "ready" ? [] : studyMaterials.filter((item) => item.courseId === courseId));

const CHANGED = "study-files-changed";
/** Tells open folder views to re-read PDFs and recordings after something was added here. */
export const notifyStudyChanged = (): boolean => window.dispatchEvent(new Event(CHANGED));

/** Re-reads browser storage while mounted: on focus, when the tab becomes visible, every 30 s, on recorder events, and on notifyStudyChanged. */
function useStudyRefresh(load: () => void) {
  const latest = useRef(load);
  latest.current = load;
  useEffect(() => {
    const refresh = () => {
      if (document.visibilityState === "visible") latest.current();
    };
    const timer = window.setInterval(refresh, REFRESH_MS);
    const off = onRecordingEvent(refresh);
    // The study room moves to the server (or back) when the backend connection settles or the login changes.
    const offBackend = onBackendChange(refresh);
    window.addEventListener("focus", refresh);
    document.addEventListener("visibilitychange", refresh);
    window.addEventListener(CHANGED, refresh);
    latest.current();
    return () => {
      window.clearInterval(timer);
      off();
      offBackend();
      window.removeEventListener("focus", refresh);
      window.removeEventListener(CHANGED, refresh);
      document.removeEventListener("visibilitychange", refresh);
    };
  }, []);
}

interface FileCardProps {
  href: string;
  kind: string;
  badge?: string;
  title: string;
  preview?: string;
  iconName?: string;
  meta: (string | null | undefined | false)[];
  label?: string;
  thumb?: ReactNode;
}

function FileCard({ href, kind, badge, title, preview, iconName, meta, label, thumb }: FileCardProps) {
  return (
    <Link className={`study-card file-card kind-${kind}`} href={href} aria-label={label}>
      <span className="sf-thumb fc-thumb" aria-hidden="true">
        {badge ? <span className="fc-badge">{badge}</span> : null}
        {preview
          ? <span className="fc-preview">{preview}</span>
          : <span className="sf-icon"><Icon name={iconName || "file"} /></span>}
        {thumb}
      </span>
      <b className="sf-name" title={title}>{title}</b>
      <span className="sf-meta">
        {meta.filter(Boolean).map((text, i) => <span key={i} className={i ? undefined : "sf-count"}>{text}</span>)}
      </span>
    </Link>
  );
}

function SampleCard({ item, showCourse }: { item: StudyMaterial; showCourse: boolean }) {
  const course = courseById(item.courseId);
  return (
    <FileCard
      href={sampleHref(item)} kind="sample" badge="샘플" title={item.title} preview={item.sentence}
      meta={[`요약 ${item.points.length}줄`, showCourse ? courseName(course) : "샘플 자료"]}
    />
  );
}

function PdfCard({ record, showCourse }: { record: CoursePdfRecord; showCourse: boolean }) {
  const ready = record.status !== "no-text";
  // Server list records carry no overview (it is extracted when the document is opened).
  const preview = ready && !record.remote ? record.overview.slice(0, 2).map((point) => point.text.replace(/\s+/g, " ")).join(" ") : "";
  // Server records have no page count until the document is opened and read in this tab.
  const detail = record.pageCount != null ? `${record.pageCount}쪽 · ${shortDate.format(record.uploadedAt)}` : shortDate.format(record.uploadedAt);
  return (
    <FileCard
      href={pdfHref(record)} kind="pdf" badge="PDF" title={record.title} preview={preview} iconName="file"
      meta={[record.remote ? "ASTRA 서버 자료" : ready ? "개요 준비됨" : "텍스트 없음", showCourse ? courseName(courseById(record.courseId)) : detail]}
      // Server PDFs: the first-page thumbnail the backend made while converting (nothing when there is none).
      thumb={record.remote ? thumbnailImage(record.backendCourseId, record.fileId, "fc-thumb-img") : null}
    />
  );
}

/** Where a recording stands, when it is not simply playable: the server's malware scan, or kept in this browser only. */
function recordingStatus(record: RecordingRecord): string | null {
  if (record.remote) return record.state === "scanning" ? "검사 중" : record.state === "rejected" ? "검사 거부" : null;
  if (backendStatus() !== "ready") return null;
  return record.uploadError ? `이 브라우저에만 저장 · ${record.uploadError}` : "이 브라우저에만 저장";
}

function RecordingCard({ record, showCourse }: { record: RecordingRecord; showCourse: boolean }) {
  return (
    <FileCard
      href={recordingHref(record.courseId, record.id)} kind="rec" badge={record.source === "audio" ? "오디오 파일" : record.source === "tab" ? "탭 녹음" : "마이크 녹음"}
      title={record.title} iconName="play"
      meta={[recordingStatus(record) || formatDuration(record.durationMs), showCourse ? courseName(courseById(record.courseId)) : shortDate.format(record.createdAt)]}
    />
  );
}

function LiveRecordingCard({ course }: { course: Course }) {
  const live = activeRecording();
  if (!live || live.courseId !== course.id) return null;
  return (
    <FileCard
      href={recordingHref(course.id)} kind="live" title={live.paused ? "녹음 일시정지" : "녹음 중"} iconName="mic"
      meta={[formatDuration(live.elapsedMs), "눌러서 이어 보기"]}
    />
  );
}

function lectureProgressText(items: ProgressItem[]): string {
  if (!items.length) return "학습 기록 없음";
  const met = items.filter((item) => item.progress && item.progress.status === "met").length;
  if (met === items.length) return PROGRESS_STATE.met;
  const percent = Math.round(items.reduce((sum, item) => sum + (Number(item.progress && item.progress.progressPercent) || 0), 0) / items.length);
  return `진도 ${percent}% · 인정 ${met}/${items.length}`;
}

function LectureCard({ activity, kj, progress }: { activity: LectureRow; kj: string; progress: ProgressWeek[] | null }) {
  const mine: ProgressItem[] = [];
  for (const week of Array.isArray(progress) ? progress : []) {
    for (const item of Array.isArray(week.items) ? week.items : []) if (item.activityId === activity.activityId) mine.push(item);
  }
  return (
    <FileCard
      href={studyLectureHref(kj, activity.activityId)} kind="lecture" badge="영상" title={activity.title} iconName="play"
      meta={[progress ? lectureProgressText(mine) : null, `${activity.weekNo}주차 · ${windowText(activity)}${activity.phase && activity.phase !== "open" ? ` · ${ACTIVITY_PHASE[activity.phase] || ""}` : ""}`]}
      label={`${activity.title} 영상 강의`}
    />
  );
}

/**
 * Server video lectures of a linked course (`GET …/weeks` and the caller's `…/lecture-progress`).
 * Students receive opened activities only, so every row links to its study-room lecture view.
 */
function LectureSection({ course, courseId }: { course: Course; courseId: number | string }) {
  const key = courseKey(courseId);
  const load = () => {
    void courseWeeks.load(key, courseId);
  };
  const loadProgress = () => {
    void lectureProgress.load(key, courseId);
  };
  const entry = useResource(courseWeeks, key, load);
  const progressEntry = useResource(lectureProgress, key, loadProgress);
  const [retrying, setRetrying] = useState(false);
  useEffect(() => {
    if (freshVisit("study-lectures", key) === "entry" || !courseWeeks.peek(key)) {
      load();
      loadProgress();
    } else if (!lectureProgress.peek(key)) loadProgress();
  });
  const data = entry && entry.data;
  let body: ReactNode;
  if (!data) {
    if (!entry || !entry.error) body = <LoadingState title="영상 강의를 불러오는 중입니다" />;
    else if (entry.error instanceof ApiError && entry.error.status === 404) body = <p className="tiny">이 과목의 영상 강의를 볼 수 없습니다.</p>;
    else {
      const detail = entry.error instanceof ApiError && entry.error.detail ? entry.error.detail : lectureMessage(entry.error);
      body = (
        <ErrorState
          title="영상 강의를 불러오지 못했습니다" body={detail}
          actions={<Button kind="ghost" disabled={retrying} onClick={() => { setRetrying(true); void courseWeeks.load(key, courseId).finally(() => setRetrying(false)); }}>다시 불러오기</Button>}
        />
      );
    }
  } else {
    const rows = lectureRows(weeksOf(data));
    const progress = (progressEntry && progressEntry.data) || null;
    body = rows.length
      ? <div className="study-grid">{rows.map((activity) => <LectureCard key={activity.activityId} activity={activity} kj={course.id} progress={progress} />)}</div>
      : <p className="tiny">서버에 등록된 영상 강의가 없습니다.</p>;
  }
  return (
    <section className="study-section" aria-label="영상 강의">
      <h2>영상 강의</h2>
      <div aria-live="polite">{body}</div>
    </section>
  );
}

function UserFolderCard({ course, folder, count }: { course: Course; folder: StudyFolder; count: number }) {
  return (
    <Link className="study-folder user-folder" href={folderHref(course.id, folder.id)} aria-label={`${folder.name} 폴더`}>
      <span className="sf-thumb" aria-hidden="true">
        <span className="sf-icon"><Icon name="folder" /></span>
        <span className="sf-open"><Icon name="chev" /></span>
      </span>
      <b className="sf-name" title={folder.name}>{folder.name}</b>
      <span className="sf-meta">
        <span className="sf-count">{count ? `${count}개` : "비어 있음"}</span>
        {folder.local ? <span>이 브라우저에만 저장</span> : null}
      </span>
    </Link>
  );
}

/** Card with a name field for a folder being created; Enter or leaving the field saves, Escape cancels. */
function FolderNameCard({ onDone }: { onDone: (name: string | null) => void }) {
  const input = useRef<HTMLInputElement>(null);
  const finished = useRef(false);
  useEffect(() => {
    input.current?.focus();
  }, []);
  const finish = (save: boolean) => {
    if (finished.current) return;
    finished.current = true;
    onDone(save ? input.current?.value ?? "" : null);
  };
  return (
    <div className="study-folder user-folder naming">
      <span className="sf-thumb" aria-hidden="true"><span className="sf-icon"><Icon name="folder" /></span></span>
      <input
        ref={input} type="text" className="sf-name-input" maxLength={60} placeholder="폴더 이름" aria-label="새 폴더 이름"
        onKeyDown={(event) => {
          if (event.key === "Enter") finish(true);
          else if (event.key === "Escape") finish(false);
        }}
        onBlur={(event) => finish(Boolean(event.currentTarget.value.trim()))}
      />
    </div>
  );
}

/** What `courseFiles` hands to the "새로 만들기" card: progress text and the inline folder-name input. */
export interface CreatorApi {
  say: (text: string, isError?: boolean) => void;
  nameFolder: () => void;
}

/**
 * One course folder or one user folder inside it: the "새로 만들기" card, folders (course root only),
 * PDFs, recordings, and samples. `makeCreator({ say, nameFolder })` returns the "새로 만들기" card.
 */
export function courseFiles(course: Course, folder: StudyFolder | null, makeCreator: (api: CreatorApi) => ReactNode): ReactNode {
  return <CourseFiles key={`${course.id}:${folder ? folder.id : ""}`} course={course} folder={folder} makeCreator={makeCreator} />;
}

interface StudyData {
  pdfs: CoursePdfRecord[];
  recordings: RecordingRecord[];
}

function CourseFiles({ course, folder, makeCreator }: { course: Course; folder: StudyFolder | null; makeCreator: (api: CreatorApi) => ReactNode }) {
  const folderId = folder ? folder.id : "";
  const samples = folder ? [] : samplesOf(course.id);
  const [data, setData] = useState<StudyData | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const [naming, setNaming] = useState(false);
  const [notice, setNotice] = useState({ text: "", isError: false });
  const [, setFolderVersion] = useState(0);
  const [moving, setMoving] = useState(false);
  useStudyRoomStore();
  const live = useRef(true);
  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
    };
  }, []);
  const say = (text: string, isError = false) => setNotice({ text, isError });

  useStudyRefresh(() => {
    Promise.all([listCoursePdfs(course.id), listRecordings(course.id)]).then(([pdfs, recordings]) => {
      if (!live.current) return;
      setData({ pdfs, recordings });
      setFailed(null);
    }).catch((error: unknown) => {
      if (live.current) setFailed(error instanceof ApiError ? studyRoomMessage(error) : "");
    });
  });

  const creator = makeCreator({ say, nameFolder: () => setNaming(true) });
  const shown = data || { pdfs: [], recordings: [] };
  const placed = placements();
  const courseFolders = listFolders(course.id);
  const known = new Set(courseFolders.map((item) => item.id));
  // An item whose folder cannot be found here (deleted, another account's, or a server folder while signed out) sits in the root.
  const folderOfItem = (record: { id: string; pendingFolderId?: string }) => {
    const target = placed[record.id] || record.pendingFolderId || "";
    return known.has(target) ? target : "";
  };
  // Browser recordings that are not the signed-in user's: shown apart at the course root, never mixed with theirs.
  const othersRecordings = shown.recordings.filter((record) => !record.remote && leftByOthers(record.ownerUserId));
  const ownRecordings = shown.recordings.filter((record) => record.remote || !leftByOthers(record.ownerUserId));
  const pdfs = shown.pdfs.filter((record) => folderOfItem(record) === folderId);
  const recordings = ownRecordings.filter((record) => folderOfItem(record) === folderId);
  const folders = folder ? [] : courseFolders;
  const countIn = (id: string) => [...shown.pdfs, ...ownRecordings].filter((record) => folderOfItem(record) === id).length;
  const onServer = serverStudyCourse(course.id) != null;
  // The signed-in user's items still only in this browser: offered for an explicit move (course root).
  const localRecordings = folder ? 0 : ownRecordings.filter((record) => !record.remote).length;
  const localFolderCount = folder ? 0 : folders.filter((item) => item.local).length;
  const othersFolders = folder || !onServer ? [] : foldersLeftByOthers(course.id);
  const accountName = backendState().me?.name || "";
  const status = failed != null
    ? backendStatus() === "ready"
      ? `ASTRA 서버에서 PDF와 녹음 목록을 받지 못했습니다. ${failed || "잠시 후 다시 시도해 주세요."}`
      : "이 브라우저의 저장소에서 PDF와 녹음을 읽지 못했습니다. 사이트 데이터 접근 설정을 확인해 주세요."
    : data
      ? [folders.length && `폴더 ${folders.length}개`, `PDF ${pdfs.length}개`, `녹음 ${recordings.length}개`, onServer && usageText(studyUsage())].filter(Boolean).join(" · ")
      : "PDF와 녹음을 불러오는 중입니다";
  const courseId = !folder && serverOwned(course.id) ? courseIdFor(course.id) : null;
  const moveAll = (others: boolean) => {
    setMoving(true);
    say("이 브라우저의 학습실 자료를 내 계정으로 옮기고 있습니다.");
    moveAllToAccount(course.id, others).then((text) => say(text), (error: unknown) => say(error instanceof Error ? error.message : String(error), true))
      .finally(() => {
        setMoving(false);
        notifyStudyChanged();
      });
  };
  const account = `지금 로그인한 계정${accountName ? `(${accountName})` : ""}`;

  return (
    // The area stops being busy after the first answer, so screen readers announce the failure text.
    <div className="course-study__files" aria-busy={data || failed != null ? undefined : "true"}>
      <p className="course-study__count" role="status" aria-live="polite">{status}</p>
      <p className={`course-study__notice${notice.isError ? " is-error" : ""}`} role="status" aria-live="polite" hidden={!notice.text}>{notice.text}</p>
      {onServer && data && (localRecordings || localFolderCount) ? (
        <div className="course-study__notice" role="note">
          <p>{`이 브라우저에만 저장된 내 ${[localFolderCount && `폴더 ${localFolderCount}개`, localRecordings && `녹음 ${localRecordings}개`].filter(Boolean).join("와 ")}가 있습니다. 옮기면 ${account}에 저장되고, 서버가 받은 항목만 이 브라우저에서 지워집니다.`}</p>
          <Button kind="secondary" disabled={moving} onClick={() => moveAll(false)}>{moving ? "옮기는 중…" : "모두 내 계정으로 옮기기"}</Button>
        </div>
      ) : null}
      <div className="study-grid">
        {data ? (
          <>
            {creator}
            <LiveRecordingCard course={course} />
            {naming ? (
              <FolderNameCard
                onDone={(name) => {
                  setNaming(false);
                  if (name === null) return;
                  createFolder(course.id, name).then(() => {
                    say(`"${name.trim()}" 폴더를 만들었습니다.`);
                  }, (error: unknown) => {
                    say(error instanceof Error ? error.message : String(error), true);
                  }).finally(() => setFolderVersion((n) => n + 1));
                }}
              />
            ) : null}
            {/* Server folder and recording ids are both small numbers: keys carry the kind. */}
            {folders.map((item) => <UserFolderCard key={`folder-${item.id}`} course={course} folder={item} count={countIn(item.id)} />)}
            {pdfs.map((record) => <PdfCard key={record.id} record={record} showCourse={false} />)}
            {recordings.map((record) => <RecordingCard key={`rec-${record.id}`} record={record} showCourse={false} />)}
            {samples.map((item) => <SampleCard key={item.id} item={item} showCourse={false} />)}
          </>
        ) : null}
      </div>
      {onServer && data && !folder && (othersRecordings.length || othersFolders.length) ? (
        <section className="study-section" aria-label="이 브라우저에 남아 있던 항목">
          <h2>이 브라우저에 남아 있던 항목</h2>
          <p className="tiny">{`로그아웃한 상태였거나 다른 계정으로 로그인했을 때 이 브라우저에 저장된 ${[othersFolders.length && `폴더 ${othersFolders.length}개`, othersRecordings.length && `녹음 ${othersRecordings.length}개`].filter(Boolean).join("와 ")}입니다. 이 컴퓨터를 함께 쓰는 다른 사람이 남긴 것일 수 있습니다. 내 것이 맞을 때만 ${account}으로 옮기세요. 녹음을 눌러 듣고, 내려받고, 옮기거나 지울 수 있습니다.`}</p>
          <Button kind="ghost" disabled={moving} onClick={() => moveAll(true)}>{moving ? "옮기는 중…" : "내 것이 맞으면 모두 내 계정으로 옮기기"}</Button>
          <div className="study-grid">
            {othersRecordings.map((record) => <RecordingCard key={`rec-${record.id}`} record={record} showCourse={false} />)}
          </div>
        </section>
      ) : null}
      {courseId != null ? <LectureSection course={course} courseId={courseId} /> : null}
    </div>
  );
}

function FolderCard({ course }: { course: Course }) {
  const samples = samplesOf(course.id).length;
  const [count, setCount] = useState(samples ? `샘플 ${samples}` : "비어 있음");
  useEffect(() => {
    let live = true;
    Promise.all([listCoursePdfs(course.id), listRecordings(course.id)]).then(([pdfs, recordings]) => {
      const parts = [pdfs.length && `PDF ${pdfs.length}`, recordings.length && `녹음 ${recordings.length}`, samples && `샘플 ${samples}`].filter(Boolean);
      if (live) setCount(parts.length ? parts.join(" · ") : "비어 있음");
    }).catch(() => {});
    return () => {
      live = false;
    };
  }, [course.id, samples]);
  return (
    <Link className="study-folder" href={folderHref(course.id)} aria-label={`${courseName(course)} 폴더`}>
      <span className="sf-thumb" aria-hidden="true">
        <span className="sf-icon"><Icon name="notebook" /></span>
        <span className="sf-open"><Icon name="chev" /></span>
      </span>
      <b className="sf-name">{courseName(course)}</b>
      <span className="sf-meta"><span className="sf-count">{count}</span><span>{course.code}</span></span>
    </Link>
  );
}

function matches(q: string, ...parts: string[]): boolean {
  return parts.join(" ").toLowerCase().includes(q);
}

function SearchResults({ q }: { q: string }) {
  const samples = backendStatus() === "ready" ? [] : studyMaterials.filter((item) => {
    const course = courseById(item.courseId);
    return matches(q, item.title, course.ko, course.en, course.cardTitle || "");
  });
  const [found, setFound] = useState<StudyData | null>(null);
  useEffect(() => {
    let live = true;
    const list = studyCourses();
    Promise.all(list.map((course) => Promise.all([listCoursePdfs(course.id), listRecordings(course.id)]).then(([pdfs, recordings]) => {
      const hit = (title: string) => matches(q, title, course.ko, course.en, course.cardTitle || "");
      return { pdfs: pdfs.filter((r) => hit(r.title)), recordings: recordings.filter((r) => hit(r.title)) };
    }))).catch((): StudyData[] => []).then((parts) => {
      if (!live) return;
      setFound({ pdfs: parts.flatMap((part) => part.pdfs), recordings: parts.flatMap((part) => part.recordings) });
    });
    return () => {
      live = false;
    };
  }, [q]);
  if (found && !found.pdfs.length && !found.recordings.length && !samples.length) {
    return <div><EmptyState title="검색 결과가 없습니다" body="자료명이나 과목명을 바꿔 보세요." /></div>;
  }
  return (
    <div aria-busy={found ? undefined : "true"}>
      <div className="study-grid">
        {found ? found.pdfs.map((record) => <PdfCard key={record.id} record={record} showCourse />) : null}
        {found ? found.recordings.map((record) => <RecordingCard key={`rec-${record.id}`} record={record} showCourse />) : null}
        {samples.map((item) => <SampleCard key={item.id} item={item} showCourse />)}
      </div>
    </div>
  );
}

/**
 * Server recordings of courses I no longer take (`GET …/recordings`): they count toward the usage line, so they
 * are listed here with the usual card (play, download and delete on the recording page).
 */
function LeftCourseRecordings() {
  const entry = useResource(myRecordings, "all", () => void myRecordings.load("all"));
  const onServer = studyRoomOnServer();
  useEffect(() => {
    if (onServer) void myRecordings.visit("study-left-courses", "all");
  });
  const rows = onServer && entry && entry.data ? entry.data.recordings.filter((recording) => !kjOfCourseId(recording.courseId)) : [];
  if (!rows.length) return null;
  return (
    <section className="study-section" aria-label="지금 듣지 않는 과목의 녹음">
      <h2>지금 듣지 않는 과목의 녹음</h2>
      <p className="tiny">수강을 마치거나 취소한 과목에서 저장한 녹음입니다. 녹음 저장 공간 사용량에 들어갑니다.</p>
      <div className="study-grid">
        {rows.map((recording) => <RecordingCard key={`rec-${recording.id}`} record={fromServer(recording, "")} showCourse={false} />)}
      </div>
    </section>
  );
}

export function studyRoot(ctx: ScreenCtx): ReactNode {
  const scope = "home:study";
  const q = readQuery(ctx.state, scope).trim().toLowerCase();
  return (
    <section className="study" data-area="home">
      <div className="page-head">
        <h1>학습실</h1>
        {pageSearch(ctx, scope, "학습실 검색", "자료명 또는 과목명")}
      </div>
      {q
        ? <SearchResults key={q} q={q} />
        : (
          <>
            <div className="study-grid">{studyCourses().map((course) => <FolderCard key={course.id} course={course} />)}</div>
            <LeftCourseRecordings />
          </>
        )}
    </section>
  );
}

export function sampleSummary(ctx: ScreenCtx): ReactNode {
  // Backend `ready`/`anon`: the server material summary or a refusal (study-server.tsx); `off` keeps the sample.
  const server = serverStudySummary(ctx, () => sampleSummary(ctx));
  if (server) return server;
  const id = (ctx.params && ctx.params.get("m")) || "sm-db";
  const item = studyMaterials.find((row) => row.id === id);
  if (!item) {
    return (
      <div className="empty" role="status" data-area="home">
        <strong>자료를 찾을 수 없습니다</strong>
        <p>학습실에서 자료를 다시 선택해 주세요.</p>
      </div>
    );
  }
  const course = courseById(item.courseId);
  return (
    <section className="study" data-area="home">
      <p className="term"><Link href={hrefFor(find("study_home"))}>학습실</Link>{" · "}<Link href={folderHref(course.id)}>{courseName(course)}</Link></p>
      <article className="summary-doc">
        <div className="sum-h">
          <Icon name="book" />
          <div>
            <strong>자료 요약</strong>
            <span className="tiny">{`${courseName(course)} · ${item.title}`}</span>
          </div>
        </div>
        <p>{item.sentence}</p>
        <ol className="key-points">{item.points.map((point, index) => <li key={index}>{point}</li>)}</ol>
        <p className="tiny">샘플 자료입니다. 실제 PDF를 요약한 결과가 아닙니다.</p>
      </article>
    </section>
  );
}
