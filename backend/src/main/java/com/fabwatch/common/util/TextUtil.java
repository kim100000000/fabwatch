package com.fabwatch.common.util;

/**
 * 문자열 길이 제한 헬퍼. 컬럼 길이를 넘는 값을 그대로 저장하면 INSERT가 실패해 트랜잭션 전체가 500으로 롤백되므로,
 * 이벤트·자동 생성 문구처럼 사용자가 직접 길이를 통제하지 못하는 값은 저장 직전에 이 헬퍼로 자른다.
 */
public final class TextUtil {

    private static final String ELLIPSIS = "…";

    private TextUtil() {
    }

    /**
     * 길이가 max를 넘으면 말줄임표('…') 포함 정확히 max자로 줄인다(UTF-16 기준 — DB 문자 수보다 같거나 작다).
     * 잘린 경계가 서로게이트 쌍(이모지 등) 중간이면 깨진 문자가 남지 않게 한 글자 더 줄인다.
     */
    public static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        if (max <= 1) {
            return ELLIPSIS.substring(0, Math.max(max, 0));
        }
        int end = max - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + ELLIPSIS;
    }
}
