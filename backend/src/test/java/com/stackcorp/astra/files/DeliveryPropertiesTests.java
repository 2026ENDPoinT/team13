package com.stackcorp.astra.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * 전달 cookie 유효 시간은 권한을 잃은 뒤 CloudFront 경로가 열려 있는 시간의 상한이다. 설정으로 그 상한을 넓힐 수 없어야 한다.
 */
class DeliveryPropertiesTests {

	@Test
	void acceptsCookieLifetimesFromOneToTenMinutes() {
		DeliveryProperties shortest = new DeliveryProperties(Duration.ofMinutes(1), Duration.ofMinutes(1), true, false);
		DeliveryProperties longest = new DeliveryProperties(Duration.ofMinutes(10), Duration.ofMinutes(10), true, false);

		assertThat(shortest.videoCookieTtl()).isEqualTo(Duration.ofMinutes(1));
		assertThat(longest.documentCookieTtl()).isEqualTo(Duration.ofMinutes(10));
	}

	@Test
	void refusesACookieLifetimeLongerThanTenMinutesOrShorterThanOneMinute() {
		Duration tooLong = Duration.ofMinutes(10).plusSeconds(1);
		Duration tooShort = Duration.ofSeconds(59);

		assertThatIllegalArgumentException()
			.isThrownBy(() -> new DeliveryProperties(tooLong, Duration.ofMinutes(10), true, false))
			.withMessageContaining("document-cookie-ttl");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new DeliveryProperties(Duration.ofMinutes(10), tooLong, true, false))
			.withMessageContaining("video-cookie-ttl");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new DeliveryProperties(Duration.ofMinutes(10), tooShort, true, false))
			.withMessageContaining("video-cookie-ttl");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new DeliveryProperties(null, Duration.ofMinutes(10), true, false));
	}

}
