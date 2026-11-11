package com.stackcorp.astra.learning.api;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 퀴즈(test) 학습활동. 활동의 제목, 설명, 주차, 공개, 응시 기간, 오프라인 여부는 learning이 소유한다(db/design/40_assessment.md 경계).
 * assessment는 퀴즈 설정을 저장하는 transaction 안에서 이 API로 활동을 만들고 바꾼다. 권한은 부르는 쪽이 판정한다.
 */
public interface QuizActivities {

	/**
	 * 부르는 쪽 transaction 안에서 퀴즈 활동을 만든다. 주차가 없으면 만든다.
	 *
	 * @return 활동 ID
	 */
	long create(long courseId, long createdBy, QuizActivityInput input);

	/**
	 * 부르는 쪽 transaction 안에서 퀴즈 활동을 바꾼다.
	 *
	 * @return 그 과목의 퀴즈 활동이 있어 바꿨는지
	 */
	boolean update(long courseId, long activityId, QuizActivityInput input);

	/** 그 과목에 속한 퀴즈 활동. 다른 과목이거나 퀴즈가 아니면 비어 있다(SC-03). */
	Optional<QuizActivity> find(long courseId, long activityId);

	/** 그 과목의 퀴즈 활동 전체. 주차, 주차 안 순서대로. 공개 여부로 거르지 않는다. */
	List<QuizActivity> list(long courseId);

	/**
	 * 주어진 활동 가운데 지우지 않은 퀴즈 활동. 여러 과목에 걸쳐 쿼리 한 번으로 읽는다. 순서는 정하지 않는다. 과목 권한과 공개 여부는 판정하지
	 * 않는다. 부르는 쪽이 자기 모듈의 행에서 얻은 활동 ID만 넘기고, 돌려받은 {@code courseId}를 자기 행의 과목과 맞춰 본다.
	 */
	List<QuizActivity> findAll(Collection<Long> activityIds);

	/**
	 * @param openAt null이면 바로 열린다
	 * @param closeAt 응시 마감. 이 시각부터 응시를 시작할 수 없다(SC-06의 openAt <= now < closeAt)
	 * @param offline 강의실에서 치르는 시험인지. 활동 목록의 오프라인 표시에 쓴다
	 */
	record QuizActivityInput(int weekNo, String title, String description, LocalDateTime openAt,
			LocalDateTime closeAt, boolean published, boolean offline) {
	}

	record QuizActivity(long activityId, long courseId, int weekNo, String title, String description,
			LocalDateTime openAt, LocalDateTime closeAt, boolean published, boolean offline) {

		/** 학생이 볼 수 있는지. 공개했고 시작 일시가 지났을 때만이다. now는 DB 시계다(SC-06). */
		public boolean openToStudents(LocalDateTime now) {
			return this.published && (this.openAt == null || !this.openAt.isAfter(now));
		}

	}

}
