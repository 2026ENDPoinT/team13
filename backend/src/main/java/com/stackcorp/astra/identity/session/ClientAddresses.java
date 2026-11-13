package com.stackcorp.astra.identity.session;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 로그인 기록, session, 로그인 요청 제한에 쓰는 브라우저 주소. 이 값을 쓰는 곳은 모두 이 class를 거친다.
 * <ul>
 * <li>연결한 쪽 주소가 {@code astra.client-address.trusted-proxies}(주소 또는 CIDR) 안일 때만 {@value #FORWARDED_FOR}를 읽는다.
 * 범위를 비우면(기본) 언제나 연결한 쪽 주소다.</li>
 * <li>header를 오른쪽부터 읽으며 믿는 proxy 주소는 건너뛰고, 처음 나오는 믿지 않는 주소를 브라우저 주소로 쓴다. 앞단(CloudFront,
 * Cloudflare, 프론트엔드 서버 server.mjs)은 받은 header 뒤에 자기가 본 주소를 덧붙이므로, 그 왼쪽 값은 브라우저가 마음대로 넣을 수
 * 있어 읽지 않는다. 모두 믿는 주소면 가장 왼쪽 값이다.</li>
 * <li>값이 IP literal이 아니면(이름, {@code unknown}, 빈 칸, zone이 붙은 IPv6) 거기서 멈추고 그 값을 넘긴 가장 가까운 proxy 주소를
 * 쓴다. 주소는 IP literal로만 읽고 이름을 찾지 않는다. {@code [IPv6]}, {@code [IPv6]:port}, {@code IPv4:port}도 받는다.</li>
 * </ul>
 * Spring과 Tomcat의 forwarded header 처리(server.forward-headers-strategy)는 켜지 않는다. 켜면 Origin 검사가 보는 Host와 scheme까지
 * header로 바뀐다(ADR-0004 4번). 로컬 실행의 개발용 로그인이 보는 "이 컴퓨터에서 온 요청" 판정은 이 값이 아니라 연결한 쪽 주소로 한다.
 */
@Component
@EnableConfigurationProperties(ClientAddressProperties.class)
public class ClientAddresses {

	static final String FORWARDED_FOR = "X-Forwarded-For";

	private final List<Cidr> trusted;

	ClientAddresses(ClientAddressProperties properties) {
		this.trusted = properties.trustedProxies().stream().map(Cidr::parse).toList();
	}

	/** @return 브라우저 주소. IP literal이다 */
	public String of(HttpServletRequest request) {
		String peer = request.getRemoteAddr();
		if (this.trusted.isEmpty() || literal(peer).filter(this::trusted).isEmpty()) {
			return peer;
		}
		List<String> hops = hops(request);
		String client = peer;
		for (int i = hops.size() - 1; i >= 0; i--) {
			Optional<InetAddress> hop = hop(hops.get(i));
			if (hop.isEmpty()) {
				return client;
			}
			client = hop.get().getHostAddress();
			if (!trusted(hop.get())) {
				return client;
			}
		}
		return client;
	}

	private boolean trusted(InetAddress address) {
		return this.trusted.stream().anyMatch((cidr) -> cidr.contains(address));
	}

	// header가 여러 줄이면 받은 순서대로 이어 붙인 하나의 목록이다. 빈 칸도 항목으로 남겨 형식 오류로 본다.
	private static List<String> hops(HttpServletRequest request) {
		List<String> hops = new ArrayList<>();
		for (String value : Collections.list(request.getHeaders(FORWARDED_FOR))) {
			Collections.addAll(hops, value.split(",", -1));
		}
		return hops;
	}

	private static Optional<InetAddress> hop(String value) {
		String host = value.strip();
		if (host.startsWith("[")) {
			int close = host.indexOf(']');
			if (close < 0 || !port(host.substring(close + 1), true)) {
				return Optional.empty();
			}
			return literal(host.substring(1, close));
		}
		int colon = host.indexOf(':');
		if (colon >= 0 && colon == host.lastIndexOf(':')) {
			if (!port(host.substring(colon), false)) {
				return Optional.empty();
			}
			host = host.substring(0, colon);
		}
		return literal(host);
	}

	// ":0"~":65535" 또는(괄호 뒤에서만) 빈 값.
	private static boolean port(String value, boolean optional) {
		if (value.isEmpty()) {
			return optional;
		}
		return value.length() >= 2 && value.length() <= 6 && value.charAt(0) == ':'
				&& value.substring(1).chars().allMatch((c) -> c >= '0' && c <= '9')
				&& Integer.parseInt(value.substring(1)) <= 65535;
	}

	private static Optional<InetAddress> literal(String value) {
		if (value == null || value.isEmpty() || value.indexOf('%') >= 0) {
			return Optional.empty();
		}
		try {
			return Optional.of(InetAddress.ofLiteral(value));
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	private record Cidr(byte[] network, int prefix) {

		static Cidr parse(String value) {
			int slash = value.indexOf('/');
			Optional<InetAddress> address = literal(slash < 0 ? value.strip() : value.substring(0, slash).strip());
			if (address.isEmpty()) {
				throw new IllegalArgumentException("astra.client-address.trusted-proxies: not a CIDR: " + value);
			}
			byte[] bytes = address.get().getAddress();
			int prefix;
			try {
				prefix = slash < 0 ? bytes.length * 8 : Integer.parseInt(value.substring(slash + 1).strip());
			}
			catch (NumberFormatException ex) {
				throw new IllegalArgumentException("astra.client-address.trusted-proxies: not a CIDR: " + value);
			}
			if (prefix < 0 || prefix > bytes.length * 8) {
				throw new IllegalArgumentException("astra.client-address.trusted-proxies: not a CIDR: " + value);
			}
			return new Cidr(bytes, prefix);
		}

		boolean contains(InetAddress address) {
			byte[] bytes = address.getAddress();
			if (bytes.length != this.network.length) {
				return false;
			}
			int full = this.prefix / 8;
			for (int i = 0; i < full; i++) {
				if (bytes[i] != this.network[i]) {
					return false;
				}
			}
			int rest = this.prefix % 8;
			if (rest == 0) {
				return true;
			}
			int mask = (0xFF << (8 - rest)) & 0xFF;
			return (bytes[full] & mask) == (this.network[full] & mask);
		}

	}

}
