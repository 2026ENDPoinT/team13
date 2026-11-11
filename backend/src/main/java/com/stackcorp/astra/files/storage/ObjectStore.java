package com.stackcorp.astra.files.storage;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * S3를 읽고 쓰는 유일한 곳(ADR-0007). 서버 측 암호화는 bucket 기본값으로 걸리므로 요청에 암호화 header를 넣지 않는다.
 */
@Component
public class ObjectStore {

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

	private String name(Bucket bucket) {
		return bucket == Bucket.CONTENT ? this.properties.contentBucket() : this.properties.deliveryBucket();
	}

}
