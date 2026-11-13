package com.stackcorp.astra.summary.job;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.stackcorp.astra.files.api.RenditionException;

/**
 * 실패한 요약 작업이 화면에 보이는 문장({@code errorMessage}). 오류 코드와 그 세부 종류마다 정해 둔 문장만 쓴다. 예외 class 이름, 제공자
 * 응답 본문, 주소, key 조각은 넣지 않고, 기술 세부는 작업 ID와 함께 서버 로그에만 남긴다. 모델 제공자({@code provider})와 자료
 * 파일({@code file}) 실패만 바깥에서 온 문구를 담을 수 있었으므로, 이 문장으로 바뀌기 전에 저장된 행은 읽을 때 같은 문장으로 바꾼다.
 */
public final class FailureText {

	static final String FILE_CHANGED = "요청한 뒤 파일이 바뀌었습니다. 다시 요청해 주세요.";

	static final String FILE_DELETED = "자료 파일이 삭제됐습니다.";

	private static final String PROVIDER_DEFAULT = "모델 호출이 실패했습니다. 잠시 후 다시 시도해 주세요.";

	/** {@code LlmException.Code} 이름(소문자)마다. */
	private static final Map<String, String> PROVIDER = Map.of(
			"auth", "모델 제공자가 API key를 거부했습니다. 관리자에게 알려 주세요.",
			"rate_limit", "모델 제공자의 요청 한도에 걸렸습니다. 잠시 후 다시 시도해 주세요.",
			"server", "모델 제공자 서버에 오류가 났습니다. 잠시 후 다시 시도해 주세요.",
			"network", "모델 제공자에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.",
			"bad_request", "모델 제공자가 요청을 거부했습니다. 관리자에게 알려 주세요.",
			"protocol", "모델 제공자의 응답을 처리하지 못했습니다. 다시 시도해 주세요.");

	private static final String FILE_DEFAULT = "자료를 쪽 이미지로 바꾸지 못했습니다. 잠시 후 다시 시도해 주세요.";

	/** converter 실패({@code ConversionException.Code} 이름, 소문자)마다. 나머지는 {@link #FILE_DEFAULT}다. */
	private static final Map<String, String> CONVERSION = Map.of(
			"encrypted", "암호가 걸린 PDF라 쪽 이미지로 바꾸지 못했습니다. 암호를 푼 파일을 올려 주세요.",
			"invalid_pdf", "PDF 파일을 읽지 못했습니다. 파일이 손상되지 않았는지 확인해 주세요.",
			"too_many_pages", "쪽 수가 너무 많아 쪽 이미지로 바꾸지 못했습니다.",
			"empty", "쪽이 없는 파일입니다.",
			"too_large", "파일이 너무 커 쪽 이미지로 바꾸지 못했습니다.",
			"unsupported_format", "쪽 이미지로 바꿀 수 없는 형식입니다.",
			"timeout", "쪽 이미지로 바꾸는 데 시간이 너무 오래 걸렸습니다. 잠시 후 다시 시도해 주세요.");

	private static final Map<RenditionException.Code, String> RENDITION = Map.of(
			RenditionException.Code.NOT_FOUND, "자료 파일이 없습니다.",
			RenditionException.Code.NOT_READY, "검사를 마치지 않은 파일입니다.",
			RenditionException.Code.UNSUPPORTED, "쪽 이미지로 바꿀 수 없는 형식입니다.",
			RenditionException.Code.CONVERSION_FAILED, FILE_DEFAULT,
			RenditionException.Code.STORAGE, "자료의 쪽 이미지를 읽지 못했습니다. 잠시 후 다시 시도해 주세요.");

	private static final Set<String> PROVIDER_TEXTS = Stream
		.concat(PROVIDER.values().stream(), Stream.of(PROVIDER_DEFAULT))
		.collect(Collectors.toUnmodifiableSet());

	private static final Set<String> FILE_TEXTS = Stream
		.of(CONVERSION.values().stream(), RENDITION.values().stream(), Stream.of(FILE_DEFAULT, FILE_CHANGED, FILE_DELETED))
		.flatMap((s) -> s)
		.collect(Collectors.toUnmodifiableSet());

	// 이 문장으로 바뀌기 전의 저장 형식: "모델 호출이 실패했습니다 (network): <제공자 문구>", "<파일 문구> (encrypted)"
	private static final Pattern OLD_PROVIDER = Pattern.compile("^모델 호출이 실패했습니다 \\(([a-z_]+)\\)");

	private static final Pattern OLD_FILE_DETAIL = Pattern.compile("\\(([a-z_]+)\\)$");

	private FailureText() {
	}

	/** @param kind 실패한 모델 호출의 {@code LlmException.Code} 이름(소문자). 모르면 null */
	static String provider(String kind) {
		return kind == null ? PROVIDER_DEFAULT : PROVIDER.getOrDefault(kind, PROVIDER_DEFAULT);
	}

	/** @param detail {@link RenditionException#detail()}(converter 실패 코드). 없으면 null */
	static String file(RenditionException.Code code, String detail) {
		if (code == RenditionException.Code.CONVERSION_FAILED && detail != null) {
			return CONVERSION.getOrDefault(detail.toLowerCase(Locale.ROOT), FILE_DEFAULT);
		}
		return RENDITION.getOrDefault(code, FILE_DEFAULT);
	}

	/**
	 * 응답에 싣는 문장. 지금 저장하는 문장은 그대로 두고, 그 전에 저장된 {@code provider}와 {@code file} 문구는 같은 종류의 정해 둔
	 * 문장으로 바꾼다. 다른 코드의 문구는 처음부터 서버가 정한 문장이다.
	 */
	public static String display(String errorCode, String stored) {
		if (stored == null || errorCode == null) {
			return stored;
		}
		return switch (errorCode) {
			case "provider" -> {
				if (PROVIDER_TEXTS.contains(stored)) {
					yield stored;
				}
				Matcher old = OLD_PROVIDER.matcher(stored);
				yield old.find() ? provider(old.group(1))
						: stored.startsWith("모델 응답에 텍스트가 없습니다") ? provider("protocol") : PROVIDER_DEFAULT;
			}
			case "file" -> {
				if (FILE_TEXTS.contains(stored)) {
					yield stored;
				}
				Matcher old = OLD_FILE_DETAIL.matcher(stored);
				yield old.find() ? CONVERSION.getOrDefault(old.group(1), FILE_DEFAULT) : FILE_DEFAULT;
			}
			default -> stored;
		};
	}

}
