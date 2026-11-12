package com.stackcorp.astra.communication;

import java.util.function.Supplier;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 여러 사람이 같은 때 부르는 조건부 전이(읽음, 회수, 일괄 삭제, 임시저장 upsert)의 transaction. READ COMMITTED로 이웃 색인 항목의
 * gap 잠금을 피하고, 교착이나 잠금 시간 초과는 정해진 횟수만 처음부터 다시 실행한다. 마지막 실패는 그대로 던진다.
 */
@Component
class ReadCommitted {

	private static final int ATTEMPTS = 3;

	private final TransactionTemplate transaction;

	ReadCommitted(PlatformTransactionManager transactionManager) {
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
	}

	<T> T execute(Supplier<T> work) {
		for (int attempt = 1;; attempt++) {
			try {
				return this.transaction.execute((status) -> work.get());
			}
			catch (TransientDataAccessException ex) {
				if (attempt == ATTEMPTS) {
					throw ex;
				}
			}
		}
	}

}
