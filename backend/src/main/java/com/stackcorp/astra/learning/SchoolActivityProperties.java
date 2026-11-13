package com.stackcorp.astra.learning;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;

/**
 * 학번·학교 비밀번호 로그인 때 학교 강의실 학습활동 목록을 가져오는 설정(ADR-0042). {@code signInWait}의 속성 이름은 {@code wait}다. 학교 로그인({@code astra.eclass-login.base-url})이
 * 없으면 가져올 것도 없다.
 * @param enabled 끄면 가져오지 않고 학교 session을 로그인 응답 전에 버린다
 * @param signInWait 로그인 응답이 가져오기를 기다리는 최대 시간. 넘으면 나머지는 뒤에서 이어 간다. 0이면 기다리지 않는다
 * @param budget 가져오기 한 번 전체의 최대 시간. 넘으면 남은 과목은 읽지 않는다
 * @param requestTimeout 학교 요청 하나의 제한 시간
 * @param maxCourses 한 번에 읽는 과목 수 상한(학교 메인 화면 순서)
 * @param interval 같은 학생이 이 간격 안에 다시 로그인하면 가져오지 않는다
 * @param concurrency 동시에 도는 가져오기 수. 더 들어오면 그 로그인은 가져오지 않는다
 */
@ConfigurationProperties("astra.school-activities")
record SchoolActivityProperties(@DefaultValue("true") boolean enabled, @Name("wait") @DefaultValue("3s") Duration signInWait,
		@DefaultValue("60s") Duration budget, @DefaultValue("8s") Duration requestTimeout,
		@DefaultValue("20") int maxCourses, @DefaultValue("10m") Duration interval,
		@DefaultValue("4") int concurrency) {

	SchoolActivityProperties {
		if (signInWait.isNegative() || signInWait.compareTo(Duration.ofSeconds(30)) > 0) {
			throw new IllegalArgumentException("astra.school-activities.wait는 0 이상 30초 이하여야 합니다.");
		}
		if (budget.compareTo(Duration.ofSeconds(1)) < 0 || budget.compareTo(Duration.ofMinutes(10)) > 0) {
			throw new IllegalArgumentException("astra.school-activities.budget은 1초 이상 10분 이하여야 합니다.");
		}
		if (requestTimeout.compareTo(Duration.ofSeconds(1)) < 0 || requestTimeout.compareTo(Duration.ofMinutes(1)) > 0) {
			throw new IllegalArgumentException("astra.school-activities.request-timeout은 1초 이상 1분 이하여야 합니다.");
		}
		if (maxCourses < 1 || maxCourses > 60) {
			throw new IllegalArgumentException("astra.school-activities.max-courses는 1 이상 60 이하여야 합니다.");
		}
		if (interval.isNegative()) {
			throw new IllegalArgumentException("astra.school-activities.interval은 0 이상이어야 합니다.");
		}
		if (concurrency < 1 || concurrency > 32) {
			throw new IllegalArgumentException("astra.school-activities.concurrency는 1 이상 32 이하여야 합니다.");
		}
	}

}
