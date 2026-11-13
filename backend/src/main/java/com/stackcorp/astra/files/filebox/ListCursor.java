package com.stackcorp.astra.files.filebox;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.stackcorp.astra.shared.ApiException;

/**
 * keyset 목록의 다음 쪽 위치(SC-09, SEC-18). 마지막으로 보낸 행의 정렬 값과 ID를 담는다. 항목이 생기고 사라져도 앞 쪽에서 본 행을
 * 다시 보내거나 건너뛰지 않는다. 정렬 방식(scope)을 함께 담아, 다른 정렬에 쓴 cursor는 400으로 거부한다.
 */
final class ListCursor {

	private ListCursor() {
	}

	static String encode(String scope, Position position) {
		String raw = scope + "\n" + position.rank() + "\n" + position.id() + "\n" + position.value();
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	/** @throws ApiException cursor 형식이 틀렸거나 다른 정렬의 cursor면 400 */
	static Position decode(String scope, String cursor) {
		try {
			String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			String[] parts = raw.split("\n", 4);
			if (parts.length != 4 || !parts[0].equals(scope)) {
				throw invalid();
			}
			return new Position(Integer.parseInt(parts[1]), parts[3], Long.parseLong(parts[2]));
		}
		catch (IllegalArgumentException ex) {
			throw invalid();
		}
	}

	private static ApiException invalid() {
		return ApiException.invalid("cursor가 올바르지 않습니다.");
	}

	/**
	 * @param rank 폴더 0, 파일 1. 폴더를 먼저 보낸다
	 * @param value 정렬 값의 문자열 형식
	 */
	record Position(int rank, String value, long id) {
	}

}
