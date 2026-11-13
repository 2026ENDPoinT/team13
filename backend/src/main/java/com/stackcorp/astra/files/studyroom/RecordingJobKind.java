package com.stackcorp.astra.files.studyroom;

/** 녹음 작업 종류(study_room_recording_jobs.job_kind). */
enum RecordingJobKind {

	TRANSCRIPTION("transcription"), SUMMARY("summary");

	private final String value;

	RecordingJobKind(String value) {
		this.value = value;
	}

	String value() {
		return this.value;
	}

	static RecordingJobKind of(String value) {
		return "summary".equals(value) ? SUMMARY : TRANSCRIPTION;
	}

}
