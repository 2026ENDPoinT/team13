package com.stackcorp.astra.summary.policy;

import java.util.function.Supplier;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 여러 행의 상태를 함께 바꾸는 명령과 worker loop의 transaction. READ COMMITTED라 조건 UPDATE가 범위 잠금을 걸지 않는다.
 * deadlock과 잠금 대기 시간 초과는 정해진 횟수만 다시 실행하고, 끝내 실패하면 예외를 그대로 던진다.
 */
public final class ReadCommitted {

	private static final int ATTEMPTS = 3;

	private final TransactionTemplate transaction;

	public ReadCommitted(PlatformTransactionManager transactionManager) {
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
	}

	public <T> T execute(Supplier<T> work) {
		for (int attempt = 1;; attempt++) {
			try {
				return this.transaction.execute((status) -> work.get());
			}
			catch (TransientDataAccessException ex) {
				if (attempt >= ATTEMPTS) {
					throw ex;
				}
			}
		}
	}

}
