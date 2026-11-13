package com.stackcorp.astra.communication;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.stackcorp.astra.communication.api.CommunityNoticeDigest;
import com.stackcorp.astra.identity.api.UserRole;

import org.springframework.stereotype.Service;

@Service
class CommunityNoticeDigestQueries implements CommunityNoticeDigest {

	private final CommunityNoticeRepository notices;

	CommunityNoticeDigestQueries(CommunityNoticeRepository notices) {
		this.notices = notices;
	}

	@Override
	public Headlines headlines(long userId, String institutionRole, LocalDateTime now, int latestLimit,
			int forMeLimit) {
		UserRole role = Arrays.stream(UserRole.values())
			.filter((r) -> r.name().equals(institutionRole))
			.findFirst()
			.orElse(null);
		if (role == null) {
			return new Headlines(List.of(), List.of());
		}
		Set<String> audiences = CommunityService.audiences(role);
		return new Headlines(headlines(userId, audiences, now, false, latestLimit),
				headlines(userId, audiences, now, true, forMeLimit));
	}

	private List<Headline> headlines(long userId, Set<String> audiences, LocalDateTime now, boolean forMe, int limit) {
		return limit < 1 ? List.of()
				: this.notices.headlines(userId, audiences, now, forMe, limit)
					.stream()
					.map((r) -> new Headline(r.noticeId(), r.title(), r.pinned(), r.publishedAt(), r.read()))
					.toList();
	}

}
