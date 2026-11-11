package com.stackcorp.astra.catalog;

import com.stackcorp.astra.catalog.NotificationService.Inbox;
import com.stackcorp.astra.catalog.NotificationService.ReadAllResult;
import com.stackcorp.astra.catalog.NotificationService.ReadResult;
import com.stackcorp.astra.catalog.NotificationService.UnreadCount;
import com.stackcorp.astra.identity.api.Actor;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 알림함(화면 04). EP-0281·EP-0282(목록), EP-0138(안 읽은 수), EP-0280(연결과 읽음)의 대체 계약이다.
 * 사용자는 session principal로만 정한다. 조회는 읽음을 바꾸지 않고, 읽음은 명시적 POST 명령이다(SC-04, CAT-08).
 */
@RestController
class NotificationController {

	private final NotificationService notifications;

	NotificationController(NotificationService notifications) {
		this.notifications = notifications;
	}

	/**
	 * 최신순 목록. nextCursor로 다음 쪽을 받는다. 쪽 사이에 알림이 새로 생기거나 지워져도 항목이 겹치거나 빠지지 않는다(CAT-06).
	 * total은 같은 조건(과목, 유형, 안 읽음)에 맞는 전체 수다.
	 * @param courseId 이 과목의 알림만
	 * @param kind 이 유형만(NotificationKind 이름)
	 * @param unread true면 안 읽은 알림만(EP-0281 UNREAD_YN)
	 */
	@GetMapping("/api/me/notifications")
	ResponseEntity<Inbox> list(@AuthenticationPrincipal Actor actor, @RequestParam(required = false) Long courseId,
			@RequestParam(required = false) String kind, @RequestParam(defaultValue = "false") boolean unread,
			@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "" + NotificationService.DEFAULT_PAGE) int size) {
		return ok(this.notifications.inbox(actor.userId(), courseId, kind, unread, cursor, size));
	}

	/** 헤더 배지. 목록의 안 읽음 필터와 같은 조건이다. 주기적으로 불러도 쿼리 두 번(시계, 건수)이다. */
	@GetMapping("/api/me/notifications/unread-count")
	ResponseEntity<UnreadCount> unreadCount(@AuthenticationPrincipal Actor actor) {
		return ok(this.notifications.unreadCount(actor.userId()));
	}

	/** 읽음 표시와 원문 연결 정보. 몇 번 보내도 같다. */
	@PostMapping("/api/me/notifications/{notificationId}/read")
	ResponseEntity<ReadResult> read(@AuthenticationPrincipal Actor actor, @PathVariable long notificationId) {
		return ok(this.notifications.markRead(actor, notificationId));
	}

	@PostMapping("/api/me/notifications/read-all")
	ResponseEntity<ReadAllResult> readAll(@AuthenticationPrincipal Actor actor) {
		return ok(this.notifications.markAllRead(actor));
	}

	@DeleteMapping("/api/me/notifications/{notificationId}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal Actor actor, @PathVariable long notificationId) {
		this.notifications.delete(actor, notificationId);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	private static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

}
