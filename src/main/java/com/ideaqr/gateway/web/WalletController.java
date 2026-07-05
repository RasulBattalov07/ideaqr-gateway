package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.RegistryObject;
import com.ideaqr.gateway.repository.RegistryObjectRepository;
import com.ideaqr.gateway.service.QrService;
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
 * The objects a user owns, behind their single identity. The aggregate read-only
 * {@code /wallet} endpoint was removed: it duplicated {@code /my-qr}, {@code /my-objects}
 * and the history journal, was never wired into the SPA, and resurrected the retired
 * gamified {@code trustScore} on the owner block. What remains is the one view the SPA
 * actually uses — the objects this identity currently owns, each with its scannable QR.
 */
@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
public class WalletController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final RegistryObjectRepository registryObjectRepository;
    private final QrService qrService;
    private final AuthSupport authSupport;

    /**
     * Objects this identity currently OWNS — including ones transferred to them by an admin
     * (Point 3). After a transfer the recipient sees the object here with its scannable QR, so
     * the hand-off is no longer a dead-end ("и что дальше?").
     */
    @GetMapping("/my-objects")
    public ResponseEntity<List<Map<String, Object>>> myObjects(Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RegistryObject obj : registryObjectRepository
                .findByOwnerIdentityUidOrderByCreatedAtDesc(identity.getIdentityUid())) {
            // Объекты-досье (медкарта / правовой статус / визитка) — документы гражданина:
            // они живут в модулях дашборда, а не среди передаваемого имущества.
            if (com.ideaqr.gateway.service.CitizenDossierService.isDossierObject(obj.getObjectUid())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("objectUid", obj.getObjectUid());
            m.put("displayName", obj.getDisplayName());
            m.put("category", obj.getCategory().name());
            m.put("status", obj.getStatus() != null ? obj.getStatus().name() : null);
            m.put("trustScore", obj.getTrustScore());
            m.put("qrImageDataUri", qrService.regenerateImageFor(obj.getObjectUid()));
            m.put("createdAt", obj.getCreatedAt() != null ? obj.getCreatedAt().format(TS) : null);
            rows.add(m);
        }
        return ResponseEntity.ok(rows);
    }
}
