package com.stackcorp.astra.identity.breakglass;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class TotpTests {

	@Test
	void matchesRfc6238Sha1Vectors() {
		byte[] key = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
		// RFC 6238 부록 B의 8자리 값 94287082, 07081804, 14050471의 마지막 6자리
		assertThat(Totp.code(key, 59 / 30)).isEqualTo("287082");
		assertThat(Totp.code(key, 1111111109L / 30)).isEqualTo("081804");
		assertThat(Totp.code(key, 1111111111L / 30)).isEqualTo("050471");
	}

	@Test
	void acceptsOneStepOfClockSkewOnly() {
		String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
		Instant now = Instant.ofEpochSecond(1111111111L);
		long step = now.getEpochSecond() / 30;

		assertThat(Totp.verify(secret, Totp.code(Totp.base32(secret), step - 1), now)).isTrue();
		assertThat(Totp.verify(secret, Totp.code(Totp.base32(secret), step + 1), now)).isTrue();
		assertThat(Totp.verify(secret, Totp.code(Totp.base32(secret), step - 2), now)).isFalse();
		assertThat(Totp.verify(secret, "12345", now)).isFalse();
	}

}
