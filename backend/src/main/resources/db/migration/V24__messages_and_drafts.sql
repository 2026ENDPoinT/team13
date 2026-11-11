-- 50 communication: 쪽지와 임시저장(docs/adr/0025-message-recipient-scope-and-boxes.md).

-- 답장과 전달은 새 쪽지이고 원래 쪽지를 값으로 가리킨다. 원래 쪽지는 지우지 않으므로(보관함별 soft delete) FK가 끊기지 않는다.
ALTER TABLE messages
  ADD COLUMN origin_message_id BIGINT UNSIGNED NULL COMMENT '답장·전달한 원래 쪽지 ID(새로 쓴 쪽지면 NULL)' AFTER attachment_group_id,
  ADD COLUMN origin_kind VARCHAR(10) NULL COMMENT '원래 쪽지와의 관계: REPLY(답장)|FORWARD(전달). origin_message_id와 함께 채운다' AFTER origin_message_id,
  ADD KEY idx_messages_origin_message_id (origin_message_id),
  ADD CONSTRAINT fk_messages_origin_messages FOREIGN KEY (origin_message_id) REFERENCES messages (message_id),
  ADD CONSTRAINT ck_messages_origin CHECK ((origin_message_id IS NULL AND origin_kind IS NULL)
                                        OR (origin_message_id IS NOT NULL AND origin_kind IN ('REPLY', 'FORWARD')));

-- 안 읽은 쪽지 수(EP-0239)는 polling으로 자주 부른다. 받은함에 보이는 행의 조건을 모두 담은 색인으로 행을 읽지 않고 센다.
ALTER TABLE message_recipients
  ADD KEY idx_message_recipients_inbox (recipient_id, recipient_deleted_at, canceled_at, read_at);

-- 임시저장은 게시글과 쪽지 작성이 같은 표를 쓴다. 쪽지 작성 초안에는 게시판이 없으므로 board_id를 비울 수 있게 하고,
-- 문맥 구분(context_type)과 NULL을 0으로 바꾼 board_key를 유일 키에 넣는다(course_key, target_post_key와 같은 방식).
ALTER TABLE post_drafts
  DROP FOREIGN KEY fk_post_drafts_boards;

ALTER TABLE post_drafts
  DROP INDEX uq_post_drafts_scope,
  ADD COLUMN context_type VARCHAR(20) NOT NULL DEFAULT 'POST' COMMENT '임시저장 문맥: POST(게시글 작성·수정)|MESSAGE(쪽지 작성)' AFTER user_id,
  MODIFY board_id BIGINT UNSIGNED NULL COMMENT '게시판 ID(쪽지 작성 초안이면 NULL)',
  MODIFY form_key VARCHAR(20) NOT NULL COMMENT '작성 폼 구분(게시글은 FROM_DIV 1|2, 쪽지는 compose)',
  MODIFY contents MEDIUMTEXT NOT NULL COMMENT '초안 내용 JSON(일반 텍스트 title, body). HTML로 해석하지 않는다',
  MODIFY content_hash CHAR(64) NULL COMMENT '내용 지문(SHA-256). 같은 revision의 재전송이 같은 내용인지 판단한다',
  MODIFY version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '클라이언트가 보낸 revision. 저장된 값보다 큰 요청만 덮어쓴다',
  ADD COLUMN board_key BIGINT UNSIGNED AS (IFNULL(board_id, 0)) STORED COMMENT '게시판 스코프 키(NULL 대체)' AFTER target_post_key,
  ADD UNIQUE KEY uq_post_drafts_scope (user_id, context_type, board_key, form_key, course_key, target_post_key),
  ADD CONSTRAINT ck_post_drafts_context CHECK ((context_type = 'POST' AND board_id IS NOT NULL)
                                            OR (context_type = 'MESSAGE' AND board_id IS NULL AND target_post_id IS NULL));

ALTER TABLE post_drafts
  ADD CONSTRAINT fk_post_drafts_boards FOREIGN KEY (board_id) REFERENCES boards (board_id);
