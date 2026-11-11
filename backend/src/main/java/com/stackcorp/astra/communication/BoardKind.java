package com.stackcorp.astra.communication;

/**
 * 과목마다 하나씩 두는 강의실 게시판(ADR-0013, ADR-0024). 게시판 행은 첫 글을 쓸 때 {@link CourseBoards}가 만든다.
 * 기능 스위치(익명, 비공개, 해결 상태, 댓글)는 이 값으로 정하고 같은 값을 boards 행에도 적는다.
 */
enum BoardKind {

	/** 강의실 공지(EP-0044~0047). 담당자만 쓴다. */
	NOTICE("K001", "NOTICE", "공지사항", false, false, false, false),

	/** 강의실 질의응답(EP-0059~0074). 학생이 묻고 구성원이 답글을 단다. 익명·비공개·해결 상태·FAQ를 쓴다. */
	QNA("K002", "QUESTION", "질의응답", true, true, true, false),

	/** 강의실 열린게시판(EP-0016~0022). 구성원 누구나 쓰고 댓글을 단다. */
	OPEN("K004", "ARTICLE", "열린게시판", false, false, false, true);

	/** boards.board_type */
	final String type;

	/** posts.post_kind(최상위 글) */
	final String postKind;

	final String defaultName;

	/** 작성자 이름을 다른 학생에게 숨길 수 있다. 담당자에게는 보인다. */
	final boolean anonymous;

	/** 작성자와 담당자만 보는 글을 쓸 수 있다. */
	final boolean secret;

	/** 해결 상태와 FAQ 지정, 답글을 쓴다. */
	final boolean answers;

	final boolean comments;

	BoardKind(String type, String postKind, String defaultName, boolean anonymous, boolean secret, boolean answers,
			boolean comments) {
		this.type = type;
		this.postKind = postKind;
		this.defaultName = defaultName;
		this.anonymous = anonymous;
		this.secret = secret;
		this.answers = answers;
		this.comments = comments;
	}

}
