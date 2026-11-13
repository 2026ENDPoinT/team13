package com.stackcorp.astra.communication;

/**
 * 임시저장 한 칸의 위치. 사용자는 넣지 않는다. 언제나 session principal이다(SC-02). 과목과 게시판은 부르는 쪽이 경로에서 받아 권한을
 * 판정한 값만 넣는다. 과목이 키에 들어 있으므로 다른 과목 폼에 그 과목의 초안이 뜨지 않는다(COMM-08, T-20). 게시글 초안은
 * {@code new DraftKey("POST", boardId, courseId, targetPostId, formKey)}로 같은 표와 {@link DraftService}를 쓴다.
 *
 * @param contextType POST|MESSAGE
 * @param boardId 게시글이면 게시판. 쪽지면 null
 * @param courseId 과목 게시판이면 과목. 전역 게시판·쪽지면 null
 * @param targetPostId 고치는 글. 새 글이면 null
 * @param formKey 같은 게시판의 폼 구분. 게시글은 원본 FROM_DIV(1|2), 쪽지는 compose
 */
record DraftKey(String contextType, Long boardId, Long courseId, Long targetPostId, String formKey) {

	/** 쪽지쓰기 폼. 사용자마다 하나다. */
	static final DraftKey MESSAGE_COMPOSE = new DraftKey("MESSAGE", null, null, null, "compose");

}
