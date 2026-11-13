package com.stackcorp.astra.files;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.stackcorp.astra.files.ConversionRecords.Claimed;
import com.stackcorp.astra.files.ConversionRecords.Job;
import com.stackcorp.astra.files.ConversionRecords.Released;
import com.stackcorp.astra.files.ConversionRecords.State;
import com.stackcorp.astra.files.ConversionRecords.Thumbnail;
import com.stackcorp.astra.files.FileRecords.FileRow;
import com.stackcorp.astra.files.api.ConversionException;
import com.stackcorp.astra.files.api.DocumentRenditions;
import com.stackcorp.astra.files.api.PageConverter;
import com.stackcorp.astra.files.api.PageConverter.ConvertedDocument;
import com.stackcorp.astra.files.api.PageConverter.SourceFormat;
import com.stackcorp.astra.files.api.RenditionException;
import com.stackcorp.astra.files.api.RenditionException.Code;
import com.stackcorp.astra.files.storage.ObjectStore;
import com.stackcorp.astra.files.storage.ObjectStore.Bucket;

import com.stackcorp.astra.shared.metrics.MetricTags;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * rendition을 만들고 읽는다. 저장 위치는 다음과 같다.
 * <ul>
 * <li>delivery: doc/{key}/NNN.jpg 화면용 쪽 이미지</li>
 * <li>content: renditions/{key}/model/NNN.jpg 모델 입력용 쪽 이미지</li>
 * <li>content: renditions/{key}/document.json 제목과 쪽 텍스트</li>
 * <li>content: renditions/{key}/thumbnails/{variant}.jpg 첫 쪽 썸네일(file_thumbnails 행이 가리킨다)</li>
 * </ul>
 * 변환은 대기열(conversion_jobs)의 lease를 잡은 쪽 하나만 한다. 미리보기 요청은 대기열에 넣고 worker가 실행한다
 * ({@link ConversionJobRunner}). 요약 worker처럼 결과를 바로 써야 하는 쪽은 {@link #prepare}로 직접 잡아 실행한다. 상태 전이는
 * READ COMMITTED transaction에서 하고, 잠금 충돌로 되돌려지면 정해진 횟수만큼 다시 한다. 잠금 순서는 conversion_jobs →
 * file_thumbnails다(GC와 같다).
 * 변환 한 번의 시간과 결과를 잰다(astra.files.rendition.conversions). 결과는 ok 또는 failed이고, 실패 code는 converter의 실패
 * 종류나 storage다. 이미 만든 rendition을 읽는 경우와 대기열을 기다린 시간은 재지 않는다. lease가 끝나 {@link #recoverExpired}가
 * 대기로 되돌리거나 닫은 변환은 astra.files.rendition.interrupted{outcome=retry|failed}로 센다. 대기열 길이와 나이는
 * {@link FilesBacklog}가 낸다.
 */
@Service
@EnableConfigurationProperties(ConversionProperties.class)
class DocumentRenditionService implements DocumentRenditions {

	static final int MAX_PAGES = 200;

	private static final int TRANSITION_ATTEMPTS = 3;

	private static final Duration WAIT_STEP = Duration.ofMillis(500);

	private static final Logger log = LoggerFactory.getLogger(DocumentRenditionService.class);

	// 저장한 document.json을 쓰고 읽는다. 필수 숫자·참거짓 값이 빠진 파일은 0이나 false로 읽지 않고 실패한다.
	static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Pattern KEY = Pattern.compile("[0-9a-f]{64}");

	private static final SecureRandom RANDOM = new SecureRandom();

	private final FileRecords files;

	private final ConversionRecords conversions;

	private final ObjectStore store;

	private final PageConverter converter;

	private final ConversionProperties properties;

	private final TransactionTemplate transaction;

	private final String owner;
	private final MeterRegistry meters;

	DocumentRenditionService(FileRecords files, ConversionRecords conversions, ObjectStore store,
			PageConverter converter, ConversionProperties properties, PlatformTransactionManager transactionManager,
			MeterRegistry meters) {
		this.files = files;
		this.conversions = conversions;
		this.store = store;
		this.converter = converter;
		this.properties = properties;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.owner = properties.workerId() != null ? properties.workerId() : defaultOwner();
		this.meters = meters;
	}

	@Override
	public Rendition prepare(long fileId) {
		FileRow file = this.files.find(fileId)
			.orElseThrow(() -> new RenditionException(Code.NOT_FOUND, null, "파일이 없습니다."));
		if (!file.ready()) {
			throw new RenditionException(Code.NOT_READY, null, "검사를 마치지 않은 파일입니다.");
		}
		if (file.previewFormat().isEmpty()) {
			throw new RenditionException(Code.UNSUPPORTED, null, "쪽 이미지로 바꿀 수 없는 형식입니다.");
		}
		long deadline = System.nanoTime() + this.properties.lease().toNanos();
		while (true) {
			Optional<Job> latest = this.conversions.latest(fileId, file.versionNo());
			if (latest.isEmpty() || latest.get().state() == State.FAILED) {
				// 실패한 변환은 다시 요청한 쪽이 새로 만든다. 다른 요청이 먼저 넣었으면 그 행을 따른다. GC가 닫은 뒤에는 새로 넣지
				// 않도록 파일이 아직 있는지 다시 본다.
				if (this.files.find(fileId).isEmpty()) {
					throw new RenditionException(Code.NOT_FOUND, null, "파일이 없습니다.");
				}
				request(file, null);
				continue;
			}
			Job job = latest.get();
			if (job.state() == State.READY) {
				return read(file, job.key());
			}
			if (job.state() == State.REQUESTED) {
				Optional<Claimed> claimed = transition(
						() -> this.conversions.claim(job.id(), this.owner, this.properties.lease()));
				if (claimed.isPresent()) {
					return execute(file, claimed.get(), this.owner);
				}
				continue;
			}
			// 다른 worker가 변환하고 있다. 끝나기를 기다린다.
			if (System.nanoTime() >= deadline) {
				throw new RenditionException(Code.CONVERSION_FAILED, ConversionException.Code.TIMEOUT.name(),
						"다른 worker의 변환이 끝나기를 기다리다 제한 시간을 넘었습니다.");
			}
			pause();
		}
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

	/**
	 * 파일의 현재 버전을 변환 대기열에 넣는다. 같은 파일 버전의 대기·변환 중·완료 rendition이 이미 있으면 넣지 않는다.
	 *
	 * @param userId 요청한 사용자. worker가 스스로 넣으면 null
	 */
	void request(FileRow file, Long userId) {
		String key = HexFormat.of().formatHex(randomBytes());
		transition(() -> this.conversions.request(file.fileId(), file.versionNo(), userId, key));
	}

	/**
	 * 대기열의 변환 하나를 잡아 실행한다. 결과(완료, 재시도 대기, 실패)는 원장에 남긴다.
	 *
	 * @return 변환 하나를 잡았으면 true
	 */
	boolean convertNext() {
		return convertNext(this.owner);
	}

	boolean convertNext(String owner) {
		Optional<Claimed> claimed = transition(() -> this.conversions.claimNext(owner, this.properties.lease()));
		if (claimed.isEmpty()) {
			return false;
		}
		Claimed job = claimed.get();
		Optional<FileRow> file = this.files.find(job.fileId())
			.filter((f) -> f.ready() && f.versionNo() == job.versionNo() && f.previewFormat().isPresent());
		if (file.isEmpty()) {
			// 대기하는 동안 파일이 지워졌거나 검사 판정이 바뀌었다. 지운 파일이면 GC가 행을 expired로 닫는다.
			fail(job, owner, "not_found");
			return true;
		}
		try {
			execute(file.get(), job, owner);
		}
		catch (RenditionException ex) {
			log.info("conversion finished without rendition conversionJobId={} code={} detail={}", job.id(), ex.code(),
					ex.detail());
		}
		return true;
	}

	/**
	 * lease가 끝난 변환을 정리한다. 시도 수가 남았으면 다시 잡히게 하고, 아니면 실패로 닫고 그 변환이 쓴 객체를 지운다.
	 *
	 * @return 정리한 변환 수
	 */
	int recoverExpired() {
		Released released = transition(() -> this.conversions.releaseExpired(this.properties.maxAttempts(), 100));
		interrupted("retry", released.requeued());
		interrupted("failed", released.closedKeys().size());
		released.closedKeys().forEach(this::discardObjects);
		return released.requeued() + released.closedKeys().size();
	}

	private void interrupted(String outcome, int count) {
		if (count > 0) {
			Counter.builder("astra.files.rendition.interrupted")
				.description("lease가 끝나 대기로 되돌리거나 닫은 문서 변환")
				.tag("outcome", outcome)
				.register(this.meters)
				.increment(count);
		}
	}

	static String deliveryPrefix(String key) {
		return "doc/" + key + "/";
	}

	static String contentPrefix(String key) {
		return "renditions/" + key + "/";
	}

	private Rendition execute(FileRow file, Claimed job, String owner) {
		Timer.Sample sample = Timer.start(this.meters);
		String code = "storage";
		try {
			Rendition rendition = convert(file, job, owner);
			code = null;
			return rendition;
		}
		catch (RenditionException ex) {
			code = MetricTags.code(ex.code() == Code.CONVERSION_FAILED ? ex.detail() : ex.code().name());
			throw ex;
		}
		finally {
			sample.stop(Timer.builder("astra.files.rendition.conversions")
				.description("문서를 쪽 이미지와 텍스트로 바꾼 시간과 결과")
				.tag("outcome", code == null ? "ok" : "failed")
				.tag("code", code == null ? "none" : code)
				.register(this.meters));
		}
	}

	private Rendition convert(FileRow file, Claimed job, String owner) {
		SourceFormat format = file.previewFormat().orElseThrow();
		String key = job.key();
		List<RenditionPage> pages = new ArrayList<>();
		try {
			Optional<byte[]> source = this.store.get(Bucket.CONTENT, file.storageKey());
			if (source.isEmpty()) {
				fail(job, owner, "storage");
				throw new RenditionException(Code.STORAGE, null, "원본 파일이 저장소에 없습니다.");
			}
			ConvertedDocument doc = this.converter.convert(source.get(), format, MAX_PAGES, (page) -> {
				String n = "%03d.jpg".formatted(page.page());
				this.store.put(Bucket.DELIVERY, deliveryPrefix(key) + n, page.displayJpeg(), "image/jpeg");
				this.store.put(Bucket.CONTENT, modelImageKey(key, page.page()), page.modelJpeg(), "image/jpeg");
				pages.add(new RenditionPage(page.page(), page.text(), page.textTruncated(), page.modelJpeg().length,
						page.imageUnavailable()));
			});
			if (doc.pageCount() != pages.size() || doc.thumbnailJpeg() == null) {
				throw new ConversionException(ConversionException.Code.FAILED, "converter가 보낸 쪽 수나 썸네일이 맞지 않습니다.");
			}
			this.store.put(Bucket.CONTENT, documentKey(key),
					JSON.writeValueAsBytes(new StoredDocument(doc.title(), pages)), "application/json");
			Thumbnail thumbnail = new Thumbnail(ThumbnailVariant.THUMB_1.code(),
					thumbnailKey(key, ThumbnailVariant.THUMB_1), "image/jpeg", doc.thumbnailJpeg().length);
			this.store.put(Bucket.CONTENT, thumbnail.storageKey(), doc.thumbnailJpeg(), thumbnail.contentType());
			if (!transition(() -> this.conversions.ready(job.id(), owner, pages.size(), thumbnail))) {
				lost(job);
				throw new RenditionException(Code.NOT_FOUND, null, "변환하는 동안 파일이 삭제됐거나 다른 worker가 이어받았습니다.");
			}
			log.info("conversion ready conversionJobId={} pages={} attempt={}", job.id(), pages.size(), job.attempt());
			return new Rendition(key, file.fileId(), file.versionNo(), file.sha256(), doc.title(), pages);
		}
		catch (ConversionException ex) {
			String code = ex.code().name().toLowerCase();
			boolean retryable = ex.code() == ConversionException.Code.UNAVAILABLE
					|| ex.code() == ConversionException.Code.INTERRUPTED;
			if (retryable && job.attempt() < this.properties.maxAttempts()) {
				retryLater(job, owner, code);
			}
			else {
				fail(job, owner, code);
			}
			throw new RenditionException(Code.CONVERSION_FAILED, ex.code().name(), ex.getMessage());
		}
		catch (RenditionException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			// 저장소나 DB의 일시 장애로 본다. 시도 수가 남았으면 다시 대기열에 넣는다.
			if (job.attempt() < this.properties.maxAttempts()) {
				retryLater(job, owner, "storage");
			}
			else {
				fail(job, owner, "storage");
			}
			throw ex;
		}
	}

	private void retryLater(Claimed job, String owner, String code) {
		if (transition(() -> this.conversions.retryLater(job.id(), owner, this.properties.retryDelay(), code))) {
			log.warn("conversion retry scheduled conversionJobId={} attempt={} code={}", job.id(), job.attempt(), code);
		}
		else {
			lost(job);
		}
	}

	// 실패로 닫은 쪽만 객체를 지운다. lease를 잃었으면 같은 key에 쓰는 다른 시도의 객체이므로 두거나(lost) GC가 지운다.
	private void fail(Claimed job, String owner, String code) {
		if (transition(() -> this.conversions.failed(job.id(), owner, code))) {
			log.warn("conversion failed conversionJobId={} attempt={} code={}", job.id(), job.attempt(), code);
			discardObjects(job.key());
		}
		else {
			lost(job);
		}
	}

	// lease를 잃었다. GC가 닫은 행이면 이 시도가 늦게 쓴 객체를 지운다. 다른 worker가 이어받았으면 그 객체이므로 두고, 결과는 그쪽이 남긴다.
	private void lost(Claimed job) {
		String state = this.conversions.state(job.id());
		log.warn("conversion lease lost conversionJobId={} state={}", job.id(), state);
		if ("expired".equals(state)) {
			discardObjects(job.key());
		}
	}

	// 지우지 못해도 원장의 상태는 이미 남았다. 남은 객체는 파일을 지울 때 GC가 같은 prefix로 지운다.
	private void discardObjects(String key) {
		try {
			this.store.deletePrefix(Bucket.DELIVERY, deliveryPrefix(key));
			this.store.deletePrefix(Bucket.CONTENT, contentPrefix(key));
		}
		catch (RuntimeException ex) {
			log.warn("rendition cleanup failed error={}", ex.getClass().getSimpleName(), ex);
		}
	}

	// 교착, 잠금 대기 초과처럼 다시 하면 될 수 있는 실패만 다시 한다. transaction은 실패할 때마다 통째로 되돌아간다.
	private <T> T transition(Supplier<T> work) {
		for (int attempt = 1;; attempt++) {
			try {
				return this.transaction.execute((tx) -> work.get());
			}
			catch (TransientDataAccessException ex) {
				if (attempt >= TRANSITION_ATTEMPTS) {
					throw ex;
				}
			}
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

	private static void pause() {
		try {
			Thread.sleep(WAIT_STEP);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new RenditionException(Code.CONVERSION_FAILED, ConversionException.Code.INTERRUPTED.name(),
					"변환을 기다리다 중단되었습니다.");
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

	private static String thumbnailKey(String key, ThumbnailVariant variant) {
		return "renditions/" + key + "/thumbnails/" + variant.code() + ".jpg";
	}

	private static String defaultOwner() {
		String host;
		try {
			host = InetAddress.getLocalHost().getHostName();
		}
		catch (UnknownHostException ex) {
			host = "unknown";
		}
		return host + ":" + ProcessHandle.current().pid();
	}

	record StoredDocument(String title, List<RenditionPage> pages) {
	}

}
