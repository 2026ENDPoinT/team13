package com.stackcorp.astra.summary.web;

import org.springframework.stereotype.Component;

import com.stackcorp.astra.files.api.FileAccess;
import com.stackcorp.astra.files.api.FileAction;
import com.stackcorp.astra.identity.api.Actor;

/**
 * 요약 권한(ADR-0006 9번). 요약은 원본 파일의 권한을 그대로 따른다. 생성·취소·재시도·게시와 후보 확인은 그 자료를 관리할 수
 * 있는 사람(과목 담당자)만, 게시본은 그 자료를 볼 수 있는 사람만 한다. role만 보고 허용하지 않는다.
 */
@Component
class SummaryPolicy {

	private final FileAccess files;

	SummaryPolicy(FileAccess files) {
		this.files = files;
	}

	boolean canManage(Actor actor, long courseId, long fileId) {
		return this.files.allowed(actor.userId(), courseId, fileId, FileAction.MANAGE);
	}

	boolean canRead(Actor actor, long courseId, long fileId) {
		return this.files.allowed(actor.userId(), courseId, fileId, FileAction.READ);
	}

}
