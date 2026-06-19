package com.ideaqr.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Application entry point for the IDEAQR Digital Gateway.
 *
 * <p>Boots the governance pipeline (Identity → Request → Decision → Interaction →
 * History), the Spring Security identity layer and the static SPA. The default
 * datasource is file-based H2; activate the {@code postgres} profile for a managed
 * database. The server honours {@code $PORT} (see {@code application.properties}).</p>
 */
@SpringBootApplication
public class IdeaqrGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdeaqrGatewayApplication.class, args);
    }
}
