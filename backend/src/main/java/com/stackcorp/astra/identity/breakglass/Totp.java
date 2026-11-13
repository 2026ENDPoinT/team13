package com.stackcorp.astra.identity.breakglass;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 TOTP(HMAC-SHA1, 6자리, 30초). 시계 오차로 앞뒤 한 칸까지 받는다.
 */
final class Totp {

	static final long STEP_SECONDS = 30;

	private Totp() {
	}

	static boolean verify(String base32Secret, String code, Instant now) {
		if (code == null || !code.matches("\\d{6}")) {
			return false;
		}
		byte[] key = base32(base32Secret);
		long step = now.getEpochSecond() / STEP_SECONDS;
		boolean match = false;
		for (long s = step - 1; s <= step + 1; s++) {
			// 시간 차로 어느 칸이 맞았는지 드러나지 않게 세 칸을 모두 비교한다.
			match |= MessageDigest.isEqual(code(key, s).getBytes(), code.getBytes());
		}
		return match;
	}

	static String code(byte[] key, long step) {
		try {
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(key, "HmacSHA1"));
			byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
			int offset = h[h.length - 1] & 0x0f;
			int binary = ((h[offset] & 0x7f) << 24) | ((h[offset + 1] & 0xff) << 16) | ((h[offset + 2] & 0xff) << 8)
					| (h[offset + 3] & 0xff);
			return "%06d".formatted(binary % 1_000_000);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	static byte[] base32(String value) {
		String s = value.replace("=", "").replace(" ", "").toUpperCase();
		ByteBuffer out = ByteBuffer.allocate(s.length() * 5 / 8);
		int buffer = 0;
		int bits = 0;
		for (char c : s.toCharArray()) {
			int v = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);
			if (v < 0) {
				throw new IllegalArgumentException("TOTP secret이 Base32가 아닙니다.");
			}
			buffer = (buffer << 5) | v;
			bits += 5;
			if (bits >= 8) {
				out.put((byte) (buffer >> (bits - 8)));
				bits -= 8;
			}
		}
		return Arrays.copyOf(out.array(), out.position());
	}

}
