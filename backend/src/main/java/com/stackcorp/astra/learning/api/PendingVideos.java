package com.stackcorp.astra.learning.api;

import java.util.Collection;
import java.util.List;

/**
 * 변환 중이거나 변환에 실패한 강의 영상(ADR-0016). 담당자 메인의 확인할 일에 쓴다. 지운 활동과 지운 콘텐츠는 빼고, 공개 여부와 관계없이
 * 센다. 과목 권한은 판정하지 않는다. 부르는 쪽이 담당을 확인한 과목만 넘긴다. 과목 수와 관계없이 쿼리 한 번이다.
 */
public interface PendingVideos {

	/** @return 그런 콘텐츠가 있는 영상 활동마다 하나. 과목, 활동 ID 순 */
	List<PendingVideo> of(Collection<Long> courseIds);

	/**
	 * @param title 영상 활동 제목
	 * @param processing 변환을 기다리거나 변환 중인 콘텐츠 수(video_state = processing)
	 * @param failed 변환이 끝내 실패한 콘텐츠 수(video_state = failed)
	 */
	record PendingVideo(long courseId, long activityId, String title, int processing, int failed) {
	}

}
