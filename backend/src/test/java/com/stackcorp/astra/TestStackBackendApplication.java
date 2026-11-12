package com.stackcorp.astra;

import java.util.stream.Stream;

import com.stackcorp.astra.identity.session.DevLoginConfiguration;
import com.stackcorp.astra.local.LocalSeed;

import org.springframework.boot.SpringApplication;

/**
 * 로컬 실행. MySQL과 S3 호환 저장소를 container로 띄우고, 표본 데이터와 개발용 로그인을 더한다.
 * local 프로필(src/test/resources/application-local.properties)과 worker 프로필을 함께 켠다.
 */
public class TestStackBackendApplication {

	public static void main(String[] args) {
		String[] withProfiles = Stream.concat(Stream.of("--spring.profiles.include=local,worker"), Stream.of(args))
			.toArray(String[]::new);
		SpringApplication.from(StackBackendApplication::main)
			.with(TestcontainersConfiguration.class, DevLoginConfiguration.class, LocalSeed.class)
			.run(withProfiles);
	}

}
