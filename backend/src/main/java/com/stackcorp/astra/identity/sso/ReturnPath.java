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
