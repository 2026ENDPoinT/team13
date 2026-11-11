package com.stackcorp.astra.files.scan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import com.stackcorp.astra.files.api.ContentScanner;

/**
 * 악성코드 검사기를 붙였는지 정한다(ADR-0018). 붙였으면 새 내용은 검사를 마칠 때까지 quarantined다.
 * 붙이지 않았으면 형식 검사만 하고(ADR-0010 6번) 시작할 때 경고를 한 번 남긴다. astra.scan.required가 켜져 있으면 시작하지 않는다.
 */
@Component
@EnableConfigurationProperties(ScanProperties.class)
public class ScanMode implements SmartInitializingSingleton {

	private static final Logger log = LoggerFactory.getLogger(ScanMode.class);

	/** 검사를 면제한 용도의 원본 blob 상태(ADR-0016 12번). */
	public static final String UNSCANNED = "unscanned";

	/** 검사를 면제한 유일한 업로드 용도. */
	public static final String EXEMPT_PURPOSE = "lecture_video";

	private final ObjectProvider<ContentScanner> scanner;

	private final ScanProperties properties;

	ScanMode(ObjectProvider<ContentScanner> scanner, ScanProperties properties) {
		this.scanner = scanner;
		this.properties = properties;
	}

	/** @return 새 내용을 검사가 끝날 때까지 quarantined로 둘지 */
	public boolean enabled() {
		return this.scanner.getIfAvailable() != null;
	}

	/** @return 새로 만드는 file_blobs 행의 blob_state */
	public String initialBlobState() {
		return enabled() ? "quarantined" : "clean";
	}

	/**
	 * 검사를 면제한 용도(lecture_video)가 새로 만드는 blob의 blob_state. 검사기가 있으면 unscanned, 없으면 clean이다(ADR-0016 12번).
	 * unscanned는 "검사하지 않았다"는 뜻이고 clean이 아니다. 다른 용도의 업로드가 같은 내용을 참조하면 그 blob은 quarantined로 바뀌어
	 * 검사를 받는다(UploadRecords.referenceBlob).
	 */
	public String exemptBlobState() {
		return enabled() ? UNSCANNED : "clean";
	}

	@Override
	public void afterSingletonsInstantiated() {
		if (enabled()) {
			return;
		}
		if (this.properties.required()) {
			throw new IllegalStateException(
					"astra.scan.required=true이지만 악성코드 검사기가 없습니다. astra.scan.clamd.host를 설정하세요.");
		}
		log.warn("Malware scanner not configured (astra.scan.clamd.host is empty): uploads become clean after "
				+ "format checks only. Set ASTRA_SCAN_CLAMD_HOST and ASTRA_SCAN_REQUIRED=true before accepting "
				+ "institution files (ADR-0018).");
	}

}
