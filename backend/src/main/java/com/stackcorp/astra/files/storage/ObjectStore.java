package com.stackcorp.astra.files.storage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * S3를 읽고 쓰는 유일한 곳(ADR-0007). 서버 측 암호화는 bucket 기본값으로 걸리므로 요청에 암호화 header를 넣지 않는다.
 */
@Component
public class ObjectStore {

	/** multipart upload의 part 크기. S3가 허용하는 마지막이 아닌 part의 최소 크기(5 MiB) 이상이다. */
	public static final int PART_SIZE = 8 * 1024 * 1024;

	public enum Bucket {
		/** 원본, 모델 입력용 rendition, 파생물. CloudFront가 읽지 못한다. */
		CONTENT,
		/** CloudFront가 읽는 전달용 파생물. */
		DELIVERY
	}

	private final S3Client s3;

	private final StorageProperties properties;

	ObjectStore(S3Client s3, StorageProperties properties) {
		this.s3 = s3;
		this.properties = properties;
	}

	public void put(Bucket bucket, String key, byte[] content, String contentType) {
		this.s3.putObject((b) -> b.bucket(name(bucket)).key(key).contentType(contentType),
				RequestBody.fromBytes(content));
	}

	/** 크기를 아는 stream을 메모리에 올리지 않고 저장한다. */
	public void put(Bucket bucket, String key, InputStream content, long length, String contentType) {
		this.s3.putObject((b) -> b.bucket(name(bucket)).key(key).contentType(contentType),
				RequestBody.fromInputStream(content, length));
	}

	/** 호출한 쪽이 stream을 닫는다. */
	public Optional<InputStream> open(Bucket bucket, String key) {
		try {
			return Optional.of(this.s3.getObject((b) -> b.bucket(name(bucket)).key(key)));
		}
		catch (NoSuchKeyException ex) {
			return Optional.empty();
		}
	}

	public Optional<byte[]> get(Bucket bucket, String key) {
		try {
			return Optional.of(this.s3.getObjectAsBytes((b) -> b.bucket(name(bucket)).key(key)).asByteArray());
		}
		catch (NoSuchKeyException ex) {
			return Optional.empty();
		}
	}

	/** 객체 하나를 지운다. 없는 key여도 성공한다. */
	public void delete(Bucket bucket, String key) {
		this.s3.deleteObject((b) -> b.bucket(name(bucket)).key(key));
	}

	/** prefix 아래 객체를 모두 지운다. */
	public void deletePrefix(Bucket bucket, String prefix) {
		String name = name(bucket);
		for (var page : this.s3.listObjectsV2Paginator((b) -> b.bucket(name).prefix(prefix))) {
			List<ObjectIdentifier> ids = page.contents()
				.stream()
				.map(S3Object::key)
				.map((key) -> ObjectIdentifier.builder().key(key).build())
				.toList();
			if (!ids.isEmpty()) {
				this.s3.deleteObjects((b) -> b.bucket(name).delete(Delete.builder().objects(ids).quiet(true).build()));
			}
		}
	}

	/**
	 * 길이를 모르는 내용을 multipart upload로 저장한다(ZIP 생성물 등). 메모리에는 part 하나({@link #PART_SIZE})만 둔다.
	 * {@link Upload#complete()}를 부르기 전에 닫으면 올린 part를 모두 버리고 객체를 만들지 않는다.
	 */
	public Upload upload(Bucket bucket, String key, String contentType) {
		String name = name(bucket);
		String uploadId = this.s3.createMultipartUpload((b) -> b.bucket(name).key(key).contentType(contentType))
			.uploadId();
		return new Upload(name, key, uploadId);
	}

	/**
	 * {@link ObjectStore#upload}가 여는 쓰기 stream. 한 thread에서만 쓴다.
	 */
	public final class Upload extends OutputStream {

		private final String bucket;

		private final String key;

		private final String uploadId;

		private final byte[] buffer = new byte[PART_SIZE];

		private final List<CompletedPart> parts = new ArrayList<>();

		private int filled;

		private long written;

		private boolean finished;

		private Upload(String bucket, String key, String uploadId) {
			this.bucket = bucket;
			this.key = key;
			this.uploadId = uploadId;
		}

		@Override
		public void write(int b) {
			if (this.filled == this.buffer.length) {
				sendPart();
			}
			this.buffer[this.filled++] = (byte) b;
			this.written++;
		}

		@Override
		public void write(byte[] b, int off, int len) {
			int offset = off;
			int remaining = len;
			while (remaining > 0) {
				if (this.filled == this.buffer.length) {
					sendPart();
				}
				int n = Math.min(remaining, this.buffer.length - this.filled);
				System.arraycopy(b, offset, this.buffer, this.filled, n);
				this.filled += n;
				offset += n;
				remaining -= n;
				this.written += n;
			}
		}

		/**
		 * 남은 part를 올리고 객체를 만든다.
		 *
		 * @return 저장한 byte 수
		 */
		public long complete() {
			if (this.finished) {
				throw new IllegalStateException("이미 끝난 upload입니다.");
			}
			if (this.filled > 0 || this.parts.isEmpty()) {
				sendPart();
			}
			ObjectStore.this.s3.completeMultipartUpload((b) -> b.bucket(this.bucket)
				.key(this.key)
				.uploadId(this.uploadId)
				.multipartUpload((m) -> m.parts(this.parts)));
			this.finished = true;
			return this.written;
		}

		/** complete 전이면 올린 part를 버린다. */
		@Override
		public void close() {
			if (this.finished) {
				return;
			}
			this.finished = true;
			ObjectStore.this.s3
				.abortMultipartUpload((b) -> b.bucket(this.bucket).key(this.key).uploadId(this.uploadId));
		}

		private void sendPart() {
			int number = this.parts.size() + 1;
			int length = this.filled;
			String etag = ObjectStore.this.s3
				.uploadPart((b) -> b.bucket(this.bucket)
					.key(this.key)
					.uploadId(this.uploadId)
					.partNumber(number)
					.contentLength((long) length), RequestBody
						.fromInputStream(new ByteArrayInputStream(this.buffer, 0, length), length))
				.eTag();
			this.parts.add(CompletedPart.builder().partNumber(number).eTag(etag).build());
			this.filled = 0;
		}

	}

	private String name(Bucket bucket) {
		return bucket == Bucket.CONTENT ? this.properties.contentBucket() : this.properties.deliveryBucket();
	}

}
