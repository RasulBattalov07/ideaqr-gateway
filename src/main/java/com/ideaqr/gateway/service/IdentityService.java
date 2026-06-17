package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.enums.HistoryEventType;
import com.ideaqr.gateway.domain.enums.IdentityStatus;
import com.ideaqr.gateway.domain.enums.IdentityType;
import com.ideaqr.gateway.domain.enums.RoleType;
import com.ideaqr.gateway.repository.IdentityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Manages digital identities: creates the permanent identity behind each user
 * account, mints temporary guest identities for unauthenticated traffic, and
 * resolves identities for the pipeline.
 */
@Service
@RequiredArgsConstructor
public class IdentityService {

    private final IdentityRepository identityRepository;
    private final AuditService auditService;

    /** Trust baselines per the Stage 2 trust profile module. */
    public static final int TRUST_GUEST = 10;
    public static final int TRUST_CITIZEN = 50;
    public static final int TRUST_VERIFIED = 80;
    public static final int TRUST_SPECIALIST = 85;
    public static final int TRUST_GOV = 90;

    /**
     * Create the permanent (PRIMARY) identity that backs a user account.
     */
    public Identity createPrimaryIdentity(Set<RoleType> roles, int trustLevel) {
        Set<RoleType> roleSet = new LinkedHashSet<>(roles == null ? Set.of() : roles);
        if (roleSet.isEmpty()) {
            roleSet.add(RoleType.CITIZEN);
        }
        Identity identity = Identity.builder()
                .identityType(IdentityType.PRIMARY)
                .status(IdentityStatus.ACTIVE)
                .roles(roleSet)
                .trustLevel(trustLevel)
                .build();
        identity = identityRepository.save(identity);
        auditService.record(identity.getIdentityUid(), null, HistoryEventType.IDENTITY_CREATED,
                "Создана основная цифровая личность с ролями: " + roleSet);
        return identity;
    }

    /**
     * Mint a temporary guest identity on the fly. Guest actions are still logged
     * and can later be merged into a primary identity.
     */
    public Identity createGuestIdentity() {
        Identity guest = Identity.builder()
                .identityType(IdentityType.GUEST)
                .status(IdentityStatus.ACTIVE)
                .roles(new LinkedHashSet<>(Set.of(RoleType.CITIZEN)))
                .trustLevel(TRUST_GUEST)
                .build();
        guest = identityRepository.save(guest);
        auditService.record(guest.getIdentityUid(), null, HistoryEventType.IDENTITY_CREATED,
                "Создана временная гостевая личность");
        return guest;
    }

    public Identity getOrCreateGuest(UUID identityUid) {
        if (identityUid != null) {
            return identityRepository.findById(identityUid).orElseGet(this::createGuestIdentity);
        }
        return createGuestIdentity();
    }

    public Identity findById(UUID identityUid) {
        return identityRepository.findById(identityUid)
                .orElseThrow(() -> new IllegalStateException("Личность не найдена: " + identityUid));
    }

    public Identity save(Identity identity) {
        return identityRepository.save(identity);
    }
}
