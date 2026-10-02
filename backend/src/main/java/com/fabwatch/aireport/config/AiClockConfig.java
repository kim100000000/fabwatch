package com.fabwatch.aireport.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 쿼터 일자 경계(KST)·복구 기준 시각을 테스트에서 고정할 수 있도록 Clock을 주입 가능하게 둔다. */
@Configuration
public class AiClockConfig {

    @Bean
    public Clock aiClock() {
        return Clock.systemUTC();
    }
}
