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

    /**
     * Attach an identity to an organization as a verified, ACTIVE member — the trusted
     * (admin-driven) counterpart of the self-service employment claim. Unlike
     * {@link #ensureMembership}, an existing PENDING/REJECTED claim is promoted to
     * ACTIVE and the work role is refreshed, so the resulting membership is always
     * one that working mode accepts.
     */
    @Transactional
    public OrganizationMembership ensureActiveMembership(UUID identityUid, UUID organizationUid, String workRole) {
        OrganizationMembership membership = membershipRepository
                .findByIdentityUidAndOrganizationUid(identityUid, organizationUid)
                .orElseGet(() -> OrganizationMembership.builder()
                        .identityUid(identityUid)
                        .organizationUid(organizationUid)
                        .build());
        membership.setWorkRole(workRole);
        membership.setStatus("ACTIVE");
        return membershipRepository.save(membership);
    }

    public List<OrganizationMembership> membershipsOf(UUID identityUid) {
        return membershipRepository.findByIdentityUid(identityUid);
    }

    /**
     * Memberships that actually govern: ACTIVE only (a missing status is legacy-ACTIVE).
     * A PENDING employment claim or a REJECTED one grants nothing — working mode and
     * the organization picker must use this view, never {@link #membershipsOf}.
     */
    public List<OrganizationMembership> activeMembershipsOf(UUID identityUid) {
        return membershipsOf(identityUid).stream()
                .filter(m -> m.getStatus() == null || "ACTIVE".equalsIgnoreCase(m.getStatus()))
                .toList();
    }

    /** All organizations — used to populate the public sign-up "employer" picker. */
    public List<Organization> listOrganizations() {
        return organizationRepository.findAll();
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
        return activeMembershipsOf(identityUid).stream()
                .findFirst()
                .map(m -> find(m.getOrganizationUid()))
                .orElse(null);
    }
}
