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
    private final com.ideaqr.gateway.service.RegistryClient registryClient;

    @GetMapping("/{objectUid}/prescriptions")
    public ResponseEntity<List<Map<String, Object>>> list(@PathVariable("objectUid") String objectUid,
                                                          Authentication authentication) {
        Identity caller = authSupport.requireIdentity(authentication);
        requireRxReadAccess(caller, objectUid);
        return ResponseEntity.ok(medicalService.listForObject(objectUid));
    }

    /**
     * Срез рецептов (Phase 2 — минимальный доступ): читать его могут врач, фармацевт или
     * сам пациент карты. Раньше листинг был открыт любой авторизованной сессии.
     */
    private void requireRxReadAccess(Identity caller, String objectUid) {
        var roles = caller.getRoles();
        if (roles.contains(com.ideaqr.gateway.domain.enums.RoleType.DOCTOR)
                || roles.contains(com.ideaqr.gateway.domain.enums.RoleType.PHARMACIST)) {
            return;
        }
        var resolved = registryClient.resolve(objectUid);
        Object patient = resolved.data() != null ? resolved.data().get("patientIdentityUid") : null;
        if (patient != null && patient.toString().equals(caller.getIdentityUid().toString())) {
            return;
        }
        throw new org.springframework.security.access.AccessDeniedException(
                "Список назначений доступен врачу, фармацевту или самому пациенту.");
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
