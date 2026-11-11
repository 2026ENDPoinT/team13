package com.stackcorp.astra.learning;

import java.util.function.Supplier;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 여러 행의 상태를 함께 바꾸는 출석 명령(스마트출석 제출·마감·취소, 출석 상태 변경, 그룹 이동)의 transaction.
 * READ COMMITTED로 실행해 gap lock 없이 잠근 행만 기다리게 하고, 행은 언제나 attendance_sessions → smart_attendance_rounds →
 * attendance_records(사용자 ID 순) 순서로, 그룹은 그룹 ID 순서로 잠근다. 교착이나 잠금 시간 초과로 진 transaction은 전부 되돌아가므로
 * 정해진 횟수까지 처음부터 다시 실행하고, 그래도 실패하면 예외를 그대로 던진다. 실패를 로그만 남기고 삼키지 않는다.
 */
@Component
class AttendanceTransactions {

	static final int ATTEMPTS = 3;

	private final TransactionTemplate template;

	AttendanceTransactions(PlatformTransactionManager transactionManager) {
		this.template = new TransactionTemplate(transactionManager);
		this.template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
	}

	<T> T execute(Supplier<T> work) {
		for (int attempt = 1;; attempt++) {
			try {
				return this.template.execute((status) -> work.get());
			}
			catch (TransientDataAccessException ex) {
				if (attempt == ATTEMPTS) {
					throw ex;
				}
			}
		}
	}

}
