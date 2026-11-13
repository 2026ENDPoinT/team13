package com.stackcorp.astra.catalog;

import java.util.Collection;
import java.util.Map;

import com.stackcorp.astra.catalog.api.EnrollmentRequests;

import org.springframework.stereotype.Service;

/**
 * {@link EnrollmentRequests} 구현. 처리 권한과 대기 상태는 신청 목록·처리 API와 같은 {@link EnrollmentRecords}의 조건이다.
 */
@Service
class EnrollmentRequestQueries implements EnrollmentRequests {

	private final EnrollmentRecords records;

	EnrollmentRequestQueries(EnrollmentRecords records) {
		this.records = records;
	}

	@Override
	public Map<Long, Integer> pendingCounts(long userId, Collection<Long> courseIds) {
		return this.records.pendingCounts(userId, courseIds);
	}

}
