package com.stackcorp.astra.identity.account;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.stackcorp.astra.identity.api.RegistrarDirectory.RegistrarRecord;
import com.stackcorp.astra.shared.ApiException;

/**
 * 내정보(users, user_contacts, user_profiles). 행은 언제나 요청한 사용자의 것만 읽고 쓴다. 대상 사용자는 session에서만 정한다.
 * 이름, 소속, 학년은 학사 원천이라 여기서 바꾸지 않는다.
 */
@Repository
class AccountProfiles {

	private final JdbcClient jdbc;

	AccountProfiles(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** @param registrarSyncAvailable 학사 동기화 명령을 시도할 수 있는지. 부르는 쪽이 판정한다 */
	Profile find(long userId, boolean registrarSyncAvailable) {
		Profile profile = this.jdbc.sql("""
				SELECT u.user_id, u.user_name, u.user_role, u.department_name, u.grade, u.last_synced_at,
				       c.phone_number, c.email_address, c.contact_source, c.synced_at,
				       p.avatar_type, p.avatar_color, p.short_name, p.profile_version
				  FROM users u
				  LEFT JOIN user_contacts c ON c.user_id = u.user_id
				  LEFT JOIN user_profiles p ON p.user_id = u.user_id
				 WHERE u.user_id = :user
				""").param("user", userId).query((rs, n) -> {
			String source = rs.getString("contact_source");
			Contact contact = new Contact(rs.getString("phone_number"), rs.getString("email_address"),
					source == null ? null : source.toLowerCase(), source == null || "LOCAL".equals(source),
					rs.getObject("synced_at", LocalDateTime.class));
			String type = rs.getString("avatar_type");
			Avatar avatar = new Avatar(type == null ? "color" : type.toLowerCase(), rs.getString("avatar_color"),
					rs.getString("short_name"), type == null ? 0 : rs.getInt("profile_version"), null);
			return new Profile(rs.getLong("user_id"), rs.getString("user_name"), rs.getString("user_role"),
					rs.getString("department_name"), rs.getObject("grade", Integer.class), contact, avatar,
					rs.getObject("last_synced_at", LocalDateTime.class), registrarSyncAvailable);
		}).single();
		Avatar avatar = profile.avatar();
		Photo photo = this.jdbc.sql("""
				SELECT image_status, error_code FROM profile_images
				 WHERE user_id = :user AND image_status IN ('TEMP', 'ACTIVE', 'FAILED')
				 ORDER BY profile_image_id DESC LIMIT 1
				""").param("user", userId).query((rs, n) -> {
			String status = rs.getString(1);
			return new Photo(switch (status) {
				case "TEMP" -> "processing";
				case "ACTIVE" -> "ready";
				default -> "failed";
			}, "image".equals(avatar.type()) ? avatarUrl(userId) : null, "FAILED".equals(status) ? rs.getString(2) : null);
		}).optional().orElse(null);
		return new Profile(profile.userId(), profile.name(), profile.role(), profile.departmentName(), profile.grade(),
				profile.contact(), new Avatar(avatar.type(), avatar.color(), avatar.shortName(), avatar.version(), photo),
				profile.lastSyncedAt(), profile.registrarSyncAvailable());
	}

	/** 아바타 사진 경로. files가 권한을 판정해 내보낸다(ADR-0038). */
	static String avatarUrl(long userId) {
		return "/api/users/" + userId + "/avatar";
	}

	/**
	 * 색상형 아바타로 저장한다(EP-0271). 이미지형이었다면 이미지 참조를 풀고, 대기·현재·실패 사진을 버린다(EP-0278). 사진 객체는 files
	 * worker가 지운다. 잠금 순서는 user_profiles → profile_images다(ADR-0038).
	 * @param version client가 읽은 version. 행이 없으면 0이다
	 * @throws ApiException 409 {@code version_conflict}
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	void updateAvatar(long userId, String color, String shortName, int version) {
		int updated;
		if (version == 0) {
			try {
				updated = this.jdbc.sql("""
						INSERT INTO user_profiles (user_id, avatar_type, avatar_color, short_name, profile_version)
						VALUES (:user, 'COLOR', :color, :short, 1)
						""").param("user", userId).param("color", color).param("short", shortName).update();
			}
			catch (DuplicateKeyException ex) {
				updated = 0;
			}
		}
		else {
			updated = this.jdbc.sql("""
					UPDATE user_profiles
					   SET avatar_type = 'COLOR', avatar_color = :color, short_name = :short, profile_image_id = NULL,
					       profile_version = profile_version + 1
					 WHERE user_id = :user AND profile_version = :version
					""")
				.param("color", color)
				.param("short", shortName)
				.param("user", userId)
				.param("version", version)
				.update();
		}
		if (updated == 0) {
			throw new ApiException(HttpStatus.CONFLICT, "version_conflict",
					"다른 창에서 먼저 바꾸었습니다. 다시 불러온 뒤 저장해 주세요.");
		}
		discardImages(userId);
	}

	/**
	 * 아바타 사진을 지우고 색상형으로 돌아간다(EP-0278). 색상과 약칭은 그대로다. 대기 중인 사진도 버린다. 버릴 사진이 없으면 아무것도
	 * 바꾸지 않는다.
	 * @return 바꾼 것이 있으면 true
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	boolean removeImage(long userId) {
		Optional<String> type = this.jdbc.sql("SELECT avatar_type FROM user_profiles WHERE user_id = :user FOR UPDATE")
			.param("user", userId)
			.query(String.class)
			.optional();
		if (type.isEmpty()) {
			return false;
		}
		int discarded = discardImages(userId);
		if (discarded == 0 && !"IMAGE".equals(type.get())) {
			return false;
		}
		this.jdbc.sql("""
				UPDATE user_profiles SET avatar_type = 'COLOR', profile_image_id = NULL, profile_version = profile_version + 1
				 WHERE user_id = :user
				""").param("user", userId).update();
		return true;
	}

	// user_profiles 행을 잠근 뒤에 부른다.
	private int discardImages(long userId) {
		return this.jdbc.sql("""
				UPDATE profile_images
				   SET image_status = 'DELETED', deleted_at = CURRENT_TIMESTAMP(3), lease_owner = NULL, lease_expires_at = NULL
				 WHERE user_id = :user AND image_status IN ('TEMP', 'ACTIVE', 'FAILED')
				""").param("user", userId).update();
	}

	/**
	 * 연락처를 바꾼다(EP-0266). 학사 원천(ACADEMIC) 연락처는 학사시스템에서 바꾸고 동기화한다. 연락처가 없거나 로컬 원천일 때만 바꾼다.
	 * null은 그 연락처를 지운다(NULL로 저장). 연락처 행이 없으면 LOCAL 행을 만든다.
	 * @throws ApiException 409 {@code contact_managed_by_registrar}
	 */
	@Transactional
	void updateContact(long userId, String phone, String email) {
		String source = this.jdbc.sql("SELECT contact_source FROM user_contacts WHERE user_id = :user FOR UPDATE")
			.param("user", userId)
			.query(String.class)
			.optional()
			.orElse(null);
		if (source != null && !"LOCAL".equals(source)) {
			throw new ApiException(HttpStatus.CONFLICT, "contact_managed_by_registrar",
					"연락처는 학사시스템에서 바꾼 뒤 동기화해 주세요.");
		}
		if (source == null) {
			try {
				this.jdbc.sql("""
						INSERT INTO user_contacts (user_id, phone_number, email_address, contact_source)
						VALUES (:user, :phone, :email, 'LOCAL')
						""").param("user", userId).param("phone", phone).param("email", email).update();
				return;
			}
			catch (DuplicateKeyException ex) {
				throw new ApiException(HttpStatus.CONFLICT, "version_conflict",
						"다른 창에서 먼저 바꾸었습니다. 다시 불러온 뒤 저장해 주세요.");
			}
		}
		this.jdbc.sql("""
				UPDATE user_contacts SET phone_number = :phone, email_address = :email
				 WHERE user_id = :user AND contact_source = 'LOCAL'
				""").param("phone", phone).param("email", email).param("user", userId).update();
	}

	/** 동기화할 때 학사시스템에 보낼 학사 식별자. */
	String externalRef(long userId) {
		return this.jdbc.sql("SELECT external_ref FROM users WHERE user_id = :user")
			.param("user", userId)
			.query(String.class)
			.single();
	}

	/**
	 * 학사시스템 값으로 이름, 소속, 학년과 동기화 시각을 바꾼다(EP-0279). 학사시스템에 연락처가 있으면 연락처도 학사 원천(ACADEMIC)으로
	 * 덮는다. 사용자가 이 화면에서 넣은 로컬 연락처도 학사 연락처가 이긴다. 학사시스템에 연락처가 없으면 연락처는 그대로 둔다. 잠금 순서는
	 * users → user_contacts다.
	 */
	@Transactional
	void applyRegistrar(long userId, RegistrarRecord record) {
		this.jdbc.sql("""
				UPDATE users SET user_name = :name, department_name = :department, grade = :grade,
				       last_synced_at = CURRENT_TIMESTAMP(3)
				 WHERE user_id = :user
				""")
			.param("name", record.name())
			.param("department", record.departmentName())
			.param("grade", record.grade())
			.param("user", userId)
			.update();
		if (record.phone() == null && record.email() == null) {
			return;
		}
		this.jdbc.sql("""
				INSERT INTO user_contacts (user_id, phone_number, email_address, contact_source, synced_at)
				VALUES (:user, :phone, :email, 'ACADEMIC', CURRENT_TIMESTAMP(3))
				ON DUPLICATE KEY UPDATE phone_number = :phone, email_address = :email, contact_source = 'ACADEMIC',
				                        synced_at = CURRENT_TIMESTAMP(3)
				""").param("user", userId).param("phone", record.phone()).param("email", record.email()).update();
	}

	/**
	 * @param departmentName 학과·소속(학사 원천)
	 * @param lastSyncedAt 학사정보를 마지막으로 동기화한 시각
	 * @param registrarSyncAvailable 학사 동기화({@code POST /api/me/profile/sync})를 시도할 수 있는지. 학사시스템 연동이 없거나
	 * break-glass 계정이면 false
	 */
	record Profile(long userId, String name, String role, String departmentName, Integer grade, Contact contact,
			Avatar avatar, LocalDateTime lastSyncedAt, boolean registrarSyncAvailable) {
	}

	/**
	 * @param source academic | local. 연락처가 없으면 null
	 * @param editable 이 화면에서 바꿀 수 있는지. 학사 원천이면 false
	 */
	record Contact(String phone, String email, String source, boolean editable, LocalDateTime syncedAt) {
	}

	/**
	 * @param type color | image
	 * @param color 24색 키 중 하나. 정하지 않았으면 null
	 * @param shortName 아바타 안의 약칭. 정하지 않았으면 null
	 * @param version 저장할 때 보낼 값. 아직 저장하지 않았으면 0
	 * @param photo 사진 상태. 올린 적이 없거나 지웠으면 null
	 */
	record Avatar(String type, String color, String shortName, int version, Photo photo) {
	}

	/**
	 * @param state 마지막으로 올린 사진의 상태. processing(검사·재인코딩 중) | ready | failed
	 * @param url 지금 보이는 사진의 경로. 이미지형일 때만 있다. 새 사진이 processing이어도 앞 사진의 경로다. 응답은 ETag로 다시 확인한다
	 * @param errorCode failed일 때 not_image | too_large | rejected | unavailable | failed
	 */
	record Photo(String state, String url, String errorCode) {
	}

}
