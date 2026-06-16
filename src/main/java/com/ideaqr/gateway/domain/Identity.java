package com.ideaqr.gateway.domain;

import com.ideaqr.gateway.domain.enums.IdentityStatus;
import com.ideaqr.gateway.domain.enums.IdentityType;
import com.ideaqr.gateway.domain.enums.RoleType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
public class Identity {

    @Id
    @Column(name = "identity_uid", nullable = false, updatable = false)
    private UUID identityUid;

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
    @Builder.Default
    private Set<RoleType> roles = new LinkedHashSet<>();

    /** Trust level used by the policy/risk engine (Guest=10 … Gov=90). */
    @Column(name = "trust_level", nullable = false)
    private int trustLevel;

    /** UUID of this identity's permanent primary QR (a {@code Qr} of type PRIMARY). */
    @Column(name = "primary_qr_uid")
    private UUID primaryQrUid;

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
