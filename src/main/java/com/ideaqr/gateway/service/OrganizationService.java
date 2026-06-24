package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Organization;
import com.ideaqr.gateway.domain.OrganizationMembership;
import com.ideaqr.gateway.repository.OrganizationMembershipRepository;
import com.ideaqr.gateway.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Manages organizations and identity ↔ organization memberships. Memberships are
 * what make working mode available (an identity can only enter working mode for
 * an organization it belongs to).
 */
@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationMembershipRepository membershipRepository;

    @Transactional
    public Organization ensureOrganization(String name, String type) {
        return organizationRepository.findByName(name).orElseGet(() ->
                organizationRepository.save(Organization.builder()
                        .name(name)
                        .type(type)
                        .status("ACTIVE")
                        .build()));
    }

    @Transactional
    public OrganizationMembership ensureMembership(UUID identityUid, UUID organizationUid, String workRole) {
        return membershipRepository.findByIdentityUidAndOrganizationUid(identityUid, organizationUid)
                .orElseGet(() -> membershipRepository.save(OrganizationMembership.builder()
                        .identityUid(identityUid)
                        .organizationUid(organizationUid)
                        .workRole(workRole)
                        .status("ACTIVE")
                        .build()));
    }

    public List<OrganizationMembership> membershipsOf(UUID identityUid) {
        return membershipRepository.findByIdentityUid(identityUid);
    }

    public Organization find(UUID organizationUid) {
        return organizationUid == null ? null : organizationRepository.findById(organizationUid).orElse(null);
    }

    /**
     * The organisation an identity acts under for the governance pipeline — the explicit
     * <b>Organization</b> element (Identifier → Identity/Object → Role → Organization →
     * Request → …). Resolves to the identity's first ACTIVE membership, or {@code null}
     * when it is a citizen/guest acting personally. Pure data resolution: new
     * organisations and memberships need no code change (платформенный принцип).
     */
    public Organization resolveActingOrganization(UUID identityUid) {
        return membershipsOf(identityUid).stream()
                .filter(m -> m.getStatus() == null || "ACTIVE".equalsIgnoreCase(m.getStatus()))
                .findFirst()
                .map(m -> find(m.getOrganizationUid()))
                .orElse(null);
    }
}
