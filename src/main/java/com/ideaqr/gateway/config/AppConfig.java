package com.ideaqr.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Shared application beans.
 */
@Configuration
public class AppConfig {

    /**
     * The server clock used by time-sensitive policies (e.g. the working-hours gate
     * in {@code ValidationService}). Provided as a bean so it can be replaced with a
     * fixed clock in tests; production always uses the real system clock — the client
     * never influences it (audit 4.3).
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
