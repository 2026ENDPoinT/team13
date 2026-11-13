package com.stackcorp.astra.summary.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 기본 가림 규칙. 형식으로 알아볼 수 있는 것만 가리고 자료의 수치는 되도록 건드리지 않는다.
 */
class PatternRedactorTests {

	private final PatternRedactor redactor = new PatternRedactor();

	@Test
	void masksEmailPhoneAndStudentNumberShapes() {
		assertThat(this.redactor.redact("문의 kim.minsu@seoultech.ac.kr, 010-1234-5678, 학번 23101234."))
			.isEqualTo("문의 [이메일], [전화번호], 학번 [번호].");
		assertThat(this.redactor.redact("(2310123456)")).isEqualTo("([번호])");
	}

	@Test
	void leavesOtherNumbersAlone() {
		String text = "pi = 3.14159265, 1,234,567,890원, 2026년 11월, 1234567, 12345678901";
		assertThat(this.redactor.redact(text)).isEqualTo(text);
	}

	@Test
	void isDeterministicAndKeepsNull() {
		assertThat(this.redactor.redact(null)).isNull();
		assertThat(this.redactor.redact("a@b.co 23101234")).isEqualTo(this.redactor.redact("a@b.co 23101234"));
	}

}
