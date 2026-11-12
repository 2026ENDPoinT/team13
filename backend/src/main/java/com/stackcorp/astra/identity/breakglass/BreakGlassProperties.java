package com.stackcorp.astra.identity.breakglass;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * break-glass 계정(ADR-0004 6번). 비밀번호 hash와 TOTP secret은 DB가 아니라 Secrets Manager에서 받는다.
 * 셋 중 하나라도 비면 break-glass 로그인은 꺼진다.
 *
 * @param externalRef users.external_ref. 운영자가 users 행을 미리 만든다
 * @param passwordHash {@code {bcrypt}...} 형식의 hash
 * @param totpSecret RFC 6238 TOTP secret(Base32)
 */
@ConfigurationProperties("astra.break-glass")
public record BreakGlassProperties(String externalRef, String passwordHash, String totpSecret) {

	boolean enabled() {
		return StringUtils.hasText(this.externalRef) && StringUtils.hasText(this.passwordHash)
				&& StringUtils.hasText(this.totpSecret);
	}

	@Override
	public String toString() {
		return "BreakGlassProperties[externalRef=" + this.externalRef + ", enabled=" + enabled() + "]";
	}

}
