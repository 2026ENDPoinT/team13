package com.stackcorp.astra.files;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import com.stackcorp.astra.files.api.DeliveryCookies;
import com.stackcorp.astra.files.api.DeliverySigner;
import com.stackcorp.astra.files.api.VideoKeys;

@Service
@EnableConfigurationProperties(DeliveryProperties.class)
class DeliveryCookieService implements DeliveryCookies {

	private static final Pattern RENDITION_KEY = Pattern.compile("[0-9a-f]{64}");

	private static final Pattern ASSET_KEY = Pattern.compile(VideoKeys.ASSET_KEY_PATTERN);

	private final ObjectProvider<DeliverySigner> signer;

	private final DeliveryProperties properties;

	DeliveryCookieService(ObjectProvider<DeliverySigner> signer, DeliveryProperties properties) {
		this.signer = signer;
		this.properties = properties;
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
