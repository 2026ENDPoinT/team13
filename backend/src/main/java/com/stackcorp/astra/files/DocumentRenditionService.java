package com.stackcorp.astra.files;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.stackcorp.astra.files.FileRecords.FileRow;
import com.stackcorp.astra.files.api.ConversionException;
import com.stackcorp.astra.files.api.DocumentRenditions;
import com.stackcorp.astra.files.api.PageConverter;
import com.stackcorp.astra.files.api.PageConverter.ConvertedDocument;
import com.stackcorp.astra.files.api.RenditionException;
import com.stackcorp.astra.files.api.RenditionException.Code;
import com.stackcorp.astra.files.storage.ObjectStore;
import com.stackcorp.astra.files.storage.ObjectStore.Bucket;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * rendition 저장 위치.
 * <ul>
 * <li>delivery: doc/{key}/NNN.jpg 화면용 쪽 이미지</li>
 * <li>content: renditions/{key}/model/NNN.jpg 모델 입력용 쪽 이미지</li>
 * <li>content: renditions/{key}/document.json 제목과 쪽 텍스트</li>
 * </ul>
 */
@Service
class DocumentRenditionService implements DocumentRenditions {

	static final int MAX_PAGES = 200;

	private static final Logger log = LoggerFactory.getLogger(DocumentRenditionService.class);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Pattern KEY = Pattern.compile("[0-9a-f]{64}");

	private static final SecureRandom RANDOM = new SecureRandom();

	private final FileRecords files;

	private final ConversionRecords conversions;

	private final ObjectStore store;

	private final PageConverter converter;

	DocumentRenditionService(FileRecords files, ConversionRecords conversions, ObjectStore store,
			PageConverter converter) {
		this.files = files;
		this.conversions = conversions;
		this.store = store;
		this.converter = converter;
	}

	@Override
	public Rendition prepare(long fileId) {
		FileRow file = this.files.find(fileId)
			.orElseThrow(() -> new RenditionException(Code.NOT_FOUND, null, "파일이 없습니다."));
		if (!file.ready()) {
			throw new RenditionException(Code.NOT_READY, null, "검사를 마치지 않은 파일입니다.");
		}
		if (!file.pdf()) {
			throw new RenditionException(Code.UNSUPPORTED, null, "PDF 파일만 변환합니다.");
		}
		var existing = this.conversions.findReadyKey(fileId, file.versionNo());
		if (existing.isPresent()) {
			return read(file, existing.get());
		}
		return convert(file);
	}

	@Override
	public Optional<String> readyKey(long fileId, int fileVersionNo) {
		return this.conversions.findReadyKey(fileId, fileVersionNo);
	}

	@Override
	public byte[] modelImage(String renditionKey, int page) {
		checkKey(renditionKey);
		return this.store.get(Bucket.CONTENT, modelImageKey(renditionKey, page))
			.orElseThrow(() -> new RenditionException(Code.STORAGE, null, "모델 입력용 쪽 이미지가 없습니다: p" + page));
	}

	static String deliveryPrefix(String key) {
		return "doc/" + key + "/";
	}

	private Rendition convert(FileRow file) {
		byte[] pdf = this.store.get(Bucket.CONTENT, file.storageKey())
			.orElseThrow(() -> new RenditionException(Code.STORAGE, null, "원본 파일이 저장소에 없습니다."));
		String key = HexFormat.of().formatHex(randomBytes());
		long id = this.conversions.start(file.fileId(), file.versionNo(), key);
		List<RenditionPage> pages = new ArrayList<>();
		try {
			ConvertedDocument doc = this.converter.convert(pdf, MAX_PAGES, (page) -> {
				String n = "%03d.jpg".formatted(page.page());
				this.store.put(Bucket.DELIVERY, deliveryPrefix(key) + n, page.displayJpeg(), "image/jpeg");
				this.store.put(Bucket.CONTENT, modelImageKey(key, page.page()), page.modelJpeg(), "image/jpeg");
				pages.add(new RenditionPage(page.page(), page.text(), page.textTruncated(), page.modelJpeg().length));
			});
			if (doc.pageCount() != pages.size()) {
				throw new ConversionException(ConversionException.Code.FAILED, "converter가 보낸 쪽 수가 맞지 않습니다.");
			}
			this.store.put(Bucket.CONTENT, documentKey(key),
					JSON.writeValueAsBytes(new StoredDocument(doc.title(), pages)), "application/json");
			this.conversions.ready(id, pages.size());
			return new Rendition(key, file.fileId(), file.versionNo(), file.sha256(), doc.title(), pages);
		}
		catch (ConversionException ex) {
			discard(id, key, ex.code().name().toLowerCase());
			throw new RenditionException(Code.CONVERSION_FAILED, ex.code().name(), ex.getMessage());
		}
		catch (RuntimeException ex) {
			discard(id, key, "storage");
			throw ex;
		}
	}

	private Rendition read(FileRow file, String key) {
		byte[] bytes = this.store.get(Bucket.CONTENT, documentKey(key))
			.orElseThrow(() -> new RenditionException(Code.STORAGE, null, "rendition 문서 정보가 없습니다."));
		StoredDocument doc;
		try {
			doc = JSON.readValue(bytes, StoredDocument.class);
		}
		catch (JacksonException ex) {
			throw new RenditionException(Code.STORAGE, null, "rendition 문서 정보를 읽지 못했습니다.");
		}
		return new Rendition(key, file.fileId(), file.versionNo(), file.sha256(), doc.title(), doc.pages());
	}

	// 실패한 변환이 쓴 객체를 지운다. 지우지 못해도 원래 실패를 가리지 않는다.
	private void discard(long id, String key, String errorCode) {
		this.conversions.failed(id, errorCode);
		try {
			this.store.deletePrefix(Bucket.DELIVERY, deliveryPrefix(key));
			this.store.deletePrefix(Bucket.CONTENT, "renditions/" + key + "/");
		}
		catch (RuntimeException ex) {
			log.warn("failed rendition cleanup conversionJobId={}", id, ex);
		}
	}

	private static byte[] randomBytes() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return bytes;
	}

	private static void checkKey(String key) {
		if (key == null || !KEY.matcher(key).matches()) {
			throw new IllegalArgumentException("rendition key 형식이 아닙니다.");
		}
	}

	private static String modelImageKey(String key, int page) {
		return "renditions/" + key + "/model/%03d.jpg".formatted(page);
	}

	private static String documentKey(String key) {
		return "renditions/" + key + "/document.json";
	}

	record StoredDocument(String title, List<RenditionPage> pages) {
	}

}
