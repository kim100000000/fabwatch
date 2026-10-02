package com.fabwatch.aireport.client;

/**
 * Claude 응답에서 추출한 값 — text 블록 이어붙임 + 토큰 사용량 (docs/11 §7, NFR-4).
 *
 * @param truncated stop_reason이 max_tokens라 본문이 잘렸을 가능성
 */
public record ClaudeResponse(String text, String model, int inputTokens, int outputTokens, boolean truncated) {
}
