package com.stackcorp.astra.identity.session;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param trustedProxies 요청을 앱에 넘기는 proxy의 주소 또는 범위(CIDR). 연결한 쪽 주소가 여기 있을 때만 {@code X-Forwarded-For}를
 * 오른쪽부터 읽고, 여기 있는 주소는 건너뛴다({@link ClientAddresses}). 비우면 아무 header도 믿지 않고 연결한 쪽 주소를 쓴다
 */
@ConfigurationProperties("astra.client-address")
public record ClientAddressProperties(@DefaultValue List<String> trustedProxies) {
}
