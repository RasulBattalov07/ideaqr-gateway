package com.ideaqr.gateway.web;

import com.ideaqr.gateway.service.DevTimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Demo "time machine" endpoints. Let a presenter mock the hour-of-day used by the
 * working-hours policy <b>for their own session only</b>, so they can show an object being
 * available during business hours and blocked outside them on demand. Authenticated session
 * required; it never affects any other user's decisions.
 */
@RestController
@RequestMapping("/api/v2/dev")
@RequiredArgsConstructor
public class DevController {

    private static final int WORK_START = 8;
    private static final int WORK_END = 18;

    private final DevTimeService devTimeService;
    private final AuthSupport authSupport;

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
        int effective = mock != null ? mock : LocalTime.now().getHour();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mockHour", mock);
        m.put("effectiveHour", effective);
        m.put("serverHour", LocalTime.now().getHour());
        m.put("workingHours", effective >= WORK_START && effective < WORK_END);
        return m;
    }
}
