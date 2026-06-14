package com.ideaqr.gateway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Records the fact of a physical or digital interaction (a scan, an access attempt).
 * Ties together the identity, the originating request and the resulting decision.
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

    @Column(name = "decision_uid", nullable = false)
    private UUID decisionUid;

    @Column(name = "object_uid", length = 128)
    private String objectUid;

    @Column(name = "interaction_type", nullable = false, length = 64)
    private String interactionType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
