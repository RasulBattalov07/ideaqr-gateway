package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.dto.GatewayResponse;
import com.ideaqr.gateway.dto.ReportRequest;
import com.ideaqr.gateway.dto.ScanRequest;
import com.ideaqr.gateway.service.GatewayService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The citizen / specialist terminal API. Every scan and report is driven through
 * the governance pipeline; the response carries the verdict, the localized
 * reason, the data payload (when granted) and the full chain of UUIDs.
 */
@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
public class GatewayController {

    private final GatewayService gatewayService;
    private final AuthSupport authSupport;

    @PostMapping("/scan")
    public ResponseEntity<GatewayResponse> scan(@Valid @RequestBody ScanRequest request,
                                                Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(gatewayService.scan(identity, request));
    }

    @PostMapping("/report")
    public ResponseEntity<GatewayResponse> report(@Valid @RequestBody ReportRequest request,
                                                  Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(gatewayService.report(identity, request));
    }

    @PostMapping("/sos")
    public ResponseEntity<GatewayResponse> sos(@RequestBody(required = false) Map<String, String> body,
                                               Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        String objectUid = body != null ? body.get("objectUid") : null;
        String message = body != null ? body.get("message") : null;
        return ResponseEntity.ok(gatewayService.sos(identity, objectUid, message));
    }
}
