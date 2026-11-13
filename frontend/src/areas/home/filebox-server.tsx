import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { backendPanel } from "../../shared/backend-prompt.tsx";
import { backendStatus, subscribe } from "../../shared/backend.ts";
import { ApiError } from "../../shared/api.ts";
import { freshVisit, useResource } from "../../shared/remote-cache.ts";
import { sizeText } from "../../shared/uploads-remote.tsx";
import { koDateTime } from "../../shared/home-remote.ts";
import { Button, Dialog, EmptyState, ErrorState, Field, LoadingState, Badge } from "../../ui/kit.tsx";
import {
  listings, trash, listKey, TRASH, SORTS, SCAN_STATE, foldersIn, createFolder,
  uploadToFilebox, renameItem, moveItems, trashItems, restoreItems, purgeItems,
  emptyTrash, downloadItem, itemReferences, requestZip, zipStatus, downloadZip,
  fileboxMessage, PURPOSE_LABEL, type FileboxItem, type FileboxListing,
  type FileboxTrash, type FileboxCount, type FileboxReference, type ZipJob,
} from "../../shared/filebox-remote.ts";

const ZIP_POLL_MS = 2000;
const SCAN_POLL_MS = 3000;
const SEARCH_DELAY_MS = 400;

interface View {
  folder: number | null;
  sort: string;
  order: string;
  q: string;
  inTrash: boolean;
  selected: Map<number, FileboxItem>;
  zip: ZipJob | null;
  renaming: { id: number; name: string } | null;
  moving: boolean;
}

function initialView(): View {
  return { folder: null, sort: "date", order: "desc", q: "", inTrash: false,
    selected: new Map(), zip: null, renaming: null, moving: false };
}

// Navigation and selection live only in memory for the current backend session.
let view = initialView();
let session = 0;
subscribe(() => { view = initialView(); session += 1; });
const isoTime = (value?: string | null): string => koDateTime(value) || "";

export function serverFileBox(): ReactNode {
  const status = backendStatus();
  if (status === "off") return null;
  return (
    <Dialog title="파일함" wide className="home-dialog">
      {status !== "ready"
        ? backendPanel({ text: "ASTRA 서버에 로그인하면 내 파일함을 서버에서 불러옵니다." })
        : <FileBox key={session} />}
    </Dialog>
  );
}

function FileBox() {
  const [v, setV] = useState<View>(() => view);
  const current = useRef(v);
  const born = useRef(session);
  const live = useRef(true);
  const [message, setMessage] = useState({ text: "", alert: false });
  const [search, setSearch] = useState(v.q);
  const [folderName, setFolderName] = useState("");
  const picked = useRef<HTMLInputElement>(null);
  const [pickerFolder, setPickerFolder] = useState<number | null>(v.folder);
  const locks = useRef(new Set<string>());
  const [pending, setPending] = useState<ReadonlySet<string>>(new Set());
  const key = v.inTrash ? TRASH : listKey(v.folder, v.sort, v.order, v.q);

  const active = useCallback(() => live.current && born.current === session, []);
  const update = useCallback((patch: Partial<View>) => {
    if (!active()) return;
    const next = { ...current.current, ...patch };
    current.current = next;
    view = next;
    setV(next);
  }, [active]);
  const say = useCallback((text: string, alert = false) => {
    if (active()) setMessage({ text, alert });
  }, [active]);
  const reload = useCallback((previous?: FileboxListing | FileboxTrash | null) => {
    const shown = current.current;
    if (!active()) return Promise.resolve();
    return shown.inTrash
      ? trash.load(TRASH, previous)
      : listings.load(listKey(shown.folder, shown.sort, shown.order, shown.q), shown.folder,
        shown.sort, shown.order, shown.q,
        previous && "path" in previous ? previous : null);
  }, [active]);
  const refresh = useCallback(() => { void reload(); }, [reload]);
  const normalEntry = useResource(listings, v.inTrash ? null : key, v.inTrash ? undefined : refresh);
  const trashEntry = useResource(trash, v.inTrash ? key : null, v.inTrash ? refresh : undefined);
  const entry = v.inTrash ? trashEntry : normalEntry;
  const data = entry?.data;
  const listing = v.inTrash ? null : normalEntry?.data;

  useEffect(() => {
    live.current = true;
    return () => { live.current = false; };
  }, []);
  useEffect(() => {
    const cached = current.current.inTrash ? trash.peek(key) : listings.peek(key);
    if (freshVisit("filebox", "files") === "entry" || !cached) void reload();
  }, [key, reload]);
  useEffect(() => { setSearch(v.q); }, [v.q, v.folder, v.inTrash]);

  const navigate = useCallback((patch: Partial<View>) => {
    update({ ...patch, selected: new Map(), renaming: null, moving: false });
    say("");
  }, [update, say]);
  useEffect(() => {
    if (v.inTrash || search.trim() === v.q) return;
    const timer = setTimeout(() => navigate({ q: search.trim() }), SEARCH_DELAY_MS);
    return () => clearTimeout(timer);
  }, [search, v.q, v.inTrash, navigate]);

  // A fresh listing replaces selected item snapshots, including their If-Match versions.
  useEffect(() => {
    if (!data) return;
    const selected = new Map<number, FileboxItem>();
    for (const item of data.items) {
      if (current.current.selected.has(item.id)) selected.set(item.id, item);
    }
    const before = current.current.selected;
    if (selected.size !== before.size || [...selected].some(([id, item]) => before.get(id) !== item)) {
      update({ selected });
    }
  }, [data, update]);
  useEffect(() => {
    if (v.inTrash || !data?.items.some((item) => item.state === "scanning")) return;
    const timer = setTimeout(refresh, SCAN_POLL_MS);
    return () => clearTimeout(timer);
  }, [data, entry?.loading, key, v.inTrash, refresh]);

  useEffect(() => {
    const job = v.zip;
    if (!job || (job.state !== "requested" && job.state !== "building")) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;
    const poll = () => {
      timer = setTimeout(() => {
        if (cancelled || !active() || current.current.zip !== job) return;
        void zipStatus(job.jobId).then((next) => {
          if (!cancelled && current.current.zip === job) update({ zip: next });
        }, (error: unknown) => {
          if (cancelled || !active() || current.current.zip !== job) return;
          say(fileboxMessage(error), true);
          poll();
        });
      }, ZIP_POLL_MS);
    };
    poll();
    return () => { cancelled = true; clearTimeout(timer); };
  }, [v.zip, active, update, say]);

  async function run(name: string, action: () => Promise<void>) {
    if (locks.current.has(name)) return;
    locks.current.add(name);
    setPending(new Set(locks.current));
    try { await action(); }
    finally {
      locks.current.delete(name);
      if (active()) setPending(new Set(locks.current));
    }
  }

  async function command(name: string, action: () => Promise<FileboxCount>, success: (result: FileboxCount) => string) {
    await run(name, async () => {
      say("");
      try {
        const result = await action();
        update({ selected: new Map(), moving: false });
        say(success(result));
      } catch (error) {
        say(fileboxMessage(error), true);
      } finally {
        update({ moving: false });
        await reload();
      }
    });
  }

  async function makeFolder() {
    const name = folderName.trim();
    if (!name) return say("폴더 이름을 입력하세요.", true);
    await run("folder", async () => {
      try {
        await createFolder(current.current.folder, name);
        if (!active()) return;
        setFolderName("");
        say("폴더를 만들었습니다.");
        await reload();
      } catch (error) { say(fileboxMessage(error), true); }
    });
  }

  async function upload() {
    const files = [...(picked.current?.files ?? [])];
    if (!files.length) return say("올릴 파일을 고르세요.", true);
    const folder = current.current.folder;
    await run("upload", async () => {
      let done = 0;
      try {
        for (const file of files) {
          if (!active()) return;
          say(`${file.name} 올리는 중입니다.`);
          await uploadToFilebox(file, folder);
          done += 1;
        }
        if (active() && picked.current) picked.current.value = "";
        say(`${done}개를 올렸습니다.`);
      } catch (error) {
        say(`${done ? `${done}개를 올린 뒤 멈췄습니다. ` : ""}${fileboxMessage(error)}`, true);
      } finally { await reload(); }
    });
  }

  async function saveName(item: FileboxItem) {
    const name = current.current.renaming?.name.trim();
    if (!name) return;
    await run(`rename:${item.id}`, async () => {
      try {
        await renameItem(item, name);
        update({ renaming: null });
        say("이름을 바꿨습니다.");
      } catch (error) {
        // Keep the typed draft on conflicts; reload supplies the new version for a retry.
        say(fileboxMessage(error), true);
      } finally { await reload(); }
    });
  }

  async function startZip() {
    const selected = [...current.current.selected.values()];
    if (!selected.length || selected.some((item) => item.kind !== "file")) return;
    await run("zip", async () => {
      say("");
      try { update({ zip: await requestZip(selected.map((item) => item.id)) }); }
      catch (error) { say(fileboxMessage(error), true); }
    });
  }

  async function getZip(job: ZipJob) {
    await run("zip-download", async () => {
      try { await downloadZip(job); }
      catch (error) {
        say(fileboxMessage(error), true);
        if (error instanceof ApiError && error.status === 410 && current.current.zip === job) {
          update({ zip: { ...job, state: "expired" } });
        }
      }
    });
  }

  const selected = data
    ? data.items.filter((item) => v.selected.has(item.id))
    : [...v.selected.values()];
  const ids = selected.map((item) => item.id);
  const path = listing?.path ?? [];
  const usage = listing?.usage;
  const emptyButton = (
    <ArmedButton key={`empty:${key}:${ids.join(",")}`} label="휴지통 비우기" armedLabel="한 번 더 누르면 모두 영구 삭제"
      busy={pending.has("empty")} busyLabel="비우는 중…"
      onConfirm={() => command("empty", emptyTrash, (r) => `휴지통에서 ${r.count}개를 영구 삭제했습니다.`)} />
  );

  function renderList(): ReactNode {
    if (!entry || (!data && !entry.error)) {
      return <LoadingState title={v.inTrash ? "휴지통을 불러오는 중입니다" : "파일함을 불러오는 중입니다"} />;
    }
    if (!data) {
      return <>
        <ErrorState title="파일함을 불러오지 못했습니다" body={fileboxMessage(entry.error)}
          actions={<Button kind="ghost" onClick={refresh}>다시 불러오기</Button>} />
        {entry.error instanceof ApiError && entry.error.status === 404 && v.folder
          ? <Button onClick={() => navigate({ folder: null, q: "" })}>내 파일함으로</Button> : null}
      </>;
    }
    return <>
      {data.items.length ? <ul className="fb-rows">{data.items.map((item) => (
        <Row key={`${key}:${item.id}`} item={item} inTrash={v.inTrash} selected={v.selected.has(item.id)}
          draft={v.renaming?.id === item.id ? v.renaming.name : null}
          saving={pending.has(`rename:${item.id}`)}
          onToggle={(on) => {
            const next = new Map(current.current.selected);
            if (on) next.set(item.id, item); else next.delete(item.id);
            update({ selected: next, moving: false });
          }}
          onOpen={() => navigate({ folder: item.id, q: "" })}
          onRename={() => update({ renaming: v.renaming?.id === item.id ? null : { id: item.id, name: item.name } })}
          onDraft={(name) => update({ renaming: { id: item.id, name } })}
          onSave={() => saveName(item)} say={say} />
      ))}</ul> : v.inTrash ? <EmptyState title="휴지통이 비어 있습니다" />
        : v.q ? <EmptyState title="찾는 이름의 항목이 없습니다" body="이 폴더 바로 아래 항목의 이름에서만 찾습니다." />
          : <EmptyState title="이 폴더에 항목이 없습니다" body="파일을 올리거나 폴더를 만드세요." />}
      {data.nextCursor ? <div className="btn-row"><Button disabled={Boolean(entry.loading)} aria-busy={Boolean(entry.loading)}
        onClick={() => { void reload(data); }}>{entry.loading ? "불러오는 중…" : "더 보기"}</Button></div> : null}
    </>;
  }

  return <div className="fb">
    <div className="filters">
      <button type="button" className={`chip${!v.inTrash ? " on all" : ""}`} aria-pressed={!v.inTrash}
        onClick={() => { if (v.inTrash) navigate({ inTrash: false }); }}>내 파일</button>
      <button type="button" className={`chip${v.inTrash ? " on all" : ""}`} aria-pressed={v.inTrash}
        onClick={() => { if (!v.inTrash) navigate({ inTrash: true }); }}>휴지통</button>
    </div>
    <div className="fb-usage">
      {v.inTrash ? <p className="note">휴지통 파일도 영구 삭제할 때까지 용량을 차지합니다.</p>
        : usage ? <>
          <span className="tiny">{`사용 ${sizeText(usage.usedBytes) || "0KB"} / ${sizeText(usage.quotaBytes)} · 항목 ${usage.itemCount}/${usage.maxItems}`}</span>
          <progress max={usage.quotaBytes || 1} value={Math.min(usage.usedBytes, usage.quotaBytes || 0)} aria-label="파일함 사용량" />
        </> : null}
    </div>
    <nav className="fb-crumbs" aria-label="폴더 위치">
      {!v.inTrash ? <ol>
        <li>{path.length ? <Button kind="ghost" onClick={() => navigate({ folder: null, q: "" })}>내 파일함</Button>
          : <span aria-current="page">내 파일함</span>}</li>
        {path.map((crumb, index) => <li key={crumb.id}>{index === path.length - 1
          ? <span aria-current="page">{crumb.name}</span>
          : <Button kind="ghost" onClick={() => navigate({ folder: crumb.id, q: "" })}>{crumb.name}</Button>}</li>)}
      </ol> : null}
    </nav>
    <div className="fb-toolbar">
      {!v.inTrash ? <>
        <div className="fb-toolbar__row">
          <Field label="검색"><input type="search" value={search} maxLength={100} placeholder="이 폴더에서 이름 찾기"
            onChange={(event) => setSearch(event.target.value)} /></Field>
          <Field label="정렬"><select value={v.sort} onChange={(event) => navigate({ sort: event.target.value, order: event.target.value === "name" ? "asc" : "desc" })}>
            {Object.entries(SORTS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select></Field>
          <Field label="순서"><select value={v.order} onChange={(event) => navigate({ order: event.target.value })}>
            <option value="desc">내림차순</option><option value="asc">오름차순</option>
          </select></Field>
        </div>
        <div className="fb-toolbar__row">
          <Field label="새 폴더"><input value={folderName} maxLength={255} placeholder="새 폴더 이름" onChange={(event) => setFolderName(event.target.value)} /></Field>
          <Button disabled={pending.has("folder")} aria-busy={pending.has("folder")} onClick={() => { void makeFolder(); }}>{pending.has("folder") ? "만드는 중…" : "폴더 만들기"}</Button>
          <Field label="파일"><input ref={picked} type="file" multiple /></Field>
          <Button kind="primary" disabled={pending.has("upload")} aria-busy={pending.has("upload")} onClick={() => { void upload(); }}>{pending.has("upload") ? "올리는 중…" : "올리기"}</Button>
        </div>
      </> : null}
    </div>
    <div className="fb-actions">
      {!selected.length ? <>
        <p className="note">{v.inTrash ? "되돌리거나 영구 삭제할 항목을 고르세요." : "항목을 고르면 옮기기, 휴지통, ZIP 받기를 할 수 있습니다."}</p>
        {v.inTrash && data?.items.length ? emptyButton : null}
      </> : <>
        <span className="tiny">{selected.length}개 선택</span>
        {v.inTrash ? <>
          <Button disabled={pending.has("restore")} aria-busy={pending.has("restore")}
            onClick={() => { void command("restore", () => restoreItems(ids), (r) => `${r.count}개를 되돌렸습니다.`); }}>{pending.has("restore") ? "되돌리는 중…" : "되돌리기"}</Button>
          <ArmedButton key={`purge:${key}:${ids.join(",")}`} label="영구 삭제" armedLabel="한 번 더 누르면 영구 삭제"
            busy={pending.has("purge")} busyLabel="지우는 중…"
            onConfirm={() => command("purge", () => purgeItems(ids), (r) => `${r.count}개를 영구 삭제했습니다. 다른 곳에 첨부한 파일은 남습니다.`)} />
          {emptyButton}
        </> : <>
          <Button aria-expanded={v.moving} onClick={() => { setPickerFolder(v.folder); update({ moving: !v.moving }); }}>옮기기</Button>
          <Button disabled={pending.has("trash")} aria-busy={pending.has("trash")}
            onClick={() => { void command("trash", () => trashItems(ids), (r) => `${r.count}개를 휴지통에 넣었습니다. 폴더 안 항목도 함께 들어갑니다.`); }}>{pending.has("trash") ? "옮기는 중…" : "휴지통으로"}</Button>
          <Button disabled={pending.has("zip") || selected.some((item) => item.kind !== "file")}
            title={selected.some((item) => item.kind !== "file") ? "폴더는 ZIP에 넣을 수 없습니다" : ""}
            aria-busy={pending.has("zip")} onClick={() => { void startZip(); }}>{pending.has("zip") ? "요청하는 중…" : "ZIP으로 받기"}</Button>
        </>}
      </>}
    </div>
    <div className="fb-picker">
      {v.moving && !v.inTrash ? <MovePicker folder={pickerFolder} moving={selected} onFolder={setPickerFolder}
        busy={pending.has("move")} onMove={(target) => command("move", () => moveItems(selected, target), (r) => `${r.count}개를 옮겼습니다.`)} /> : null}
    </div>
    <div className="fb-zip">
      {v.zip && !v.inTrash ? <ZipNotice job={v.zip} busy={pending.has("zip-download")}
        onDownload={() => { const job = current.current.zip; return job ? getZip(job) : Promise.resolve(); }} onClose={() => update({ zip: null })} /> : null}
    </div>
    <div className="fb-list" aria-live="polite">{renderList()}</div>
    <p className="acct-status" role={message.alert ? "alert" : "status"} aria-live="polite">{message.text}</p>
  </div>;
}

function ArmedButton({ label, armedLabel, busy, busyLabel, onConfirm }: {
  label: string; armedLabel: string; busy: boolean; busyLabel: string; onConfirm: () => Promise<void>;
}) {
  const [armed, setArmed] = useState(false);
  return <Button kind="danger" disabled={busy} aria-busy={busy} onClick={() => {
    if (!armed) setArmed(true); else void onConfirm();
  }}>{busy ? busyLabel : armed ? armedLabel : label}</Button>;
}

function MovePicker({ folder, moving, onFolder, busy, onMove }: {
  folder: number | null; moving: FileboxItem[]; onFolder: (folder: number | null) => void;
  busy: boolean; onMove: (folder: number | null) => Promise<void>;
}) {
  const [result, setResult] = useState<{ folder: number | null; data: Awaited<ReturnType<typeof foldersIn>> | null; error: unknown } | null>(null);
  useEffect(() => {
    let cancelled = false;
    void foldersIn(folder).then((data) => {
      if (!cancelled) setResult({ folder, data, error: null });
    }, (error: unknown) => {
      if (!cancelled) setResult({ folder, data: null, error });
    });
    return () => { cancelled = true; };
  }, [folder]);
  if (!result || result.folder !== folder) return <LoadingState title="폴더를 불러오는 중입니다" />;
  if (!result.data) return <ErrorState title="폴더를 불러오지 못했습니다" body={fileboxMessage(result.error)} />;
  const { path, folders } = result.data;
  const movingIds = new Set(moving.map((item) => item.id));
  return <div className="fb-picker__box" role="group" aria-label="옮길 폴더 고르기">
    <p className="tiny">{`옮길 곳: ${["내 파일함", ...path.map((p) => p.name)].join(" / ")}`}</p>
    {folders.length ? <ul className="fb-picker__list">{folders.map((item) => <li key={item.id}>
      <Button kind="ghost" aria-label={`${item.name} 폴더 열기`} disabled={movingIds.has(item.id)}
        title={movingIds.has(item.id) ? "옮기는 폴더입니다" : undefined} onClick={() => onFolder(item.id)}>{item.name}</Button>
    </li>)}</ul> : <p className="note">아래 폴더가 없습니다.</p>}
    <div className="btn-row">
      <Button kind="ghost" disabled={!folder} onClick={() => onFolder(path.length > 1 ? path[path.length - 2]!.id : null)}>위 폴더</Button>
      <Button kind="primary" disabled={busy} aria-busy={busy} onClick={() => { void onMove(folder); }}>{busy ? "옮기는 중…" : "여기로 옮기기"}</Button>
    </div>
  </div>;
}

function ZipNotice({ job, busy, onDownload, onClose }: {
  job: ZipJob; busy: boolean; onDownload: () => Promise<void>; onClose: () => void;
}) {
  const texts: Record<string, string> = {
    requested: "ZIP을 만들 차례를 기다리고 있습니다.",
    building: "ZIP을 만들고 있습니다.",
    ready: `ZIP이 준비되었습니다 (${sizeText(job.sizeBytes) || "크기 정보 없음"}). ${job.expiresAt ? `${isoTime(job.expiresAt)}까지` : "2시간 동안"} 받을 수 있습니다.`,
    failed: job.failure === "item_unavailable" ? "고른 파일 중 휴지통에 있거나 사라진 파일이 있어 ZIP을 만들지 못했습니다." : "ZIP을 만들지 못했습니다. 다시 요청하세요.",
    expired: "ZIP을 받을 수 있는 시간이 지났습니다. 다시 요청하세요.",
  };
  return <div className="fb-zip__box" role="status" aria-live="polite">
    <span>{`파일 ${job.itemCount}개 · ${texts[job.state] || "ZIP 상태를 확인하지 못했습니다."}`}</span>
    {job.state === "requested" || job.state === "building" ? <progress aria-label="ZIP 만드는 중" /> : null}
    {job.state === "ready" ? <Button kind="primary" disabled={busy} aria-busy={busy} onClick={() => { void onDownload(); }}>{busy ? "내려받는 중…" : "ZIP 내려받기"}</Button> : null}
    <Button kind="ghost" aria-label="ZIP 안내 닫기" onClick={onClose}>닫기</Button>
  </div>;
}

function Row({ item, inTrash, selected, draft, saving, onToggle, onOpen, onRename, onDraft, onSave, say }: {
  item: FileboxItem; inTrash: boolean; selected: boolean; draft: string | null; saving: boolean;
  onToggle: (on: boolean) => void; onOpen: () => void; onRename: () => void;
  onDraft: (name: string) => void; onSave: () => Promise<void>;
  say: (text: string, alert?: boolean) => void;
}) {
  const [downloading, setDownloading] = useState(false);
  const downloadLock = useRef(false);
  const [showRefs, setShowRefs] = useState(false);
  const [refs, setRefs] = useState<{ data: FileboxReference[] | null; error: unknown } | null>(null);
  const live = useRef(true);
  useEffect(() => {
    live.current = true;
    return () => { live.current = false; };
  }, []);
  useEffect(() => {
    if (!showRefs) return;
    let cancelled = false;
    setRefs(null);
    void itemReferences(item.id).then((data) => {
      if (!cancelled) setRefs({ data, error: null });
    }, (error: unknown) => {
      if (!cancelled) setRefs({ data: null, error });
    });
    return () => { cancelled = true; };
  }, [showRefs, item.id]);

  async function download() {
    if (downloadLock.current || item.state !== "ready") return;
    downloadLock.current = true;
    setDownloading(true);
    try { await downloadItem(item); }
    catch (error) { say(fileboxMessage(error), true); }
    finally {
      downloadLock.current = false;
      if (live.current) setDownloading(false);
    }
  }

  const meta = inTrash
    ? [item.kind === "folder" ? `폴더 · 항목 ${item.itemCount}개` : "파일", sizeText(item.sizeBytes), item.trashedAt ? `${isoTime(item.trashedAt)} 삭제` : ""].filter(Boolean).join(" · ")
    : [item.kind === "folder" ? "폴더" : (item.extension || "파일").toUpperCase(), sizeText(item.sizeBytes), isoTime(item.createdAt)].filter(Boolean).join(" · ");
  return <li className="fb-row">
    <input type="checkbox" aria-label={`${item.name} 고르기`} checked={selected} onChange={(event) => onToggle(event.target.checked)} />
    <span className="fb-row__main">
      {item.kind === "folder" && !inTrash
        ? <Button kind="ghost" className="fb-row__open" aria-label={`${item.name} 폴더 열기`} onClick={onOpen}>{item.name}</Button>
        : <b>{item.name}</b>}
      <span className="tiny">{meta}</span>
      {!inTrash && item.kind === "file" && item.state && SCAN_STATE[item.state]
        ? <Badge tone={item.state === "rejected" ? "danger" : "neutral"}>{SCAN_STATE[item.state]}</Badge> : null}
    </span>
    {!inTrash ? <>
      <span className="fb-row__tools">
        <Button kind="ghost" aria-label={`${item.name} 이름 바꾸기`} onClick={onRename}>이름 바꾸기</Button>
        {item.kind === "file" ? <>
          <Button kind="ghost" aria-label={`${item.name} 내려받기`} disabled={downloading || item.state !== "ready"}
            aria-busy={downloading} onClick={() => { void download(); }}>{downloading ? "내려받는 중…" : "내려받기"}</Button>
          <Button kind="ghost" aria-label={`${item.name}을 첨부한 곳`} aria-expanded={showRefs} onClick={() => setShowRefs(!showRefs)}>연결된 곳</Button>
        </> : null}
      </span>
      <div className="fb-row__extra">
        {showRefs ? !refs ? <p className="tiny">불러오는 중입니다.</p>
          : refs.error ? <p className="tiny" role="alert">{fileboxMessage(refs.error)}</p>
            : refs.data?.length ? <ul className="fb-refs">{refs.data.map((ref, index) => <li key={index}>
              {`${ref.courseName || "과목"} · ${(ref.purpose && PURPOSE_LABEL[ref.purpose]) || ref.purpose || "첨부"} · ${isoTime(ref.attachedAt)}`}
            </li>)}</ul> : <p className="tiny">이 파일을 첨부한 곳이 없습니다.</p> : null}
        {draft !== null ? <div className="fb-rename">
          <input autoFocus value={draft} maxLength={255} aria-label={`${item.name}의 새 이름`} onChange={(event) => onDraft(event.target.value)}
            onKeyDown={(event) => { if (event.key === "Enter") { event.preventDefault(); void onSave(); } }} />
          <Button kind="primary" disabled={saving} aria-busy={saving} onClick={() => { void onSave(); }}>{saving ? "저장하는 중…" : "저장"}</Button>
          <p className="tiny">{item.kind === "file" ? "파일 확장자는 바꿀 수 없습니다." : ""}</p>
        </div> : null}
      </div>
    </> : null}
  </li>;
}
