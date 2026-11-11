package com.stackcorp.astra.identity.sso;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 로그인 뒤 돌아갈 경로. 같은 origin의 상대 경로만 받고, 아니면 {@code /}로 보낸다(EP-0195 보안 검사, open redirect 방지).
 * scheme, {@code //}로 시작하는 주소, 역슬래시, 제어 문자, 공백, ASCII 밖의 문자가 있으면 받지 않는다.
 */
final class ReturnPath {

	static final String ROOT = "/";

	static final int MAX_LENGTH = 512;

	private ReturnPath() {
	}

	static String orRoot(String value) {
		return safe(value) ? value : ROOT;
	}

	/**
	 * 돌아갈 경로에 결과 표시({@code name=marker} query)를 더한다. 경로는 먼저 {@link #orRoot}로 거른다. fragment가 있으면 그 앞에
	 * 넣고, query가 있으면 뒤에 잇는다. 결과가 길이 상한을 넘으면 표시만 붙인 {@code /}로 보낸다.
	 *
	 * @param name query 이름. 코드가 정한 영문 소문자 값만 쓴다
	 * @param marker 값. 코드가 정한 영문 소문자 값만 쓴다
	 */
	static String marked(String value, String name, String marker) {
		String path = orRoot(value);
		int hash = path.indexOf('#');
		String head = hash < 0 ? path : path.substring(0, hash);
		String fragment = hash < 0 ? "" : path.substring(hash);
		String result = head + (head.indexOf('?') < 0 ? "?" : "&") + name + "=" + marker + fragment;
		return safe(result) ? result : ROOT + "?" + name + "=" + marker;
	}

	static boolean safe(String value) {
		if (value == null || value.isEmpty() || value.length() > MAX_LENGTH || value.charAt(0) != '/'
				|| value.startsWith("//")) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c <= 0x20 || c >= 0x7f || c == '\\') {
				return false;
			}
		}
		try {
			URI uri = new URI(value);
			return uri.getScheme() == null && uri.getRawAuthority() == null && uri.getRawPath() != null
					&& uri.getRawPath().startsWith("/");
		}
		catch (URISyntaxException ex) {
			return false;
		}
	}

}
