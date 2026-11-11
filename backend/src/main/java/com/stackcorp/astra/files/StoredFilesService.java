package com.stackcorp.astra.files;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.stackcorp.astra.files.api.StoredFiles;

@Service
class StoredFilesService implements StoredFiles {

	private final FileRecords records;

	StoredFilesService(FileRecords records) {
		this.records = records;
	}

	@Override
	public Optional<StoredFile> find(long fileId) {
		return this.records.find(fileId)
			.map((f) -> new StoredFile(f.fileId(), f.versionNo(), f.name(), f.courseId(), f.ready(), f.pdf()));
	}

}
