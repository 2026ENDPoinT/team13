package com.stackcorp.astra.summary.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 배포가 쓰는 LLM 제공자의 데이터 처리 조건. 운영자가 설정에 적고, API가 그대로 보여 주며, 교수자는 이 내용에 동의한다(ADR-0032).
 * 기본값은 기본 제공자인 DeepSeek API를 docs/research/llm-data-policy.md 1절의 확인 내용대로 적은 것이다. 제공자를 바꾸면 이
 * 값과 {@code noticeVersion}을 함께 바꾼다. 값이 하나라도 바뀌면 지문이 바뀌어 이전 동의는 효력을 잃는다(PIPA 제28조의8③).
 *
 * @param providerName 화면에 보일 제공자 이름
 * @param providerOperator 입력을 받는 계약 주체(PIPA 제28조의8② 수취자 명칭)
 * @param processingRegion 처리·저장 지역. 국가 코드나 클라우드 지역 이름
 * @param retentionDays 제공자 보관 일수. 0이면 보관하지 않는다. 비우면 제공자가 밝히지 않은 것이다
 * @param trainingUse 제공자가 입력을 자기 서비스 개선·학습에 쓸 수 있는지
 * @param crossBorderTransfer 입력이 국외로 이전되는지
 * @param noticeVersion 교수자에게 보여 주는 고지 문구의 판
 */
public record ProviderProfile(@DefaultValue("DeepSeek API") String providerName,
		@DefaultValue("Hangzhou DeepSeek Artificial Intelligence Co., Ltd.") String providerOperator,
		@DefaultValue("CN") String processingRegion, Integer retentionDays, @DefaultValue("true") boolean trainingUse,
		@DefaultValue("true") boolean crossBorderTransfer, @DefaultValue("deepseek-2026-11-11") String noticeVersion) {

	private static final Pattern REGION = Pattern.compile("[A-Za-z0-9-]{2,40}");

	private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9._-]{1,40}");

	public ProviderProfile {
		if (providerName == null || providerName.isBlank() || providerName.length() > 100) {
			throw new IllegalArgumentException("astra.llm.policy.provider-name은 1~100자여야 합니다.");
		}
		if (providerOperator == null || providerOperator.isBlank() || providerOperator.length() > 200) {
			throw new IllegalArgumentException("astra.llm.policy.provider-operator는 1~200자여야 합니다.");
		}
		if (processingRegion == null || !REGION.matcher(processingRegion).matches()) {
			throw new IllegalArgumentException("astra.llm.policy.processing-region은 영문·숫자·-로 된 2~40자여야 합니다.");
		}
		if (retentionDays != null && retentionDays < 0) {
			throw new IllegalArgumentException("astra.llm.policy.retention-days는 0 이상이어야 합니다.");
		}
		if (noticeVersion == null || !VERSION.matcher(noticeVersion).matches()) {
			throw new IllegalArgumentException("astra.llm.policy.notice-version은 [A-Za-z0-9._-] 1~40자여야 합니다.");
		}
	}

	/** 국외 이전이나 학습 이용이 있으면 동의할 때 그 사실을 따로 확인받는다. */
	public boolean acknowledgementRequired() {
		return this.crossBorderTransfer || this.trainingUse;
	}

	/** 동의 행에 남기고 동의 요청과 대조하는 지문. 모든 필드를 고정한 순서로 이어 SHA-256을 만든다. */
	public String digest() {
		String canonical = String.join("\n", "astra-llm-profile-1", this.providerName, this.providerOperator,
				this.processingRegion, String.valueOf(this.retentionDays), String.valueOf(this.trainingUse),
				String.valueOf(this.crossBorderTransfer), this.noticeVersion);
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
