package com.stackcorp.astra.learning;

import java.sql.SQLException;
import java.util.function.Supplier;

import org.springframework.dao.PessimisticLockingFailureException;

/**
 * 교착으로 진 transaction을 처음부터 다시 실행한다. 온라인 출석 반영(OnlineAttendance)은 하트비트와 차시·영상 활동 명령이 주차, 콘텐츠,
 * 진도, 출석 기록을 함께 잠가 드물게 교착할 수 있다. MySQL은 진 쪽 transaction 전체를 되돌리므로, 단위 안의 쓰기(멱등 원장, 감사 기록,
 * version 증가, 첨부)가 모두 같은 transaction이면 다시 실행해도 한 번 실행한 것과 같다. 부르는 쪽은 transaction 경계 밖에서 부른다.
 * 잠금 시간 초과는 다시 실행하지 않는다. 기다림이 그 한 번으로 끝나게 한다.
 */
final class DeadlockRetry {

	static final int ATTEMPTS = 3;

	/** MySQL이 교착으로 transaction을 되돌릴 때의 SQLSTATE. */
	private static final String DEADLOCK = "40001";

	private DeadlockRetry() {
	}

	static <T> T run(Supplier<T> work) {
		for (int attempt = 1;; attempt++) {
			try {
				return work.get();
			}
			catch (PessimisticLockingFailureException ex) {
				if (attempt == ATTEMPTS || !deadlock(ex)) {
					throw ex;
				}
			}
		}
	}

	static void run(Runnable work) {
		run(() -> {
			work.run();
			return null;
		});
	}

	static boolean deadlock(PessimisticLockingFailureException ex) {
		return ex.getMostSpecificCause() instanceof SQLException sql && DEADLOCK.equals(sql.getSQLState());
	}

}
