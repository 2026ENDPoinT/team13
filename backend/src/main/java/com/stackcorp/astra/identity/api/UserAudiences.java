package com.stackcorp.astra.identity.api;

import java.util.List;

/**
 * 기관 전체 대상(알림마당 공지처럼 과목이 없는 알림)의 사용자. 대상 어휘는 알림마당 설문의 대상(survey_targets.target_type)과 같다.
 * 활동 중(ACTIVE)인 계정만이고 break-glass 계정은 어느 대상에도 없다(ADR-0004 6번). 권한은 판정하지 않는다.
 */
public interface UserAudiences {

	/**
	 * 사용자 ID 오름차순의 한 쪽. 많은 사용자를 나눠 읽도록 keyset으로 자른다.
	 * @param afterUserId 이 ID보다 큰 사용자부터. 처음이면 0
	 * @return 비어 있으면 더 없다
	 */
	List<Long> members(UserAudience audience, long afterUserId, int limit);

	/** 이름의 소문자가 survey_targets.target_type 값이다. */
	enum UserAudience {
		/** 모든 사용자 */
		ALL,
		/** 학생(users.user_role = STUDENT) */
		STUDENT,
		/** 교직원(학생이 아닌 교수, 조교, 직원) */
		STAFF
	}

}
