# ADR-0008 LLM은 port 뒤에 두고 DeepSeek로 시작한다

## 상태
채택 (2026-11-11). 데이터 처리 정책의 일부를 [ADR-0032](0032-llm-consent-and-study-chat.md)가 정했다(근거: [docs/research/llm-data-policy.md](../research/llm-data-policy.md)).

- 정한 것: 배포가 제공자 처리 조건(지역, 보관, 학습 이용, 국외 이전, 고지 판)을 설정으로 밝히고 API로 보여 준다. 과목 자료는 담당 교수자가 그 조건에 범위별로 동의한 뒤에만 보낸다. 국외 이전이나 학습 이용이 있으면 따로 확인받는다. 조건이 바뀌면 다시 동의받는다. 철회하면 새 전송이 멈춘다. 배포 스위치로 외부 호출을 모두 끈다. 보내는 글은 가림 hook을 거친다. 사용량을 과목별로 남긴다.
- 아직 정하지 않은 것: 제공자와의 계약 형태(처리위탁·DPA, 학습 이용 배제, 무보관), 국외 이전의 법적 근거, 처리방침과 학생 고지 문구, 고지 문구 본문, 학생 이름이 든 자료의 처리, 보존 기간. 학교 자료에 쓰기 전에 학교와 정한다.

## 맥락
쪽별 요약([ADR-0006](0006-summary-module.md))은 강의 자료의 쪽 이미지와 텍스트를 외부 모델에 보낸다. [SC-08](https://github.com/STack-Corp/ASTRA-Reference/blob/main/backend/00_server_contract.md)은 기관 자료를 외부 LLM에 보내려면 별도 데이터 처리 정책이 필요하다고 정한다.

첫 제공자는 DeepSeek로 한다. 정책은 뒤에 정한다. [DeepSeek API 문서](https://api-docs.deepseek.com/quick_start/pricing)에서 2026-11-11에 확인한 내용은 다음과 같다.

| 항목 | 내용 |
|---|---|
| 형식 | OpenAI Chat Completions 호환. base URL `https://api.deepseek.com` |
| `deepseek-flash` | 이미지 입력(base64 data URL, JPEG·PNG)과 JSON output을 지원한다 |
| `deepseek-v4-pro` | 이미지 입력을 지원하지 않는다 |

## 결정
1. `summary`가 공개 API에 `LlmClient` port를 정의한다. 요청은 system 문구, text와 image로 된 message, 출력 token 상한, 용도다. 응답은 text, 종료 사유, token 사용량이다. `summary`는 제공자, key, 주소를 알지 못한다.
2. `integration`이 port를 구현한다. 첫 adapter는 OpenAI Chat Completions 호환 adapter 하나다. base URL, model, key는 설정으로 받는다.
3. 기본 설정은 `https://api.deepseek.com`과 `deepseek-flash`다. 쪽 이미지를 보내야 하므로 이미지 입력을 지원하는 model만 쓴다.
4. 외부 호출은 SC-08을 따른다. 허용 host는 설정한 base URL 하나다. redirect를 따르지 않고, timeout과 응답 크기 상한, 동시 호출 상한을 둔다. key는 Secrets Manager에서 읽는다.
5. 모델 응답은 신뢰하지 않는 입력이다. 검사를 통과한 결과만 저장한다. 생성된 Markdown의 이미지와 raw HTML은 렌더하지 않는다(CHG-07).
6. 보내는 내용은 자료 제목, 쪽 이미지, 쪽 텍스트다. 사용자 식별자, 수강 정보, session 값은 보내지 않는다. 교수자가 생성을 시작한 자료만 보낸다.
7. 작업마다 model 이름, prompt 판, 호출 수, token 사용량, 실패 code를 기록한다(SC-09).

## 결과와 트레이드오프
- 제공자를 바꿀 때 호환 형식이면 설정만 바꾸고, 아니면 adapter를 추가한다. `summary`는 바뀌지 않는다.
- 같은 prompt라도 model마다 결과 품질이 다르다. model을 바꾸면 검사 실패율과 결과를 다시 확인해야 한다.
- 교수자 동의 절차와 제공자 조건의 고지는 ADR-0032가 정했다. 계약과 법적 근거는 아직 없다([architecture.md](../architecture.md) 12절).
