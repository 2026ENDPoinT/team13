package com.stackcorp.astra.integration.delivery;

import java.time.Duration;
import java.time.Instant;

import com.stackcorp.astra.files.api.DeliveryObjects;
import com.stackcorp.astra.files.api.DeliveryObjects.DeliveryObject;
import com.stackcorp.astra.shared.ApiException;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * CDN이 하던 전달 경로 응답을 앱이 대신한다(ADR-0009). 권한 판정은 cookie를 발급한 API가 이미 했고,
 * 여기서는 로그인한 요청인지(security 설정)와 그 경로에 발급한 cookie의 서명·만료만 확인한다.
 * 서명이 없거나 맞지 않으면 객체가 있는지와 관계없이 403이다.
 */
@RestController
@ConditionalOnProperty(name = "astra.delivery.origin.enabled", havingValue = "true")
class OriginDeliveryController {

	private final OriginDeliverySigner signer;

	private final DeliveryObjects objects;

	OriginDeliveryController(OriginDeliverySigner signer, DeliveryObjects objects) {
		this.signer = signer;
		this.objects = objects;
	}

	@GetMapping("/doc/{key}/{file}")
	ResponseEntity<byte[]> document(@PathVariable String key, @PathVariable String file, HttpServletRequest request) {
		return deliver("doc", key, file, request);
	}

	@GetMapping("/vod/{key}/{file}")
	ResponseEntity<byte[]> video(@PathVariable String key, @PathVariable String file, HttpServletRequest request) {
		return deliver("vod", key, file, request);
	}

	private ResponseEntity<byte[]> deliver(String kind, String key, String file, HttpServletRequest request) {
		String prefix = "/" + kind + "/" + key + "/";
		if (!signed(prefix, request)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "delivery_forbidden", "전달 권한이 없거나 만료됐습니다.");
		}
		DeliveryObject object = this.objects.find(kind + "/" + key + "/" + file).orElseThrow(ApiException::notFound);
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(object.contentType()))
			.cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
			.header("X-Content-Type-Options", "nosniff")
			.header("Cross-Origin-Resource-Policy", "same-origin")
			.body(object.content());
	}

	private boolean signed(String prefix, HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return false;
		}
		Instant now = Instant.now();
		for (Cookie cookie : cookies) {
			if (OriginDeliverySigner.COOKIE.equals(cookie.getName())
					&& this.signer.valid(prefix, cookie.getValue(), now)) {
				return true;
			}
		}
		return false;
	}

}
