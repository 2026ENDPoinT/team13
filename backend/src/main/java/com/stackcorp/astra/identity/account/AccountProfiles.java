package com.stackcorp.astra.identity.account;

import java.time.LocalDateTime;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

	Profile find(long userId) {
		return this.jdbc.sql("""
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
					rs.getString("short_name"), type == null ? 0 : rs.getInt("profile_version"));
			return new Profile(rs.getLong("user_id"), rs.getString("user_name"), rs.getString("user_role"),
					rs.getString("department_name"), rs.getObject("grade", Integer.class), contact, avatar,
					rs.getObject("last_synced_at", LocalDateTime.class));
		}).single();
	}

	/**
	 * 색상형 아바타로 저장한다(EP-0271). 이미지형이었다면 이미지 참조를 푼다(EP-0278).
	 * @param version client가 읽은 version. 행이 없으면 0이다
	 * @throws ApiException 409 {@code version_conflict}
	 */
	@Transactional
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
	}

	/**
	 * 연락처를 바꾼다(EP-0266). 학사 원천(ACADEMIC) 연락처는 학사시스템에서 바꾸고 동기화한다. 연락처가 없거나 로컬 원천일 때만 바꾼다.
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

	/**
	 * @param departmentName 학과·소속(학사 원천)
	 * @param lastSyncedAt 학사정보를 마지막으로 동기화한 시각
	 */
	record Profile(long userId, String name, String role, String departmentName, Integer grade, Contact contact,
			Avatar avatar, LocalDateTime lastSyncedAt) {
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
	 */
	record Avatar(String type, String color, String shortName, int version) {
	}

}
