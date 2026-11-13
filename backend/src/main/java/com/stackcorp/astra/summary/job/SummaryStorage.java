package com.stackcorp.astra.summary.job;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.stackcorp.astra.files.api.FileDerivatives;
import com.stackcorp.astra.summary.pipeline.CheckpointStore;

import tools.jackson.databind.json.JsonMapper;

/**
 * 요약 결과와 중간 결과의 저장 위치. 모두 파일 파생물이다(ADR-0007).
 * <ul>
 * <li>summary/jobs/{jobId}/result.json 완료 결과</li>
 * <li>summary/jobs/{jobId}/failure.json 실패 세부. 자료 일부를 담을 수 있어 DB에 두지 않는다</li>
 * <li>summary/jobs/{rootJobId}/checkpoints/... 재시도 사슬이 함께 쓰는 중간 결과</li>
 * </ul>
 */
@Component
class SummaryStorage {

	static final JsonMapper JSON = JsonMapper.builder().build();

	private final FileDerivatives derivatives;

	SummaryStorage(FileDerivatives derivatives) {
		this.derivatives = derivatives;
	}

	void writeResult(long fileId, long jobId, SummaryResult result) {
		this.derivatives.put(fileId, job(jobId) + "result.json", JSON.writeValueAsBytes(result), "application/json");
	}

	Optional<SummaryResult> readResult(long fileId, long jobId) {
		return this.derivatives.get(fileId, job(jobId) + "result.json")
			.map((bytes) -> JSON.readValue(bytes, SummaryResult.class));
	}

	void writeFailure(long fileId, long jobId, String stage, String detail) {
		byte[] body = JSON.writeValueAsBytes(Map.of("stage", stage, "detail", detail));
		this.derivatives.put(fileId, job(jobId) + "failure.json", body, "application/json");
	}

	CheckpointStore checkpoints(long fileId, long rootJobId) {
		String prefix = job(rootJobId) + "checkpoints/";
		return new CheckpointStore() {

			@Override
			public Optional<String> read(String name) {
				return SummaryStorage.this.derivatives.get(fileId, prefix + name + ".json")
					.map((bytes) -> new String(bytes, StandardCharsets.UTF_8));
			}

			@Override
			public void write(String name, String json) {
				SummaryStorage.this.derivatives.put(fileId, prefix + name + ".json",
						json.getBytes(StandardCharsets.UTF_8), "application/json");
			}

		};
	}

	private static String job(long jobId) {
		return "summary/jobs/" + jobId + "/";
	}

}
