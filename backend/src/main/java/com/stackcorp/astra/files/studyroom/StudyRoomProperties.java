package com.stackcorp.astra.files.studyroom;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * 학습실 설정(ADR-0040). 녹음과 문서 파일 하나의 크기 상한은 upload_policies의 study_recording, study_document 행이 정한다.
 *
 * @param quota 사용자 한 사람의 녹음과 문서 바이트 합 상한
 * @param maxRecordings 사용자 한 사람의 녹음 수 상한
 * @param maxFoldersPerCourse 한 과목 학습실의 폴더 수 상한
 * @param rateLimit 한 사용자가 rateWindow 안에 보낼 수 있는 학습실 변경 명령 수
 * @param playRateLimit 한 사용자가 rateWindow 안에 보낼 수 있는 녹음 재생·문서 내용 요청 수(GET과 HEAD, 탐색마다 하나)
 * @param maxDocuments 사용자 한 사람의 문서 수 상한
 */
@ConfigurationProperties("astra.study-room")
record StudyRoomProperties(@DefaultValue("2GB") DataSize quota, @DefaultValue("500") int maxRecordings,
		@DefaultValue("50") int maxFoldersPerCourse, @DefaultValue("120") int rateLimit,
		@DefaultValue("600") int playRateLimit, @DefaultValue("10m") Duration rateWindow,
		@DefaultValue("500") int maxDocuments) {

	StudyRoomProperties {
		if (maxRecordings < 1 || maxFoldersPerCourse < 1 || rateLimit < 1 || playRateLimit < 1 || maxDocuments < 1) {
			throw new IllegalArgumentException("astra.study-room.max-recordings, max-folders-per-course, rate-limit, "
					+ "play-rate-limit, max-documents는 1 이상이어야 합니다.");
		}
	}

}
