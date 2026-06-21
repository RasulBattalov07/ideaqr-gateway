package com.ideaqr.gateway.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Small hashing / token helpers shared by the identity and guest-merge flows.
 * The merge token is a high-entropy random value, so a fast SHA-256 (not BCrypt)
 * is the right primitive for storing and comparing it.
 */
public final class Hashing {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Hashing() {
    }

    /** A URL-safe, 256-bit random token (used as the one-time guest merge proof). */
    public static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Lower-case hex SHA-256 of the input. */
    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }

    /** Constant-time comparison to avoid leaking match position via timing. */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
