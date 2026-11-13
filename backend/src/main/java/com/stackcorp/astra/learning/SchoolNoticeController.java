package com.stackcorp.astra.learning;

import com.stackcorp.astra.identity.api.Actor;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 학교 강의실 공지의 상세(ADR-0043). 목록은 서비스 공지 목록({@code GET /api/courses/{courseId}/notices})에 {@code "school-<id>"}로
 * 나온다. 이 경로는 그 학생의 학교 session으로 학교 상세를 읽는다(학교에서 그 학생이 읽은 것으로 바뀐다). 읽음·확인 명령
 * ({@code POST …/read}, {@code …/confirm})은 학교 공지에 없다.
 */
@RestController
class SchoolNoticeController {

	private final SchoolNotices notices;

	SchoolNoticeController(SchoolNotices notices) {
		this.notices = notices;
	}

	@GetMapping("/api/courses/{courseId}/notices/school-{schoolNoticeId}")
	ResponseEntity<SchoolNotices.SchoolNoticeView> notice(@AuthenticationPrincipal Actor actor,
			@PathVariable long courseId, @PathVariable long schoolNoticeId) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.notices.open(actor, courseId, schoolNoticeId));
	}

}
