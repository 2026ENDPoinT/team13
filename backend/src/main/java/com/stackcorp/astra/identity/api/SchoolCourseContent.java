package com.stackcorp.astra.identity.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link SchoolCourseRooms}가 학교 강의실에서 읽어 오는 내용. 학교 화면의 값이며 시각은 학교 화면의 벽시계(기관 시간대)다.
 */
public final class SchoolCourseContent {

	/**
	 * 학생 본인이 과제에 낸 파일의 {@link SchoolFile#activityType()}. 그 학생에게만 보이는 파일이다(파일모음에도 나오지 않는다).
	 */
	public static final String SUBMISSION = "report_submission";

	private SchoolCourseContent() {
	}

	/**
	 * 학교 첨부 파일 하나. 받을 때 학교에 그대로 돌려줄 키를 담는다.
	 * @param fileSeq 학교 파일 키({@code FILE_SEQ})
	 * @param contentSeq 학교 내용 키({@code CONTENT_SEQ}). 받기 요청에 그대로 쓴다
	 * @param name 화면의 파일 이름
	 * @param sizeBytes 화면의 크기 표시(예 {@code 3.2MB})를 바이트로 바꾼 근사값. 없으면 null
	 * @param activityType 파일이 붙은 학습활동의 학교 메뉴 ID({@code lecture_material}|{@code report} 등). 모르면 null
	 * @param activityItemId 그 학습활동의 학교 번호. 모르면 null
	 */
	public record SchoolFile(String fileSeq, String contentSeq, String name, Long sizeBytes, String activityType,
			String activityItemId) {
	}

	/**
	 * 글 상세(자료, 공지).
	 * @param title 제목
	 * @param postedAt 공개일·게시일. 읽지 못했으면 null
	 * @param author 작성자 표시. 없으면 null
	 * @param body 본문을 글자로 바꾼 것(문단은 줄바꿈). 없으면 null
	 * @param files 첨부. 화면 순서
	 */
	public record SchoolPost(String title, LocalDateTime postedAt, String author, String body,
			List<SchoolFile> files) {

		public SchoolPost {
			files = List.copyOf(files);
		}

	}

	/**
	 * 강의실 공지 목록의 공지 하나. 목록 화면의 값이다(목록은 학교 쪽 읽음을 바꾸지 않는다).
	 * @param itemId 학교 공지 번호({@code ARTL_NUM})
	 * @param excerpt 목록의 본문 앞부분. 없으면 null
	 * @param postedAt 게시일. 화면에 연도가 없어 과목 학년도로 정한다. 읽지 못했으면 null
	 * @param viewCount 조회수. 없으면 null
	 * @param read 로그인한 학생이 학교에서 읽었는지(목록의 안 읽음 표시가 없으면 true)
	 * @param expired 공지 만료 표시
	 * @param url 이 공지를 학교 e-Class에서 여는 주소
	 */
	public record SchoolNotice(String itemId, String title, String excerpt, LocalDateTime postedAt, Integer viewCount,
			boolean read, boolean expired, String url) {
	}

	/**
	 * 과제 상세({@code report_view_form.acl})와 로그인한 학생 본인의 제출 정보.
	 * @param openAt 화면의 공개일. 읽지 못했으면 null
	 * @param closeAt 화면의 마감일. 읽지 못했으면 null
	 * @param body 과제 설명을 글자로 바꾼 것. 없으면 null
	 * @param files 과제 첨부(같은 과목 수강생이 함께 보는 파일). 화면 순서
	 * @param submissionLabel 학교 화면의 제출 상태 표시(예 {@code 정상제출}). 보여 주기만 하고 판정에 쓰지 않는다. 없으면 null
	 * @param submittedAt 학생 본인의 제출 일시(제출 정보의 일시). 제출하지 않았으면 null
	 * @param submissionBody 학생 본인이 글로 낸 내용. 없으면 null
	 * @param submittedFiles 학생 본인이 낸 파일. 그 학생에게만 보인다
	 */
	public record SchoolAssignment(String title, LocalDateTime openAt, LocalDateTime closeAt, String body,
			List<SchoolFile> files, String submissionLabel, LocalDateTime submittedAt, String submissionBody,
			List<SchoolFile> submittedFiles) {

		public SchoolAssignment {
			files = List.copyOf(files);
			submittedFiles = List.copyOf(submittedFiles);
		}

	}

	/**
	 * 받은 파일.
	 * @param sizeBytes 받은 바이트 수
	 */
	public record SchoolDownload(long sizeBytes) {
	}

}
