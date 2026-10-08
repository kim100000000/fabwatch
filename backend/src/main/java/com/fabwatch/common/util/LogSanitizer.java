package com.fabwatch.common.util;

/**
 * 로그 인젝션 방지 — 사용자 입력이 섞인 문자열을 로그에 남기기 전에 개행·제어문자를 공백으로 바꾸고 길이를 제한한다.
 * (Logback 기본 패턴은 개행을 이스케이프하지 않아 가짜 로그 라인을 끼워 넣을 수 있다.)
 */
public final class LogSanitizer {

    private static final int MAX_LENGTH = 200;

    private LogSanitizer() {
    }

    public static String clean(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder(Math.min(value.length(), MAX_LENGTH + 1));
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean control = Character.isISOControl(c) || c == ' ' || c == ' ';
            builder.append(control ? ' ' : c);
        }
        return TextUtil.truncate(builder.toString(), MAX_LENGTH);
    }
}
