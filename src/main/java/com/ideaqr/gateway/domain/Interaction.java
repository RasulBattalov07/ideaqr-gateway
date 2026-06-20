package com.ideaqr.gateway.domain;

import com.ideaqr.gateway.domain.enums.InteractionStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A record that an interaction took place. Created on every request regardless
 * of the decision outcome, so even denied attempts are observable.
 */
@Entity
@Table(name = "interactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Interaction {

    @Id
    @Column(name = "interaction_uid", nullable = false, updatable = false)
    private UUID interactionUid;

    @Column(name = "identity_uid", nullable = false)
    private UUID identityUid;

    @Column(name = "request_uid", nullable = false)
    private UUID requestUid;

    @Column(name = "object_uid", length = 120)
    private String objectUid;

    /**
     * For person-to-person scans: the identity whose primary QR was scanned (the
     * "scanned" side). Lets a user see "who scanned me" without exposing the full
     * audit. Null for object scans.
     */
    @Column(name = "target_identity_uid")
    private UUID targetIdentityUid;

    /** Type of interaction, e.g. SCAN, QR_CREATION, REPORT, PROFILE_SCAN. */
    @Column(name = "interaction_type", nullable = false, length = 40)
    private String interactionType;

    /** Lifecycle: public scans are CONFIRMED; profile access starts PENDING. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @Builder.Default
    private InteractionStatus status = InteractionStatus.CONFIRMED;

    @Column(name = "detail", length = 400)
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (interactionUid == null) {
            interactionUid = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
