package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.service.MedicalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The <b>Doctor → Pharmacist</b> prescription flow. A doctor writes a prescription onto a
 * medical object; a pharmacist dispenses it. Role enforcement lives in
 * {@link MedicalService} (only DOCTOR may prescribe, only PHARMACIST may dispense); the URL
 * tier only requires an authenticated session.
 */
@RestController
@RequestMapping("/api/v2/medical")
@RequiredArgsConstructor
public class MedicalController {

    private final MedicalService medicalService;
    private final AuthSupport authSupport;

    @GetMapping("/{objectUid}/prescriptions")
    public ResponseEntity<List<Map<String, Object>>> list(@PathVariable("objectUid") String objectUid,
                                                          Authentication authentication) {
        authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(medicalService.listForObject(objectUid));
    }

    @PostMapping("/{objectUid}/prescribe")
    public ResponseEntity<List<Map<String, Object>>> prescribe(@PathVariable("objectUid") String objectUid,
                                                              @RequestBody Map<String, String> body,
                                                              Authentication authentication) {
        Identity doctor = authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(medicalService.prescribe(doctor, objectUid,
                body.get("name"), body.get("dose"), body.get("schedule")));
    }

    @PostMapping("/prescriptions/{interactionUid}/dispense")
    public ResponseEntity<List<Map<String, Object>>> dispense(@PathVariable("interactionUid") String interactionUid,
                                                             Authentication authentication) {
        Identity pharmacist = authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(medicalService.dispense(pharmacist, UUID.fromString(interactionUid)));
    }
}
