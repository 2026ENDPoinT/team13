// Notification dialog (`?pop=notify` 전체, `?pop=notify-unread` 안 읽음) from the ASTRA backend
// while the connection is `ready`; the login panel while it is `anon`. Viewing never marks a
// notification read: opening its link sends `read` and then navigates. Server text is only
// ever rendered as text.
import { useEffect, useRef, useState, type MouseEvent, type ReactNode } from "react";
import { Link, isModifiedClick, navigate } from "../../router.tsx";
import { Button, EmptyState, LoadingState, ErrorState } from "../../ui/kit.tsx";
import { currentRole, routeFor } from "../../shared/routes.ts";
import { backendStatus } from "../../shared/backend.ts";
import { useUnread } from "../../shared/unread-badges.tsx";
import { koDateTime } from "../../shared/home-remote.ts";
import {
  inboxEntry, useInbox, reloadInbox, loadMoreInbox, readNotification, readAllNotifications,
  deleteNotification, isGone, notificationHref, notificationTag, notificationMessage,
} from "../../shared/notifications-remote.ts";
import type { NotificationItem } from "../../shared/notifications-remote.ts";
import { RetryButton } from "./home-server.tsx";

interface Status {
  text: string;
  alert: boolean;
}

function Chip({ href, label, count, on }: { href: string; label: string; count?: number | null; on: boolean }) {
  return (
    <Link className={`chip all${on ? " on" : ""}`} href={href} aria-current={on ? "page" : undefined}>
      <span>{label}</span>
      {count === undefined ? null : <span className="ntf-count">{count == null ? "" : String(count)}</span>}
    </Link>
  );
}

interface RowProps {
  item: NotificationItem;
  filter: string;
  nextId: string | null;
  say: (text: string, alert?: boolean) => void;
  focusAfter: (nextId: string | null) => void;
}

function Row({ item, filter, nextId, say, focusAfter }: RowProps) {
  const [opening, setOpening] = useState(false);
  const [removing, setRemoving] = useState(false);
  const href = notificationHref(item);
  const read = Boolean(item.read);
  const tag = notificationTag(item);
  const title = `[${tag}] ${item.message || ""}`;
  const source = item.courseName ? `${item.courseName}${item.courseCode ? `(${item.courseCode})` : ""}` : tag;
  const body = (
    <>
      {read
        ? <span className="ntf-dot" aria-hidden="true" />
        : <span className="ntf-dot" role="img" aria-label="새 알림" />}
      <span className="ntf-copy">
        <b>{title}</b>
        <span className="ntf-src">{source}</span>
        <span className="ntf-when"><span>{koDateTime(item.createdAt)}</span></span>
      </span>
    </>
  );
  // A new-tab click keeps the browser default and still sends `read`.
  const readAside = () => {
    readNotification(item.notificationId).catch(() => {});
  };
  // Plain click: marks the notification read, then opens its link in place.
  const open = (event: MouseEvent<HTMLAnchorElement>, target: string) => {
    if (isModifiedClick(event)) {
      readAside();
      return;
    }
    event.preventDefault();
    if (opening) return;
    setOpening(true);
    readNotification(item.notificationId).then(() => navigate(target), (error: unknown) => {
      setOpening(false);
      // Withdrawn or no longer visible: the row is dropped and the link is not followed. Any other
      // failure (offline, rate limit) still opens the target; it stays unread on the server.
      if (isGone(error)) {
        say(notificationMessage(error), true);
        return;
      }
      navigate(target);
    });
  };
  const remove = () => {
    if (removing) return;
    setRemoving(true);
    deleteNotification(item.notificationId).then(() => {
      say("알림을 삭제했습니다.");
      focusAfter(nextId);
    }, (error: unknown) => {
      if (isGone(error)) {
        say(notificationMessage(error), true);
        focusAfter(nextId);
        return;
      }
      setRemoving(false);
      say(notificationMessage(error), true);
    });
  };
  return (
    <li className="ntf-item" data-id={String(item.notificationId)} data-filter={filter}>
      {href
        ? (
          <a
            className={`ntf-row${read ? " read" : ""}`}
            href={href}
            data-id={String(item.notificationId)}
            aria-busy={opening ? "true" : undefined}
            onClick={(event) => open(event, href)}
            onAuxClick={(event) => {
              if (event.button === 1) readAside();
            }}
          >
            {body}
          </a>
        )
        : <div className={`ntf-row${read ? " read" : ""}`} data-id={String(item.notificationId)}>{body}</div>}
      <Button kind="ghost" className="ntf-del" aria-label={`알림 삭제: ${title}`} disabled={removing} aria-busy={removing ? "true" : undefined} onClick={remove}>
        {removing ? "삭제 중…" : "삭제"}
      </Button>
    </li>
  );
}

/** Body of the notification dialog for `name` ("notify" | "notify-unread"). */
export function serverNotifications(name: string): ReactNode {
  if (backendStatus() !== "ready") {
    return <div className="ntf"></div>;
  }
  const filter = name === "notify-unread" ? "unread" : "all";
  return <ServerNotifications key={filter} filter={filter} />;
}

function ServerNotifications({ filter }: { filter: "all" | "unread" }) {
  const box = useRef<HTMLDivElement>(null);
  const statusRef = useRef<HTMLParagraphElement>(null);
  const entry = useInbox(filter);
  const unread = useUnread("notifications");
  const [status, setStatus] = useState<Status | null>(null);
  const [readingAll, setReadingAll] = useState(false);
  // After a row is removed the list repaints; focus moves to the next row (or the status line).
  const [focus, setFocus] = useState<{ nextId: string | null } | null>(null);
  useEffect(() => {
    if (!focus) return;
    setFocus(null);
    const { nextId } = focus;
    const target = nextId
      ? box.current?.querySelector<HTMLElement>(`li[data-id="${CSS.escape(nextId)}"] .ntf-row, li[data-id="${CSS.escape(nextId)}"] .ntf-del`)
      : null;
    if (target) target.focus();
    else statusRef.current?.focus();
  }, [focus]);

  const data = entry && entry.data;
  const unreadShown = unread ?? (data ? data.unreadCount : null) ?? null;
  const allTotal = inboxEntry("all")?.data?.total ?? null;
  const say = (text: string, alert = false) => setStatus({ text, alert });
  const focusAfter = (nextId: string | null) => setFocus({ nextId });
  const shownStatus = data && entry.error ? { text: notificationMessage(entry.error), alert: true } : status;

  const readAll = () => {
    if (readingAll) return;
    setReadingAll(true);
    readAllNotifications().then((result) => {
      setReadingAll(false);
      say(result && "updated" in result && result.updated ? `알림 ${result.updated}건을 읽음으로 표시했습니다.` : "읽지 않은 알림이 없습니다.");
    }, (error: unknown) => {
      setReadingAll(false);
      say(notificationMessage(error), true);
    });
  };

  let slot: ReactNode;
  if (!data) {
    slot = entry && entry.error
      ? <ErrorState title="알림을 불러오지 못했습니다" body={notificationMessage(entry.error)} actions={<RetryButton reload={() => reloadInbox(filter)} />} />
      : <LoadingState title="알림을 불러오는 중입니다" />;
  } else if (data.items.length) {
    slot = (
      <ul className="ntf-list">
        {data.items.map((item, index) => {
          const next = data.items[index + 1];
          return (
            <Row
              key={item.notificationId}
              item={item}
              filter={filter}
              nextId={next ? String(next.notificationId) : null}
              say={say}
              focusAfter={focusAfter}
            />
          );
        })}
      </ul>
    );
  } else {
    slot = <EmptyState title={filter === "unread" ? "읽지 않은 알림이 없습니다" : "받은 알림이 없습니다"} />;
  }
  const loading = Boolean(entry && entry.loading);
  return (
    <div ref={box} className="ntf">
      <nav className="filters ntf-filters" aria-label="알림 보기">
        <Chip href={routeFor(currentRole(), "pop", { pop: "notify" })} label="전체" count={allTotal} on={filter === "all"} />
        <Chip href={routeFor(currentRole(), "notify", { pop: "notify-unread" })} label="안 읽음" count={unreadShown} on={filter === "unread"} />
      </nav>
      <div className="ntf-top">
        <p className="note">60일 지난 알림 내용은 자동 삭제됩니다.</p>
        <Button kind="secondary" disabled={readingAll || unreadShown === 0} aria-busy={readingAll ? "true" : undefined} onClick={readAll}>
          {readingAll ? "처리 중…" : "모두 읽음"}
        </Button>
      </div>
      <p ref={statusRef} className="task-empty" role={shownStatus?.alert ? "alert" : "status"} aria-live="polite" tabIndex={-1}>{shownStatus?.text}</p>
      <div>{slot}</div>
      <div className="btn-row ntf-more">
        {data && data.nextCursor
          ? (
            <Button kind="secondary" disabled={loading} aria-busy={loading ? "true" : undefined} onClick={() => void loadMoreInbox(filter)}>
              {loading ? "불러오는 중…" : "더 보기"}
            </Button>
          )
          : null}
      </div>
    </div>
  );
}
