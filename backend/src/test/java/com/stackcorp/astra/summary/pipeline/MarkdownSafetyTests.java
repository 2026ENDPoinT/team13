package com.stackcorp.astra.summary.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 학습 대화 답의 링크 금지. 코드와 수식 안은 보지 않는다.
 */
class MarkdownSafetyTests {

	@Test
	void findsLinksAndUrlsInProse() {
		for (String text : new String[] { "[여기](https://evil.example)", "[x](/relative)", "see https://evil.example",
				"ftp://host/file", "www.evil.example 참고", "WWW.EVIL.EXAMPLE" }) {
			assertThat(MarkdownSafety.linkErrors("answer", text)).as(text).isNotEmpty();
		}
	}

	@Test
	void ignoresCitationsCodeAndMath() {
		for (String text : new String[] { "정의예요 [p.3] (참고)", "보충: data: 값과 note: 메모", "`https://example.com`",
				"```\ncurl https://example.com\n```", "$a://b$", "[p.2 \"인용\"]" }) {
			assertThat(MarkdownSafety.linkErrors("answer", text)).as(text).isEmpty();
		}
	}

}
