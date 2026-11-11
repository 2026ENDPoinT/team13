# ADR-0007 기밀 본문의 배치와 암호화

## 상태
채택 (2026-11-11)

## 맥락
강의 영상, 강의 자료 원본, 쪽 이미지와 추출 텍스트, 생성한 요약은 교수자의 저작물이고 수강 권한이 있는 사용자만 볼 수 있어야 한다. 이 문서의 대상은 이 네 가지와 그 파생물이다. 게시글, 쪽지, 제출물 본문은 다루지 않는다.

서버는 변환과 요약을 위해 평문을 읽어야 한다. 브라우저만 복호화할 수 있는 end-to-end 암호화는 성립하지 않는다.

[ADR-0003](0003-aws-single-ec2-until-adoption.md)은 앱이 S3 API, 컨테이너, MySQL 표준만 쓰고 AWS 전용 구성은 Terraform에만 두도록 정했다. [architecture.md](../architecture.md) 7절은 DRM을 두지 않고 필요해지면 HLS AES-128부터 적용한다고 적었다.

## 결정
1. 대상 본문은 object storage에만 둔다. MySQL에는 상태, 메타데이터, object key, hash만 둔다. 요약 본문과 추출 텍스트를 컬럼에 저장하지 않는다.
2. bucket을 용도로 나눈다.

   | bucket | 들어가는 것 | 읽을 수 있는 주체 |
   |---|---|---|
   | `content` | 업로드 원본, 모델 입력용 쪽 이미지와 쪽 텍스트, 요약 결과와 checkpoint, 영상 암호화 key | api, worker |
   | `delivery` | HLS manifest와 segment(`vod/`), 쪽 이미지(`doc/`) | api, worker, CloudFront(OAC) |
   | `frontend` | 정적 빌드 | CloudFront(OAC) |

   CloudFront가 읽는 bucket에는 전달용 파생물만 있다.
3. `content`와 `delivery`는 public access를 막고 SSE-KMS로 암호화한다. customer managed key를 bucket마다 따로 둔다. CloudFront service principal은 `delivery`의 key만 쓸 수 있다. TLS가 아닌 요청은 bucket policy로 거부한다.
4. RDS storage와 EBS volume을 암호화한다. snapshot과 backup도 같은 암호화를 따른다.
5. S3를 읽고 쓰는 모듈은 `files` 하나다. 다른 모듈은 `files`의 공개 API로 파일의 파생물을 저장하고 읽는다. 파일을 삭제하면 `files`가 원본, rendition, 요약, HLS, key를 함께 삭제 대상에 넣는다.
6. 쪽 이미지는 `/doc/{renditionKey}/*` 경로로 전달한다. 백엔드가 권한을 판정한 뒤 그 경로에만 유효한 짧은 TTL의 CloudFront signed cookie를 발급한다. `renditionKey`는 추측할 수 없는 값이다.
7. 요약 본문은 `/api/*` 응답으로만 전달하고 `Cache-Control: no-store`를 붙인다. CDN에 캐시하지 않는다.
8. 영상은 HLS AES-128로 암호화한다.
   - 영상 asset마다 임의의 16 byte key를 만들어 `content` bucket에 저장하고 MediaConvert static key로 넘긴다.
   - manifest의 key URI는 백엔드 API다. 이 API는 요청마다 권한을 판정하고 `no-store`로 응답한다. 같은 도메인이므로 player의 요청에 session 쿠키가 실린다.
   - signed cookie 발급은 [architecture.md](../architecture.md) 7절을 그대로 따른다.
9. 로그, trace, 오류 응답에 본문, prompt, 모델 출력, 영상 key를 남기지 않는다. id, hash, 쪽 번호, token 수, 오류 code만 남긴다(SC-07).
10. 앱이 직접 암호화하는 envelope encryption은 두지 않는다. 클라우드 운영자를 위협 모델에 넣어야 하는 요구가 생기면 `shared`에 key wrap port를 정의하는 안으로 다시 결정한다.

## 결과와 트레이드오프
- DB dump, SQL injection, 운영자의 DB 조회로는 대상 본문이 나오지 않는다.
- bucket이 잘못 공개되거나 disk, snapshot이 유출돼도 KMS key 권한 없이는 읽을 수 없다.
- signed cookie가 유출되거나 edge cache에 segment가 남아도 영상은 key 없이 재생되지 않는다. key는 권한 판정을 통과한 요청에만 나간다.
- HLS AES-128은 DRM이 아니다. 권한이 있는 사용자는 key를 받아 영상을 복호화할 수 있다. 화면 녹화도 막지 못한다.
- 영상 key가 MediaConvert 작업 설정에 들어간다. MediaConvert 작업 조회 권한은 worker role로 제한한다.
- 지금 적용하지 않고 나중에 켜면 이미 변환한 영상을 모두 다시 변환해야 한다.
- api, worker의 role이 탈취되면 복호화할 수 있다. 모델 제공자에게는 평문이 전달된다([ADR-0008](0008-llm-port-deepseek.md)).
- 요약을 조회할 때마다 object를 읽는다. 요약 본문은 SQL로 검색할 수 없다.
- 앱은 S3 API만 쓴다. SSE-KMS와 key policy는 Terraform에만 있어 ADR-0003의 이식성 조건을 유지한다.
- CloudFront와 EC2 사이 구간의 TLS는 이 문서에서 정하지 않는다. 이 구간은 VPC origin의 private 경로다. [architecture.md](../architecture.md) 3.2절에서 ALB로 전환할 때 listener의 TLS로 정한다.
