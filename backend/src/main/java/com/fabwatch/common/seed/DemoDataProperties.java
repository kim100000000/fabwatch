package com.fabwatch.common.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 공개 데모 배포용 옵트인 데이터 로더 설정 (환경변수 DEMO_DATA_ENABLED / DEMO_ACCOUNTS_PASSWORD).
 *
 * @param enabled          true일 때만 로더가 동작한다 (기본 false — 운영에 시연 데이터를 넣지 않는 것이 원칙)
 * @param accountsPassword 비어 있지 않으면 ENGINEER/TECHNICIAN 데모 계정 2개를 만든다. ADMIN은 절대 만들지 않는다.
 *                         비밀번호는 로그·toString에 노출하지 않는다 (docs/11 P-5).
 */
@ConfigurationProperties(prefix = "fabwatch.demo")
public record DemoDataProperties(Boolean enabled, String accountsPassword) {

    public boolean hasAccountsPassword() {
        return accountsPassword != null && !accountsPassword.isBlank();
    }

    @Override
    public String toString() {
        return "DemoDataProperties[enabled=" + enabled + ", accountsPassword=***]";
    }
}
