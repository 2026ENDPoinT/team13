package com.stackcorp.astra.shared.schedule;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주기 작업을 인스턴스 하나에서만 실행하는 lease 잠금(architecture.md 4.3절, ADR-0034). 잠금은 scheduler_locks의 이름별 행 하나다.
 * <ul>
 * <li>잡기: lease가 끝난 행만 compare-and-set으로 바꾼다. 동시에 잡으려는 다른 인스턴스는 행 잠금이 풀린 뒤 조건을 다시 보고 진다.</li>
 * <li>놓기: 작업이 끝나거나 실패하면 이번 실행의 token이 맞을 때만 lease를 지금으로 줄인다. 다른 인스턴스가 이미 잡은 lease는 건드리지
 * 않는다.</li>
 * <li>만료: 잡은 process가 죽으면 lease 끝 시각(atMostFor) 뒤에 다른 인스턴스가 잡는다. 작업이 atMostFor보다 오래 걸리면 다른
 * 인스턴스가 겹쳐 실행할 수 있으므로, 잠그는 작업도 행 단위 상태 전이로 두 번 실행해도 안전해야 한다.</li>
 * </ul>
 * 시각은 DB 시계만 쓴다. 잡기와 놓기는 각자 READ COMMITTED transaction에서 바로 commit하고, 교착·잠금 대기 초과는 정해진 횟수만큼 다시
 * 한다. 잠금이 막는 것은 겹친 실행의 중복 작업이다. 연달아 실행하는 것은 막지 않는다. 앞 실행이 상태를 바꿔 두므로 뒤 실행은 할 일이 없다.
 */
@Component
public class SchedulerLock {

	private static final int ATTEMPTS = 3;

	private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_.]{0,63}");

	private final JdbcClient jdbc;

	private final TransactionTemplate transaction;

	private final String instance;

	@Autowired
	SchedulerLock(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
		this(jdbc, transactionManager, defaultInstance());
	}

	SchedulerLock(JdbcClient jdbc, PlatformTransactionManager transactionManager, String instance) {
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.instance = instance;
	}

	/**
	 * 잠금을 잡았을 때만 작업을 실행한다. 작업의 예외는 잠금을 놓은 뒤 그대로 올린다.
	 *
	 * @param name 잠금 이름. 소문자 식별자(예: files.storage_gc)
	 * @param atMostFor 잡은 process가 죽었을 때 잠금이 남는 최대 시간. 작업이 정상으로 걸리는 시간보다 길게 둔다
	 * @return 이 인스턴스가 실행했으면 true, 다른 인스턴스가 잡고 있어 건너뛰었으면 false
	 * @throws org.springframework.dao.DataAccessException 잠금을 잡거나 놓지 못했을 때. 놓지 못한 잠금은 atMostFor 뒤에 풀린다
	 */
	public boolean runExclusively(String name, Duration atMostFor, Runnable task) {
		if (!NAME.matcher(name).matches() || atMostFor.toMillis() < 1) {
			throw new IllegalArgumentException("잠금 이름은 소문자 식별자이고 atMostFor는 0보다 커야 합니다.");
		}
		String token = this.instance + ":" + UUID.randomUUID();
		if (!retried(() -> acquire(name, atMostFor, token))) {
			return false;
		}
		try {
			task.run();
		}
		catch (RuntimeException | Error ex) {
			try {
				release(name, token);
			}
			catch (RuntimeException releaseFailure) {
				ex.addSuppressed(releaseFailure);
			}
			throw ex;
		}
		release(name, token);
		return true;
	}

	private boolean acquire(String name, Duration atMostFor, String token) {
		return Boolean.TRUE.equals(this.transaction.execute((status) -> {
			int taken = this.jdbc.sql("""
					UPDATE scheduler_locks
					   SET locked_until = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL :micros MICROSECOND),
					       locked_at = CURRENT_TIMESTAMP(3), locked_by = :token
					 WHERE lock_name = :name AND locked_until <= CURRENT_TIMESTAMP(3)
					""")
				.param("micros", atMostFor.toMillis() * 1000)
				.param("token", token)
				.param("name", name)
				.update();
			if (taken == 1) {
				return true;
			}
			boolean exists = this.jdbc.sql("SELECT EXISTS (SELECT 1 FROM scheduler_locks WHERE lock_name = :name)")
				.param("name", name)
				.query(Boolean.class)
				.single();
			if (exists) {
				return false;
			}
			// 처음 쓰는 이름이다. 같은 이름을 동시에 처음 잡으면 unique 제약에서 한쪽만 들어간다.
			try {
				this.jdbc.sql("""
						INSERT INTO scheduler_locks (lock_name, locked_until, locked_at, locked_by)
						VALUES (:name, DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL :micros MICROSECOND), CURRENT_TIMESTAMP(3), :token)
						""")
					.param("name", name)
					.param("micros", atMostFor.toMillis() * 1000)
					.param("token", token)
					.update();
				return true;
			}
			catch (DuplicateKeyException ex) {
				return false;
			}
		}));
	}

	private void release(String name, String token) {
		retried(() -> this.transaction.execute((status) -> this.jdbc.sql("""
				UPDATE scheduler_locks SET locked_until = CURRENT_TIMESTAMP(3)
				 WHERE lock_name = :name AND locked_by = :token AND locked_until > CURRENT_TIMESTAMP(3)
				""").param("name", name).param("token", token).update()));
	}

	private static <T> T retried(Supplier<T> write) {
		for (int attempt = 1;; attempt++) {
			try {
				return write.get();
			}
			catch (TransientDataAccessException ex) {
				if (attempt >= ATTEMPTS) {
					throw ex;
				}
			}
		}
	}

	private static String defaultInstance() {
		String host;
		try {
			host = InetAddress.getLocalHost().getHostName();
		}
		catch (UnknownHostException ex) {
			host = "unknown";
		}
		String id = host + ":" + ProcessHandle.current().pid();
		return id.length() > 120 ? id.substring(0, 120) : id;
	}

}
