package com.stackcorp.astra.files.upload;

import java.util.Optional;

import com.stackcorp.astra.files.upload.UploadRecords.Policy;
import com.stackcorp.astra.shared.ApiException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 학습실 강의 녹음과 내 문서(files.studyroom)가 쓰는 업로드 기능(ADR-0040). 일반 업로드와 같은 정책, 이름 규칙, 내용 검사, 악성코드 검사
 * 상태를 따른다. 용도는 {@link Kind}마다 따로다. 요청 제한(upload)은 multipart 본문을 나누기 전에 {@link MultipartRateLimitFilter}가 센다. 파일은 과목 없이(files.course_id
 * NULL) 기록하고 첨부하지 않으므로, 과목 파일 경로, 첨부 확정, 미리보기, 요약은 이 파일을 열지 못한다. 업로드는 기록과 같은
 * transaction에서 녹음·문서 행과 함께 attached가 된다.
 */
@Service
public class StudyRoomUploads {

	private final UploadService uploads;

	private final UploadRecords records;

	private final JdbcClient jdbc;

	StudyRoomUploads(UploadService uploads, UploadRecords records, JdbcClient jdbc) {
		this.uploads = uploads;
		this.records = records;
		this.jdbc = jdbc;
	}

	/** 파일 하나의 크기 상한. 정책 행에 없으면 비어 있는 값이다. */
	public Optional<Long> maxFileBytes(Kind kind) {
		return Optional.ofNullable(policy(kind).maxFileBytes());
	}

	/**
	 * 파일 하나의 크기 상한을 넘으면 413 {@code too_large}다. 학습실은 용량·개수 한도보다 먼저 본다(올리기 흐름의 같은 검사와 같은
	 * 응답이다).
	 */
	public void requireSize(Kind kind, long sizeBytes) {
		UploadService.requireSize(policy(kind), sizeBytes);
	}

	/**
	 * 파일을 검사해 저장하고 기록한다. 같은 사용자의 같은 멱등 key로 같은 요청(같은 파일, 이름, {@code request})의 업로드가 이미
	 * 있으면 아무것도 하지 않고 그 업로드 ID를 돌려주고, 다른 요청이면 409 {@code idempotency_conflict}다. 그 업로드가 녹음이나 문서를
	 * 만들었는지는 부르는 쪽이 확인한다.
	 *
	 * @param request 녹음·문서 행에 함께 기록할 값. 요청 지문에 들어간다
	 * @return 업로드 ID
	 */
	public long receive(Kind kind, long userId, MultipartFile file, String clientKey, Object request, Target target) {
		if (clientKey != null && !UploadService.CLIENT_KEY.matcher(clientKey).matches()) {
			throw ApiException.invalid("Idempotency-Key 형식이 올바르지 않습니다.");
		}
		long size = file.getSize();
		return this.uploads.store(userId, null, kind.purpose(), policy(kind), file, clientKey, null, new UploadService.Placement() {

			@Override
			public void before() {
				target.reserve(size);
			}

			@Override
			public void after(long fileId, long uploadId, String name) {
				target.place(fileId, name);
				StudyRoomUploads.this.jdbc.sql("""
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

	/** 녹음이나 문서를 지운 뒤, 그 파일을 만든 업로드를 지운 상태로 둔다. 파일을 지운 뒤 같은 transaction에서 부른다. */
	public void deleteUploadOf(long fileId) {
		this.jdbc.sql("UPDATE upload_sessions SET upload_state = 'deleted' WHERE file_id = :file AND upload_state = 'attached'")
			.param("file", fileId)
			.update();
	}

	/**
	 * 지운 녹음·문서 파일의 원래 이름(가져온 파일 이름, 곧 제목)을 {@code recording.<확장자>}나 {@code document.<확장자>}로 바꾼다.
	 * 지우는 transaction에서 부른다. 파일 행은 저장소 GC와 감사 대상 ID 때문에 남지만 사용자가 지운 이름은 남기지 않는다(ADR-0040 9번).
	 */
	public void forgetName(Kind kind, long fileId) {
		this.jdbc.sql("""
				UPDATE files SET original_file_name = CONCAT(:stem, '.', COALESCE(file_extension, 'bin'))
				 WHERE file_id = :file AND source_menu_code = :purpose
				""").param("stem", kind.stem()).param("file", fileId).param("purpose", kind.purpose()).update();
	}

	private Policy policy(Kind kind) {
		return this.records.policy(kind.purpose())
			.orElseThrow(() -> new IllegalStateException("upload policy가 없습니다: " + kind.purpose()));
	}

	/** 학습실 업로드의 종류. 용도 코드는 upload_policies.policy_code이자 files.source_menu_code다. */
	public enum Kind {

		/** 강의 녹음(오디오). */
		RECORDING("study_recording", "recording"),

		/** 내 문서(PDF). */
		DOCUMENT("study_document", "document");

		private final String purpose;

		private final String stem;

		Kind(String purpose, String stem) {
			this.purpose = purpose;
			this.stem = stem;
		}

		public String purpose() {
			return this.purpose;
		}

		/** 지운 파일 행에 남기는 이름의 앞부분. */
		String stem() {
			return this.stem;
		}

	}

	/** 학습실이 업로드 기록과 같은 transaction에서 하는 일. 예외를 던지면 업로드 전체가 되돌아간다. */
	public interface Target {

		/** 내용 객체를 잠그기 전에 부른다. 용량과 개수를 잡는다. */
		void reserve(long sizeBytes);

		/**
		 * 파일을 기록한 뒤 부른다. 녹음이나 문서 행을 만든다.
		 *
		 * @param name 정리한 파일 이름
		 */
		void place(long fileId, String name);

	}

}
