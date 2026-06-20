package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.Complaint;
import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.dto.ApiResponse;
import com.ideaqr.gateway.service.ComplaintService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * User-facing complaints API. Each complaint must reference a concrete
 * interaction (the brief's requirement); administrators triage them via
 * {@link AdminController}.
 */
@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
public class ComplaintController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ComplaintService complaintService;
    private final AuthSupport authSupport;

    @PostMapping("/complaints")
    public ResponseEntity<ApiResponse> create(@RequestBody Map<String, String> body, Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        String interactionUid = body.get("interactionUid");
        if (interactionUid == null || interactionUid.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Жалоба должна быть привязана к взаимодействию."));
        }
        Complaint c = complaintService.create(identity, UUID.fromString(interactionUid.trim()),
                body.get("subject"), body.get("category"), body.get("description"));
        return ResponseEntity.ok(ApiResponse.ok("Жалоба зарегистрирована.")
                .with("complaintUid", c.getComplaintUid().toString())
                .with("status", c.getStatus().name()));
    }

    @GetMapping("/complaints/me")
    public ResponseEntity<List<Map<String, Object>>> mine(Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Complaint c : complaintService.mine(identity.getIdentityUid())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("complaintUid", c.getComplaintUid().toString());
            m.put("subject", c.getSubject());
            m.put("category", c.getCategory());
            m.put("description", c.getDescription());
            m.put("status", c.getStatus().name());
            m.put("interactionUid", c.getInteractionUid().toString());
            m.put("createdAt", c.getCreatedAt() != null ? c.getCreatedAt().format(TS) : null);
            rows.add(m);
        }
        return ResponseEntity.ok(rows);
    }
}
