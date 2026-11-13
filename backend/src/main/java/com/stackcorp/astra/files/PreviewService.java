package com.stackcorp.astra.files;

import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.stackcorp.astra.files.ConversionRecords.Job;
import com.stackcorp.astra.files.FileRecords.FileRow;
import com.stackcorp.astra.files.api.ConversionException;
import com.stackcorp.astra.shared.ApiException;

/**
 * 문서 미리보기의 상태(ADR-0033). 권한은 호출한 쪽이 먼저 판정한다. 변환이 필요하면 대기열에 넣고 바로 돌아온다. 변환은 worker가
 * 한다. 같은 파일 버전의 요청이 여럿이어도 대기열에는 하나만 들어간다(DB 유일 키).
 */
@Service
class PreviewService {

	private final FileRecords files;

	private final ConversionRecords conversions;

	private final DocumentRenditionService renditions;

	PreviewService(FileRecords files, ConversionRecords conversions, DocumentRenditionService renditions) {
		this.files = files;
		this.conversions = conversions;
		this.renditions = renditions;
	}

	enum State {
		READY, CONVERTING, FAILED, UNSUPPORTED
	}

	/**
	 * 미리보기 실패 코드(ADR-0033). 응답의 {@code code}는 이 값뿐이다. converter와 worker의 실패 종류는 모두 이 중 하나로 바뀐다.
	 * {@code conversion_jobs.error_code}에 남은 내부 값을 그대로 내보내지 않는다.
	 */
	enum Failure {

		/** 암호가 걸린 문서. */
		ENCRYPTED,
		/** 문서로 읽을 수 없는 파일(깨진 PDF, 쪽 크기가 PDF 사양 밖). */
		INVALID_DOCUMENT,
		/** 쪽이 없는 문서. */
		EMPTY,
		/** 쪽 수가 상한({@value DocumentRenditionService#MAX_PAGES}쪽)을 넘는다. */
		TOO_MANY_PAGES,
		/** 원본이나 변환 결과가 크기 상한을 넘는다. */
		TOO_LARGE,
		/** 변환이 제한 시간을 넘었다. */
		TIMEOUT,
		/** converter나 저장소 장애가 정해진 시도 수 동안 이어졌다. 문서 문제가 아니다. */
		UNAVAILABLE,
		/** 그 밖의 변환 실패(office 문서를 PDF로 바꾸지 못함, converter 응답이 맞지 않음). */
		FAILED;

		/** 응답의 code 값. */
		String code() {
			return name().toLowerCase(Locale.ROOT);
		}

		/**
		 * conversion_jobs.error_code(ConversionException.Code의 소문자 이름이나 storage)를 응답 코드로 바꾼다. 모르는 값은
		 * FAILED다. unsupported_format은 실패가 아니라 unsupported 상태라서 여기로 오지 않는다.
		 */
		static Failure of(String errorCode) {
			if ("storage".equals(errorCode)) {
				return UNAVAILABLE;
			}
			ConversionException.Code code;
			try {
				code = ConversionException.Code.valueOf(String.valueOf(errorCode).toUpperCase(Locale.ROOT));
			}
			catch (IllegalArgumentException ex) {
				return FAILED;
			}
			return switch (code) {
				case ENCRYPTED -> ENCRYPTED;
				case INVALID_PDF -> INVALID_DOCUMENT;
				case EMPTY -> EMPTY;
				case TOO_MANY_PAGES -> TOO_MANY_PAGES;
				case TOO_LARGE -> TOO_LARGE;
				case TIMEOUT -> TIMEOUT;
				case UNAVAILABLE, INTERRUPTED -> UNAVAILABLE;
				case FAILED, UNSUPPORTED_FORMAT -> FAILED;
			};
		}

	}

	/**
	 * @param key READY일 때 rendition key
	 * @param pageCount READY일 때 쪽 수
	 * @param failure FAILED일 때 실패 코드
	 */
	record Preview(State state, String key, Integer pageCount, Failure failure) {
	}

	/**
	 * @throws ApiException 파일이 없거나, 지웠거나, 검사를 통과하지 않았으면 404
	 */
	Preview open(long fileId, long userId) {
		FileRow file = this.files.find(fileId).filter(FileRow::ready).orElseThrow(ApiException::notFound);
		if (file.previewFormat().isEmpty()) {
			return new Preview(State.UNSUPPORTED, null, null, null);
		}
		Optional<Job> latest = this.conversions.latest(fileId, file.versionNo());
		if (latest.isEmpty()) {
			this.renditions.request(file, userId);
			latest = this.conversions.latest(fileId, file.versionNo());
		}
		// 넣은 직후에 비어 있으면 그 사이에 파일이 지워져 GC가 닫은 것이다.
		Job job = latest.orElseThrow(ApiException::notFound);
		return switch (job.state()) {
			case READY -> new Preview(State.READY, job.key(), job.pageCount(), null);
			case REQUESTED, CONVERTING -> new Preview(State.CONVERTING, null, null, null);
			case FAILED -> "unsupported_format".equals(job.errorCode()) ? new Preview(State.UNSUPPORTED, null, null, null)
					: new Preview(State.FAILED, null, null, Failure.of(job.errorCode()));
		};
	}

}
