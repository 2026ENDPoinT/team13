package com.stackcorp.astra.shared;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 컨트롤러 단계의 모든 problem JSON에 requestId를 넣는다. {@link ApiExceptionHandler}뿐 아니라 모듈의 컨트롤러가 직접 만든
 * ProblemDetail에도 붙는다. filter 단계의 오류는 identity의 ProblemResponses가 같은 값을 넣는다. problem의 code는 요청 지표의 code
 * tag로도 쓴다({@link HttpObservationConvention}).
 */
@RestControllerAdvice
class ProblemRequestIds implements ResponseBodyAdvice<Object> {

	@Override
	public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
		return true;
	}

	@Override
	public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
			Class<? extends HttpMessageConverter<?>> selectedConverterType, ServerHttpRequest request,
			ServerHttpResponse response) {
		String id = RequestIds.current();
		if (body instanceof ProblemDetail problem && id != null) {
			problem.setProperty("requestId", id);
		}
		if (body instanceof ProblemDetail problem && request instanceof ServletServerHttpRequest servlet
				&& problem.getProperties() != null && problem.getProperties().get("code") instanceof String code) {
			servlet.getServletRequest().setAttribute(HttpObservationConvention.PROBLEM_CODE, code);
		}
		return body;
	}

}
