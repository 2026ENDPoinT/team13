package com.stackcorp.astra.assessment.api;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Set;

/**
 * 학생 본인이 이미 끝낸 평가 활동(할 일의 완료 판정, EP-0144의 "미완료"). 완료는 활동 유형마다 다음 하나다.
 * <ul>
 * <li>과제: 최종 제출했다. 임시저장은 완료가 아니다. 마감 전 다시 제출할 수 있어도 완료다.</li>
 * <li>퀴즈: 온라인 시험이고, 이어갈 진행 중 응시가 없고, 허용 응시 횟수를 모두 썼다. 응시 기간 안에서 학생이 더는 응시를 시작할 수 없는
 * 경우와 같다. 횟수 제한이 없는 퀴즈와 오프라인 시험은 학생이 끝낼 수 없으므로 완료가 아니다.</li>
 * <li>강의실 설문: 참여 원장에 완료가 있다. 익명 설문도 참여 원장만 읽고 응답과 사용자를 잇지 않는다(ADR-0022).</li>
 * </ul>
 * 과목 권한과 공개 여부는 판정하지 않는다. 부르는 쪽이 그 사용자에게 보이는 활동의 ID만 넘긴다. 돌려주는 값은 그 사용자 자신의 상태라 다른
 * 사람의 정보를 담지 않는다. 쿼리는 활동 수, 과목 수와 관계없이 넷 이하다(과제 1, 설문 1, 퀴즈 설정 1, 퀴즈 응시 1).
 */
public interface ActivityCompletions {

	/**
	 * @param activityIds 할 일 목록의 학습활동 ID. 평가 활동이 아닌 것은 무시한다
	 * @param now DB 시계 값({@code SELECT CURRENT_TIMESTAMP(3)}). 진행 중 응시의 마감 판정에 쓴다
	 * @return 그 가운데 userId가 끝낸 활동 ID
	 */
	Set<Long> completedBy(long userId, Collection<Long> activityIds, LocalDateTime now);

}
