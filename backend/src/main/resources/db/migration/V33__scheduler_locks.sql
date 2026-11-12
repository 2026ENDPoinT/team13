-- shared: worker의 주기 작업 중 표 전체를 훑는 작업을 인스턴스 하나만 실행하게 하는 lease 잠금(architecture.md 4.3절).
-- 결정은 docs/adr/0034-observability-and-scheduler-lock.md에 있다. 업무 테이블을 가리키지 않는 기반 테이블이라 FK가 없다
-- (db/tools/check.sql의 isolated_table 예외 목록에 있다).

CREATE TABLE scheduler_locks (
  scheduler_lock_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '주기 작업 잠금 ID',
  lock_name    VARCHAR(64)  NOT NULL COMMENT '잠금 이름(주기 작업 이름, 예: files.storage_gc)',
  locked_until DATETIME(3)  NOT NULL COMMENT 'lease 끝 일시(DB 시계). 이 시각이 지나면 다른 인스턴스가 잡을 수 있다',
  locked_at    DATETIME(3)  NOT NULL COMMENT '마지막으로 잡은 일시(DB 시계)',
  locked_by    VARCHAR(160) NOT NULL COMMENT '잡은 인스턴스와 이번 실행의 token(host:pid:token). 놓을 때 같은 값인지 확인한다',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  PRIMARY KEY (scheduler_lock_id),
  UNIQUE KEY uq_scheduler_locks_lock_name (lock_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='주기 작업 lease 잠금';
