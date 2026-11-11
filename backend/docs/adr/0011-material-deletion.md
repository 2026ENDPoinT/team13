# ADR-0011 강의 자료는 삭제 표시로 지우고, 파일은 참조가 끊기면 지운 뒤 worker가 저장소를 정리한다

## 상태
채택 (2026-11-11)

## 맥락
강의 자료는 등록(`POST /api/courses/{courseId}/materials`)만 있었다. 담당자가 자료를 고치고 지울 수 있어야 하고, 지운 자료와 그 파일, 쪽 이미지, 게시한 요약은 그 순간부터 학생에게 보이지 않아야 한다.

`learning_activities`는 진도(`activity_progress`), 열람 이력(`activity_view_logs`), 이어보기(`course_continue_pointers`), 학습 session(`learning_sessions`), 성적 항목(`grade_items`), 알림(`notifications`)이 FK로 가리킨다. 행을 지우면 FK가 막거나, cascade로 학습 기록이 함께 사라진다. SC-07은 학사 기록의 보존 범위를 기관 정책으로 정하라고 하고, 그 전에 임의로 지우지 말라고 한다.

파일은 `files` 하나가 원본(`file_blobs`, 같은 내용은 객체 하나), rendition(`conversion_jobs`, `doc/{key}/`, `renditions/{key}/`), 파생물(`derivatives/{fileId}/`)을 가진다. SC-05의 파일 삭제는 "참조 확인 + tombstone + 지연 물리 삭제"다. 같은 내용을 다시 올리는 업로드는 이미 있는 blob의 참조 수만 올린다(ADR-0010 5번). GC가 객체를 지우는 것과 같은 내용의 업로드가 그 객체를 다시 쓰는 것이 엇갈리면 살아 있는 파일의 원본이 사라진다.

동시에 두 사람이 같은 자료를 고치면 나중 저장이 앞 저장을 모르고 덮는다(SC-05).

## 결정
1. 활동은 행을 지우지 않고 `learning_activities.deleted_at`, `deleted_by`로 삭제 표시를 한다. 활동을 읽는 모든 조회(주차, 자료 목록, 활동 상세, `MaterialAttachmentAccess`, 강의 영상 재생·key)는 `deleted_at IS NULL`인 활동만 본다. 지운 활동은 담당자에게도 없는 활동이고 404다. 학습 기록은 그대로 남는다. 복구 API는 두지 않는다.
2. 수정과 삭제는 `If-Match`로 마지막으로 읽은 version을 보내야 한다. version은 `learning_activities.row_version`이고 응답 본문의 `version`과 `ETag`로 알린다. 서버는 활동 행을 `FOR UPDATE`로 잠근 뒤 비교하고, 다르면 아무것도 바꾸지 않고 409(`version_conflict`)로 거부한다. `If-Match`가 없으면 428이다.
   - 본문 필드 대신 header를 쓴 이유: 본문이 없는 DELETE에도 같은 방식으로 걸 수 있고, 수정 본문은 "수정 뒤 전체 상태"로만 남는다. SC-05의 멱등 fingerprint가 `If-Match`를 precondition으로 포함하는 것과도 맞다.
   - `updated_at`(ms 단위 시각)을 쓰지 않은 이유: 같은 ms 안의 두 수정과 값이 바뀌지 않은 UPDATE를 구분하지 못한다.
   - 412가 아니라 409인 이유: 서버 계약이 lost update를 version 충돌(409)로 정했다.
3. 수정 본문의 첨부는 최종 집합 `attachments: {keep: [fileId], add: [uploadId]}`다(SC-05-B). keep은 지금 이 자료에 첨부된 파일만, add는 요청한 사람이 같은 과목·같은 용도로 올린 검사 통과 업로드만 받는다. `files.api.Attachments.change`가 같은 transaction 안에서 집합을 바꾼다. 하나라도 쓸 수 없으면 활동과 첨부 모두 바뀌지 않는다(409 `attachment_not_ready`).
4. 첨부가 끊겨 파일을 가리키는 첨부가 하나도 남지 않으면 그 파일은 `files.state = 'deleted'`가 되고 blob 참조 수가 하나 준다. 그 파일을 만든 업로드는 `deleted`가 된다. 자료를 지우면 `Attachments.release`로 그룹의 첨부를 모두 끊는다. `FileAccess`, 원본 내려받기, 요약 조회, rendition 전달(`DeliveryObjects`), 파생물 쓰기는 모두 파일 상태를 보므로 같은 commit부터 막힌다.
5. 저장소 객체는 worker의 GC(`files.StorageGc`)가 나중에 지운다. 한 번에 정해진 수만큼 처리하고, 상태 전이는 현재 상태를 조건으로 건 UPDATE라 여러 번, 두 worker가 돌려도 한 번만 반영된다. S3 호출은 DB transaction 밖에서 한다.
   - 만료된 미첨부 업로드: `expired`로 바꾸고 파일을 지운다(`FOR UPDATE SKIP LOCKED`로 첨부 확정과 엇갈리지 않는다).
   - 지운 파일: 유예 시간(`astra.files.gc.derivative-grace`)이 지나면 rendition 행을 먼저 `expired`로 닫고, `derivatives/{fileId}/`, `renditions/{key}/`, `doc/{key}/`를 지운 뒤 `files.derivatives_purged_at`을 쓴다. 닫은 뒤 끝난 변환은 완료로 바뀌지 못하고 자기가 쓴 객체를 지운다.
   - 참조가 0인 blob: 유예 시간(`astra.files.gc.blob-grace`)이 지나면 행을 잠그고 먼저 `purged_at`을 쓴 뒤 commit하고 객체를 지운다. 같은 내용의 업로드는 `UploadRecords.referenceBlob`에서 같은 행을 잠그고 판정한다. 지운 것으로 표시된 blob은 그 요청이 새 key(`blobs/{sha 앞 2자}/{sha}-{임의 값}`)에 저장한 내용으로만 되살리고, 옛 key는 다시 쓰지 않는다. 저장하지 않기로 했던 요청은 새 key에 저장하고 한 번 더 판정한다.
6. 실행 중인 요약 작업은 모델 호출 앞뒤마다 파일 상태도 확인한다. 파일이 지워졌으면 결과를 쓰지 않고 `failed`(`file`)로 닫는다. 지운 파일의 작업은 게시하거나 다시 실행할 수 없다.
7. 게시한 요약은 `file_id`에 묶인다. 같은 내용을 다시 올려도 새 파일 ID가 생기므로 지운 파일의 요약, rendition, checkpoint가 되살아나지 않는다.

## 결과와 트레이드오프
- 학습 기록과 감사에 필요한 활동 행이 남는다. 대신 `learning_activities`를 읽는 새 조회는 모두 `deleted_at IS NULL`을 넣어야 한다. 빠뜨리면 지운 자료가 보인다. 리뷰와 테스트로 막는다.
- 지운 자료를 되살리는 기능은 없다. 파일이 이미 지워졌기 때문이다. 필요해지면 휴지통 기간을 두고 파일 삭제를 그 뒤로 미룬다.
- CloudFront가 직접 읽는 `doc/{key}/`는 이미 발급한 signed cookie가 끝나기 전까지, GC가 객체를 지우기 전이면 읽힐 수 있다. 새 cookie는 발급되지 않고, CDN 없는 전달(ADR-0009)은 파일 상태를 보고 바로 막는다. 노출 상한은 문서 cookie TTL과 파생물 유예 시간 중 긴 쪽이다.
- blob을 지운 것으로 표시한 뒤 S3 삭제가 실패하면 객체가 남는다. DB에서는 닿을 수 없는 객체다. 오류 로그로 알리고 운영자가 지운다.
- GC를 ShedLock으로 묶지 않았다. 두 worker가 같은 지운 파일의 prefix를 함께 지울 수 있지만 결과는 같다.
- 수정마다 version이 오르므로, 열어 둔 화면에서 저장하면 409를 받고 다시 불러와야 한다.
