package com.ideaqr.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised, per-environment rate-limit configuration (audit 4.8 / 4.9), bound
 * from the {@code app.rate-limit.*} properties. Each capacity is the number of
 * requests allowed inside one {@link #windowSeconds} window before the bucket is
 * empty; the bucket then refills smoothly over the next window (Bucket4j greedy
 * refill). Defaults are sized for the demo and are safe to raise via env vars.
 */
@Data
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    /** Master switch — set false to disable throttling entirely (e.g. for load tests). */
    private boolean enabled = true;

    /** Length of the rolling refill window, in seconds. */
    private int windowSeconds = 60;

    /** Password attempts per window, per client IP (brute-force defence, audit 4.9). */
    private int loginCapacity = 10;

    /** Anonymous guest provisioning per window, per client IP (flood defence, audit 4.8). */
    private int guestCapacity = 5;

    /** Account creations per window, per client IP. */
    private int registerCapacity = 5;

    /** Baseline ceiling for authenticated API traffic per window, keyed by username. */
    private int authenticatedCapacity = 100;

    /**
     * Number of <b>trusted</b> reverse proxies in front of the app (audit C-1). The real
     * client IP is read from {@code X-Forwarded-For} at position {@code (len - trustedProxyCount)}
     * — i.e. the hop the outermost trusted proxy actually observed — so a client cannot
     * forge its bucket key by injecting extra left-hand entries. {@code 0} (the safe default)
     * ignores {@code X-Forwarded-For} entirely and keys on the socket address. Set to the
     * exact number of proxies you control (Render's edge = 1).
     */
    private int trustedProxyCount = 0;
}
