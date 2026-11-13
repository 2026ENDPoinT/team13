package com.stackcorp.converter;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 외부 실행 파일 하나를 고정한 인자 목록으로 실행하고 제한 시간 안에 끝나기를 기다린다(FILES-12).
 * <ul>
 * <li>shell을 거치지 않는다. 인자 목록의 첫 값은 실행 파일의 절대 경로여야 한다</li>
 * <li>환경 변수는 비운 뒤 넘긴 값만 넣는다. 표준 입력은 시작하자마자 닫고(/dev/null처럼 바로 EOF) 표준 출력과 오류는 버린다. 출력에
 * 문서 내용이 섞일 수 있기 때문이다</li>
 * <li>기다리는 동안 자식 process를 계속 기록한다. 제한 시간이 지나면 기록한 자식과 process를 모두 강제로 끝낸다. 정상 종료한
 * 뒤에도 남은 자식은 끝낸다. 기록하기 전에 생기고 사라진 손자 process는 놓칠 수 있다</li>
 * </ul>
 */
final class BoundedProcess {

	private static final long POLL_MILLIS = 50;

	private static final Duration KILL_WAIT = Duration.ofSeconds(5);

	private BoundedProcess() {
	}

	/**
	 * @param timedOut 제한 시간이 지나 강제로 끝냈는지
	 * @param exitCode 종료 코드. 강제로 끝냈으면 의미가 없다
	 */
	record Outcome(boolean timedOut, int exitCode) {
	}

	static Outcome run(List<String> command, Map<String, String> environment, Path directory, Duration timeout)
			throws IOException {
		if (command.isEmpty() || !Path.of(command.getFirst()).isAbsolute()) {
			throw new IllegalArgumentException("실행 파일은 절대 경로로 지정해야 합니다.");
		}
		ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
			.redirectOutput(ProcessBuilder.Redirect.DISCARD)
			.redirectError(ProcessBuilder.Redirect.DISCARD);
		builder.environment().clear();
		builder.environment().putAll(environment);
		Process process = builder.start();
		Set<ProcessHandle> seen = new LinkedHashSet<>();
		long deadline = System.nanoTime() + timeout.toNanos();
		try {
			// Windows에는 /dev/null이 없어 pipe를 닫는 것으로 같은 효과를 낸다.
			process.getOutputStream().close();
			while (true) {
				process.descendants().forEach(seen::add);
				if (process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
					return new Outcome(false, process.exitValue());
				}
				if (System.nanoTime() >= deadline) {
					return new Outcome(true, -1);
				}
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new InterruptedIOException("외부 process를 기다리다 중단되었습니다.");
		}
		finally {
			kill(process, seen);
		}
	}

	// 자식을 먼저 끝내 다시 자식을 만들지 못하게 하고, 그다음 process를 끝낸다. 끝날 때까지 잠시 기다린다.
	private static void kill(Process process, Set<ProcessHandle> seen) {
		process.descendants().forEach(seen::add);
		for (ProcessHandle child : seen) {
			child.destroyForcibly();
		}
		process.destroyForcibly();
		await(process.toHandle());
		seen.forEach(BoundedProcess::await);
	}

	private static void await(ProcessHandle handle) {
		try {
			handle.onExit().get(KILL_WAIT.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		catch (ExecutionException | TimeoutException ex) {
			// SIGKILL을 보냈다. 커널이 정리한다.
		}
	}

}
