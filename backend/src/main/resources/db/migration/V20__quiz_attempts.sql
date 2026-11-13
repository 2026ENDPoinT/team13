-- 40 assessment: 온라인 퀴즈 출제, 응시, 채점(docs/adr/0021-quiz-attempt-model.md)
-- 응시 기간과 제목은 learning_activities(activity_type = test), 배점과 결과 공개 일시는 grade_items가 가진다.

-- 퀴즈 설정·문항 수정의 동시 수정 감지(lost update 방지). 쓰는 컬럼의 의미를 이 구현에 맞춰 적는다.
ALTER TABLE quizzes
  ADD COLUMN revision_no INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '퀴즈 설정·문항 수정 번호(수정할 때마다 1 증가, 동시 수정 감지)',
  MODIFY COLUMN duration_minutes INT NULL COMMENT '응시 제한 시간(분, NULL=응시 마감까지). 응시 마감 일시는 시작할 때 고정한다',
  MODIFY COLUMN attempt_limit INT NULL COMMENT '허용 응시 횟수(NULL=제한 없음)',
  MODIFY COLUMN shuffle_yn TINYINT(1) NOT NULL DEFAULT 0 COMMENT '응시마다 문항 순서를 섞는지(응시 ID로 정해지는 고정 순서)',
  MODIFY COLUMN answer_release_yn TINYINT(1) NOT NULL DEFAULT 0 COMMENT '정답·해설·문항별 점수 공개 여부(점수 공개 뒤, 응시 기간이 끝난 뒤에만 보인다)';

ALTER TABLE quiz_questions
  MODIFY COLUMN question_type VARCHAR(20) NOT NULL DEFAULT 'single' COMMENT '문항 유형(single:단일 선택, multiple:복수 선택, short:단답형 수동 채점)',
  MODIFY COLUMN question_text TEXT NOT NULL COMMENT '문항 지문(일반 텍스트)',
  MODIFY COLUMN explanation TEXT NULL COMMENT '해설(일반 텍스트, 정답 공개 뒤에만 학생에게 보인다)';

-- 종료 사유와 마감이 지난 진행 중 응시를 찾는 worker용 index.
ALTER TABLE quiz_attempts
  ADD COLUMN finish_reason VARCHAR(20) NULL COMMENT '종료 사유(submitted:학생 제출, deadline:응시 마감 일시에 서버가 닫음)' AFTER submitted_at,
  ADD KEY idx_quiz_attempts_status_deadline (status, deadline_at);

-- 단답형 수동 채점의 의견과 채점자.
ALTER TABLE quiz_attempt_answers
  MODIFY COLUMN grading_status VARCHAR(20) NULL COMMENT '채점 상태(NULL=응시 중, auto:자동 채점, pending:수동 채점 대기, manual:수동 채점)',
  ADD COLUMN grader_comment VARCHAR(1000) NULL COMMENT '채점 의견(일반 텍스트, 수동 채점)' AFTER awarded_score,
  ADD COLUMN graded_by_user_id BIGINT UNSIGNED NULL COMMENT '수동 채점자 ID(자동 채점이면 NULL)' AFTER graded_at,
  ADD KEY idx_quiz_attempt_answers_grader (graded_by_user_id),
  ADD CONSTRAINT fk_quiz_attempt_answers_graded_by FOREIGN KEY (graded_by_user_id) REFERENCES users (user_id);
