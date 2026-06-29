package com.ideaqr.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Shared application beans.
 */
@Configuration
public class AppConfig {

    /**
     * The server clock used by time-sensitive policies (e.g. the working-hours gate
     * in {@code ValidationService}). Production (and local/dev) always uses the real
     * system clock — the client never influences it (audit 4.3).
     */
    @Bean
    @Profile("!test")
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    /**
     * Deterministic clock for the test profile, pinned to 10:00 UTC (inside the 08:00–18:00
     * working window). Time-gated integration tests must not depend on the wall-clock hour at
     * which the suite happens to run — the session "time-machine" mock hour is not always
     * carried across MockMvc requests, so without a fixed clock the medical/infrastructure gate
     * would flip outside business hours and the suite would pass only during the day. Mirrors
     * the {@code DAYTIME} clock used by {@code ValidationServiceTest}.
     */
    @Bean
    @Profile("test")
    public Clock testClock() {
        return Clock.fixed(Instant.parse("2026-06-21T10:00:00Z"), ZoneOffset.UTC);
    }
}
