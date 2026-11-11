package com.stackcorp.astra.learning;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 하트비트 적립 규칙 v1(ADR-0014, SEC-11, CHG-05). 입력은 서버 시각과 원장뿐이다. client가 보낸 경과 시간, 주기, 재생 위치는 받지 않는다.
 * <ul>
 * <li>적립은 직전 기준 시각부터 지금까지의 벽시계 구간이다. 하트비트 횟수와 무관하다.</li>
 * <li>기준 시각에서 주기의 1/2이 지나기 전에 온 하트비트는 아무것도 바꾸지 않는다. 그 시간은 다음 하트비트가 적립한다.</li>
 * <li>주기의 2배보다 긴 공백은 적립하지 않고 기준 시각만 지금으로 옮긴다.</li>
 * <li>구간은 같은 사용자가 이미 적립한 구간의 끝(frontier)과 활동의 open_at 뒤, close_at 앞으로 자른다.
 * 그래서 두 기기, 두 session이 같은 벽시계 시간을 두 번 적립하지 못한다.</li>
 * <li>원장은 초 단위다. 1초 미만의 나머지는 기준 시각에 남겨 다음 구간이 적립한다.</li>
 * </ul>
 * 규칙 v2(SC-06-A, ADR-0029)는 v1에 학습 인증 경계를 더한다. 학습 인증을 요구하는 배포에서 시작한 session에만 쓴다.
 * <ul>
 * <li>구간은 적립 유효기한 min(grant 만료, close_at, 로그인 session 만료)에서 자른다. 경계를 지난 하트비트는 최소 간격을 보지 않고 경계까지
 * 적립한 뒤 session을 {@code reauth_required}로 바꾼다. 이미 적립한 시간은 그대로 둔다.</li>
 * <li>{@code reauth_required}에서는 적립하지 않고 기준 시각만 지금으로 옮긴다. 다시 인증하면 {@code active}로 돌아가고 그 사이
 * 시간은 소급하지 않는다.</li>
 * </ul>
 */
final class CreditRule {

	static final String VERSION = "v1";

	/** 학습 인증 경계를 더한 규칙. */
	static final String AUTH_BOUND_VERSION = "v2";

	private CreditRule() {
	}

	static Duration minGap(int intervalSec) {
		return Duration.ofSeconds(intervalSec).dividedBy(2);
	}

	static Duration maxGap(int intervalSec) {
		return Duration.ofSeconds(intervalSec * 2L);
	}

	/** 마지막 기준 시각 뒤 이 시간 안에 하트비트가 없으면 session을 lease_expired로 닫는다. */
	static int leaseSec(int intervalSec) {
		return intervalSec * 3;
	}

	/**
	 * @param baseline 이 session의 적립 기준 시각
	 * @param frontier 이 사용자의 모든 session이 적립한 구간의 끝. 없으면 null
	 * @param window 적립할 수 있는 기간. 적립 대상이 아니면 null
	 * @param last 종료 요청이면 true. 최소 간격을 보지 않는다. 종료는 session마다 한 번뿐이다
	 */
	static Credit apply(LocalDateTime baseline, LocalDateTime frontier, LocalDateTime now, Window window,
			int intervalSec, boolean last) {
		Duration elapsed = Duration.between(baseline, now);
		if (elapsed.isNegative() || (!last && elapsed.compareTo(minGap(intervalSec)) < 0)) {
			return new Credit(Outcome.TOO_EARLY, null, null, 0, baseline);
		}
		if (elapsed.compareTo(maxGap(intervalSec)) > 0) {
			return none(Outcome.GAP, now);
		}
		if (window == null) {
			return none(Outcome.NOT_CREDITABLE, now);
		}
		LocalDateTime from = latest(latest(baseline, frontier), window.openAt());
		LocalDateTime to = window.closeAt() != null && window.closeAt().isBefore(now) ? window.closeAt() : now;
		long seconds = from.isBefore(to) ? Duration.between(from, to).toSeconds() : 0;
		if (seconds == 0) {
			boolean closed = window.closeAt() != null && !now.isBefore(window.closeAt());
			return none(closed ? Outcome.WINDOW_CLOSED : Outcome.ALREADY_CREDITED, now);
		}
		LocalDateTime end = from.plusSeconds(seconds);
		return new Credit(Outcome.CREDITED, from, end, (int) seconds, to.equals(now) ? end : now);
	}

	private static Credit none(Outcome outcome, LocalDateTime now) {
		return new Credit(outcome, null, null, 0, now);
	}

	private static LocalDateTime latest(LocalDateTime a, LocalDateTime b) {
		return b != null && b.isAfter(a) ? b : a;
	}

	/** 응답의 outcome 값은 이름의 소문자다. */
	enum Outcome {

		/** 구간을 적립했다 */
		CREDITED,
		/** 최소 간격 전에 왔다. 아무것도 바꾸지 않는다 */
		TOO_EARLY,
		/** 이미 받은 순번이거나 그보다 작은 순번이다. 아무것도 바꾸지 않는다 */
		DUPLICATE,
		/** 공백이 상한보다 길었다. 적립하지 않고 기준 시각만 옮긴다 */
		GAP,
		/** 이 사용자의 다른 session이 이미 적립한 구간이다 */
		ALREADY_CREDITED,
		/** close_at이 지났다. 재생은 계속할 수 있지만 적립하지 않는다 */
		WINDOW_CLOSED,
		/** 학습자가 아니다(담당자 미리보기) */
		NOT_CREDITABLE,
		/** 학습 인증이 끝났거나 폐기됐다. 다시 인증할 때까지 적립하지 않는다(v2) */
		REAUTH_REQUIRED,
		/** 다시 인증해 적립을 이어 간다. 인증이 없던 시간은 적립하지 않는다(v2) */
		RESUMED;

		String code() {
			return name().toLowerCase();
		}

	}

	/** 적립할 수 있는 기간 [openAt, closeAt). null은 제한 없음이다. */
	record Window(LocalDateTime openAt, LocalDateTime closeAt) {
	}

	/**
	 * @param from 원장에 쓸 구간 시작. 적립이 없으면 null
	 * @param baseline 다음 기준 시각
	 */
	record Credit(Outcome outcome, LocalDateTime from, LocalDateTime to, int seconds, LocalDateTime baseline) {
	}

}
