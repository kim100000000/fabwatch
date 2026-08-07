package com.fabwatch.common.util;

/**
 * 비밀번호 정책 — 8자 이상 + 영문/숫자 포함 (docs/03 F-1, docs/11 §2).
 * 사용자 등록 API(docs/06 §9)는 이번 라운드 범위가 아니라, 현재는 시드에서만 사용한다.
 */
public final class PasswordPolicy {

    private static final int MIN_LENGTH = 8;

    private PasswordPolicy() {
    }

    public static boolean isValid(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_LENGTH) {
            return false;
        }
        boolean hasLetter = rawPassword.chars().anyMatch(Character::isLetter);
        boolean hasDigit = rawPassword.chars().anyMatch(Character::isDigit);
        return hasLetter && hasDigit;
    }
}
