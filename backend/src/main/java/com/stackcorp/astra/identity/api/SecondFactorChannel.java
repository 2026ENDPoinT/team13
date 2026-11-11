package com.stackcorp.astra.identity.api;

import java.time.LocalDateTime;

/**
 * 학습 인증 challenge의 2차 인증 수단 port(ADR-0029). 수단마다 bean 하나다. 지금 구현은 {@code identity}의 학교 IdP
 * 재인증({@code idp}) 하나다. 문자, 메일, 앱 push처럼 외부 발송이 필요한 수단은 {@code integration}이 이 port로 붙인다.
 * <ul>
 * <li>발송 대상은 수단이 사용자 등록값에서 정한다. client가 대상을 고르지 않는다(IDENTITY-04).</li>
 * <li>확인은 서버가 가진 근거로만 통과한다. 빈 값, 형식이 다른 값, client가 보낸 성공 표시로는 통과하지 않는다(SEC-04).</li>
 * <li>시도 횟수, 만료, 한 번만 확인되는 것은 challenge 쪽(identity)이 지킨다. 수단은 근거만 판정한다.</li>
 * </ul>
 */
public interface SecondFactorChannel {

	/** 요청과 응답에 쓰는 수단 이름. 소문자 식별자다. */
	String name();

	/** secondary_auth_challenges.auth_way 값. */
	int authWay();

	/** 이 실행에서 쓸 수 있는지. 설정이 없으면 false다. */
	boolean available();

	/**
	 * challenge를 시작한다. 코드를 보내는 수단은 여기서 보내고 그 hash를 돌려준다.
	 * @param userId challenge를 만든 사용자
	 */
	Start start(long userId, long sessionId);

	/**
	 * @param code 사용자가 입력한 값. 코드가 없는 수단이면 null이어야 한다
	 * @return 근거가 맞을 때만 true
	 */
	boolean verify(Pending challenge, String code);

	/**
	 * @param destinationLabel 화면에 보일 마스킹한 대상. 없으면 null
	 * @param codeHash 보낸 코드의 hash. 코드가 없는 수단이면 null
	 * @param redirectPath 사용자를 보낼 같은 origin 경로. 없으면 null
	 */
	record Start(String destinationLabel, String codeHash, String redirectPath) {

		@Override
		public String toString() {
			return "Start[redirectPath=" + this.redirectPath + "]";
		}

	}

	/**
	 * @param createdAt challenge를 만든 DB 시각. 근거는 이 시각 뒤에 생긴 것이어야 한다
	 */
	record Pending(long userId, long sessionId, LocalDateTime createdAt, String codeHash) {

		@Override
		public String toString() {
			return "Pending[userId=" + this.userId + ", sessionId=" + this.sessionId + "]";
		}

	}

}
