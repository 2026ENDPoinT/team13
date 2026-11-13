package com.stackcorp.astra.integration.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.stackcorp.astra.files.api.DeliverySigner.SignedCookie;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CloudFrontDeliverySignerTests {

	@Test
	void signsPolicyLimitedToPathPrefixAndExpiry() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		KeyPair pair = generator.generateKeyPair();
		CloudFrontDeliverySigner signer = new CloudFrontDeliverySigner(new CloudFrontProperties(
				URI.create("https://lms.example.ac.kr"), "K2KEYPAIR", pem(pair)));
		Instant expires = Instant.parse("2026-11-11T10:00:00Z");

		Map<String, String> cookies = signer.sign("/doc/abc/", expires)
			.stream()
			.collect(Collectors.toMap(SignedCookie::name, SignedCookie::value));

		assertThat(cookies).containsEntry("CloudFront-Key-Pair-Id", "K2KEYPAIR");
		byte[] policy = cloudFrontBase64(cookies.get("CloudFront-Policy"));
		JsonNode statement = JsonMapper.builder().build().readTree(policy).path("Statement").path(0);
		assertThat(statement.path("Resource").stringValue()).isEqualTo("https://lms.example.ac.kr/doc/abc/*");
		assertThat(statement.path("Condition").path("DateLessThan").path("AWS:EpochTime").longValue())
			.isEqualTo(expires.getEpochSecond());
		Signature verify = Signature.getInstance("SHA1withRSA");
		verify.initVerify(pair.getPublic());
		verify.update(policy);
		assertThat(verify.verify(cloudFrontBase64(cookies.get("CloudFront-Signature")))).isTrue();
	}

	@Test
	void refusesWildcardsAndNonPrefixPaths() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		CloudFrontDeliverySigner signer = new CloudFrontDeliverySigner(new CloudFrontProperties(
				URI.create("https://lms.example.ac.kr"), "K", pem(generator.generateKeyPair())));

		for (String path : List.of("/doc/*", "doc/abc/", "/doc/abc")) {
			assertThatIllegalArgumentException().as(path).isThrownBy(() -> signer.sign(path, Instant.now()));
		}
	}

	private static String pem(KeyPair pair) {
		return "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())
				+ "\n-----END PRIVATE KEY-----\n";
	}

	// CloudFront는 Base64의 +, =, /를 -, _, ~로 바꿔 쓴다.
	private static byte[] cloudFrontBase64(String value) {
		return Base64.getDecoder()
			.decode(value.replace('-', '+').replace('_', '=').replace('~', '/').getBytes(StandardCharsets.US_ASCII));
	}

}
