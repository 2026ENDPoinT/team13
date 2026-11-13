# ADR-0033 문서 미리보기는 비동기 변환 대기열과 경로 cookie로 열고, office 문서는 converter 안의 LibreOffice로 PDF를 만든다

## 상태
채택 (2026-11-11). [ADR-0010](0010-upload-acceptance.md) 9번의 "inline 응답을 두지 않는다"에 PNG·JPEG 이미지 예외를 더한다(아래 5번).

## 맥락
원본 e-Class의 "문서미리보기"는 FPP(Base64로 인코딩한 `FILE_SEQ`, 과목 키, 학번)를 받아 학교의 Synap 뷰어로 보내고, 뷰어는 URL의 `key`, `annotToken`으로 열린다([68_문서미리보기.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/68_문서미리보기.md), EP-0125, EP-0136). URL에 학번과 뷰어 token이 남고(SEC-17), 변환기가 문서 안의 외부 link나 원격 개체를 따라갈 수 있다(FILES-12). 썸네일(EP-0118)은 `path=thumb_1.jpg`를 client가 보내고(FILES-13), 이미지 보기(EP-0130)는 inline이다(FILES-09).

이미 있는 것은 다음과 같다.

- PDF rendition(`conversion_jobs`, `doc/{key}/`, `renditions/{key}/`)과 격리한 converter([ADR-0006](0006-summary-module.md), [ADR-0019](0019-container-image-and-single-host-compose.md)). rendition은 요약 worker가 필요할 때 동기로 만들었다.
- 쪽 이미지 전달은 권한 판정 뒤 경로 prefix에만 유효한 짧은 cookie로 연다([ADR-0007](0007-confidential-content-placement.md) 6번, [ADR-0009](0009-origin-delivery-without-cdn.md)).
- 업로드 정책은 `pptx`, `docx`, `xlsx`, `hwp`, `hwpx`를 받는다. 형식은 앞부분 signature로만 확인한다([ADR-0010](0010-upload-acceptance.md)).

정할 것은 네 가지였다. 미리보기 요청이 변환을 기다릴지, office 문서를 어떤 엔진으로 PDF로 바꿀지, 엔진을 어디서 어떻게 실행할지, 썸네일과 inline 이미지를 어떻게 내보낼지.

## 결정
1. **미리보기 API는 상태를 돌려주는 명령이다.** `POST /api/courses/{courseId}/files/{fileId}/preview-sessions`는 요청마다 `FileAccess`로 읽기 권한을 판정한다. rendition이 있으면 `ready`, 쪽 수, `pageImageBase`(`/doc/{key}/`)를 돌려주고 요약 조회와 같은 path 범위 cookie를 발급한다. 없으면 `conversion_jobs`에 `requested` 행을 넣고 202 `converting`을 돌려준다. client는 같은 요청을 다시 보낸다. 변환하지 않는 형식은 `unsupported`, 변환에 실패했으면 `failed`와 코드다. 응답과 URL에 사용자 ID와 뷰어 token이 없다.
   - 대안: 요청 thread에서 변환을 기다린다. 큰 문서는 수십 초가 걸리고 api가 converter network에 붙어야 한다(ADR-0019 4번은 worker만 붙인다). 기각.
   - 대안: `preview_sessions`에 요청마다 행(`viewer_session_key`)을 남긴다. 쓰는 곳이 없다. 권한 철회는 cookie TTL(10분)로 끝나고, 행은 사용자 ID를 쌓기만 한다. 접근 기록은 감사 기록(`file.preview`)으로 남긴다. `preview_sessions`는 쓰지 않는다.
2. **변환은 대기열의 lease를 잡은 쪽 하나만 한다.** 상태는 `requested → converting → ready | failed`다. worker는 `FOR UPDATE SKIP LOCKED`로 한 행을 잡고 lease를 건다. 결과는 상태와 lease 소유자를 조건으로 한 compare-and-set이다. converter에 닿지 못한 시도는 `astra.files.conversion.retry-delay` 뒤에 다시 잡히고, lease가 끝난 시도는 다시 대기열로 간다. 둘 다 시도 수 상한(기본 3)을 넘으면 `failed`다. 같은 파일 버전의 `requested`, `converting`, `ready` 행은 DB 유일 키(`live_slot`)로 하나만 둔다. 동시에 여러 번 열어도 변환은 하나다. 요약 worker는 같은 대기열 행을 직접 잡아 바로 실행한다. 미리보기 요청으로는 실패한 변환을 다시 하지 않는다.
3. **office 엔진은 형식마다 정했다.**

   | 형식 | 엔진 | 이유 |
   |---|---|---|
   | pdf | PDFBox(기존) | |
   | pptx, docx, xlsx | LibreOffice 24.2 headless(`soffice --convert-to pdf`), MPL-2.0 | 쪽 배치를 다시 계산하는 엔진이 필요하다. 순수 Java 대안 중 Apache POI(Apache-2.0)는 pptx를 그릴 수 있지만 docx, xlsx의 쪽 배치가 없고, docx4j+FOP는 표현이 크게 다르다. 상용 엔진(Aspose, Synap)은 비용과 계약이 필요하다 |
   | hwp, hwpx | 받지 않는다(`unsupported`) | LibreOffice의 내장 filter는 HWP 97(3.x)만 읽는다. HWP 5.0과 HWPX는 H2Orestart 확장이 필요한데 GPL-3.0이고, LibreOffice의 Java 연동(JRE 버전 판별)을 켜야 한다. 이미지에 넣으면 GPL 배포 의무가 생기고, 그 조합을 이 저장소에서 실행해 보지 못했다 |
   | 그 밖(txt, zip, 이미지) | 받지 않는다(`unsupported`) | 이미지는 5번의 inline 보기로 본다 |

   형식은 저장할 때 서버가 판정한 MIME으로만 정한다. converter는 받지 않는 형식이거나 엔진이 없으면 `unsupported_format`으로 거부하고, 미리보기는 `unsupported`가 된다.
4. **엔진은 converter container 안에서만, 악성 문서를 전제로 실행한다(FILES-12).**
   - 기존 HTTP 계약(`POST /render`)에 `format` query만 더했다. 요청은 문서 바이트뿐이고 URL이나 경로를 받지 않는다. 결과는 PDF와 같은 쪽 검사(쪽 수 200, 쪽 크기)와 zip 검사를 거친다.
   - soffice는 고정한 인자 목록으로 `ProcessBuilder`에서 바로 실행한다. shell 명령 문자열을 만들지 않고, 입력 파일 이름은 `document.<확장자>`로 고정한다. 환경 변수는 비우고 `PATH`, `HOME`, `TMPDIR`, `LANG`, `SAL_USE_VCLPLUGIN`만 넣는다. 표준 출력과 오류는 버린다.
   - 작업마다 권한 700의 임시 디렉터리를 만들고 LibreOffice 사용자 profile도 새로 만든다. profile은 macro 실행(`DisableMacrosExecution`, 보안 수준 3), 신뢰하지 않는 위치의 link, OLE·DDE(`DisableActiveContent`), 불러올 때의 link 갱신(Writer, Calc)을 끈다. 끝나면 디렉터리를 지운다.
   - `prlimit`으로 주소 공간(기본 3 GiB), CPU 시간(제한 시간과 같다), 한 파일 크기(결과 상한), 열린 파일 수를 묶고 core dump를 끈다. 제한 시간(기본 120초)이 지나면 기다리는 동안 기록한 자식 process와 함께 강제로 끝낸다. container는 `init: true`로 남은 process를 거둔다.
   - 결과는 `document.pdf` 하나만 받는다. symlink가 아닌 일반 파일이고, 크기 상한(기본 100 MiB) 안이며, `%PDF-`로 시작해야 한다.
   - PDF(office 문서를 바꾼 PDF 포함)는 문서마다 heap을 묶은 자식 JVM(`-Xmx`, 기본 768 MiB, `-XX:+ExitOnOutOfMemoryError`)이 PDFBox로 그린다(B5). 문서 제한 시간(기본 300초)이 지나면 process tree를 끝내고 `timeout`이다. 쪽 하나가 자식 heap을 넘기면 그 쪽만 낮은 해상도(800px)로, 그래도 안 되면 "이 쪽은 표시할 수 없습니다" 그림과 그 쪽 텍스트로 바꾸고 나머지 쪽을 이어서 변환한다. 쪽 하나를 그리다 단계 제한 시간(기본 20초)을 넘기면 낮은 해상도를 건너뛰고 바로 그 그림과 텍스트로 바꾼다. 그리기가 오래 걸리는 쪽은 해상도를 낮춰도 거의 그대로 오래 걸리기 때문이다. 정상 쪽은 단계 하나가 2초 안에 끝나므로(B5 corpus, CPU 1개) 20초는 10배 여유이고, 악성 쪽 하나가 converter를 붙잡는 시간은 약 20초다. 대체 그림을 넣은 쪽은 쪽 JSON에 `imageUnavailable: true`를 넣고, 요약은 그 쪽을 그림 없이 텍스트로만 모델에 보낸다. 문서를 읽다가 넘기거나 자식을 다시 띄운 횟수가 6번을 넘으면 `too_large`(시간이면 `timeout`)다. 큰 그림은 그릴 크기에 맞춰 줄여 읽는다(PDFBox subsampling). converter는 자식이 모두 끝난 뒤에만 200을 보낸다.
   - converter는 외부 network가 없는 `conversion` network에만 있고 비밀값과 저장소 credential을 받지 않는다(ADR-0019 4번). 문서가 외부 주소를 가리켜도 나갈 길이 없다.
   - LibreOffice는 converter 역할의 이미지(Dockerfile target `converter`)에만 설치한다. api, worker 이미지는 그대로다. ADR-0019 1번의 "이미지 하나"에서 converter만 layer를 더한 같은 commit의 이미지로 바꾼다. LibreOffice와 글꼴이 수백 MB이고, 신뢰할 수 없는 문서를 해석하는 코드를 api, worker에 둘 이유가 없기 때문이다.
5. **썸네일과 inline 이미지.**
   - 썸네일은 worker가 변환할 때 converter가 만든 첫 쪽 이미지(긴 변 360px JPEG)를 `renditions/{key}/thumbnails/thumb_1.jpg`에 두고 `file_thumbnails` 행으로 가리킨다. 완료 기록과 썸네일 행은 같은 transaction이다. `GET /api/courses/{courseId}/files/{fileId}/thumbnail/{variant}`는 variant를 enum(`thumb_1`)으로만 해석하고, 저장할 때 정한 MIME, `nosniff`, `Cache-Control: private, max-age=300`으로 응답한다. 요청으로 썸네일을 만들지 않는다(SC-04).
   - `GET /api/courses/{courseId}/files/{fileId}/image`는 저장할 때 서버가 PNG나 JPEG로 판정한 파일만 `inline`으로 응답한다. `Content-Security-Policy: default-src 'none'; sandbox`, `nosniff`, `Cache-Control: private, no-store`를 붙인다. SVG, GIF, 판정 값이 없는 옛 파일과 그 밖의 형식은 404이고, 원본 내려받기(`attachment`)로만 받는다.
6. **파일을 지우면 미리보기 파생물도 지운다.** GC가 지운 파일의 rendition을 `expired`로 닫을 때 같은 transaction에서 썸네일 행을 지운다. 대기 중인 변환도 닫혀 worker가 잡지 않는다. 객체는 기존 prefix 삭제(`doc/{key}/`, `renditions/{key}/`)로 함께 지운다.

## 결과와 트레이드오프
- 미리보기는 요약과 같은 rendition, 같은 쪽 이미지 경로, 같은 cookie를 쓴다. 뷰어 token, FPP, 외부 뷰어 host가 없다. 화면은 쪽 이미지를 `{pageImageBase}NNN.jpg`로 차례로 불러온다.
- 첫 미리보기는 worker의 poll 간격(기본 5초)과 변환 시간만큼 기다린다. client는 `Retry-After`(2초)마다 다시 요청한다. 사용자당 10분에 300번으로 제한한다.
- 실패는 파일 버전마다 끝 상태다. converter가 고쳐져도 실패한 문서의 미리보기는 다시 만들지 않는다. 요약 생성처럼 담당자가 명시적으로 다시 요청하는 흐름만 새 변환을 만든다.
- 미리보기 응답의 실패 코드는 닫힌 집합(`PreviewService.Failure`)이다. `conversion_jobs.error_code`의 내부 값은 그대로 내보내지 않고 하나씩 바꾼다: `encrypted`, `invalid_pdf` → `invalid_document`, `empty`, `too_many_pages`(200쪽 상한), `too_large`(converter의 입력·결과 상한), `timeout`, `unavailable`·`interrupted`·`storage` → `unavailable`, 그 밖(`failed`, office 변환 실패, 모르는 값) → `failed`. `unsupported_format`은 실패가 아니라 `unsupported` 상태다.
- LibreOffice의 쪽 모양은 PowerPoint, Word, Excel과 다를 수 있다. 글꼴이 없으면 대체 글꼴(나눔, Liberation, Carlito, Caladea)로 그린다. 넓은 Excel 시트는 쪽이 많아져 200쪽 상한에 걸릴 수 있다.
- HWP, HWPX 미리보기가 없다. 학교 자료에 많다. H2Orestart를 넣으려면 GPL-3.0 배포 조건 검토와 LibreOffice Java 연동 확인이 먼저다(architecture.md 12절).
- 실행하지 않은 것: 이 저장소의 빌드 container에는 LibreOffice가 없다. 테스트는 converter의 office 엔진 자리에 가짜 엔진을 넣어 앱 흐름을, soffice 자리에 같은 인자를 받는 Java process를 넣어 인자, 환경, 임시 디렉터리, 제한 시간 강제 종료, 결과 검사를 확인했다. 실제 soffice 실행, profile의 macro·link 설정이 LibreOffice 24.2에서 적용되는지, `prlimit` 주소 공간 상한 아래에서 soffice가 뜨는지, Dockerfile target `converter`의 패키지 설치와 compose 구성, smoke test의 office 변환 단계는 실행하지 않았다. profile 설정 이름과 값은 LibreOffice 24.2의 officecfg schema에서 확인했다.
- `soffice`는 LibreOffice가 제공하는 sh launcher이고 `oosplash`, `soffice.bin`을 실행한다. 앱은 고정 인자로 그 launcher를 실행하고, launcher는 인자를 그대로 넘긴다.
- 자식 process 추적은 50ms마다 하는 기록이다. 그 사이에 생기고 사라진 손자 process는 놓칠 수 있다. 남은 process는 container의 init이 거둔다.
