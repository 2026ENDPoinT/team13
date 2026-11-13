# ADR-0038 아바타 사진은 격리한 converter가 다시 만든 JPEG만 내보내고, 과목을 함께하는 사람만 본다

## 상태
채택 (2026-11-12)

## 맥락
원본 내정보의 아바타 대화상자에는 "사진 선택" 탭이 있다([10_팝업_아바타설정.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/10_팝업_아바타설정.md) 5.5~5.8절). 사진을 임시로 올리고(EP-0274), 위치·확대를 조정해 canvas로 자른 PNG를 올리고(EP-0275, EP-0273), 보고(EP-0277), 초기화해 색상형으로 돌아간다(EP-0278). 서버 쪽 형식·크기 제한은 확인되지 않았고, 응답의 `imgPath`는 CSS `url('…')`에 문자열로 이어 붙는다(IDENTITY-10). 읽기 요청이 임시 이미지를 지우기도 한다(IDENTITY-13).

V1에는 `profile_images`(TEMP/ACTIVE/DELETED, 자체 `storage_key`)와 `user_profiles.profile_image_id`가 identity 영역에 있다. 모듈 규칙상 identity는 다른 모듈에 의존하지 않고 S3는 files만 만진다. 신뢰할 수 없는 문서를 해석하는 코드는 api·worker가 아니라 converter container에만 둔다([ADR-0033](0033-document-preview-and-office-conversion.md) 4번). converter에는 worker만 닿는다.

아바타는 머리글(본인), 쪽지의 보낸 사람, 강의실의 교수 표시와 최근글, 게시판 작성자, 강의계획서의 강사 소개, 카탈로그 과목 상세의 교수 표시에 나온다.

## 결정
1. **상태는 identity, 바이트는 files다.** identity가 `identity.api.ProfileImages`를 구현해 `profile_images`와 `user_profiles`의 전이(대기, 현재, 실패, 버림)를 맡는다. files의 `files.avatar`가 올리기, 검사, 재인코딩, 저장, 전달, 객체 정리를 하고 이 port로만 상태를 바꾼다. 여러 행을 바꾸는 전이는 READ COMMITTED에서 `user_profiles` 행을 먼저 잠그고 `profile_images`를 바꾼다. 색상 저장(EP-0271)과 사진 지우기(EP-0278)도 같은 순서다.
2. **자르기는 화면이 하고 서버는 사진 하나를 받는다.** 원본의 임시 업로드·위치 조정 단계는 canvas로 화면 안에서 끝난다. `POST /api/me/profile/image`는 multipart `file` 하나를 받고, 앞부분 signature가 PNG나 JPEG이고 5MB 이하일 때만 원본을 content bucket에 두고 대기(TEMP)로 넘긴다. 이름과 Content-Type은 쓰지 않는다. 앞선 대기 사진은 버린다. 202이고 결과는 `GET /api/me/profile`의 `avatar.photo`로 본다. 같은 요청을 다시 보내도 같은 사진으로 바꿀 뿐 늘어나는 객체가 없어 멱등 원장을 거치지 않는다.
3. **디코딩은 converter에서만 한다.** worker가 대기 사진을 SKIP LOCKED와 lease로 잡고, 다른 업로드와 같은 악성코드 검사기([ADR-0018](0018-malware-scan-clamd.md))를 거친 뒤 converter의 `POST /avatar`로 보낸다. converter는 signature로 고른 형식의 reader 하나로, 메타데이터를 읽지 않고, 픽셀을 풀기 전에 header의 가로·세로(한 변 4096, 16M 화소)를 본다. 가운데 정사각형을 줄여 읽어 흰 배경 위 256px로 그리고 JPEG로 다시 인코딩한다. 검사기·converter 장애는 1분 뒤 3번까지 다시 하고, 그 뒤와 lease가 끝난 처리는 `unavailable`로 닫는다. 실패 코드는 `not_image | too_large | rejected | unavailable | failed`다.
4. **원본은 내보내지 않는다.** 결과 JPEG만 현재 사진이 되고, 원본 객체와 버린·실패한 사진의 객체는 worker가 지운 뒤 `purged_at`을 남긴다(V38).
5. **보는 사람은 본인과 과목을 함께하는 사람이다.** `GET /api/users/{userId}/avatar`는 본인이거나 `catalog.api.CourseMates`가 폐강하지 않은 공통 과목을 찾을 때만 사진을 준다. 그 밖과 사진이 없는 사람은 같은 404다. 아바타가 이름과 함께 나오는 화면 대부분(쪽지, 강의실, 게시판, 강의계획서)은 과목 구성원만 보기 때문이다. 응답은 `image/jpeg` `inline`, `nosniff`, `Content-Security-Policy: default-src 'none'; sandbox`, `Cross-Origin-Resource-Policy: same-origin`, `Cache-Control: private, no-cache`와 내용 SHA-256 ETag다. 경로는 사용자마다 하나이고 304로 다시 확인하므로 사진을 바꾸면 다음 요청부터 새 사진이다.

검토하고 쓰지 않은 방법은 다음과 같다.

| 방법 | 쓰지 않은 이유 |
|---|---|
| api process에서 ImageIO로 디코딩·재인코딩 | 응답이 바로 나오지만, 신뢰할 수 없는 이미지 parser가 DB와 저장소 credential을 가진 process에서 돈다. ADR-0033 4번의 격리 원칙과 어긋난다 |
| `POST /api/uploads`의 새 용도(`avatar`)와 첨부 확정으로 처리 | 업로드 권한은 과목에 묶여 있고, 과목이 없는 용도의 업로드 권한은 다른 작업이 따로 만든다. 같은 일을 두 번 만들지 않도록 아바타는 자기 경로에 둔다 |
| 사진을 파일함·첨부처럼 `files` 행으로 두기 | 아바타는 첨부가 아니고 대기·현재·버림과 정리 규칙이 다르다. V1 설계도 `profile_images`가 자체 key를 갖게 했다 |
| 로그인한 누구에게나 사용자 ID로 공개 | 학번 순서의 ID로 전교생 사진을 모을 수 있다. 원본도 사진을 이름이 보이는 화면 안에서만 보였다 |
| 카탈로그의 교수 사진을 누구에게나 공개 | 카탈로그 과목 상세는 로그인한 누구나 본다. 교수 사진을 전교에 여는 것은 학교 정책이다. 지금 카탈로그 API는 교수 이름만 주고 화면은 이름 글자로 그린다 |
| 버전을 넣은 URL과 긴 `max-age` | 쪽지·게시판 목록은 사용자 ID만 알아 버전을 모른다. 버전을 모든 목록 응답에 넣으려면 여러 모듈이 identity를 더 읽어야 한다 |
| 확대·위치(`PROFILE_IMG_ZOOM`, `POSX`, `POSY`)를 받아 서버가 자르기 | 원본도 잘린 PNG를 올렸고 값은 표시 복원용이었다. 서버가 그 값을 해석하면 입력 검사만 늘어난다. 컬럼(`crop_*`)은 기본값으로 둔다 |

## 결과와 트레이드오프
- 올린 사진은 worker의 poll 간격과 converter 처리 시간 뒤에 보인다. converter는 한 번에 하나만 처리하므로 긴 문서 변환 뒤에 사진이 기다릴 수 있다. 그동안 앞 사진이나 색상 아바타가 보인다.
- JPEG의 EXIF 회전은 적용하지 않는다. 화면이 canvas로 그린 사진을 올리면 브라우저가 이미 회전을 반영한다.
- 투명한 PNG는 흰 배경이 된다. 원본 canvas도 흰색으로 채웠다.
- 지금 과목을 함께하지 않는 사람(예: 카탈로그에서 과목 상세를 보는 사람)에게는 사진이 보이지 않고 이름 글자 아바타가 보인다.
- 사진 하나마다 converter 호출 한 번과 객체 두 개(원본, 결과)가 생긴다. 원본은 처리 직후 지운다.
