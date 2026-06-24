package com.ideaqr.gateway.config;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Per-IP / per-user rate limiter for the abuse-prone public endpoints (audit 4.8
 * anonymous guest flood, 4.9 login brute force) and a baseline ceiling for
 * authenticated API traffic. Backed by <a href="https://bucket4j.com">Bucket4j</a>
 * token buckets with a smooth (greedy) refill, so a client may burst up to the
 * configured capacity and then proceeds at the refill rate. Over the limit it
 * answers {@code 429} with a localized JSON body and an accurate {@code Retry-After}.
 *
 * <p>Buckets are held per node in memory — cheap and dependency-light. With N
 * replicas the effective public ceiling is N×capacity, which is an acceptable MVP
 * trade-off; a strict cluster-wide limit would swap this {@link ConcurrentHashMap}
 * for a distributed Bucket4j backend (e.g. {@code bucket4j-redis} /
 * {@code bucket4j-postgresql}). Note that shared <em>session</em> state — the actual
 * prerequisite for running multiple replicas (audit 3.7) — is handled separately by
 * Spring Session JDBC.</p>
 */
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    /** Cap on distinct tracked keys; oldest (least-recently-used) keys are evicted on overflow. */
    private static final int MAX_TRACKED_KEYS = 50_000;

    private final RateLimitProperties properties;

    /**
     * Bounded, access-ordered LRU of per-key buckets. On overflow only the
     * least-recently-used key is evicted — never a global {@code clear()}, so a flood
     * of fresh keys can no longer wipe everyone else's (incl. brute-force) counters.
     */
    private final Map<String, Bucket> buckets = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                    return size() > MAX_TRACKED_KEYS;
                }
            });

    public RateLimitingFilter(RateLimitProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!properties.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        BucketSpec spec = bucketFor(request);
        if (spec == null) {
            chain.doFilter(request, response);
            return;
        }

        ConsumptionProbe probe = resolveBucket(spec).tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            response.setHeader("X-Rate-Limit-Remaining", Long.toString(probe.getRemainingTokens()));
            chain.doFilter(request, response);
            return;
        }

        long retryAfter = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value()); // 429
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.getWriter().write(
                "{\"success\":false,\"message\":\"Слишком много запросов. Повторите попытку позже.\"}");
    }

    /** The bucket key + capacity for this request, or {@code null} if it is not throttled. */
    private BucketSpec bucketFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean post = HttpMethod.POST.matches(request.getMethod());

        // Hard public limits, keyed by client IP.
        if (post && "/login".equals(path)) {
            return new BucketSpec("login:" + clientIp(request), properties.getLoginCapacity());
        }
        if (post && "/api/auth/guest".equals(path)) {
            return new BucketSpec("guest:" + clientIp(request), properties.getGuestCapacity());
        }
        if (post && "/api/auth/register".equals(path)) {
            return new BucketSpec("register:" + clientIp(request), properties.getRegisterCapacity());
        }

        // Baseline ceiling for the rest of the API, keyed by user (or IP if anonymous).
        // The open health probe is exempt so monitoring is never throttled.
        if (path.startsWith("/api/") && !path.equals("/api/health")) {
            return new BucketSpec("api:" + principalOrIp(request), properties.getAuthenticatedCapacity());
        }
        return null;
    }

    private Bucket resolveBucket(BucketSpec spec) {
        // computeIfAbsent on the synchronized LRU map is atomic; overflow evicts the
        // least-recently-used key (see the map's removeEldestEntry), not everything.
        return buckets.computeIfAbsent(spec.key(), k -> newBucket(spec.capacity()));
    }

    private Bucket newBucket(int capacity) {
        Duration window = Duration.ofSeconds(properties.getWindowSeconds());
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(capacity).refillGreedy(capacity, window))
                .build();
    }

    /** Authenticated requests are keyed by username (fair across a shared NAT); else by IP. */
    private String principalOrIp(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            return "user:" + auth.getName();
        }
        return "ip:" + clientIp(request);
    }

    /**
     * Resolve the real client IP for bucketing (audit C-1). {@code X-Forwarded-For} is
     * <b>only</b> consulted when {@code trustedProxyCount > 0}, and then we read the hop at
     * {@code (len - trustedProxyCount)} — the address the outermost proxy <i>we control</i>
     * actually saw. Any extra left-hand entries a client injects sit before that index and
     * are ignored, so the bucket key can no longer be forged. With the default of {@code 0}
     * the header is ignored entirely and the (unspoofable) socket address is used.
     */
    private String clientIp(HttpServletRequest request) {
        int trusted = properties.getTrustedProxyCount();
        if (trusted > 0) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String[] hops = forwarded.split(",");
                int idx = Math.max(0, hops.length - trusted);
                String ip = hops[idx].trim();
                if (!ip.isEmpty()) {
                    return ip;
                }
            }
        }
        return request.getRemoteAddr();
    }

    /** Identifies which bucket a request maps to and how big that bucket is. */
    private record BucketSpec(String key, int capacity) {
    }
}
