package com.stackcorp.astra.shared;

import java.net.URI;
import java.net.URISyntaxException;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * DispatcherServlet 밖(servlet filter, security filter chain)에서 난 오류도 컨트롤러 단계와 같은 problem JSON으로 응답한다.
 * 그런 오류는 servlet container가 /error로 다시 보내므로 Spring Boot 기본 BasicErrorController(timestamp, error, path) 대신
 * 이것이 받는다. 상태는 container가 넘긴 상태 코드를 따르고 code와 고정 문장, Cache-Control은 {@link ApiExceptionHandler}의
 * 규칙 그대로다. 예외 메시지와 stack trace는 싣지 않는다. 로그는 처음 dispatch에서 {@link RequestIdFilter}가 이미 남겼다.
 * <p>
 * {@link RequestIdFilter}가 오류 dispatch에서도 처음 정한 requestId를 다시 MDC에 넣으므로 {@link ProblemRequestIds}가 붙이는 값은
 * 응답 header와 같다. /error는 security에서 permitAll이라 처음 요청이 인증된 뒤 실패했어도 오류 dispatch가 401로 바뀌지 않는다.
 * 응답이 이미 나가기 시작했으면 container가 이 경로를 include로 부른다. 그때는 보낸 바이트 뒤에 problem을 덧붙이지 않는다.
 * client가 /error를 직접 부르면 넘겨받은 오류가 없으므로 404다.
 */
@RestController
class ProblemErrorController implements ErrorController {

	@RequestMapping("${server.error.path:/error}")
	ResponseEntity<ProblemDetail> error(HttpServletRequest request, HttpServletResponse response) {
		if (response.isCommitted()) {
			return null;
		}
		ResponseEntity<ProblemDetail> problem = ApiExceptionHandler.forStatus(
				request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer status ? status
						: HttpServletResponse.SC_NOT_FOUND);
		// 컨트롤러 단계의 problem처럼 instance는 처음 요청한 경로다. 비워 두면 Spring이 /error를 넣는다. 처음 경로를 URI로 읽을 수
		// 없으면 그대로 둔다. 여기서 예외가 나면 container의 기본 오류 페이지가 나간다.
		if (request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String uri) {
			try {
				problem.getBody().setInstance(new URI(uri));
			}
			catch (URISyntaxException ex) {
				// instance는 Spring이 채운다.
			}
		}
		return problem;
	}

}
