package com.ideaqr.gateway.service;

import com.ideaqr.gateway.dto.IdentityResponse;
import com.ideaqr.gateway.dto.MigrationResponse;
import com.ideaqr.gateway.entity.Identity;
import com.ideaqr.gateway.entity.Interaction;
import com.ideaqr.gateway.entity.RequestRecord;
import com.ideaqr.gateway.enums.IdentityStatus;
import com.ideaqr.gateway.enums.IdentityType;
import com.ideaqr.gateway.enums.Role;
import com.ideaqr.gateway.exception.BusinessRuleException;
import com.ideaqr.gateway.exception.ResourceNotFoundException;
import com.ideaqr.gateway.repository.IdentityRepository;
import com.ideaqr.gateway.repository.InteractionRepository;
import com.ideaqr.gateway.repository.RequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns the lifecycle of Identity entities, including anonymous guest provisioning
 * and the guest-to-primary migration that re-points historical artefacts.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IdentityService {

    private final IdentityRepository identityRepository;
    private final RequestRepository requestRepository;
    private final InteractionRepository interactionRepository;
    private final AuditService auditService;

    /**
     * Register a new permanent PRIMARY identity with the given roles.
     */
    @Transactional
    public IdentityResponse registerPrimary(Set<Role> roles) {
        Identity identity = Identity.builder()
                .identityUid(UUID.randomUUID())
                .identityType(IdentityType.PRIMARY)
                .status(IdentityStatus.ACTIVE)
                .roles(roles == null ? new HashSet<>() : new HashSet<>(roles))
                .createdAt(LocalDateTime.now())
                .build();
        Identity saved = identityRepository.save(identity);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("identityUid", saved.getIdentityUid().toString());
        payload.put("identityType", saved.getIdentityType().name());
        payload.put("roles", saved.getRoles());
        auditService.append("IDENTITY_REGISTERED", null, payload);

        log.info("Registered PRIMARY identity {}", saved.getIdentityUid());
        return toResponse(saved);
    }

    /**
     * Provision an ephemeral GUEST identity for an anonymous scan.
     * Guests start with no roles and PENDING status.
     */
    @Transactional
    public Identity provisionGuest() {
        Identity guest = Identity.builder()
                .identityUid(UUID.randomUUID())
                .identityType(IdentityType.GUEST)
                .status(IdentityStatus.PENDING)
                .roles(new HashSet<>())
                .createdAt(LocalDateTime.now())
                .build();
        Identity saved = identityRepository.save(guest);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("identityUid", saved.getIdentityUid().toString());
        payload.put("identityType", saved.getIdentityType().name());
        auditService.append("GUEST_PROVISIONED", null, payload);

        log.info("Provisioned GUEST identity {}", saved.getIdentityUid());
        return saved;
    }

    /**
     * Look up an identity or throw.
     */
    @Transactional(readOnly = true)
    public Identity getById(UUID identityUid) {
        return identityRepository.findById(identityUid)
                .orElseThrow(() -> new ResourceNotFoundException("Identity not found: " + identityUid));
    }

    @Transactional(readOnly = true)
    public IdentityResponse getResponseById(UUID identityUid) {
        return toResponse(getById(identityUid));
    }

    @Transactional(readOnly = true)
    public List<IdentityResponse> findAll() {
        return identityRepository.findAll().stream().map(this::toResponse).toList();
    }

    /**
     * Migrate a GUEST identity into a new PRIMARY identity.
     * All Requests and Interactions previously bound to the guest's identityUid are
     * re-pointed onto the new primary identityUid, and an append-only audit event is written.
     *
     * The guest identity itself is marked SUSPENDED (kept for traceability) rather than deleted.
     */
    @Transactional
    public MigrationResponse migrateGuestToPrimary(UUID guestIdentityUid, Set<Role> roles) {
        Identity guest = identityRepository.findById(guestIdentityUid)
                .orElseThrow(() -> new ResourceNotFoundException("Guest identity not found: " + guestIdentityUid));

        if (guest.getIdentityType() != IdentityType.GUEST) {
            throw new BusinessRuleException(
                    "Identity " + guestIdentityUid + " is not a GUEST and cannot be migrated");
        }

        // Create the new permanent identity.
        Identity primary = Identity.builder()
                .identityUid(UUID.randomUUID())
                .identityType(IdentityType.PRIMARY)
                .status(IdentityStatus.ACTIVE)
                .roles(roles == null ? new HashSet<>() : new HashSet<>(roles))
                .createdAt(LocalDateTime.now())
                .build();
        Identity savedPrimary = identityRepository.save(primary);

        // Re-point requests.
        List<RequestRecord> guestRequests =
                requestRepository.findByIdentityUidOrderByCreatedAtDesc(guestIdentityUid);
        for (RequestRecord req : guestRequests) {
            req.setIdentityUid(savedPrimary.getIdentityUid());
        }
        requestRepository.saveAll(guestRequests);

        // Re-point interactions.
        List<Interaction> guestInteractions =
                interactionRepository.findByIdentityUidOrderByCreatedAtDesc(guestIdentityUid);
        for (Interaction interaction : guestInteractions) {
            interaction.setIdentityUid(savedPrimary.getIdentityUid());
        }
        interactionRepository.saveAll(guestInteractions);

        // Retire the guest shell.
        guest.setStatus(IdentityStatus.SUSPENDED);
        identityRepository.save(guest);

        // Append immutable audit event capturing the merge.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("guestIdentityUid", guestIdentityUid.toString());
        payload.put("primaryIdentityUid", savedPrimary.getIdentityUid().toString());
        payload.put("rebindedRequests", guestRequests.size());
        payload.put("rebindedInteractions", guestInteractions.size());
        payload.put("grantedRoles", savedPrimary.getRoles());
        auditService.append("IDENTITY_MERGED", null, payload);

        log.info("Migrated GUEST {} -> PRIMARY {} (requests={}, interactions={})",
                guestIdentityUid, savedPrimary.getIdentityUid(),
                guestRequests.size(), guestInteractions.size());

        return MigrationResponse.builder()
                .guestIdentityUid(guestIdentityUid)
                .primaryIdentityUid(savedPrimary.getIdentityUid())
                .rebindedRequests(guestRequests.size())
                .rebindedInteractions(guestInteractions.size())
                .appendedHistoryEvents(1)
                .message("Guest successfully migrated to primary identity")
                .build();
    }

    public IdentityResponse toResponse(Identity identity) {
        return IdentityResponse.builder()
                .identityUid(identity.getIdentityUid())
                .identityType(identity.getIdentityType())
                .status(identity.getStatus())
                .roles(identity.getRoles())
                .createdAt(identity.getCreatedAt())
                .build();
    }
}
