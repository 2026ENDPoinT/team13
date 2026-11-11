# ADR-0013 강의실 공지는 과목 공지 게시판의 게시글로 둔다

## 상태
채택 (2026-11-11)

## 맥락
강의실 공지(화면 47, 48, EP-0044~0047)를 구현한다. V1 스키마에는 게시판(`boards`), 게시글(`posts`), 열람 기록(`post_read_receipts`), 공지 확인(`post_confirmations`)이 있다. 설계 문서는 과목을 개설할 때 공지 게시판(`K001`) 1행을 넣는다고 적었지만([db/design/50_communication.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/db/design/50_communication.md)), 과목은 학사 동기화로 `catalog`에 생기고 `catalog`는 `communication`의 테이블을 쓸 수 없다. 과목이 생기는 시점에 게시판을 만들 곳이 없다. `boards`에는 과목과 유형에 대한 유일 제약이 없어서, 첫 공지를 쓸 때 게시판을 만들면 동시에 쓴 두 공지가 서로 다른 게시판에 들어갈 수 있다.

## 결정
1. 공지는 그 과목의 공지 게시판(`board_type = 'K001'`)에 속한 `posts` 행(`post_kind = 'NOTICE'`)이다. 본문은 `body_text`에 일반 텍스트로 두고 `body_html`은 쓰지 않는다. 게시 일시(`published_at`)가 NULL이면 임시, 미래면 예약 공지다. 삭제는 `deleted_at`으로 한다.
2. 게시판은 첫 공지를 쓸 때 만든다. V11이 `boards`에 생성 열 `course_notice_key`(K001이면 1)와 `UNIQUE (course_id, course_notice_key)`를 더해 과목마다 하나만 남긴다. 만드는 쪽은 `INSERT … ON DUPLICATE KEY UPDATE board_id = LAST_INSERT_ID(board_id)`로 먼저 생긴 행의 ID를 받고, 공지를 쓰는 transaction과 따로 commit한다.
3. 읽음과 확인은 `post_read_receipts`, `post_confirmations`의 `(post_id, user_id)` 유일 행이다. 조회수(`posts.view_count`)는 읽음 행을 처음 만들 때만 올린다.
4. 수정은 `posts.version`을 비교하는 조건부 UPDATE다. 맞지 않으면 409다.
5. 작성의 `Idempotency-Key`는 공용 멱등 원장(`notice.create`, [ADR-0012](0012-idempotency-ledger-and-audit.md))이 받는다. 같은 key에 같은 과목과 같은 검사한 본문이면 처음 응답을, 다르면 409 `idempotency_conflict`를 돌려준다. `posts.client_request_id`는 쓰지 않는다. 작성, 수정, 삭제는 감사 기록(`notice.create`, `notice.update`, `notice.delete`, 대상 `post`)을 남기고 제목과 본문은 남기지 않는다.

## 결과와 트레이드오프
- 질의응답, 열린게시판, 1:1 문의가 같은 테이블과 읽음 원장을 쓸 수 있다. 공지 전용 테이블을 따로 만들면 이 기능들과 읽음·첨부 처리가 둘로 갈린다.
- 과목 개설 때 게시판을 미리 만드는 방식은 `catalog`가 `communication`의 port를 불러야 하고, 이미 있는 과목에는 별도 backfill이 필요하다. 첫 쓰기 때 만들면 이 둘이 없다. 공지가 없는 과목에는 게시판 행도 없고, 조회는 빈 목록을 돌려준다.
- 앱 잠금(`GET_LOCK`)이나 `courses` 행 잠금으로 동시 생성을 막는 방식은 쓰지 않았다. 앞의 것은 연결 단위 상태라 pool과 섞이기 쉽고, 뒤의 것은 다른 모듈의 테이블을 잠근다.
- 질의응답(`K002`)은 과목마다 하나인지 아직 정하지 않아 제약에 넣지 않았다. 필요하면 같은 방식으로 생성 열의 조건을 넓힌다.
