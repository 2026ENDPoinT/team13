package com.stackcorp.astra.learning;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 스마트출석 증명(SEC-12, ADR-0030). PIN과 QR token은 회차마다 서버가 만든 비밀값의 HMAC-SHA256이다.
 * <ul>
 * <li>메시지에 과목, 출석 차시, 회차, 방식을 넣는다. 다른 회차·과목·방식으로 옮긴 값은 맞지 않는다.</li>
 * <li>QR token은 회차를 연 시각부터 정해진 주기로 바뀌는 구간 번호(slot)를 담는다. 지금 구간의 token과, 바뀐 뒤 유예 시간 안의 바로 앞
 * token만 받는다. 미래 구간과 더 오래된 구간은 받지 않는다.</li>
 * <li>비교는 고정 시간 비교다.</li>
 * </ul>
 * 사용자는 증명에 넣지 않는다. 제출한 사람은 언제나 session principal이고, 한 사람의 성공은 회차마다 한 번만 소비된다
 * (smart_attendance_attempts.used_jti 유일 제약).
 */
final class SmartAttendanceProof {

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final Pattern QR = Pattern.compile("([0-9]{1,9})\\.([A-Za-z0-9_-]{22})");

	private static final int MAC_BYTES = 16;

	private SmartAttendanceProof() {
	}

	static byte[] newSecret() {
		byte[] secret = new byte[32];
		RANDOM.nextBytes(secret);
		return secret;
	}

	static String pin(byte[] secret, Scope scope, int length) {
		long value = ByteBuffer.wrap(mac(secret, "pin|" + scope.message())).getLong() & Long.MAX_VALUE;
		long modulus = (long) Math.pow(10, length);
		String digits = Long.toString(value % modulus);
		return "0".repeat(length - digits.length()) + digits;
	}

	static boolean pinMatches(byte[] secret, Scope scope, int length, String candidate) {
		return MessageDigest.isEqual(pin(secret, scope, length).getBytes(StandardCharsets.US_ASCII),
				candidate.getBytes(StandardCharsets.US_ASCII));
	}

	/** 회차를 연 시각부터 센 지금의 QR 구간 번호. */
	static long slot(LocalDateTime openedAt, LocalDateTime now, Duration rotation) {
		long elapsed = Duration.between(openedAt, now).toMillis();
		return elapsed <= 0 ? 0 : elapsed / rotation.toMillis();
	}

	/** 그 구간의 token이 다음 token으로 바뀌는 시각. */
	static LocalDateTime slotEnd(LocalDateTime openedAt, long slot, Duration rotation) {
		return openedAt.plus(rotation.multipliedBy(slot + 1));
	}

	static String qrToken(byte[] secret, Scope scope, long slot) {
		byte[] mac = Arrays.copyOf(mac(secret, "qr|" + scope.message() + "|" + slot), MAC_BYTES);
		return slot + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac);
	}

	/** 지금 받을 수 있는 QR token인지. 형식이 틀리면 맞지 않는 token이다. */
	static boolean qrMatches(byte[] secret, Scope scope, String token, LocalDateTime openedAt, LocalDateTime now,
			Duration rotation, Duration grace) {
		Matcher m = QR.matcher(token);
		if (!m.matches()) {
			return false;
		}
		long slot = Long.parseLong(m.group(1));
		if (slot > slot(openedAt, now, rotation) || !now.isBefore(slotEnd(openedAt, slot, rotation).plus(grace))) {
			return false;
		}
		return MessageDigest.isEqual(qrToken(secret, scope, slot).getBytes(StandardCharsets.US_ASCII),
				token.getBytes(StandardCharsets.US_ASCII));
	}

	private static byte[] mac(byte[] secret, String message) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** 증명이 묶이는 범위. */
	record Scope(long courseId, long sessionId, long roundId, String method) {

		String message() {
			return "v1|" + this.courseId + "|" + this.sessionId + "|" + this.roundId + "|" + this.method;
		}

	}

}
