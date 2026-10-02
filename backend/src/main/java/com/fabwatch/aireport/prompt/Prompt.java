package com.fabwatch.aireport.prompt;

/** Claude 요청에 쓰는 시스템 프롬프트 + 사용자 메시지 쌍. */
public record Prompt(String system, String user) {
}
