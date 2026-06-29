package com.ideaqr.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Shared application beans.
 */
@Configuration
public class AppConfig {

    /** The business timezone the working-hours policy is expressed in (Kazakhstan, UTC+5). */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Almaty");

    /**
     * The server clock used by time-sensitive policies (e.g. the working-hours gate
     * in {@code ValidationService}). Pinned to the business timezone {@code Asia/Almaty}
     * (UTC+5) so "08:00–18:00" means local Kazakhstan time, not the host's UTC clock —
     * otherwise a Render/UTC host would shift the working window by five hours. The client
     * never influences it (audit 4.3); only the wall-clock zone is fixed to the business one.
     */
    @Bean
    @Profile("!test")
    public Clock clock() {
        return Clock.system(BUSINESS_ZONE);
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
