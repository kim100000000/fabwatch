package com.fabwatch.common.security;

import com.fabwatch.common.config.SecurityProperties;
import com.fabwatch.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * 요청 본문 크기 상한 (기본 1MB, fabwatch.security.max-request-bytes). 보안 감사 M-3.
 * - Content-Length가 상한을 넘으면 본문을 읽지 않고 즉시 413 PAYLOAD_TOO_LARGE
 * - Content-Length가 없는 청크 전송은 읽는 도중 누적량을 세어, 상한을 넘으면 PayloadTooLargeException을 던진다
 *   (GlobalExceptionHandler가 413으로 변환)
 */
@Slf4j
@Component
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    /** 청크 전송 본문이 상한을 넘었을 때 읽기 스트림에서 던지는 예외 */
    public static class PayloadTooLargeException extends IOException {
        public PayloadTooLargeException() {
            super("request body too large");
        }
    }

    private final long maxBytes;
    private final ErrorResponseWriter errorWriter;

    public RequestSizeLimitFilter(SecurityProperties properties, ErrorResponseWriter errorWriter) {
        this.maxBytes = properties.maxRequestBytes();
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            log.warn("요청 본문 크기 초과: path={}, contentLength={}", request.getRequestURI(), declared);
            errorWriter.write(response, ErrorCode.PAYLOAD_TOO_LARGE);
            return;
        }
        // Content-Length가 있고 상한 이내면 그대로 통과(톰캣이 선언 길이만큼만 읽는다). 없을 때만 누적 계수.
        chain.doFilter(declared >= 0 ? request : new LimitedRequest(request, maxBytes), response);
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long maxBytes;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedInputStream(super.getInputStream(), maxBytes);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding() == null ? "UTF-8" : getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(), encoding));
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private long count;

        LimitedInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        private void count(int bytes) throws IOException {
            count += bytes;
            if (count > maxBytes) {
                throw new PayloadTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
