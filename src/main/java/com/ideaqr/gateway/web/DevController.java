package com.ideaqr.gateway.web;

import com.ideaqr.gateway.service.DevTimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Demo "time machine" endpoints. Let the platform administrator mock the hour-of-day used by
 * the working-hours policy, so they can show an object being available during business hours
 * and blocked outside them on demand. <b>Admin only</b>: the mock is a process-wide demo clock
 * the presenter controls (see {@link DevTimeService}); a regular user can neither read nor set
 * it. Hardening note — previously any authenticated user could shift their own hour, which
 * undermined the working-hours gate for infrastructure access; it is now locked to
 * {@code ROLE_ADMIN} at both the URL tier and here.
 */
@RestController
@RequestMapping("/api/v2/dev")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")  // second line of defence beyond the URL matcher (audit 3.8)
public class DevController {

    private static final int WORK_START = 8;
    private static final int WORK_END = 18;

    private final DevTimeService devTimeService;
    private final AuthSupport authSupport;
    private final Clock clock;

    @GetMapping("/time")
    public ResponseEntity<Map<String, Object>> current(Authentication authentication) {
        authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(view());
    }

    @PostMapping("/time")
    public ResponseEntity<Map<String, Object>> set(@RequestBody(required = false) Map<String, Object> body,
                                                  Authentication authentication) {
        authSupport.requireIdentity(authentication);
        Object hour = body != null ? body.get("hour") : null;
        if (hour == null || hour.toString().isBlank()) {
            devTimeService.clear();
        } else {
            try {
                devTimeService.setMockHour(Integer.parseInt(hour.toString().trim()));
            } catch (NumberFormatException e) {
                devTimeService.clear();
            }
        }
        return ResponseEntity.ok(view());
    }

    @PostMapping("/time/reset")
    public ResponseEntity<Map<String, Object>> reset(Authentication authentication) {
        authSupport.requireIdentity(authentication);
        devTimeService.clear();
        return ResponseEntity.ok(view());
    }

    private Map<String, Object> view() {
        Integer mock = devTimeService.currentMockHour();
        int serverHour = LocalTime.now(clock).getHour();   // business-zone clock (Asia/Almaty)
        int effective = mock != null ? mock : serverHour;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mockHour", mock);
        m.put("effectiveHour", effective);
        m.put("serverHour", serverHour);
        m.put("workingHours", effective >= WORK_START && effective < WORK_END);
        return m;
    }
}
