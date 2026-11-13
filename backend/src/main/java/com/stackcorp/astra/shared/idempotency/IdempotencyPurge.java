package com.stackcorp.astra.shared.idempotency;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 보존기한이 지난 멱등 원장 항목을 지운다(SC-05). worker가 두 개 떠도 SKIP LOCKED로 서로 다른 행을 지운다. READ COMMITTED라
 * 범위 잠금을 걸지 않아 새 예약을 막지 않는다.
 */
@Component
@Profile("worker")
class IdempotencyPurge {

	private static final Logger log = LoggerFactory.getLogger(IdempotencyPurge.class);

	private static final int BATCH = 500;

	private final JdbcClient jdbc;

	private final TransactionTemplate transaction;

	IdempotencyPurge(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
	}

	@Scheduled(fixedDelayString = "${astra.idempotency.purge-interval:15m}",
			initialDelayString = "${astra.idempotency.purge-interval:15m}")
	void scheduled() {
		int purged = purgeExpired();
		if (purged > 0) {
			log.info("idempotency ledger purged count={}", purged);
		}
	}

	/** @return 지운 항목 수 */
	int purgeExpired() {
		int total = 0;
		while (true) {
			List<Long> ids = this.transaction.execute((status) -> {
				List<Long> expired = this.jdbc.sql("""
						SELECT ledger_entry_id FROM idempotency_ledger
						 WHERE expires_at <= CURRENT_TIMESTAMP(3)
						 ORDER BY expires_at
						 LIMIT :batch
						   FOR UPDATE SKIP LOCKED
						""").param("batch", BATCH).query(Long.class).list();
				if (!expired.isEmpty()) {
					this.jdbc.sql("DELETE FROM idempotency_ledger WHERE ledger_entry_id IN (:ids)")
						.param("ids", expired)
						.update();
				}
				return expired;
			});
			total += ids.size();
			if (ids.size() < BATCH) {
				return total;
			}
		}
	}

}
