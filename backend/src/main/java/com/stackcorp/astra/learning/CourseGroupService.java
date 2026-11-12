package com.stackcorp.astra.learning;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.stackcorp.astra.catalog.api.CourseRosters;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.learning.AttendanceCodes.GroupType;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.audit.AuditLog;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 과목 그룹(그룹활동 팀, 출석 분반)과 구성원(EP-0039, EP-0040의 대체 계약 중 쓰기). 권한은 부르는 쪽이 먼저 판정한다.
 * 구성원은 그 과목의 수강생만 된다. 같은 그룹에 같은 학생은 한 번이다(유일 제약). 한 학생이 여러 그룹(예: 팀과 분반)에 속할 수 있다.
 * 이름 바꾸기와 삭제는 If-Match version으로 막는다. 스마트출석 회차나 출석 차시가 가리키는 그룹은 지우지 않는다.
 */
@Service
class CourseGroupService {

	static final int MAX_GROUPS = 200;

	static final int MAX_MEMBERS = 500;

	private final JdbcClient jdbc;

	private final CourseRosters rosters;

	private final AttendanceTransactions transactions;

	private final AuditLog audit;

	CourseGroupService(JdbcClient jdbc, CourseRosters rosters, AttendanceTransactions transactions, AuditLog audit) {
		this.jdbc = jdbc;
		this.rosters = rosters;
		this.transactions = transactions;
		this.audit = audit;
	}

	/** 부르는 쪽 transaction(멱등 원장) 안에서 그룹을 만든다. */
	long create(Actor actor, long courseId, String name, GroupType type, String description) {
		int count = this.jdbc.sql("SELECT COUNT(*) FROM course_groups WHERE course_id = :course")
			.param("course", courseId)
			.query(Integer.class)
			.single();
		if (count >= MAX_GROUPS) {
			throw new ApiException(HttpStatus.CONFLICT, "too_many_groups", "한 과목의 그룹은 " + MAX_GROUPS + "개까지입니다.");
		}
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		try {
			this.jdbc.sql("""
					INSERT INTO course_groups (course_id, group_type, group_name, description, sort_order, created_by)
					VALUES (:course, :type, :name, :description, :order, :user)
					""")
				.param("course", courseId)
				.param("type", type.db)
				.param("name", name)
				.param("description", description)
				.param("order", count + 1)
				.param("user", actor.userId())
				.update(key, "course_group_id");
		}
		catch (DuplicateKeyException ex) {
			throw groupExists();
		}
		long groupId = key.getKey().longValue();
		this.audit.success(actor.userId(), actor.sessionId(), "course_group.create", "course_group", groupId, courseId);
		return groupId;
	}

	@Transactional
	void rename(Actor actor, long courseId, long groupId, int expectedVersion, String name, String description) {
		lock(courseId, groupId, expectedVersion);
		try {
			this.jdbc.sql("""
					UPDATE course_groups SET group_name = :name, description = :description, row_version = row_version + 1
					 WHERE course_group_id = :group
					""").param("name", name).param("description", description).param("group", groupId).update();
		}
		catch (DuplicateKeyException ex) {
			throw groupExists();
		}
		this.audit.success(actor.userId(), actor.sessionId(), "course_group.update", "course_group", groupId, courseId);
	}

	@Transactional
	void delete(Actor actor, long courseId, long groupId, int expectedVersion) {
		lock(courseId, groupId, expectedVersion);
		boolean inUse = this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM smart_attendance_rounds WHERE course_group_id = :group)
				    OR EXISTS (SELECT 1 FROM attendance_sessions WHERE course_group_id = :group)
				""").param("group", groupId).query(Boolean.class).single();
		if (inUse) {
			throw new ApiException(HttpStatus.CONFLICT, "group_in_use", "출석에 쓴 그룹은 지울 수 없습니다.");
		}
		this.jdbc.sql("DELETE FROM course_group_members WHERE course_group_id = :group").param("group", groupId).update();
		this.jdbc.sql("DELETE FROM course_groups WHERE course_group_id = :group").param("group", groupId).update();
		this.audit.success(actor.userId(), actor.sessionId(), "course_group.delete", "course_group", groupId, courseId);
	}

	/** 구성원으로 넣는다. 이미 있으면 그대로다. */
	void add(Actor actor, long courseId, long groupId, long studentId) {
		requireStudent(courseId, studentId);
		this.transactions.execute(() -> {
			lockGroups(courseId, List.of(groupId));
			insertMember(groupId, studentId);
			this.audit.success(actor.userId(), actor.sessionId(), "course_group.member_add", "course_group", groupId,
					courseId);
			return null;
		});
	}

	/** 구성원에서 뺀다. 없으면 그대로다. */
	void remove(Actor actor, long courseId, long groupId, long studentId) {
		this.transactions.execute(() -> {
			lockGroups(courseId, List.of(groupId));
			this.jdbc.sql("DELETE FROM course_group_members WHERE course_group_id = :group AND user_id = :user")
				.param("group", groupId)
				.param("user", studentId)
				.update();
			this.audit.success(actor.userId(), actor.sessionId(), "course_group.member_remove", "course_group", groupId,
					courseId);
			return null;
		});
	}

	/**
	 * 한 그룹에서 다른 그룹으로 옮긴다. 두 그룹을 ID 순서로 잠그므로 반대 방향의 이동과 교착하지 않는다. 원래 그룹의 구성원이 아니면 404다.
	 */
	void move(Actor actor, long courseId, long fromGroupId, long toGroupId, long studentId) {
		if (fromGroupId == toGroupId) {
			throw ApiException.invalid("toGroupId가 지금 그룹과 같습니다.");
		}
		requireStudent(courseId, studentId);
		this.transactions.execute(() -> {
			lockGroups(courseId, List.of(fromGroupId, toGroupId));
			int removed = this.jdbc.sql("DELETE FROM course_group_members WHERE course_group_id = :group AND user_id = :user")
				.param("group", fromGroupId)
				.param("user", studentId)
				.update();
			if (removed == 0) {
				throw ApiException.notFound();
			}
			insertMember(toGroupId, studentId);
			this.audit.success(actor.userId(), actor.sessionId(), "course_group.member_move", "course_group", toGroupId,
					courseId);
			return null;
		});
	}

	/** 과목의 그룹. 정렬 순서, ID 순서다. */
	List<GroupRow> groups(long courseId, Collection<Long> only) {
		if (only != null && only.isEmpty()) {
			return List.of();
		}
		return this.jdbc.sql("""
				SELECT course_group_id, group_name, group_type, description, row_version FROM course_groups
				 WHERE course_id = :course AND (:all OR course_group_id IN (:only))
				 ORDER BY sort_order, course_group_id
				""")
			.param("course", courseId)
			.param("all", only == null)
			.param("only", only == null ? List.of(0L) : only)
			.query((rs, n) -> new GroupRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
					rs.getInt(5)))
			.list();
	}

	/** 사용자가 속한 그 과목의 그룹 ID. */
	List<Long> groupsOf(long courseId, long userId) {
		return this.jdbc.sql("""
				SELECT g.course_group_id FROM course_groups g
				  JOIN course_group_members m ON m.course_group_id = g.course_group_id
				 WHERE g.course_id = :course AND m.user_id = :user
				""").param("course", courseId).param("user", userId).query(Long.class).list();
	}

	/** 그룹 ID → 구성원 사용자 ID(참여 순서). */
	Map<Long, List<Long>> members(Collection<Long> groupIds) {
		Map<Long, List<Long>> members = new LinkedHashMap<>();
		if (groupIds.isEmpty()) {
			return members;
		}
		this.jdbc.sql("""
				SELECT course_group_id, user_id FROM course_group_members WHERE course_group_id IN (:groups)
				 ORDER BY course_group_id, joined_at, course_group_member_id
				""").param("groups", groupIds).query((rs) -> {
			members.computeIfAbsent(rs.getLong(1), (g) -> new java.util.ArrayList<>()).add(rs.getLong(2));
		});
		return members;
	}

	private void insertMember(long groupId, long studentId) {
		int count = this.jdbc.sql("SELECT COUNT(*) FROM course_group_members WHERE course_group_id = :group")
			.param("group", groupId)
			.query(Integer.class)
			.single();
		if (count >= MAX_MEMBERS) {
			throw new ApiException(HttpStatus.CONFLICT, "too_many_members", "한 그룹의 구성원은 " + MAX_MEMBERS + "명까지입니다.");
		}
		this.jdbc.sql("""
				INSERT INTO course_group_members (course_group_id, user_id) VALUES (:group, :user)
				ON DUPLICATE KEY UPDATE course_group_member_id = course_group_member_id
				""").param("group", groupId).param("user", studentId).update();
	}

	// 그룹을 ID 순서로 잠근다. 다른 과목의 그룹이 섞이면 404다.
	private void lockGroups(long courseId, List<Long> groupIds) {
		List<Long> locked = this.jdbc.sql("""
				SELECT course_group_id FROM course_groups WHERE course_group_id IN (:groups) AND course_id = :course
				 ORDER BY course_group_id
				   FOR UPDATE
				""").param("groups", groupIds).param("course", courseId).query(Long.class).list();
		if (locked.size() != groupIds.size()) {
			throw ApiException.notFound();
		}
	}

	private void lock(long courseId, long groupId, int expectedVersion) {
		int version = this.jdbc.sql("""
				SELECT row_version FROM course_groups WHERE course_group_id = :group AND course_id = :course FOR UPDATE
				""").param("group", groupId).param("course", courseId).query(Integer.class).optional().orElseThrow(ApiException::notFound);
		if (version != expectedVersion) {
			throw Versions.conflict();
		}
	}

	private void requireStudent(long courseId, long studentId) {
		if (!this.rosters.students(courseId).contains(studentId)) {
			throw ApiException.notFound();
		}
	}

	private static ApiException groupExists() {
		return new ApiException(HttpStatus.CONFLICT, "group_exists", "같은 이름의 그룹이 이미 있습니다.");
	}

	/**
	 * @param type DB 값(활동|출석분반)
	 */
	record GroupRow(long groupId, String name, String type, String description, int version) {
	}

}
