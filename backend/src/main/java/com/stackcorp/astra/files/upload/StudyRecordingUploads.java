package com.stackcorp.astra.files.upload;

import java.util.Optional;

import com.stackcorp.astra.files.upload.UploadRecords.Policy;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 학습실 강의 녹음(files.studyroom)이 쓰는 업로드 기능(ADR-0040). 일반 업로드와 같은 정책, 이름 규칙, 내용 검사, 악성코드 검사 상태,
 * 요청 제한을 따른다. 파일은 과목 없이(files.course_id NULL) 기록하고 첨부하지 않으므로, 과목 파일 경로, 첨부 확정, 미리보기, 요약은
 * 이 파일을 열지 못한다. 업로드는 기록과 같은 transaction에서 녹음 행과 함께 attached가 된다.
 */
@Service
public class StudyRecordingUploads {

	/** 녹음 업로드의 용도 코드(upload_policies.policy_code, files.source_menu_code). */
	public static final String PURPOSE = "study_recording";

	private final UploadService uploads;

	private final UploadRecords records;

	private final RateLimiter rateLimiter;

	private final UploadProperties properties;

	private final JdbcClient jdbc;

	StudyRecordingUploads(UploadService uploads, UploadRecords records, RateLimiter rateLimiter,
			UploadProperties properties, JdbcClient jdbc) {
		this.uploads = uploads;
		this.records = records;
		this.rateLimiter = rateLimiter;
		this.properties = properties;
		this.jdbc = jdbc;
	}

	/** 녹음 파일 하나의 크기 상한. 정책 행에 없으면 비어 있는 값이다. */
	public Optional<Long> maxFileBytes() {
		return Optional.ofNullable(policy().maxFileBytes());
	}

	/**
	 * 녹음 파일을 검사해 저장하고 기록한다. 같은 사용자의 같은 멱등 key로 같은 요청(같은 파일, 이름, {@code request})의 업로드가 이미
	 * 있으면 아무것도 하지 않고 그 업로드 ID를 돌려주고, 다른 요청이면 409 {@code idempotency_conflict}다. 그 업로드가 녹음을
	 * 만들었는지는 부르는 쪽이 확인한다.
	 *
	 * @param request 녹음 행에 함께 기록할 값. 요청 지문에 들어간다
	 * @return 업로드 ID
	 */
	public long receive(long userId, MultipartFile file, String clientKey, Object request, Target target) {
		// 거부한 업로드도 센다. 본문은 이미 받은 뒤라 횟수로 막는다.
		this.rateLimiter.check("upload", userId, this.properties.rateLimit(), this.properties.rateWindow());
		if (clientKey != null && !UploadService.CLIENT_KEY.matcher(clientKey).matches()) {
			throw ApiException.invalid("Idempotency-Key 형식이 올바르지 않습니다.");
		}
		long size = file.getSize();
		return this.uploads.store(userId, null, PURPOSE, policy(), file, clientKey, null, new UploadService.Placement() {

			@Override
			public void before() {
				target.reserve(size);
			}

			@Override
			public void after(long fileId, long uploadId, String name) {
				target.place(fileId, name);
				StudyRecordingUploads.this.jdbc.sql("""
						UPDATE upload_sessions SET upload_state = 'attached', attached_at = CURRENT_TIMESTAMP(3)
						 WHERE upload_id = :id AND upload_state IN ('quarantined', 'clean')
						""").param("id", uploadId).update();
			}

			@Override
			public Object request() {
				return request;
			}

		}).uploadId();
	}

	/** 녹음을 지운 뒤, 그 파일을 만든 업로드를 지운 상태로 둔다. 파일을 지운 뒤 같은 transaction에서 부른다. */
	public void deleteUploadOf(long fileId) {
		this.jdbc.sql("UPDATE upload_sessions SET upload_state = 'deleted' WHERE file_id = :file AND upload_state = 'attached'")
			.param("file", fileId)
			.update();
	}

	private Policy policy() {
		return this.records.policy(PURPOSE).orElseThrow(() -> new IllegalStateException("upload policy가 없습니다: " + PURPOSE));
	}

	/** 학습실이 업로드 기록과 같은 transaction에서 하는 일. 예외를 던지면 업로드 전체가 되돌아간다. */
	public interface Target {

		/** 내용 객체를 잠그기 전에 부른다. 용량과 개수를 잡는다. */
		void reserve(long sizeBytes);

		/**
		 * 파일을 기록한 뒤 부른다. 녹음 행을 만든다.
		 *
		 * @param name 정리한 파일 이름
		 */
		void place(long fileId, String name);

	}

}
