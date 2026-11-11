# ADR-0020 Terraform 구성과 배포 명령의 범위

## 상태
채택 (2026-11-11)

## 맥락
[architecture.md](../architecture.md) 10절은 인프라를 Terraform으로 관리하고, GitHub Actions가 OIDC로 받은 role로 ECR에 push한 뒤 SSM Run Command로 EC2의 compose를 갱신한다고 정했다. [ADR-0003](0003-aws-single-ec2-until-adoption.md)은 3.1절 구성을, [ADR-0007](0007-confidential-content-placement.md)은 bucket과 KMS key를 정했다. 코드로 옮길 때 세 가지를 정해야 했다.

- 코드를 어떻게 나눌지, 외부 module을 쓸지
- CI role이 EC2에서 무엇을 실행할 수 있게 할지
- 비밀값을 Terraform 밖에 두면서 EC2의 컨테이너에 어떻게 넣을지

fck-nat에는 [RaJiska/fck-nat/aws](https://registry.terraform.io/modules/RaJiska/fck-nat/aws) registry module이 있다. SSM에는 임의의 shell 명령을 받는 AWS 관리 문서 `AWS-RunShellScript`가 있다.

## 결정
1. `infra/terraform/envs/<환경>`에 환경마다 root module을 두고, `infra/terraform/modules/`의 local module 7개(network, storage, database, app, edge, deploy, monitoring)로 조립한다. 외부 module은 쓰지 않는다. fck-nat는 registry module 대신 같은 동작(크기 1 Auto Scaling group, 고정 ENI, 고정 EIP)을 network module에 직접 쓴다.
2. CI role이 실행할 수 있는 SSM 문서는 Terraform이 만든 `<name>-deploy` 하나다. 인자는 image tag 하나이고 형식(`^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`)을 문서가 검사한다. 대상은 `DeployTarget` tag가 붙은 인스턴스뿐이다. `AWS-RunShellScript`는 허용하지 않는다.
3. 비밀값은 Secrets Manager에 container만 Terraform으로 만들고 값은 운영자가 넣는다. 값은 key가 환경 변수 이름인 JSON object다. 배포 문서가 instance role로 값을 읽어 `/opt/astra/.env`(root, 0600)를 다시 만든다. secret마다 허용한 key만 받고, 그 밖의 key가 있으면 배포를 멈춘다.
4. Terraform이 아는 설정(bucket 이름, DB 주소, CloudFront 주소와 key ID, 허용 Origin, `CloudFront-Viewer-Address`를 믿을 연결 주소 범위인 app subnet CIDR)은 Terraform이 배포 문서에 넣는다. 운영자가 정하는 비밀이 아닌 설정은 `app_environment` 변수로 넣는다. Terraform이 정하는 key와 비밀 key는 이 변수에 넣을 수 없다.
5. us-east-1에 있어야 하는 자원(ACM 인증서, CloudFront용 WAF, CloudFront 알람과 그 SNS topic)은 provider alias 대신 AWS provider 6의 resource별 `region` 인자로 둔다.

## 결과와 트레이드오프
- CI 자격 증명이 유출돼도 EC2에서 할 수 있는 일은 ECR에 있는 image로 배포하는 것뿐이다. 임의 명령 실행과 비밀값 조회는 막힌다. 악성 image를 push해 배포하는 것은 막지 못한다. 이 위험은 브랜치 보호와 OIDC `sub` 조건(기본 `refs/heads/main`)으로 줄인다.
- 배포 절차를 바꾸려면 Terraform을 apply해야 한다. 절차는 `modules/deploy/templates/deploy.sh` 하나다.
- `compose.yaml`은 이 배포 문서가 옮기지 않는다. 운영자가 처음에 `/opt/astra/`에 둔다. compose 파일을 바꾸면 운영자가 다시 둔다.
- registry module의 기능 추가(CloudWatch agent, NAT64)는 따라오지 않는다. 대신 IAM 권한, security group 포트, AMI 갱신 시점을 이 레포에서 정한다. fck-nat security group은 private subnet에서 오는 TCP 443만 받고 TCP 443으로만 내보낸다.
- 다른 환경(3.2절 학교 도입)은 `envs/`에 root module을 하나 더 만들고 module을 다시 쓴다. ALB, ECS, Valkey, Multi-AZ module은 그때 만든다.
- us-east-1 자원도 같은 provider 설정과 기본 tag를 쓴다. AWS provider 6 이상이 필요하다.
