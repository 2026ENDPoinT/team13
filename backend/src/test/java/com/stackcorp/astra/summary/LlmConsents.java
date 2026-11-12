package com.stackcorp.astra.summary;

import java.util.List;

import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.stackcorp.astra.TestData;
import com.stackcorp.astra.summary.policy.LlmGate;
import com.stackcorp.astra.summary.policy.ProcessingScope;
import com.stackcorp.astra.summary.policy.ProviderProfile;

/**
 * 동의 API를 시험하지 않는 테스트가 교수자의 외부 LLM 처리 동의를 미리 넣는다. 지금 배포의 profile 지문으로 넣으므로 문이 열린다.
 * 동의 API 자체는 ConsentApiTests가 시험한다.
 */
public final class LlmConsents {

	private LlmConsents() {
	}

	/** @param scopes 비우면 모든 범위 */
	public static void grant(ApplicationContext context, long courseId, long instructorUserId,
			ProcessingScope... scopes) {
		ProviderProfile profile = context.getBean(LlmGate.class).profile();
		JdbcClient jdbc = context.getBean(JdbcClient.class);
		for (ProcessingScope scope : scopes.length == 0 ? ProcessingScope.values() : scopes) {
			jdbc.sql("""
					INSERT INTO llm_consents (course_id, instructor_user_id, processing_scope, notice_version, profile_sha256,
					                          provider_name, provider_operator, processing_region, retention_days, training_use,
					                          cross_border_transfer, risk_acknowledged)
					VALUES (:course, :user, :scope, :notice, :sha, :name, :operator, :region, :retention, :training,
					        :transfer, 1)
					""")
				.param("course", courseId)
				.param("user", instructorUserId)
				.param("scope", scope.value())
				.param("notice", profile.noticeVersion())
				.param("sha", profile.digest())
				.param("name", profile.providerName())
				.param("operator", profile.providerOperator())
				.param("region", profile.processingRegion())
				.param("retention", profile.retentionDays())
				.param("training", profile.trainingUse())
				.param("transfer", profile.crossBorderTransfer())
				.update();
		}
	}

	/** 과목 하나와 그 담당 교수자를 만들고 모든 범위에 동의한다. @return {과목 ID, 교수자 ID} */
	public static List<Long> consentedCourse(ApplicationContext context, TestData data) {
		long course = data.course();
		long professor = data.user("PROFESSOR");
		data.instructor(course, professor);
		grant(context, course, professor);
		return List.of(course, professor);
	}

}
