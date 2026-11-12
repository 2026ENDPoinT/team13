package com.stackcorp.astra.summary.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

/**
 * 제공자 profile의 지문과 확인 요구. 값이 하나라도 바뀌면 지문이 바뀌어 이전 동의가 효력을 잃는다.
 */
class ProviderProfileTests {

	private static ProviderProfile profile(Integer retention, boolean training, boolean transfer, String notice) {
		return new ProviderProfile("Bedrock", "Amazon Web Services Korea", "ap-northeast-2", retention, training,
				transfer, notice);
	}

	@Test
	void acknowledgementIsRequiredOnlyForTransferOrTrainingUse() {
		assertThat(profile(0, false, false, "v1").acknowledgementRequired()).isFalse();
		assertThat(profile(0, true, false, "v1").acknowledgementRequired()).isTrue();
		assertThat(profile(0, false, true, "v1").acknowledgementRequired()).isTrue();
	}

	@Test
	void digestChangesWithEveryField() {
		String base = profile(0, false, false, "v1").digest();
		assertThat(base).matches("[0-9a-f]{64}").isEqualTo(profile(0, false, false, "v1").digest());
		assertThat(profile(null, false, false, "v1").digest()).isNotEqualTo(base);
		assertThat(profile(0, true, false, "v1").digest()).isNotEqualTo(base);
		assertThat(profile(0, false, true, "v1").digest()).isNotEqualTo(base);
		assertThat(profile(0, false, false, "v2").digest()).isNotEqualTo(base);
		assertThat(new ProviderProfile("Bedrock", "Amazon Web Services Korea", "us-east-1", 0, false, false, "v1")
			.digest()).isNotEqualTo(base);
	}

	@Test
	void rejectsValuesThatCannotBeShownOrStored() {
		assertThatIllegalArgumentException().isThrownBy(() -> profile(-1, false, false, "v1"));
		assertThatIllegalArgumentException().isThrownBy(() -> profile(0, false, false, "v 1"));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new ProviderProfile(" ", "op", "KR", 0, false, false, "v1"));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new ProviderProfile("p", "op", "서울", 0, false, false, "v1"));
	}

}
