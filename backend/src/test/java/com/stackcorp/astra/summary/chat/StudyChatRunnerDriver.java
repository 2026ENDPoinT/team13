package com.stackcorp.astra.summary.chat;

import org.springframework.context.ApplicationContext;

/**
 * 다른 package의 테스트가 학습 대화 worker를 한 번 돌릴 때 쓴다.
 */
public final class StudyChatRunnerDriver {

	private StudyChatRunnerDriver() {
	}

	/** @return 질문 하나를 처리했는지 */
	public static boolean runOnce(ApplicationContext context) {
		return context.getBean(StudyChatRunner.class).runOnce();
	}

}
