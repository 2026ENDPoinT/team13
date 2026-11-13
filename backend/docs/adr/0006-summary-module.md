# ADR-0006 쪽별 요약은 `summary` 모듈로 옮겨 구현한다

## 상태
채택 (2026-11-11). 8번에 학습실 내 문서의 요약을 더했다(2026-11-14, [ADR-0040](0040-study-room-sync.md) 14번).

## 맥락
강의 자료(PDF)의 쪽마다 해설과 요약을 만들어 학생에게 보여 준다. 생성 흐름은 [LessonViewer](https://github.com/STack-Corp/lessonviewer)의 `server/generation.ts`에 구현되어 있고 테스트와 실제 호출로 확인돼 있다. 흐름은 추출 → 쪽 해설 → 전체 흐름(story) → 검사 → 후보 → 게시다.

LessonViewer는 호스트 서비스가 proxy로 붙이는 방식(`server/module/README.md`)을 제공한다. 이 방식을 그대로 쓰면 다음이 생긴다.

- 문서, 쪽 이미지, 해설을 container의 local disk와 SQLite에 평문으로 둔다. [ADR-0003](0003-aws-single-ec2-until-adoption.md) 6번(파일은 S3, local disk에 상태를 남기지 않음)과 어긋나고, 기밀 본문 저장소가 두 곳이 된다.
- 권한 단위가 workspace와 user 두 값이다. [architecture.md](../architecture.md) 5절의 판정(`principal × course × membership × object × action × visibility × serverTime`)은 proxy에서 요청마다 다시 계산해야 한다.
- 작업 큐가 process 메모리에 있다. 재시작하면 진행 중 작업이 끊긴다.
- PDF 추출이 PyMuPDF다. PyMuPDF는 AGPL 3.0과 상용 라이선스의 이중 라이선스다.

기존 `ai_file_summaries`는 파일 1건당 Markdown 1건이라 쪽 단위 결과를 담지 못한다.

## 결정
1. LessonViewer의 생성 흐름을 루트 앱의 새 모듈 `summary`로 옮긴다. LessonViewer container는 배포하지 않는다. LessonViewer 저장소는 흐름과 prompt의 기준 구현으로 남긴다.
2. `summary`는 `files`, `identity`의 공개 API와 `shared`에만 의존한다. `integration`이 `summary`의 port를 구현하는 것 외에는 어떤 모듈도 `summary`에 의존하지 않는다.
3. 단계와 담당은 다음과 같다.

   | 단계 | 하는 일 | 담당 |
   |---|---|---|
   | rendition | PDF를 쪽 이미지(화면용, 모델 입력용)와 쪽 텍스트로 바꾼다. 문서 미리보기([68_문서미리보기.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/68_문서미리보기.md))와 같은 결과를 쓴다 | `files`. 변환은 격리한 converter container가 한다(SC-08) |
   | pages | 연속한 쪽을 최대 6쪽씩 묶어 이미지와 텍스트를 모델에 보내고 쪽마다 `tag`, `title`, `explain`, `digest`를 받는다 | `summary` |
   | story | 쪽별 `digest`로 자료 전체의 질문과 단계별 흐름을 받는다 | `summary` |
   | validate | 스키마, 쪽 범위, 수식 표기를 검사한다 | `summary` |
   | publish | 교수자가 후보를 확인하고 게시한다 | `summary` |

4. 출력 검사에 실패하면 오류 목록을 붙여 다시 요청한다. 요청은 첫 요청을 포함해 3번까지다. 그래도 실패하면 묶음을 반으로 나눠 다시 요청하고, 한 쪽으로도 실패하면 작업을 실패로 끝낸다. 빈 값을 채워 넣어 성공으로 만들지 않는다.
5. 끝난 쪽은 checkpoint로 저장한다. key는 `sha256(prompt_version | 원본 SHA-256 | 쪽 번호 | 이미지 사용 여부)`다. 재시도는 checkpoint가 있는 쪽을 다시 요청하지 않는다. 작업마다 모델 호출 수 상한을 둔다.
6. 작업은 `summary_jobs` 원장으로 관리한다. 상태는 `queued → running → completed | failed | cancelled`이고 끝난 상태는 바뀌지 않는다. 재시도는 새 작업으로 만든다. api는 원장에 쓰기만 하고 worker가 lease를 잡아 실행한다. 큐 제품은 두지 않는다([ADR-0001](0001-modular-monolith.md) 6번). 같은 파일에 진행 중인 작업은 하나만 허용하고 두 번째 요청은 409로 거부한다.
7. lease가 만료된 작업은 `failed`(`interrupted`)로 닫는다. 자동으로 다시 실행하지 않는다. 모델 호출에 비용이 들기 때문이다.
8. 생성 시작과 게시는 교수자의 명시적 요청으로만 한다. 완료된 작업의 결과는 후보이고, 게시한 결과만 학생에게 보인다. 게시는 DB의 게시 작업 pointer를 바꾸는 한 번의 commit이다.
   - (2026-11-14 추가, 소유자 결정) 학생이 학습실에 올린 자기 PDF는 그 학생이 요약을 요청한다(ADR-0040 14번). 같은 원장, pipeline, worker, 출력 검사를 쓰고, 작업은 `summary_jobs.job_origin = 'study_room'`이며 과목이 없다(`course_id` NULL). 게시 단계가 없다. 완료된 결과는 요청한 학생에게만 게시본과 같은 모양으로 보인다. 과목 경로의 작업·후보·게시 API는 그 파일이 과목 첨부가 아니어서 열지 못한다(9번).
9. 권한은 `files`의 공개 API가 판정한 파일 권한을 그대로 따른다(CHG-06). `files`는 부모 객체의 권한을 자신이 정의한 port로 부모 모듈에 묻는다([ADR-0001](0001-modular-monolith.md) 4번). `summary`는 role만 보고 허용하지 않는다.
10. 결과 본문과 checkpoint의 저장 위치는 [ADR-0007](0007-confidential-content-placement.md), 모델 호출은 [ADR-0008](0008-llm-port-deepseek.md)을 따른다.
11. 테이블은 구현할 때 새 DB 영역 `70 summary`로 [db/README.md](../../db/README.md) 5절 절차에 따라 추가한다. `ai_file_summaries`는 바꾸지 않는다.
12. LessonViewer의 학습 대화(`search_source`, `read_page`), 필기, 실습은 이 결정의 범위가 아니다.

## 결과와 트레이드오프
- 저장소, 권한 판정, 작업 실행이 루트 앱 한 곳에 있다. container는 converter 하나만 늘어난다.
- prompt와 검사 규칙이 두 저장소에 있게 된다. LessonViewer가 바뀌면 수동으로 옮겨야 한다. 옮긴 판은 `prompt_version`으로 구분한다.
- LessonViewer는 수식을 KaTeX로 실제 컴파일해 검사한다. JVM에서는 `$` 짝, `$$` 줄 형식, 제어 문자 같은 표기 검사만 옮긴다. 컴파일되지 않는 수식은 화면에서 렌더 실패로 드러난다.
- 추출 라이브러리가 바뀌므로 쪽 이미지와 추출 텍스트가 LessonViewer와 같지 않을 수 있다. 라이브러리는 구현할 때 Apache PDFBox를 기준으로 확인한다.
- 자료 상세 화면([63_학습활동_자료상세.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/63_학습활동_자료상세.md))의 파일 단위 요약을 이 흐름의 story로 채울지는 정하지 않았다.
