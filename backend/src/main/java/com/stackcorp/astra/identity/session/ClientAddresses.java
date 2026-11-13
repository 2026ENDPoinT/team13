package com.stackcorp.astra.identity.session;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 로그인 기록, session, 로그인 요청 제한에 쓰는 브라우저 주소. 이 값을 쓰는 곳은 모두 이 class를 거친다.
 * <ul>
 * <li>연결한 쪽 주소가 {@code astra.client-address.trusted-proxies}(주소 또는 CIDR) 안일 때만 {@value #FORWARDED_FOR}를 읽는다.
 * 범위를 비우면(기본) 언제나 연결한 쪽 주소다.</li>
 * <li>header를 오른쪽부터 읽으며 믿는 proxy 주소는 건너뛰고, 처음 나오는 믿지 않는 주소를 브라우저 주소로 쓴다. 모두 믿는 주소면
 * 가장 왼쪽 값이다. 이것이 안전한 것은 믿는 hop마다 받은 header 뒤에 자기가 본 주소를 덧붙일 때뿐이다(CloudFront, Cloudflare,
 * 프론트엔드 서버 server.mjs). 덧붙이지 않는 앞단이 믿는 hop 바로 앞에 있으면 브라우저가 보낸 값을 읽게 된다.</li>
 * <li>값이 IP literal이 아니면(이름, {@code unknown}, 빈 칸, zone이 붙은 IPv6, 점 넷이 아닌 IPv4) 거기서 멈추고 그 값을 넘긴 가장
 * 가까운 proxy 주소를 쓴다. 주소는 IP literal로만 읽고 이름을 찾지 않는다. {@code [IPv6]}, {@code [IPv6]:port},
 * {@code IPv4:port}도 받는다.</li>
 * </ul>
 * 주소 단위 요청 제한은 {@link #limitKey}로 센다. IPv6는 /64 하나가 한 가입자이므로 그 안의 주소를 하나로 센다. 기록에는 주소 그대로
 * 남긴다.
 * <p>
 * Spring과 Tomcat의 forwarded header 처리는 켜지 않는다({@code server.forward-headers-strategy=none}). 켜면 Origin 검사가 보는 Host와
 * scheme까지 header로 바뀐다(ADR-0004 4번). 로컬 실행의 개발용 로그인이 보는 "이 컴퓨터에서 온 요청" 판정은 이 값이 아니라 연결한 쪽
 * 주소로 한다.
 */
@Component
@EnableConfigurationProperties(ClientAddressProperties.class)
public class ClientAddresses {

	static final String FORWARDED_FOR = "X-Forwarded-For";

	private static final String SETTING = "ASTRA_CLIENT_ADDRESS_TRUSTED_PROXIES(astra.client-address.trusted-proxies)";

	private static final Pattern IPV4 = Pattern.compile("[0-9]{1,3}(\\.[0-9]{1,3}){3}");

	private static final int IPV6_LIMIT_PREFIX = 64;

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

	/**
	 * 주소 단위 요청 제한의 key. IPv4는 주소 그대로, IPv6는 그 주소의 /64 범위다. 가입자 하나가 /64를 받으므로 요청마다 다른 주소를
	 * 써도 한도를 나눠 쓰지 못한다.
	 * @param address {@link #of}가 돌려준 주소
	 */
	public static String limitKey(String address) {
		Optional<InetAddress> parsed = literal(address);
		if (parsed.isEmpty() || parsed.get().getAddress().length != 16) {
			return address;
		}
		byte[] network = Arrays.copyOf(parsed.get().getAddress(), 16);
		Arrays.fill(network, IPV6_LIMIT_PREFIX / 8, 16, (byte) 0);
		try {
			return InetAddress.getByAddress(network).getHostAddress() + "/" + IPV6_LIMIT_PREFIX;
		}
		catch (UnknownHostException ex) {
			throw new IllegalStateException(ex);
		}
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

	// IPv4는 점으로 나뉜 네 수만 받는다. InetAddress는 "10", "172.18"도 주소로 읽어 다른 값으로 늘인다.
	private static Optional<InetAddress> literal(String value) {
		if (value == null || value.isEmpty() || value.indexOf('%') >= 0) {
			return Optional.empty();
		}
		if (value.indexOf(':') < 0 && !IPV4.matcher(value).matches()) {
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
				throw invalid(value, "IP 주소나 CIDR이 아닙니다. IPv4는 점으로 나뉜 네 수로 적습니다");
			}
			byte[] bytes = address.get().getAddress();
			int prefix;
			try {
				prefix = slash < 0 ? bytes.length * 8 : Integer.parseInt(value.substring(slash + 1).strip());
			}
			catch (NumberFormatException ex) {
				throw invalid(value, "prefix 길이가 수가 아닙니다");
			}
			if (prefix < 1 || prefix > bytes.length * 8) {
				throw invalid(value, "prefix 길이는 1부터 " + bytes.length * 8 + "까지입니다. 모든 주소를 믿는 범위는 받지 않습니다");
			}
			Cidr cidr = new Cidr(bytes, prefix);
			if (!Arrays.equals(cidr.masked(bytes), bytes)) {
				throw invalid(value, "prefix 밖의 주소 bit가 0이 아닙니다. 범위의 첫 주소를 적습니다");
			}
			return cidr;
		}

		private static IllegalArgumentException invalid(String value, String reason) {
			return new IllegalArgumentException(SETTING + "의 값 '" + value + "': " + reason);
		}

		boolean contains(InetAddress address) {
			byte[] bytes = address.getAddress();
			return bytes.length == this.network.length && Arrays.equals(masked(bytes), this.network);
		}

		private byte[] masked(byte[] bytes) {
			byte[] masked = new byte[bytes.length];
			for (int bit = 0; bit < this.prefix; bit += 8) {
				int keep = Math.min(8, this.prefix - bit);
				masked[bit / 8] = (byte) (bytes[bit / 8] & (0xFF << (8 - keep)));
			}
			return masked;
		}

	}

}
