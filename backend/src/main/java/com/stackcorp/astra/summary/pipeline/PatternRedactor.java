package com.stackcorp.astra.summary.pipeline;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 기본 가림 규칙. 형식만으로 알아볼 수 있는 것만 가린다.
 * <ul>
 * <li>전자우편 주소 → {@code [이메일]}</li>
 * <li>휴대전화 번호(010-1234-5678 형식) → {@code [전화번호]}</li>
 * <li>다른 숫자와 이어지지 않은 8~10자리 숫자(학번 형식) → {@code [번호]}</li>
 * </ul>
 * 한계: 이름은 형식으로 알아볼 수 없어 가리지 못한다. 쪽 이미지는 그대로 간다. 학번과 같은 자릿수의 수치(예: 8자리 상수)도 가려진다.
 * 띄어 쓰거나 끊어 쓴 학번은 지나간다. 학생 이름이 들어간 자료는 교수자가 요약을 요청하기 전에 확인해야 한다(ADR-0032).
 */
@Component
class PatternRedactor implements TextRedactor {

	private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");

	// 앞뒤에 숫자가 붙거나 소수점·자릿수 구분으로 이어진 수(3.14159265, 1,234,567,890)는 건드리지 않는다.
	private static final String BEFORE = "(?<!\\d)(?<!\\d[.,])";

	private static final String AFTER = "(?!\\d)(?![.,]\\d)";

	private static final Pattern PHONE = Pattern.compile(BEFORE + "01[016789][-. ]?\\d{3,4}[-. ]?\\d{4}" + AFTER);

	private static final Pattern STUDENT_NUMBER = Pattern.compile(BEFORE + "\\d{8,10}" + AFTER);

	@Override
	public String redact(String text) {
		if (text == null || text.isEmpty()) {
			return text;
		}
		String out = EMAIL.matcher(text).replaceAll("[이메일]");
		out = PHONE.matcher(out).replaceAll("[전화번호]");
		return STUDENT_NUMBER.matcher(out).replaceAll("[번호]");
	}

}
