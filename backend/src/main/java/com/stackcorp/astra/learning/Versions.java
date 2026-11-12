package com.stackcorp.astra.learning;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.stackcorp.astra.shared.ApiException;

import org.springframework.http.HttpStatus;

/**
 * 출석, 그룹, 강의계획서의 If-Match version(ADR-0011과 같은 방식). 아직 행이 없는 대상(출석 기록, 강의계획서, 설정)은 version 0이고,
 * 처음 저장할 때도 0을 보낸다. 약한 비교(W/)와 *는 받지 않는다. 없으면 428, 형식이 틀리면 400이다.
 */
final class Versions {

	private static final Pattern VERSION = Pattern.compile("\"(0|[1-9][0-9]{0,8})\"");

	private Versions() {
	}

	static int expected(String ifMatch) {
		if (ifMatch == null || ifMatch.isBlank()) {
			throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "precondition_required",
					"If-Match에 마지막으로 읽은 version을 보내야 합니다.");
		}
		Matcher m = VERSION.matcher(ifMatch.strip());
		if (!m.matches()) {
			throw ApiException.invalid("If-Match 형식이 올바르지 않습니다.");
		}
		return Integer.parseInt(m.group(1));
	}

	static String etag(int version) {
		return "\"" + version + "\"";
	}

	static ApiException conflict() {
		return new ApiException(HttpStatus.CONFLICT, "version_conflict", "그 사이 내용이 바뀌었습니다. 다시 불러온 뒤 고쳐 주세요.");
	}

}
