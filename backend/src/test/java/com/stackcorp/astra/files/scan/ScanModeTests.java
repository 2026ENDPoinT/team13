package com.stackcorp.astra.files.scan;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.stackcorp.astra.files.api.ContentScanner;

/**
 * 검사기 유무에 따른 시작 동작(ADR-0018). 검사기가 없으면 경고를 한 번 남기고 형식 검사만 한다. astra.scan.required면 시작하지 않는다.
 */
@ExtendWith(OutputCaptureExtension.class)
class ScanModeTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(ScanMode.class);

	@Test
	void startsWithOneWarningAndFormatChecksOnlyWhenNoScannerIsConfigured(CapturedOutput output) {
		this.runner.run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(ScanMode.class).initialBlobState()).isEqualTo("clean");
		});
		assertThat(output).containsOnlyOnce("Malware scanner not configured");
	}

	@Test
	void refusesToStartWithoutAScannerWhenOneIsRequired() {
		this.runner.withPropertyValues("astra.scan.required=true").run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasMessageContaining("astra.scan.clamd.host");
		});
	}

	@Test
	void quarantinesNewContentWhenAScannerIsConfigured(CapturedOutput output) {
		this.runner.withPropertyValues("astra.scan.required=true")
			.withBean(ContentScanner.class, () -> (content, size) -> new ContentScanner.Clean())
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(ScanMode.class).initialBlobState()).isEqualTo("quarantined");
			});
		assertThat(output).doesNotContain("Malware scanner not configured");
	}

}
