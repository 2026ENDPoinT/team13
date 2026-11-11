package com.stackcorp.astra.identity.session;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param trustedProxies 요청을 앱에 넘기는 CloudFront VPC origin이 쓰는 주소 범위(CIDR). 이 범위에서 온 연결에서만
 * {@code CloudFront-Viewer-Address}를 브라우저 주소로 믿는다. 비우면 아무 header도 믿지 않고 연결한 쪽 주소를 쓴다
 */
@ConfigurationProperties("astra.client-address")
public record ClientAddressProperties(@DefaultValue List<String> trustedProxies) {
}
