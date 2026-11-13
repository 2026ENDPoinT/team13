package com.stackcorp.astra.files.api;

public enum FileAction {
	/** 파일과 그 파생물(게시한 요약, 쪽 이미지)을 본다. */
	READ,
	/** 파일의 파생물을 만들고 바꾼다(요약 생성, 게시). */
	MANAGE
}
