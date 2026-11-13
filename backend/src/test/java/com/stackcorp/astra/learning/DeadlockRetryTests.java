package com.stackcorp.astra.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;

/**
 * 교착(SQLSTATE 40001)만 정해진 횟수까지 다시 실행하고, 잠금 시간 초과(MySQL 1205, SQLSTATE HY000)는 바로 던진다.
 */
class DeadlockRetryTests {

	@Test
	void retriesADeadlockUntilItSucceeds() {
		AtomicInteger calls = new AtomicInteger();
		String result = DeadlockRetry.run(() -> {
			if (calls.incrementAndGet() < DeadlockRetry.ATTEMPTS) {
				throw deadlock();
			}
			return "done";
		});
		assertThat(result).isEqualTo("done");
		assertThat(calls).hasValue(DeadlockRetry.ATTEMPTS);
	}

	@Test
	void givesUpAfterTheLastAttempt() {
		AtomicInteger calls = new AtomicInteger();
		assertThatThrownBy(() -> DeadlockRetry.run(() -> {
			calls.incrementAndGet();
			throw deadlock();
		})).isInstanceOf(PessimisticLockingFailureException.class);
		assertThat(calls).hasValue(DeadlockRetry.ATTEMPTS);
	}

	@Test
	void doesNotRetryALockWaitTimeout() {
		AtomicInteger calls = new AtomicInteger();
		assertThatThrownBy(() -> DeadlockRetry.run(() -> {
			calls.incrementAndGet();
			throw new CannotAcquireLockException("timeout",
					new SQLException("Lock wait timeout exceeded", "HY000", 1205));
		})).isInstanceOf(CannotAcquireLockException.class);
		assertThat(calls).hasValue(1);
	}

	private static CannotAcquireLockException deadlock() {
		return new CannotAcquireLockException("deadlock",
				new SQLTransactionRollbackException("Deadlock found when trying to get lock", "40001", 1213));
	}

}
