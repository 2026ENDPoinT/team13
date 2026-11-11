package com.stackcorp.astra.local;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 로컬 실행용 그룹. 데이터베이스 과목(bi)에 교수 박은주가 만든 기말 프로젝트 활동 그룹 셋과 구성원(수강생 아홉 명을 세 명씩)을 넣는다.
 * 그룹 만들기(CourseGroupService)처럼 순서는 1부터, row_version은 1이고 구성원 역할은 기본값(구성원)이다. 과목에 그룹이 이미 있으면
 * 넣지 않는다.
 */
final class LocalCourseGroupSeed {

	private LocalCourseGroupSeed() {
	}

	static void seed(JdbcClient jdbc, long student, long professor) {
		long bi = LocalSql.course(jdbc, "bi");
		if (LocalSql.exists(jdbc, "SELECT 1 FROM course_groups WHERE course_id = :c", LocalSql.params("c", bi))) {
			return;
		}
		List<Long> students = new ArrayList<>();
		students.add(student);
		LocalPeopleSeed.refs().forEach((ref) -> students.add(LocalSql.user(jdbc, ref)));
		String[][] groups = { { "1조", "도서 대출 시스템 DB 설계" }, { "2조", "수강신청 시스템 DB 설계" }, { "3조", "쇼핑몰 주문 DB 설계" } };
		for (int g = 0; g < groups.length; g++) {
			long group = LocalSql.insert(jdbc, """
					INSERT INTO course_groups (course_id, group_type, group_name, description, sort_order, created_by, created_at)
					VALUES (:c, '활동', :name, :description, :order, :u, CURRENT_TIMESTAMP(3) - INTERVAL 14 DAY)
					""", LocalSql.params("c", bi, "name", groups[g][0], "description", groups[g][1], "order", g + 1, "u",
					professor));
			for (int i = g; i < students.size(); i += groups.length) {
				jdbc.sql("""
						INSERT INTO course_group_members (course_group_id, user_id, joined_at, created_at)
						VALUES (:g, :u, CURRENT_TIMESTAMP(3) - INTERVAL 14 DAY, CURRENT_TIMESTAMP(3) - INTERVAL 14 DAY)
						""").param("g", group).param("u", students.get(i)).update();
			}
		}
	}

}
