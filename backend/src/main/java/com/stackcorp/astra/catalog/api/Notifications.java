package com.stackcorp.astra.catalog.api;

import java.time.LocalDateTime;
import java.util.Collection;

import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.identity.api.UserAudiences.UserAudience;

/**
 * 알림 생성(architecture.md 4.1, ADR-0027). 알림을 일으키는 모듈이 원래 변경과 같은 transaction 안에서 부른다. transaction 밖에서
 * 부르면 실패한다. 원래 변경이 되돌아가면 알림도 남지 않는다. 대상 ID와 학습활동 ID는 값으로만 저장하고 catalog는 그 행을 읽지 않는다.
 * <p>
 * 알림은 대상이 받는 사람에게 보일 때만 있어야 한다. 대상을 더 볼 수 없게 되면(삭제, 비공개, 예약으로 되돌림) 부르는 쪽이 같은
 * transaction에서 {@link #withdraw}를 부른다. 수강 취소처럼 과목 관계가 끊긴 경우는 catalog가 읽을 때 거른다.
 */
public interface Notifications {

	/** {@link #send} 한 번에 받을 수 있는 사람 수 상한. */
	int MAX_RECIPIENTS = 1000;

	/**
	 * 과목에서 그 역할인 사람 모두에게 보낸다. 받는 사람은 알림을 만드는 시점의 구성원이다.
	 * <ul>
	 * <li>{@code visibleAt}이 없거나 DB 시계로 지났으면 지금 만든다.</li>
	 * <li>미래면 그 시각이 지난 뒤 worker가 그때의 구성원에게 만든다.</li>
	 * <li>같은 (유형, 대상)으로 다시 부르면 이미 보낸 사람에게 다시 만들지 않는다. 문장은 새 제목으로 바꾼다. 보낸 뒤 {@code visibleAt}이
	 * 미래로 바뀌면 보낸 알림을 지우고 다시 예약한다.</li>
	 * </ul>
	 */
	void publish(CourseNotification notification);

	/**
	 * 정한 사람에게 지금 보낸다. 과목이 있으면 그 과목의 구성원만 받는다. 같은 사람에게 같은 (유형, 대상)은 한 번만 만든다.
	 * @throws IllegalArgumentException 받는 사람이 {@link #MAX_RECIPIENTS}명을 넘을 때
	 */
	void send(DirectNotification notification);

	/** 대상을 더 볼 수 없게 됐다. 그 대상의 이 유형 알림과 예약을 모두 지운다. 없으면 아무것도 하지 않는다. */
	void withdraw(NotificationKind kind, long targetId);

	/**
	 * 과목 없이 기관 전체의 대상(모든 사용자, 학생, 교직원)에게 보낸다(알림마당 공지, ADR-0036). 사건 하나만 남기고, 받는 사람은
	 * worker가 보일 시각 뒤에 그때의 대상 사용자를 사용자 ID 순으로 나눠 읽어 만든다. 한 묶음이 한 transaction이다.
	 * <ul>
	 * <li>{@code visibleAt}이 없거나 지났으면 다음 worker 주기에 보낸다. 미래면 그 시각 뒤에 보낸다.</li>
	 * <li>같은 (유형, 대상)으로 다시 부르면 이미 받은 사람에게 다시 만들지 않고 문장만 새 제목으로 바꾼다. 대상이 바뀌거나 보낸 뒤
	 * {@code visibleAt}이 미래로 바뀌면 보낸 알림을 지우고 처음부터 다시 보낸다.</li>
	 * <li>거둘 때는 {@link #withdraw}를 같은 유형과 대상 ID로 부른다.</li>
	 * </ul>
	 * @throws IllegalArgumentException 과목 없는 유형({@link NotificationKind#SITE_NOTICE})이 아닐 때
	 */
	void publishSite(SiteNotification notification);

	/**
	 * @param audience 받는 사람의 과목 내 역할({@link CourseMemberships#roleOf}와 같은 판정)
	 * @param targetId 유형이 정한 대상의 ID. 학습활동 유형이면 학습활동 ID
	 * @param subject 대상 제목(일반 텍스트). 문장 안에 따옴표로 들어간다
	 * @param visibleAt 대상이 받는 사람에게 보이기 시작하는 일시(DB 시계 기준). null이면 지금
	 */
	record CourseNotification(NotificationKind kind, long courseId, CourseRole audience, long targetId, String subject,
			LocalDateTime visibleAt) {
	}

	/**
	 * @param courseId 관련 과목. 없으면(쪽지 등) null
	 */
	record DirectNotification(NotificationKind kind, Collection<Long> recipients, Long courseId, long targetId,
			String subject) {
	}

	/**
	 * @param audience 받는 사람(알림마당 설문 대상과 같은 어휘). 활동 중인 계정만 받고 break-glass 계정은 받지 않는다
	 * @param targetId 유형이 정한 대상의 ID(알림마당 공지 게시글 ID)
	 * @param subject 대상 제목(일반 텍스트). 문장 안에 따옴표로 들어간다
	 * @param visibleAt 대상이 보이기 시작하는 일시(DB 시계 기준). null이면 지금
	 */
	record SiteNotification(NotificationKind kind, UserAudience audience, long targetId, String subject,
			LocalDateTime visibleAt) {
	}

}
