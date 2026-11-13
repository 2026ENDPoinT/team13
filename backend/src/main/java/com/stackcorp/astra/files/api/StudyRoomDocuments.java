package com.stackcorp.astra.files.api;

import java.util.Optional;

/**
 * 학습실의 내 문서(ADR-0040 14번) 조회. 소유자 한 사람의 문서만 돌려준다. 남의 문서와 없는 문서는 같은 빈 값이다.
 */
public interface StudyRoomDocuments {

	Optional<OwnedDocument> owned(long ownerUserId, long documentId);

	/**
	 * @param fileId 문서 PDF의 파일 ID. 과목 없이 첨부 없이 저장한 파일이라 과목 파일 경로로는 열리지 않는다
	 * @param courseId 문서를 둔 과목
	 * @param state {@link #READY}, {@link #SCANNING}, {@link #REJECTED}
	 */
	record OwnedDocument(long documentId, long fileId, long courseId, String state) {

		public static final String READY = "ready";

		public static final String SCANNING = "scanning";

		public static final String REJECTED = "rejected";

	}

}
