package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.History;
import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.dto.PageResponse;
import com.ideaqr.gateway.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
 * Read access to the immutable journal. Administrators see the whole system;
 * everyone else sees only their own actions. The journal is append-only — there
 * is no write endpoint here.
 */
@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
public class AuditController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AuditService auditService;
    private final AuthSupport authSupport;

    /**
     * Whole-system journal for admins; falls back to the caller's own journal
     * otherwise. Server-paginated (audit 3.1): {@code ?page=0&size=50}.
     */
    @GetMapping("/audit")
    public ResponseEntity<PageResponse<Map<String, Object>>> all(
            @PageableDefault(size = 50) Pageable pageable, Authentication authentication) {
        User user = authSupport.requireUser(authentication);
        Identity identity = authSupport.requireIdentity(authentication);
        Page<History> events = user.isAdmin()
                ? auditService.globalJournal(pageable)
                : auditService.journalFor(identity.getIdentityUid(), pageable);
        return ResponseEntity.ok(PageResponse.of(events, toRows(events.getContent())));
    }

    @GetMapping("/audit/me")
    public ResponseEntity<PageResponse<Map<String, Object>>> mine(
            @PageableDefault(size = 50) Pageable pageable, Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        Page<History> events = auditService.journalFor(identity.getIdentityUid(), pageable);
        return ResponseEntity.ok(PageResponse.of(events, toRows(events.getContent())));
    }

    private List<Map<String, Object>> toRows(List<History> events) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (History h : events) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("eventType", h.getEventType() != null ? h.getEventType().name() : null);
            m.put("objectUid", h.getObjectUid());
            m.put("historyUid", h.getHistoryUid() != null ? h.getHistoryUid().toString() : null);
            m.put("description", h.getDescription());
            m.put("createdAt", h.getCreatedAt() != null ? h.getCreatedAt().format(TS) : null);
            rows.add(m);
        }
        return rows;
    }
}
