package com.stackcorp.astra;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.stackcorp.astra.assessment.TestQuizClock;
import com.stackcorp.astra.learning.TestLearningClock;
import com.stackcorp.astra.summary.FakeLlm;

/**
 * MySQL, S3 호환 저장소, converter를 띄운 통합 테스트의 공통 설정. 모델만 가짜다. 같은 설정을 쓰므로 Spring context 하나를
 * 함께 쓴다. worker 프로필이지만 스케줄은 테스트가 직접 부른다.
 */
@SpringBootTest(properties = { "astra.summary.poll-interval=1h", "astra.summary.lease-renewal=1h",
		"astra.summary.lease=2h", "astra.break-glass.external-ref=" + IntegrationTest.BREAK_GLASS_REF,
		"astra.break-glass.totp-secret=" + IntegrationTest.TOTP_SECRET,
		"astra.cloudfront.base-url=https://lms.example.ac.kr", "astra.cloudfront.key-pair-id=K2TESTKEYPAIR",
		"astra.upload.rate-limit=6", "astra.learning.lease-sweep-interval=1h",
		"astra.files.gc.interval=1h", "astra.transcoder.worker.poll-interval=1h", "astra.quiz.close-interval=1h",
		"astra.metrics.backlog-interval=1h", "astra.filebox.zip.poll-interval=1h", "astra.filebox.zip.max-total-size=12MB",
		"astra.files.conversion.poll-interval=1h", "astra.files.conversion.recovery-interval=1h",
		"astra.studychat.poll-interval=1h", "astra.studychat.sweep-interval=1h", "astra.studychat.rate-limit=8",
		"astra.avatar.poll-interval=1h", "astra.avatar.recovery-interval=1h", "astra.scan.poll-interval=1h",
		"astra.client-address.trusted-proxies=" + IntegrationTest.TRUSTED_PROXIES, "astra.notification.site-batch=25" })
@AutoConfigureMockMvc
@ActiveProfiles("worker")
@Import({ TestcontainersConfiguration.class, FakeLlm.Configuration.class, TestLearningClock.class,
		TestQuizClock.class })
public abstract class IntegrationTest {

	public static final String BREAK_GLASS_REF = "break-glass-admin";

	/** CloudFront VPC origin이 있는 app subnet을 흉내 낸다. MockMvc의 기본 주소(127.0.0.1)는 이 범위 밖이다. */
	public static final String TRUSTED_PROXIES = "10.40.2.0/24";

	public static final String BREAK_GLASS_PASSWORD = "correct horse battery staple";

	public static final String TOTP_SECRET = "JBSWY3DPEHPK3PXP";

	public static final KeyPair CLOUDFRONT_KEY = rsa();

	@DynamicPropertySource
	static void secrets(DynamicPropertyRegistry registry) {
		String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(BREAK_GLASS_PASSWORD);
		registry.add("astra.break-glass.password-hash", () -> hash);
		registry.add("astra.cloudfront.private-key", () -> "-----BEGIN PRIVATE KEY-----\n"
				+ Base64.getMimeEncoder().encodeToString(CLOUDFRONT_KEY.getPrivate().getEncoded())
				+ "\n-----END PRIVATE KEY-----\n");
	}

	private static KeyPair rsa() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
