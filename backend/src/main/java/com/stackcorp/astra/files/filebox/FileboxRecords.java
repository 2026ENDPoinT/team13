package com.stackcorp.astra.files.filebox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.stackcorp.astra.files.filebox.ListCursor.Position;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * filebox_items, filebox_quotas와 파일함 파일의 files, file_blobs 조회. 항목은 언제나 소유자 조건과 함께 읽는다(SEC-08).
 * 한 사용자의 항목을 바꾸는 명령은 먼저 {@link #lockQuota}로 그 사용자 행을 잠근다. 그래서 항목 행은 잠그지 않고 읽은 뒤 상태와
 * version을 조건으로 바꾼다.
 */
@Repository
class FileboxRecords {

	private final JdbcClient jdbc;

	FileboxRecords(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** 사용자의 사용량 행을 만들고(없으면) 잠근다. 한 사용자의 트리 변경은 여기서 차례를 기다린다. */
	Quota lockQuota(long owner) {
		this.jdbc.sql("""
				INSERT INTO filebox_quotas (owner_user_id) VALUES (:owner)
				ON DUPLICATE KEY UPDATE owner_user_id = owner_user_id
				""").param("owner", owner).update();
		return this.jdbc.sql("SELECT quota_bytes, used_bytes, item_count FROM filebox_quotas WHERE owner_user_id = :owner FOR UPDATE")
			.param("owner", owner)
			.query(FileboxRecords::quota)
			.single();
	}

	/** 잠그지 않고 읽는다. 행이 없으면 쓴 것이 없다. */
	Quota quota(long owner) {
		return this.jdbc.sql("SELECT quota_bytes, used_bytes, item_count FROM filebox_quotas WHERE owner_user_id = :owner")
			.param("owner", owner)
			.query(FileboxRecords::quota)
			.optional()
			.orElse(new Quota(null, 0, 0));
	}

	/** {@link #lockQuota}로 잠근 뒤 부른다. */
	void addUsage(long owner, long bytes, int items) {
		this.jdbc.sql("""
				UPDATE filebox_quotas SET used_bytes = used_bytes + :bytes, item_count = item_count + :items
				 WHERE owner_user_id = :owner
				""").param("bytes", bytes).param("items", items).param("owner", owner).update();
	}

	/** {@link #lockQuota}로 잠근 뒤 부른다. */
	void removeUsage(long owner, long bytes, int items) {
		this.jdbc.sql("""
				UPDATE filebox_quotas
				   SET used_bytes = IF(used_bytes >= :bytes, used_bytes - :bytes, 0),
				       item_count = IF(item_count >= :items, item_count - :items, 0)
				 WHERE owner_user_id = :owner
				""").param("bytes", bytes).param("items", items).param("owner", owner).update();
	}

	Optional<Item> item(long owner, long id) {
		return this.jdbc.sql(SELECT + " WHERE i.owner_user_id = :owner AND i.filebox_item_id = :id")
			.param("owner", owner)
			.param("id", id)
			.query(FileboxRecords::item)
			.optional();
	}

	/** 소유자의 항목만 돌려준다. 남의 항목과 없는 ID는 빠진다. */
	List<Item> items(long owner, Collection<Long> ids) {
		return this.jdbc.sql(SELECT + " WHERE i.owner_user_id = :owner AND i.filebox_item_id IN (:ids)")
			.param("owner", owner)
			.param("ids", ids)
			.query(FileboxRecords::item)
			.list();
	}

	/**
	 * 다른 요청이 영구 삭제하지 못하도록 공유 잠금으로 읽는다(파일함 파일 다시 쓰기). 잠금 순서 filebox_items → file_blobs를 지킨다.
	 */
	List<Item> itemsForShare(long owner, Collection<Long> ids) {
		return this.jdbc.sql(SELECT + " WHERE i.owner_user_id = :owner AND i.filebox_item_id IN (:ids) FOR SHARE OF i")
			.param("owner", owner)
			.param("ids", ids)
			.query(FileboxRecords::item)
			.list();
	}

	/** 폴더에서 루트까지 거슬러 올라간 경로. 루트 바로 아래 폴더가 맨 앞이고 그 폴더가 맨 뒤다. 크기가 그 폴더의 깊이다. */
	List<PathEntry> path(long folderId) {
		return this.jdbc.sql("""
				WITH RECURSIVE up (id, parent, name, lvl) AS (
				  SELECT filebox_item_id, parent_item_id, item_name, 0 FROM filebox_items WHERE filebox_item_id = :id
				  UNION ALL
				  SELECT p.filebox_item_id, p.parent_item_id, p.item_name, up.lvl + 1
				    FROM filebox_items p JOIN up ON p.filebox_item_id = up.parent
				)
				SELECT id, name FROM up ORDER BY lvl DESC
				""").param("id", folderId).query((rs, n) -> new PathEntry(rs.getLong(1), rs.getString(2))).list();
	}

	/** 활성 폴더 아래 폴더 층 수. 폴더 하나는 1, 파일은 0이다. */
	int activeFolderHeight(long itemId) {
		return this.jdbc.sql("""
				WITH RECURSIVE down (id, lvl) AS (
				  SELECT filebox_item_id, 1 FROM filebox_items WHERE filebox_item_id = :id AND item_kind = 'folder'
				  UNION ALL
				  SELECT c.filebox_item_id, down.lvl + 1 FROM filebox_items c JOIN down ON c.parent_item_id = down.id
				   WHERE c.item_kind = 'folder' AND c.item_state = 'active'
				)
				SELECT COALESCE(MAX(lvl), 0) FROM down
				""").param("id", itemId).query(Integer.class).single();
	}

	/** 휴지통 root와 함께 들어간 폴더의 층 수. 복원하면 함께 돌아올 폴더만 센다. */
	int trashedFolderHeight(long rootId) {
		return this.jdbc.sql("""
				WITH RECURSIVE down (id, lvl) AS (
				  SELECT filebox_item_id, 1 FROM filebox_items WHERE filebox_item_id = :root AND item_kind = 'folder'
				  UNION ALL
				  SELECT c.filebox_item_id, down.lvl + 1 FROM filebox_items c JOIN down ON c.parent_item_id = down.id
				   WHERE c.item_kind = 'folder' AND c.item_state = 'trashed' AND c.trash_root_id = :root
				)
				SELECT COALESCE(MAX(lvl), 0) FROM down
				""").param("root", rootId).query(Integer.class).single();
	}

	/** 활성 항목과 그 아래 활성 항목 전부(서버가 계산하는 폐포, FILES-07). */
	List<Long> activeSubtree(long rootId) {
		return this.jdbc.sql("""
				WITH RECURSIVE down (id) AS (
				  SELECT filebox_item_id FROM filebox_items WHERE filebox_item_id = :id AND item_state = 'active'
				  UNION ALL
				  SELECT c.filebox_item_id FROM filebox_items c JOIN down ON c.parent_item_id = down.id
				   WHERE c.item_state = 'active'
				)
				SELECT id FROM down
				""").param("id", rootId).query(Long.class).list();
	}

	/** 휴지통 항목과 그 아래 휴지통 항목 전부. 먼저 따로 휴지통에 넣은 하위 항목도 든다. */
	List<Long> trashedSubtree(long rootId) {
		return this.jdbc.sql("""
				WITH RECURSIVE down (id) AS (
				  SELECT filebox_item_id FROM filebox_items WHERE filebox_item_id = :id AND item_state = 'trashed'
				  UNION ALL
				  SELECT c.filebox_item_id FROM filebox_items c JOIN down ON c.parent_item_id = down.id
				   WHERE c.item_state = 'trashed'
				)
				SELECT id FROM down
				""").param("id", rootId).query(Long.class).list();
	}

	/** @throws DuplicateKeyException 같은 폴더에 같은 이름의 활성 항목이 있을 때 */
	long insertFolder(long owner, Long parentId, String name) {
		return insert(owner, parentId, "folder", name, null);
	}

	/** @throws DuplicateKeyException 같은 폴더에 같은 이름의 활성 항목이 있을 때 */
	long insertFile(long owner, Long parentId, String name, long fileId) {
		return insert(owner, parentId, "file", name, fileId);
	}

	private long insert(long owner, Long parentId, String kind, String name, Long fileId) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO filebox_items (owner_user_id, parent_item_id, item_kind, item_name, file_id)
				VALUES (:owner, :parent, :kind, :name, :file)
				""")
			.param("owner", owner)
			.param("parent", parentId)
			.param("kind", kind)
			.param("name", name)
			.param("file", fileId)
			.update(key, "filebox_item_id");
		return key.getKey().longValue();
	}

	/** @return 바꿨으면 1. 활성이 아니거나 version이 다르면 0 */
	int rename(long owner, long id, int version, String name) {
		return this.jdbc.sql("""
				UPDATE filebox_items SET item_name = :name, item_version = item_version + 1
				 WHERE filebox_item_id = :id AND owner_user_id = :owner AND item_state = 'active' AND item_version = :version
				""").param("name", name).param("id", id).param("owner", owner).param("version", version).update();
	}

	/** @return 바꿨으면 1. 활성이 아니거나 version이 다르면 0 */
	int move(long owner, long id, int version, Long parentId) {
		return this.jdbc.sql("""
				UPDATE filebox_items SET parent_item_id = :parent, item_version = item_version + 1
				 WHERE filebox_item_id = :id AND owner_user_id = :owner AND item_state = 'active' AND item_version = :version
				""").param("parent", parentId).param("id", id).param("owner", owner).param("version", version).update();
	}

	/** 활성 항목들을 휴지통 root 하나의 묶음으로 휴지통에 넣는다. @return 바꾼 행 수 */
	int trash(long owner, Collection<Long> ids, long rootId) {
		return this.jdbc.sql("""
				UPDATE filebox_items
				   SET item_state = 'trashed', trashed_at = CURRENT_TIMESTAMP(3), trash_root_id = :root
				 WHERE owner_user_id = :owner AND item_state = 'active' AND filebox_item_id IN (:ids)
				""").param("root", rootId).param("owner", owner).param("ids", ids).update();
	}

	/**
	 * 휴지통 root를 parent 아래 name으로 되돌린다.
	 *
	 * @return 바꿨으면 1
	 * @throws DuplicateKeyException 그 폴더에 같은 이름의 활성 항목이 있을 때
	 */
	int restoreRoot(long owner, long rootId, Long parentId, String name) {
		return this.jdbc.sql("""
				UPDATE filebox_items
				   SET item_state = 'active', trashed_at = NULL, trash_root_id = NULL, parent_item_id = :parent,
				       item_name = :name, item_version = item_version + 1
				 WHERE filebox_item_id = :id AND owner_user_id = :owner AND item_state = 'trashed' AND trash_root_id = :id
				""").param("parent", parentId).param("name", name).param("id", rootId).param("owner", owner).update();
	}

	/**
	 * root와 함께 휴지통에 들어간 하위 항목을 되돌린다. 이 항목들의 형제는 모두 휴지통에 있었으므로 이름이 겹치지 않는다.
	 *
	 * @return 되돌린 행 수
	 */
	int restoreBundle(long owner, long rootId) {
		return this.jdbc.sql("""
				UPDATE filebox_items SET item_state = 'active', trashed_at = NULL, trash_root_id = NULL
				 WHERE owner_user_id = :owner AND trash_root_id = :root AND item_state = 'trashed'
				""").param("owner", owner).param("root", rootId).update();
	}

	List<Long> trashRoots(long owner) {
		return this.jdbc.sql("""
				SELECT filebox_item_id FROM filebox_items
				 WHERE owner_user_id = :owner AND item_state = 'trashed' AND trash_root_id = filebox_item_id
				 ORDER BY filebox_item_id
				""").param("owner", owner).query(Long.class).list();
	}

	/** 영구 삭제할 휴지통 항목과 그 파일. */
	List<Purgeable> purgeable(long owner, Collection<Long> ids) {
		return this.jdbc.sql("""
				SELECT i.filebox_item_id, i.file_id, COALESCE(f.file_size_bytes, 0)
				  FROM filebox_items i LEFT JOIN files f ON f.file_id = i.file_id
				 WHERE i.owner_user_id = :owner AND i.item_state = 'trashed' AND i.filebox_item_id IN (:ids)
				 ORDER BY i.filebox_item_id
				""")
			.param("owner", owner)
			.param("ids", ids)
			.query((rs, n) -> new Purgeable(rs.getLong(1), rs.getObject(2, Long.class), rs.getLong(3)))
			.list();
	}

	/** @return purged로 바꾼 행 수. 휴지통 상태인 행만 바꾸므로 같은 항목을 두 번 지우지 않는다 */
	int purge(long owner, Collection<Long> ids) {
		return this.jdbc.sql("""
				UPDATE filebox_items SET item_state = 'purged', purged_at = CURRENT_TIMESTAMP(3), trash_root_id = NULL
				 WHERE owner_user_id = :owner AND item_state = 'trashed' AND filebox_item_id IN (:ids)
				""").param("owner", owner).param("ids", ids).update();
	}

	/** 파일함에 올린 파일의 업로드를 지운 상태로 둔다. 파일을 지운 뒤 부른다. */
	void deleteUploadOf(long fileId) {
		this.jdbc.sql("UPDATE upload_sessions SET upload_state = 'deleted' WHERE file_id = :file AND upload_state = 'attached'")
			.param("file", fileId)
			.update();
	}

	/** 업로드로 만든 파일함 항목. 그 업로드가 파일함 업로드가 아니면 비어 있다. */
	Optional<Long> itemOfUpload(long owner, long uploadId) {
		return this.jdbc.sql("""
				SELECT i.filebox_item_id FROM upload_sessions u JOIN filebox_items i ON i.file_id = u.file_id
				 WHERE u.upload_id = :upload AND u.owner_user_id = :owner AND i.owner_user_id = :owner
				""").param("upload", uploadId).param("owner", owner).query(Long.class).optional();
	}

	/** 같은 멱등 key로 먼저 올린 업로드가 만든 파일함 항목. */
	Optional<Long> itemOfUploadKey(long owner, String clientKey) {
		return this.jdbc.sql("""
				SELECT i.filebox_item_id FROM upload_sessions u JOIN filebox_items i ON i.file_id = u.file_id
				 WHERE u.owner_user_id = :owner AND u.client_upload_key = :key AND i.owner_user_id = :owner
				""").param("owner", owner).param("key", clientKey).query(Long.class).optional();
	}

	/**
	 * 폴더 하나의 활성 항목. 폴더가 먼저이고, 그 안에서 sort 값, 그다음 ID 순이다. after가 있으면 그 행 다음부터다.
	 *
	 * @param nameLike LIKE 형식으로 escape한 검색어('!'가 escape 문자). 없으면 null
	 */
	List<Item> children(long owner, Long parentId, String nameLike, ItemSort sort, boolean ascending, Position after,
			Object afterValue, int limit) {
		String op = ascending ? ">" : "<";
		String dir = ascending ? "ASC" : "DESC";
		StringBuilder sql = new StringBuilder(SELECT)
			.append(" WHERE i.owner_user_id = :owner AND i.parent_key = :parent AND i.item_state = 'active'");
		if (nameLike != null) {
			sql.append(" AND i.item_name LIKE :name ESCAPE '!'");
		}
		if (after != null) {
			sql.append(" AND (" + RANK + " > :rank OR (" + RANK + " = :rank AND (" + sort.expression() + " " + op
					+ " :value OR (" + sort.expression() + " = :value AND i.filebox_item_id " + op + " :after))))");
		}
		sql.append(" ORDER BY " + RANK + " ASC, " + sort.expression() + " " + dir + ", i.filebox_item_id " + dir)
			.append(" LIMIT :limit");
		StatementSpec spec = this.jdbc.sql(sql.toString())
			.param("owner", owner)
			.param("parent", parentId == null ? 0L : parentId)
			.param("limit", limit);
		if (nameLike != null) {
			spec = spec.param("name", nameLike);
		}
		if (after != null) {
			spec = spec.param("rank", after.rank()).param("value", afterValue).param("after", after.id());
		}
		return spec.query(FileboxRecords::item).list();
	}

	/** 휴지통 root. 휴지통에 넣은 일시 역순, 그다음 ID 역순이다. */
	List<TrashEntry> trash(long owner, LocalDateTime afterTrashedAt, Long afterId, int limit) {
		StringBuilder sql = new StringBuilder("""
				SELECT i.filebox_item_id, i.item_kind, i.item_name, i.trashed_at,
				       (SELECT COUNT(*) FROM filebox_items d WHERE d.trash_root_id = i.filebox_item_id) AS item_count,
				       (SELECT COALESCE(SUM(df.file_size_bytes), 0) FROM filebox_items d JOIN files df ON df.file_id = d.file_id
				         WHERE d.trash_root_id = i.filebox_item_id) AS size_bytes
				  FROM filebox_items i
				 WHERE i.owner_user_id = :owner AND i.item_state = 'trashed' AND i.trash_root_id = i.filebox_item_id
				""");
		if (afterId != null) {
			sql.append(" AND (i.trashed_at < :at OR (i.trashed_at = :at AND i.filebox_item_id < :after))");
		}
		sql.append(" ORDER BY i.trashed_at DESC, i.filebox_item_id DESC LIMIT :limit");
		StatementSpec spec = this.jdbc.sql(sql.toString()).param("owner", owner).param("limit", limit);
		if (afterId != null) {
			spec = spec.param("at", afterTrashedAt).param("after", afterId);
		}
		return spec.query((rs, n) -> new TrashEntry(rs.getLong("filebox_item_id"), rs.getString("item_kind"),
				rs.getString("item_name"), rs.getObject("trashed_at", LocalDateTime.class), rs.getInt("item_count"),
				rs.getLong("size_bytes"))).list();
	}

	/** 내려받을 파일함 파일. 활성 항목의 파일만. */
	Optional<Content> content(long owner, long id) {
		return this.jdbc.sql("""
				SELECT i.item_name, f.scan_state, b.blob_state, b.detected_mime_type, b.storage_key, b.size_bytes
				  FROM filebox_items i JOIN files f ON f.file_id = i.file_id JOIN file_blobs b ON b.file_blob_id = f.file_blob_id
				 WHERE i.owner_user_id = :owner AND i.filebox_item_id = :id AND i.item_state = 'active'
				   AND i.item_kind = 'file' AND f.state = 'active'
				""")
			.param("owner", owner)
			.param("id", id)
			.query((rs, n) -> new Content(rs.getString(1),
					"clean".equals(rs.getString(2)) && "clean".equals(rs.getString(3)), rs.getString(4), rs.getString(5),
					rs.getLong(6)))
			.optional();
	}

	/** 파일함 항목에서 다시 쓴 파일 가운데 첨부된 것. 볼 수 있는지는 부르는 쪽이 부모 권한으로 판정한다. */
	List<Reference> references(long itemId) {
		return this.jdbc.sql("""
				SELECT d.file_id, d.course_id, d.source_menu_code, MIN(a.created_at) AS attached_at
				  FROM files d JOIN attachments a ON a.file_id = d.file_id
				 WHERE d.source_filebox_item_id = :item AND d.state = 'active'
				 GROUP BY d.file_id, d.course_id, d.source_menu_code
				 ORDER BY attached_at, d.file_id
				""")
			.param("item", itemId)
			.query((rs, n) -> new Reference(rs.getLong(1), rs.getObject(2, Long.class), rs.getString(3),
					rs.getTimestamp(4).toInstant()))
			.list();
	}

	private static final String RANK = "(CASE WHEN i.item_kind = 'folder' THEN 0 ELSE 1 END)";

	private static final String SELECT = """
			SELECT i.filebox_item_id, i.item_kind, i.item_name, i.parent_item_id, i.item_state, i.file_id, i.item_version,
			       i.trash_root_id, i.created_at, f.file_size_bytes, f.file_extension, f.scan_state
			  FROM filebox_items i LEFT JOIN files f ON f.file_id = i.file_id
			""";

	private static Item item(ResultSet rs, int n) throws SQLException {
		return new Item(rs.getLong("filebox_item_id"), rs.getString("item_kind"), rs.getString("item_name"),
				rs.getObject("parent_item_id", Long.class), rs.getString("item_state"), rs.getObject("file_id", Long.class),
				rs.getInt("item_version"), rs.getObject("trash_root_id", Long.class),
				rs.getObject("created_at", LocalDateTime.class), rs.getObject("file_size_bytes", Long.class),
				rs.getString("file_extension"), rs.getString("scan_state"));
	}

	private static Quota quota(ResultSet rs, int n) throws SQLException {
		return new Quota(rs.getObject("quota_bytes", Long.class), rs.getLong("used_bytes"), rs.getInt("item_count"));
	}

	static Instant instant(LocalDateTime value) {
		return value == null ? null : Timestamp.valueOf(value).toInstant();
	}

	/** @param quotaBytes 사용자별 한도. 없으면 설정 기본값 */
	record Quota(Long quotaBytes, long usedBytes, int itemCount) {
	}

	record Item(long id, String kind, String name, Long parentId, String state, Long fileId, int version,
			Long trashRootId, LocalDateTime createdAt, Long sizeBytes, String extension, String scanState) {

		boolean folder() {
			return "folder".equals(this.kind);
		}

		boolean active() {
			return "active".equals(this.state);
		}

		boolean trashRoot() {
			return "trashed".equals(this.state) && this.trashRootId != null && this.trashRootId == this.id;
		}

	}

	record PathEntry(long id, String name) {
	}

	record Purgeable(long id, Long fileId, long sizeBytes) {
	}

	record TrashEntry(long id, String kind, String name, LocalDateTime trashedAt, int itemCount, long sizeBytes) {
	}

	record Content(String name, boolean ready, String mimeType, String storageKey, long sizeBytes) {
	}

	/** @param courseId 다시 쓴 업로드의 과목 */
	record Reference(long fileId, Long courseId, String purpose, Instant attachedAt) {
	}

}
