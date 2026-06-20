package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.Interaction;
import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.dto.GatewayResponse;
import com.ideaqr.gateway.repository.InteractionRepository;
import com.ideaqr.gateway.repository.UserRepository;
import com.ideaqr.gateway.service.GatewayService;
import com.ideaqr.gateway.service.QrService;
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
 * The user-facing personal history (per the customer's instruction: ordinary
 * users see only their own history — "who scanned me" / "whom I scanned" — never
 * the full system audit, which lives in {@link AdminController}). Also serves the
 * "My QR" view and the person-to-person access confirmation flow.
 */
@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
public class HistoryController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final InteractionRepository interactionRepository;
    private final UserRepository userRepository;
    private final GatewayService gatewayService;
    private final QrService qrService;
    private final AuthSupport authSupport;

    /** "Мой QR" — primary QR image, name and identity for the personal card. */
    @GetMapping("/my-qr")
    public ResponseEntity<Map<String, Object>> myQr(Authentication authentication) {
        User user = authSupport.requireUser(authentication);
        Identity identity = authSupport.requireIdentity(authentication);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fullName", (user.getFirstName() + " " + user.getLastName()).trim());
        m.put("identityUid", identity.getIdentityUid().toString());
        m.put("primaryQrUid", identity.getPrimaryQrUid() != null ? identity.getPrimaryQrUid().toString() : null);
        // The personal QR encodes the identity, never roles or trust.
        m.put("qrImageDataUri", qrService.regenerateImageFor("IDENTITY:" + identity.getIdentityUid()));
        return ResponseEntity.ok(m);
    }

    /** Personal history: "я сканировал" and "кто сканировал меня". */
    @GetMapping("/history/me")
    public ResponseEntity<Map<String, Object>> myHistory(Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        UUID me = identity.getIdentityUid();

        List<Map<String, Object>> scannedByMe = new ArrayList<>();
        for (Interaction i : interactionRepository.findByIdentityUidOrderByCreatedAtDesc(me)) {
            String type = i.getInteractionType();
            if (!"SCAN".equals(type) && !"PROFILE_SCAN".equals(type)) continue;
            String counterpart = "PROFILE_SCAN".equals(type) && i.getTargetIdentityUid() != null
                    ? displayName(i.getTargetIdentityUid())
                    : (i.getObjectUid() != null ? i.getObjectUid() : "—");
            scannedByMe.add(row(counterpart, type, i));
        }

        List<Map<String, Object>> scansOfMe = new ArrayList<>();
        for (Interaction i : interactionRepository.findByTargetIdentityUidOrderByCreatedAtDesc(me)) {
            scansOfMe.add(row(displayName(i.getIdentityUid()), i.getInteractionType(), i));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scannedByMe", scannedByMe);
        body.put("scansOfMe", scansOfMe);
        return ResponseEntity.ok(body);
    }

    /** Pending person-to-person access requests addressed to me ("подтвердить доступ"). */
    @GetMapping("/access/pending")
    public ResponseEntity<List<Map<String, Object>>> pending(Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Interaction i : gatewayService.pendingAccessRequests(identity.getIdentityUid())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("interactionUid", i.getInteractionUid().toString());
            m.put("fromName", displayName(i.getIdentityUid()));
            m.put("createdAt", i.getCreatedAt() != null ? i.getCreatedAt().format(TS) : null);
            rows.add(m);
        }
        return ResponseEntity.ok(rows);
    }

    @PostMapping("/access/{interactionUid}/confirm")
    public ResponseEntity<GatewayResponse> confirm(@PathVariable("interactionUid") String interactionUid,
                                                   Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(gatewayService.confirmProfileAccess(identity, UUID.fromString(interactionUid)));
    }

    @PostMapping("/access/{interactionUid}/reject")
    public ResponseEntity<GatewayResponse> reject(@PathVariable("interactionUid") String interactionUid,
                                                  Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(gatewayService.rejectProfileAccess(identity, UUID.fromString(interactionUid)));
    }

    // ------------------------------------------------------------------

    private Map<String, Object> row(String counterpart, String type, Interaction i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", counterpart);
        m.put("type", type);
        m.put("status", i.getStatus() != null ? i.getStatus().name() : null);
        m.put("interactionUid", i.getInteractionUid().toString());
        m.put("createdAt", i.getCreatedAt() != null ? i.getCreatedAt().format(TS) : null);
        return m;
    }

    private String displayName(UUID identityUid) {
        if (identityUid == null) return "—";
        return userRepository.findByIdentityUid(identityUid)
                .map(u -> (u.getFirstName() + " " + u.getLastName()).trim())
                .orElse("Пользователь");
    }
}
