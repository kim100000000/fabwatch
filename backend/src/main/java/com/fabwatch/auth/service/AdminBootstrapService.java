package com.fabwatch.auth.service;

import com.fabwatch.auth.config.AdminBootstrapProperties;
import com.fabwatch.auth.entity.Role;
import com.fabwatch.auth.entity.User;
import com.fabwatch.auth.repository.UserRepository;
import com.fabwatch.common.util.LogSanitizer;
import com.fabwatch.common.util.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영 최초 ADMIN 1명 생성 (멱등). 운영(prod)에는 시연 시드가 없으므로 이 경로로만 첫 계정이 생긴다.
 *
 * 규칙:
 * - users 테이블이 비어 있을 때만 생성한다. 한 명이라도 있으면 아무것도 하지 않는다(재기동·재배포에 안전).
 * - 설정(BOOTSTRAP_ADMIN_*)이 비어 있으면 경고 로그만 남기고 넘어간다(앱은 기동 — 이후 DB에 직접 계정을 넣거나 설정 후 재기동).
 * - 이메일/비밀번호가 일부만 있거나 정책(8자 이상 영문+숫자)에 맞지 않으면 기동을 실패시킨다(fail-fast).
 *   조용히 건너뛰면 "관리자 없는 운영 서버"가 만들어지는데 원인을 알기 어렵기 때문이다.
 * - 비밀번호는 로그에 남기지 않는다(BCrypt 해시로만 저장).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminBootstrapService {

    /** User.email 컬럼 길이 */
    private static final int EMAIL_MAX_LENGTH = 100;
    /** User.name 컬럼 길이 */
    private static final int NAME_MAX_LENGTH = 50;

    public enum Result {
        /** ADMIN 생성됨 */
        CREATED,
        /** 이미 사용자가 있어 건너뜀 */
        SKIPPED_USERS_EXIST,
        /** 설정이 비어 있어 건너뜀(경고 로그) */
        SKIPPED_NOT_CONFIGURED
    }

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminBootstrapProperties properties;

    // TENANT: 멀티테넌트 전환 시 테넌트별 최초 관리자 생성 지점
    @Transactional
    public Result bootstrapIfEmpty() {
        // 사용자가 이미 있으면 설정 검증도 하지 않는다 — 부트스트랩 변수는 최초 1회용이라 남아 있어도 무해해야 한다
        if (userRepository.count() > 0) {
            log.debug("ADMIN 부트스트랩 건너뜀 — 이미 사용자가 존재합니다.");
            return Result.SKIPPED_USERS_EXIST;
        }
        if (properties.isUnset()) {
            log.warn("사용자가 한 명도 없지만 BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD가 설정되지 않아 ADMIN을 만들지 못했습니다. "
                    + "두 환경변수를 설정하고 재기동하세요.");
            return Result.SKIPPED_NOT_CONFIGURED;
        }

        String email = validatedEmail();
        String password = properties.password();
        if (properties.isPasswordBlank() || !PasswordPolicy.isValid(password)) {
            // 값은 절대 메시지에 넣지 않는다
            throw new IllegalStateException(
                    "BOOTSTRAP_ADMIN_PASSWORD가 비밀번호 정책(8자 이상, 영문+숫자 포함)에 맞지 않거나 비어 있습니다.");
        }
        String name = properties.name() == null || properties.name().isBlank()
                ? AdminBootstrapProperties.DEFAULT_NAME
                : properties.name().trim();
        if (name.length() > NAME_MAX_LENGTH) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_NAME은 " + NAME_MAX_LENGTH + "자 이하여야 합니다.");
        }

        userRepository.saveAndFlush(User.builder()
                .email(email)
                .password(passwordEncoder.encode(password))
                .name(name)
                .role(Role.ADMIN)
                .enabled(true)
                .build());
        log.info("최초 ADMIN 계정을 생성했습니다: {} (비밀번호는 출력하지 않습니다)", LogSanitizer.clean(email));
        return Result.CREATED;
    }

    private String validatedEmail() {
        if (properties.isEmailBlank()) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_EMAIL이 비어 있습니다 (BOOTSTRAP_ADMIN_PASSWORD만 설정됨).");
        }
        String email = properties.email().trim();
        // 로그인 DTO(@Email, 최대 100자)와 같은 기준으로 로그인 가능한 이메일만 허용
        int at = email.indexOf('@');
        boolean plausible = at > 0 && at == email.lastIndexOf('@') && at < email.length() - 1
                && email.indexOf(' ') < 0 && email.length() <= EMAIL_MAX_LENGTH;
        if (!plausible) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_EMAIL 형식이 올바르지 않습니다 (이메일 형식, 100자 이하).");
        }
        return email;
    }
}
