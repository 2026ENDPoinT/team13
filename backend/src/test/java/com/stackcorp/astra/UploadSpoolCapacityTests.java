package com.stackcorp.astra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.util.unit.DataSize;
import org.yaml.snakeyaml.Yaml;

/**
 * api container가 multipart 본문을 임시로 쓰는 곳(spool)의 compose 설정을 본다. 요청 thread마다
 * multipart 요청 상한만큼 쓸 수 있다. spool이 작은 tmpfs면 몇 개의 큰 업로드만으로 가득 차 모든 업로드가 실패한다. spool이 tmpfs면 그
 * 크기가 {@code threads.max × max-request-size} 이상인지 본다. host 디스크의 named volume이면 compose 설정(volume과 이미지의 디렉터리
 * 소유자)만 본다. host 디스크의 빈 용량은 배포 환경에 달려 있어 이 테스트가 보지 않는다.
 */
class UploadSpoolCapacityTests {

	@Test
	@SuppressWarnings("unchecked")
	void apiSpoolsMultipartBodiesOnAWritableVolumeOrALargeEnoughTmpfs() throws IOException {
		Properties shipped = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
		long perRequest = DataSize.parse(shipped.getProperty("spring.servlet.multipart.max-request-size")).toBytes();
		int threads = Integer.parseInt(shipped.getProperty("server.tomcat.threads.max"));
		long worstCase = perRequest * threads;

		Map<String, Object> api;
		try (Reader reader = Files.newBufferedReader(Path.of("compose.yaml"))) {
			Map<String, Object> compose = new Yaml().load(reader);
			api = (Map<String, Object>) ((Map<String, Object>) compose.get("services")).get("api");
		}
		Map<String, Object> environment = (Map<String, Object>) api.getOrDefault("environment", Map.of());
		// 지정하지 않으면 Tomcat은 java.io.tmpdir(/tmp) 아래 작업 디렉터리에 쓴다.
		String spool = String.valueOf(environment.getOrDefault("SPRING_SERVLET_MULTIPART_LOCATION", "/tmp"));

		Optional<String> tmpfs = ((List<String>) api.getOrDefault("tmpfs", List.of())).stream()
			.filter((entry) -> within(spool, entry.split(":", 2)[0]))
			.findFirst();
		if (tmpfs.isPresent()) {
			long size = DataSize.parse(option(tmpfs.get(), "size").toUpperCase() + "B").toBytes();
			assertThat(size).as("tmpfs %s holding the multipart spool %s vs %d threads x %s", tmpfs.get(), spool,
					threads, DataSize.ofBytes(perRequest))
				.isGreaterThanOrEqualTo(worstCase);
			return;
		}
		// tmpfs가 아니면 named volume(host 디스크)이어야 한다. root filesystem은 읽기 전용이다.
		assertThat((List<String>) api.getOrDefault("volumes", List.of())).as("volume holding %s", spool)
			.anyMatch((entry) -> {
				String[] parts = entry.split(":");
				return parts.length >= 2 && !parts[0].startsWith("/") && !parts[0].startsWith(".")
						&& within(spool, parts[1]);
			});
		// named volume은 처음 만들 때 이미지에 있는 디렉터리의 소유자를 물려받는다. 실행 user(10001)가 쓸 수 있어야 한다.
		assertThat(Files.readString(Path.of("Dockerfile"))).as("Dockerfile creates %s owned by 10001", spool)
			.containsPattern("install --directory --owner=10001 --group=10001 --mode=0700 [^\\n]*" + spool + "\\b");
	}

	private static boolean within(String path, String mount) {
		return path.equals(mount) || path.startsWith(mount.endsWith("/") ? mount : mount + "/");
	}

	private static String option(String tmpfs, String name) {
		for (String option : tmpfs.split(":", 2)[1].split(",")) {
			if (option.startsWith(name + "=")) {
				return option.substring(name.length() + 1);
			}
		}
		throw new AssertionError("tmpfs " + tmpfs + " has no " + name);
	}

}
