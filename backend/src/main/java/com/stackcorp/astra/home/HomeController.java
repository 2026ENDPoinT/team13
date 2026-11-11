package com.stackcorp.astra.home;

import com.stackcorp.astra.home.HomeService.HomeView;
import com.stackcorp.astra.home.HomeService.TodoList;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 메인 화면 집계(EP-0179, EP-0191, EP-0194, EP-0144의 대체 계약, ADR-0005). 요청한 사용자의 과목만 다룬다.
 * 사용자는 session principal의 userId로만 정한다(SC-02). 이 모듈은 identity에 의존하지 않으므로 principal 타입 대신 그 값만 받는다.
 */
@RestController
class HomeController {

	private final HomeService home;

	HomeController(HomeService home) {
		this.home = home;
	}

	/**
	 * 과목과 역할, 과목별 안 읽은 공지 수, 최근 공지 5개, 알림마당 최근 공지 5개와 읽지 않은 고정 공지(진입 공지), 할 일 앞 5개와 전체 수,
	 * 새로 공개된 성적 항목 5개(점수 없음), 안 읽은 쪽지 수, 안 읽은 알림 수, 서버 시각. 알림마당 공지의 대상은 session의 기관 역할로 정한다.
	 */
	@GetMapping("/api/me/home")
	ResponseEntity<HomeView> home(@AuthenticationPrincipal(expression = "userId()") Long userId,
			@AuthenticationPrincipal(expression = "role().name()") String role) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(this.home.home(userId, role));
	}

	/** 할 일 전체. 마감 임박순이고 nextCursor로 다음 쪽을 받는다. 본인이 끝낸 활동은 없다. 쪽 사이에 마감이 지나도 남은 항목이 겹치거나 빠지지 않는다. */
	@GetMapping("/api/me/todo")
	ResponseEntity<TodoList> todo(@AuthenticationPrincipal(expression = "userId()") Long userId,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int size) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(this.home.todo(userId, cursor, size));
	}

}
