package com.stackcorp.astra.learning;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 학교 강의실 내용을 필요할 때 읽기(ADR-0043).
 * @param requestTimeout 학교 요청 하나의 제한 시간
 * @param readBudget 화면 하나를 열 때 학교 읽기 전체(과목 묶기 포함)가 넘지 않을 시간
 * @param downloadBudget 파일 하나를 받을 때 학교 요청 전체가 넘지 않을 시간
 * @param listInterval 과목의 학교 파일모음 목록을 다시 읽기 전 간격. 이 간격 안에는 저장해 둔 목록을 쓴다
 */
@ConfigurationProperties("astra.school-content")
public record SchoolContentProperties(@DefaultValue("8s") Duration requestTimeout,
		@DefaultValue("20s") Duration readBudget, @DefaultValue("60s") Duration downloadBudget,
		@DefaultValue("10m") Duration listInterval) {
}
