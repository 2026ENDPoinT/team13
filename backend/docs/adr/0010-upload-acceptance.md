# ADR-0010 업로드는 형식을 검사해 받고, 악성코드 검사는 port로 남긴다

## 상태
채택 (2026-11-11). 6번의 검사기는 [ADR-0018](0018-malware-scan-clamd.md)로 정했다. 9번의 inline 금지에는 서버가 판정한 PNG·JPEG 이미지 보기 예외가 있다([ADR-0033](0033-document-preview-and-office-conversion.md) 5번).

## 맥락
서버 계약 SC-08은 업로드를 "주체·크기·형식 확인 → 격리 저장 → 검사·변환 → 권한을 가진 객체에 첨부 → 공개" 순서로 처리하도록 정했다. [60_files_integrations.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/backend/60_files_integrations.md) 7절은 `POST /api/uploads` 하나와 `quarantined → clean → attached` 상태를 제안한다.

원본 ASTRA의 업로드는 형식 목록이 비어 있고, 경로 코드와 사용자 값을 client가 보낸다(FILES-11, SEC-15). 악성코드 검사기는 아직 정하지 않았다. 기관 자료를 외부 분석 서비스로 보내려면 데이터 처리 정책이 먼저 있어야 한다(SC-08).

검사기가 없다고 업로드를 `quarantined`에 묶어 두면 첨부를 확정할 수 없어 강의 자료 등록을 만들 수 없다.

## 결정
1. 업로드 API는 `files`의 `POST /api/uploads` 하나다. 요청은 파일, 과목, 용도만 보낸다. 올린 사람은 session에서 정한다.
2. 용도는 서버가 아는 목록(`upload_policies.policy_code`)에 있을 때만 받는다. 용도마다 그 과목에서 필요한 역할이 정해져 있다. 지금은 `lecture_material`(담당자) 하나다.
3. 받을 때 아래를 순서대로 확인한다. 하나라도 어긋나면 저장하지 않는다.
   - 사용자 단위 요청 제한
   - 용도와 과목 권한
   - 파일 이름 정규화: 마지막 경로 조각만 쓰고, 제어 문자와 방향 제어 문자를 없애고, `< > : " | ? *`를 `_`로 바꾼다
   - 정책의 확장자 목록과 크기 상한
   - 내용 형식: 파일 앞부분의 signature가 확장자와 맞아야 한다. 검사 규칙이 없는 확장자는 정책에 있어도 거부한다
4. 저장하는 MIME은 서버가 판정한 값이다. client가 보낸 `Content-Type`은 쓰지 않는다.
5. 원본은 `content` bucket의 `blobs/{sha256 앞 2자}/{sha256}`에 둔다. 같은 내용은 객체 하나를 함께 쓰고 `file_blobs.reference_count`로 센다. client가 보낸 이름은 저장 key에 들어가지 않는다.
6. 형식 검사를 통과한 업로드는 바로 `clean`이 된다. 악성코드 검사는 하지 않는다. 검사기를 붙이면 결과가 나올 때까지 `quarantined`로 두고, client는 `GET /api/uploads/{id}`로 상태를 확인한다. API의 상태 값은 지금부터 이 흐름을 전제로 한다.
7. 첨부 확정은 부모 저장과 같은 transaction에서 한다(SC-05-B). `files`의 `Attachments.attach`가 소유자, 과목, 용도, 상태, 만료를 한 조건으로 잠가 확인하고 `attached`로 바꾼다. 한 업로드는 한 번만 첨부된다. 조건에 맞지 않는 업로드가 하나라도 있으면 부모 저장 전체를 되돌린다. 어느 업로드가 왜 안 되는지는 응답에 밝히지 않는다.
8. 첨부하지 않은 업로드는 올린 사람만 조회하고 지운다. 첨부하기 전에는 누구도 내려받지 못한다.
9. 원본 내려받기는 `GET /api/courses/{courseId}/files/{fileId}/content`다. 요청마다 부모 객체의 권한을 판정하고, 언제나 `Content-Disposition: attachment`, `X-Content-Type-Options: nosniff`, `Content-Security-Policy: sandbox`, `Cache-Control: private, no-store`로 응답한다. 브라우저가 문서로 해석하는 inline 응답은 두지 않는다.
10. `Idempotency-Key`를 보내면 같은 사용자의 같은 key에 같은 요청(용도, 과목, 이름, 크기, 내용 SHA-256, 파일함 폴더가 같다)은 처음 만든 업로드를 다시 돌려준다. 다른 요청이면 409 `idempotency_conflict`이고 아무것도 저장하지 않는다. 판정은 hash를 계산한 뒤, 객체를 저장하기 전에 한다. 지문은 `upload_sessions.request_fingerprint`(V34)에 있고 규칙은 공용 멱등 원장과 같다([ADR-0012](0012-idempotency-ledger-and-audit.md) 3번).

## 결과와 트레이드오프
- 확장자를 바꾼 HTML, script, 실행 파일은 저장되지 않는다. 저장된 파일도 같은 origin에서 문서로 열리지 않는다.
- signature 검사는 형식을 속인 파일을 거르는 검사다. 형식이 맞는 문서 안의 악성 macro나 exploit은 거르지 못한다. 학교 자료를 받기 전에 검사기를 정한다([architecture.md](../architecture.md) 12절).
- 업로드 본문은 권한을 판정하기 전에 임시 디스크로 받는다. 로그인한 사용자의 반복 시도는 요청 제한으로 막는다.
- 저장이 끝난 뒤 기록에 실패하면 객체가 남는다. 같은 내용을 다시 올리면 그 객체를 쓴다. 참조가 없는 객체와 만료된 업로드를 지우는 GC는 worker에 만든다.
- 다른 사람이 올린 같은 내용의 파일과 객체를 함께 쓴다. 업로드 응답은 내용이 이미 있었는지 알려주지 않는다.
