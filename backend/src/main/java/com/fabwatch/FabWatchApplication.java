package com.fabwatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * FabWatch 백엔드 진입점.
 * 모듈러 모놀리스 — com.fabwatch.{common, auth, equipment, ...} 도메인 우선 패키지 (docs/10 ADR-1).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FabWatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(FabWatchApplication.class, args);
    }
}
