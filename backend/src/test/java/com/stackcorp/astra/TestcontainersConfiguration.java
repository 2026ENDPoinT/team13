package com.stackcorp.astra;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.stackcorp.astra.files.storage.StorageProperties;
import com.stackcorp.astra.identity.session.SessionStore;
import com.stackcorp.converter.ConverterServer;
import com.stackcorp.converter.FakeOfficeEngine;
import com.stackcorp.transcoder.TranscoderServer;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;

/**
 * 테스트와 로컬 실행(TestStackBackendApplication)에 쓰는 MySQL, S3 호환 저장소, converter.
 * converter는 편의상 같은 process에서 띄운다. 운영에서는 격리한 container로 실행한다(ADR-0006).
 * <p>
 * MySQL과 S3 mock container는 test JVM 하나에 하나씩만 띄우고 모든 Spring context가 함께 쓴다. context마다 container를 띄우면
 * context cache가 JVM이 끝날 때까지 그것들을 붙잡아 Docker VM의 memory가 모자란다. 대신 context마다 번호를 하나 받아 자기
 * database({@code astra_c<번호>})와 bucket({@code astra-content-c<번호>}, {@code astra-delivery-c<번호>})을 쓴다. Flyway는
 * context마다 자기 database를 처음부터 migrate하고, 한 context의 worker와 저장소 GC는 다른 context의 행과 객체를 보지 못한다.
 * container는 Spring bean이 아니므로 context가 닫혀도 멈추지 않는다. JVM이 끝나면 Testcontainers의 Ryuk이 지운다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	private static final int S3_PORT = 9090;

	private static final AtomicInteger CONTEXTS = new AtomicInteger();

	private static MySQLContainer mysql;

	private static GenericContainer<?> s3;

	@Bean
	TestNamespace testNamespace() {
		return new TestNamespace(CONTEXTS.incrementAndGet());
	}

	/**
	 * context의 database. 시간대 연결 속성(ADR-0039)은 URL에 넣지 않고 InstitutionTimeEnvironment가 더한 Hikari 속성으로 받는다.
	 */
	@Bean
	JdbcConnectionDetails testDatabase(TestNamespace namespace) {
		MySQLContainer server = mysql();
		String database = namespace.database();
		String url = "jdbc:mysql://" + server.getHost() + ":" + server.getMappedPort(MySQLContainer.MYSQL_PORT) + "/";
		try (Connection root = DriverManager.getConnection(url, "root", server.getPassword());
				Statement statement = root.createStatement()) {
			statement.execute("CREATE DATABASE `" + database + "`");
			statement.execute("GRANT ALL PRIVILEGES ON `" + database + "`.* TO '" + server.getUsername() + "'@'%'");
		}
		catch (SQLException ex) {
			throw new IllegalStateException("cannot create test database " + database, ex);
		}
		return new JdbcConnectionDetails() {

			@Override
			public String getUsername() {
				return server.getUsername();
			}

			@Override
			public String getPassword() {
				return server.getPassword();
			}

			@Override
			public String getJdbcUrl() {
				return url + database;
			}

		};
	}

	// office 형식은 LibreOffice 대신 같은 process의 가짜 엔진이 PDF로 바꾼다(FakeOfficeEngine). 로컬 실행도 같다.
	@Bean(destroyMethod = "close")
	ConverterServer converterServer() throws IOException {
		return ConverterServer.start(new InetSocketAddress("127.0.0.1", 0), 50L << 20, new FakeOfficeEngine());
	}

	@Bean
	DynamicPropertyRegistrar testServices(TestNamespace namespace, ConverterServer converterServer) {
		GenericContainer<?> storage = s3();
		return (registry) -> {
			registry.add("astra.storage.endpoint",
					() -> "http://" + storage.getHost() + ":" + storage.getMappedPort(S3_PORT));
			registry.add("astra.storage.path-style", () -> "true");
			registry.add("astra.storage.access-key", () -> "test");
			registry.add("astra.storage.secret-key", () -> "test");
			registry.add("astra.storage.content-bucket", () -> namespace.bucket("content"));
			registry.add("astra.storage.delivery-bucket", () -> namespace.bucket("delivery"));
			registry.add("astra.converter.url", () -> "http://127.0.0.1:" + converterServer.port());
		};
	}

	// 영상 transcoder도 편의상 같은 process에서 띄운다. ffmpeg와 ffprobe가 PATH에 있어야 한다(ADR-0016).
	@Bean(destroyMethod = "close")
	TranscoderServer transcoderServer() throws IOException {
		return TranscoderServer.start(new InetSocketAddress("127.0.0.1", 0),
				TranscoderServer.Limits.fromEnvironment(java.util.Map.of()));
	}

	@Bean
	DynamicPropertyRegistrar transcoderService(TranscoderServer transcoderServer) {
		return (registry) -> registry.add("astra.transcoder.url", () -> "http://127.0.0.1:" + transcoderServer.port());
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

	private static synchronized MySQLContainer mysql() {
		if (mysql == null) {
			MySQLContainer container = new MySQLContainer(DockerImageName
				.parse("mysql:8.4.13@sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902")
				.asCompatibleSubstituteFor("mysql"));
			container.start();
			mysql = container;
		}
		return mysql;
	}

	private static synchronized GenericContainer<?> s3() {
		if (s3 == null) {
			GenericContainer<?> container = new GenericContainer<>(DockerImageName
				.parse("adobe/s3mock:5.2.3@sha256:ab01a6946750f451ca215a47e91030695b260e4003b8a5a6201d25029b8fca92"))
				.withExposedPorts(S3_PORT);
			container.start();
			s3 = container;
		}
		return s3;
	}

	/** Spring context 하나가 공유 container 안에서 쓰는 이름. 번호는 JVM 안에서 context가 생긴 순서다. */
	public record TestNamespace(int id) {

		public String database() {
			return "astra_c" + this.id;
		}

		public String bucket(String purpose) {
			return "astra-" + purpose + "-c" + this.id;
		}

	}

	public record TestBuckets(String content, String delivery) {
	}

}
