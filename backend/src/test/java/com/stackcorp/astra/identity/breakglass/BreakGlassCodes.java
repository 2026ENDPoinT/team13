package com.stackcorp.astra.identity.breakglass;

import java.time.Instant;

import com.stackcorp.astra.IntegrationTest;

/**
 * 다른 package의 테스트가 break-glass로 로그인할 때 쓰는 현재 TOTP 코드.
 */
public final class BreakGlassCodes {

	private BreakGlassCodes() {
	}

	public static String current() {
		return Totp.code(Totp.base32(IntegrationTest.TOTP_SECRET), Instant.now().getEpochSecond() / Totp.STEP_SECONDS);
	}

}
