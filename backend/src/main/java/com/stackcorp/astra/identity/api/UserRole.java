package com.stackcorp.astra.identity.api;

/**
 * users.user_role. 과목 안의 권한은 이 값이 아니라 수강·담당 관계로 판정한다.
 */
public enum UserRole {
	STUDENT, PROFESSOR, TA, STAFF
}
