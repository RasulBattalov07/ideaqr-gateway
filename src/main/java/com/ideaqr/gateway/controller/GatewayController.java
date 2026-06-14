package com.ideaqr.gateway.controller;

import com.ideaqr.gateway.dto.AssignmentRequest;
import com.ideaqr.gateway.dto.GatewayResponse;
import com.ideaqr.gateway.dto.IdentityResponse;
import com.ideaqr.gateway.dto.MigrationRequest;
import com.ideaqr.gateway.dto.MigrationResponse;
import com.ideaqr.gateway.dto.QrCreationRequest;
import com.ideaqr.gateway.dto.RegisterIdentityRequest;
import com.ideaqr.gateway.dto.ScanRequest;
import com.ideaqr.gateway.entity.Assignment;
import com.ideaqr.gateway.entity.History;
import com.ideaqr.gateway.entity.QrCode;
import com.ideaqr.gateway.repository.HistoryRepository;
import com.ideaqr.gateway.service.GatewayService;
import com.ideaqr.gateway.service.IdentityService;
import com.ideaqr.gateway.service.QrService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Public API surface for the IDEAQR Digital Gateway (Stage 2).
 *
 * Endpoints:
 *   POST /api/v2/scan                       - end-to-end scan pipeline
 *   POST /api/v2/identities                 - register a PRIMARY identity
 *   GET  /api/v2/identities                 - list identities
 *   GET  /api/v2/identities/{id}            - fetch one identity
 *   POST /api/v2/identities/migrate         - migrate a GUEST into a PRIMARY identity
 *   POST /api/v2/qr                          - register a QR (gated on APPROVED decision)
 *   POST /api/v2/assignments                - bind identity + QR to an object
 *   GET  /api/v2/audit                       - read the append-only history journal
 *   GET  /api/v2/health                      - liveness probe
 */
@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
public class GatewayController {

    private final GatewayService gatewayService;
    private final IdentityService identityService;
    private final QrService qrService;
    private final HistoryRepository historyRepository;

    @PostMapping("/scan")
    public ResponseEntity<GatewayResponse> scan(@Valid @RequestBody ScanRequest request) {
        return ResponseEntity.ok(gatewayService.processScan(request));
    }

    @PostMapping("/identities")
    public ResponseEntity<IdentityResponse> register(@Valid @RequestBody RegisterIdentityRequest request) {
        IdentityResponse response = identityService.registerPrimary(request.getRoles());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/identities")
    public ResponseEntity<List<IdentityResponse>> listIdentities() {
        return ResponseEntity.ok(identityService.findAll());
    }

    @GetMapping("/identities/{identityUid}")
    public ResponseEntity<IdentityResponse> getIdentity(@PathVariable UUID identityUid) {
        return ResponseEntity.ok(identityService.getResponseById(identityUid));
    }

    @PostMapping("/identities/migrate")
    public ResponseEntity<MigrationResponse> migrate(@Valid @RequestBody MigrationRequest request) {
        MigrationResponse response =
                identityService.migrateGuestToPrimary(request.getGuestIdentityUid(), request.getRoles());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/qr")
    public ResponseEntity<QrCode> createQr(@Valid @RequestBody QrCreationRequest request) {
        QrCode qr = qrService.createQr(
                request.getIdentityUid(), request.getRequestUid(), request.getQrType());
        return ResponseEntity.status(HttpStatus.CREATED).body(qr);
    }

    @PostMapping("/assignments")
    public ResponseEntity<Assignment> assign(@Valid @RequestBody AssignmentRequest request) {
        Assignment assignment = qrService.assign(
                request.getIdentityUid(), request.getQrUid(), request.getObjectUid());
        return ResponseEntity.status(HttpStatus.CREATED).body(assignment);
    }

    @GetMapping("/audit")
    public ResponseEntity<List<History>> audit() {
        return ResponseEntity.ok(historyRepository.findAllByOrderByCreatedAtDesc());
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "ideaqr-gateway",
                "stage", "2",
                "timestamp", LocalDateTime.now().toString()
        ));
    }
}
