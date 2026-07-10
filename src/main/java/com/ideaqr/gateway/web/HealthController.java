package com.ideaqr.gateway.web;

import com.ideaqr.gateway.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public liveness endpoint used by the SPA and by deployment health checks. */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public ResponseEntity<ApiResponse> health() {
        // Stage 4 задеплоен (сессии, организации, SOS, гостевые личности) — health обязан
        // совпадать с территорией (инвест-аудит 2.5: «карта не сходится с территорией»).
        return ResponseEntity.ok(ApiResponse.ok("IDEAQR Digital Gateway работает")
                .with("status", "UP")
                .with("stage", 4));
    }
}
