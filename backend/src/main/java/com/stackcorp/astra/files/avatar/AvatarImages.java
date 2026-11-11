package com.stackcorp.astra.files.avatar;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.stackcorp.astra.catalog.api.CourseMates;
import com.stackcorp.astra.files.api.ContentScanner;
import com.stackcorp.astra.files.api.ContentScanner.Infected;
import com.stackcorp.astra.files.api.ContentScanner.Unavailable;
import com.stackcorp.astra.files.api.ContentScanner.Verdict;
import com.stackcorp.astra.files.api.ImageNormalizer;
import com.stackcorp.astra.files.api.ImageNormalizer.NormalizationException;
import com.stackcorp.astra.files.storage.ObjectStore;
import com.stackcorp.astra.files.storage.ObjectStore.Bucket;
import com.stackcorp.astra.identity.api.ProfileImages;
import com.stackcorp.astra.identity.api.ProfileImages.Current;
import com.stackcorp.astra.identity.api.ProfileImages.Discarded;
import com.stackcorp.astra.identity.api.ProfileImages.Pending;
import com.stackcorp.astra.shared.ApiException;

/**
 * 아바타 사진의 바이트(ADR-0038). 상태는 identity의 {@link ProfileImages}가 갖고, 여기서는 저장, 검사, 재인코딩, 전달만 한다.
 * <ul>
 * <li>올리기: 앞부분 signature가 PNG나 JPEG이고 {@value #MAX_BYTES} byte 이하일 때만 원본을 content bucket에 두고 대기로 넘긴다.
 * client가 보낸 이름과 Content-Type은 쓰지 않는다. 디코딩은 하지 않는다.</li>
 * <li>처리(worker): 다른 업로드와 같은 악성코드 검사기(ADR-0018)를 거친 뒤 격리한 converter가 256px JPEG로 다시 만든다. 검사기가
 * 없는 배포는 형식 검사만 거친다(ADR-0010 6번). 원본은 내보내지 않고 처리 뒤 지운다.</li>
 * <li>보기: 본인이거나, 폐강하지 않은 과목을 함께 듣거나 가르치는 사람만 본다(catalog CourseMates). 그 밖에는 사진이 없는 것과 같은
 * 404다.</li>
 * </ul>
 */
@Service
class AvatarImages {

	/** 올리는 원본의 크기 상한. converter도 같은 상한을 본다. */
	static final int MAX_BYTES = 5 << 20;

	static final Duration LEASE = Duration.ofMinutes(15);

	static final int MAX_ATTEMPTS = 3;

	static final Duration RETRY_DELAY = Duration.ofMinutes(1);

	/** 처리 중인 첫 사진의 404에 붙이는 {@code Retry-After}. worker의 기본 poll 간격({@code astra.avatar.poll-interval} 5s)과 같다. */
	static final Duration PROCESSING_RETRY_AFTER = Duration.ofSeconds(5);

	private static final int SWEEP_BATCH = 100;

	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };

	private static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final Logger log = LoggerFactory.getLogger(AvatarImages.class);

	private final ProfileImages images;

	private final ObjectStore store;

	private final CourseMates mates;

	private final ImageNormalizer normalizer;

	private final Optional<ContentScanner> scanner;

	private final String owner;

	AvatarImages(ProfileImages images, ObjectStore store, CourseMates mates, ImageNormalizer normalizer,
			Optional<ContentScanner> scanner) {
		this.images = images;
		this.store = store;
		this.mates = mates;
		this.normalizer = normalizer;
		this.scanner = scanner;
		this.owner = defaultOwner();
	}

	/**
	 * 사진을 받아 처리 대기로 둔다.
	 * @return 사진 ID
	 * @throws ApiException 400 {@code not_image}, 413 {@code too_large}
	 */
	long upload(long userId, MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw ApiException.invalid("file이 필요합니다.");
		}
		if (file.getSize() > MAX_BYTES) {
			throw tooLarge();
		}
		byte[] bytes;
		try {
			bytes = file.getInputStream().readNBytes(MAX_BYTES + 1);
		}
		catch (IOException ex) {
			throw ApiException.invalid("file을 읽지 못했습니다.");
		}
		if (bytes.length > MAX_BYTES) {
			throw tooLarge();
		}
		if (!startsWith(bytes, PNG) && !startsWith(bytes, JPEG)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "not_image", "PNG나 JPEG 이미지만 올릴 수 있습니다.");
		}
		String key = HexFormat.of().formatHex(randomBytes());
		String sourceKey = "avatar-sources/" + key;
		this.store.put(Bucket.CONTENT, sourceKey, bytes, "application/octet-stream");
		try {
			return this.images.stage(userId, "avatars/" + key + ".jpg", sourceKey);
		}
		catch (RuntimeException ex) {
			this.store.delete(Bucket.CONTENT, sourceKey);
			throw ex;
		}
	}

	/**
	 * 본인이거나 함께 있는 과목이 있을 때 그 사람의 지금 사진.
	 * @throws ApiException 404 {@code not_found}. 볼 수 있는 사람의 첫 사진이 아직 처리 중이면 {@code Retry-After}
	 * ({@link #PROCESSING_RETRY_AFTER})를 붙인다
	 */
	Photo visible(long viewerId, long userId) {
		if (viewerId != userId && this.mates.of(viewerId, null, List.of(userId)).isEmpty()) {
			throw ApiException.notFound();
		}
		Optional<Photo> photo = this.images.current(userId)
			.flatMap((current) -> this.store.get(Bucket.CONTENT, current.storageKey())
				.map((bytes) -> new Photo(current, bytes)));
		if (photo.isPresent()) {
			return photo.get();
		}
		if (this.images.processing(userId)) {
			throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "찾을 수 없습니다.", PROCESSING_RETRY_AFTER);
		}
		throw ApiException.notFound();
	}

	/**
	 * 대기 사진 하나를 처리한다. 끝나면 지울 객체를 지운다.
	 * @return 처리한 사진이 있었으면 true
	 */
	boolean processNext() {
		Optional<Pending> claimed = this.images.claim(this.owner, LEASE);
		if (claimed.isEmpty()) {
			return false;
		}
		process(claimed.get());
		sweep();
		return true;
	}

	/** lease가 끝난 처리를 대기로 돌리거나 닫는다. */
	int recoverExpired() {
		return this.images.recoverExpired(MAX_ATTEMPTS);
	}

	/** 버렸거나 실패한 사진의 객체와 처리한 원본을 지운다. */
	int sweep() {
		List<Discarded> discarded = this.images.discarded(SWEEP_BATCH);
		for (Discarded d : discarded) {
			d.keys().forEach((key) -> this.store.delete(Bucket.CONTENT, key));
			this.images.purged(d.imageId());
		}
		return discarded.size();
	}

	private void process(Pending pending) {
		try {
			Optional<byte[]> source = this.store.get(Bucket.CONTENT, pending.sourceKey());
			if (source.isEmpty()) {
				// 그 사이 새 사진으로 바뀌어 원본이 지워졌다. 행이 아직 대기이면 실패로 닫는다.
				fail(pending, "unavailable");
				return;
			}
			byte[] bytes = source.get();
			if (this.scanner.isPresent()) {
				Verdict verdict = this.scanner.get().scan(new ByteArrayInputStream(bytes), bytes.length);
				if (verdict instanceof Infected infected) {
					log.warn("avatar image rejected by scanner imageId={} signature={}", pending.imageId(),
							infected.signature());
					fail(pending, "rejected");
					return;
				}
				if (verdict instanceof Unavailable unavailable) {
					retryOrFail(pending, unavailable.reason());
					return;
				}
			}
			byte[] jpeg;
			try {
				jpeg = this.normalizer.avatar(bytes);
			}
			catch (NormalizationException ex) {
				switch (ex.code()) {
					case NOT_IMAGE -> fail(pending, "not_image");
					case TOO_LARGE -> fail(pending, "too_large");
					case TIMEOUT, UNAVAILABLE -> retryOrFail(pending, ex.code().value());
					case FAILED -> fail(pending, "failed");
				}
				return;
			}
			this.store.put(Bucket.CONTENT, pending.storageKey(), jpeg, "image/jpeg");
			if (!this.images.activate(pending.imageId(), this.owner, sha256(jpeg), jpeg.length,
					ImageNormalizer.AVATAR_SIZE, ImageNormalizer.AVATAR_SIZE)) {
				// lease를 잃었거나 그 사이 버려졌다. 만든 객체는 이 시도의 것이므로 지운다.
				this.store.delete(Bucket.CONTENT, pending.storageKey());
				log.info("avatar image superseded before activation imageId={}", pending.imageId());
				return;
			}
			log.info("avatar image ready imageId={} attempt={}", pending.imageId(), pending.attempt());
		}
		catch (RuntimeException ex) {
			// 저장소나 DB의 일시 장애로 본다.
			log.warn("avatar image processing failed imageId={} error={}", pending.imageId(),
					ex.getClass().getSimpleName());
			retryOrFail(pending, "storage");
		}
	}

	private void retryOrFail(Pending pending, String reason) {
		if (pending.attempt() < MAX_ATTEMPTS) {
			this.images.retryLater(pending.imageId(), this.owner, "unavailable", RETRY_DELAY);
			log.warn("avatar image retry scheduled imageId={} attempt={} reason={}", pending.imageId(),
					pending.attempt(), reason);
		}
		else {
			fail(pending, "unavailable");
		}
	}

	private void fail(Pending pending, String code) {
		if (this.images.fail(pending.imageId(), this.owner, code)) {
			log.info("avatar image failed imageId={} code={}", pending.imageId(), code);
		}
	}

	private static ApiException tooLarge() {
		return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "too_large", "사진은 5MB 이하여야 합니다.");
	}

	private static boolean startsWith(byte[] bytes, byte[] prefix) {
		return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
	}

	private static byte[] randomBytes() {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		return bytes;
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String defaultOwner() {
		String host;
		try {
			host = InetAddress.getLocalHost().getHostName();
		}
		catch (UnknownHostException ex) {
			host = "worker";
		}
		String owner = host + "-avatar-" + HexFormat.of().formatHex(randomBytes(), 0, 4);
		return owner.length() > 64 ? owner.substring(owner.length() - 64) : owner;
	}

	/** @param jpeg 재인코딩한 사진 */
	record Photo(Current current, byte[] jpeg) {
	}

}
