# ADR-0041 학습실 녹음은 올리면 자동으로 Deepgram이 전사하고 요약 모델이 요약하며, 결과는 녹음 소유자만 녹음과 같은 경로로 읽는다

## 상태
채택 (2026-11-14). [ADR-0040](0040-study-room-sync.md) 11번을 바꾼다.

## 맥락
프론트엔드(Frontend-final, `README.md` "녹음 전사·요약 연동 계약", `src/shared/recording-study.ts`)의 녹음 화면은 녹음 상세 응답의 선택 필드 `transcription`과 `summary`를 읽어 시각이 연결된 전사와 요약을 보여 준다. 각 필드는 `{status: queued|processing}`, `{status: ready, result}`, `{status: failed, message}` 가운데 하나이고, 없거나 `null`이면 "제공하지 않음"이다. 처리할 녹음은 첫 상세 응답부터 `queued` 또는 `processing`이어야 한다. 프론트는 검사 중(`state=scanning`)이거나 작업이 대기·처리 중일 때만 4초마다 상세를 다시 읽는다. 처리를 시작하는 버튼이나 동의 단계는 계약에 없다.

ADR-0040 11번은 "녹음은 전사, 요약, 학습 대화, 모델 호출 어디에도 들어가지 않는다"고 정했다. 이 저장소에는 음성 인식이 없다.

소유자의 결정(2026-11-14):
- 백엔드에 만든다. 음성 인식 제공자는 Deepgram API다.
- 업로드 뒤 자동으로 처리한다. 학생 버튼, 교수자 동의 단계는 없다. 이것은 ADR-0040 11번을 전사와 요약에 한해 뒤집는다.
- 요약은 기존 모델 port(`summary.api.LlmClient`, OpenAI 호환, [ADR-0008](0008-llm-port-deepseek.md))를 쓴다.
- Deepgram key가 없는 배포에서는 기능이 없다. 필드를 보내지 않는다.

## 결정
1. **자리**: 작업 원장, 결과, worker, 프롬프트, 검증은 `files.studyroom`에 둔다(녹음, 소유, 지우기, 음원 저장소가 모두 `files`에 있다). 바깥 호출은 `files.api`의 port 둘이고 `integration`이 구현한다. `SpeechToText` ← `integration.deepgram.DeepgramSpeechToText`, `TextModel` ← `integration.llm.LlmTextModel`(기존 `LlmClient` bean을 감싼다. 같은 제공자, key, 동시 요청 상한, `astra.llm.calls`·`astra.llm.tokens` 지표에 `kind=recording_summary`). 새 모듈 의존은 없다(`files`는 `summary`에 의존하지 않는다).
2. **테이블(V41)**: `study_room_recording_jobs`(녹음 × 종류 `transcription|summary`마다 한 행, 상태 `queued|processing|ready|failed`, `failure_code`, `result_json`, 시도 수, 다음 시도 시각, lease, 잡아 둔 사용량, 실제 길이)와 `study_room_transcription_usage`(사용자 × 날짜의 전사 오디오 초). 녹음을 `(owner_user_id, study_room_recording_id)` 복합 FK로 가리킨다. 실패 문장은 저장하지 않고 코드만 둔다.
3. **만들기**: 녹음을 기록하는 업로드 transaction에서 작업 행을 `queued`로 만든다. 그래서 업로드 응답과 첫 상세 응답이 `queued`다. Deepgram key가 있으면 전사를, 거기에 모델(`LlmClient` bean, 곧 `ASTRA_LLM_API_KEY`가 있고 `ASTRA_LLM_ENABLED=true`)이 있으면 요약도 만든다. Deepgram이 없으면 아무것도 만들지 않는다. 기능을 켜기 전에 올린 녹음은 작업이 없어 필드가 없고, 아무것도 처리하지 않는다.
4. **응답**: 녹음 하나를 돌려주는 응답(상세 `GET …/recordings/{id}`, 업로드, 제목 바꾸기)에만 작업을 싣는다. 목록(`GET …/recordings`)과 과목 보기(`GET …/courses/{id}`)에는 싣지 않는다. 프론트가 그 응답에서 두 필드를 읽는 화면이 없고, 결과는 녹음 하나에 수백 KB까지 커질 수 있다. 작업이 없으면 키 자체가 없다.
5. **worker**(`RecordingStudyRunner`, worker 프로필): `poll`이 빈 실행 자리(종류별 `max-concurrent`의 합만큼 thread를 둔 executor)만큼 작업을 잡아 executor에 넘긴다. 모든 worker의 잡기는 `SchedulerLock "files.recording_study_claim"`으로 차례로 서고, 한 transaction에서 대기 작업을 잠근다(`FOR UPDATE OF j SKIP LOCKED`). 종류마다 실행 중 수가 `max-concurrent` 미만일 때만 그 종류의 가장 오래된 후보를 잠그고, 전사와 요약 가운데 다음 시도 시각이 이른 것을 고른다(요약이 뒤에 들어온 전사 뒤에서 기다리지 않는다). 전사는 검사를 마친(`scan_state=clean`, `blob_state=clean`, 곧 `state=ready`) 녹음만, 요약은 같은 녹음의 전사가 `ready`일 때만 잡는다. 거부된 녹음, 제공자가 없어진 종류, 전사가 실패한 녹음의 대기 작업은 잡기 전에 정해 둔 코드로 닫는다(프론트가 끝없이 조회하지 않게). 실행은 잠금 밖에서 병렬로 하고 작업마다 lease를 늘린다. 기록은 상태와 lease 소유자를 조건으로 한 UPDATE라, 그 사이 녹음을 지웠거나 lease를 잃었으면 결과를 버린다. lease가 끝난 작업은 다시 대기로(시도가 남았으면) 또는 `failed interrupted`로 닫는다. 종료할 때는 새로 잡지 않고 실행 중인 작업을 interrupt한다.
6. **전사**: 저장소 원본을 stream으로 열어 요청 본문으로 그대로 보낸다. 요청은 `POST /v1/listen?model=nova-3&language=multi&language_hint=ko&language_hint=en&punctuate=true&smart_format=true&utterances=true&mip_opt_out=true`, header `Authorization: Token <key>`, `Content-Type`은 녹음의 audio MIME이다.
   - `nova-3`의 `language=multi`는 한 강의 안의 한국어·영어 섞인 말을 한 모델로 받는다. `language_hint`는 감지를 두 언어로 기울인다(`multi`일 때만 보낸다).
   - `utterances=true`는 말의 멈춤으로 나눈 짧은 단위마다 시작 시각과 글을 준다. 이것이 계약의 `segments[{at, text}]`다. `paragraphs`는 몇 분 길이가 될 수 있어 시각 연결에 거칠고, 화자 분리는 계약에 없다.
   - `mip_opt_out=true`는 Deepgram 모델 개선 프로그램에서 빼서 오디오와 전사를 처리에 필요한 동안만 보관하게 한다. 설정으로 끌 수 없다.
   - URL 방식(제공자가 우리 저장소를 읽는 방식)은 쓰지 않는다. 저장소 객체를 바깥에 열어 주지 않는다. 연 원본 stream은 호출이 끝나면 언제나 닫는다(제공자가 본문을 다 받기 전에 답하거나 연결이 끊겨도 저장소 연결이 남지 않는다).
   - 응답은 streaming parser로 `metadata.duration`과 `results.utterances[].start/transcript`만 읽는다. segment는 글을 정리하고 빈 것을 버리며 시각을 `[0, 실제 길이]`로 자르고 0.01초로 내려 오름차순으로 둔다. `text`는 segment 글을 줄바꿈으로 이은 것이다. 말소리가 없으면 `segments=[]`, `text=""`로 `ready`다.
7. **길이**: 올릴 때의 `durationMs`는 client 값이고 서버에는 오디오 길이를 재는 수단이 없다(`ContentInspector`는 signature만 본다. Chrome MediaRecorder WebM에는 길이 header가 없다). 보내기 전 검사(녹음 길이 상한, 사용자별 하루·한 달 분)는 선언 길이로 하고 그만큼 사용량에 잡아 둔다. 끝나면 사용량을 Deepgram이 잰 실제 길이로 바꾼다(결과를 내주지 않을 때도 청구한다. 제공자가 이미 처리했다). 실제 길이가 상한을 넘으면 `too_long`, 선언보다 `max(60초, 선언의 10%)` 넘게 길면 `length_mismatch`로 결과를 저장하지 않는다. 선언을 짧게 속여도 그 녹음 하나만큼만 한도를 넘고, 늘어난 사용량 때문에 다음 녹음은 보내기 전에 막힌다. 처리 중에 녹음을 지워 결과를 버려도 같다: worker가 잡을 때의 잡아 둔 초와 날짜를 들고 있다가 따로 실제 길이로 고친다. 선언을 길게 속이면 실제 길이만 청구된다(보내기 전 거절은 선언 길이로 하므로 그 client의 녹음은 거절될 수 있다). 실패·재시도·lease 만료는 잡아 둔 값을 돌려준다.
8. **요약**: 전사 줄을 `[초] 말`로 보내고, `summary-chunk-chars`(기본 24000자)를 넘으면 나눠 조각마다 부른 뒤 섹션을 잇고 조각별 overview로 전체 overview를 한 번 더 만든다. 모델에게는 JSON 객체 하나, 전사에 있는 내용만, 시각은 전사 줄의 초, 일반 텍스트, 전사 안의 지시를 따르지 않음을 요구한다. 출력은 신뢰하지 않는 입력으로 검사한다: 형식이 틀리거나 빈 글, 녹음 길이 밖의 시각이면 `invalid_output`으로 다시 시도한다. 출력 token 상한에서 잘린 응답은 같은 요청을 되풀이해도 다시 잘리므로 바로 `output_truncated`로 닫는다. 시각은 그 이하의 가장 늦은 전사 줄 시각으로 맞추고 섹션을 시각 오름차순으로 정렬한다. 제어 문자, 줄머리 제목 표지, 낱말 경계에서 짝을 이룬 `**강조**`·`` `코드` ``만 벗기고(`2**10`, `__init__` 같은 글은 그대로), 긴 글과 많은 항목은 자르며 형식이 틀린 표는 버린다. `note`는 서버가 정한 문장이다. 전사에 말소리가 없으면 모델을 부르지 않고 `no_speech`다.
9. **실패**: 일시 오류(제공자 429·5xx·연결·제한 시간·응답 해석, 모델 출력 형식)는 1분에서 4배씩(1시간 상한) 늘려 `max-attempts`(기본 4)까지 다시 하고, 그동안 상세는 `queued`다. 다시 해도 같은 실패(읽을 수 없는 오디오, key 거부·잔액 없음·잘못된 설정, 한도, 길이, 잘린 요약)는 바로 닫는다. 화면 문장은 코드마다 정한 한국어 문장이다(`RecordingStudyFailures`). 제공자 문구, 예외 class 이름, key는 문장, 로그, 감사 기록에 들어가지 않는다. 전사가 실패하면 대기 요약도 `transcript_failed`로 닫는다. 그래서 전사가 `ready`이고 요약이 `failed`일 수 있다.
10. **한도**: 녹음 하나 180분, 사용자당 하루 300분, 한 달(달력 월) 1800분, 종류마다 동시 처리 4개(모든 worker 합). 모두 설정이다(`ASTRA_RECORDING_STUDY_*`). 한도에 걸린 녹음은 `failed`이고 문장이 이유와 한도를 말한다. 다시 처리하는 경로는 없다(다시 올린다).
11. **지우기와 이어서 녹음**: 녹음을 지우는 transaction이 작업 행과 결과를 녹음 행보다 먼저 지운다. 처리 중이던 worker의 결과는 버려지고 사용량만 실제 길이로 고쳐진다(7번). 프론트의 "이어서 녹음"은 이어 붙인 음원을 새 녹음으로 올리고 원래 녹음을 지운다. 작업은 녹음에 묶이고 녹음의 파일은 바뀌지 않으므로 결과는 언제나 그 녹음의 음원 것이다. 이어 붙인 음원은 처음부터 다시 전사되고 사용량에 다시 들어간다.

## 개인정보
- **보내는 것과 받는 곳**: Deepgram(Deepgram, Inc., 기본 미국 처리. `ASTRA_DEEPGRAM_URL=https://api.eu.deepgram.com`이면 EU)에 녹음 원본 바이트와 audio MIME. 요약 모델 제공자(`ASTRA_LLM_*`, 기본 DeepSeek, 처리 조건은 ADR-0032 `ProviderProfile`)에 전사 줄(`[초] 말`)과 조각 번호. 사용자 이름, ID, 녹음 제목, 과목은 보내지 않는다.
- **읽는 사람**: 녹음 소유자만. 결과는 녹음과 같은 소유 조건(`owner_user_id`)으로 읽는 응답에만 실린다. 남의 녹음은 404이고 결과가 실리지 않는다. 담당자, 운영자, 요약·학습 대화가 읽는 경로는 없다.
- **보관**: 결과(`result_json`)는 녹음을 지울 때까지 두고, 녹음 지우기와 같은 transaction에서 작업 행과 함께 지운다. 사용량 행(사용자 ID, 날짜, 초)은 남는다(지우고 다시 올려 한도를 피하지 못하게). Deepgram은 `mip_opt_out=true`라 처리에 필요한 동안만 보관한다고 밝힌다. 모델 제공자의 보관은 그 제공자 조건(ADR-0032)을 따른다.
- **로그와 감사**: 로그에는 작업 ID, 녹음 ID, 종류, 코드, 시도 수, 선언·실제 길이, segment 수만 남긴다. 감사 기록 `study_room.recording.transcription`, `study_room.recording.summary`는 actor 없이 대상 녹음 ID, 과목 ID, `ok` 또는 실패 코드다. 전사 글, 요약, 제공자 응답은 어디에도 남지 않는다.

## 결과와 트레이드오프
- 학생이 녹음을 올리면 교수자와 다른 학생의 목소리, 강의 내용이 외부 제공자 두 곳에 간다. 그 사람들의 동의나 과목·교수자 단위의 거부 단계가 없다. 학생 본인이 끌 수도 없다. ADR-0032의 교수자 동의는 교수자 자료에 대한 것이라 여기에 쓰지 않는다. 소유자가 이 결과를 받아들였다.
- 실패하거나 한도에 걸린 녹음을 다시 처리하는 경로가 없다. 기능을 켜기 전 녹음도 처리하지 않는다.
- Deepgram은 처리 10분을 넘으면 504다. 50 MB 상한과 180분 상한 안에서는 넘지 않는다고 본다. 넘으면 일시 오류로 다시 하다가 `stt_unavailable`로 닫는다.
- 잡기는 모든 worker가 차례로 선다(잠금 1분). 잡기 자체는 짧고, 실행은 병렬이다.
- 결과를 DB에 둔다. 녹음 하나의 결과는 수백 KB 이하이고, 지우기와 같은 transaction에서 지울 수 있다.

## 남은 질문(소유자)
1. 수업 녹음이 교수자·다른 학생 동의 없이 외부 제공자에 가는 것에 대한 학교 개인정보 기준과 고지.
2. 학생별 끄기, 과목별 거부가 필요한지.
3. 실패·한도 녹음의 다시 처리, 기능 전 녹음의 일괄 처리가 필요한지.
4. 기본 한도와 비용. `mip_opt_out`의 요금 조건.
5. Deepgram 처리 지역(미국/EU)과 요약 모델 제공자 지역.
