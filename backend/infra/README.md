# 인프라 (Terraform)

[docs/architecture.md](../docs/architecture.md) 3.1절 "학교 도입 전" 구성을 AWS에 만드는 Terraform 코드다. 구성과 나눈 이유는 [ADR-0003](../docs/adr/0003-aws-single-ec2-until-adoption.md), [ADR-0007](../docs/adr/0007-confidential-content-placement.md), [ADR-0020](../docs/adr/0020-terraform-layout.md)에 있다. 이 코드는 아직 어떤 계정에도 apply하지 않았다(맨 아래 "검증하지 않은 것").

```
infra/terraform/
  envs/pre-adoption/   root module: provider, backend, 변수, 출력, mock provider 시험(tests/)
  modules/
    network/           VPC, subnet, IGW, S3 gateway endpoint, fck-nat(HA mode)
    storage/           S3 frontend·content·delivery, content·delivery용 KMS key, bucket policy
    database/          RDS MySQL 8.4 single-AZ, parameter group, security group
    app/               EC2 1대, instance role, security group, log group, Secrets Manager container
    edge/              CloudFront, VPC origin, OAC, signed cookie key group, WAF, ACM, Route 53
    deploy/            ECR, GitHub Actions OIDC provider와 deploy role, 배포 SSM 문서
    monitoring/        SNS topic, CloudWatch 알람
```

## 1. 만드는 것

| 영역 | 내용 |
|---|---|
| 네트워크 | VPC 1개, AZ 2개에 public·private subnet 하나씩. private subnet의 기본 경로는 fck-nat의 고정 ENI다. S3는 gateway endpoint로 간다. interface endpoint는 없다(3.3절) |
| fck-nat | `t4g.nano`, 크기 1 Auto Scaling group. 부팅할 때 고정 ENI와 고정 EIP를 붙인다. private subnet에서 오는 TCP 443만 받아 TCP 443으로만 내보낸다. 모든 외부 호출의 출발 IP는 출력 `nat_egress_ip` 하나다 |
| 앱 EC2 | private subnet, public IP 없음, SSH 없음, SSM Session Manager로 접근. IMDSv2만 허용(hop limit 2, api와 worker 컨테이너가 instance role을 쓰기 위해). host firewall이 instance metadata로 가는 컨테이너 트래픽 중 compose `egress` network(bridge `astra-egress`, api와 worker만 붙는다)에서 온 것만 통과시킨다([ADR-0020](../docs/adr/0020-terraform-layout.md) 6번). root volume 암호화. docker와 compose plugin(checksum 확인)을 설치하고 컨테이너 로그를 CloudWatch Logs로 보낸다 |
| 앱 security group | 들어오는 트래픽은 `CloudFront-VPCOrigins-Service-SG`에서 오는 앱 포트(기본 8080)뿐이다. 나가는 트래픽은 RDS 3306, S3 prefix list 443, fck-nat를 거치는 443뿐이다 |
| instance role | `content`·`delivery` bucket의 객체 읽기·쓰기·삭제와 두 KMS key 사용(S3 경유만), 자기 secret 5개 읽기, ECR pull, 자기 log group 쓰기, `AmazonSSMManagedInstanceCore`. RDS master secret은 읽지 못한다 |
| RDS | MySQL 8.4, single-AZ, private subnet, 공개 안 함, storage 암호화, 자동 백업 7일(PITR), deletion protection, 마지막 snapshot. `utf8mb4`·`utf8mb4_0900_ai_ci`, `require_secure_transport=1`. master 비밀번호는 RDS가 Secrets Manager에서 만들고 돌린다. Terraform state에 비밀번호가 없다. error·slow query 로그는 보존 기간을 둔 log group으로 간다 |
| S3 | `frontend`(SSE-S3, versioning), `content`(SSE-KMS, versioning), `delivery`(SSE-KMS). 셋 다 public access 차단, BucketOwnerEnforced, TLS가 아닌 요청 거부, 미완료 multipart upload 7일 뒤 정리, 이전 version 30일 뒤 삭제. `content`·`delivery`는 자기 KMS key가 아닌 암호화로 쓰는 요청을 거부한다 |
| KMS | `content`, `delivery` key를 따로 두고 자동 회전한다. CloudFront service principal은 이 distribution에 한해 `delivery` key의 `kms:Decrypt`만 쓸 수 있다 |
| CloudFront | 도메인 하나. `/*` → S3 frontend(OAC), `/vod/*`·`/doc/*` → S3 delivery(OAC, signed cookie key group), `/api/*`·`/ws`·`/ws/*` → VPC origin(EC2, 캐시 안 함). `/actuator`는 라우팅하지 않는다(정적 사이트로 떨어진다). viewer TLS 1.2 이상, HTTP는 HTTPS로 돌린다. 정적 사이트에는 HSTS, `nosniff`, `X-Frame-Options: DENY`, Referrer-Policy를, API와 전달 경로에는 HSTS와 `nosniff`만 붙인다. 정적 사이트 경로는 CloudFront Function이 `index.html`로 바꾼다(`frontend_routing`) |
| API 요청 전달 | viewer의 모든 header·cookie·query string과 `CloudFront-Viewer-Address`, `CloudFront-Forwarded-Proto`를 넘긴다. `Host`, `Origin`, `Cookie`, `X-XSRF-TOKEN`, `Sec-Fetch-*`, `Sec-WebSocket-*`, `Idempotency-Key`가 여기에 들어간다 |
| WAF | Core, SQL database, Known bad inputs managed rule group. Core의 `SizeRestrictions_BODY`는 Count다. 대신 업로드 경로(`/api/uploads`, `/api/filebox/files`, `/api/me/profile/image`) 밖에서 본문이 16 KB를 넘으면 413으로 막는 규칙을 둔다. IP 기준 rate rule은 없다(3.3절) |
| 인증서·DNS | us-east-1 ACM 인증서(DNS 검증), 서비스 도메인의 A·AAAA alias |
| signed cookie | public key만 변수로 받아 key group을 만든다. 개인 key는 Terraform에 들어오지 않는다 |
| Secrets Manager | 값이 없는 container 5개: `db-app`, `break-glass`, `cloudfront-private-key`, `llm-api-key`, `oidc-client-secret` |
| 배포 | ECR(tag 변경 불가, push할 때 scan, 최근 30개 유지), GitHub Actions OIDC provider, deploy role, 배포 SSM 문서. deploy role은 이 ECR repository에 push하고, `DeployTarget` tag가 붙은 인스턴스에 배포 문서만 보낼 수 있다 |
| 알람 | EC2 system status(자동 recover 포함), EC2 instance status, fck-nat in-service 수, RDS CPU·남은 storage·연결 수, CloudFront 5xx 비율. SNS topic(서울, us-east-1)으로 보낸다 |
| state | S3 backend, S3 lock file(`use_lockfile`). bucket은 `-backend-config`로 받는다 |

## 2. 만들지 않는 것

| 대상 | 이유 |
|---|---|
| ALB, ECS, ElastiCache for Valkey, RDS Multi-AZ | 3.2절 학교 도입 단계다. 그때 `envs/`에 root module을 하나 더 만든다 |
| NAT Gateway, interface endpoint | 3.3절. 비용 때문에 fck-nat와 S3 gateway endpoint만 둔다 |
| MediaConvert role, EventBridge 규칙, SQS | 7.1절 영상 흐름이다. 11절 2단계에서 앱 코드와 함께 만든다. 그때 `delivery` key policy에 MediaConvert role을 더한다 |
| X-Ray, CloudWatch 지표 쓰기 권한 | 9절. 앱이 OTLP·X-Ray로 내보내기 시작할 때 instance role에 더한다 |
| CI 배포 workflow | 실제 AWS 계정과 GitHub 설정이 있어야 한다. 사용할 명령은 5절에 있다 |
| Slack 연동, 외부 uptime check | 9절. SNS topic까지만 만든다. 지금은 email 구독만 받는다 |
| WAF 로그, CloudFront 접근 로그, VPC flow log, S3 access log | 비용과 개인정보(IP, 경로) 보관 정책을 정한 뒤 켠다 |
| 데이터 반출을 막는 S3 endpoint policy | ECR layer와 AL2023 package bucket을 함께 허용해야 해서 실제 계정에서 확인한 뒤 건다 |

## 3. 운영자가 정하는 변수

`envs/pre-adoption/terraform.tfvars.example`을 `terraform.tfvars`로 복사해 채운다. `terraform.tfvars`와 `backend.hcl`은 커밋하지 않는다(`.gitignore`).

| 변수 | 기본값 | 내용 |
|---|---|---|
| `aws_account_id` | 없음 | 대상 계정. 다른 계정 자격 증명으로는 provider가 멈춘다 |
| `domain_name` | 없음 | 서비스 도메인 하나 |
| `route53_zone_id` | 없음 | 그 도메인을 담은 public hosted zone |
| `cloudfront_signing_public_keys` | 없음 | signed cookie public key(PEM). key 이름 → PEM. 개인 key는 넣지 않는다(검사함) |
| `cloudfront_signing_active_key` | 없음 | 앱이 서명에 쓰는 key 이름 |
| `alarm_emails` | `[]` | 알람 email 구독자 |
| `app_environment` | `{}` | Terraform이 모르는 비밀 아닌 앱 설정. 예: `ASTRA_LLM_MODEL`, `ASTRA_OIDC_ISSUER`, `ASTRA_OIDC_CLIENT_ID`. 검사기 설정(`ASTRA_SCAN_*`)과 `COMPOSE_PROFILES`는 받지 않는다 |
| `scan_uploads` | `true` | ClamAV 검사(compose 프로필 `scan`)를 띄우고 검사기 없이는 앱이 뜨지 않게 한다(ADR-0018). 학교 자료를 받는 배포는 `true`로 둔다. `false`면 업로드가 형식 검사만 거쳐 바로 clean이 된다 |
| `app_instance_type` | `t3.large` | ECR image의 CPU 구조와 맞아야 한다. `scan_uploads`이면 메모리 8 GiB 이상이어야 한다(plan이 검사한다). clamd가 서명만으로 약 1.2 GiB를 쓰고, 컨테이너 메모리 상한의 합(api 1.25, worker 1, converter 2, transcoder 2, clamav 3, freshclam 1 GiB)은 8 GiB를 넘는다. 변환, 영상 변환, 검사가 한꺼번에 상한까지 쓰면 host가 메모리 부족으로 process를 끝낼 수 있다. 그런 부하가 보이면 `t3.xlarge`로 올린다 |
| `db_instance_class` | `db.t4g.small` | |
| `institution_time_zone` | `Asia/Seoul` | 기관 시간대(IANA 이름, 서머타임 없는 것). 앱의 `ASTRA_TIME_ZONE`과 RDS parameter group의 `time_zone`을 함께 정한다. 앱은 연결마다 session offset을 스스로 정하고 다르면 뜨지 않는다(ADR-0039). `app_environment`로는 넣지 않는다 |
| `github_repository`, `github_refs`, `github_environment` | `STack-Corp/STack-eclass-Backend`, `["refs/heads/main"]`, 없음 | deploy role을 받을 수 있는 workflow |
| `create_github_oidc_provider` | `true` | 계정에 이미 GitHub OIDC provider가 있으면 `false` |
| `vpc_cidr`, `availability_zones` | `10.40.0.0/16`, `ap-northeast-2a`·`2c` | |
| `waf_large_body_path_prefixes`, `waf_body_size_limit_bytes` | `["/api/uploads", "/api/filebox/files", "/api/me/profile/image"]`, `16384` | 본문 크기 규칙에서 뺄 경로(모든 업로드 endpoint)와 그 밖의 상한 |

나머지 변수와 검사 조건은 `envs/pre-adoption/variables.tf`에 있다.

## 4. 처음 만드는 순서

1. **state bucket.** 관리자 자격 증명으로 한 번만 만든다. Terraform이 관리하지 않는다.
   ```sh
   aws s3api create-bucket --bucket astra-tfstate-<account id> --region ap-northeast-2 \
     --create-bucket-configuration LocationConstraint=ap-northeast-2
   aws s3api put-public-access-block --bucket astra-tfstate-<account id> \
     --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
   aws s3api put-bucket-versioning --bucket astra-tfstate-<account id> --versioning-configuration Status=Enabled
   cd infra/terraform/envs/pre-adoption
   cp backend.hcl.example backend.hcl   # bucket 이름을 고친다
   terraform init -backend-config=backend.hcl
   ```
2. **도메인과 ACM.** `domain_name`을 담은 hosted zone이 Route 53에 있고 상위 도메인에서 위임돼 있어야 한다. 학교 도메인이면 학교에 NS 위임을 먼저 요청한다. 인증서는 apply할 때 us-east-1에 만들어지고, 검증 record가 같은 zone에 들어가 검증이 끝날 때까지 apply가 기다린다.
3. **signed cookie key pair.** 로컬에서 만든다. public key만 `terraform.tfvars`에 넣는다.
   ```sh
   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out cf-k2026a.pem   # PKCS#8
   openssl pkey -in cf-k2026a.pem -pubout -out cf-k2026a.pub.pem
   ```
4. **apply.** `terraform plan -out=tfplan`으로 확인한 뒤 `terraform apply tfplan`.
5. **비밀값.** secret마다 key가 환경 변수 이름인 JSON object를 넣는다. 허용한 key는 출력 `app_secret_arns`에 있고, 그 밖의 key가 있으면 배포가 멈춘다. `db-app`만 필수다.
   ```sh
   aws secretsmanager put-secret-value --secret-id astra-pre/cloudfront-private-key \
     --secret-string "$(jq -n --rawfile k cf-k2026a.pem '{ASTRA_CLOUDFRONT_PRIVATE_KEY: $k}')"
   aws secretsmanager put-secret-value --secret-id astra-pre/break-glass --secret-string \
     '{"ASTRA_BREAK_GLASS_EXTERNAL_REF":"…","ASTRA_BREAK_GLASS_PASSWORD_HASH":"{bcrypt}…","ASTRA_BREAK_GLASS_TOTP_SECRET":"…"}'
   # llm-api-key: {"ASTRA_LLM_API_KEY":"…"}, oidc-client-secret: {"ASTRA_OIDC_CLIENT_SECRET":"…"}
   ```
   넣은 뒤 로컬의 개인 key 파일을 지운다. 값에 작은따옴표를 넣을 수 없고, 여러 줄 값(PEM)에는 `"`, `\`, `` ` ``, `$`를 넣을 수 없다.
6. **DB 앱 사용자.** master 비밀번호는 출력 `db_master_user_secret_arn`의 secret에서 운영자 자격 증명으로 꺼낸다. instance role은 이 secret을 읽지 못한다. SSM으로 EC2에 접속해 MySQL client 컨테이너로 앱 사용자를 만들고 `db-app` secret에 `{"DB_USERNAME":"…","DB_PASSWORD":"…"}`를 넣는다.
   ```sh
   aws ssm start-session --target <app_instance_id>
   sudo docker run --rm -it mysql:8.4 mysql -h <db_endpoint 호스트> -u astra_admin -p --ssl-mode=REQUIRED
   # CREATE USER 'astra_app'@'%' IDENTIFIED BY '…' REQUIRE SSL;
   # GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP, REFERENCES ON astra.* TO 'astra_app'@'%';
   ```
   Flyway가 앱 사용자로 마이그레이션하므로 DDL 권한이 필요하다.
7. **compose 파일.** 같은 SSM session에서 레포 루트의 `compose.yaml`을 `/opt/astra/compose.yaml`에 둔다. `.env`는 배포할 때마다 다시 만들어지므로 손으로 고치지 않는다.
8. **첫 배포.** 5절 명령으로 image를 push하고 배포 문서를 보낸다.
9. **정적 사이트.** frontend build를 `bucket_names.frontend`에 올리고 CloudFront를 invalidate한다.
10. **알람 구독.** `alarm_emails`의 주소마다 서울과 us-east-1 topic의 확인 메일이 두 통 온다. 둘 다 확인한다.

## 5. 배포

CI workflow는 아직 없다. GitHub Actions에서는 `aws-actions/configure-aws-credentials`의 `role-to-assume`에 출력 `github_deploy_role_arn`을 넣고(`permissions: id-token: write`), 다음을 실행한다. tag는 commit SHA처럼 다시 쓰지 않는 값으로 한다(tag 변경 불가).

```sh
aws ecr get-login-password | docker login --username AWS --password-stdin <ecr 주소>
docker push <ecr_repository_url>:<tag>
docker push <ecr_repository_url>:<tag>-converter   # Dockerfile target converter로 만든 이미지
aws ssm send-command --document-name <deploy_document_name> \
  --targets Key=tag:DeployTarget,Values=astra-pre --parameters ImageTag=<tag>
```

배포 문서(`modules/deploy/templates/deploy.sh`)는 Terraform이 아는 설정, `app_environment`, secret 값으로 `/opt/astra/.env`(root, 0600)를 만들고 `docker compose pull`, `docker compose up -d --wait`를 실행한다. deploy role은 이 문서 말고 다른 명령을 EC2에서 실행할 수 없다([ADR-0020](../docs/adr/0020-terraform-layout.md)).

Terraform이 넣는 설정: `ASTRA_API_BIND=0.0.0.0`, `ASTRA_API_PORT`, `DB_URL`(`sslMode=REQUIRED`), `ASTRA_STORAGE_CONTENT_BUCKET`, `ASTRA_STORAGE_DELIVERY_BUCKET`, `ASTRA_STORAGE_REGION`, `ASTRA_CLOUDFRONT_BASE_URL`, `ASTRA_CLOUDFRONT_KEY_PAIR_ID`, `ASTRA_SESSION_ALLOWED_ORIGINS`, `ASTRA_OIDC_REDIRECT_URI`, `ASTRA_CLIENT_ADDRESS_TRUSTED_PROXIES`, `ASTRA_TIME_ZONE`(`institution_time_zone`), 그리고 `scan_uploads`이면 `COMPOSE_PROFILES=scan`, `ASTRA_SCAN_CLAMD_HOST=clamav`, `ASTRA_SCAN_CLAMD_PORT=3310`, `ASTRA_SCAN_REQUIRED=true`. 배포 문서는 `ASTRA_IMAGE`를 `<tag>`로, `ASTRA_CONVERTER_IMAGE`를 `<tag>-converter`로 넣는다. CloudFront와 EC2 사이가 HTTP라 앱은 요청에서 자기 공개 origin을 알 수 없다. 그래서 허용 Origin을 서비스 주소로 넣는다. app port는 CloudFront VPC origin에만 열려 있으므로 app subnet에서 온 연결은 CloudFront를 거친 요청이고, 앱은 그 연결에서만 `CloudFront-Viewer-Address`를 브라우저 주소로 쓴다.

배포 문서는 컨테이너를 띄우기 전에 host의 instance metadata 차단 규칙이 있는지 보고, 없으면 멈춘다. 띄운 뒤에는 떠 있는 컨테이너마다 그 network namespace에서 instance metadata token을 요청한다. api와 worker가 token을 받지 못하거나 다른 컨테이너가 token을 받으면 그 컨테이너를 멈추고 배포를 실패로 끝낸다. `scan_uploads`로 처음 배포하면 freshclam이 서명을 받는 몇 분 동안 clamav와 worker가 뜨지 않는다. 그동안 받은 업로드는 `quarantined`로 남는다. 문서의 실행 제한 시간은 30분이다.

앱이 지켜야 하는 것:

- `X-Forwarded-For`의 앞쪽 값은 사용자가 보낸 값이다. CloudFront는 이 header에 덧붙이기만 한다. 사용자 IP가 필요하면 `CloudFront-Viewer-Address`를 쓴다. 앱 포트는 CloudFront에서만 열려 있다.
- S3에 쓸 때 SSE header를 보내지 않는다. bucket 기본 암호화가 key를 정한다. 다른 KMS key나 SSE-S3를 지정한 요청은 bucket policy가 거부한다.
- api와 worker 말고는 instance role을 받으면 안 된다(SC-08). hop limit 2라서 bridge network의 컨테이너는 그대로면 IMDS에 닿는다. converter, transcoder, clamav, mysql은 `internal: true` network에만 두고, 외부로 나가야 하는 freshclam은 자기 network(`signatures`)에 둔다. host firewall은 `egress` bridge 밖에서 온 instance metadata 트래픽을 모두 버리므로 새 network를 더해도 기본으로 막힌다. `egress`에는 instance role이 필요한 컨테이너만 붙인다.

## 6. 운영 메모

- 앱 EC2는 AMI와 user data 변경을 무시한다. 새 AL2023으로 바꿀 때는 `terraform apply -replace=module.app.aws_instance.app`로 교체하고 4절 6·7번(compose 파일)과 배포를 다시 한다. 교체하는 동안 API가 멈춘다. instance metadata 차단 규칙(`astra-imds-guard.service`)이 없는 인스턴스에는 배포 문서가 배포하지 않는다. 이 규칙보다 먼저 만든 인스턴스도 같은 방법으로 교체한다.
- fck-nat도 새 AMI를 자동으로 따라가지 않는다. `terraform apply -replace=module.network.aws_launch_template.nat` 뒤 instance refresh가 끝날 때까지 외부 호출, SSM, 로그 전송이 멈춘다.
- signed cookie key 교체: 새 public key를 `cloudfront_signing_public_keys`에 더해 apply → 새 개인 key를 secret에 넣고 `cloudfront_signing_active_key`를 바꿔 apply·배포 → 기존 cookie TTL이 지난 뒤 이전 key를 뺀다.
- 앱 EC2는 `app_termination_protection`, RDS는 `modules/database`의 `deletion_protection`으로 삭제를 막는다. 지우려면 먼저 끄고 apply한다. RDS는 삭제할 때 마지막 snapshot을 남긴다.

## 7. 상시 월 비용 요인

단가는 적지 않는다. apply 전에 AWS Pricing Calculator로 확인한다([ADR-0003](../docs/adr/0003-aws-single-ec2-until-adoption.md)).

| 항목 | 상시로 나가는 것 |
|---|---|
| 앱 EC2 | `t3.large` 1대 730시간, gp3 30 GiB. `scan_uploads`(clamd) 때문에 `t3.medium`의 두 배 메모리를 쓴다 |
| fck-nat | `t4g.nano` 1대, gp3 8 GiB, 사용 중인 public IPv4(EIP) 1개 |
| RDS | `db.t4g.small` single-AZ, gp3 20 GiB, 보존 기간 동안 DB 크기를 넘는 백업 storage |
| KMS | customer managed key 2개의 월 요금과 요청 수(S3 Bucket Key로 줄임) |
| Secrets Manager | secret 6개(container 5개, RDS master 1개) |
| Route 53 | hosted zone 1개와 질의 수 |
| WAF | web ACL 1개, rule 4개, 요청 수 |
| CloudFront | 요청 수와 전송량. 영상 전송이 월 비용 대부분이다(architecture.md 2절) |
| CloudWatch | 로그 수집·보관, 알람 7개 |
| 그 밖 | ECR image 보관, S3 storage와 요청, fck-nat를 지나 인터넷으로 나가는 전송량 |

## 8. 검증한 것과 하지 않은 것

검증한 것(2026-11-11, Terraform 1.15.8, AWS provider 6.72.0):

- `terraform fmt -check -recursive`, `terraform init -backend=false`, `terraform validate`
- `terraform test`: mock AWS provider로 환경 전체를 plan·apply한다. 자원 사이 순환 의존이 없고, `DB_URL`과 허용 Origin이 맞게 만들어지며, 예약된 key를 `app_environment`에 넣거나 개인 key를 public key 자리에 넣으면 거부되는 것을 확인한다. 기본 설정의 `.env`에 검사기 설정 넷이 들어가는지(배포 문서를 렌더링해 본다), `scan_uploads = false`에서 빠지는지, `app_environment`로 검사기를 끌 수 없는지, 메모리 8 GiB 미만 instance type이면 plan이 멈추는지, user data에 instance metadata 차단 규칙이 있고 배포 문서가 그 규칙을 확인하는지도 본다. AWS API는 부르지 않는다.
- 배포 script: 가짜 `aws`·`docker` 명령으로 로컬에서 `.env` 생성, PEM 줄바꿈 처리, `$`가 든 값의 작은따옴표 처리, 허용하지 않은 key 거부, 값이 없는 선택 secret 건너뛰기를 확인했다(2026-11-11). instance metadata 확인 단계는 이 방식으로 확인하지 않았다.

apply하지 않아 확인하지 못한 것:

- fck-nat가 이 IAM 권한(Name tag 조건)으로 고정 ENI와 EIP를 붙이는지, 교체에 걸리는 시간
- VPC origin을 만든 직후 `CloudFront-VPCOrigins-Service-SG` 조회가 성공하는지(이름 pattern과 생성 시점)
- CloudFront OAC가 `delivery` key로 SSE-KMS 객체를 읽는지, WebSocket(`/ws`)이 VPC origin으로 연결되는지
- WAF 오탐: Core·SQLi rule group의 본문 규칙(`CrossSiteScripting_BODY`, `SQLi_BODY` 등)이 업로드 파일 앞 16 KB에 걸리는지, 16 KB 상한이 글 작성·제출 본문에 충분한지. 첫 배포 뒤 sampled request를 보고 `waf_common_rules_to_count`와 `waf_large_body_path_prefixes`를 조정한다
- deploy role의 `ssm:resourceTag/DeployTarget` 조건과 SSM 문서 실행, 실제 AL2023에서 user data(docker, compose plugin, awslogs driver)와 배포 script
- instance metadata 차단 규칙: AL2023의 docker(iptables-nft)가 시작할 때 `DOCKER-USER`의 규칙을 그대로 두는지, bridge 이름(`astra-egress`)이 compose network에 붙는지, 배포 문서의 확인 단계가 api·worker는 통과시키고 freshclam 등은 막는지. 첫 배포 뒤 `docker/smoke-test.sh` 12번과 같은 확인을 운영자가 한 번 더 한다
- `scan_uploads`의 첫 배포: freshclam이 fck-nat를 거쳐 서명을 받는 데 걸리는 시간, clamd의 실제 메모리 사용량
- RDS parameter(`require_secure_transport`, `log_output` 등)가 `mysql8.4` family에서 그대로 받아지는지, 앱의 Connector/J 기본 설정으로 TLS 연결이 되는지
- 정적 사이트 라우팅 함수가 frontend build 형태(SPA 또는 경로별 `index.html`)에 맞는지
- 비용
