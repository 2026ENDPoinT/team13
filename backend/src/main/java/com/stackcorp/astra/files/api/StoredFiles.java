package com.stackcorp.astra.files.api;

import java.util.Optional;

/**
 * 파일 조회. 권한 판정은 하지 않는다.
 */
public interface StoredFiles {

	/** 삭제된 파일은 없는 것으로 본다. */
	Optional<StoredFile> find(long fileId);

	/**
	 * @param courseId 소속 과목. 과목 밖 파일은 null
	 * @param ready 검사를 통과해 열람·변환할 수 있는지
	 * @param pdf PDF 파일인지
	 */
	record StoredFile(long fileId, int versionNo, String name, Long courseId, boolean ready, boolean pdf) {
	}

}
