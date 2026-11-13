package com.stackcorp.astra.learning;

import com.stackcorp.astra.identity.api.Actor;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 학교 e-Class 과제의 상세와 제출 거절(ADR-0043). 목록은 서비스 과제 목록({@code GET /api/courses/{courseId}/assignments})과 할 일
 * ({@code GET /api/me/todo})에 {@code "school-<id>"}로 나온다. 상세는 그 학생의 학교 session으로 학교 상세를 읽는다. 서비스의 제출 경로에
 * 학교 ID를 보내면 409 {@code school_submit_at_school}이다(STack은 학교에 제출하지 않는다).
 */
@RestController
class SchoolAssignmentController {

	private final SchoolAssignments assignments;

	private final LearningAccess access;

	SchoolAssignmentController(SchoolAssignments assignments, LearningAccess access) {
		this.assignments = assignments;
		this.access = access;
	}

	@GetMapping("/api/courses/{courseId}/assignments/school-{schoolActivityId}")
	ResponseEntity<SchoolAssignments.SchoolAssignmentView> assignment(@AuthenticationPrincipal Actor actor,
			@PathVariable long courseId, @PathVariable long schoolActivityId) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.assignments.open(actor, courseId, schoolActivityId, this.access.now()));
	}

	@PostMapping("/api/courses/{courseId}/assignments/school-{schoolActivityId}/submission")
	ResponseEntity<Void> submit(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long schoolActivityId) {
		throw this.assignments.refuseSubmit(actor, courseId, schoolActivityId, SchoolAssignments.REPORT);
	}

	@PutMapping("/api/courses/{courseId}/assignments/school-{schoolActivityId}/submission/draft")
	ResponseEntity<Void> draft(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long schoolActivityId) {
		throw this.assignments.refuseSubmit(actor, courseId, schoolActivityId, SchoolAssignments.REPORT);
	}

	/** 학교 시험·퀴즈({@code test})는 STack에서 응시하지 않는다. */
	@PostMapping("/api/courses/{courseId}/quizzes/school-{schoolActivityId}/attempts")
	ResponseEntity<Void> attempt(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long schoolActivityId) {
		throw this.assignments.refuseSubmit(actor, courseId, schoolActivityId, "test");
	}

	/** 학교 설문({@code survey})은 STack에서 답하지 않는다. */
	@PostMapping("/api/courses/{courseId}/surveys/school-{schoolActivityId}/completion")
	ResponseEntity<Void> survey(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long schoolActivityId) {
		throw this.assignments.refuseSubmit(actor, courseId, schoolActivityId, "survey");
	}

}
