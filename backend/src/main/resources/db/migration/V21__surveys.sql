-- 40 assessment: 설문 등록, 응답, 결과(docs/adr/0022-survey-anonymity-and-idempotency.md)
-- 익명 설문은 참여(누가 완료했는지)와 응답(무엇을 답했는지)을 잇지 않는다(SC-05-A). 응답 쪽 행에는 사용자, 참여 행, 요청을 가리키는
-- 값을 두지 않고 시각은 일 단위로 자른다. 설문 안내와 답변은 일반 텍스트로만 저장한다. description_html은 쓰지 않는다.

-- 알림마당 설문의 안내 본문과 설정 수정 번호(lost update 방지). 강의실 설문의 제목·안내·기간은 학습활동이 가진다.
ALTER TABLE surveys
  ADD COLUMN description_text TEXT NULL COMMENT '설문 안내(일반 텍스트, 알림마당 설문만. 강의실 설문은 학습활동 설명을 쓴다)' AFTER description_html,
  ADD COLUMN revision_no INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '설문 설정 수정 번호(수정할 때마다 1 증가, 동시 수정 감지)';

-- 척도 문항: 보기 번호(option_no)가 척도 값이다.
ALTER TABLE survey_questions
  MODIFY COLUMN question_type VARCHAR(20) NOT NULL DEFAULT 'single' COMMENT '문항 유형(single:객관식 단일, multiple:객관식 복수, scale:척도(보기 번호가 척도 값), essay:서술형)';

-- 참여 원장. 익명 설문은 (설문, 사용자, key)와 최소 결과만 남기고 시각은 일 단위다(SC-05-A).
ALTER TABLE survey_participations
  ADD COLUMN completion_key VARCHAR(100) NULL COMMENT '익명 설문 완료 요청의 Idempotency-Key(SC-05-A 참여 원장. 실명 설문은 공용 멱등 원장을 쓰므로 NULL)',
  MODIFY COLUMN completed_at DATETIME(3) NULL COMMENT '응답 완료 일시(익명 설문은 일 단위 절삭 저장)',
  MODIFY COLUMN current_response_id BIGINT UNSIGNED NULL COMMENT '현재 응답 세트 ID(쓰지 않고 NULL로 둔다. 참여와 응답을 잇지 않는다, ADR-0022)',
  MODIFY COLUMN created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시(익명 설문은 일 단위 절삭 저장)';

ALTER TABLE survey_responses
  MODIFY COLUMN created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시(익명 설문은 일 단위 절삭 저장)';

ALTER TABLE survey_answers
  MODIFY COLUMN answer_text VARCHAR(2000) NULL COMMENT '서술형 답변(일반 텍스트, 최대 2000자)',
  MODIFY COLUMN created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시(익명 설문은 일 단위 절삭 저장)';

ALTER TABLE survey_answer_options
  MODIFY COLUMN created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시(익명 설문은 일 단위 절삭 저장)';
