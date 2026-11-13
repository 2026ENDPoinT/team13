package com.stackcorp.astra.files.storage;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * object storage 설정. bucket의 암호화와 접근 정책은 Terraform이 정한다(ADR-0007). 앱은 S3 API만 쓴다.
 *
 * @param endpoint S3 호환 저장소 주소. AWS S3를 쓰면 비운다
 * @param accessKey endpoint와 함께 쓰는 정적 key. 비우면 기본 credential(instance role)을 쓴다
 */
@ConfigurationProperties("astra.storage")
public record StorageProperties(@DefaultValue("astra-content") String contentBucket,
		@DefaultValue("astra-delivery") String deliveryBucket, @DefaultValue("ap-northeast-2") String region,
		URI endpoint, String accessKey, String secretKey, @DefaultValue("false") boolean pathStyle) {

	@Override
	public String toString() {
		return "StorageProperties[contentBucket=" + this.contentBucket + ", deliveryBucket=" + this.deliveryBucket
				+ ", region=" + this.region + ", endpoint=" + this.endpoint + "]";
	}

}
