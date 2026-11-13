# ADR-0003 학교 도입 전까지 CloudFront VPC origin과 EC2 1대

## 상태
채택 (2026-11-09)

## 맥락
클라우드는 AWS 서울 리전을 쓴다. 학교 도입 전까지 사용자는 수백 명 이하이고, 온라인 강의 하트비트는 동시 시청 500명이어도 약 2 rps다. 영상은 CDN이 송출하므로 API 서버의 부하는 작다. 스타트업 비용으로 운영해야 한다.

처음 안은 CloudFront, ALB, NAT Gateway, ECS Fargate(api, worker)였다. ALB는 TLS 종료, health check, 방화벽 경계, 오토스케일링 입구 역할이다. 그런데 CloudFront [VPC origin](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-vpc-origins.html)은 private subnet의 EC2 인스턴스에 직접 연결할 수 있고 서울 리전을 지원한다. VPC origin은 ALB, NLB, EC2만 받으므로 ALB 없이 Fargate task를 origin으로 쓸 수는 없다. AWS App Runner는 2026년 4월 30일부터 신규 고객을 받지 않는다.

| 구성 | ALB | NAT | 실행 | 월 고정비(대략) |
|---|---|---|---|---|
| ALB, NAT Gateway, Fargate api·worker | 약 $24 | 약 $43 + GB당 처리비 | 약 $83 | 약 $150 |
| VPC origin, fck-nat, EC2 1대 | $0 | 약 $5 | 약 $37 | 약 $42 |

서울 리전 시간당 단가에 730시간을 곱한 값이다. RDS는 두 안이 같아 뺐다. 단가는 [2026-05 기준 제3자 정리값](https://github.com/sirzzang/aws-cost-estimation/blob/main/prices/seoul-cheatsheet.json)이고 작성자도 검증 전 초안으로 표시했다. Fargate는 1 vCPU·2 GB task 2개, EC2는 t3.medium 기준이다. fck-nat의 public IPv4 요금은 넣지 않았다. 리소스를 만들기 전에 AWS Pricing Calculator로 다시 확인한다.

## 결정
1. 학교 도입 전까지 CloudFront VPC origin이 private subnet의 EC2 1대에 연결한다. ALB는 두지 않는다.
2. EC2 1대에서 docker compose로 api와 worker 컨테이너를 실행한다. EC2와 RDS에는 public IP를 주지 않는다.
3. egress는 NAT Gateway 대신 fck-nat 인스턴스를 high-availability mode(크기 1 Auto Scaling group과 고정 ENI)로 쓴다. S3는 gateway endpoint로 접근한다.
4. EC2 security group은 CloudFront VPC origin의 service-managed security group에서 오는 트래픽만 받는다. SSH는 열지 않고 SSM으로 접근한다.
5. 배포는 GitHub Actions가 OIDC로 받은 role로 ECR에 이미지를 push하고 SSM Run Command로 compose를 갱신한다.
6. 앱은 stateless로 만든다. session은 DB, 파일은 S3, 스케줄 작업은 ShedLock으로 처리하고 로컬 디스크에 상태를 남기지 않는다.
7. 다음 중 하나가 생기면 private ALB와 ECS(api min 2)로 전환한다. 학교 정식 도입으로 이중화가 필요할 때, 배포 중 API 중단이 실제 불만이 될 때, EC2 1대의 CPU가 상시 높을 때. 전환할 때 CloudFront origin을 EC2에서 ALB로 바꾸고, 인스턴스가 2대 이상이 되므로 ElastiCache for Valkey를 붙여 WebSocket fan-out과 rate limit 저장소를 옮긴다. 도메인은 그대로이고 앱은 설정만 바뀐다.

## 결과와 트레이드오프
- 오토스케일링이 없다. 인스턴스 하나가 감당할 수 있는 규모라고 본다.
- 배포하는 동안 JVM 기동 시간만큼 API가 끊긴다. 사용량이 적은 시간에 배포하고 JDK AOT cache로 기동 시간을 줄인다. 그래도 문제가 되면 같은 호스트에서 api 컨테이너 두 개를 reverse proxy로 교대시키거나 7번 전환을 앞당긴다.
- 앱 EC2의 장애는 docker restart 정책과 EC2 auto recovery로 대응한다. VPC origin은 인스턴스 ARN에 묶이므로 앱 EC2를 크기 1인 Auto Scaling group으로 교체하는 방식은 쓰지 않는다. fck-nat는 origin이 아니므로 3번처럼 Auto Scaling group을 쓴다.
- fck-nat는 관리형이 아니다. S3 외의 AWS API(SSM, ECR, Secrets Manager, CloudWatch, X-Ray, SQS, MediaConvert)와 외부 IdP·AI 호출이 모두 fck-nat를 지나므로, 인스턴스가 교체되는 동안 배포, SSM 접속, 로그·지표 전송, 미디어 완료 처리, 로그인이 멈춘다. interface endpoint는 개당 AZ별로 과금되어 이 단계에서는 NAT 비용보다 비싸므로 두지 않고 이 장애 범위를 받아들인다. 학교 도입 시점에 NAT Gateway 전환을 함께 판단한다.
- 학교 공식 시스템으로 개인정보를 다루면 CSAP 중 등급이 요구될 수 있고, AWS는 하 등급만 보유하고 있다. 앱은 S3 API, 컨테이너, MySQL 표준만 쓰고 AWS 전용 구성은 Terraform에만 둬서 다른 클라우드로 옮길 수 있게 한다.
