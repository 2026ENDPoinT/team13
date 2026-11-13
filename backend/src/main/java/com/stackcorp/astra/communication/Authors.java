package com.stackcorp.astra.communication;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.stackcorp.astra.files.api.AvatarPhotos;
import com.stackcorp.astra.identity.api.UserNames;

/**
 * 글·댓글·쪽지 옆에 그리는 사람(이름, 사용자 ID, 사진 유무). 누구의 이름을, 누구의 ID까지 드러낼지는 부르는 쪽이 화면의 공개 범위로
 * 정한다. 사람 수와 관계없이 쿼리 세 번 이하다(이름 한 번, {@link AvatarPhotos} 두 번 이하).
 * <p>
 * 사진 유무는 ID를 드러내는 사람에게만 묻고, 요청한 사람이 {@code GET /api/users/{userId}/avatar}로 사진을 받을 수 있을 때만
 * true다(ADR-0038). 화면이 사진이 없는 사람의 아바타를 요청해 404를 받지 않게 한다.
 */
@Component
class Authors {

	private final UserNames names;

	private final AvatarPhotos photos;

	Authors(UserNames names, AvatarPhotos photos) {
		this.names = names;
		this.photos = photos;
	}

	/** @param shown 이름과 ID를 드러낼 사람. 중복과 null(드러내지 않는 사람)이 있어도 된다 */
	Known of(long viewerId, Collection<Long> shown) {
		return of(viewerId, shown, List.of());
	}

	/**
	 * @param shown 이름과 ID를 드러낼 사람. 중복과 null이 있어도 된다
	 * @param named 이름만 드러낼 사람. {@code shown}에도 있으면 ID까지 드러낸다
	 */
	Known of(long viewerId, Collection<Long> shown, Collection<Long> named) {
		Set<Long> ids = shown.stream().filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
		List<Long> all = Stream.concat(ids.stream(), named.stream().filter(Objects::nonNull)).distinct().toList();
		if (all.isEmpty()) {
			return new Known(Map.of(), Set.of(), Set.of());
		}
		Map<Long, String> found = this.names.namesOf(all);
		List<Long> existing = ids.stream().filter(found::containsKey).toList();
		return new Known(found, ids, this.photos.visibleTo(viewerId, existing));
	}

	/** @param shown null이면 드러내지 않는 사람이고 쿼리가 없다 */
	Author one(long viewerId, Long shown) {
		return shown == null ? Author.HIDDEN : of(viewerId, List.of(shown)).author(shown);
	}

	/** 이름만 드러내고 사용자 ID와 사진 유무는 숨긴다. 쿼리 한 번이다. */
	Author nameOnly(long userId) {
		return of(0, List.of(), List.of(userId)).author(userId);
	}

	/**
	 * @param names 있는 사용자의 이름. 계정이 없으면 빠진다
	 * @param shown ID까지 드러내는 사람
	 * @param photos 요청한 사람이 사진을 받을 수 있는 사람
	 */
	record Known(Map<Long, String> names, Set<Long> shown, Set<Long> photos) {

		/** @param userId null이면 드러내지 않는 사람이다 */
		Author author(Long userId) {
			if (userId == null || !this.names.containsKey(userId)) {
				return Author.HIDDEN;
			}
			if (!this.shown.contains(userId)) {
				return new Author(null, this.names.get(userId), false);
			}
			return new Author(userId, this.names.get(userId), this.photos.contains(userId));
		}

	}

	/**
	 * @param id 드러내지 않거나 계정이 없으면 null
	 * @param name 드러내지 않거나 계정이 없으면 null
	 * @param hasPhoto id가 없으면 false
	 */
	record Author(Long id, String name, boolean hasPhoto) {

		static final Author HIDDEN = new Author(null, null, false);

	}

}
