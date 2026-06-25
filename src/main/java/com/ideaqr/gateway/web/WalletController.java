package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.History;
import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.RegistryObject;
import com.ideaqr.gateway.domain.RequestRecord;
import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.repository.RegistryObjectRepository;
import com.ideaqr.gateway.repository.RequestRepository;
import com.ideaqr.gateway.service.AuditService;
import com.ideaqr.gateway.service.QrService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The <b>QR WALLET</b> — a read-only container that gathers all of a user's
 * digital entities behind their single identity: their primary QR, the objects
 * they govern, their requests and their history, plus the live Trust Score. The
 * brief asks only for the architectural foundation at this stage ("достаточно
 * предусмотреть архитектурную основу"); this endpoint provides exactly that
 * aggregation without introducing any new write path or weakening the core.
 */
@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
public class WalletController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int RECENT_LIMIT = 20;

    private final RegistryObjectRepository registryObjectRepository;
    private final RequestRepository requestRepository;
    private final AuditService auditService;
    private final QrService qrService;
    private final AuthSupport authSupport;

    @GetMapping("/wallet")
    public ResponseEntity<Map<String, Object>> wallet(Authentication authentication) {
        User user = authSupport.requireUser(authentication);
        Identity identity = authSupport.requireIdentity(authentication);

        Map<String, Object> wallet = new LinkedHashMap<>();

        // --- Owner identity + Trust Score --------------------------------------
        Map<String, Object> owner = new LinkedHashMap<>();
        owner.put("identityUid", identity.getIdentityUid().toString());
        owner.put("fullName", (user.getFirstName() + " " + user.getLastName()).trim());
        owner.put("roles", identity.getRoles());
        owner.put("trustScore", identity.getTrustScore());
        owner.put("trustLevel", identity.getTrustLevel());
        owner.put("riskScore", identity.getRiskScore());
        wallet.put("owner", owner);

        // --- Мой QR ------------------------------------------------------------
        Map<String, Object> myQr = new LinkedHashMap<>();
        myQr.put("primaryQrUid", identity.getPrimaryQrUid() != null ? identity.getPrimaryQrUid().toString() : null);
        myQr.put("qrValue", "IDENTITY:" + identity.getIdentityUid());
        myQr.put("qrImageDataUri", qrService.regenerateImageFor("IDENTITY:" + identity.getIdentityUid()));
        wallet.put("myQr", myQr);

        // --- Мои объекты / товары / услуги -------------------------------------
        List<Map<String, Object>> objects = new ArrayList<>();
        for (RegistryObject obj : registryObjectRepository
                .findByCreatedByIdentityUidOrderByCreatedAtDesc(identity.getIdentityUid())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("objectUid", obj.getObjectUid());
            m.put("displayName", obj.getDisplayName());
            m.put("category", obj.getCategory().name());
            m.put("status", obj.getStatus() != null ? obj.getStatus().name() : null);
            m.put("trustScore", obj.getTrustScore());
            objects.add(m);
        }
        wallet.put("myObjects", objects);

        // --- Мои заявки --------------------------------------------------------
        List<Map<String, Object>> requests = new ArrayList<>();
        requestRepository.findByIdentityUid(identity.getIdentityUid()).stream()
                .sorted(Comparator.comparing(RequestRecord::getCreatedAt).reversed())
                .limit(RECENT_LIMIT)
                .forEach(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("requestUid", r.getRequestUid().toString());
                    m.put("requestType", r.getRequestType().name());
                    m.put("status", r.getStatus().name());
                    m.put("objectUid", r.getObjectUid());
                    m.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().format(TS) : null);
                    requests.add(m);
                });
        wallet.put("myRequests", requests);

        // --- Моя история -------------------------------------------------------
        List<Map<String, Object>> history = new ArrayList<>();
        List<History> journal = auditService.journalFor(identity.getIdentityUid());
        for (History h : journal.subList(0, Math.min(RECENT_LIMIT, journal.size()))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("eventType", h.getEventType().name());
            m.put("description", h.getDescription());
            m.put("objectUid", h.getObjectUid());
            m.put("createdAt", h.getCreatedAt() != null ? h.getCreatedAt().format(TS) : null);
            history.add(m);
        }
        wallet.put("myHistory", history);

        return ResponseEntity.ok(wallet);
    }

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
