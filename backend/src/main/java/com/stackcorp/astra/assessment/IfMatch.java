package com.stackcorp.astra.assessment;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.stackcorp.astra.shared.ApiException;

import org.springframework.http.HttpStatus;

/**
 * 과제, 퀴즈, 설문 삭제의 전제 조건(ADR-0011과 같은 방식, architecture.md 5절). {@code If-Match}에 마지막으로 읽은 그 객체의
 * {@code revision}을 {@code "3"}처럼 보낸다. 약한 비교(W/)와 *는 받지 않는다. 없으면 428, 형식이 틀리면 400이다. 값이 지금과 다르면
 * 부르는 쪽이 수정과 같은 409 {@code edit_conflict}로 거부한다.
 */
final class IfMatch {

	private static final Pattern REVISION = Pattern.compile("\"([1-9][0-9]{0,8})\"");

	private IfMatch() {
	}

	static long revision(String ifMatch) {
		if (ifMatch == null || ifMatch.isBlank()) {
			throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "precondition_required",
					"If-Match에 마지막으로 읽은 revision을 보내야 합니다.");
		}
		Matcher m = REVISION.matcher(ifMatch.strip());
		if (!m.matches()) {
			throw ApiException.invalid("If-Match 형식이 올바르지 않습니다.");
		}
		return Long.parseLong(m.group(1));
	}

}
