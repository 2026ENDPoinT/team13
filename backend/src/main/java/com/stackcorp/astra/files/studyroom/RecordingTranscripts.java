package com.stackcorp.astra.files.studyroom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import com.stackcorp.astra.files.api.SpeechToText.Utterance;
import com.stackcorp.astra.files.studyroom.RecordingStudyViews.Segment;
import com.stackcorp.astra.files.studyroom.RecordingStudyViews.Transcription;

/**
 * 제공자 발화를 계약 형식의 전사로 바꾼다(ADR-0041). 글은 제어 문자를 빼고 공백을 하나로 하며 빈 발화는 버린다. 시각은
 * {@code [0, 실제 길이]}로 자르고 0.01초로 내린 뒤 오름차순으로 정렬한다(같으면 제공자 순서). {@code text}는 segment 글을 줄바꿈으로
 * 이은 것이다. 말소리가 없으면 segment가 없고 text는 빈 문자열이다.
 */
final class RecordingTranscripts {

	private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}]");

	private static final Pattern SPACE = Pattern.compile("\\s+");

	private RecordingTranscripts() {
	}

	/** @param actualSeconds 실제 길이(초) */
	static Transcription build(List<Utterance> utterances, double actualSeconds) {
		List<Segment> segments = new ArrayList<>();
		for (Utterance utterance : utterances) {
			String text = SPACE.matcher(CONTROL.matcher(utterance.text()).replaceAll(" ")).replaceAll(" ").strip();
			if (text.isEmpty()) {
				continue;
			}
			double at = Math.min(Math.max(0, utterance.start()), actualSeconds);
			segments.add(new Segment(Math.floor(at * 100) / 100, text));
		}
		segments.sort(Comparator.comparingDouble(Segment::at));
		return new Transcription(segments, String.join("\n", segments.stream().map(Segment::text).toList()));
	}

	/**
	 * 실제 길이(초, 올림). 제공자가 길이를 주지 않으면 선언 길이와 마지막 발화 시각 가운데 큰 값이다.
	 *
	 * @param declaredMs 올릴 때 보낸 길이
	 */
	static int actualSeconds(Double providerSeconds, long declaredMs, List<Utterance> utterances) {
		double seconds = providerSeconds != null ? providerSeconds
				: Math.max(declaredMs / 1000.0,
						utterances.stream().mapToDouble(Utterance::start).filter(Double::isFinite).max().orElse(0));
		return (int) Math.min(Integer.MAX_VALUE, Math.ceil(seconds));
	}

	/** 실제 길이가 선언 길이보다 허용 범위(60초와 선언의 10% 가운데 큰 값)를 넘게 길다. */
	static boolean understated(int actualSeconds, long declaredMs) {
		double declared = declaredMs / 1000.0;
		return actualSeconds > declared + Math.max(60, declared * 0.1);
	}

}
