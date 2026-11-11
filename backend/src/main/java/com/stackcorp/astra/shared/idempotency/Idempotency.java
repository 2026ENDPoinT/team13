package com.stackcorp.astra.shared.idempotency;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.metrics.MetricTags;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 일반 명령의 멱등 원장(SC-05, ADR-0012). 원장은 {@code (principal, operation, key)}가 unique다.
 * <p>
 * 순서는 SC-05를 따른다. 부르는 쪽이 인증과 객체 권한을 먼저 판정하고 입력을 검사한 뒤 이 메서드를 부른다. 같은 key의 receipt를
 * 돌려줄 때도 그 판정을 다시 거치므로, 권한을 잃은 사용자는 receipt를 받지 못한다.
 * <ul>
 * <li>처음 보는 key: 한 transaction에서 예약, 명령, receipt 저장을 하고 commit한다. 명령이 실패하면 예약도 되돌아가 같은
 * key로 다시 시도할 수 있다.</li>
 * <li>같은 key, 같은 지문의 완료 기록: 명령을 실행하지 않고 저장한 receipt를 돌려준다. version, 마감 같은 업무 조건은 다시
 * 검사하지 않는다.</li>
 * <li>같은 key, 다른 지문: 409 {@code idempotency_conflict}.</li>
 * <li>같은 key가 처리 중: 그 transaction이 끝날 때까지 정해진 시간만 기다린다. 끝나면 위 규칙을 따르고, 시간이 지나면 409
 * {@code idempotency_in_progress}다.</li>
 * </ul>
 * key가 없는 요청은 원장 없이 한 transaction으로 실행한다.
 * <p>
 * 지표: 이미 있는 key를 만난 요청을 결과(replayed, conflict, in_progress)별로 세고(astra.idempotency.requests), 그 key의 행을 확인하는 데
 * 걸린 시간(처리 중인 요청을 기다린 시간 포함)을 잰다(astra.idempotency.wait). tag는 연산 이름뿐이다.
 */
@Component
public class Idempotency {

	/** 요청 header 이름. */
	public static final String HEADER = "Idempotency-Key";

	// 업로드(files.upload.UploadService)와 같은 형식이다.
	private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._-]{1,100}");

	private static final JsonMapper CANONICAL = JsonMapper.builder()
		.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
		.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
		.build();

	private final JdbcClient jdbc;

	private final TransactionTemplate transaction;

	private final JsonMapper json;

	private final IdempotencyProperties properties;

	private final MeterRegistry meters;

	Idempotency(JdbcClient jdbc, TransactionTemplate transaction, JsonMapper json, IdempotencyProperties properties,
			MeterRegistry meters) {
		this.jdbc = jdbc;
		this.transaction = transaction;
		this.json = json;
		this.properties = properties;
		this.meters = meters;
	}

	/**
	 * @param principalUserId session principal
	 * @param operation 연산 이름. 예: material.create
	 * @param key {@value #HEADER} header 값. 없으면 null
	 * @param request 지문을 만들 요청의 의미 있는 부분: 대상 범위(경로의 ID), 검사한 본문, If-Match 같은 전제 조건
	 * @param receiptType 응답 본문 타입. receipt로 저장하고 재전송 때 그대로 돌려준다
	 * @param command 업무 write와 응답 생성. 이 transaction 안에서 실행한다
	 * @return 새로 만든 응답 또는 저장한 receipt
	 * @throws ApiException key 형식이 틀리면 400, 지문이 다르거나 처리 중이면 409
	 */
	public <T> T execute(long principalUserId, String operation, String key, Object request, Class<T> receiptType,
			Supplier<T> command) {
		if (key == null) {
			return this.transaction.execute((status) -> command.get());
		}
		if (!KEY.matcher(key).matches()) {
			throw ApiException.invalid("Idempotency-Key 형식이 올바르지 않습니다.");
		}
		String fingerprint = fingerprint(request);
		return this.transaction.execute((status) -> {
			long started = System.nanoTime();
			Optional<Entry> existing = boundedWait(operation, started,
					() -> reserve(principalUserId, operation, key, fingerprint));
			if (existing.isPresent()) {
				waited(operation, started);
				return replay(operation, existing.get(), fingerprint, receiptType);
			}
			T receipt = Objects.requireNonNull(command.get(), "receipt");
			this.jdbc.sql("""
					UPDATE idempotency_ledger
					   SET entry_state = 'completed', receipt_json = :receipt, completed_at = CURRENT_TIMESTAMP(3)
					 WHERE principal_user_id = :principal AND operation = :operation AND idempotency_key = :key
					""")
				.param("receipt", this.json.writeValueAsString(receipt))
				.param("principal", principalUserId)
				.param("operation", operation)
				.param("key", key)
				.update();
			return receipt;
		});
	}

	/**
	 * key를 예약한다. 같은 key의 행이 있으면 그 행을 잠가 읽는다. 처리 중인 행은 unique 검사에서 그 transaction이 끝날 때까지
	 * 기다린다. 보존기한이 지났지만 아직 지우지 않은 행은 지우고 한 번 더 예약한다.
	 * @return 이미 있는 행. 비어 있으면 이 transaction이 예약했다
	 */
	private Optional<Entry> reserve(long principal, String operation, String key, String fingerprint) {
		for (int attempt = 1;; attempt++) {
			try {
				this.jdbc.sql("""
						INSERT INTO idempotency_ledger (principal_user_id, operation, idempotency_key, request_fingerprint, expires_at)
						VALUES (:principal, :operation, :key, :fingerprint,
						        DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL :seconds SECOND))
						""")
					.param("principal", principal)
					.param("operation", operation)
					.param("key", key)
					.param("fingerprint", fingerprint)
					.param("seconds", this.properties.retention().toSeconds())
					.update();
				return Optional.empty();
			}
			catch (DuplicateKeyException ex) {
				// 방금 commit된 행은 이 transaction의 snapshot 밖이라 잠금 읽기로 본다. 중복 검사가 이미 공유 잠금을 잡았으므로
				// 공유 잠금으로 읽는다. 배타 잠금을 요청하면 같은 key를 기다리던 요청끼리 deadlock이 난다.
				Optional<Entry> entry = this.jdbc.sql("""
						SELECT ledger_entry_id, request_fingerprint, entry_state, receipt_json,
						       expires_at <= CURRENT_TIMESTAMP(3) AS expired
						  FROM idempotency_ledger
						 WHERE principal_user_id = :principal AND operation = :operation AND idempotency_key = :key
						   FOR SHARE
						""")
					.param("principal", principal)
					.param("operation", operation)
					.param("key", key)
					.query((rs, n) -> new Entry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
							rs.getBoolean(5)))
					.optional();
				if (entry.isPresent() && !entry.get().expired()) {
					return entry;
				}
				if (attempt == 2) {
					throw ex;
				}
				// 지난 key를 두 요청이 함께 다시 쓰면 한쪽은 deadlock으로 409 idempotency_in_progress를 받고 다시 보낸다.
				entry.ifPresent((e) -> this.jdbc.sql("DELETE FROM idempotency_ledger WHERE ledger_entry_id = :id")
					.param("id", e.id())
					.update());
			}
		}
	}

	// 같은 key를 처리 중인 transaction을 기다리는 시간을 이 구간에서만 줄인다. 명령의 다른 잠금 대기에는 기본값을 쓴다.
	private <T> T boundedWait(String operation, long started, Supplier<T> action) {
		this.jdbc.sql("SET SESSION innodb_lock_wait_timeout = " + this.properties.inFlightWaitSeconds()).update();
		try {
			return action.get();
		}
		catch (PessimisticLockingFailureException ex) {
			waited(operation, started);
			count(operation, "in_progress");
			throw new ApiException(HttpStatus.CONFLICT, "idempotency_in_progress",
					"같은 요청을 처리하고 있습니다. 잠시 뒤 다시 시도해 주세요.");
		}
		finally {
			this.jdbc.sql("SET SESSION innodb_lock_wait_timeout = DEFAULT").update();
		}
	}

	private <T> T replay(String operation, Entry entry, String fingerprint, Class<T> receiptType) {
		if (!entry.fingerprint().equals(fingerprint)) {
			count(operation, "conflict");
			throw new ApiException(HttpStatus.CONFLICT, "idempotency_conflict", "같은 Idempotency-Key를 다른 요청에 썼습니다.");
		}
		if (!"completed".equals(entry.state()) || entry.receipt() == null) {
			throw new IllegalStateException("완료하지 않은 멱등 원장 항목이 commit됐습니다: " + entry.id());
		}
		T receipt = this.json.readValue(entry.receipt(), receiptType);
		count(operation, "replayed");
		return receipt;
	}

	private void count(String operation, String outcome) {
		Counter.builder("astra.idempotency.requests")
			.description("이미 있는 Idempotency-Key를 만난 요청 수")
			.tag("operation", MetricTags.code(operation))
			.tag("outcome", outcome)
			.register(this.meters)
			.increment();
	}

	private void waited(String operation, long started) {
		Timer.builder("astra.idempotency.wait")
			.description("이미 있는 Idempotency-Key의 행을 확인하는 데 걸린 시간. 처리 중인 같은 key를 기다린 시간을 포함한다")
			.tag("operation", MetricTags.code(operation))
			.register(this.meters)
			.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
	}

	static String fingerprint(Object request) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(CANONICAL.writeValueAsBytes(request)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private record Entry(long id, String fingerprint, String state, String receipt, boolean expired) {
	}

}
