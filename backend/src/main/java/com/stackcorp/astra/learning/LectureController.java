package com.stackcorp.astra.learning;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;

import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.learning.LearningController.LectureItem;
import com.stackcorp.astra.learning.LectureService.NewItem;
import com.stackcorp.astra.learning.LectureService.NewLecture;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 영상(온라인강의) 활동 등록(ADR-0016). 원본 ASTRA의 교수자 등록 화면은 캡처가 없어 계약은 PROPOSED다. 학생 쪽 상세(EP-0052의 대체
 * 계약)는 GET /api/courses/{courseId}/activities/{activityId}의 lectureItems다.
 * <p>
 * 담당자만 등록한다. 콘텐츠마다 POST /api/uploads/stream으로 올린 lecture_video 업로드 하나를 지정한다. 등록하면 변환이 예약되고
 * 콘텐츠 상태는 processing이다. 변환이 끝나면 ready가 되어 재생할 수 있다. 과목의 담당자가 아니면 404로 응답한다.
 */
@RestController
class LectureController {

	private static final int MAX_TITLE = 300;

	private static final int MAX_DESCRIPTION = 10_000;

	private static final int MAX_WEEK = 60;

	private static final int MAX_ITEMS = 20;

	// 출석인정 필요 시간 상한(초). 변환 상한(4시간)과 같다.
	private static final int MAX_REQUIRED_SEC = 4 * 60 * 60;

	private final JdbcClient jdbc;

	private final LearningAccess access;

	private final LectureService lectures;

	private final RateLimiter rateLimiter;

	LectureController(JdbcClient jdbc, LearningAccess access, LectureService lectures, RateLimiter rateLimiter) {
		this.jdbc = jdbc;
		this.access = access;
		this.lectures = lectures;
		this.rateLimiter = rateLimiter;
	}

	@PostMapping("/api/courses/{courseId}/lectures")
	ResponseEntity<LectureView> create(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestBody CreateLecture body) {
		if (!this.access.canManage(actor.userId(), courseId)) {
			throw ApiException.notFound();
		}
		NewLecture lecture = validated(body);
		this.rateLimiter.check("lecture-create", actor.userId(), 30, Duration.ofMinutes(10));
		long activityId = this.lectures.create(actor.userId(), courseId, lecture);
		return ResponseEntity.status(HttpStatus.CREATED)
			.cacheControl(CacheControl.noStore())
			.body(new LectureView(activityId, lecture.weekNo(), lecture.title(), lecture.openAt(), lecture.closeAt(),
					lecture.published(), LearningController.lectureItems(this.jdbc, activityId)));
	}

	private static NewLecture validated(CreateLecture body) {
		if (body.weekNo() == null || body.weekNo() < 1 || body.weekNo() > MAX_WEEK) {
			throw ApiException.invalid("weekNo는 1 이상 " + MAX_WEEK + " 이하여야 합니다.");
		}
		String title = title(body.title());
		String description = body.description() == null || body.description().isBlank() ? null : body.description();
		if (description != null && (description.length() > MAX_DESCRIPTION || hasControl(description, true))) {
			throw ApiException.invalid("description은 " + MAX_DESCRIPTION + "자 이하여야 합니다.");
		}
		if (body.openAt() != null && body.closeAt() != null && !body.openAt().isBefore(body.closeAt())) {
			throw ApiException.invalid("closeAt은 openAt보다 뒤여야 합니다.");
		}
		List<CreateItem> items = body.items() == null ? List.of() : body.items();
		if (items.isEmpty() || items.size() > MAX_ITEMS || items.stream().anyMatch((i) -> i == null)) {
			throw ApiException.invalid("items는 1개 이상 " + MAX_ITEMS + "개 이하여야 합니다.");
		}
		HashSet<Long> uploads = new HashSet<>();
		List<NewItem> validItems = items.stream().map((item) -> {
			if (item.uploadId() == null || item.uploadId() < 1 || !uploads.add(item.uploadId())) {
				throw ApiException.invalid("uploadId가 올바르지 않습니다.");
			}
			if (item.requiredSec() != null && (item.requiredSec() < 1 || item.requiredSec() > MAX_REQUIRED_SEC)) {
				throw ApiException.invalid("requiredSec은 1 이상 " + MAX_REQUIRED_SEC + " 이하여야 합니다.");
			}
			return new NewItem(title(item.title()), item.uploadId(), item.requiredSec());
		}).toList();
		return new NewLecture(body.weekNo(), title, description, body.openAt(), body.closeAt(),
				body.published() == null || body.published(), validItems);
	}

	private static String title(String raw) {
		String title = raw == null ? "" : raw.strip();
		if (title.isEmpty() || title.length() > MAX_TITLE || hasControl(title, false)) {
			throw ApiException.invalid("title은 1자 이상 " + MAX_TITLE + "자 이하여야 합니다.");
		}
		return title;
	}

	private static boolean hasControl(String text, boolean allowLineBreaks) {
		return text.codePoints()
			.anyMatch((cp) -> Character.isISOControl(cp) && !(allowLineBreaks && (cp == '\n' || cp == '\r' || cp == '\t')));
	}

	/** 요청 본문. 작성자와 과목은 받지 않는다. session과 경로에서 정한다(SC-02). */
	record CreateLecture(Integer weekNo, String title, String description, LocalDateTime openAt, LocalDateTime closeAt,
			Boolean published, List<CreateItem> items) {
	}

	record CreateItem(String title, Long uploadId, Integer requiredSec) {
	}

	record LectureView(long activityId, int weekNo, String title, LocalDateTime openAt, LocalDateTime closeAt,
			boolean published, List<LectureItem> items) {
	}

}
