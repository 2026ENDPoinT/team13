package com.stackcorp.astra;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * 공통 context에서 ASTRA의 주기 작업은 테스트 도중 저절로 돌지 않는다. 테스트가 직접 부른다. 저절로 도는 작업은 다른 테스트가 만든
 * 행과 객체를 바꾸어 테스트가 순서와 시각에 따라 실패한다. 예를 들어 검사 worker의 poll(기본 10초)은 rejected blob의 객체를 지워
 * StreamUploadTests의 객체 수 비교를 깨뜨렸다.
 * <p>
 * 영상 변환의 만료 lease 회수만 예외다. 그 간격은 실행 중 lease 갱신 간격(astra.transcoder.worker.lease-renewal)과 같고 lease보다
 * 짧아야 해서 늘릴 수 없다. lease가 끝난 변환 작업만 건드린다. 첫 실행 지연이 없는 작업(lease 회수)은 context를 띄울 때 한 번 돌지만
 * 그때 database는 비어 있다. Spring Modulith의 시각 사건(Moments)은 ASTRA 작업이 아니다.
 */
class IntegrationTestScheduleTests extends IntegrationTest {

	private static final Duration TEST_RUN = Duration.ofHours(1);

	@Autowired
	List<ScheduledTaskHolder> holders;

	@Test
	void periodicJobsDoNotRunOnTheirOwnDuringTheTestRun() {
		List<ScheduledTask> tasks = this.holders.stream()
			.flatMap((h) -> h.getScheduledTasks().stream())
			.filter((t) -> t.toString().startsWith("com.stackcorp."))
			.toList();
		List<String> frequent = tasks.stream()
			.map(ScheduledTask::getTask)
			.filter((t) -> !t.toString().equals("com.stackcorp.astra.files.VideoTranscodeRunner.recoverExpired"))
			.filter((t) -> !(t instanceof IntervalTask interval) || interval.getIntervalDuration().compareTo(TEST_RUN) < 0)
			.map(Object::toString)
			.toList();

		assertThat(tasks).extracting(Object::toString).contains("com.stackcorp.astra.files.scan.ScanWorker.poll");
		assertThat(frequent).isEmpty();
	}

}
