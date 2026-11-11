package com.stackcorp.astra.learning.api;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 과제(report) 학습활동. 활동의 제목, 설명, 주차, 공개, 기간은 learning이 소유한다(db/design/40_assessment.md 경계).
 * assessment는 과제 설정을 저장하는 transaction 안에서 이 API로 활동을 만들고 바꾼다. 권한은 부르는 쪽이 판정한다.
 */
public interface ReportActivities {

	/**
	 * 부르는 쪽 transaction 안에서 과제 활동을 만든다. 주차가 없으면 만든다.
	 *
	 * @return 활동 ID
	 */
	long create(long courseId, long createdBy, ReportActivityInput input);

	/**
	 * 부르는 쪽 transaction 안에서 과제 활동을 바꾼다.
	 *
	 * @return 그 과목의 과제 활동이 있어 바꿨는지
	 */
	boolean update(long courseId, long activityId, ReportActivityInput input);

	/** 그 과목에 속한 과제 활동. 다른 과목이거나 과제가 아니면 비어 있다(SC-03). */
	Optional<ReportActivity> find(long courseId, long activityId);

	/** 그 과목의 과제 활동 전체. 주차, 주차 안 순서대로. 공개 여부로 거르지 않는다. */
	List<ReportActivity> list(long courseId);

	/**
	 * 주어진 활동 가운데 지우지 않은 과제 활동. 여러 과목에 걸쳐 쿼리 한 번으로 읽는다. 순서는 정하지 않는다. 과목 권한과 공개 여부는 판정하지
	 * 않는다. 부르는 쪽이 자기 모듈의 행에서 얻은 활동 ID만 넘기고, 돌려받은 {@code courseId}를 자기 행의 과목과 맞춰 본다.
	 */
	List<ReportActivity> findAll(Collection<Long> activityIds);

	/**
	 * @param openAt null이면 바로 열린다
	 * @param closeAt 마감. 이 시각부터 제출 기간이 아니다(SC-06의 openAt <= now < closeAt)
	 */
	record ReportActivityInput(int weekNo, String title, String description, LocalDateTime openAt,
			LocalDateTime closeAt, boolean published) {
	}

	record ReportActivity(long activityId, long courseId, int weekNo, String title, String description,
			LocalDateTime openAt, LocalDateTime closeAt, boolean published) {

		/** 학생이 볼 수 있는지. 공개했고 시작 일시가 지났을 때만이다. now는 DB 시계다(SC-06). */
		public boolean openToStudents(LocalDateTime now) {
			return this.published && (this.openAt == null || !this.openAt.isAfter(now));
		}

	}

}
