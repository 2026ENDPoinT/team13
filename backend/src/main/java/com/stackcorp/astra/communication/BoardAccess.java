package com.stackcorp.astra.communication;

import com.stackcorp.astra.catalog.api.CourseMemberships;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.shared.ApiException;

import org.springframework.stereotype.Component;

/**
 * 강의실 게시판(질의응답, 열린게시판)을 보는 사람. 권한은 users.user_role이 아니라 그 과목의 담당·수강 관계로 정한다(SC-02).
 * 과목 구성원이 아니면 게시판이 있는지도 드러내지 않도록 404다.
 */
@Component
class BoardAccess {

	private final CourseMemberships memberships;

	BoardAccess(CourseMemberships memberships) {
		this.memberships = memberships;
	}

	Viewer viewer(long userId, long courseId) {
		CourseRole role = this.memberships.roleOf(userId, courseId).orElseThrow(ApiException::notFound);
		return new Viewer(userId, courseId, role == CourseRole.INSTRUCTOR);
	}

	boolean instructor(long userId, long courseId) {
		return this.memberships.roleOf(userId, courseId).filter((r) -> r == CourseRole.INSTRUCTOR).isPresent();
	}

	/**
	 * @param manager 그 과목의 담당자면 true. 비공개 글과 익명 작성자 이름을 보고, 남의 글을 지울 수 있다
	 */
	record Viewer(long userId, long courseId, boolean manager) {

		/** 익명 글의 작성자 이름은 작성자 본인과 담당자에게만 보인다. 내부 작성자 ID는 그대로 저장한다(SC-07, COMM-09). */
		boolean seesAuthor(long authorId, boolean anonymous) {
			return !anonymous || this.manager || authorId == this.userId;
		}

		/** 이 사람에게 드러내는 작성자 ID. 이름과 같은 규칙이고, 볼 수 없는 익명 작성자면 null이다. */
		Long shown(long authorId, boolean anonymous) {
			return seesAuthor(authorId, anonymous) ? authorId : null;
		}

	}

}
