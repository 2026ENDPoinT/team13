package com.stackcorp.astra.learning.api;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 할 일 계산에 쓰는 마감 전 활동 조회(EP-0144, EP-0194). 학생에게 열려 있고 마감 일시가 있는 활동 중 마감 전인 것만 돌려준다.
 * 조건은 공개됨, {@code open_at <= now}(없으면 열린 것), {@code now < close_at}이다(SC-06). 마감 일시가 없는 활동은 할 일이 아니다.
 * 과목 권한은 판정하지 않는다. 부르는 쪽이 수강을 확인한 과목만 넘긴다. 과목 수와 관계없이 쿼리 한 번이다.
 */
public interface DueActivities {

	/**
	 * 마감 임박순({@code close_at}, {@code activity_id}) 목록의 앞 limit개. 할 일은 다른 모듈의 완료 여부로 다시 거르므로 쪽 나누기와 건수는
	 * 부르는 쪽이 거른 결과로 정한다.
	 * @param now DB 시계 값({@code SELECT CURRENT_TIMESTAMP(3)})
	 */
	List<DueActivity> due(Collection<Long> courseIds, LocalDateTime now, int limit);

	/**
	 * 학교 e-Class 강의실에서 가져온 과제·시험·설문(ADR-0043) 중 마감 전이고 그 학생의 가져온 완료 표시가 없는 것. 마감 임박순 앞 limit개.
	 * 로그인 때 가져온 행만 읽고 학교에 요청하지 않는다. 학교 화면의 만료 표시나 오프라인 시험 표시가 있는 행은 없다. 과목 수와 관계없이 쿼리
	 * 한 번이다.
	 * @param now DB 시계 값
	 */
	List<SchoolDueActivity> schoolDue(long userId, Collection<Long> courseIds, LocalDateTime now, int limit);

	/**
	 * @param type learning_activities.activity_type
	 */
	record DueActivity(long activityId, long courseId, String type, String title, LocalDateTime openAt,
			LocalDateTime closeAt) {

		public Position position() {
			return new Position(this.closeAt, this.activityId);
		}

	}

	/**
	 * @param schoolActivityId 학교 활동 ID. 응답의 활동 ID는 {@link #activityId()}다
	 * @param type 학교 메뉴 ID(report, test, survey). 서비스 활동 유형과 같은 말이다
	 * @param schoolUrl 학교 e-Class에서 그 활동을 여는 주소
	 */
	record SchoolDueActivity(long schoolActivityId, long courseId, String type, String title, LocalDateTime openAt,
			LocalDateTime closeAt, String schoolUrl) {

		public String activityId() {
			return "school-" + this.schoolActivityId;
		}

		/** 서비스 활동과 한 순서로 섞는 위치. 같은 마감이면 학교 활동이 앞이다(음수 ID). */
		public Position position() {
			return new Position(this.closeAt, -this.schoolActivityId);
		}

	}

	/** 정렬 순서의 한 위치. keyset paging에 쓴다. 학교 활동은 음수 ID다. */
	record Position(LocalDateTime closeAt, long activityId) {

		/** 정렬 순서로 이 위치가 other보다 뒤인지. */
		public boolean after(Position other) {
			int byClose = this.closeAt.compareTo(other.closeAt);
			return byClose > 0 || (byClose == 0 && this.activityId > other.activityId);
		}

	}

}
