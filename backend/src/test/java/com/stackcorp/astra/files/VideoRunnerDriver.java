package com.stackcorp.astra.files;

import com.stackcorp.astra.files.api.VideoTranscoder;

import org.springframework.context.ApplicationContext;

/**
 * 다른 package의 테스트가 영상 변환 worker를 일정 없이 직접 돌릴 때 쓴다.
 */
public final class VideoRunnerDriver {

	private VideoRunnerDriver() {
	}

	/** @return 작업 하나를 처리했는지 */
	public static boolean runOnce(ApplicationContext context) {
		return runner(context).runOnce();
	}

	/** 실제 transcoder 대신 주어진 구현으로 작업 하나를 처리한다. 일시 장애를 흉내 낼 때 쓴다. */
	public static boolean runOnce(ApplicationContext context, VideoTranscoder transcoder) {
		return runner(context).runOnce(transcoder);
	}

	/** 대기 작업이 없을 때까지 실행한다. 앞선 테스트가 남긴 작업도 비운다. */
	public static void drain(ApplicationContext context) {
		while (runOnce(context)) {
			// 남은 작업을 비운다.
		}
	}

	public static void withdrawRemovedFiles(ApplicationContext context) {
		runner(context).withdrawRemovedFiles();
	}

	public static void recoverExpired(ApplicationContext context) {
		runner(context).recoverExpired();
	}

	private static VideoTranscodeRunner runner(ApplicationContext context) {
		return context.getBean(VideoTranscodeRunner.class);
	}

}
