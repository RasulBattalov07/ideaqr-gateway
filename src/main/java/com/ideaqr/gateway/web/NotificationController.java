package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.Notification;
import com.ideaqr.gateway.dto.ApiResponse;
import com.ideaqr.gateway.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Notification center API: list the caller's notifications and mark one read.
 */
@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
public class NotificationController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final NotificationService notificationService;
    private final AuthSupport authSupport;

    @GetMapping("/notifications")
    public ResponseEntity<List<Map<String, Object>>> list(Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Notification n : notificationService.list(identity.getIdentityUid())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("notificationUid", n.getNotificationUid().toString());
            m.put("type", n.getNotificationType());
            m.put("title", n.getTitle());
            m.put("status", n.getStatus().name());
            m.put("createdAt", n.getCreatedAt() != null ? n.getCreatedAt().format(TS) : null);
            rows.add(m);
        }
        return ResponseEntity.ok(rows);
    }

    @PostMapping("/notifications/{id}/read")
    public ResponseEntity<ApiResponse> read(@PathVariable("id") String id, Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        boolean ok = notificationService.markRead(identity.getIdentityUid(), UUID.fromString(id));
        return ResponseEntity.ok(ok
                ? ApiResponse.ok("Уведомление прочитано")
                : ApiResponse.error("Уведомление не найдено"));
    }
}
