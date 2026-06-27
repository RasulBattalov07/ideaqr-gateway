package com.ideaqr.gateway.domain;

import com.ideaqr.gateway.domain.enums.IdentityStatus;
import com.ideaqr.gateway.domain.enums.IdentityType;
import com.ideaqr.gateway.domain.enums.RoleType;
import com.ideaqr.gateway.tenant.TenantListener;
import com.ideaqr.gateway.tenant.TenantScoped;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Central subject of the platform. Every request, decision, interaction and
 * history record references an identity by its UUID.
 *
 * <p>Per the Stage 2 architecture, relationships are stored as plain UUID
 * fields rather than JPA associations, so a guest identity's history can be
 * re-pointed to a primary identity during a merge without cascade effects.</p>
 */
@Entity
@Table(name = "identities")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(TenantListener.class)
// Global definition of the tenant-isolation filter used by all TenantScoped entities.
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = UUID.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class Identity implements TenantScoped {

    @Id
    @Column(name = "identity_uid", nullable = false, updatable = false)
    private UUID identityUid;

    /** Owning tenant (organisation) — enforces hard SaaS isolation (audit 5.3). */
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "identity_type", nullable = false, length = 20)
    private IdentityType identityType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private IdentityStatus status;

    /**
     * Business roles. Loaded EAGERly because the decision engine needs them on
     * every request. Backed by a dedicated {@code identity_roles} table.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "identity_roles", joinColumns = @JoinColumn(name = "identity_uid"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 30)
    @BatchSize(size = 64)  // load roles for a page of identities in a few IN queries (audit 3.2)
    @Builder.Default
    private Set<RoleType> roles = new LinkedHashSet<>();

    /** Trust level used by the policy/risk engine (Guest=10 … Gov=90). */
    @Column(name = "trust_level", nullable = false)
    private int trustLevel;

    /** Identity-level risk score per the brief: NORMAL | MEDIUM | HIGH. */
    @Column(name = "risk_score", length = 20)
    @Builder.Default
    private String riskScore = "NORMAL";

    /**
     * Legacy column retained for schema compatibility (Flyway {@code validate}). The platform
     * unified on a SINGLE trust metric — {@link #trustLevel}, the value the policy engine gates
     * on — so the old gamified "trust score" is no longer computed or surfaced anywhere
     * (audit-fix: it looked decisive but never influenced a decision). Do not reintroduce a
     * second number; gate and display {@code trustLevel}.
     */
    @Column(name = "trust_score")
    @Builder.Default
    private Integer trustScore = 50;

    /** UUID of this identity's permanent primary QR (a {@code Qr} of type PRIMARY). */
    @Column(name = "primary_qr_uid")
    private UUID primaryQrUid;

    /**
     * Guest identities merged into this (primary) identity. Rather than rewriting the
     * guest's append-only history rows (which would violate immutability — audit 4.5),
     * a merge records the guest UID here as a soft alias; read paths union over it.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "identity_linked_guests", joinColumns = @JoinColumn(name = "identity_uid"))
    @Column(name = "guest_identity_uid")
    @BatchSize(size = 64)
    @Builder.Default
    private Set<UUID> linkedGuestUids = new LinkedHashSet<>();

    /**
     * SHA-256 of the one-time merge token issued to a GUEST identity's browser at
     * creation. A merge must present the matching token, proving the caller owns the
     * guest session — this closes the IDOR where any known guest UID could be claimed
     * (audit 4.6). Cleared once the guest has been merged.
     */
    @Column(name = "merge_token_hash", length = 64)
    private String mergeTokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (identityUid == null) {
            identityUid = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
