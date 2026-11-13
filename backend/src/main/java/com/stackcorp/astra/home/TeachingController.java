package com.stackcorp.astra.home;

import com.stackcorp.astra.home.TeachingService.TeachingView;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 담당자 메인(교수 홈의 통계 줄, 확인할 일, 다가오는 일정). 요청한 사람이 담당하는 과목만 다룬다. 원본에는 같은 화면이 없어 새 계약이다.
 * 사용자는 session principal의 userId로만 정한다(SC-02). 이 모듈은 identity에 의존하지 않으므로 principal 타입 대신 그 값만 받는다.
 */
@RestController
class TeachingController {

	private final TeachingService teaching;

	TeachingController(TeachingService teaching) {
		this.teaching = teaching;
	}

	/** 담당 과목별 수, 확인할 일, 다가오는 일정, 서버 시각. 담당 과목이 없으면 목록이 모두 비어 있다. */
	@GetMapping("/api/me/teaching")
	ResponseEntity<TeachingView> teaching(@AuthenticationPrincipal(expression = "userId()") Long userId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(this.teaching.teaching(userId));
	}

}
