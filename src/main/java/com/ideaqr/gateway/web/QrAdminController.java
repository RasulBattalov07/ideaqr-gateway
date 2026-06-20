package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.RegistryObject;
import com.ideaqr.gateway.dto.QrCreationRequest;
import com.ideaqr.gateway.dto.QrCreationResponse;
import com.ideaqr.gateway.service.QrService;
import jakarta.validation.Valid;
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

/**
 * The administrator governance panel API ("Govern and create QR codes").
 * Restricted to {@code ROLE_ADMIN} by the security configuration. Creating an
 * object runs the full {@code QR_CREATION} pipeline and returns the generated,
 * scannable QR image plus the governance chain.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class QrAdminController {

    private final QrService qrService;
    private final AuthSupport authSupport;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @PostMapping("/qr/create")
    public ResponseEntity<QrCreationResponse> create(@Valid @RequestBody QrCreationRequest request,
                                                     Authentication authentication) {
        Identity admin = authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(qrService.createGovernedObject(admin, request));
    }

    @GetMapping("/qr/list")
    public ResponseEntity<List<Map<String, Object>>> list(Authentication authentication) {
        Identity admin = authSupport.requireIdentity(authentication);
        List<RegistryObject> objects = qrService.listObjectsForAdmin(admin.getIdentityUid());

        List<Map<String, Object>> summaries = new ArrayList<>();
        for (RegistryObject obj : objects) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("objectUid", obj.getObjectUid());
            m.put("displayName", obj.getDisplayName());
            m.put("category", obj.getCategory().name());
            m.put("status", obj.getStatus() != null ? obj.getStatus().name() : null);
            m.put("trustScore", obj.getTrustScore());
            m.put("qrUid", obj.getQrUid() != null ? obj.getQrUid().toString() : null);
            m.put("createdAt", obj.getCreatedAt() != null ? obj.getCreatedAt().format(TS) : null);
            m.put("qrImageDataUri", qrService.regenerateImageFor(obj.getObjectUid()));
            summaries.add(m);
        }
        return ResponseEntity.ok(summaries);
    }
}
