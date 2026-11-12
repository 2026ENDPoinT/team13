package com.stackcorp.astra.summary.chat;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 학습 대화 설정(ADR-0032).
 *
 * @param pollInterval worker가 답을 기다리는 질문을 찾는 간격
 * @param sweepInterval 멈춘 worker의 질문을 닫고 지운·보존기한이 지난 대화를 정리하는 간격
 * @param lease 질문 하나의 실행 lease. 모델을 부르기 전마다 갱신한다. 모델 호출 제한 시간보다 길어야 한다
 * @param rateLimit 한 사용자가 rateWindow 안에 보낼 수 있는 질문 수
 * @param dailyTokenBudget 과목 하나가 하루(DB 날짜)에 학습 대화로 쓸 수 있는 token 수(입력과 출력의 합)
 * @param questionMaxChars 질문 길이 상한
 * @param historyExchanges 모델에 함께 보내는 지난 질문·답 수
 * @param historyMaxChars 지난 질문·답에 쓰는 글자 수 상한
 * @param maxToolSteps 답 하나에서 도구를 쓸 수 있는 모델 호출 수. 그다음 호출은 도구 없이 답을 마무리한다
 * @param maxInputTokensPerAnswer 답 하나에 쓰는 입력 token 상한. 넘으면 도구를 더 주지 않는다
 * @param maxOutputTokens 모델 호출 하나의 출력 token 상한
 * @param pageMaxChars read_page가 돌려주는 쪽 텍스트 상한
 * @param retention 마지막 질문 뒤 대화를 남기는 기간. 비우면 기간으로 지우지 않는다(소유자 삭제와 파일 삭제로만 지운다)
 * @param workerId lease 소유자 이름. 비우면 host 이름과 process ID로 만든다
 */
@ConfigurationProperties("astra.studychat")
public record StudyChatProperties(@DefaultValue("1s") Duration pollInterval,
		@DefaultValue("30s") Duration sweepInterval, @DefaultValue("10m") Duration lease,
		@DefaultValue("20") int rateLimit, @DefaultValue("10m") Duration rateWindow,
		@DefaultValue("2000000") long dailyTokenBudget, @DefaultValue("2000") int questionMaxChars,
		@DefaultValue("4") int historyExchanges, @DefaultValue("6000") int historyMaxChars,
		@DefaultValue("4") int maxToolSteps, @DefaultValue("60000") long maxInputTokensPerAnswer,
		@DefaultValue("2000") int maxOutputTokens, @DefaultValue("4000") int pageMaxChars, Duration retention,
		String workerId) {

	public StudyChatProperties {
		if (rateLimit < 1 || dailyTokenBudget < 1 || questionMaxChars < 1 || historyExchanges < 0 || maxToolSteps < 0
				|| maxOutputTokens < 1 || pageMaxChars < 200) {
			throw new IllegalArgumentException("astra.studychat.* 값이 범위를 벗어났습니다.");
		}
		if (retention != null && (retention.isNegative() || retention.isZero())) {
			throw new IllegalArgumentException("astra.studychat.retention은 0보다 커야 합니다.");
		}
	}

}
