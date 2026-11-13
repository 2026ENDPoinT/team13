package com.stackcorp.astra.identity.session;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.stackcorp.astra.identity.api.SchoolCourseRooms;
import com.stackcorp.astra.identity.api.SchoolSessions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * STack session ID → 학교 e-Class session. 메모리에만 둔다(학교 쿠키를 저장하지 않는다).
 * <ul>
 * <li>로그아웃·만료 폐기({@link SessionStore#revoke})는 바로 닫는다.</li>
 * <li>그 밖의 폐기(다른 기기 로그아웃, 기기 폐기)와 요청 없이 지난 유휴·절대 만료는 {@link #SWEEP_MINUTES}분마다 user_sessions를 보고
 * 닫는다. 끝난 STack session의 학교 session은 어느 요청도 쓸 수 없다(그 session으로는 인증되지 않는다).</li>
 * </ul>
 */
@Component
public class SchoolSessionRegistry implements SchoolSessions, DisposableBean {

	static final int SWEEP_MINUTES = 5;

	private static final Logger log = LoggerFactory.getLogger(SchoolSessionRegistry.class);

	private final Map<Long, SchoolCourseRooms> rooms = new ConcurrentHashMap<>();

	private final JdbcClient jdbc;

	private final SessionProperties properties;

	private final ScheduledExecutorService sweeper;

	SchoolSessionRegistry(JdbcClient jdbc, SessionProperties properties) {
		this.jdbc = jdbc;
		this.properties = properties;
		this.sweeper = Executors.newSingleThreadScheduledExecutor(
				Thread.ofPlatform().daemon().name("school-session-sweeper").factory());
		this.sweeper.scheduleWithFixedDelay(this::sweepQuietly, SWEEP_MINUTES, SWEEP_MINUTES, TimeUnit.MINUTES);
	}

	/**
	 * 로그인으로 열린 학교 session을 이 STack session에 붙인다. 이미 누가 가져간 손잡이는 붙이지 않는다.
	 * @return 붙였으면 true. 이제 이 registry가 닫는다
	 */
	public boolean keep(long sessionId, SchoolCourseRooms schoolRooms) {
		if (schoolRooms == null || !schoolRooms.claim()) {
			return false;
		}
		SchoolCourseRooms previous = this.rooms.put(sessionId, schoolRooms);
		if (previous != null && previous != schoolRooms) {
			previous.close();
		}
		return true;
	}

	@Override
	public Optional<SchoolCourseRooms> of(long sessionId) {
		return Optional.ofNullable(this.rooms.get(sessionId));
	}

	@Override
	public void forget(long sessionId) {
		SchoolCourseRooms removed = this.rooms.remove(sessionId);
		if (removed != null) {
			removed.close();
		}
	}

	int size() {
		return this.rooms.size();
	}

	/** 끝난 STack session(폐기, 유휴·절대 만료, 없는 행)의 학교 session을 닫는다. */
	void sweep() {
		List<Long> held = new ArrayList<>(this.rooms.keySet());
		if (held.isEmpty()) {
			return;
		}
		Set<Long> alive = new HashSet<>();
		for (int from = 0; from < held.size(); from += 500) {
			List<Long> chunk = held.subList(from, Math.min(held.size(), from + 500));
			alive.addAll(this.jdbc.sql("""
					SELECT session_id FROM user_sessions
					 WHERE session_id IN (:ids) AND revoked_at IS NULL
					   AND COALESCE(last_seen_at, authenticated_at) > DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL :idle SECOND)
					   AND authenticated_at > DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL :absolute SECOND)
					""")
				.param("ids", chunk)
				.param("idle", this.properties.idleTimeout().toSeconds())
				.param("absolute", this.properties.absoluteTimeout().toSeconds())
				.query(Long.class)
				.list());
		}
		for (long sessionId : held) {
			if (!alive.contains(sessionId)) {
				forget(sessionId);
			}
		}
	}

	private void sweepQuietly() {
		try {
			sweep();
		}
		catch (RuntimeException ex) {
			log.warn("School session sweep failed: type={}", ex.getClass().getSimpleName());
		}
	}

	@Override
	public void destroy() {
		this.sweeper.shutdownNow();
		for (Long sessionId : new ArrayList<>(this.rooms.keySet())) {
			forget(sessionId);
		}
	}

}
