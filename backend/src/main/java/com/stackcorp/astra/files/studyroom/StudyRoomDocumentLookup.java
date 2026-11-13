package com.stackcorp.astra.files.studyroom;

import java.util.Optional;

import com.stackcorp.astra.files.api.StudyRoomDocuments;

import org.springframework.stereotype.Component;

/** {@link StudyRoomDocuments}의 구현. 소유자를 조건에 넣어 읽으므로 남의 문서는 없는 문서와 같다. */
@Component
class StudyRoomDocumentLookup implements StudyRoomDocuments {

	private final StudyRoomRecords records;

	StudyRoomDocumentLookup(StudyRoomRecords records) {
		this.records = records;
	}

	@Override
	public Optional<OwnedDocument> owned(long ownerUserId, long documentId) {
		return this.records.document(ownerUserId, documentId)
			.map((d) -> new OwnedDocument(d.id(), d.fileId(), d.courseId(), d.state()));
	}

}
