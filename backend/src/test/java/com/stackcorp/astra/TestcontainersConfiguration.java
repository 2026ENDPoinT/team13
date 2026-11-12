package com.stackcorp.astra;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.stackcorp.astra.files.storage.StorageProperties;
import com.stackcorp.astra.identity.session.SessionStore;
import com.stackcorp.converter.ConverterServer;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;

/**
 * 테스트와 로컬 실행(TestStackBackendApplication)에 쓰는 MySQL, S3 호환 저장소, converter.
 * converter는 편의상 같은 process에서 띄운다. 운영에서는 격리한 container로 실행한다(ADR-0006).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	private static final int S3_PORT = 9090;

	@Bean
	@ServiceConnection
	MySQLContainer mysqlContainer() {
		return new MySQLContainer(DockerImageName.parse("mysql:8.4"));
	}

	@Bean
	GenericContainer<?> s3Container() {
		return new GenericContainer<>(DockerImageName.parse("adobe/s3mock:5.2.3")).withExposedPorts(S3_PORT)
			.waitingFor(Wait.forListeningPort());
	}

	@Bean(destroyMethod = "close")
	ConverterServer converterServer() throws IOException {
		return ConverterServer.start(new InetSocketAddress("127.0.0.1", 0), 50L << 20);
	}

	@Bean
	DynamicPropertyRegistrar testServices(GenericContainer<?> s3Container, ConverterServer converterServer) {
		return (registry) -> {
			registry.add("astra.storage.endpoint",
					() -> "http://" + s3Container.getHost() + ":" + s3Container.getMappedPort(S3_PORT));
			registry.add("astra.storage.path-style", () -> "true");
			registry.add("astra.storage.access-key", () -> "test");
			registry.add("astra.storage.secret-key", () -> "test");
			registry.add("astra.converter.url", () -> "http://127.0.0.1:" + converterServer.port());
		};
	}

	@Bean
	TestBuckets testBuckets(S3Client s3, StorageProperties properties) {
		for (String bucket : new String[] { properties.contentBucket(), properties.deliveryBucket() }) {
			try {
				s3.createBucket((b) -> b.bucket(bucket));
			}
			catch (BucketAlreadyOwnedByYouException ex) {
				// 이미 있다.
			}
		}
		return new TestBuckets(properties.contentBucket(), properties.deliveryBucket());
	}

	@Bean
	TestData testData(JdbcClient jdbc, S3Client s3, TestBuckets buckets, SessionStore sessions) {
		return new TestData(jdbc, s3, buckets, sessions);
	}

	public record TestBuckets(String content, String delivery) {
	}

}
