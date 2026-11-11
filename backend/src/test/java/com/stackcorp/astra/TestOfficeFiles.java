package com.stackcorp.astra;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 테스트용 office 문서(OOXML zip). converter의 가짜 엔진(FakeOfficeEngine)이 읽는 항목만 넣는다. 내용마다 zip 주석에 임의 값을 넣어
 * 같은 내용으로 묶이지 않게 한다.
 */
public final class TestOfficeFiles {

	public static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";

	public static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

	private TestOfficeFiles() {
	}

	/**
	 * @param marker 가짜 엔진의 동작을 바꾸는 항목(astra-test/fail, astra-test/timeout). 없으면 null
	 */
	public static byte[] pptx(int slides, String marker) {
		String[] entries = new String[slides + (marker != null ? 1 : 0)];
		for (int i = 0; i < slides; i++) {
			entries[i] = "ppt/slides/slide" + (i + 1) + ".xml";
		}
		if (marker != null) {
			entries[slides] = marker;
		}
		return zip(entries);
	}

	public static byte[] docx() {
		return zip("word/document.xml");
	}

	private static byte[] zip(String... entries) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			zip.setComment(UUID.randomUUID().toString());
			zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
			zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			for (String name : entries) {
				zip.putNextEntry(new ZipEntry(name));
				zip.write("<x/>".getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return bytes.toByteArray();
	}

}
