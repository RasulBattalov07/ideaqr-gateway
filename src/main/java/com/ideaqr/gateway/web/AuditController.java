package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.History;
import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read access to the append-only History journal. The journal is the platform's
 * core transparency guarantee: every access decision, QR creation and report is
 * recorded and can never be altered. Any authenticated user may read the global
 * journal; {@code /me} narrows it to the caller's own identity.
 */
@RestController
@RequestMapping("/api/v2/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;
    private final AuthSupport authSupport;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> global(Authentication authentication) {
        authSupport.requireUser(authentication); // gate behind a session
        return ResponseEntity.ok(map(auditService.recentGlobal()));
    }

    @GetMapping("/me")
    public ResponseEntity<List<Map<String, Object>>> mine(Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(map(auditService.forIdentity(identity.getIdentityUid())));
    }

    private List<Map<String, Object>> map(List<History> events) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (History h : events) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("historyUid", h.getHistoryUid() != null ? h.getHistoryUid().toString() : null);
            m.put("eventType", h.getEventType() != null ? h.getEventType().name() : null);
            m.put("description", h.getDescription());
            m.put("objectUid", h.getObjectUid());
            m.put("identityUid", h.getIdentityUid() != null ? h.getIdentityUid().toString() : null);
            m.put("requestUid", h.getRequestUid() != null ? h.getRequestUid().toString() : null);
            m.put("decisionUid", h.getDecisionUid() != null ? h.getDecisionUid().toString() : null);
            m.put("createdAt", h.getCreatedAt() != null ? h.getCreatedAt().format(TS) : null);
            out.add(m);
        }
        return out;
    }
}
