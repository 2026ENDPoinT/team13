package com.stackcorp.astra.files;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import com.stackcorp.astra.files.api.DeliveryCookies;
import com.stackcorp.astra.files.api.DeliverySigner;
import com.stackcorp.astra.files.api.VideoKeys;

/**
 * 쪽 이미지(/doc)와 영상(/vod) 전달 cookie를 발급한다. 서명기는 CloudFront(ADR-0007)나 앱 직접 응답(ADR-0009) 하나다. 서명기가 없으면
 * cookie를 내지 않아 미리보기의 쪽 이미지와 재생이 열리지 않는다. astra.delivery.required가 켜져 있으면 그 상태로 시작하지 않는다.
 */
@Service
@EnableConfigurationProperties(DeliveryProperties.class)
class DeliveryCookieService implements DeliveryCookies, SmartInitializingSingleton {

	private static final Pattern RENDITION_KEY = Pattern.compile("[0-9a-f]{64}");

	private static final Pattern ASSET_KEY = Pattern.compile(VideoKeys.ASSET_KEY_PATTERN);

	private final ObjectProvider<DeliverySigner> signer;

	private final DeliveryProperties properties;

	DeliveryCookieService(ObjectProvider<DeliverySigner> signer, DeliveryProperties properties) {
		this.signer = signer;
		this.properties = properties;
	}

	@Override
	public void afterSingletonsInstantiated() {
		if (this.properties.required() && this.signer.getIfAvailable() == null) {
			throw new IllegalStateException("astra.delivery.required=true이지만 /doc, /vod 전달 cookie를 서명할 수 없습니다. "
					+ "CloudFront가 /doc, /vod를 내보내면 ASTRA_CLOUDFRONT_KEY_PAIR_ID와 ASTRA_CLOUDFRONT_PRIVATE_KEY를, "
					+ "프론트엔드 서버처럼 /doc, /vod를 앱으로 넘기면 ASTRA_DELIVERY_ORIGIN_ENABLED=true와 "
					+ "ASTRA_DELIVERY_ORIGIN_SIGNING_KEY를 설정하세요(ADR-0009).");
		}
	}

	@Override
	public List<DeliveryCookie> forDocument(String renditionKey) {
		if (!RENDITION_KEY.matcher(renditionKey).matches()) {
			throw new IllegalArgumentException("rendition key 형식이 아닙니다.");
		}
		return issue(DocumentRenditionService.deliveryPrefix(renditionKey), this.properties.documentCookieTtl());
	}

	@Override
	public List<DeliveryCookie> forVideo(String assetKey) {
		if (!ASSET_KEY.matcher(assetKey).matches()) {
			throw new IllegalArgumentException("asset key 형식이 아닙니다.");
		}
		return issue("vod/" + assetKey + "/", this.properties.videoCookieTtl());
	}

	@Override
	public Duration videoTtl() {
		return this.properties.videoCookieTtl();
	}

	private List<DeliveryCookie> issue(String prefix, Duration ttl) {
		DeliverySigner s = this.signer.getIfAvailable();
		if (s == null) {
			return List.of();
		}
		String path = "/" + prefix;
		return s.sign(path, Instant.now().plus(ttl))
			.stream()
			.map((c) -> new DeliveryCookie(c.name(), c.value(), path, ttl, this.properties.secureCookie()))
			.toList();
	}

}
