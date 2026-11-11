package com.stackcorp.astra.assessment;

import java.util.function.Supplier;

import com.stackcorp.astra.shared.ApiException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 성적 항목과 학생 성적을 바꾸는 transaction. 순서는 언제나 항목 행 잠금({@link GradebookRecords#lock})이 먼저다. READ COMMITTED로
 * 실행해 학생 성적의 유일 index (grade_item_id, user_id)에 이웃 항목까지 걸치는 gap 잠금을 만들지 않는다. deadlock이나 잠금 대기
 * 시간 초과는 전체를 되돌린 뒤 정해진 횟수만 처음부터 다시 실행한다. 다시 실행해도 읽은 수정 번호를 다시 비교하므로 결과가 같다.
 */
@Component
class GradeWrites {

	private static final Logger log = LoggerFactory.getLogger(GradeWrites.class);

	private static final int ATTEMPTS = 3;

	private final TransactionTemplate transaction;

	GradeWrites(PlatformTransactionManager transactionManager) {
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
	}

	/** @throws ApiException 다시 실행해도 잠금을 얻지 못하면 409 {@code write_busy}. 이때 아무것도 저장하지 않았다 */
	<T> T run(Supplier<T> write) {
		for (int attempt = 1;; attempt++) {
			try {
				return this.transaction.execute((status) -> write.get());
			}
			catch (TransientDataAccessException ex) {
				if (attempt >= ATTEMPTS) {
					log.warn("grade write gave up attempts={} error={}", attempt, ex.getClass().getSimpleName());
					throw new ApiException(HttpStatus.CONFLICT, "write_busy", "다른 저장과 겹쳤습니다. 잠시 뒤 다시 시도해 주세요.");
				}
				log.info("grade write retry attempt={} error={}", attempt, ex.getClass().getSimpleName());
			}
		}
	}

}
