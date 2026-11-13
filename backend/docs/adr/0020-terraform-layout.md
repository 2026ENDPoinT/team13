# ADR-0020 Terraform 구성과 배포 명령의 범위

## 상태
채택 (2026-11-11). 6번과 7번은 보안 검토(M2, M3) 뒤 더했다 (2026-11-12).

## 맥락
[architecture.md](../architecture.md) 10절은 인프라를 Terraform으로 관리하고, GitHub Actions가 OIDC로 받은 role로 ECR에 push한 뒤 SSM Run Command로 EC2의 compose를 갱신한다고 정했다. [ADR-0003](0003-aws-single-ec2-until-adoption.md)은 3.1절 구성을, [ADR-0007](0007-confidential-content-placement.md)은 bucket과 KMS key를 정했다. 코드로 옮길 때 다섯 가지를 정해야 했다.

- 코드를 어떻게 나눌지, 외부 module을 쓸지
- CI role이 EC2에서 무엇을 실행할 수 있게 할지
- 비밀값을 Terraform 밖에 두면서 EC2의 컨테이너에 어떻게 넣을지
- instance role의 자격 증명을 어느 컨테이너가 받을 수 있게 할지
- 악성코드 검사([ADR-0018](0018-malware-scan-clamd.md))를 배포에서 어떻게 켤지

api와 worker는 S3와 KMS(S3 경유)를 instance role로 쓴다. AWS SDK는 instance metadata(IMDSv2)에서 자격 증명을 받는다. docker bridge network의 컨테이너는 host보다 한 hop 멀어서 hop limit이 1이면 token을 받지 못한다. hop limit을 2로 두면 bridge network의 모든 컨테이너가 token을 받는다. 그 role은 bucket 읽기·쓰기와 Secrets Manager의 모든 앱 비밀값(DB 비밀번호, OIDC client secret, LLM key, CloudFront 개인 key, break-glass hash와 TOTP seed) 읽기를 허용한다. 외부로 나가는 network에 있던 freshclam이 그 token을 받을 수 있었다.

처음 구성의 `app_environment` 검사는 `COMPOSE_PROFILES`를 거부했고 Terraform은 검사기 설정을 넣지 않았다. 그래서 Terraform으로 만든 배포에서는 clamav가 뜨지 않고 업로드가 형식 검사만 거쳐 clean이 됐다.

fck-nat에는 [RaJiska/fck-nat/aws](https://registry.terraform.io/modules/RaJiska/fck-nat/aws) registry module이 있다. SSM에는 임의의 shell 명령을 받는 AWS 관리 문서 `AWS-RunShellScript`가 있다.

## 결정
1. `infra/terraform/envs/<환경>`에 환경마다 root module을 두고, `infra/terraform/modules/`의 local module 7개(network, storage, database, app, edge, deploy, monitoring)로 조립한다. 외부 module은 쓰지 않는다. fck-nat는 registry module 대신 같은 동작(크기 1 Auto Scaling group, 고정 ENI, 고정 EIP)을 network module에 직접 쓴다.
2. CI role이 실행할 수 있는 SSM 문서는 Terraform이 만든 `<name>-deploy` 하나다. 인자는 image tag 하나이고 형식(`^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`)을 문서가 검사한다. 대상은 `DeployTarget` tag가 붙은 인스턴스뿐이다. `AWS-RunShellScript`는 허용하지 않는다.
3. 비밀값은 Secrets Manager에 container만 Terraform으로 만들고 값은 운영자가 넣는다. 값은 key가 환경 변수 이름인 JSON object다. 배포 문서가 instance role로 값을 읽어 `/opt/astra/.env`(root, 0600)를 다시 만든다. secret마다 허용한 key만 받고, 그 밖의 key가 있으면 배포를 멈춘다.
4. Terraform이 아는 설정(bucket 이름, DB 주소, CloudFront 주소와 key ID, 허용 Origin, `X-Forwarded-For`를 믿을 연결 주소 범위인 app subnet CIDR)은 Terraform이 배포 문서에 넣는다. 운영자가 정하는 비밀이 아닌 설정은 `app_environment` 변수로 넣는다. Terraform이 정하는 key와 비밀 key는 이 변수에 넣을 수 없다.
5. us-east-1에 있어야 하는 자원(ACM 인증서, CloudFront용 WAF, CloudFront 알람과 그 SNS topic)은 provider alias 대신 AWS provider 6의 resource별 `region` 인자로 둔다.
6. instance metadata는 api와 worker만 받는다. hop limit은 2로 두고, host firewall이 허용 목록으로 가린다. compose의 `egress` network는 bridge 이름을 `astra-egress`로 고정하고 api와 worker만 붙인다. user data가 docker보다 먼저 뜨는 systemd unit(`astra-imds-guard`)으로 `DOCKER-USER` chain에 "169.254.169.254로 가는 forward 트래픽 중 `astra-egress`에서 들어오지 않은 것은 버린다"는 규칙 하나를 넣는다. 외부로 나가야 하는 freshclam은 자기 network(`signatures`)에 혼자 둔다. 배포 문서는 컨테이너를 띄우기 전에 이 규칙을 확인하고, 띄운 뒤에는 떠 있는 모든 컨테이너의 network namespace에서 token을 요청해 본다. api와 worker가 받지 못하거나 다른 컨테이너가 받으면 그 컨테이너를 멈추고 실패로 끝낸다. `docker/smoke-test.sh`도 EC2에서 실행하면 같은 확인을 한다. instance metadata의 IPv6 endpoint는 끈다.
7. 악성코드 검사는 root 변수 `scan_uploads`(기본 `true`)로 켠다. 켜면 Terraform이 `COMPOSE_PROFILES=scan`, `ASTRA_SCAN_CLAMD_HOST=clamav`, `ASTRA_SCAN_CLAMD_PORT=3310`, `ASTRA_SCAN_REQUIRED=true`를 `.env`에 넣는다. 이 넷은 `app_environment`로 넣거나 덮을 수 없다. 켜면 앱 instance type의 메모리가 8 GiB 이상이어야 하고 plan이 이를 검사한다. 기본 instance type은 `t3.large`다.

## 결과와 트레이드오프
- CI 자격 증명이 유출돼도 EC2에서 할 수 있는 일은 ECR에 있는 image로 배포하는 것뿐이다. 임의 명령 실행과 비밀값 조회는 막힌다. 악성 image를 push해 배포하는 것은 막지 못한다. 이 위험은 브랜치 보호와 OIDC `sub` 조건(기본 `refs/heads/main`)으로 줄인다.
- 배포 절차를 바꾸려면 Terraform을 apply해야 한다. 절차는 `modules/deploy/templates/deploy.sh` 하나다.
- `compose.yaml`은 이 배포 문서가 옮기지 않는다. 운영자가 처음에 `/opt/astra/`에 둔다. compose 파일을 바꾸면 운영자가 다시 둔다.
- registry module의 기능 추가(CloudWatch agent, NAT64)는 따라오지 않는다. 대신 IAM 권한, security group 포트, AMI 갱신 시점을 이 레포에서 정한다. fck-nat security group은 private subnet에서 오는 TCP 443만 받고 TCP 443으로만 내보낸다.
- 다른 환경(3.2절 학교 도입)은 `envs/`에 root module을 하나 더 만들고 module을 다시 쓴다. ALB, ECS, Valkey, Multi-AZ module은 그때 만든다.
- us-east-1 자원도 같은 provider 설정과 기본 tag를 쓴다. AWS provider 6 이상이 필요하다.
- 6번에서 검토한 안:

  | 안 | 장점 | 단점 |
  |---|---|---|
  | 자격 증명이 없어야 할 network마다 고정 subnet을 주고 subnet별로 169.254.169.254를 막는다(보안 검토가 제안한 안) | 규칙이 network와 일대일로 보인다 | 차단 목록이다. network를 더하거나 subnet을 바꾸고 규칙을 빠뜨리면 그 network가 열린다. `docker0`(compose 밖 `docker run`)도 따로 막아야 한다 |
  | freshclam을 host의 systemd timer로 옮겨 서명 volume에 쓴다 | freshclam이 컨테이너 network에서 빠진다 | host에 ClamAV를 설치하고 판을 compose 이미지와 맞춰야 한다. 나머지 컨테이너의 경계는 그대로 network 구성에 달린다 |
  | hop limit 1로 두고 자격 증명을 다른 길로 준다(host가 받은 임시 자격 증명을 파일로 넣거나, host에 credential endpoint를 띄워 `AWS_CONTAINER_CREDENTIALS_FULL_URI`로 가리킨다) | hop 수로 모든 컨테이너가 막힌다 | 임시 자격 증명이 몇 시간마다 바뀌어 갱신하는 host process가 필요하다. credential endpoint는 직접 만들고 지켜야 하는 새 구성 요소다. endpoint가 bridge에서 닿으면 같은 문제를 그 endpoint의 접근 제어로 옮길 뿐이다 |
  | hop limit 2와 허용 목록 규칙(채택) | 새 network와 compose 밖 컨테이너가 기본으로 막힌다. 규칙 하나, 새 process 없음 | api나 worker 말고 다른 서비스를 `egress`에 붙이면 그 서비스가 token을 받는다. 배포 문서의 확인 단계가 그때 배포를 실패시킨다 |

- api와 worker는 같은 instance role을 그대로 쓴다. worker만 필요한 권한과 api만 필요한 권한을 나누지 않았다([ADR-0019](0019-container-image-and-single-host-compose.md) 5번과 같은 신뢰 경계).
- bridge 이름이 고정이라 같은 host에 compose project를 둘 띄우면 두 번째가 network를 만들지 못한다. 운영 host에는 project 하나만 둔다.
- 이 규칙보다 먼저 bootstrap한 인스턴스는 user data 변경을 무시하므로 규칙이 없다. 배포 문서가 그 인스턴스에는 배포하지 않는다. 인스턴스를 교체해 규칙을 넣는다.
- 7번 때문에 상시 EC2 비용이 `t3.medium`의 약 두 배가 된다. clamd는 서명만으로 약 1.2 GiB를 쓰고([ClamAV 문서](https://docs.clamav.net/manual/Installing/Docker.html)), 컨테이너 메모리 상한의 합은 8 GiB를 넘는다. 문서 변환, 영상 변환, 검사가 한꺼번에 상한까지 쓰면 메모리가 모자랄 수 있다. 서명을 다시 읽을 때 메모리가 두 배가 되지 않도록 clamd의 `ConcurrentDatabaseReload`를 끈다. 대신 다시 읽는 동안 검사가 기다리고, 제한 시간을 넘으면 그 업로드는 `quarantined`로 남아 다시 검사된다.
- 빈 서명 volume으로 처음 배포하면 freshclam이 서명을 받을 때까지 clamav와 worker가 뜨지 않는다. api는 뜨고 업로드를 `quarantined`로 받는다. 검사기가 없으면 clean으로 가는 길이 없으므로 그동안 첨부할 수 없다. 배포 문서의 실행 제한 시간은 이 때문에 30분이다. freshclam이 서명을 받지 못하면 `docker compose up --wait`가 실패하고 배포가 실패로 끝난다.
- `scan_uploads = false`로 두면 ADR-0018 7번의 "검사기 없음" 동작이다. 학교 자료를 받지 않는 시험 배포에서만 쓴다.
