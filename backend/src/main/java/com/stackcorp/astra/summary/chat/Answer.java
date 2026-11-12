package com.stackcorp.astra.summary.chat;

import java.util.List;

/**
 * 검사를 통과한 답. text는 Markdown이고 raw HTML, 이미지, 링크가 없다.
 */
public record Answer(String text, List<Citation> citations) {

	public Answer {
		citations = List.copyOf(citations);
	}

}
