package com.stackcorp.astra.files.studyroom;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.stackcorp.astra.files.api.SpeechToText;
import com.stackcorp.astra.files.api.TextModel;

/**
 * 녹음 전사·요약의 가짜 제공자(ADR-0041). 공통 context에서는 꺼져 있어(Deepgram key가 없는 배포와 같다) 다른 테스트의 녹음에는 작업이
 * 생기지 않는다. 전사·요약 테스트가 켜고, 끝나면 {@link #reset}으로 끈다. 모델은 진짜 adapter(LlmTextModel)를 거쳐 FakeLlm을 부른다.
 */
public final class RecordingStudyFakes {

	private RecordingStudyFakes() {
	}

	/** 원본을 끝까지 읽고 script의 결과를 돌려준다. */
	public static final class FakeSpeechToText implements SpeechToText {

		public volatile boolean enabled;

		/** (요청, 읽은 원본 바이트 수) → 결과. 예외를 던져 실패를 흉내 낸다. */
		public volatile BiFunction<Audio, Long, Transcript> script = FakeSpeechToText::lecture;

		public final List<Long> calls = new CopyOnWriteArrayList<>();

		@Override
		public boolean available() {
			return this.enabled;
		}

		@Override
		public Transcript transcribe(Audio audio) {
			long read;
			try (InputStream in = audio.opener().open()) {
				read = in.transferTo(OutputStream.nullOutputStream());
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
			this.calls.add(read);
			return this.script.apply(audio, read);
		}

		public void reset() {
			this.enabled = false;
			this.script = FakeSpeechToText::lecture;
			this.calls.clear();
		}

		/** 발화 셋, 순서가 섞여 있고 빈 발화가 하나 있다. 글에 받은 바이트 수를 넣는다. */
		public static Transcript lecture(Audio audio, long bytes) {
			return new Transcript(5.0, List.of(new Utterance(2.345, "  두 번째\u0007 문장입니다. "),
					new Utterance(0.0, "첫 문장 bytes=" + bytes), new Utterance(3.0, "   "),
					new Utterance(4.5, "세 번째 Stack 문장")));
		}

	}

	/** 진짜 adapter에 넘기되 설정이 없는 배포를 흉내 낼 수 있다. */
	public static final class SwitchableTextModel implements TextModel {

		public volatile boolean enabled = true;

		private final TextModel delegate;

		SwitchableTextModel(TextModel delegate) {
			this.delegate = delegate;
		}

		@Override
		public boolean available() {
			return this.enabled && this.delegate.available();
		}

		@Override
		public String complete(String system, String user, int maxOutputTokens) {
			return this.delegate.complete(system, user, maxOutputTokens);
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	public static class Configuration {

		@Bean
		@Primary
		FakeSpeechToText fakeSpeechToText() {
			return new FakeSpeechToText();
		}

		@Bean
		@Primary
		SwitchableTextModel switchableTextModel(@Qualifier("llmTextModel") TextModel delegate) {
			return new SwitchableTextModel(delegate);
		}

	}

}
