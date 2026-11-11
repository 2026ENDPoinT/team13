package com.stackcorp.astra.identity.session;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param idleTimeout 마지막 요청 뒤 이 시간이 지나면 session을 끝낸다
 * @param absoluteTimeout 로그인 뒤 이 시간이 지나면 session을 끝낸다
 * @param allowedOrigins 상태를 바꾸는 요청에 허용하는 Origin. 비우면 요청 자신의 origin만 허용한다
 */
@ConfigurationProperties("astra.session")
public record SessionProperties(@DefaultValue("ASTRA_SESSION") String cookieName,
		@DefaultValue("2h") Duration idleTimeout, @DefaultValue("12h") Duration absoluteTimeout,
		@DefaultValue("true") boolean secureCookie, @DefaultValue List<String> allowedOrigins) {
}
