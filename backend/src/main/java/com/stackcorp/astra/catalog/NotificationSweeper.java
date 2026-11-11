package com.stackcorp.astra.catalog;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * worker 프로필에서 보일 시각이 지난 알림 사건을 보내고(ADR-0027), 보존 기간이 지난 알림을 지운다.
 * 사건마다 READ COMMITTED transaction 하나에서 {@code FOR UPDATE SKIP LOCKED}로 잡고, 알림 행을 만든 뒤 보낸 것으로 표시한다.
 * worker가 둘이어도 한 사건은 한 worker만 보내고, 그래도 겹치면 (유형, 대상, 사용자) 유일 키가 두 번째 행을 막는다.
 * 잠금 충돌은 정해진 횟수만 다시 시도한다. 그래도 실패한 사건은 보내지 않은 상태로 남아 다음 주기에 다시 잡히고, 실패 수를 예외로 알린다.
 */
@Component
@Profile("worker")
class NotificationSweeper {

	private static final Logger log = LoggerFactory.getLogger(NotificationSweeper.class);

	private static final int BATCH = 100;

	private static final int PURGE_BATCH = 500;

	private static final int ATTEMPTS = 3;

	private final NotificationRecords records;

	private final NotificationService service;

	private final TransactionTemplate transaction;

	NotificationSweeper(NotificationRecords records, NotificationService service,
			PlatformTransactionManager transactionManager) {
		this.records = records;
		this.service = service;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
	}

	@Scheduled(fixedDelayString = "${astra.notification.sweep-interval:30s}",
			initialDelayString = "${astra.notification.sweep-interval:30s}")
	void scheduled() {
		LocalDateTime now = this.records.now();
		int purged = purgeExpired(now);
		if (purged > 0) {
			log.info("expired notifications purged count={}", purged);
		}
		int delivered = deliverDue(now);
		if (delivered > 0) {
			log.info("scheduled notifications delivered events={}", delivered);
		}
	}

	/**
	 * 보일 시각이 {@code now} 이전인 사건을 {@value #BATCH}개까지 보낸다. 시각은 부르는 쪽이 DB 시계로 정한다.
	 * @return 이번에 보낸 사건 수
	 * @throws IllegalStateException 다시 시도해도 보내지 못한 사건이 있을 때. 그 사건은 다음 주기에 다시 잡힌다
	 */
	int deliverDue(LocalDateTime now) {
		int delivered = 0;
		int failed = 0;
		for (long eventId : this.records.dueEvents(now, BATCH)) {
			try {
				if (deliverOne(eventId, now)) {
					delivered++;
				}
			}
			catch (DataAccessException ex) {
				failed++;
				log.warn("notification delivery failed eventId={} error={}", eventId, ex.getClass().getSimpleName());
			}
		}
		if (failed > 0) {
			throw new IllegalStateException("notification delivery failed events=" + failed);
		}
		return delivered;
	}

	/** @return 지운 알림 수 */
	int purgeExpired(LocalDateTime now) {
		int total = 0;
		while (true) {
			Integer purged = this.transaction.execute((tx) -> this.records.purgeExpired(now, PURGE_BATCH));
			total += purged;
			if (purged < PURGE_BATCH) {
				return total;
			}
		}
	}

	private boolean deliverOne(long eventId, LocalDateTime now) {
		for (int attempt = 1;; attempt++) {
			try {
				return Boolean.TRUE.equals(this.transaction.execute((tx) -> this.records.claimDue(eventId, now)
					.map((event) -> this.service.deliver(event) >= 0)
					.orElse(false)));
			}
			catch (TransientDataAccessException ex) {
				if (attempt >= ATTEMPTS) {
					throw ex;
				}
			}
		}
	}

	/** 알림 worker의 주기 실행은 worker 프로필에서만 켠다. */
	@Configuration(proxyBeanMethods = false)
	@Profile("worker")
	@EnableScheduling
	static class Scheduling {

	}

}
