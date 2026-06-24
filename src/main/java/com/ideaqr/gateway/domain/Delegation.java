package com.ideaqr.gateway.domain;

import com.ideaqr.gateway.domain.enums.GrantStatus;
import com.ideaqr.gateway.domain.enums.PartyType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <b>Delegation</b> (Document 22) — one participant handing part of its authority to
 * another (a company lets an employee act for it; a patient lets a clinic handle
 * requests). Universal {@code (PartyType, uuid)} endpoints so delegation can run between
 * Identity / Organization / Object without specialised tables.
 *
 * <p>Foundation only — the model is provided; delegated-authority enforcement is a
 * future phase.</p>
 */
@Entity
@Table(name = "delegations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Delegation {

    @Id
    @Column(name = "delegation_uid", nullable = false, updatable = false)
    private UUID delegationUid;

    /** Who delegated the authority. */
    @Enumerated(EnumType.STRING)
    @Column(name = "delegator_type", nullable = false, length = 20)
    private PartyType delegatorType;

    @Column(name = "delegator_uid", nullable = false)
    private UUID delegatorUid;

    /** Who received the authority. */
    @Enumerated(EnumType.STRING)
    @Column(name = "delegatee_type", nullable = false, length = 20)
    private PartyType delegateeType;

    @Column(name = "delegatee_uid", nullable = false)
    private UUID delegateeUid;

    /** What was delegated (free-form scope label, refined in a future phase). */
    @Column(name = "scope", length = 60)
    private String scope;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GrantStatus status;

    @Column(name = "valid_until")
    private LocalDateTime validUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @PrePersist
    void onCreate() {
        if (delegationUid == null) {
            delegationUid = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = GrantStatus.ACTIVE;
        }
    }
}
