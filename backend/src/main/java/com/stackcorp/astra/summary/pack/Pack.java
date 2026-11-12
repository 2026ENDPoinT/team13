package com.stackcorp.astra.summary.pack;

import java.util.List;

/**
 * 문서 하나의 쪽별 요약 결과. 필드 이름은 LessonViewer의 Pack 스키마와 같다. lecture는 문서 식별자다.
 */
public record Pack(String lecture, String question, List<Beat> story, List<Slide> slides) {

	public Pack {
		story = List.copyOf(story);
		slides = List.copyOf(slides);
	}

}
