package com.stackcorp.astra.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.apache.tomcat.util.http.fileupload.FileUploadException;
import org.apache.tomcat.util.http.fileupload.InvalidFileNameException;
import org.apache.tomcat.util.http.fileupload.MultipartStream.IllegalBoundaryException;
import org.apache.tomcat.util.http.fileupload.MultipartStream.MalformedStreamException;
import org.apache.tomcat.util.http.fileupload.impl.IOFileUploadException;
import org.apache.tomcat.util.http.fileupload.impl.InvalidContentTypeException;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartException;

import jakarta.servlet.ServletException;

/**
 * multipart 본문을 나누지 못한 예외를 client 탓(400)과 서버 탓(500)으로 가른다. Tomcat parser가 실제로 감싸는 모양 그대로 만든다
 * ({@code Request.parseParts}, {@code FileUploadBase.parseRequest}). 실제 요청은
 * {@code files.upload.MultipartParseFailureTests}가 본다.
 */
class MultipartFailureTests {

	@Test
	void malformedBodiesAreTheClientsFault() {
		// part 본문 도중에 끝났다
		assertThat(clientFault(new IOFileUploadException("Processing of multipart/form-data request failed.",
				new MalformedStreamException("Stream ended unexpectedly"))))
			.isTrue();
		// 다음 boundary를 찾다 끝났다
		assertThat(clientFault(new FileUploadException("Stream ended unexpectedly",
				new MalformedStreamException("Stream ended unexpectedly"))))
			.isTrue();
		assertThat(clientFault(
				new FileUploadException("the request was rejected because no multipart boundary was found")))
			.isTrue();
		assertThat(clientFault(new ServletException(new InvalidContentTypeException("not multipart/form-data"))))
			.isTrue();
		// 파일 이름에 NUL이 있다
		assertThat(clientFault(new ServletException(new InvalidFileNameException("a\u0000.png", "Invalid file name"))))
			.isTrue();
		// 안쪽 multipart의 boundary 길이가 바깥과 다르다
		assertThat(clientFault(new FileUploadException("The length of a boundary token cannot be changed",
				new IllegalBoundaryException("The length of a boundary token cannot be changed"))))
			.isTrue();
	}

	@Test
	void spoolFailuresAreTheServersFault() {
		// 임시 파일에 쓰지 못했다(디스크가 찼다, 권한이 없다)
		assertThat(clientFault(new IOFileUploadException("Processing of multipart/form-data request failed.",
				new IOException("No space left on device"))))
			.isFalse();
		// spool 디렉터리가 없다
		assertThat(clientFault(new IOException("The temporary upload location [/tmp/x] is not valid"))).isFalse();
		assertThat(clientFault(new FileUploadException("Permission denied", new IOException("Permission denied"))))
			.isFalse();
	}

	private static boolean clientFault(Exception parseFailure) {
		return ApiExceptionHandler
			.malformedByClient(new MultipartException("Failed to parse multipart servlet request", parseFailure));
	}

}
