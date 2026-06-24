package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.Consent;
import com.ideaqr.gateway.domain.Delegation;
import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.OrganizationMembership;
import com.ideaqr.gateway.domain.Policy;
import com.ideaqr.gateway.domain.Relationship;
import com.ideaqr.gateway.domain.enums.PartyType;
import com.ideaqr.gateway.domain.enums.RelationshipType;
import com.ideaqr.gateway.repository.ConsentRepository;
import com.ideaqr.gateway.repository.DelegationRepository;
import com.ideaqr.gateway.repository.PolicyRepository;
import com.ideaqr.gateway.repository.RelationshipRepository;
import com.ideaqr.gateway.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <b>Architecture foundation — read-only API stubs (заготовки) for "Документ 22".</b>
 *
 * <p>These endpoints expose the new architectural layers — {@code Policy}, {@code Consent},
 * {@code Relationship}, {@code Delegation} — so the foundation is reachable and demonstrable.
 * They are deliberately <b>read-only and engine-less</b>: the MVP provides the models,
 * migrations and stubs; the Policy / Consent / Delegation engines are a future phase
 * (and the excluded concepts — Trust Engine, Smart Contracts, Digital Assets … — have no
 * endpoint here at all).</p>
 *
 * <p>The relationship view also <i>projects</i> the caller's real organisation memberships
 * as {@code EMPLOYEE_ORGANIZATION} edges, showing the universal Relationship model working
 * over existing data without a per-type table.</p>
 */
@RestController
@RequestMapping("/api/v2/foundation")
@RequiredArgsConstructor
public class FoundationController {

    private final PolicyRepository policyRepository;
    private final ConsentRepository consentRepository;
    private final RelationshipRepository relationshipRepository;
    private final DelegationRepository delegationRepository;
    private final OrganizationService organizationService;
    private final AuthSupport authSupport;

    /** The Policy catalog (Document 22) — the source of access rules a future engine will read. */
    @GetMapping("/policies")
    public ResponseEntity<List<Map<String, Object>>> policies(Authentication authentication) {
        authSupport.requireUser(authentication);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Policy p : policyRepository.findAllByOrderByCodeAsc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", p.getCode());
            m.put("name", p.getName());
            m.put("description", p.getDescription());
            m.put("objectCategory", p.getObjectCategory());
            m.put("active", p.isActive());
            rows.add(m);
        }
        return ResponseEntity.ok(rows);
    }

    /** Consents granted to or by the caller (Document 22). Empty until a future phase populates them. */
    @GetMapping("/consents")
    public ResponseEntity<List<Map<String, Object>>> consents(Authentication authentication) {
        Identity me = authSupport.requireIdentity(authentication);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Consent c : consentRepository.findByGranteeUidOrderByCreatedAtDesc(me.getIdentityUid())) {
            rows.add(consentRow(c, "RECEIVED"));
        }
        for (Consent c : consentRepository.findByGrantorUidOrderByCreatedAtDesc(me.getIdentityUid())) {
            rows.add(consentRow(c, "GRANTED"));
        }
        return ResponseEntity.ok(rows);
    }

    /**
     * The caller's relationships (Document 22). Projects real organisation memberships as
     * {@code EMPLOYEE_ORGANIZATION} edges and unions any persisted relationships — the same
     * universal {@code (PartyType, uuid)} shape for every kind of tie.
     */
    @GetMapping("/relationships")
    public ResponseEntity<List<Map<String, Object>>> relationships(Authentication authentication) {
        Identity me = authSupport.requireIdentity(authentication);
        UUID meUid = me.getIdentityUid();
        List<Map<String, Object>> rows = new ArrayList<>();

        // Projection over existing data — demonstrates the universal model without new writes.
        for (OrganizationMembership mem : organizationService.membershipsOf(meUid)) {
            rows.add(edge(PartyType.IDENTITY, meUid, PartyType.ORGANIZATION, mem.getOrganizationUid(),
                    RelationshipType.EMPLOYEE_ORGANIZATION, mem.getStatus(), "membership-projection"));
        }
        // Plus any relationships persisted into the foundation table.
        for (Relationship r : relationshipRepository.findByFromUidOrderByCreatedAtDesc(meUid)) {
            rows.add(edge(r.getFromType(), r.getFromUid(), r.getToType(), r.getToUid(),
                    r.getRelationshipType(), r.getStatus(), "persisted"));
        }
        return ResponseEntity.ok(rows);
    }

    /** Delegations to or by the caller (Document 22). Empty until a future phase populates them. */
    @GetMapping("/delegations")
    public ResponseEntity<List<Map<String, Object>>> delegations(Authentication authentication) {
        Identity me = authSupport.requireIdentity(authentication);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Delegation d : delegationRepository.findByDelegateeUidOrderByCreatedAtDesc(me.getIdentityUid())) {
            rows.add(delegationRow(d));
        }
        return ResponseEntity.ok(rows);
    }

    // ------------------------------------------------------------------

    private Map<String, Object> consentRow(Consent c, String direction) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("consentUid", c.getConsentUid().toString());
        m.put("direction", direction);
        m.put("subjectType", c.getSubjectType().name());
        m.put("subjectUid", c.getSubjectUid().toString());
        m.put("scope", c.getScope());
        m.put("status", c.getStatus().name());
        return m;
    }

    private Map<String, Object> delegationRow(Delegation d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("delegationUid", d.getDelegationUid().toString());
        m.put("scope", d.getScope());
        m.put("status", d.getStatus().name());
        return m;
    }

    private Map<String, Object> edge(PartyType fromType, UUID fromUid, PartyType toType, UUID toUid,
                                     RelationshipType type, String status, String origin) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fromType", fromType.name());
        m.put("fromUid", fromUid != null ? fromUid.toString() : null);
        m.put("toType", toType.name());
        m.put("toUid", toUid != null ? toUid.toString() : null);
        m.put("relationshipType", type.name());
        m.put("status", status);
        m.put("origin", origin);
        return m;
    }
}
