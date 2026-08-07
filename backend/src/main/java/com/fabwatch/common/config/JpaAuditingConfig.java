package com.fabwatch.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * created_at / updated_at 자동 기록 (docs/05 공통 규칙).
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
