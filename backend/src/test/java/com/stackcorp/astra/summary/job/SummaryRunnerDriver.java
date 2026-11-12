package com.stackcorp.astra.summary.job;

import org.springframework.context.ApplicationContext;

/**
 * 다른 package의 테스트가 요약 worker를 한 번 돌릴 때 쓴다.
 */
public final class SummaryRunnerDriver {

	private SummaryRunnerDriver() {
	}

	/** @return 작업 하나를 처리했는지 */
	public static boolean runOnce(ApplicationContext context) {
		return context.getBean(SummaryJobRunner.class).runOnce();
	}

}
