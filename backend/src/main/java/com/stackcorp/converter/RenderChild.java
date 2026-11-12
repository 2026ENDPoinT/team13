package com.stackcorp.converter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.stackcorp.converter.PdfRendering.Step;

/**
 * 문서 하나를 변환하는 자식 JVM의 진입점({@link RenderProcess}가 띄운다). 인자는 작업 디렉터리, 쪽 수 상한, 시작 쪽, 시작 쪽의
 * 그리기 단계, 시작 쪽의 텍스트를 뽑을지, 단계 하나의 제한 시간(ms)이다. 작업 디렉터리의 {@value #INPUT}을 읽고 {@value #OUTPUT}
 * 디렉터리에 쪽 파일을 쓴다.
 * <ul>
 * <li>단계를 시작할 때마다 {@value #PROGRESS} 파일에 "쪽 단계"를 쓴다. JVM이 도중에 끝나면 부모가 이 파일을 읽는다.</li>
 * <li>단계 하나가 제한 시간을 넘으면 그 자리에서 {@value #SLOW_EXIT}로 끝난다. 압축된 그리기 명령 수백만 개처럼 메모리는 적게 쓰고
 * 시간만 오래 쓰는 쪽, heap이 거의 차서 GC만 되풀이하는 쪽을 끊는다.</li>
 * <li>종료 코드: 0 성공, {@value #FAILURE_EXIT} 변환할 수 없는 입력({@value #FAILURE} 파일 첫 줄이 code, 나머지가 message),
 * {@value #SLOW_EXIT} 단계 제한 시간 초과, 1 그 밖의 오류. heap이 차면 JVM이 {@code -XX:+ExitOnOutOfMemoryError}로 3을
 * 돌려준다.</li>
 * </ul>
 */
public final class RenderChild {

	static final String INPUT = "input.pdf";

	static final String OUTPUT = "out";

	static final String PROGRESS = "progress";

	static final String FAILURE = "failure";

	static final int FAILURE_EXIT = 2;

	static final int SLOW_EXIT = 4;

	private static final long WATCH_MILLIS = 200;

	// 지금 단계를 시작한 시각(System.nanoTime)
	private static volatile long stepStarted = System.nanoTime();

	private RenderChild() {
	}

	public static void main(String[] args) {
		Path job = Path.of(args[0]);
		int maxPages = Integer.parseInt(args[1]);
		int from = Integer.parseInt(args[2]);
		Step render = Step.valueOf(args[3]);
		boolean withText = Boolean.parseBoolean(args[4]);
		long stepTimeout = Long.parseLong(args[5]) * 1_000_000;
		preferAsOomVictim();
		watch(stepTimeout);
		PdfRendering.Progress progress = (page, step) -> {
			stepStarted = System.nanoTime();
			Files.writeString(job.resolve(PROGRESS), page + " " + step, StandardCharsets.US_ASCII);
		};
		int exit;
		try {
			progress.at(0, Step.OPEN);
			PdfRendering.open(job.resolve(INPUT), maxPages)
				.writePages(job.resolve(OUTPUT), from, render, withText, progress);
			exit = 0;
		}
		catch (ConversionFailure ex) {
			exit = writeFailure(job, ex);
		}
		catch (IOException | RuntimeException ex) {
			exit = 1;
		}
		System.exit(exit);
	}

	private static void watch(long stepTimeoutNanos) {
		Thread.ofPlatform().daemon().name("render-step-watchdog").start(() -> {
			while (true) {
				try {
					Thread.sleep(WATCH_MILLIS);
				}
				catch (InterruptedException ex) {
					return;
				}
				if (System.nanoTime() - stepStarted > stepTimeoutNanos) {
					Runtime.getRuntime().halt(SLOW_EXIT);
				}
			}
		});
	}

	private static int writeFailure(Path job, ConversionFailure failure) {
		try {
			Files.writeString(job.resolve(FAILURE), failure.code() + "\n" + failure.getMessage(), StandardCharsets.UTF_8);
			return FAILURE_EXIT;
		}
		catch (IOException ex) {
			return 1;
		}
	}

	// container 메모리가 차서 커널이 process 하나를 끝내야 하면 converter 부모 대신 이 자식을 고르게 한다. Linux 밖에서는 파일이 없다.
	private static void preferAsOomVictim() {
		try {
			Files.writeString(Path.of("/proc/self/oom_score_adj"), "1000", StandardCharsets.US_ASCII);
		}
		catch (IOException | RuntimeException ex) {
			// 우선순위를 못 바꿔도 변환은 한다.
		}
	}

}
