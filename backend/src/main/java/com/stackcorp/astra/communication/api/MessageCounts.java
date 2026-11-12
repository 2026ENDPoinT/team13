package com.stackcorp.astra.communication.api;

/**
 * 메인 화면과 헤더 배지에 쓰는 쪽지 수(EP-0239). 쿼리 한 번이고 색인만 읽는다.
 */
public interface MessageCounts {

	/** 받은함에 보이는 안 읽은 쪽지 수. 회수되거나 지운 쪽지는 세지 않는다. */
	int unread(long userId);

}
