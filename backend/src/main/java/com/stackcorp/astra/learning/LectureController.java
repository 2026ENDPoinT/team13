package com.stackcorp.astra.learning;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.learning.LearningController.LectureItem;
import com.stackcorp.astra.learning.LectureService.KeptItem;
import com.stackcorp.astra.learning.LectureService.LectureChange;
import com.stackcorp.astra.learning.LectureService.NewItem;
import com.stackcorp.astra.learning.LectureService.NewLecture;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.audit.AuditLog;
import com.stackcorp.astra.shared.idempotency.Idempotency;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 영상(온라인강의) 활동 등록, 수정, 삭제(ADR-0016, ADR-0011). 원본 ASTRA의 교수자 등록 화면은 캡처가 없어 계약은 PROPOSED다.
 * 학생 쪽 상세(EP-0052의 대체 계약)는 GET /api/courses/{courseId}/activities/{activityId}의 lectureItems다.
 * <p>
 * 담당자만 쓴다. 콘텐츠마다 POST /api/uploads/stream으로 올린 lecture_video 업로드 하나를 지정한다. 등록하면 변환이 예약되고
 * 콘텐츠 상태는 processing이다. 변환이 끝나면 ready가 되어 재생할 수 있다. 수정과 삭제는 If-Match로 마지막으로 읽은 version을
 * 보내야 하고 다르면 409다(SC-05). 과목의 담당자가 아니거나 지운 활동이면 404로 응답한다.
 */
@RestController
class LectureController {

	private static final int MAX_TITLE = 300;

	private static final int MAX_DESCRIPTION = 10_000;

	private static final int MAX_WEEK = 60;

	private static final int MAX_ITEMS = 20;

	// 출석인정 필요 시간 상한(초). 변환 상한(4시간)과 같다.
	private static final int MAX_REQUIRED_SEC = 4 * 60 * 60;

	private static final int MAX_PAGE = 100;

	// 목록 cursor: 주차.주차 안 순서.활동 ID
	private static final Pattern CURSOR = Pattern.compile("([0-9]{1,9})\\.([0-9]{1,9})\\.([1-9][0-9]{0,17})");

	private final JdbcClient jdbc;

	private final LearningAccess access;

	private final LectureService lectures;

	private final RateLimiter rateLimiter;

	private final Idempotency idempotency;

	private final AuditLog audit;

	LectureController(JdbcClient jdbc, LearningAccess access, LectureService lectures, RateLimiter rateLimiter,
			Idempotency idempotency, AuditLog audit) {
		this.jdbc = jdbc;
		this.access = access;
		this.lectures = lectures;
		this.rateLimiter = rateLimiter;
		this.idempotency = idempotency;
		this.audit = audit;
	}

	/** {@code Idempotency-Key}를 보내면 같은 key의 재전송은 처음 응답을 다시 돌려받고 활동을 더 만들지 않는다(SC-05). */
	@PostMapping("/api/courses/{courseId}/lectures")
	ResponseEntity<LectureView> create(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestBody CreateLecture body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		if (!this.access.canManage(actor.userId(), courseId)) {
			throw ApiException.notFound();
		}
		NewLecture lecture = validated(body);
		this.rateLimiter.check("lecture-create", actor.userId(), 30, Duration.ofMinutes(10));
		LectureView created = this.idempotency.execute(actor.userId(), "lecture.create", idempotencyKey,
				List.of(courseId, lecture), LectureView.class, () -> {
					long activityId = this.lectures.create(actor.userId(), courseId, lecture);
					this.audit.success(actor.userId(), actor.sessionId(), "lecture.create", "learning_activity", activityId,
							courseId);
					return view(activityId);
				});
		return ResponseEntity.status(HttpStatus.CREATED)
			.cacheControl(CacheControl.noStore())
			.eTag(LearningController.etag(created.version()))
			.body(created);
	}

	/**
	 * 영상 활동 수정. 본문은 수정한 뒤의 전체 상태이고 콘텐츠는 최종 집합(keep: 이 활동의 콘텐츠, add: 내 영상 업로드)이다(SC-05-B).
	 * 집합에서 빠진 콘텐츠는 바로 재생할 수 없고 그 영상은 지워진다.
	 */
	@PutMapping("/api/courses/{courseId}/lectures/{activityId}")
	ResponseEntity<LectureView> update(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long activityId, @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@RequestBody UpdateLecture body) {
		if (!this.access.canManage(actor.userId(), courseId)) {
			throw ApiException.notFound();
		}
		int version = LearningController.expectedVersion(ifMatch);
		LectureChange change = validated(body);
		this.rateLimiter.check("lecture-update", actor.userId(), 60, Duration.ofMinutes(10));
		this.lectures.update(actor.userId(), courseId, activityId, version, change);
		this.audit.success(actor.userId(), actor.sessionId(), "lecture.update", "learning_activity", activityId, courseId);
		LectureView updated = view(activityId);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.eTag(LearningController.etag(updated.version()))
			.body(updated);
	}

	/** 영상 활동 삭제. 삭제 표시만 남기고 학습 기록은 지우지 않는다. 영상은 지운다(ADR-0011). If-Match는 수정과 같다. */
	@DeleteMapping("/api/courses/{courseId}/lectures/{activityId}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long activityId, @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
		if (!this.access.canManage(actor.userId(), courseId)) {
			throw ApiException.notFound();
		}
		this.lectures.delete(actor.userId(), courseId, activityId, LearningController.expectedVersion(ifMatch));
		this.audit.success(actor.userId(), actor.sessionId(), "lecture.delete", "learning_activity", activityId, courseId);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	/**
	 * 담당자의 영상 활동 목록. 주차, 주차 안 순서, 활동 ID 순이고 공개 여부와 시작 일시와 관계없이 모두 보인다. 콘텐츠마다 영상 처리 상태가
	 * 있다. 쪽 크기는 1~{@value #MAX_PAGE}(기본 20)이고 다음 쪽은 nextCursor를 cursor로 보낸다. 과목과 콘텐츠 수와 관계없이 권한 판정
	 * 뒤 쿼리 두 번(활동, 콘텐츠)이다. 학생 쪽은 같은 순서의 {@code GET …/lecture-progress}가 목록이다.
	 */
	@GetMapping("/api/courses/{courseId}/lectures")
	ResponseEntity<LecturePage> list(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int size) {
		if (!this.access.canManage(actor.userId(), courseId)) {
			throw ApiException.notFound();
		}
		if (size < 1 || size > MAX_PAGE) {
			throw ApiException.invalid("size는 1 이상 " + MAX_PAGE + " 이하여야 합니다.");
		}
		Long[] after = cursor(cursor);
		List<LectureRow> rows = this.jdbc.sql("""
				SELECT a.activity_id, w.week_no, a.sort_order, a.title, a.open_at, a.close_at, a.published_yn, a.row_version
				  FROM learning_activities a JOIN course_weeks w ON w.course_week_id = a.course_week_id
				 WHERE a.course_id = :course AND a.activity_type = 'lecture_weeks' AND a.deleted_at IS NULL
				   AND (:week IS NULL OR (w.week_no, a.sort_order, a.activity_id) > (:week, :order, :activity))
				 ORDER BY w.week_no, a.sort_order, a.activity_id
				 LIMIT :limit
				""")
			.param("course", courseId)
			.param("week", after[0])
			.param("order", after[1])
			.param("activity", after[2])
			.param("limit", size + 1)
			.query((rs, n) -> new LectureRow(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getString(4),
					rs.getObject(5, LocalDateTime.class), rs.getObject(6, LocalDateTime.class), rs.getBoolean(7),
					rs.getInt(8)))
			.list();
		List<LectureRow> page = rows.size() > size ? rows.subList(0, size) : rows;
		Map<Long, List<LectureItem>> items = items(page.stream().map(LectureRow::activityId).toList());
		String next = null;
		if (rows.size() > size) {
			LectureRow last = page.getLast();
			next = last.weekNo() + "." + last.sortOrder() + "." + last.activityId();
		}
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(new LecturePage(page.stream()
				.map((r) -> new LectureView(r.activityId(), r.weekNo(), r.title(), r.openAt(), r.closeAt(), r.published(),
						items.getOrDefault(r.activityId(), List.of()), r.version()))
				.toList(), next));
	}

	// 여러 활동의 지우지 않은 콘텐츠를 한 번에 읽는다. 상태 규칙은 상세(LearningController.lectureItems)와 같다.
	private Map<Long, List<LectureItem>> items(List<Long> activityIds) {
		Map<Long, List<LectureItem>> items = new HashMap<>();
		if (activityIds.isEmpty()) {
			return items;
		}
		this.jdbc.sql("""
				SELECT activity_id, online_lecture_item_id, title, duration_sec, required_sec, content_path, video_state
				  FROM online_lecture_items WHERE activity_id IN (:activities) AND deleted_at IS NULL
				 ORDER BY activity_id, link_seq
				""").param("activities", activityIds).query((rs) -> {
			items.computeIfAbsent(rs.getLong(1), (id) -> new ArrayList<>())
				.add(new LectureItem(rs.getLong(2), rs.getString(3), rs.getObject(4, Integer.class),
						rs.getObject(5, Integer.class), LectureItem.state(rs.getString(6), rs.getString(7))));
		});
		return items;
	}

	/** @return {week, sortOrder, activityId}. 처음이면 모두 null */
	private static Long[] cursor(String cursor) {
		if (cursor == null) {
			return new Long[3];
		}
		Matcher m = CURSOR.matcher(cursor);
		if (!m.matches()) {
			throw ApiException.invalid("cursor 값이 올바르지 않습니다.");
		}
		return new Long[] { Long.valueOf(m.group(1)), Long.valueOf(m.group(2)), Long.valueOf(m.group(3)) };
	}

	private LectureView view(long activityId) {
		return this.jdbc.sql("""
				SELECT a.activity_id, w.week_no, a.title, a.open_at, a.close_at, a.published_yn, a.row_version
				  FROM learning_activities a JOIN course_weeks w ON w.course_week_id = a.course_week_id
				 WHERE a.activity_id = :activity AND a.deleted_at IS NULL
				""")
			.param("activity", activityId)
			.query((rs, n) -> new LectureView(rs.getLong(1), rs.getInt(2), rs.getString(3),
					rs.getObject(4, LocalDateTime.class), rs.getObject(5, LocalDateTime.class), rs.getBoolean(6),
					LearningController.lectureItems(this.jdbc, activityId), rs.getInt(7)))
			.single();
	}

	private static NewLecture validated(CreateLecture body) {
		Fields fields = fields(body.weekNo(), body.title(), body.description(), body.openAt(), body.closeAt());
		List<CreateItem> items = body.items() == null ? List.of() : body.items();
		if (items.isEmpty() || items.size() > MAX_ITEMS) {
			throw ApiException.invalid("items는 1개 이상 " + MAX_ITEMS + "개 이하여야 합니다.");
		}
		return new NewLecture(fields.weekNo(), fields.title(), fields.description(), body.openAt(), body.closeAt(),
				body.published() == null || body.published(), newItems(items));
	}

	private static LectureChange validated(UpdateLecture body) {
		if (body.published() == null) {
			throw ApiException.invalid("published 값이 필요합니다.");
		}
		if (body.items() == null) {
			throw ApiException.invalid("items 값이 필요합니다.");
		}
		Fields fields = fields(body.weekNo(), body.title(), body.description(), body.openAt(), body.closeAt());
		List<KeepItem> keep = body.items().keep() == null ? List.of() : body.items().keep();
		List<CreateItem> add = body.items().add() == null ? List.of() : body.items().add();
		if (keep.size() + add.size() == 0 || keep.size() + add.size() > MAX_ITEMS) {
			throw ApiException.invalid("콘텐츠는 1개 이상 " + MAX_ITEMS + "개 이하여야 합니다.");
		}
		Set<Long> ids = new HashSet<>();
		List<KeptItem> kept = keep.stream().map((item) -> {
			if (item == null || item.itemId() == null || item.itemId() < 1 || !ids.add(item.itemId())) {
				throw ApiException.invalid("items.keep이 올바르지 않습니다.");
			}
			return new KeptItem(item.itemId(), title(item.title()), requiredSec(item.requiredSec()));
		}).toList();
		return new LectureChange(fields.weekNo(), fields.title(), fields.description(), body.openAt(), body.closeAt(),
				body.published(), kept, newItems(add));
	}

	private static Fields fields(Integer weekNo, String rawTitle, String rawDescription, LocalDateTime openAt,
			LocalDateTime closeAt) {
		if (weekNo == null || weekNo < 1 || weekNo > MAX_WEEK) {
			throw ApiException.invalid("weekNo는 1 이상 " + MAX_WEEK + " 이하여야 합니다.");
		}
		String title = title(rawTitle);
		String description = rawDescription == null || rawDescription.isBlank() ? null : rawDescription;
		if (description != null && (description.length() > MAX_DESCRIPTION || hasControl(description, true))) {
			throw ApiException.invalid("설명은 " + MAX_DESCRIPTION + "자 이하의 일반 텍스트여야 합니다.");
		}
		if (openAt != null && closeAt != null && !openAt.isBefore(closeAt)) {
			throw ApiException.invalid("마감 일시는 시작 일시보다 뒤여야 합니다.");
		}
		return new Fields(weekNo, title, description);
	}

	private static List<NewItem> newItems(List<CreateItem> items) {
		Set<Long> uploads = new HashSet<>();
		return items.stream().map((item) -> {
			if (item == null || item.uploadId() == null || item.uploadId() < 1 || !uploads.add(item.uploadId())) {
				throw ApiException.invalid("uploadId가 올바르지 않습니다.");
			}
			return new NewItem(title(item.title()), item.uploadId(), requiredSec(item.requiredSec()));
		}).toList();
	}

	private static Integer requiredSec(Integer requiredSec) {
		if (requiredSec != null && (requiredSec < 1 || requiredSec > MAX_REQUIRED_SEC)) {
			throw ApiException.invalid("requiredSec은 1 이상 " + MAX_REQUIRED_SEC + " 이하여야 합니다.");
		}
		return requiredSec;
	}

	private static String title(String raw) {
		String title = raw == null ? "" : raw.strip();
		if (title.isEmpty() || title.length() > MAX_TITLE || hasControl(title, false)) {
			throw ApiException.invalid("제목은 1자 이상 " + MAX_TITLE + "자 이하의 한 줄 글이어야 합니다.");
		}
		return title;
	}

	private static boolean hasControl(String text, boolean allowLineBreaks) {
		return text.codePoints()
			.anyMatch((cp) -> Character.isISOControl(cp) && !(allowLineBreaks && (cp == '\n' || cp == '\r' || cp == '\t')));
	}

	private record Fields(int weekNo, String title, String description) {
	}

	/** 요청 본문. 작성자와 과목은 받지 않는다. session과 경로에서 정한다(SC-02). */
	record CreateLecture(Integer weekNo, String title, String description, LocalDateTime openAt, LocalDateTime closeAt,
			Boolean published, List<CreateItem> items) {
	}

	record CreateItem(String title, Long uploadId, Integer requiredSec) {
	}

	/** 수정 요청 본문. 모든 값을 보낸다. 빠진 선택 값(description, openAt, closeAt, requiredSec)은 비운다는 뜻이다. */
	record UpdateLecture(Integer weekNo, String title, String description, LocalDateTime openAt, LocalDateTime closeAt,
			Boolean published, ItemSet items) {
	}

	/**
	 * @param keep 남길 콘텐츠. 이 활동의 콘텐츠만 된다
	 * @param add 새로 더할 콘텐츠. 내가 이 과목의 강의 영상으로 올린 업로드만 된다
	 */
	record ItemSet(List<KeepItem> keep, List<CreateItem> add) {
	}

	record KeepItem(Long itemId, String title, Integer requiredSec) {
	}

	/**
	 * @param version 수정·삭제할 때 If-Match로 보내는 값
	 */
	record LectureView(long activityId, int weekNo, String title, LocalDateTime openAt, LocalDateTime closeAt,
			boolean published, List<LectureItem> items, int version) {
	}

	/** @param nextCursor 다음 쪽이 있으면 그 cursor, 없으면 null */
	record LecturePage(List<LectureView> items, String nextCursor) {
	}

	private record LectureRow(long activityId, int weekNo, int sortOrder, String title, LocalDateTime openAt,
			LocalDateTime closeAt, boolean published, int version) {
	}

}
