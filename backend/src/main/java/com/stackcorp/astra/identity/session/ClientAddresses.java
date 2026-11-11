package com.stackcorp.astra.identity.session;

import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 로그인 기록, session, 등록 기기, 요청 제한에 쓰는 브라우저 주소.
 * <ul>
 * <li>CloudFront는 VPC origin으로 앱에 HTTP로 연결하므로 연결한 쪽 주소는 CloudFront ENI다. X-Forwarded-For는 CloudFront가 뒤에
 * 붙이기만 해서 왼쪽 값을 브라우저가 정할 수 있으므로 쓰지 않는다. CloudFront가 만들어 넘기는 {@value #VIEWER_ADDRESS}만
 * 쓴다(infra/terraform/modules/edge).</li>
 * <li>연결한 쪽 주소가 {@code astra.client-address.trusted-proxies} 범위일 때만 그 header를 믿는다. app port는 security group이
 * CloudFront VPC origin에만 열어 두므로 그 범위에서 온 연결은 CloudFront를 거친 요청이다. 다른 곳(같은 host의 container, 로컬
 * 실행)에서 온 연결의 header는 무시한다. 범위를 비우면(기본) 언제나 연결한 쪽 주소다.</li>
 * <li>header가 없거나, 둘 이상이거나, {@code IP:port} 형식이 아니면 연결한 쪽 주소를 쓴다. 주소는 IP literal로만 읽고 이름을
 * 찾지 않는다.</li>
 * </ul>
 * 로컬 실행의 개발용 로그인이 보는 "이 컴퓨터에서 온 요청" 판정은 이 값이 아니라 연결한 쪽 주소로 한다.
 */
@Component
@EnableConfigurationProperties(ClientAddressProperties.class)
public class ClientAddresses {

	static final String VIEWER_ADDRESS = "CloudFront-Viewer-Address";

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
		List<String> values = Collections.list(request.getHeaders(VIEWER_ADDRESS));
		if (values.size() != 1) {
			return peer;
		}
		return viewer(values.get(0)).map(InetAddress::getHostAddress).orElse(peer);
	}

	private boolean trusted(InetAddress address) {
		return this.trusted.stream().anyMatch((cidr) -> cidr.contains(address));
	}

	// CloudFront 형식은 "198.51.100.10:46532"이고 IPv6도 괄호 없이 주소 뒤에 ":port"를 붙인다.
	private static Optional<InetAddress> viewer(String value) {
		int colon = value.lastIndexOf(':');
		if (colon <= 0 || colon == value.length() - 1 || value.length() - colon - 1 > 5) {
			return Optional.empty();
		}
		if (!value.substring(colon + 1).chars().allMatch((c) -> c >= '0' && c <= '9')) {
			return Optional.empty();
		}
		String host = value.substring(0, colon);
		if (host.startsWith("[") && host.endsWith("]")) {
			host = host.substring(1, host.length() - 1);
		}
		return literal(host);
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
