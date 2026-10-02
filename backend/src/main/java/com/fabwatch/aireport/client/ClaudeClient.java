package com.fabwatch.aireport.client;

/**
 * Claude 호출 추상화 — 외부 호출 격리 지점 (docs/10 ADR-1: aireport는 분리 2순위 후보).
 * 테스트에서는 이 인터페이스의 Fake/Mock 구현을 주입하고 실호출은 절대 하지 않는다 (docs/12 T-3).
 */
public interface ClaudeClient {

    /**
     * 시스템 프롬프트 + 사용자 메시지로 1회 생성한다. 타임아웃/5xx 재시도 정책은 구현체가 책임진다.
     *
     * @throws ClaudeClientException 모든 실패. 메시지는 정제된 사유(API 키·응답 원문 미포함)만 담는다.
     */
    ClaudeResponse generate(String systemPrompt, String userMessage);
}
