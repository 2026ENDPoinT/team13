-- 보안 검토 M4, L1(docs/adr/0022-survey-anonymity-and-idempotency.md, docs/adr/0010-upload-acceptance.md).
--
-- 1. 설문 응답 저장부의 행 ID를 임의 값으로 바꾼다. AUTO_INCREMENT ID는 입력 순서라서, 같은 transaction에 넣는 참여 원장
--    (survey_participations, user_id가 있다)의 ID 순서와 맞춰 익명 응답의 주인을 찾을 수 있었다. 이제 응용이 SecureRandom으로
--    63bit 양의 정수를 만들어 넣는다. 타입은 BIGINT UNSIGNED 그대로라 FK와 index의 크기, 결과 집계 query는 바뀌지 않는다.
--    이미 저장한 행도 임의 값으로 다시 매긴다. AUTO_INCREMENT 속성을 바꾸려면 그 컬럼을 가리키는 FK를 잠시 떼야 한다.
-- 2. 업로드 세션에 요청 지문을 둔다. 같은 Idempotency-Key로 다른 파일, 이름, 용도, 과목, 폴더를 보내면 409로 거부한다.

ALTER TABLE survey_participations DROP FOREIGN KEY fk_survey_participations_survey_responses;
ALTER TABLE survey_answers DROP FOREIGN KEY fk_survey_answers_survey_responses;
ALTER TABLE survey_answer_options DROP FOREIGN KEY fk_survey_answer_options_survey_answers;

ALTER TABLE survey_responses
  MODIFY COLUMN survey_response_id BIGINT UNSIGNED NOT NULL COMMENT '설문 응답 세트 ID(응용이 만드는 임의 값. 입력 순서가 드러나지 않는다, ADR-0022)';
ALTER TABLE survey_answers
  MODIFY COLUMN survey_answer_id BIGINT UNSIGNED NOT NULL COMMENT '설문 답변 ID(응용이 만드는 임의 값. 입력 순서가 드러나지 않는다, ADR-0022)';
ALTER TABLE survey_answer_options
  MODIFY COLUMN survey_answer_option_id BIGINT UNSIGNED NOT NULL COMMENT '설문 답변 보기 ID(응용이 만드는 임의 값. 입력 순서가 드러나지 않는다, ADR-0022)';

-- 이미 저장한 응답을 다시 매긴다. 새 ID는 RANDOM_BYTES(암호학적 난수) 7byte에 1을 더한 값이라 0이 없고, 기존의 작은 순번과
-- 겹치지 않는다. 대응표는 이 migration 안에서만 쓰고 끝에서 지운다.
CREATE TABLE v34_survey_rekey_responses (
  old_id BIGINT UNSIGNED NOT NULL COMMENT '옛 응답 세트 ID',
  new_id BIGINT UNSIGNED NOT NULL COMMENT '새 응답 세트 ID',
  PRIMARY KEY (old_id),
  UNIQUE KEY uq_v34_survey_rekey_responses_new (new_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='V34 응답 세트 ID 대응표(migration 안에서 지운다)';
CREATE TABLE v34_survey_rekey_answers (
  old_id BIGINT UNSIGNED NOT NULL COMMENT '옛 답변 ID',
  new_id BIGINT UNSIGNED NOT NULL COMMENT '새 답변 ID',
  PRIMARY KEY (old_id),
  UNIQUE KEY uq_v34_survey_rekey_answers_new (new_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='V34 답변 ID 대응표(migration 안에서 지운다)';
INSERT INTO v34_survey_rekey_responses (old_id, new_id)
SELECT survey_response_id, CAST(CONV(HEX(RANDOM_BYTES(7)), 16, 10) AS UNSIGNED) + 1 FROM survey_responses;
INSERT INTO v34_survey_rekey_answers (old_id, new_id)
SELECT survey_answer_id, CAST(CONV(HEX(RANDOM_BYTES(7)), 16, 10) AS UNSIGNED) + 1 FROM survey_answers;

UPDATE survey_answer_options o JOIN v34_survey_rekey_answers k ON k.old_id = o.survey_answer_id
   SET o.survey_answer_id = k.new_id,
       o.survey_answer_option_id = CAST(CONV(HEX(RANDOM_BYTES(7)), 16, 10) AS UNSIGNED) + 1;
UPDATE survey_answers a JOIN v34_survey_rekey_answers k ON k.old_id = a.survey_answer_id
   SET a.survey_answer_id = k.new_id;
UPDATE survey_answers a JOIN v34_survey_rekey_responses k ON k.old_id = a.survey_response_id
   SET a.survey_response_id = k.new_id;
UPDATE survey_participations p JOIN v34_survey_rekey_responses k ON k.old_id = p.current_response_id
   SET p.current_response_id = k.new_id;
UPDATE survey_responses r JOIN v34_survey_rekey_responses k ON k.old_id = r.survey_response_id
   SET r.survey_response_id = k.new_id;
DROP TABLE v34_survey_rekey_answers;
DROP TABLE v34_survey_rekey_responses;

ALTER TABLE survey_participations
  ADD CONSTRAINT fk_survey_participations_survey_responses FOREIGN KEY (current_response_id) REFERENCES survey_responses (survey_response_id);
ALTER TABLE survey_answers
  ADD CONSTRAINT fk_survey_answers_survey_responses FOREIGN KEY (survey_response_id) REFERENCES survey_responses (survey_response_id);
ALTER TABLE survey_answer_options
  ADD CONSTRAINT fk_survey_answer_options_survey_answers FOREIGN KEY (survey_answer_id) REFERENCES survey_answers (survey_answer_id);

ALTER TABLE upload_sessions
  ADD COLUMN request_fingerprint CHAR(64) NULL COMMENT '멱등 업로드 요청 지문(용도, 과목, 이름, 크기, 내용 SHA-256, 파일함 폴더를 정렬한 JSON의 SHA-256. key가 없으면 NULL)' AFTER client_upload_key;
