package com.fabwatch.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 운영 최초 ADMIN 부트스트랩 설정 (환경변수 BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD / BOOTSTRAP_ADMIN_NAME).
 * 비밀번호는 환경변수로만 받으며, toString/로그에 노출하지 않는다 (docs/11 P-5).
 *
 * @param email    ADMIN 로그인 이메일
 * @param password ADMIN 초기 비밀번호 (PasswordPolicy 검증 대상)
 * @param name     표시 이름 (생략 시 "관리자")
 */
@ConfigurationProperties(prefix = "fabwatch.bootstrap.admin")
public record AdminBootstrapProperties(String email, String password, String name) {

    public static final String DEFAULT_NAME = "관리자";

    public boolean isEmailBlank() {
        return email == null || email.isBlank();
    }

    public boolean isPasswordBlank() {
        return password == null || password.isBlank();
    }

    /** 이메일·비밀번호가 둘 다 비었을 때만 "미설정"으로 본다 (하나만 있으면 설정 오류) */
    public boolean isUnset() {
        return isEmailBlank() && isPasswordBlank();
    }

    @Override
    public String toString() {
        return "AdminBootstrapProperties[email=" + email + ", password=***, name=" + name + "]";
    }
}
