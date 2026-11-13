package com.stackcorp.astra.identity.api;

import java.util.Optional;

/**
 * 학사시스템 조회 port(EP-0279 내정보 동기화의 대체). {@code integration}이 학교 학사시스템 adapter로 구현한다. 학사시스템 연동이
 * 설정되지 않은 배포에는 bean이 없고, 동기화 명령은 503 {@code not_configured}다.
 * <p>
 * 대상은 언제나 요청한 사용자의 학사 식별자(users.external_ref)다. 요청 본문에서 고르지 않는다(IDENTITY-01). adapter는 설정한
 * 학사시스템 host만 부르고 제한 시간과 응답 크기 상한을 둔다(SC-08).
 */
public interface RegistrarDirectory {

	/**
	 * @param externalRef 학번·교번
	 * @return 학사 원천의 지금 값. 학사시스템에 그 사람이 없으면 empty
	 * @throws Unavailable 학사시스템에 닿지 못했거나 응답을 해석하지 못했을 때
	 */
	Optional<RegistrarRecord> lookup(String externalRef);

	/**
	 * @param name 이름. 비어 있지 않다
	 * @param departmentName 학과·소속명. 없으면 null
	 * @param grade 학년(학생만). 없으면 null
	 * @param phone 학사시스템의 휴대전화번호. phone과 email이 모두 null이면 연락처는 바꾸지 않는다
	 * @param email 학사시스템의 이메일
	 */
	record RegistrarRecord(String name, String departmentName, Integer grade, String phone, String email) {

		// 응답을 로그에 찍는 설정에서도 개인정보가 나가지 않게 한다(SC-07).
		@Override
		public String toString() {
			return "RegistrarRecord[]";
		}

	}

	/** 학사시스템 장애. message에 개인정보를 넣지 않는다. */
	class Unavailable extends RuntimeException {

		public Unavailable(String message, Throwable cause) {
			super(message, cause);
		}

	}

}
