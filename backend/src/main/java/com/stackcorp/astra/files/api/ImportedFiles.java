package com.stackcorp.astra.files.api;

import java.nio.file.Path;

/**
 * 서버가 바깥(학교 e-Class)에서 받아 온 파일을 과목 파일로 저장한다(ADR-0043). 업로드와 같은 이름·확장자·크기·내용 형식 검사와 악성코드
 * 검사 상태를 따르고, 같은 내용은 저장소 객체 하나를 함께 쓴다. 저장한 파일은 새 첨부 그룹 하나에 붙는다. 그 그룹을 가진 부모 모듈의
 * {@link AttachmentParentAccess}가 열람 권한을 판정하므로 내려받기·미리보기·썸네일이 과목 파일과 같은 경로로 열린다.
 * <p>
 * 권한 판정은 부르는 쪽이 한다.
 */
public interface ImportedFiles {

	/** 한 파일의 크기 상한(byte). */
	long maxFileBytes();

	/**
	 * @param ownerUserId 받아 오게 한 사용자(그 파일을 연 학생)
	 * @param name 원래 파일 이름. 확장자로 형식을 정한다
	 * @param content 받은 내용. 부르는 쪽이 지운다
	 * @throws ImportRejectedException 이름·확장자·크기·내용 형식이 맞지 않을 때. message는 이유 코드다
	 */
	Imported store(long ownerUserId, long courseId, String name, Path content);

	/**
	 * @param attachmentGroupId 이 파일 하나를 담은 새 첨부 그룹
	 * @param ready 검사를 통과해 바로 열 수 있는지. 악성코드 검사기가 있으면 검사를 마칠 때까지 false다
	 */
	record Imported(long attachmentGroupId, long fileId, boolean ready) {
	}

	/** 받아 둘 수 없는 파일. message는 이유 코드({@code extension_not_allowed}, {@code too_large} 등)다. */
	class ImportRejectedException extends RuntimeException {

		public ImportRejectedException(String reason) {
			super(reason, null, false, false);
		}

	}

}
