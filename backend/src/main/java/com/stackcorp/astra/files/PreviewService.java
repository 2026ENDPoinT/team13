package com.stackcorp.astra.files;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.stackcorp.astra.files.ConversionRecords.Job;
import com.stackcorp.astra.files.FileRecords.FileRow;
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
	 * @param key READY일 때 rendition key
	 * @param pageCount READY일 때 쪽 수
	 * @param code FAILED일 때 실패 코드
	 */
	record Preview(State state, String key, Integer pageCount, String code) {
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
					: new Preview(State.FAILED, null, null, job.errorCode());
		};
	}

}
