package com.stackcorp.astra.learning.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.stackcorp.astra.identity.api.Actor;

/**
 * 학교 e-Class 과목의 학생 본인 성적(ADR-0043). 학교 session은 학교 로그인을 쓰는 {@code learning}이 가지고 학생 성적 화면은
 * {@code assessment}가 내므로 {@code learning}이 이 port를 구현한다. 그 학생이 성적 화면을 열 때마다 그 학생의 학교 session으로
 * 학교 성적 화면을 읽는다. 저장하거나 다른 학생에게 쓰지 않는다.
 */
public interface SchoolGrades {

	/**
	 * @return 그 과목을 학교에서 읽는 학생(학교 과목 키가 있고 STack 담당자가 없는 과목의 수강생)이면 학교 성적 행, 아니면 empty
	 * @throws com.stackcorp.astra.shared.ApiException 학교 session이 없거나 끝났으면 409 {@code school_signin_required}, 학교
	 * 장애는 502 {@code school_unavailable}
	 */
	Optional<List<Item>> of(Actor actor, long courseId);

	/**
	 * @param itemId {@code "school-<학교 활동 번호>"}. 같은 목록 안에서만 쓰는 이름이다
	 * @param type {@code assignment}(학교 과제) | {@code quiz}(학교 시험·퀴즈) | {@code manual}(그 밖)
	 * @param schoolType 학교 메뉴 ID(report, test 등)
	 * @param activityId 강의실 주차 목록의 같은 활동({@code "school-<id>"}). 가져오지 않은 활동이면 null
	 * @param released 점수를 공개했는지. false면 {@code score}는 null이다
	 * @param score 공개했고 점수가 있을 때만
	 */
	record Item(String itemId, String type, String schoolType, String activityId, String name, LocalDateTime closeAt,
			boolean submitted, boolean offline, BigDecimal maxScore, boolean released, BigDecimal score) {
	}

}
