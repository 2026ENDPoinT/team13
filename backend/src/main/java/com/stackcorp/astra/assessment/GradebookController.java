package com.stackcorp.astra.assessment;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.stackcorp.astra.assessment.GradebookService.Gradebook;
import com.stackcorp.astra.assessment.GradebookService.ItemInput;
import com.stackcorp.astra.assessment.GradebookService.ItemSheet;
import com.stackcorp.astra.assessment.GradebookService.ItemUpdate;
import com.stackcorp.astra.assessment.GradebookService.ItemView;
import com.stackcorp.astra.assessment.GradebookService.ScoreRow;
import com.stackcorp.astra.assessment.GradebookService.ScoreSheet;
import com.stackcorp.astra.assessment.GradebookService.SettingsView;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.idempotency.Idempotency;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 성적부(ADR-0023). 학생 성적 화면은 EP-0035, EP-0036의 대체 계약이다. 담당자 성적부는 원본 캡처에 없는 화면이라 대응 EP가 없다.
 * 과목은 경로로 명시한다(SC-03). 작성자와 채점자는 session에서 정하고, 본문의 studentId는 점수를 줄 대상이며 그 과목 수강생이어야
 * 한다(SC-02). 학생 경로에는 대상 학생을 고르는 parameter가 없다. 수정은 읽은 revision 또는 version을 보내고, 다르면 409다.
 */
@RestController
class GradebookController {

	private final GradebookService service;

	private final RateLimiter rateLimiter;

	private final Idempotency idempotency;

	private final SchoolMyGrades school;

	GradebookController(GradebookService service, RateLimiter rateLimiter, Idempotency idempotency,
			SchoolMyGrades school) {
		this.service = service;
		this.rateLimiter = rateLimiter;
		this.idempotency = idempotency;
		this.school = school;
	}

	/**
	 * 학생 본인 성적. 학교에서 읽는 과목(ADR-0043)의 학생에게는 학생 본인의 학교 성적 화면을 그 학생의 학교 session으로 읽어 같은
	 * 모양으로 준다({@code origin: "school"}).
	 */
	@GetMapping("/api/courses/{courseId}/grades/me")
	ResponseEntity<?> mine(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestParam(required = false) String type, @RequestParam(required = false) String q) {
		Optional<SchoolMyGrades.View> school = this.school.of(actor, courseId, type, q);
		if (school.isPresent()) {
			return ok(school.get());
		}
		return ok(this.service.mine(actor.userId(), courseId, type, q));
	}

	@GetMapping("/api/courses/{courseId}/gradebook")
	ResponseEntity<Gradebook> gradebook(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size) {
		return ok(this.service.gradebook(actor.userId(), courseId, page, size));
	}

	/** 성적부의 항목 하나와 그 항목의 학생별 점수. 오프라인 점수표처럼 항목 하나만 볼 때 성적부 전체를 쪽마다 읽지 않는다. */
	@GetMapping("/api/courses/{courseId}/gradebook/items/{itemId}")
	ResponseEntity<ItemSheet> item(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long itemId, @RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "50") int size) {
		return ok(this.service.itemSheet(actor.userId(), courseId, itemId, page, size));
	}

	@PutMapping("/api/courses/{courseId}/grade-settings")
	ResponseEntity<SettingsView> settings(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestBody SettingsRequest body) {
		this.service.requireInstructor(actor.userId(), courseId);
		if (body.activitiesVisible() == null || body.revision() == null || body.revision() < 0) {
			throw ApiException.invalid("activitiesVisible과 revision을 보내야 합니다.");
		}
		limitWrites(actor);
		return ok(this.service.updateSettings(actor, courseId, body.activitiesVisible(), body.revision()));
	}

	@PostMapping("/api/courses/{courseId}/grade-items")
	ResponseEntity<ItemView> create(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestBody ItemRequest body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		this.service.requireInstructor(actor.userId(), courseId);
		if (body.revision() != null) {
			throw ApiException.invalid("revision은 수정할 때만 보냅니다.");
		}
		ItemInput input = new ItemInput(name(body.name()), maxScore(body.maxScore()), weight(body.weight()));
		limitWrites(actor);
		ItemView created = this.idempotency.execute(actor.userId(), "grade_item.create", idempotencyKey,
				List.of(courseId, input), ItemView.class, () -> this.service.create(actor, courseId, input));
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(created);
	}

	@PutMapping("/api/courses/{courseId}/grade-items/{itemId}")
	ResponseEntity<ItemView> update(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long itemId, @RequestBody ItemRequest body) {
		this.service.requireInstructor(actor.userId(), courseId);
		ItemUpdate input = new ItemUpdate(body.name() == null ? null : name(body.name()),
				body.maxScore() == null ? null : maxScore(body.maxScore()), weight(body.weight()), revision(body.revision()));
		limitWrites(actor);
		return ok(this.service.update(actor, courseId, itemId, input));
	}

	@PutMapping("/api/courses/{courseId}/grade-items/{itemId}/release")
	ResponseEntity<ItemView> release(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long itemId, @RequestBody ReleaseRequest body) {
		this.service.requireInstructor(actor.userId(), courseId);
		if (body.released() == null) {
			throw ApiException.invalid("released를 보내야 합니다.");
		}
		long revision = revision(body.revision());
		limitWrites(actor);
		return ok(this.service.release(actor, courseId, itemId, body.released(), revision));
	}

	@DeleteMapping("/api/courses/{courseId}/grade-items/{itemId}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long itemId, @RequestParam(required = false) Long revision) {
		this.service.requireInstructor(actor.userId(), courseId);
		long checked = revision(revision);
		limitWrites(actor);
		this.service.delete(actor, courseId, itemId, checked);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	@PutMapping("/api/courses/{courseId}/grade-items/{itemId}/scores")
	ResponseEntity<ScoreSheet> scores(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long itemId, @RequestBody ScoresRequest body) {
		this.service.requireInstructor(actor.userId(), courseId);
		if (body.rows() == null || body.rows().isEmpty() || body.rows().size() > GradebookService.MAX_ROWS) {
			throw ApiException.invalid("rows는 1개 이상 " + GradebookService.MAX_ROWS + "개 이하여야 합니다.");
		}
		this.rateLimiter.check("grade-scores", actor.userId(), 60, Duration.ofMinutes(10));
		return ok(this.service.putScores(actor, courseId, itemId, body.rows()));
	}

	/**
	 * 오프라인 시험의 점수 일괄 저장. 본문과 오류는 직접 입력 항목의 점수 일괄 저장과 같다. 학생 공개는 퀴즈의 결과 공개 일시가 정한다.
	 * 온라인 시험은 409 {@code online_quiz}다.
	 */
	@PutMapping("/api/courses/{courseId}/quizzes/{quizId}/scores")
	ResponseEntity<ScoreSheet> quizScores(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long quizId, @RequestBody ScoresRequest body) {
		this.service.requireInstructor(actor.userId(), courseId);
		if (body.rows() == null || body.rows().isEmpty() || body.rows().size() > GradebookService.MAX_ROWS) {
			throw ApiException.invalid("rows는 1개 이상 " + GradebookService.MAX_ROWS + "개 이하여야 합니다.");
		}
		this.rateLimiter.check("grade-scores", actor.userId(), 60, Duration.ofMinutes(10));
		return ok(this.service.putQuizScores(actor, courseId, quizId, body.rows()));
	}

	/** 일괄 저장 거부. 틀린 행의 순번과 이유 코드를 rows에 담는다. */
	@ExceptionHandler(ScoreRowsRejected.class)
	ResponseEntity<ProblemDetail> rejected(ScoreRowsRejected ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
		problem.setProperty("code", ex.code());
		problem.setProperty("rows", ex.rows());
		return ResponseEntity.status(ex.status()).cacheControl(CacheControl.noStore()).body(problem);
	}

	private void limitWrites(Actor actor) {
		this.rateLimiter.check("gradebook-write", actor.userId(), 120, Duration.ofMinutes(10));
	}

	private static String name(String value) {
		return PlainText.line(value, GradebookService.MAX_NAME, "항목 이름");
	}

	private static BigDecimal maxScore(BigDecimal value) {
		BigDecimal max = PlainText.points(value, GradebookService.MAX_SCORE, "배점");
		if (max.signum() == 0) {
			throw ApiException.invalid("배점은 0보다 커야 합니다.");
		}
		return max;
	}

	private static BigDecimal weight(BigDecimal value) {
		return value == null ? null : PlainText.points(value, GradebookService.MAX_WEIGHT, "반영 비율");
	}

	private static long revision(Long value) {
		if (value == null || value < 1) {
			throw ApiException.invalid("revision은 반드시 보내야 합니다.");
		}
		return value;
	}

	private static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	/**
	 * @param activitiesVisible 학생에게 평가 활동 목록을 보일지
	 * @param revision 읽은 설정 수정 번호. 아직 저장한 적이 없으면 0
	 */
	record SettingsRequest(Boolean activitiesVisible, Long revision) {
	}

	/**
	 * 항목 등록과 수정. 과제 항목을 고칠 때는 weight와 revision만 보낸다.
	 *
	 * @param weight 성적 반영 비율(%, 0~100). 보내지 않으면 비율을 지운다
	 * @param revision 수정할 때 읽은 항목 수정 번호. 등록할 때는 보내지 않는다
	 */
	record ItemRequest(String name, BigDecimal maxScore, BigDecimal weight, Long revision) {
	}

	/**
	 * @param released true면 지금 공개, false면 공개를 거둔다
	 */
	record ReleaseRequest(Boolean released, Long revision) {
	}

	record ScoresRequest(List<ScoreRow> rows) {
	}

}
