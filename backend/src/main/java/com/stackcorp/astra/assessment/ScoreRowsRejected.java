package com.stackcorp.astra.assessment;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * 점수 일괄 저장을 거부했다. 한 행이라도 틀리면 아무 행도 저장하지 않는다(ADR-0023). problem JSON의 {@code rows}에 틀린 행의 순번과 이유
 * 코드만 담는다. 입력값과 다른 사용자의 ID는 담지 않는다(SC-04).
 */
class ScoreRowsRejected extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	private final List<RowError> rows;

	ScoreRowsRejected(HttpStatus status, String code, String detail, List<RowError> rows) {
		super(detail);
		this.status = status;
		this.code = code;
		this.rows = List.copyOf(rows);
	}

	HttpStatus status() {
		return this.status;
	}

	String code() {
		return this.code;
	}

	List<RowError> rows() {
		return this.rows;
	}

	/**
	 * @param index 요청 rows 배열의 순번(0부터)
	 * @param code invalid_student, not_student, duplicate_student, invalid_version, invalid_score,
	 * score_exceeds_max, invalid_feedback, feedback_without_score, version_conflict
	 */
	record RowError(int index, String code) {
	}

}
