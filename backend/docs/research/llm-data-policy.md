# ADR-0008 보완 자료: 강의자료 요약용 외부 LLM API의 데이터 처리 정책 근거

작성 기준일: 2026-11-11. 모든 항목은 아래 출처 장부의 번호로 근거를 단다. 1차 자료로 확인하지 못한 내용은 "미확인"으로, 근거 문서를 해석한 부분은 `[해석]`으로 표시한다. 이 문서는 법률 자문이 아니며 선택지를 권고하지 않는다.

## 0. 결정 범위

- 결정: 강의자료 페이지 이미지와 추출 텍스트(교수 저작물, 드물게 학생 이름 포함)를 외부 LLM API로 보내 페이지별 요약을 만들 때의 데이터 처리 정책.
- 열린 항목: 제공자 보관 기간, 학습 이용, 국외 이전, 교수 동의 절차.
- 다루지 않은 항목(열어 둠): 요약 결과물의 저작권 귀속, 학생 대상 고지 문구, 비용, 모델 품질 비교.

## 1. DeepSeek 플랫폼 API (api.deepseek.com)

| 항목 | 확인한 사실 | 출처 |
|---|---|---|
| 적용 문서 | API에는 Open Platform ToS(2026-04-29 시행)가 "DeepSeek Terms of Use"의 특별약관으로 적용된다. 운영 주체는 Hangzhou DeepSeek Artificial Intelligence Co., Ltd.이다. 개인정보 처리방침은 개발자가 만든 다운스트림 서비스의 최종 이용자 정보에는 적용되지 않으며, 그 경우 개발자가 개인정보 관리자로서 처리방침을 공개해야 한다고 적는다. | [2], [1] |
| (a) 보관 | API 입력·출력의 보관 기간은 어느 문서에도 명시되어 있지 않다. 처리방침은 계정 탈퇴 시 또는 목적 달성 시까지 보유한다고 적고, 법정 보관 기간(전자상거래법 5년·3년·6개월, 통신비밀보호법 3개월)을 나열한다. 국외 수취자 표에서 Beijing DeepSeek이 받는 "비식별화 후의 사용자 프롬프트"의 보유 기간은 "모델 훈련 또는 최적화 완료 시"이다. | [1] |
| (b) 학습 이용 | 이용약관 4.3: "안전한 암호화, 엄격한 비식별화" 전제로 서비스와 기반 기술의 제공·개선을 위해 입력·출력을 이용할 수 있고, 거부는 "모두를 위한 모델 개선" 기능을 끄는 방식이다. 이용약관 1.1은 API를 "본 서비스"에 포함한다. 처리방침은 PIPA 제15조제3항(추가적 이용)을 근거로 학습·개선 이용을 적고, 옵트아웃 권리를 명시한다. 이 토글이 API 호출에 적용되는지, API 계정에서 어떻게 끄는지는 문서에 없다(미확인). | [3], [1] |
| (c) 처리·저장 위치 | "저희 서버는 중화인민공화국에 위치해 있습니다." 국외 이전 근거로 PIPA 제28조의8제1항제3호를 든다. 준거법은 중국 본토 법, 관할은 항저우 DeepSeek 등록지 법원이다(ToS 10.1~10.2). | [1], [2] |
| (d) 수탁자·이전 수취자 | 처리방침 표: Beijing DeepSeek(중국, 비식별 프롬프트, 알고리즘·아키텍처 설계 지원), Beijing Volcano Engine(중국, 기기·네트워크 정보·이용 내역), Intuition Machines(미국, hCaptcha). Cloudflare를 방화벽·침입탐지에 쓴다고 적는다. 국내대리인은 법무법인 세종이다. | [1] |
| (e) 기업·무보관 옵션 | 기업 계약, 데이터 처리 부속서(DPA), 무보관(zero-retention) 옵션을 설명한 1차 자료를 찾지 못했다(미확인). | — |
| 기타 의무 | ToS 3.3: 개발자는 최종 이용자에게 처리 규칙을 고지하고, DeepSeek에 대한 처리 위탁에 관해 동의 또는 다른 법적 근거를 확보해야 한다. ToS 4.1: 입력에 필요한 모든 권리·허락을 보유함을 진술·보장해야 한다. | [2] |
| 규제 이력 | 개인정보위는 2025-04-23 전체회의에서 딥시크가 이용자 프롬프트 입력 내용을 Volcano Engine에 전송한 사실을 확인하고, 국외 이전 합법 근거 구비와 이미 이전한 프롬프트의 즉각 파기를 시정권고했다. 딥시크는 2025-04-10부터 신규 이전을 차단했다고 알려왔다. 당시 프롬프트 학습에 대한 opt-out 기능이 없었고 이후 마련했다고 한다. API·오픈소스 이용 기업은 점검 대상이 아니었다고 답했다. | [4] |

## 2. Hive(thehive.ai) 모델 API로 DeepSeek 모델을 쓰는 경우

참고: thehive.ai의 약관·처리방침 URL은 2026-11-11 현재 deploy.com으로 이동하며, 계약 주체는 Castle Global, Inc.("Deploy")로 표기된다. 기술 문서는 docs.thehive.ai에 남아 있다.

| 항목 | 확인한 사실 | 출처 |
|---|---|---|
| 제공 형태 | `/api/v3/chat/completions`는 "Hive's self-hosted large language models"에 대한 OpenAI 호환 경로이다. 모델 키 `deepseek-ai/deepseek-v4.1-flash-1017`, 입력 모달리티 Text, Image. 서버 측 대화 상태가 없다(stateless). | [5], [6] |
| DeepSeek 인프라 전달 여부 | 문서는 "self-hosted"라고만 적는다. 프롬프트를 DeepSeek 인프라로 전달하지 않는다는 명시적 문장은 찾지 못했다(명시 진술은 미확인). AI Service Addendum 3.1(4)은 Third-Party Model이 포함된 서비스에서 Deploy가 "infrastructure and platform services"만 제공한다고 적는다. | [5], [9] |
| (a) 보관 | FAQ: 기본 보관 기간 14일, 요청 시 늘리거나 줄일 수 있고 "not retaining your data at all"도 가능하다. 보관 연장은 추가 비용이 붙을 수 있다. Enterprise Commitments: "Configurable data retention". 단, FAQ는 분류 API 맥락에서 작성되어 LLM 경로에도 같은 기본값이 적용되는지는 문서상 명시되어 있지 않다(미확인). | [7], [8] |
| (b) 학습 이용 | Terms of Use 3.1: 고객 제공 콘텐츠를 "providing, developing and improving Deploy Services, their underlying technologies" 목적으로 이용할 비독점 권리를 Deploy에 부여한다(서면 합의로 제한 가능). AI Service Addendum 3.1(5): Generative AI Services에 제출한 콘텐츠에 대한 이 라이선스는 "irrevocable and perpetual"이다. AI Service Addendum 5.1: AI Services 이용 시 고객 콘텐츠가 Hive Workers의 수작업 검토를 받으며, 서면으로 배제 합의한 경우만 예외이다. Labeling Addendum 2.1: Hive Workers는 전 세계에 분산된 독립 작업자이며 sub-processor가 될 수 있다. 학습 opt-out 절차는 문서에 없다(미확인). | [9] |
| (c) 처리·저장 위치 | 처리방침: 서비스는 미국에서 운영되며 정보는 미국 또는 다른 국가에서 처리·저장될 수 있다. API 문서는 Virginia(VA1) 고객용 별도 엔드포인트를 안내한다. 지역 고정 옵션의 계약 조건은 미확인. 준거법은 캘리포니아주법, 분쟁은 JAMS 중재(Terms 11.1~11.2). | [11], [5], [9] |
| (d) 수탁자 | 공개 sub-processor 목록을 찾지 못했다(미확인). 처리방침은 계열사, 서비스 제공자(클라우드 등)와 공유한다고 적는다. Hive Workers가 sub-processor가 될 수 있다는 조항은 위와 같다. | [11], [9] |
| (e) 기업·무보관 옵션 | V2 Enterprise 프로젝트(영업 담당 활성화)가 있고, 데이터 보관 통제는 영업 상담 사항이다. 무보관은 요청 가능하다고 FAQ가 적는다. 개인정보는 Deploy의 사전 서면 동의가 있을 때만 제출할 수 있고, 제출 시 개인정보임을 표시해야 한다(Terms 6.1(c), Customer Agreement 5.1(c)). | [7], [8], [9], [10] |

## 3. 비교 대상

### 3-1. Amazon Bedrock, ap-northeast-2(서울)

| 항목 | 확인한 사실 | 출처 |
|---|---|---|
| 서울 In-Region 비전 모델 | AWS는 2026-09-29 Claude Opus 5, Claude Sonnet 5를 서울 리전 bedrock-runtime에서 in-region 추론으로 제공한다고 발표했다("the processing never leaves that Region"). 두 모델 카드 모두 입력 모달리티에 Image, Text가 있다. Sonnet 5 카드는 서울 In-Region 지원을 표시한다. | [16], [17], [18] |
| OpenAI 호환 여부 | Opus 5·Sonnet 5는 bedrock-runtime에서 Messages, Converse, Invoke를 지원하고 Chat Completions는 지원하지 않는다. OpenAI 호환 API를 제공하는 bedrock-mantle 엔드포인트는 서울 리전에 없다. 따라서 서울 In-Region으로 확인된 비전 모델은 OpenAI 호환 Chat Completions로 호출할 수 없다. | [17], [18], [19] |
| 기타 모델 | Nova Lite, Nova Pro는 서울에서 Geo(교차 리전)만 지원하고, Nova 2 Lite는 Global만 지원한다. Geo는 정의된 지리권(APAC 등) 안의 다른 리전으로 라우팅되고, Global은 전 세계 상용 리전으로 라우팅된다. 서울 In-Region 모델의 전체 목록은 2,700행 규모 표를 끝까지 대조하지 못했다(전체 목록 미확인). | [20] |
| (a) 보관 | 리전·계정·프로젝트 단위 `data_retention_mode`. `none`이면 요청·응답을 내구 저장소에 쓰지 않고 모델 제공자와 공유하지 않는다. `default`이면 모델별 정책이 적용되고 AWS가 안전·남용 방지 목적으로 보관할 수 있다. 모드 설정은 리전별이며 다른 리전으로 전파되지 않는다. SCP로 `none` 외 설정을 금지할 수 있다. 남용 탐지: OpenAI 모델(gpt-oss 계열 제외)은 분류기 표시 트래픽을 최대 30일, Claude Fable 5 계열은 전 트래픽을 최대 30일 보관한다. CSAM 탐지는 `none` 모드와 별개로 저장·검토될 수 있다. | [12], [14], [15] |
| (b) 학습 이용 | AWS와 제3자 모델 제공자는 Bedrock 입력·출력을 모델 학습에 쓰지 않는다. 모델 제공자는 모델 배포 계정, 로그, 프롬프트·응답에 접근하지 못한다. | [15], [13] |
| (c) 위치 | In-Region은 지정 리전 안에서만 처리한다. 교차 리전 추론으로 보관이 필요한 모델을 쓰면 보관 데이터는 실제 처리 리전에 저장된다. | [20], [14] |
| (d) 수탁자 | AWS Sub-processors 페이지(2026-10-30 갱신): 서울 리전 인프라 주체는 Amazon Corporate Services Korea LLC. 신규 sub-processor는 30일 전 공지. Bedrock은 "service improvement" 표에 포함되어 있지 않다. | [21] |
| (e) 무보관 | `none` 모드가 표준 기능이다. 보관이 필요한 모델의 ZDR은 계정·모델 단위로 AWS 계정팀과 협의한다. | [12] |

### 3-2. 네이버클라우드 CLOVA Studio

| 항목 | 확인한 사실 | 출처 |
|---|---|---|
| API·비전 | OpenAI 호환 `/chat/completions`(Chat Completions, Chat Completions v3) 제공, 기본 URL `https://clovastudio.stream.ntruss.com/v1/openai/`. 이미지 입력은 v3 Chat Completions의 HCX-005에서만 가능하다. OpenAI 호환 경로에서 이미지 입력이 되는지는 호환 문서에 명시되어 있지 않다(미확인). | [24], [25] |
| (a) 보관 | 계약 종료 또는 서비스 종료 시 모델·결과물·법정 보관 의무 없는 데이터를 5일 내 삭제(민간 약관 제7조①). 단, 제한조치 사유 확인·증명 또는 활용례·샘플 제공(개인정보 미포함에 한함) 목적이면 이용 종료 후 1년간 고객 데이터와 결과물을 보관·이용할 수 있다(제7조②). 요청 단위 보관 기간은 명시되어 있지 않다(미확인). 공공기관용 약관(2024-12-19 시행)은 제7조①에 "5일 내" 문구가 없다. | [22], [23] |
| (b) 학습·기타 이용 | 회사는 서비스 운영(성능 개선, 통계 포함)에 필요한 범위에서 고객 데이터·결과물에 접근하고, 홍보 목적으로 사용할 수 있다(제4조②). 모든 정보·자료를 홍보, 품질·기능 개선 목적으로 사용할 수 있다(제10조①). 모델 학습 이용 여부를 직접 다룬 문장은 없다(미확인). | [22], [23] |
| 입력 제한 | 고객 데이터에 "개인정보", "저작권자의 허락이 없는 저작물"이 포함되지 않도록 해야 하고, 회사는 이를 모니터링하여 삭제·이용 중단할 수 있다(제10조②). 개인정보 처리가 필요하면 위수탁계약 체결 또는 제3자 제공 동의 확보를 고객 책임으로 둔다(제11조①). 회사는 "고객의 위탁을 받아" 서비스를 제공하는 지위라고 적는다(제5조②). | [22], [23] |
| (c) 위치 | 데이터 처리 위치를 명시한 1차 자료를 찾지 못했다(미확인). | — |
| (d) 수탁자 | CLOVA Studio의 재수탁자 목록을 찾지 못했다(미확인). | — |
| (e) 무보관 | 무보관 계약 옵션을 설명한 1차 자료를 찾지 못했다(미확인). | — |
| 버전 | 확인한 최신본은 민간 약관 2025-07-17 시행, 공공기관용 2024-12-19 시행이다. 이후 개정 여부는 미확인. | [22], [23] |

## 4. 국내 법령·공공 지침

### 4-1. 개인정보 보호법(법률 제21445호, 2026-09-11 시행)

- 제28조의8①: 국외로 제공(조회 포함)·처리위탁·보관하는 것을 "이전"으로 보고 원칙적으로 금지한다. 예외: 1. 별도 동의, 2. 법률·조약의 특별 규정, 3. 정보주체와의 계약 체결·이행에 필요한 처리위탁·보관으로서 제2항 사항을 처리방침에 공개하거나 전자우편 등으로 알린 경우, 4. 보호위원회 고시 인증 + 조치, 5. 보호위원회 적정성 인정. [26]
- 제28조의8②: 동의 시 미리 알릴 사항은 이전 항목, 국가·시기·방법, 수취자 명칭·연락처, 수취자의 이용 목적·보유 기간, 거부 방법·절차·효과이다. ③ 변경 시 재고지·재동의. ④ 이전 시 제17조~제19조, 제5장 준수와 대통령령 보호조치. ⑤ 법 위반 내용의 국외 이전 계약 금지. [26]
- 제28조의9: 위반 또는 이전받는 국가의 보호 수준 미흡으로 피해 우려가 현저하면 보호위원회가 이전 중지를 명할 수 있다. 제28조의11: 이전받은 자가 제3국으로 재이전하면 제28조의8·9를 준용한다. [26]
- 제26조: 처리위탁은 목적 외 처리 금지, 기술적·관리적 보호조치 등을 담은 문서로 한다(①). 위탁 업무와 수탁자(재수탁자 포함)를 정보주체가 쉽게 확인하도록 공개한다(②). 수탁자 교육·감독(④). 수탁자는 위탁 범위를 초과하여 이용하거나 제3자에게 제공할 수 없다(⑤). 재위탁은 위탁자 동의가 필요하다(⑥). 수탁자에게 제28조의7~제28조의11 등을 준용한다(⑧). [27]

### 4-2. 개인정보위 생성형 AI 지침

- 「생성형 인공지능(AI) 개발·활용을 위한 개인정보 처리 안내서」, 2025-08-06 공개. 서비스형 LLM(API 연계)을 쓰는 기관은 전송 데이터의 처리 목적·범위, 보관·파기 정책, 재이용(AI 학습 포함) 여부, 국외 이전을 사전 검토하고, 기업용 API 라이선스·DPA 등으로 입력 데이터 소유권, 재이용 제한, 안전조치, 파기를 계약상 확보할 것을 권장한다. 공공기관은 법정 요건 해당 시 개인정보 영향평가(제33조)를 의무로 수행한다. LLM 서버가 해외에 있으면 국외 이전 규율을 확인하라고 하며, 처리방침에 국외 이전 고지사항이 누락된 사례(제2024-010-184호 결정)를 든다. [28], [29]
  - 본문은 개인정보위 원본 첨부 다운로드가 실패하여 외부 게시본("(안)" 표기)으로 확인했다. 공개 확정본과 문구 차이가 있을 수 있다.
- 2025-08 이후 이 안내서의 개정판 존재 여부는 개인정보위 안내서 게시판 접근 실패로 확인하지 못했다(미확인).
- 「공공 AX 프라이버시 보호 안내서」: 2026-07-23 과학기술관계장관회의에서 공개되었다고 보도되었다. "자료 요약·검색 등 기초업무 보조" 유형은 안전한 이용환경과 기본 안전조치에 초점을 둔다고 한다. 원문은 확인하지 못했다(보도만 확인, 1차 자료 미확인). [30]

### 4-3. 교육부(대학) 지침

- 교육부·한국대학교육협의회 「대학 AI 활용 윤리 가이드라인」, 2026-08-24 배포. 공문은 법적 구속력이 없고 대학에 특정 조치를 의무화하지 않는다고 적는다. [31], [32]
- 교수자 가이드라인 5.1.1: 교수자·학습자를 식별할 수 있는 개인정보는 AI 도구에 입력하지 않는다. 학생이 제출한 과제 등 비공개 학습자료나 수업 관련 문서, "비공개 교육 자료"를 포함한 기밀·소유권 정보를 입력하지 않는다. 5.1.2: 대학이 공식 제공하지 않는 외부 AI 서비스는 이용약관과 데이터 보관 정책을 확인하도록 지도한다. 5.2.1: 대학이 공식 승인한 도구가 있으면 그것을 중심으로 쓴다. [33]
- KERIS 명의의 생성형 AI 관련 대학 지침은 찾지 못했다(미확인).

### 4-4. 공공 클라우드·AI 보안 지침

- 국가정보원 「국가·공공기관 AI보안 가이드북」 2.0, 2025-12(2023-06 「챗GPT 등 생성형 AI 활용 보안 가이드라인」 1.0의 개정판). 제2장 제3절 "상용 AI서비스 활용": 외부 상용 AI의 API를 쓰는 시스템에도 적용된다. 공개등급(O) 데이터 사용, AI-DLP 등 입·출력 필터링(M13), 로깅·모니터링(M09), 중계서버·경계보안(M17), 파일·이미지 입력의 별도 승인절차 가능, 용역업체 보안관리(T15)를 제시한다. 가이드북의 보안대책은 권고사항이며 네트워크 세부 대책은 국정원 관련 지침을 따른다고 적는다. [34]
- 행정안전부 공문 「생성형 인공지능 서비스 활용 시 보안 주의사항 안내」: 행정내부정보 등 비공개 정보, 개인정보를 입력하지 않도록 기존 유의사항(행안부 2023-05-08, 국정원 2023-06-29 가이드라인)을 준수하라고 한다. 문서 발송일은 본문에 표시되어 있지 않고 게시 경로가 2026-09-21이다. [35]
- 행정안전부고시 제2025-79호(2025-12-29 시행) 제11조: ① 「국가 정보보안 기본지침」과 「국가 망 보안체계(N2SF) 보안가이드라인」 준수. ② 클라우드컴퓨팅법 제23조의2 보안인증(CSAP) 서비스를 우선 고려하고 「국가 클라우드 컴퓨팅 보안 가이드라인」에 따라 이용. ③ 고등교육법 제2조의 학교의 장이 "교육 현장에서 이용하는 정보시스템"에는 ②를 적용하지 않을 수 있다. 제2조제2호의 "공공기관"에서 사립학교는 제외된다. 본문은 국가법령정보센터 페이지가 스크립트로만 렌더링되어 U-LEX 게시본으로 확인했다. [36]
- 국외 AI 서비스로 기관 데이터를 보내는 것을 일률적으로 금지하는 조문은 위 자료에서 찾지 못했다. 위 자료는 입력 정보의 등급 제한과 통제 수단을 요구하는 형태이다.

## 5. 저작권

- 저작권법 제9조(2026-10-29 시행본): 법인등의 명의로 공표되는 업무상저작물의 저작자는 계약 또는 근무규칙 등에 다른 정함이 없으면 그 법인등이다. [37]
- KERIS 이슈리포트 RR 2015-1(연구진 견해이며 KERIS 공식 의견이 아니라고 명시): 교원의 강의는 통상 교원 명의로 공표되어 "법인 등의 명의 공표" 요건이 충족되지 않으므로 대부분 저작자는 교원이다. 배포자료도 교원 명의이고 작성이 교원 자율에 맡겨지는 경우가 대부분이어서 직무상 작성 요건도 충족되지 않는다. 학문의 자유에 따른 내용 자율성 등을 들어 "특별히 법인 등의 저작물로 보아야 할 다른 사정이 없는 한 업무상 저작물로 취급하지 않는 것이 일반적 견해"라고 적는다(이해완, 저작권법, 2007 인용). 국공립 교원의 직무발명은 발명진흥법 제10조에 따라 전담조직이 승계하지만 저작권은 대학이 일방적으로 이전받을 수 없다고 적는다. [39]
- 국내 대법원 판례로 국립대 교수 강의자료의 귀속을 정면으로 다룬 사례는 찾지 못했다(미확인). 서울과학기술대학교 규정에서 강의자료 저작권 귀속을 정한 조항은 찾지 못했다(미확인).
- 관련 권리: 복제권(제16조), 2차적저작물작성권(제22조), 이용허락은 허락받은 방법·조건 범위 안에서만 이용 가능하고 저작재산권자 동의 없이 제3자에게 양도할 수 없다(제46조). 일시적 복제 면책(제35조의2)은 "원활하고 효율적인 정보처리를 위하여 필요하다고 인정되는 범위"에 한하고 이용이 저작권을 침해하는 경우에는 적용되지 않는다. [38]
- `[해석]` 위 자료를 종합하면, 교수가 저작자인 강의자료를 대학이 제3자 API로 전송하고 요약을 만드는 것은 복제(전송 과정과 제공자 측 저장) 및 경우에 따라 2차적저작물 작성에 해당할 수 있어 저작재산권자의 이용허락 범위에 들어야 한다. 요약문이 2차적저작물에 해당하는지는 요약의 창작성·원문 표현 차용 정도에 따라 달라지며 확인한 1차 자료는 없다.
- 제공자 약관 측 제약: DeepSeek ToS 4.1과 Deploy Terms 6.1은 입력에 필요한 권리·허락 보유를 고객이 진술·보장하게 한다. CLOVA Studio 약관 제10조②는 "저작권자의 허락이 없는 저작물"의 입력을 금지한다. Deploy는 생성형 AI 입력 콘텐츠에 대해 개발·개선 목적의 취소 불가·영구 라이선스를 받는다(AI Service Addendum 3.1(5)). [2], [9], [22]

## 6. 실무 사례

| 기관 | 내용 | 출처 |
|---|---|---|
| 명지대학교(2026-02-20 공지) | 차세대 LMS(ASTRA Learning Platform)에 강의 요약, 영상·자료 분석 기반 핵심 요약, 시험 문항 초안 생성 등 AI 기능을 2026-1학기부터 적용한다고 공지한다. 공지 본문에는 교수 동의·옵트인 절차, 외부 제공자, 데이터 보관에 관한 문장이 없다. | [40] |
| 경희사이버대학교(2026-08 제안요청서) | 강의 교안·강의자료를 벡터 DB로 만들고 LMS와 연동하는 사업에서 "학습 데이터가 외부 상용 AI의 학습에 사용되지 않는 폐쇄형 구축", 대학 내부 LLM·임베딩·벡터 DB 서버로 "외부 API 의존 없이 폐쇄형으로 운영"을 요구한다. 요구사항에 "데이터 주권 및 외부 반출 통제(DAR-005)"가 있다. 교수 동의 절차는 확인한 범위에 없다. | [41] |
| Instructure(Canvas) | AI Features는 고객(기관)이 계정에서 활성화한 경우에만 정의상 해당한다(기관 단위 옵트인). 고객 승인 없이는 고객 콘텐츠를 AI 학습·개선에 쓰지 않는다(AI Supplement 2). 단, AI 기능에 직접 입력한 프롬프트와 파생 데이터는 Usage Data로 분류된다(같은 문서 6~7). 학생 데이터로 AI 모델을 학습하지 않으며, 제3자 기반 모델(Claude 3 Haiku 등)에 입력 데이터를 공유할 수 있고 기능별 "AI Nutrition Facts"를 공개한다. | [42], [43] |
| Moodle(4.5 이후 AI 서브시스템) | 이용자는 LMS에서 AI를 쓰기 전에 AI User Policy를 수락해야 한다. 미수락 이용자에게 정책을 표시하고 수락 전에는 기능을 쓸 수 없으며, 수락은 한 번이면 된다. 수락 시 컨텍스트 ID를 함께 기록한다. 이는 기능 사용 시점의 이용자 고지·수락이며 저작자(교수)에 대한 저작물 이용허락 절차와는 별개이다. | [44] |

교수 개인 단위로 강의자료의 AI 처리에 대한 동의·옵트인을 받는 국내 대학 공개 문서는 찾지 못했다(미확인).

## 7. 팀이 정해야 할 선택과 각 사실이 거는 제약

1. **제공자와의 법적 관계를 처리위탁으로 둘지.** 처리위탁이면 수탁자는 위탁 범위 밖 이용이 금지된다(PIPA 제26조⑤). DeepSeek 이용약관 4.3, Deploy Terms 3.1·AI Addendum 3.1(5), CLOVA 약관 제4조②·제10조①은 제공자 자신의 개선·홍보 목적 이용을 약관에 둔다. `[해석]` 이 조항이 그대로 적용되는 상태에서 개인정보가 섞인 입력을 보내면 위탁 범위를 넘는 이용이 생길 수 있어, 개인정보를 보내는 경로는 이 조항을 배제하는 계약(DPA 등) 없이는 위탁 구성이 성립하기 어렵다. 개인정보위 안내서는 기업용 API·DPA로 재이용 금지를 확보하라고 권장한다. [2], [3], [9], [22], [27], [29]
2. **국외 이전을 허용할지.** DeepSeek(중국 서버), Hive/Deploy(미국 운영), Bedrock Geo·Global 라우팅은 개인정보가 포함되면 제28조의8①의 이전에 해당한다. Bedrock 서울 In-Region(Claude Opus 5·Sonnet 5)은 리전 내 처리로 확인된다. 이전을 허용하면 제28조의8①의 근거 하나와 제2항 고지 항목, 제26조② 수탁자 공개가 필요하다. `[해석]` 제3호(계약 체결·이행)는 "정보주체와의 계약"을 요건으로 하므로 학생·교수와의 관계가 이에 해당하는지는 별도 검토 대상이다. DeepSeek은 2025-04 국외 이전 근거 미비로 시정권고를 받은 이력이 있다. [1], [4], [11], [16], [20], [26]
3. **학생 이름 등 개인정보를 전송 전에 제거할지, 포함을 허용할지.** Deploy는 개인정보 제출에 사전 서면 동의와 표시를 요구하고, CLOVA는 개인정보 입력을 금지하며, 교육부 가이드라인(비구속)은 식별 가능한 개인정보 입력을 금지하고, 국정원 가이드북(권고)은 AI-DLP 입력 필터링을 제시한다. 포함을 허용하는 선택은 Deploy·CLOVA 약관과 충돌한다. [9], [10], [22], [33], [34]
4. **보관 기간 요구값.** Bedrock은 `none` 모드로 무보관을 설정할 수 있고 SCP로 강제할 수 있다. Hive는 기본 14일이며 무보관은 요청 사항이다. DeepSeek은 API 보관 기간이 문서에 없다. CLOVA는 종료 후 5일 삭제와 1년 보관 예외를 둔다. 공공 고시 제14조는 이용 종료 시 저장정보 반환·파기와 파기 증빙 확보를 요구한다. 보관 기간을 정책값으로 정하면 DeepSeek은 문서상 검증할 수단이 없다. [1], [7], [12], [22], [36]
5. **학습 이용 금지 요구 여부.** Bedrock은 학습 미이용을 공식 문서로 밝힌다. DeepSeek은 비식별 후 이용과 앱 토글 방식 거부만 문서화되어 있고 API 적용은 미확인이다. Deploy는 개발·개선 목적의 영구 라이선스와 Hive Workers 수작업 검토를 기본으로 두고 서면 합의로만 배제한다. CLOVA는 품질·기능 개선 이용을 둔다. 학습 금지를 요구하면 Bedrock 외에는 별도 계약 협상이 전제가 된다. [3], [9], [15], [22]
6. **교수 동의를 옵트인으로 받을지, 옵트아웃으로 둘지, 동의의 범위.** KERIS 보고서의 일반적 견해에 따르면 강의자료의 저작자는 대개 교수이며, 대학이 저작권을 일방적으로 가져올 수 없다. 이용허락은 허락받은 방법·조건 범위 안에서만 유효하다(제46조②). 제공자 약관은 권리 보유를 고객이 보장하게 하고, CLOVA는 허락 없는 저작물 입력을 금지한다. `[해석]` 따라서 동의 문서에는 전송 대상 제공자, 국가, 보관·학습 이용 조건, 요약물 생성(2차적저작물 가능성)을 포함해야 허락 범위와 실제 처리가 일치한다. 옵트아웃 방식이 저작권법상 유효한 허락이 되는지는 확인한 1차 자료가 없다. 교육부 가이드라인(비구속)은 "비공개 교육 자료"를 외부 AI 도구에 입력하지 말라고 한다. [9], [22], [33], [38], [39]
7. **보안 통제 수준.** 고시 제11조②는 CSAP 인증 서비스 우선 고려를 요구하지만 ③은 대학의 교육 현장 정보시스템을 제외할 수 있게 한다. 같은 조 ①의 국가 정보보안 기본지침·N2SF 준수는 제외 대상이 아니다. 국정원 가이드북(권고)은 상용 AI API 연계 시 공개등급 데이터, 입·출력 필터링, 로깅, 중계서버 경계보안을 제시한다. [34], [36]
8. **OpenAI 호환 인터페이스 유지 여부.** 서울 In-Region으로 확인된 Bedrock 비전 모델은 Chat Completions를 지원하지 않고 서울에는 bedrock-mantle이 없다. 서울 In-Region을 요건으로 두면 Converse·Messages API 어댑터가 필요하다. CLOVA는 OpenAI 호환 경로가 있으나 이미지 입력 지원은 v3 고유 API에서만 확인된다. [17], [18], [19], [24], [25]
9. **계약상 분쟁 관할 수용 여부.** DeepSeek은 중국 본토 법·항저우 관할, Deploy는 캘리포니아주법·JAMS 중재이다. 국가기관 계약에서 외국 법 준거·중재 조항을 수용할 수 있는지는 대학 계약 규정 검토 사항이다(대학 규정 미확인). [2], [9]
10. **처리방침·고지 범위.** 위탁이면 수탁자 공개(제26조②), 국외 이전이면 제28조의8② 항목 공개·고지가 필요하다. DeepSeek ToS 3.3도 최종 이용자 고지를 개발자 의무로 둔다. Moodle 방식(사용 시점 정책 수락)은 이용자 고지 수단의 한 예이다. [2], [26], [27], [44]

## 8. 확인하지 못한 항목

- DeepSeek: API 입력·출력 보관 기간, API 계정의 학습 opt-out 방법, 기업·무보관 계약 존재 여부.
- Hive/Deploy: LLM 경로의 기본 보관 기간이 FAQ의 14일과 같은지, DeepSeek 인프라로 전달하지 않는다는 명시 진술, sub-processor 목록, 지역 고정 계약 조건.
- Bedrock: 서울 In-Region에서 쓸 수 있는 비전 모델의 전체 목록(Claude Opus 5·Sonnet 5 외), 두 모델의 `allowed_modes`에 `none`이 포함되는지(문서는 Claude Fable 계열만 보관 필수로 명시).
- CLOVA Studio: 데이터 처리 위치, 재수탁자, 요청 단위 보관 기간, 학습 이용 여부, 2025-07 이후 약관 개정, OpenAI 호환 경로의 이미지 입력.
- 개인정보위 생성형 AI 안내서의 2025-08 이후 개정판, 「공공 AX 프라이버시 보호 안내서」 원문.
- KERIS의 대학 생성형 AI 지침, 국립대 교수 강의자료 귀속에 관한 국내 판례, 서울과학기술대학교의 저작권·원격수업 규정.
- 교수 단위 AI 처리 동의 절차를 공개한 국내 대학 문서.

## 9. 출처 장부

| # | 제목 | 발행 주체 | 날짜 | URL |
|---|---|---|---|---|
| 1 | DeepSeek 개인정보 처리방침(ko-KR) | Hangzhou DeepSeek AI | 2026-05-06 최종 업데이트 | https://cdn.deepseek.com/policies/ko-KR/deepseek-privacy-policy.html (en-US 주소가 ko-KR로 이동) |
| 2 | DeepSeek Open Platform Terms of Service | Hangzhou DeepSeek AI | 2026-04-22 공개, 2026-04-29 시행 | https://cdn.deepseek.com/policies/en-US/deepseek-open-platform-terms-of-service.html |
| 3 | DeepSeek 이용약관(ko-KR) | Hangzhou DeepSeek AI | 2025-04-28 최종 업데이트 | https://cdn.deepseek.com/policies/ko-KR/deepseek-terms-of-use.html |
| 4 | 개인정위 제9회 전체회의 결과(딥시크 사전 실태점검 브리핑) | 개인정보보호위원회(정책브리핑) | 2025-04-24 | https://www.korea.kr/news/policyNewsView.do?newsId=156686001 |
| 5 | Chat Completions (OpenAI-Compatible LLMs) | Hive | 2026-10-17 갱신 | https://docs.thehive.ai/docs/chat-completions-openai-compatible-llms |
| 6 | DeepSeek V4.1 Flash-1017 | Hive | 2026-10-17 갱신 | https://docs.thehive.ai/docs/deepseek-v41-flash |
| 7 | Frequently Asked Questions (FAQ) | Hive | 2025-11-13 갱신 | https://docs.thehive.ai/docs/frequently-asked-questions-faq |
| 8 | Our Commitments to Enterprise Trust & Safety | Hive | 날짜 미표기 | https://thehive.ai/enterprise-commitments |
| 9 | Terms of Use(AI Service Addendum, Labeling Service Addendum 포함) | Castle Global, Inc.(Deploy) | 2026-10-26 개정 | https://thehive.ai/terms-of-use → https://deploy.com/terms-of-use |
| 10 | Customer Agreement | Castle Global, Inc.(Deploy) | 2026-10-23 갱신 | https://thehive.ai/customer-agreement → https://deploy.com/customer-agreement |
| 11 | Privacy Policy | Castle Global, Inc.(Deploy) | 2026-10-23 갱신 | https://thehive.ai/privacy → https://deploy.com/privacy |
| 12 | Data retention – Amazon Bedrock | AWS | 날짜 미표기(2026-11-11 열람) | https://docs.aws.amazon.com/bedrock/latest/userguide/data-retention.html |
| 13 | Data protection – Amazon Bedrock | AWS | 날짜 미표기(2026-11-11 열람) | https://docs.aws.amazon.com/bedrock/latest/userguide/data-protection.html |
| 14 | Amazon Bedrock abuse detection | AWS | 날짜 미표기(2026-11-11 열람) | https://docs.aws.amazon.com/bedrock/latest/userguide/abuse-detection.html |
| 15 | Does Amazon Bedrock share input and output data with model providers or use the data to train models? | AWS(re:Post, AWS Official) | 2026-09-23 검토 | https://repost.aws/knowledge-center/amazon-bedrock-model-data-use |
| 16 | Amazon Bedrock expands Claude model availability to India, South Korea, and Singapore | AWS | 2026-09-29 | https://aws.amazon.com/about-aws/whats-new/2026/09/claude-region-expansion-in-sk/ |
| 17 | Claude Sonnet 5 – Amazon Bedrock 모델 카드 | AWS | 날짜 미표기(2026-11-11 열람) | https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-anthropic-claude-sonnet-5.html |
| 18 | Claude Opus 5 – Amazon Bedrock 모델 카드 | AWS | 날짜 미표기(2026-11-11 열람) | https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-anthropic-claude-opus-5.html |
| 19 | Regional availability by endpoints | AWS | 날짜 미표기(2026-11-11 열람) | https://docs.aws.amazon.com/bedrock/latest/userguide/endpoints-region-availability.html |
| 20 | Regional availability by models | AWS | 날짜 미표기(2026-11-11 열람) | https://docs.aws.amazon.com/bedrock/latest/userguide/models-region-compatibility.html |
| 21 | AWS Sub-processors | AWS | 2026-10-30 갱신 | https://aws.amazon.com/compliance/sub-processors/ |
| 22 | 네이버 클라우드 플랫폼 CLOVA Studio 서비스 이용약관(민간) | 네이버클라우드 | 2025-07-17 시행 | https://xv-ncloud.pstatic.net/images/provision/250717_%EB%AF%BC%EA%B0%84_CLOVAStudio%EC%84%9C%EB%B9%84%EC%8A%A4%EC%9D%B4%EC%9A%A9%EC%95%BD%EA%B4%80%EC%B4%88%EC%95%88_%EB%8D%B0%EC%9D%B4%ED%84%B0%EC%82%AD%EC%A0%9C%EA%B8%B0%EA%B0%84%EB%AA%85%EC%8B%9C_(NCCCLEAN)_1752468765285.pdf |
| 23 | 네이버 클라우드 플랫폼[공공기관용] CLOVA Studio 서비스 이용약관 | 네이버클라우드 | 2024-12-19 시행 | https://xv-ncloud.pstatic.net/images/provision/241219_[%EA%B3%B5%EA%B3%B5]CLOVAStudio%EC%84%9C%EB%B9%84%EC%8A%A4%EC%9D%B4%EC%9A%A9%EC%95%BD%EA%B4%80_(CLEAN)_1734488830380.pdf |
| 24 | OpenAI compatibility – CLOVA Studio API | 네이버클라우드 | 2026-09-17 갱신 | https://api.ncloud-docs.com/docs/en/clovastudio-openaicompatibility |
| 25 | 텍스트 및 이미지(Chat Completions v3) – CLOVA Studio API | 네이버클라우드 | 2026-09-17 갱신 | https://api.ncloud-docs.com/docs/clovastudio-chatcompletionsv3 |
| 26 | 개인정보 보호법 제28조의7~제28조의11 | 국가법령정보센터 | 법률 제21445호, 2026-09-11 시행 | https://www.law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1029334869 |
| 27 | 개인정보 보호법 제26조 | 국가법령정보센터 | 법률 제21445호, 2026-09-11 시행 | https://www.law.go.kr/LSW/lsLawLinkInfo.do?chrClsCd=010202&lsId=011357&lsJoLnkSeq=900079061&print=print |
| 28 | 개인정보위, 생성형 AI 개발·활용 '개인정보 처리 기준' 공개 | 개인정보보호위원회(정책브리핑) | 2025-08-06 | https://www.korea.kr/news/policyNewsView.do?newsId=148947194 |
| 29 | 생성형 인공지능(AI) 개발·활용을 위한 개인정보 처리 안내서(안) | 개인정보보호위원회(외부 게시본) | 2025-08 | https://itskorea.kr/downloadFile.do?fileId=FILE_000000000105330&fileSn=3 |
| 30 | 공공기관 AI 전환 가속…개보위, '프라이버시 가이드라인' 마련 | 연합뉴스(보도) | 2026-07-23 | https://www.yna.co.kr/view/AKR20260722151700530 |
| 31 | 「대학 AI 활용 윤리 가이드라인」 안내 | 교육부 | 2026-08-24 | https://www.moe.go.kr/boardCnts/viewRenew.do?boardID=337&boardSeq=107003&lev=0&m=0303&opType=N&page=1&s=moe&searchType=null&statusYN=W |
| 32 | 「대학 AI 활용 윤리 가이드라인」 배포 안내(공문) | 교육부(서울사이버대 게시본) | 2026-08-24 시행 | https://grad.iscu.ac.kr/attach//potl/board//202608261503203551409.pdf |
| 33 | 대학 AI 활용 윤리 가이드라인(본문) | 교육부·한국대학교육협의회(중앙대 게시본) | 2026-08 | https://aiguide.cau.ac.kr/files/College_AI_Guide.pdf |
| 34 | 국가·공공기관 AI보안 가이드북 2.0 | 국가정보원(한국보건복지인재원 게시본) | 2025-12 | http://cdn.kohi.or.kr/AI%EB%B0%B0%EC%9B%80ON/2026/%EA%B5%90%EC%9C%A1%EC%9E%90%EB%A3%8C%EC%8B%A4/data_0006.pdf |
| 35 | 생성형 인공지능 서비스 활용 시 보안 주의사항 안내(공문) | 행정안전부 | 본문 날짜 미표기, 게시 경로 2026-09-21 | https://dams.pa.go.kr/dams/DOCUMENT/2026/09/21/DOC/SRC/0A04202609211126192653933015128.pdf |
| 36 | 행정기관 및 공공기관의 클라우드컴퓨팅서비스 이용 기준 및 안전성 확보 등에 관한 고시 | 행정안전부(U-LEX 게시본) | 고시 제2025-79호, 2025-12-29 시행 | https://www.ulex.co.kr/%EB%B2%95%EB%A5%A0/2100000270616-81348-%ED%96%89%EC%A0%95%EA%B8%B0%EA%B4%80%EB%B0%8F |
| 37 | 저작권법 제9조 | 국가법령정보센터 | 법률 제21595호, 2026-10-29 시행 | https://www.law.go.kr/LSW/lsLinkProc.do?efYd=20261029&joNo=000900&lsNm=%EC%A0%80%EC%9E%91%EA%B6%8C%EB%B2%95&mode=11 |
| 38 | 저작권법 제16조, 제22조, 제35조의2, 제46조 | 국가법령정보센터 | 법률 제21595호, 2026-10-29 시행 | https://www.law.go.kr/LSW/lsLinkProc.do?efYd=20261029&joNo=001600&lsNm=%EC%A0%80%EC%9E%91%EA%B6%8C%EB%B2%95&mode=11 (joNo=002200, 003502, 004600 동일 형식) |
| 39 | 대학 공개강의 저작권 관리 방안(KERIS 이슈리포트 RR 2015-1) | 한국교육학술정보원(연구진 견해) | 2015 | http://elearning.kocw.net/copyright/kocw_copyright1.pdf |
| 40 | 2026-1 LMS 고도화 및 AI협업기능 도입 안내문 | 명지대학교 원격교육센터 | 2026-02-20 | https://record.mju.ac.kr/bbs/mjukr/143/229379/artclView.do |
| 41 | AI 강의 교안 제작 시스템 및 생성형 AI 구축 제안요청서 | 경희사이버대학교(나라장터) | 2026-08 | https://www.g2b.go.kr/pn/pnp/pnpe/UntyAtchFile/downloadFile.do?bidPbancNo=R26BK01689897&bidPbancOrd=000&fileSeq=2&fileType=&prcmBsneSeCd=03 |
| 42 | Artificial Intelligence Privacy Notice | Instructure | 2025-10-27 갱신 | https://www.instructure.com/policies/artificial-intelligence-privacy |
| 43 | Artificial Intelligence Supplement | Instructure | 2026-04-15 시행 | https://www.instructure.com/policies/artificial-intelligence-supplement |
| 44 | AI Subsystem(Moodle 5.2 개발자 문서) | Moodle | 날짜 미표기(2026-11-11 열람) | https://moodledev.io/docs/5.2/apis/subsystems/ai |
