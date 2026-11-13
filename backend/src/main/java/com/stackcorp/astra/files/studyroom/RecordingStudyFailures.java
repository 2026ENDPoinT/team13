package com.stackcorp.astra.files.studyroom;

import java.util.Map;

/**
 * 실패한 녹음 작업이 화면에 보이는 문장(ADR-0041). 원장에는 코드만 저장하고 읽을 때 이 표로 바꾼다. 제공자 응답, 예외 class 이름,
 * 주소, key가 문장에 들어갈 자리가 없다. 모르는 코드는 {@link #INTERNAL}의 문장이다. {N}이 있는 문장은 지금 설정값을 넣는다.
 */
final class RecordingStudyFailures {

	static final String REJECTED = "rejected";

	static final String NOT_CONFIGURED = "not_configured";

	static final String TOO_LONG = "too_long";

	static final String LENGTH_MISMATCH = "length_mismatch";

	static final String DAILY_LIMIT = "daily_limit";

	static final String MONTHLY_LIMIT = "monthly_limit";

	static final String UNREADABLE_AUDIO = "unreadable_audio";

	static final String STT_CONFIG = "stt_config";

	static final String STT_UNAVAILABLE = "stt_unavailable";

	static final String TRANSCRIPT_FAILED = "transcript_failed";

	static final String NO_SPEECH = "no_speech";

	static final String MODEL_CONFIG = "model_config";

	static final String MODEL_UNAVAILABLE = "model_unavailable";

	static final String INVALID_OUTPUT = "invalid_output";

	static final String INTERRUPTED = "interrupted";

	static final String INTERNAL = "internal";

	private static final Map<String, String> TEXTS = Map.ofEntries(
			Map.entry(REJECTED, "악성코드 검사에서 거부된 녹음이라 처리하지 않았습니다."),
			Map.entry(UNREADABLE_AUDIO, "음성을 인식할 수 없는 오디오라 전사하지 못했습니다."),
			Map.entry(LENGTH_MISMATCH, "녹음의 실제 길이가 올릴 때 알린 길이와 달라 전사 결과를 보여 주지 않습니다. 다시 올려 주세요."),
			Map.entry(STT_CONFIG, "음성 인식 서비스 설정에 문제가 있어 전사하지 못했습니다. 관리자에게 알려 주세요."),
			Map.entry(STT_UNAVAILABLE, "음성 인식 서비스가 응답하지 않아 전사하지 못했습니다."),
			Map.entry(TRANSCRIPT_FAILED, "전사를 끝내지 못해 요약을 만들지 않았습니다."),
			Map.entry(NO_SPEECH, "녹음에서 말소리를 찾지 못해 요약을 만들지 않았습니다."),
			Map.entry(MODEL_CONFIG, "요약 모델 설정에 문제가 있어 요약을 만들지 못했습니다. 관리자에게 알려 주세요."),
			Map.entry(MODEL_UNAVAILABLE, "요약 모델이 응답하지 않아 요약을 만들지 못했습니다."),
			Map.entry(INVALID_OUTPUT, "요약 모델의 결과가 형식에 맞지 않아 요약을 만들지 못했습니다."),
			Map.entry(INTERRUPTED, "처리하던 서버가 멈춰 끝내지 못했습니다."),
			Map.entry(INTERNAL, "서버 오류로 처리하지 못했습니다."));

	private RecordingStudyFailures() {
	}

	static String message(RecordingJobKind kind, String code, RecordingStudyProperties properties) {
		if (code == null) {
			return TEXTS.get(INTERNAL);
		}
		return switch (code) {
			case NOT_CONFIGURED -> kind == RecordingJobKind.TRANSCRIPTION
					? "이 서비스에서 녹음 전사를 쓰지 않도록 설정이 바뀌어 전사하지 않았습니다."
					: "이 서비스에서 요약 모델을 쓰지 않도록 설정이 바뀌어 요약하지 않았습니다.";
			case TOO_LONG -> "녹음이 " + properties.maxRecordingMinutes() + "분보다 길어 전사하지 않았습니다.";
			case DAILY_LIMIT -> "오늘 전사할 수 있는 녹음 시간(" + properties.dailyMinutes()
					+ "분)을 넘어 전사하지 않았습니다. 내일 다시 올리면 전사합니다.";
			case MONTHLY_LIMIT -> "이번 달 전사할 수 있는 녹음 시간(" + properties.monthlyMinutes() + "분)을 넘어 전사하지 않았습니다.";
			default -> TEXTS.getOrDefault(code, TEXTS.get(INTERNAL));
		};
	}

}
