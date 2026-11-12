package com.stackcorp.astra.catalog.api;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;

/**
 * 담당자 수강생 화면의 학습 열(최근 학습, 진도율). 학습 기록은 learning이 가지고 catalog는 learning에 의존하지 않으므로
 * (architecture.md 4.1) catalog가 이 port를 정의하고 learning이 구현한다.
 * <p>
 * 권한은 판정하지 않는다. 부르는 쪽이 담당자 권한을 먼저 판정하고 그 과목 수강생의 ID만 넘긴다. 사용자 수와 관계없이 쿼리 수가 같다.
 */
public interface RosterLearning {

	/** @return 넘긴 사용자마다 하나 */
	Map<Long, Learning> of(long courseId, Collection<Long> userIds);

	/**
	 * @param lastAccessAt 그 과목에서 마지막으로 영상 학습을 시작한 일시. 없으면 null
	 * @param progressPercent 학생에게 열린 영상 콘텐츠 가운데 출석인정을 채운 비율(0~100, 버림). 열린 콘텐츠가 없으면 null
	 */
	record Learning(LocalDateTime lastAccessAt, Integer progressPercent) {

		public static final Learning NONE = new Learning(null, null);

	}

}
