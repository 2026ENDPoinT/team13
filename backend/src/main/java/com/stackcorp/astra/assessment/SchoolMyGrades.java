package com.stackcorp.astra.assessment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.learning.api.SchoolGrades;

import org.springframework.stereotype.Component;

/**
 * 학교에서 읽는 과목(ADR-0043)의 학생 성적 화면. 학생이 {@code GET …/grades/me}를 열 때마다 {@link SchoolGrades}가 그 학생의 학교
 * session으로 학교 성적 화면을 읽는다. 모양은 서비스 학생 성적({@code GradebookService.MyGrades})과 같은 이름의 값이고 공개하지
 * 않은 항목에는 점수 속성이 없다(SEC-14). 저장하지 않는다.
 */
@Component
class SchoolMyGrades {

	private final SchoolGrades grades;

	private final QuizAccess clock;

	SchoolMyGrades(SchoolGrades grades, QuizAccess clock) {
		this.grades = grades;
		this.clock = clock;
	}

	/** @return 학교에서 읽는 과목의 학생이 아니면 empty */
	Optional<View> of(Actor actor, long courseId, String type, String query) {
		String typeFilter = GradebookService.validatedType(type);
		String q = GradebookService.validatedQuery(query);
		return this.grades.of(actor, courseId).map((items) -> {
			List<Item> rows = items.stream()
				.filter((item) -> typeFilter == null || typeFilter.equals(item.type()))
				.filter((item) -> q == null || item.name().toLowerCase(Locale.ROOT).contains(q))
				.map(Item::of)
				.toList();
			List<SchoolGrades.Item> scored = items.stream()
				.filter((item) -> item.released() && item.score() != null && item.maxScore() != null)
				.toList();
			Total total = scored.isEmpty() ? null
					: new Total(scored.stream().map(SchoolGrades.Item::score).reduce(BigDecimal.ZERO, BigDecimal::add),
							scored.stream().map(SchoolGrades.Item::maxScore).reduce(BigDecimal.ZERO, BigDecimal::add),
							scored.size());
			return new View(courseId, "school", this.clock.now(), true, rows, total);
		});
	}

	/**
	 * @param total 공개된 점수가 있는 항목의 합계. 없으면 속성이 없다
	 */
	record View(long courseId, String origin, LocalDateTime serverTime, boolean activitiesVisible, List<Item> items,
			@JsonInclude(JsonInclude.Include.NON_NULL) Total total) {
	}

	record Total(BigDecimal score, BigDecimal maxScore, int itemCount) {
	}

	/**
	 * 학교 성적 한 줄. 서비스 학생 성적 한 줄과 같은 이름의 값에 {@code origin}, {@code schoolType}, {@code activityId},
	 * {@code offline}을 더한다.
	 * @param itemId {@code "school-<학교 활동 번호>"}
	 * @param submitted 학교 화면의 제출·응시 완료 표시
	 * @param score 공개했고 점수가 있을 때만 있다
	 */
	record Item(String itemId, String origin, String name, String type, String schoolType,
			@JsonInclude(JsonInclude.Include.NON_NULL) String activityId,
			@JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime closeAt, boolean submitted, boolean offline,
			BigDecimal maxScore, boolean released, @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal score) {

		static Item of(SchoolGrades.Item item) {
			return new Item(item.itemId(), "school", item.name(), item.type(), item.schoolType(), item.activityId(),
					item.closeAt(), item.submitted(), item.offline(), item.maxScore(), item.released(),
					item.released() ? item.score() : null);
		}

	}

}
