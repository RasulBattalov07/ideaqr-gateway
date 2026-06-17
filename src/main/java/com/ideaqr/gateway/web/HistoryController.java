package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.History;
import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.enums.HistoryEventType;
import com.ideaqr.gateway.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read access to the append-only {@link History} journal — the visible proof of
 * the platform's immutable-audit principle.
 *
 * <ul>
 *   <li>{@code /api/admin/history} — recent global events (governance oversight,
 *       admin only by the security rules).</li>
 *   <li>{@code /api/v2/history/me} — the current identity's own events.</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class HistoryController {

    private final AuditService auditService;
    private final AuthSupport authSupport;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @GetMapping("/api/admin/history")
    public ResponseEntity<List<Map<String, Object>>> globalHistory() {
        return ResponseEntity.ok(map(auditService.recentGlobal()));
    }

    @GetMapping("/api/v2/history/me")
    public ResponseEntity<List<Map<String, Object>>> myHistory(Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(map(auditService.forIdentity(identity.getIdentityUid())));
    }

    private List<Map<String, Object>> map(List<History> events) {
        List<Map<String, Object>> out = new ArrayList<>(events.size());
        for (History h : events) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("historyUid", h.getHistoryUid() != null ? h.getHistoryUid().toString() : null);
            m.put("eventType", h.getEventType() != null ? h.getEventType().name() : null);
            m.put("eventLabel", label(h.getEventType()));
            m.put("status", statusTag(h.getEventType()));
            m.put("description", h.getDescription());
            m.put("objectUid", h.getObjectUid());
            m.put("requestUid", h.getRequestUid() != null ? h.getRequestUid().toString() : null);
            m.put("decisionUid", h.getDecisionUid() != null ? h.getDecisionUid().toString() : null);
            m.put("createdAt", h.getCreatedAt() != null ? h.getCreatedAt().format(TS) : null);
            out.add(m);
        }
        return out;
    }

    /** Russian label for the journal. */
    private String label(HistoryEventType type) {
        if (type == null) return "Событие";
        return switch (type) {
            case ACCESS_GRANTED -> "Доступ разрешён";
            case ACCESS_DENIED -> "Доступ запрещён";
            case ACCESS_REVIEW -> "Требует проверки";
            case QR_CREATED -> "QR-код создан";
            case ISSUE_REPORTED -> "Обращение зарегистрировано";
            case IDENTITY_CREATED -> "Личность создана";
            case USER_REGISTERED -> "Пользователь зарегистрирован";
        };
    }

    /** Coarse status used for colour-coding the journal rows. */
    private String statusTag(HistoryEventType type) {
        if (type == null) return "INFO";
        return switch (type) {
            case ACCESS_GRANTED -> "GRANTED";
            case ACCESS_DENIED -> "DENIED";
            case ACCESS_REVIEW -> "REVIEW";
            default -> "INFO";
        };
    }
}
