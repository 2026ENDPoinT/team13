package com.stackcorp.astra.learning;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.stackcorp.astra.files.api.Attachments;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolFile;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * school_files: 학교 강의실 파일 목록과 받아 둔 사본(ADR-0043). 학교 화면이 원천이므로 학교 파일모음 목록에서 사라진 행은 지우고, 받아
 * 둔 사본의 첨부를 끊는다(마지막 참조였으면 파일도 지워진다). 파일모음에 나오지 않는 파일(공지 첨부)은 그 상세를 열 때와 그 글이 학교
 * 목록에서 사라질 때 맞춘다.
 */
@Component
class SchoolFileStore {

	/** 학교 파일모음({@code files_list.acl})에 나오지 않는 파일이 붙는 활동 유형. 파일모음 목록으로 지우지 않는다. */
	static final Set<String> OUTSIDE_FILE_LIST = Set.of(SchoolNotices.NOTICE);

	private static final String SELECT = """
			SELECT school_file_id, course_id, file_seq, content_seq, school_type, school_item_id, file_name, size_bytes,
			       attachment_group_id, file_id
			  FROM school_files
			""";

	private final JdbcClient jdbc;

	private final Attachments attachments;

	private final TransactionTemplate transaction;

	SchoolFileStore(JdbcClient jdbc, Attachments attachments, TransactionTemplate transaction) {
		this.jdbc = jdbc;
		this.attachments = attachments;
		this.transaction = transaction;
	}

	/** 이 과목의 학교 파일모음 목록을 다시 읽을 때가 됐는지. 읽은 적이 없으면 true. */
	boolean listDue(long courseId, Duration interval) {
		return this.jdbc.sql("""
				SELECT COUNT(*) FROM school_file_lists
				 WHERE course_id = :course AND listed_at > DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL :seconds SECOND)
				""").param("course", courseId).param("seconds", interval.toSeconds()).query(Integer.class).single() == 0;
	}

	/**
	 * 학교 파일모음 목록 전체로 바꾼다. 새 파일은 넣고, 있던 파일은 이름·크기·붙은 활동을 고치고, 목록에 없는 파일은 지운다. 파일모음에
	 * 나오지 않는 유형({@link #OUTSIDE_FILE_LIST})의 파일은 건드리지 않는다.
	 */
	void replace(long courseId, List<SchoolFile> files) {
		this.transaction.executeWithoutResult((tx) -> {
			upsert(courseId, files);
			Set<String> seen = new HashSet<>();
			files.forEach((f) -> seen.add(f.fileSeq()));
			for (Row row : of(courseId)) {
				if (!seen.contains(row.fileSeq())
						&& (row.schoolType() == null || !OUTSIDE_FILE_LIST.contains(row.schoolType()))) {
					remove(row);
				}
			}
			this.jdbc.sql("""
					INSERT INTO school_file_lists (course_id, listed_at) VALUES (:course, CURRENT_TIMESTAMP(3))
					ON DUPLICATE KEY UPDATE listed_at = CURRENT_TIMESTAMP(3)
					""").param("course", courseId).update();
		});
	}

	/**
	 * 학습활동 상세의 첨부 목록으로 그 활동의 파일을 맞춘다. 상세에 없는 그 활동의 파일은 지운다. 다른 활동의 파일은 건드리지 않는다.
	 */
	void replaceFor(long courseId, String schoolType, String schoolItemId, List<SchoolFile> files) {
		this.transaction.executeWithoutResult((tx) -> {
			upsert(courseId, files);
			Set<String> seen = new HashSet<>();
			files.forEach((f) -> seen.add(f.fileSeq()));
			for (Row row : of(courseId, schoolType, List.of(schoolItemId))) {
				if (!seen.contains(row.fileSeq())) {
					remove(row);
				}
			}
		});
	}

	/** 그 활동들의 파일을 지운다(활동이 학교 목록에서 사라졌을 때). 부르는 쪽의 transaction 안에서 돈다. */
	void removeFor(long courseId, String schoolType, Collection<String> schoolItemIds) {
		for (Row row : of(courseId, schoolType, schoolItemIds)) {
			remove(row);
		}
	}

	private void upsert(long courseId, List<SchoolFile> files) {
		int order = 0;
		for (SchoolFile file : files) {
			this.jdbc.sql("""
					INSERT INTO school_files (course_id, file_seq, content_seq, school_type, school_item_id, file_name,
					                          size_bytes, sort_order, listed_at)
					VALUES (:course, :seq, :content, :type, :item, :name, :size, :order, CURRENT_TIMESTAMP(3))
					ON DUPLICATE KEY UPDATE content_seq = VALUES(content_seq),
					    school_type = COALESCE(VALUES(school_type), school_type),
					    school_item_id = COALESCE(VALUES(school_item_id), school_item_id),
					    file_name = VALUES(file_name), size_bytes = COALESCE(VALUES(size_bytes), size_bytes),
					    sort_order = VALUES(sort_order), listed_at = VALUES(listed_at)
					""")
				.param("course", courseId)
				.param("seq", file.fileSeq())
				.param("content", file.contentSeq())
				.param("type", file.activityType())
				.param("item", file.activityItemId())
				.param("name", file.name())
				.param("size", file.sizeBytes())
				.param("order", order++)
				.update();
		}
	}

	private void remove(Row row) {
		this.jdbc.sql("DELETE FROM school_files WHERE school_file_id = :id").param("id", row.id()).update();
		if (row.attachmentGroupId() != null) {
			this.attachments.release(row.attachmentGroupId());
		}
	}

	List<Row> of(long courseId) {
		return this.jdbc.sql(SELECT + " WHERE course_id = :course ORDER BY sort_order, school_file_id")
			.param("course", courseId)
			.query(SchoolFileStore::row)
			.list();
	}

	/** 그 활동들에 붙은 파일. 학교 화면 순서. */
	List<Row> of(long courseId, String schoolType, Collection<String> schoolItemIds) {
		if (schoolItemIds.isEmpty()) {
			return List.of();
		}
		return this.jdbc.sql(SELECT + """
				 WHERE course_id = :course AND school_type = :type AND school_item_id IN (:items)
				 ORDER BY sort_order, school_file_id
				""")
			.param("course", courseId)
			.param("type", schoolType)
			.param("items", new ArrayList<>(schoolItemIds))
			.query(SchoolFileStore::row)
			.list();
	}

	Optional<Row> find(long courseId, long schoolFileId) {
		return this.jdbc.sql(SELECT + " WHERE school_file_id = :id AND course_id = :course")
			.param("id", schoolFileId)
			.param("course", courseId)
			.query(SchoolFileStore::row)
			.optional();
	}

	/** 받아 둔 사본을 기록한다. 그 사이 다른 요청이 먼저 기록했으면 false이고 부르는 쪽이 이 사본의 첨부를 끊는다. */
	boolean copied(long schoolFileId, long attachmentGroupId, long fileId) {
		return this.jdbc.sql("""
				UPDATE school_files SET attachment_group_id = :group, file_id = :file, copied_at = CURRENT_TIMESTAMP(3)
				 WHERE school_file_id = :id AND file_id IS NULL
				""").param("group", attachmentGroupId).param("file", fileId).param("id", schoolFileId).update() == 1;
	}

	/** 쓰지 않게 된 사본의 첨부를 끊는다. */
	void discard(long attachmentGroupId) {
		this.transaction.executeWithoutResult((tx) -> this.attachments.release(attachmentGroupId));
	}

	/** 이 첨부 그룹을 가진 학교 파일의 과목. 학교 파일의 그룹이 아니면 비어 있다. */
	Optional<Long> courseOfGroup(long attachmentGroupId) {
		return this.jdbc.sql("SELECT course_id FROM school_files WHERE attachment_group_id = :group")
			.param("group", attachmentGroupId)
			.query(Long.class)
			.optional();
	}

	private static Row row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		long size = rs.getLong("size_bytes");
		Long sizeBytes = rs.wasNull() ? null : size;
		long group = rs.getLong("attachment_group_id");
		Long attachmentGroupId = rs.wasNull() ? null : group;
		long file = rs.getLong("file_id");
		Long fileId = rs.wasNull() ? null : file;
		return new Row(rs.getLong("school_file_id"), rs.getLong("course_id"), rs.getString("file_seq"),
				rs.getString("content_seq"), rs.getString("school_type"), rs.getString("school_item_id"),
				rs.getString("file_name"), sizeBytes, attachmentGroupId, fileId);
	}

	/**
	 * @param fileId 받아 둔 사본. 아직 받지 않았으면 null
	 */
	record Row(long id, long courseId, String fileSeq, String contentSeq, String schoolType, String schoolItemId,
			String name, Long sizeBytes, Long attachmentGroupId, Long fileId) {

		SchoolFile schoolFile() {
			return new SchoolFile(this.fileSeq, this.contentSeq, this.name, this.sizeBytes, this.schoolType,
					this.schoolItemId);
		}

	}

}
