package com.stackcorp.astra.files.storage;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.stackcorp.astra.files.storage.ObjectStore.Bucket;

import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.MultipartUpload;

/**
 * 크기를 미리 다 알 수 없거나 메모리에 올릴 수 없는 객체를 S3 multipart upload로 쓴다(ADR-0016).
 * 한 번에 part 하나만 메모리에 둔다. 실패하면 {@link Writer#abort()}로 올린 part를 모두 지운다.
 * process가 중간에 멈춰 남은 part는 {@link #abortIncomplete}가 지운다.
 */
@Component
public class ObjectStreams {

	/** S3 multipart의 마지막이 아닌 part의 최소 크기. */
	public static final int MIN_PART_BYTES = 5 * 1024 * 1024;

	private final S3Client s3;

	private final StorageProperties properties;

	ObjectStreams(S3Client s3, StorageProperties properties) {
		this.s3 = s3;
		this.properties = properties;
	}

	/**
	 * @param partBytes part 크기. {@link #MIN_PART_BYTES} 이상
	 */
	public Writer create(Bucket bucket, String key, String contentType, int partBytes) {
		if (partBytes < MIN_PART_BYTES) {
			throw new IllegalArgumentException("part 크기가 S3 최소값보다 작습니다.");
		}
		String name = name(bucket);
		String uploadId = this.s3.createMultipartUpload((b) -> b.bucket(name).key(key).contentType(contentType))
			.uploadId();
		return new Writer(name, key, uploadId, partBytes);
	}

	public void delete(Bucket bucket, String key) {
		this.s3.deleteObject((b) -> b.bucket(name(bucket)).key(key));
	}

	/**
	 * prefix 아래에서 olderThan보다 먼저 시작해 끝나지 않은 multipart upload를 취소한다.
	 *
	 * @return 취소한 수
	 */
	public int abortIncomplete(Bucket bucket, String prefix, Duration olderThan) {
		String name = name(bucket);
		Instant cutoff = Instant.now().minus(olderThan);
		int aborted = 0;
		for (var page : this.s3.listMultipartUploadsPaginator((b) -> b.bucket(name).prefix(prefix))) {
			for (MultipartUpload upload : page.uploads()) {
				if (upload.initiated() == null || !upload.initiated().isAfter(cutoff)) {
					this.s3.abortMultipartUpload((b) -> b.bucket(name).key(upload.key()).uploadId(upload.uploadId()));
					aborted++;
				}
			}
		}
		return aborted;
	}

	private String name(Bucket bucket) {
		return bucket == Bucket.CONTENT ? this.properties.contentBucket() : this.properties.deliveryBucket();
	}

	/**
	 * 받은 byte를 part 크기만큼 모아 올린다. 한 thread에서만 쓴다.
	 */
	public final class Writer {

		private final String bucket;

		private final String key;

		private final String uploadId;

		private final byte[] buffer;

		private final List<CompletedPart> parts = new ArrayList<>();

		private int filled;

		private boolean closed;

		private Writer(String bucket, String key, String uploadId, int partBytes) {
			this.bucket = bucket;
			this.key = key;
			this.uploadId = uploadId;
			this.buffer = new byte[partBytes];
		}

		public void write(byte[] bytes, int offset, int length) {
			if (this.closed) {
				throw new IllegalStateException("이미 끝낸 upload입니다.");
			}
			while (length > 0) {
				int n = Math.min(length, this.buffer.length - this.filled);
				System.arraycopy(bytes, offset, this.buffer, this.filled, n);
				this.filled += n;
				offset += n;
				length -= n;
				if (this.filled == this.buffer.length) {
					flush();
				}
			}
		}

		/** 남은 byte를 올리고 객체를 만든다. 실패하면 upload를 취소하고 예외를 다시 던진다. */
		public void complete() {
			try {
				if (this.filled > 0 || this.parts.isEmpty()) {
					flush();
				}
				ObjectStreams.this.s3.completeMultipartUpload((b) -> b.bucket(this.bucket)
					.key(this.key)
					.uploadId(this.uploadId)
					.multipartUpload((m) -> m.parts(this.parts)));
				this.closed = true;
			}
			catch (RuntimeException ex) {
				abort();
				throw ex;
			}
		}

		/** 올린 part를 지운다. 여러 번 불러도 된다. */
		public void abort() {
			if (this.closed) {
				return;
			}
			this.closed = true;
			ObjectStreams.this.s3
				.abortMultipartUpload((b) -> b.bucket(this.bucket).key(this.key).uploadId(this.uploadId));
		}

		private void flush() {
			int partNumber = this.parts.size() + 1;
			String etag = ObjectStreams.this.s3
				.uploadPart((b) -> b.bucket(this.bucket).key(this.key).uploadId(this.uploadId).partNumber(partNumber),
						RequestBody.fromInputStream(new ByteArrayInputStream(this.buffer, 0, this.filled), this.filled))
				.eTag();
			this.parts.add(CompletedPart.builder().partNumber(partNumber).eTag(etag).build());
			this.filled = 0;
		}

	}

}
