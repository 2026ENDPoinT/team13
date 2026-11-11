package com.stackcorp.astra.identity.session;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * user_devices에 로그인한 브라우저를 등록한다. 앱 기기 등록(푸시 token, 기기인증)은 앱 채널을 만들 때 정한다.
 */
@Repository
class Devices {

	private static final Pattern UUID_FORMAT = Pattern
		.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

	private final JdbcClient jdbc;

	Devices(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	static boolean isUuid(String value) {
		return UUID_FORMAT.matcher(value).matches();
	}

	/**
	 * 브라우저가 보낸 기기가 이 사용자의 폐기하지 않은 기기면 최종 접속 시각을 갱신하고, 아니면 새 기기를 만든다.
	 * @param presented 기기 쿠키 값. 없으면 null
	 */
	Device bind(long userId, String presented) {
		if (presented != null) {
			Optional<Long> known = this.jdbc.sql("""
					SELECT device_id FROM user_devices WHERE device_uuid = :uuid AND user_id = :user AND revoked_at IS NULL
					""").param("uuid", presented).param("user", userId).query(Long.class).optional();
			if (known.isPresent()) {
				this.jdbc.sql("UPDATE user_devices SET last_access_at = CURRENT_TIMESTAMP(3) WHERE device_id = :id")
					.param("id", known.get())
					.update();
				return new Device(known.get(), presented);
			}
		}
		String uuid = UUID.randomUUID().toString();
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO user_devices (user_id, device_uuid, device_name, client_type, last_access_at)
				VALUES (:user, :uuid, 'PC', 'BROWSER', CURRENT_TIMESTAMP(3))
				""").param("user", userId).param("uuid", uuid).update(key, "device_id");
		return new Device(key.getKey().longValue(), uuid);
	}

	record Device(long deviceId, String uuid) {
	}

}
