package com.stackcorp.astra.learning;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.stackcorp.astra.files.api.Attachments;
import com.stackcorp.astra.files.api.Attachments.AttachedFile;
import com.stackcorp.astra.learning.api.FileSummaries;

import org.springframework.stereotype.Component;

/**
 * 활동 첨부와 파일별 요약 게시 여부. 자료 목록, 활동 상세, 강의실 파일모음이 같이 쓴다. 그룹 수, 파일 수와 관계없이 쿼리 두 번(첨부, 게시
 * 여부)이다. 권한은 부르는 쪽이 활동 권한으로 먼저 판정한다.
 */
@Component
class MaterialFiles {

	private final Attachments attachments;

	private final FileSummaries summaries;

	MaterialFiles(Attachments attachments, FileSummaries summaries) {
		this.attachments = attachments;
		this.summaries = summaries;
	}

	/** @return 첨부 그룹 → 첨부 순서의 파일. 삭제된 파일은 없다 */
	Map<Long, List<MaterialFile>> of(Collection<Long> attachmentGroupIds) {
		if (attachmentGroupIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, List<AttachedFile>> byGroup = this.attachments.list(attachmentGroupIds);
		Set<Long> summarized = this.summaries.publishedAmong(
				byGroup.values().stream().flatMap(List::stream).map(AttachedFile::fileId).distinct().toList());
		Map<Long, List<MaterialFile>> files = new HashMap<>();
		byGroup.forEach((group, list) -> files.put(group,
				list.stream().map((f) -> MaterialFile.of(f, summarized.contains(f.fileId()))).toList()));
		return files;
	}

	/**
	 * @param summaryPublished 학생에게 보이는 요약 게시본이 있는지. 있으면
	 * {@code GET /api/courses/{courseId}/files/{fileId}/summary}가 게시본을 돌려준다
	 */
	record MaterialFile(long fileId, String name, long sizeBytes, String extension, boolean pdf,
			boolean summaryPublished) {

		static MaterialFile of(AttachedFile file, boolean summaryPublished) {
			return new MaterialFile(file.fileId(), file.name(), file.sizeBytes(), file.extension(), file.pdf(),
					summaryPublished);
		}

	}

}
