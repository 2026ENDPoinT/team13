package com.stackcorp.astra.shared.audit;

import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.stackcorp.astra.shared.RequestIds;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 감사 기록(SC-09, ADR-0012). 누가(actor, session), 무엇을(operation), 어디에(object, course), 결과(code)를 requestId,
 * DB 시각과 함께 남긴다. 요청 본문, 파일 이름, 자유 텍스트는 받지 않는다. 이름과 코드는 소문자 식별자만 허용한다.
 * <ul>
 * <li>부르는 쪽의 transaction 안이면 같은 transaction에 쓴다. 기록에 실패하면 그 명령도 되돌린다.</li>
 * <li>transaction 밖이면 바로 commit한다. 기록에 실패해도 요청을 실패시키지 않고 경고 로그를 남기며 빠진 수를 연산별로 센다
 * (astra.audit.write.failures).</li>
 * </ul>
 */
@Component
public class AuditLog {

	/** 성공 결과 코드. */
	public static final String OK = "ok";

	private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

	private static final Pattern OPERATION = Pattern.compile("[a-z][a-z0-9_.]{0,63}");

	private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,39}");

	private final JdbcClient jdbc;

	private final MeterRegistry meters;

	AuditLog(JdbcClient jdbc, MeterRegistry meters) {
		this.jdbc = jdbc;
		this.meters = meters;
	}

	/** 성공한 명령을 남긴다. */
	public void success(long actorUserId, long sessionId, String operation, String objectType, long objectId,
			Long courseId) {
		record(actorUserId, sessionId, operation, objectType, objectId, courseId, OK);
	}

	/**
	 * 익명 설문 참여처럼 요청 처리 기록과 이어지면 안 되는 성공을 requestId 없이 남긴다(SC-09의 SC-05-A 예외). 행위자와 대상,
	 * 결과만 남고, 같은 요청의 로그나 응답 저장부와 requestId로 맞출 수 없다. 부르는 쪽은 응답 세트 ID나 답변에서 나온 값을 대상으로
	 * 넘기지 않는다.
	 */
	public void successWithoutRequestId(long actorUserId, long sessionId, String operation, String objectType,
			long objectId, Long courseId) {
		record(actorUserId, sessionId, operation, objectType, objectId, courseId, OK, null);
	}

	/**
	 * @param actorUserId 정할 수 없으면 null(예: 계정을 알 수 없는 로그인 실패)
	 * @param operation 예: material.create
	 * @param objectType 예: learning_activity. 대상이 없으면 null
	 * @param resultCode {@link #OK} 또는 거부 코드
	 */
	public void record(Long actorUserId, Long sessionId, String operation, String objectType, Long objectId,
			Long courseId, String resultCode) {
		record(actorUserId, sessionId, operation, objectType, objectId, courseId, resultCode, RequestIds.current());
	}

	private void record(Long actorUserId, Long sessionId, String operation, String objectType, Long objectId,
			Long courseId, String resultCode, String requestId) {
		if (!OPERATION.matcher(operation).matches() || (objectType != null && !IDENTIFIER.matcher(objectType).matches())
				|| !IDENTIFIER.matcher(resultCode).matches()) {
			throw new IllegalArgumentException("감사 기록의 이름과 코드는 소문자 식별자여야 합니다.");
		}
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			insert(actorUserId, sessionId, operation, objectType, objectId, courseId, resultCode, requestId);
			return;
		}
		try {
			insert(actorUserId, sessionId, operation, objectType, objectId, courseId, resultCode, requestId);
		}
		catch (DataAccessException ex) {
			log.warn("audit record failed operation={} result={} error={}", operation, resultCode,
					ex.getClass().getSimpleName());
			Counter.builder("astra.audit.write.failures")
				.description("transaction 밖에서 쓰지 못해 빠진 감사 기록 수")
				.tag("operation", operation)
				.register(this.meters)
				.increment();
		}
	}

	private void insert(Long actorUserId, Long sessionId, String operation, String objectType, Long objectId,
			Long courseId, String resultCode, String requestId) {
		this.jdbc.sql("""
				INSERT INTO audit_events (actor_user_id, session_id, operation, object_type, object_id, course_id,
				                          result_code, request_id)
				VALUES (:actor, :session, :operation, :objectType, :objectId, :course, :result, :requestId)
				""")
			.param("actor", actorUserId)
			.param("session", sessionId)
			.param("operation", operation)
			.param("objectType", objectType)
			.param("objectId", objectId)
			.param("course", courseId)
			.param("result", resultCode)
			.param("requestId", requestId)
			.update();
	}

}
