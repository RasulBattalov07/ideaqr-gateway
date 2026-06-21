package com.ideaqr.gateway.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A small in-memory, per-IP rate limiter for the public, abuse-prone endpoints
 * (audit 4.8 anonymous guest flood, 4.9 login brute force). It uses a fixed
 * one-minute window per (client, bucket) and answers {@code 429} with a localized
 * JSON body when the limit is exceeded.
 *
 * <p>This is deliberately dependency-free and sized for a single instance — the
 * appropriate MVP control. A horizontally-scaled deployment would move the counters
 * to a shared store (e.g. Redis); see audit 3.7.</p>
 */
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final long WINDOW_MILLIS = 60_000L;
    private static final int MAX_TRACKED_KEYS = 50_000;

    /** Per-bucket request ceiling within a one-minute window. */
    private static final int LIMIT_GUEST = 5;     // anonymous guest provisioning
    private static final int LIMIT_REGISTER = 5;  // account creation
    private static final int LIMIT_LOGIN = 10;    // password attempts

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String bucket = bucketFor(request);
        if (bucket != null && exceeded(bucket, request)) {
            response.setStatus(429); // Too Many Requests
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Retry-After", "60");
            response.getWriter().write(
                    "{\"success\":false,\"message\":\"Слишком много запросов. Повторите попытку через минуту.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Identify which rate-limit bucket (if any) applies to this request. */
    private String bucketFor(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return null;
        }
        String path = request.getRequestURI();
        if ("/api/auth/guest".equals(path)) return "guest";
        if ("/api/auth/register".equals(path)) return "register";
        if ("/login".equals(path)) return "login";
        return null;
    }

    private int limitFor(String bucket) {
        return switch (bucket) {
            case "guest" -> LIMIT_GUEST;
            case "register" -> LIMIT_REGISTER;
            default -> LIMIT_LOGIN;
        };
    }

    private boolean exceeded(String bucket, HttpServletRequest request) {
        // Guard against unbounded growth from spoofed IPs: drop everything on overflow
        // and start fresh (fail-open on memory, never fail-open on the limit itself).
        if (windows.size() > MAX_TRACKED_KEYS) {
            windows.clear();
        }
        String key = clientIp(request) + ":" + bucket;
        long now = System.currentTimeMillis();
        Window w = windows.compute(key, (k, existing) -> {
            if (existing == null || now - existing.start >= WINDOW_MILLIS) {
                return new Window(now);
            }
            return existing;
        });
        return w.count.incrementAndGet() > limitFor(bucket);
    }

    /** Honour the proxy header on Render/Heroku, else the socket address. */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    private static final class Window {
        final long start;
        final AtomicInteger count = new AtomicInteger(0);

        Window(long start) {
            this.start = start;
        }
    }
}
