package com.stackcorp.astra.identity.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 브라우저 주소는 연결한 쪽이 믿는 proxy일 때만 X-Forwarded-For에서 오른쪽부터 읽는다. 앞단은 받은 header 뒤에 자기가 본 주소를
 * 덧붙이므로 왼쪽 값은 브라우저가 넣은 것일 수 있다.
 */
class ClientAddressesTests {

	// compose 단일 host: Cloudflare(덧붙임) → cloudflared(viewer container의 loopback) → server.mjs(덧붙임,
	// astra_egress의 주소) → api. server.mjs를 host에서 띄우면 api가 보는 연결은 같은 bridge의 gateway다. 둘 다 astra_egress 범위다.
	private static final ClientAddresses COMPOSE = new ClientAddresses(
			new ClientAddressProperties(List.of("127.0.0.0/8", "::1", "172.18.0.0/16")));

	// Terraform: CloudFront VPC origin ENI가 있는 app subnet.
	private static final ClientAddresses CLOUDFRONT = new ClientAddresses(
			new ClientAddressProperties(List.of("10.40.2.0/24")));

	@Test
	void usesThePeerWhenNothingIsTrusted() {
		ClientAddresses none = new ClientAddresses(new ClientAddressProperties(List.of()));

		assertThat(none.of(request("172.18.0.1", "198.51.100.7, 127.0.0.1"))).isEqualTo("172.18.0.1");
	}

	@Test
	void ignoresTheHeaderFromAnUntrustedPeer() {
		assertThat(CLOUDFRONT.of(request("203.0.113.66", "198.51.100.7"))).isEqualTo("203.0.113.66");
		assertThat(CLOUDFRONT.of(request("10.40.3.4", "198.51.100.7"))).isEqualTo("10.40.3.4");
		assertThat(COMPOSE.of(request("172.19.0.1", "198.51.100.7"))).isEqualTo("172.19.0.1");
	}

	@Test
	void takesTheAddressTheTrustedProxyAppended() {
		assertThat(CLOUDFRONT.of(request("10.40.2.15", "198.51.100.7"))).isEqualTo("198.51.100.7");
		// 브라우저가 보낸 왼쪽 값은 읽지 않는다.
		assertThat(CLOUDFRONT.of(request("10.40.2.15", "10.40.2.9, 1.1.1.1, 198.51.100.7"))).isEqualTo("198.51.100.7");
	}

	@Test
	void skipsTrustedHopsFromTheRight() {
		// Cloudflare가 브라우저 주소를 덧붙이고, server.mjs가 cloudflared(127.0.0.1)를 덧붙인다.
		assertThat(COMPOSE.of(request("172.18.0.1", "6.6.6.6, 198.51.100.7, 127.0.0.1"))).isEqualTo("198.51.100.7");
		assertThat(COMPOSE.of(request("172.18.0.1", "198.51.100.7, ::1, 127.0.0.1"))).isEqualTo("198.51.100.7");
		// server.mjs에 이 host의 브라우저가 직접 붙으면 모두 믿는 주소다. 가장 왼쪽 값을 쓴다.
		assertThat(COMPOSE.of(request("172.18.0.1", "127.0.0.1"))).isEqualTo("127.0.0.1");
		// header 여러 줄은 받은 순서대로 이어 읽는다.
		assertThat(COMPOSE.of(request("172.18.0.1", "6.6.6.6", "198.51.100.7", "127.0.0.1"))).isEqualTo("198.51.100.7");
	}

	@Test
	void stopsAtAMalformedHopAndUsesTheProxyThatSentIt() {
		assertThat(CLOUDFRONT.of(request("10.40.2.15", "evil.example"))).isEqualTo("10.40.2.15");
		assertThat(CLOUDFRONT.of(request("10.40.2.15", "unknown"))).isEqualTo("10.40.2.15");
		assertThat(CLOUDFRONT.of(request("10.40.2.15", "198.51.100.7, "))).isEqualTo("10.40.2.15");
		assertThat(CLOUDFRONT.of(request("10.40.2.15", "fe80::1%eth0"))).isEqualTo("10.40.2.15");
		assertThat(CLOUDFRONT.of(request("10.40.2.15", "198.51.100.7:99999"))).isEqualTo("10.40.2.15");
		assertThat(CLOUDFRONT.of(request("10.40.2.15", "[2001:db8::7"))).isEqualTo("10.40.2.15");
		// 믿는 hop 다음의 형식 오류는 그 hop이 넘긴 것이다.
		assertThat(COMPOSE.of(request("172.18.0.1", "198.51.100.7, garbage, 127.0.0.1"))).isEqualTo("127.0.0.1");
		// 점 넷이 아닌 IPv4는 InetAddress가 다른 주소로 늘이므로 받지 않는다.
		assertThat(CLOUDFRONT.of(request("10.40.2.15", "198.51"))).isEqualTo("10.40.2.15");
		assertThat(CLOUDFRONT.of(request("10.40.2.15", "3325256711"))).isEqualTo("10.40.2.15");
	}

	@Test
	void usesThePeerWhenTheHeaderIsMissingOrEmpty() {
		assertThat(CLOUDFRONT.of(request("10.40.2.15"))).isEqualTo("10.40.2.15");
		assertThat(CLOUDFRONT.of(request("10.40.2.15", ""))).isEqualTo("10.40.2.15");
		assertThat(CLOUDFRONT.of(request("10.40.2.15", " "))).isEqualTo("10.40.2.15");
	}

	@Test
	void readsIpv6AndPortForms() {
		assertThat(CLOUDFRONT.of(request("10.40.2.15", "2001:db8::7"))).isEqualTo("2001:db8:0:0:0:0:0:7");
		assertThat(CLOUDFRONT.of(request("10.40.2.15", "[2001:db8::7]:443"))).isEqualTo("2001:db8:0:0:0:0:0:7");
		assertThat(CLOUDFRONT.of(request("10.40.2.15", "[2001:db8::7]"))).isEqualTo("2001:db8:0:0:0:0:0:7");
		assertThat(CLOUDFRONT.of(request("10.40.2.15", "198.51.100.7:51234"))).isEqualTo("198.51.100.7");
		assertThat(CLOUDFRONT.of(request("10.40.2.15", "::ffff:198.51.100.7"))).isEqualTo("198.51.100.7");
		// IPv6 peer와 IPv4-mapped peer도 범위로 판정한다.
		assertThat(COMPOSE.of(request("::1", "198.51.100.7"))).isEqualTo("198.51.100.7");
		assertThat(COMPOSE.of(request("::ffff:172.18.0.1", "198.51.100.7"))).isEqualTo("198.51.100.7");
	}

	@Test
	void refusesAMalformedOrUnsafeTrustedProxy() {
		for (String value : List.of("10.40.2.0/33", "10.40.2.0/x", "proxy.example", "::1/129", "0.0.0.0/0", "::/0",
				"172.18/16", "10/8", "10.40.2.15/24", "2001:db8::1/64")) {
			assertThatIllegalArgumentException()
				.isThrownBy(() -> new ClientAddresses(new ClientAddressProperties(List.of(value))))
				.withMessageContaining("ASTRA_CLIENT_ADDRESS_TRUSTED_PROXIES")
				.withMessageContaining("astra.client-address.trusted-proxies")
				.withMessageContaining(value);
		}
	}

	@Test
	void limitsIpv6ByTheSlash64AndIpv4ByTheAddress() {
		assertThat(ClientAddresses.limitKey("2001:db8:1:2:aaaa::1")).isEqualTo("2001:db8:1:2:0:0:0:0/64")
			.isEqualTo(ClientAddresses.limitKey("2001:db8:1:2:bbbb:cccc:dddd:eeee"));
		assertThat(ClientAddresses.limitKey("2001:db8:1:3::1")).isEqualTo("2001:db8:1:3:0:0:0:0/64");
		assertThat(ClientAddresses.limitKey("198.51.100.7")).isEqualTo("198.51.100.7");
		assertThat(ClientAddresses.limitKey("198.51.100.8")).isEqualTo("198.51.100.8");
	}

	private static MockHttpServletRequest request(String peer, String... forwardedFor) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr(peer);
		for (String value : forwardedFor) {
			request.addHeader(ClientAddresses.FORWARDED_FOR, value);
		}
		return request;
	}

}
