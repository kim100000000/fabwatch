package com.fabwatch.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Refresh 토큰 해시(SHA-256 hex, 64자). 토큰이 256bit 난수라 솔트·느린 해시가 필요 없다
 * (비밀번호와 달리 무차별 대입 대상이 아님). 원문과 해시는 로그에 남기지 않는다 (docs/11 P-5).
 */
public final class RefreshTokenHasher {

    private RefreshTokenHasher() {
    }

    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JRE가 제공해야 하는 알고리즘이다
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}
