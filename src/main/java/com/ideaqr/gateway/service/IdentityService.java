package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.enums.HistoryEventType;
import com.ideaqr.gateway.domain.enums.IdentityStatus;
import com.ideaqr.gateway.domain.enums.IdentityType;
import com.ideaqr.gateway.domain.enums.RoleType;
import com.ideaqr.gateway.repository.IdentityRepository;
import com.ideaqr.gateway.util.Hashing;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Manages the platform's central subject — the {@link Identity}. Provisions both
 * primary (registered) and guest identities, and exposes the trust constants used
 * by the policy/risk engine.
 */
@Service
@RequiredArgsConstructor
public class IdentityService {

    // Trust levels (0–100) referenced by the policy engine and profession mapping.
    public static final int TRUST_GUEST = 10;
    public static final int TRUST_CITIZEN = 50;
    public static final int TRUST_VERIFIED = 80;
    public static final int TRUST_SPECIALIST = 85;
    public static final int TRUST_GOV = 90;

    private final IdentityRepository identityRepository;
    private final AuditService auditService;

    @Transactional
    public Identity createPrimaryIdentity(Set<RoleType> roles, int trustLevel) {
        Identity identity = Identity.builder()
                .identityType(IdentityType.PRIMARY)
                .status(IdentityStatus.ACTIVE)
                .roles(new LinkedHashSet<>(roles))
                .trustLevel(trustLevel)
                .riskScore("NORMAL")
                .build();
        identity = identityRepository.save(identity);
        auditService.record(identity.getIdentityUid(), null, HistoryEventType.IDENTITY_CREATED,
                "Создана основная личность (уровень доверия " + trustLevel + ").");
        return identity;
    }

    /** A provisioned guest identity plus the one-time merge token for its browser. */
    public record GuestProvision(Identity identity, String mergeToken) {}

    @Transactional
    public GuestProvision createGuestIdentity() {
        // Issue a one-time, high-entropy merge token; only its hash is persisted.
        // Presenting the matching token later proves ownership of this guest session
        // and is the gate on merge (audit 4.6).
        String mergeToken = Hashing.randomToken();
        Identity identity = Identity.builder()
                .identityType(IdentityType.GUEST)
                .status(IdentityStatus.ACTIVE)
                .roles(new LinkedHashSet<>(Set.of(RoleType.CITIZEN)))
                .trustLevel(TRUST_GUEST)
                .riskScore("MEDIUM")
                .mergeTokenHash(Hashing.sha256Hex(mergeToken))
                .build();
        identity = identityRepository.save(identity);
        auditService.record(identity.getIdentityUid(), null, HistoryEventType.IDENTITY_CREATED,
                "Создана гостевая личность.");
        return new GuestProvision(identity, mergeToken);
    }

    public Identity save(Identity identity) {
        return identityRepository.save(identity);
    }

    public Identity findById(UUID identityUid) {
        // Audit M-3: do not echo the raw identifier back to the client in the error message.
        return identityRepository.findById(identityUid)
                .orElseThrow(() -> new IllegalStateException("Личность не найдена."));
    }

    /** Batch-load identities by id (used to avoid N+1 in the admin user list — audit 3.2). */
    public java.util.List<Identity> findAllByIds(java.util.Collection<UUID> ids) {
        return identityRepository.findAllById(ids);
    }
}
