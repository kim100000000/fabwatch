package com.fabwatch.aireport.service;

import com.fabwatch.aireport.entity.AiReport;

import java.util.regex.Pattern;

/**
 * fail_reason / error_summary에 저장하기 전 마지막 방어선 (docs/11 §5·§7).
 * ClaudeClient가 이미 고정 문구만 던지지만, 어떤 경로로든 API 키가 섞여 들어가도 DB에는 남지 않게 마스킹하고
 * 300자로 자른다. 응답 원문 전체를 저장하는 일은 이 앞단에서 이미 차단된다.
 */
public final class FailReasonSanitizer {

    private static final Pattern KEY_PATTERN = Pattern.compile("sk-ant-[A-Za-z0-9_\\-]+");
    private static final Pattern API_KEY_HEADER = Pattern.compile("(?i)x-api-key\\s*[:=]\\s*\\S+");

    private FailReasonSanitizer() {
    }

    public static String sanitize(String reason, String apiKey) {
        String text = reason == null || reason.isBlank() ? "알 수 없는 오류로 생성에 실패했습니다" : reason;
        if (apiKey != null && !apiKey.isBlank()) {
            text = text.replace(apiKey, "***");
        }
        text = KEY_PATTERN.matcher(text).replaceAll("***");
        text = API_KEY_HEADER.matcher(text).replaceAll("x-api-key: ***");
        return text.length() <= AiReport.FAIL_REASON_MAX ? text : text.substring(0, AiReport.FAIL_REASON_MAX);
    }
}
