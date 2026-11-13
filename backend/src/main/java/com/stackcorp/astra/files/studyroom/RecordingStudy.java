package com.stackcorp.astra.files.studyroom;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import com.stackcorp.astra.files.api.SpeechToText;
import com.stackcorp.astra.files.api.TextModel;
import com.stackcorp.astra.files.studyroom.RecordingJobRecords.JobRow;
import com.stackcorp.astra.files.studyroom.RecordingStudyViews.Job;
import com.stackcorp.astra.files.studyroom.RecordingStudyViews.Summary;
import com.stackcorp.astra.files.studyroom.RecordingStudyViews.Transcription;

import tools.jackson.databind.json.JsonMapper;

/**
 * 학습실 녹음의 전사·요약 작업을 api 쪽에서 다룬다(ADR-0041). 녹음을 만들 때 작업을 queued로 만들고, 녹음 하나를 돌려줄 때 작업
 * 상태와 결과를 싣고, 녹음을 지울 때 작업과 결과를 지운다. 모두 부르는 쪽의 transaction 안에서 소유자를 조건으로 한다.
 * <p>
 * 어떤 작업을 만들지는 지금 배포의 설정으로 정한다. 음성 인식 제공자가 없으면 아무것도 만들지 않고, 모델이 없으면 전사만 만든다.
 * 이미 만든 작업은 설정이 바뀌어도 그대로 보인다.
 */
@Component
@EnableConfigurationProperties(RecordingStudyProperties.class)
class RecordingStudy {

	private final RecordingJobRecords jobs;

	private final SpeechToText speech;

	private final TextModel model;

	private final JsonMapper json;

	private final RecordingStudyProperties properties;

	RecordingStudy(RecordingJobRecords jobs, SpeechToText speech, TextModel model, JsonMapper json,
			RecordingStudyProperties properties) {
		this.jobs = jobs;
		this.speech = speech;
		this.model = model;
		this.json = json;
		this.properties = properties;
	}

	/** 녹음 행을 만든 transaction에서 부른다. */
	void created(long owner, long recordingId) {
		if (!this.speech.available()) {
			return;
		}
		this.jobs.insert(owner, recordingId, RecordingJobKind.TRANSCRIPTION);
		if (this.model.available()) {
			this.jobs.insert(owner, recordingId, RecordingJobKind.SUMMARY);
		}
	}

	/** 녹음 행을 지우기 전에 같은 transaction에서 부른다. 결과도 함께 지운다. */
	void deleted(long owner, long recordingId) {
		this.jobs.deleteOfRecording(owner, recordingId);
	}

	/** 소유자의 녹음 하나의 작업 상태. 작업이 없는 종류는 들어 있지 않다. */
	Map<RecordingJobKind, Job> of(long owner, long recordingId) {
		Map<RecordingJobKind, Job> views = new EnumMap<>(RecordingJobKind.class);
		for (JobRow row : this.jobs.ofRecording(owner, recordingId)) {
			views.put(row.kind(), view(row));
		}
		return views;
	}

	private Job view(JobRow row) {
		return switch (row.state()) {
			case Job.READY -> new Job(Job.READY, row.kind() == RecordingJobKind.TRANSCRIPTION
					? this.json.readValue(row.resultJson(), Transcription.class)
					: this.json.readValue(row.resultJson(), Summary.class), null);
			case Job.FAILED -> new Job(Job.FAILED, null,
					RecordingStudyFailures.message(row.kind(), row.failureCode(), this.properties));
			case Job.PROCESSING -> new Job(Job.PROCESSING, null, null);
			default -> new Job(Job.QUEUED, null, null);
		};
	}

}
