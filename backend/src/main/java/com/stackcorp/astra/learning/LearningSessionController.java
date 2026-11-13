package com.stackcorp.astra.learning;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;

import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.learning.LearningSessionService.Ended;
import com.stackcorp.astra.learning.LearningSessionService.Granted;
import com.stackcorp.astra.learning.LearningSessionService.HeartbeatResult;
import com.stackcorp.astra.learning.LearningSessionService.Started;
import com.stackcorp.astra.learning.LectureVideoController.Playback;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 온라인 강의 학습 session(EP-0048~EP-0050, EP-0053, EP-0054, EP-0057, EP-0058의 대체 계약, ADR-0014).
 * 적립 시간은 서버 시각과 원장으로만 정한다. 요청은 순번, 재생 위치 힌트, 재생 여부만 보내고 경과 시간이나 주기는 받지 않는다(SEC-11).
 * 다른 사람의 session이거나 경로의 과목과 다르면 404, 자기 session이 이미 닫혔으면 409다. 영상 cookie는 볼 수 있는 동안에만 응답마다 다시 발급한다.
 */
@RestController
class LearningSessionController {

	private static final long MAX_SEQ = 1_000_000_000L;

	private static final Set<String> END_REASONS = Set.of("button", "unload");

	private final LearningSessionService sessions;

	private final RateLimiter rateLimiter;

	LearningSessionController(LearningSessionService sessions, RateLimiter rateLimiter) {
		this.sessions = sessions;
		this.rateLimiter = rateLimiter;
	}

	/**
	 * 학습 시작. 같은 사용자의 열린 session은 대체된다(superseded). 응답의 주기마다 하트비트를 보낸다. 학습 인증을 요구하는 배포에서
	 * 학생에게 grant가 없으면 403 {@code learning_auth_required}(SC-06-A).
	 */
	@PostMapping("/api/courses/{courseId}/lecture-items/{itemId}/learning-sessions")
	ResponseEntity<StartedView> start(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long itemId) {
		this.rateLimiter.check("learning-session-start", actor.userId(), 30, Duration.ofMinutes(10));
		Granted<Started> started = this.sessions.start(actor.userId(), actor.sessionId(), courseId, itemId);
		return granted(ResponseEntity.status(HttpStatus.CREATED), started)
			.body(new StartedView(started.body(), started.playback()));
	}

	@PostMapping("/api/courses/{courseId}/learning-sessions/{sessionId}/heartbeats")
	ResponseEntity<HeartbeatView> heartbeat(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long sessionId, @RequestBody HeartbeatRequest body) {
		if (body.seq() == null || body.seq() < 1 || body.seq() > MAX_SEQ) {
			throw ApiException.invalid("seq는 1 이상의 증가하는 순번이어야 합니다.");
		}
		this.rateLimiter.check("learning-heartbeat", actor.userId(), 120, Duration.ofMinutes(10));
		Granted<HeartbeatResult> result = this.sessions.heartbeat(actor.userId(), actor.sessionId(), courseId,
				sessionId, body.seq(), body.positionSec(), body.playing());
		return granted(ResponseEntity.ok(), result).body(new HeartbeatView(result.body(), result.playback()));
	}

	/** 종료. 여러 번 보내도 결과가 같다. 페이지를 떠날 때는 fetch keepalive로 보낸다(CSRF header가 필요해 sendBeacon은 쓰지 않는다). */
	@PostMapping("/api/courses/{courseId}/learning-sessions/{sessionId}/end")
	ResponseEntity<Ended> end(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long sessionId, @RequestBody(required = false) EndRequest body) {
		String reason = body == null || body.reason() == null ? "button" : body.reason();
		if (!END_REASONS.contains(reason)) {
			throw ApiException.invalid("reason은 button 또는 unload여야 합니다.");
		}
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.sessions.end(actor.userId(), actor.sessionId(), courseId, sessionId,
					body == null ? null : body.positionSec(), reason));
	}

	private static ResponseEntity.BodyBuilder granted(ResponseEntity.BodyBuilder response, Granted<?> granted) {
		response.cacheControl(CacheControl.noStore());
		granted.cookies().forEach((c) -> response.header(HttpHeaders.SET_COOKIE, c.header()));
		return response;
	}

	/**
	 * 하트비트 요청. 사용자, 경과 시간, 주기는 받지 않는다. 문서에 없는 필드는 400이다.
	 *
	 * @param seq session 안에서 1부터 증가하는 순번. 이미 받은 순번 이하는 아무것도 바꾸지 않는다
	 * @param positionSec 재생 위치(초). 이어보기 힌트로만 저장한다
	 * @param playing 재생 중인지. 기록만 하고 적립에 쓰지 않는다
	 */
	record HeartbeatRequest(Long seq, Integer positionSec, Boolean playing) {
	}

	/**
	 * @param reason button(종료 버튼) | unload(페이지 이탈). 없으면 button
	 * @param positionSec 마지막 재생 위치(초). 이어보기 힌트
	 */
	record EndRequest(String reason, Integer positionSec) {
	}

	record StartedView(long sessionId, long itemId, int heartbeatIntervalSec, int leaseSec, boolean creditable,
			LocalDateTime creditUntil, Integer resumePositionSec, ItemProgress progress, Playback playback) {

		StartedView(Started s, Playback playback) {
			this(s.sessionId(), s.itemId(), s.heartbeatIntervalSec(), s.leaseSec(), s.creditable(), s.creditUntil(),
					s.resumePositionSec(), s.progress(), playback);
		}

	}

	/**
	 * @param playback 이번 응답이 cookie를 다시 발급했으면 manifest 경로와 유효 시간
	 */
	record HeartbeatView(long sessionId, String outcome, int creditedSec, boolean creditOpen, String creditState,
			LocalDateTime creditUntil, LocalDateTime leaseExpiresAt, int leaseRemainingSec, ItemProgress progress,
			Playback playback) {

		HeartbeatView(HeartbeatResult r, Playback playback) {
			this(r.sessionId(), r.outcome(), r.creditedSec(), r.creditOpen(), r.creditState(), r.creditUntil(),
					r.leaseExpiresAt(), r.leaseRemainingSec(), r.progress(), playback);
		}

	}

}
