package com.stackcorp.astra.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.stackcorp.astra.files.DocumentRenditionService.StoredDocument;
import com.stackcorp.astra.files.api.DocumentRenditions.RenditionPage;

import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 저장소에 둔 rendition 문서 정보(document.json)의 형식. DocumentRenditionService가 쓰는 JsonMapper로 읽는다.
 */
class StoredDocumentTests {

	private static final JsonMapper JSON = DocumentRenditionService.JSON;

	@Test
	void readsRenditionsStoredBeforePagesCarriedImageUnavailable() {
		StoredDocument doc = JSON.readValue("""
				{"title":null,"pages":[{"page":1,"text":"a","textTruncated":false,"modelImageBytes":3}]}
				""", StoredDocument.class);

		assertThat(doc.pages()).containsExactly(new RenditionPage(1, "a", false, 3, false));
	}

	@Test
	void readsANullImageUnavailableAsFalse() {
		StoredDocument doc = JSON.readValue("""
				{"title":null,"pages":[{"page":1,"text":"a","textTruncated":false,"modelImageBytes":3,"imageUnavailable":null}]}
				""", StoredDocument.class);

		assertThat(doc.pages()).containsExactly(new RenditionPage(1, "a", false, 3, false));
	}

	// imageUnavailable 말고는 예전 파일에도 늘 있던 값이다. 빠지거나 null이면 손상된 파일이므로 0이나 false로 읽지 않는다.
	@Test
	void rejectsAPageMissingAValueEveryStoredRenditionHas() {
		List<String> pages = List.of("""
				{"text":"a","textTruncated":false,"modelImageBytes":3}""", """
				{"page":1,"text":"a","modelImageBytes":3}""", """
				{"page":1,"text":"a","textTruncated":false}""", """
				{"page":1,"text":"a","textTruncated":false,"modelImageBytes":null}""");
		for (String page : pages) {
			assertThatExceptionOfType(MismatchedInputException.class).as(page)
				.isThrownBy(() -> JSON.readValue("{\"title\":null,\"pages\":[" + page + "]}", StoredDocument.class));
		}
	}

	@Test
	void keepsImageUnavailableAcrossAWriteAndRead() {
		StoredDocument doc = new StoredDocument("t", List.of(new RenditionPage(1, "a", false, 3, true)));

		assertThat(JSON.readValue(JSON.writeValueAsBytes(doc), StoredDocument.class)).isEqualTo(doc);
	}

}
