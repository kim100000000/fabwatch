package com.fabwatch.aireport.client;

import lombok.Getter;

/**
 * Claude 호출 실패. getMessage()는 곧 fail_reason에 저장되므로 항상 정제된 고정 문구만 담는다
 * (API 키·요청/응답 원문 금지 — docs/11 §5·§7).
 */
@Getter
public class ClaudeClientException extends RuntimeException {

    public enum Kind {
        /** API 키 미설정 — 호출 자체를 하지 않음 */
        NOT_CONFIGURED,
        /** 429 — 재시도 없음 */
        RATE_LIMITED,
        /** 401/403 */
        AUTH,
        /** 그 외 4xx(400 등) — 재시도 없음 */
        BAD_REQUEST,
        /** 5xx — 1회 재시도 후에도 실패 */
        SERVER,
        TIMEOUT,
        NETWORK,
        /** 안전 정책으로 응답 거부(stop_reason=refusal) */
        REFUSED,
        /** 200이지만 응답 형식이 이상함 */
        INVALID_RESPONSE,
        /** stop_reason=max_tokens인데 text 블록이 비어 있음 — 사고 토큰이 한도를 먹은 경우 (H-1, 호출 로그에서 구분) */
        MAX_TOKENS_TRUNCATED,
        /** max_tokens가 아닌 이유로 text 블록이 비어 있음 */
        EMPTY_CONTENT
    }

    /** fail_reason / error_summary에서 원인을 구분하는 고정 코드 접두사 */
    public static final String CODE_MAX_TOKENS_TRUNCATED = "MAX_TOKENS_TRUNCATED";
    public static final String CODE_EMPTY_CONTENT = "EMPTY_CONTENT";

    private final Kind kind;
    private final Integer httpStatus;

    public ClaudeClientException(Kind kind, String safeMessage, Integer httpStatus) {
        super(safeMessage);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    public ClaudeClientException(Kind kind, String safeMessage) {
        this(kind, safeMessage, null);
    }
}
