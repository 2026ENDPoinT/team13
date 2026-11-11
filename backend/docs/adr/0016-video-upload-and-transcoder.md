# ADR-0016 강의 영상은 본문 스트림으로 받고, 격리한 transcoder가 HLS로 나눈 뒤 files가 암호화해 게시한다

## 상태
채택 (2026-11-11)

## 맥락
[ADR-0007](0007-confidential-content-placement.md)은 영상을 HLS AES-128로 암호화해 `delivery` bucket의 `vod/`에 두고, key는 요청마다 권한을 판정하는 API로만 내보내도록 정했다. 재생 허가(`POST .../playback`)와 key API(`GET .../hls-key`), 앱 직접 전달([ADR-0009](0009-origin-delivery-without-cdn.md))은 이미 있다. 빠진 것은 영상을 받는 경로, 변환, 변환 결과를 콘텐츠에 잇는 일이다.

- 기존 업로드(`POST /api/uploads`, [ADR-0010](0010-upload-acceptance.md))는 multipart다. 서블릿 컨테이너가 본문 전체를 임시 디스크에 받은 뒤에야 권한을 판정하고, 상한이 50 MB다. 강의 영상은 수백 MB에서 수 GB다.
- [architecture.md](../architecture.md) 7.1절은 MediaConvert를 전제로 했다. AWS 계정이 없어 MediaConvert 연동은 지금 검증할 수 없다.
- 영상은 신뢰할 수 없는 입력이다. 디코더는 공격면이 넓고, 컨테이너 형식 중에는 다른 파일이나 URL을 참조하는 것(HLS, concat, mov data reference)이 있다(SC-08).

## 결정
1. **업로드 전송**: 영상은 `POST /api/uploads/stream?courseId&purpose&name`으로 받는다. 본문은 파일 바이트 그대로(`application/octet-stream`)이고 `Content-Length`가 있어야 한다. 요청 제한, 용도와 과목 권한, 멱등 key, 이름과 확장자, 선언한 크기와 정책 상한(`upload_policies.max_file_bytes`, `lecture_video`는 2 GiB)을 본문을 읽기 전에 판정한다. 거부하면 본문을 읽지 않는다(401, 403, 404, 400, 411, 413, 422).
2. 판정을 통과하면 앞 1024 byte로 컨테이너 signature를 확인한다(mp4·mov는 `ftyp` box와 brand, webm·mkv는 EBML DocType). 통과한 뒤에야 S3 multipart upload를 시작하고, 8 MB part 하나씩만 메모리에 두며 SHA-256을 함께 계산한다. 받은 크기가 선언과 다르거나 중간에 실패하면 multipart upload를 취소한다. api process가 도중에 멈춰 남은 part는 worker가 24시간 뒤 취소한다.
3. 기록은 multipart 업로드와 같다. `file_blobs`, `files`, `upload_sessions`를 같은 상태로 만들고, 첨부는 `Attachments.attach`로 확정한다. 내용 hash는 다 받은 뒤에야 알 수 있어 원본은 `blobs/stream/{임의 값}`에 둔다. 같은 내용의 blob이 이미 있으면 그 blob을 쓰고 방금 올린 객체는 지운다. 영상 용도는 multipart 업로드에서 받지 않는다.
4. **영상 asset과 작업 원장**: 담당자가 영상 활동(`lecture_weeks`)을 등록하면 콘텐츠마다 업로드 하나를 첨부 그룹으로 확정하고, 같은 transaction에서 `files`의 `VideoAssets.register`가 asset(`video_assets`)과 변환 작업(`video_transcode_jobs`)을 만든다. asset key는 임의의 32자 hex다. 작업은 `queued → running → succeeded | failed | cancelled`이고, worker가 `FOR UPDATE SKIP LOCKED`로 잡아 lease를 갱신하며 실행한다. asset당 진행 중 작업은 하나다.
5. 일시 오류(transcoder 연결 실패, 응답 손상, 내부 오류, lease 만료)는 1분에서 시작해 4배씩 늘리는 대기 뒤 다시 실행하고, 실행 3번째에도 실패하면 `failed`로 닫는다. 잘못된 입력(읽을 수 없는 영상, 길이·크기 초과, 시간 초과)은 바로 `failed`다. 실패 사유는 원장에만 남고 학생 응답에는 상태(`processing | ready | failed`)만 나간다.
6. **transcoder 경계**: 변환은 문서 converter와 같은 방식의 별도 process(`com.stackcorp.transcoder.TranscoderServer`, 같은 jar의 다른 main)가 한다. network와 AWS credential이 없는 container에서 실행하고 worker만 부른다. 입력은 요청 본문으로, 결과는 응답 zip(재생 목록과 MPEG-TS segment)으로만 오간다. 요청마다 소유자만 접근하는 작업 directory를 만들고 응답 뒤 지운다.
7. ffmpeg는 고정 인자 목록으로 shell 없이 실행한다. demuxer를 서버가 판정한 컨테이너로 고정하고(`-f mov`·`-f matroska`), `-protocol_whitelist file`, mov의 `-enable_drefs 0 -use_absolute_path 0`으로 다른 파일과 network 참조를 막는다. 첫 영상·음성 stream만 쓰고 자막, data, attachment stream과 metadata는 버린다. 환경 변수는 PATH만 넘기고, thread 수, 재생 길이(4시간), 실행 시간(2시간), 원본(2 GiB)과 결과(4 GiB) 크기를 제한한다. 출력은 720p 이하 H.264/AAC 한 화질, 6초 segment다.
8. **암호화는 files가 한다**: transcoder는 암호화하지 않은 segment를 돌려주고, key는 transcoder에 가지 않는다. worker가 `VideoKeys.ensure`로 asset key(16 byte)를 받아 segment마다 새 IV로 AES-128-CBC 암호화해 `vod/{assetKey}/seg_NNNNN.ts`에 쓰고, 마지막에 `index.m3u8`을 쓴다. 재생 목록은 transcoder 출력을 그대로 쓰지 않고 segment 길이만 읽어 다시 만들며, 각 segment 앞의 `EXT-X-KEY`에 key URI `/api/courses/{courseId}/lecture-items/{itemId}/hls-key`와 IV를 적는다. key는 로그와 디스크에 남기지 않는다.
9. 게시가 끝나면 작업 성공, asset `ready`, 부모 모듈 알림을 한 transaction으로 commit한다. `files`는 learning 테이블을 읽지 않으므로 learning이 구현하는 port `VideoAssetListener`로 알리고, learning이 `online_lecture_items.content_path`, `duration_sec`을 채운다. `content_path`가 있는 콘텐츠만 재생 허가와 key API가 응답한다. 실패나 취소면 `vod/{assetKey}/`를 비우고 `content_path`는 채우지 않는다.
10. **취소**: 원본 파일이 삭제되면 worker가 asset을 `removed`로 바꾸고, 대기 작업을 취소하고, 실행 중 작업에 취소를 요청하고, `content_path`를 비운다. 실행 중 작업이 끝난 뒤 `vod/{assetKey}/`와 key를 지운다. 실행 중 작업은 segment 사이마다 취소 요청을 확인하고, 게시 commit은 원본이 남아 있을 때만 성공한다.
11. **MediaConvert를 붙일 때**: port `VideoTranscoder`는 "원본 stream → 암호화하지 않은 segment"의 동기 계약이다. MediaConvert는 S3 원본을 직접 읽고 암호화한 결과를 S3에 직접 쓰고, 완료를 EventBridge·SQS로 알린다. 붙이려면 (a) 원본의 bucket·key와 출력 prefix를 넘기는 비동기 port(작업 제출과 완료 처리)를 `files`에 더하고, (b) 작업 원장에 외부 작업 ID와 `waiting` 상태를 더하고, (c) key는 static key provider로 넘기고 key URI와 IV 규칙을 이 결정과 맞추고, (d) worker role에 `mediaconvert:CreateJob`과 `iam:PassRole`, MediaConvert role에 `content` 읽기와 `delivery` 쓰기, SQS 수신 권한을 주고, (e) 작업 설정에 key가 들어가므로 작업 조회 권한을 worker role로 제한한다(ADR-0007 결과). 그 전까지 같은 EC2의 transcoder container가 변환한다.

## 결과와 트레이드오프
- 권한이 없거나 너무 큰 요청은 본문을 받기 전에 끝난다. 업로드 하나가 쓰는 메모리는 part 하나(8 MB)이고 api 디스크를 쓰지 않는다. WAF의 경로별 본문 상한도 `/api/uploads/stream`에 맞춰야 한다.
- 원본이 content-addressed key(`blobs/{sha256}`)가 아니어서 같은 내용을 동시에 올리면 잠깐 객체가 둘 생긴다. 기록 뒤 참조되지 않은 쪽을 지운다.
- 변환이 api·worker와 같은 EC2의 CPU를 쓴다. 영상 하나에 수 분이 걸리고 transcoder는 한 번에 하나만 변환한다. container의 CPU·메모리 상한은 compose에서 정한다. 동시 변환이 부족해지면 transcoder 인스턴스를 늘리거나 MediaConvert로 옮긴다.
- 화질은 720p 하나다. 멀티비트레이트, 썸네일, 자막은 하지 않는다.
- worker가 segment를 메모리에서 암호화하므로 평문 segment가 worker를 지난다. worker는 이미 원본을 읽는 주체라 노출 범위가 넓어지지 않는다. 반대로 hostile 입력을 다루는 transcoder는 key를 보지 못한다.
- segment마다 IV를 새로 만들어 같은 key로 같은 IV를 다시 쓰지 않는다. HLS AES-128은 DRM이 아니다(ADR-0007).
- 영상 원본의 내려받기 경로(`FileAccess`)는 열지 않는다. 학생이 암호화하지 않은 원본을 받지 못하게 하기 위해서다. 담당자의 원본 내려받기가 필요하면 learning이 콘텐츠 첨부 그룹의 `AttachmentParentAccess`를 담당자에게만 열도록 더한다.
