package com.ideaqr.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * IDEAQR Digital Gateway — Stage 3.
 *
 * <p>An access-governance and immutable-audit layer built on top of digital
 * identities. Stage 3 adds real authentication (Spring Security + BCrypt),
 * persistent accounts, and two role-based interfaces: an administrator QR
 * governance panel and a citizen scanning terminal.</p>
 *
 * <p>The Stage 2 processing pipeline is preserved and is now driven by
 * authenticated identities:</p>
 *
 * <pre>
 *   Identity → Request → Decision → QR/Access → Assignment → Interaction → History
 * </pre>
 */
@SpringBootApplication
public class IdeaqrGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdeaqrGatewayApplication.class, args);
    }
}
