package com.fabwatch.common.security;

import com.fabwatch.common.exception.ErrorCode;
import com.fabwatch.common.exception.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 서블릿 필터 단계(컨트롤러 이전)에서 공통 에러 포맷 { code, message, timestamp }을 직접 쓰는 헬퍼.
 * 필터에서 난 오류는 GlobalExceptionHandler를 거치지 않으므로 같은 포맷을 여기서 보장한다.
 */
@Component
@RequiredArgsConstructor
public class ErrorResponseWriter {

    private final ObjectMapper objectMapper;

    public void write(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ErrorResponse.of(code));
    }
}
