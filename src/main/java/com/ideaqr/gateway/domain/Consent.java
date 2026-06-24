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
 * <b>Consent</b> (Document 22) — the explicit will of a data/asset owner, recorded
 * separately from the system's {@code Decision}. Future pipeline:
 * {@code Request → Consent → Decision → Interaction}.
 *
 * <p>Architectural foundation only: this is the data model + relationships + migration;
 * the Consent <i>engine</i> is explicitly out of scope for the MVP. The grantor, grantee
 * and subject are stored as universal {@code (PartyType, uuid)} pairs so a consent can
 * span Identity / Object / Organization / Request without typed join tables.</p>
 */
@Entity
@Table(name = "consents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Consent {

    @Id
    @Column(name = "consent_uid", nullable = false, updatable = false)
    private UUID consentUid;

    /** Who granted the consent. */
    @Enumerated(EnumType.STRING)
    @Column(name = "grantor_type", nullable = false, length = 20)
    private PartyType grantorType;

    @Column(name = "grantor_uid", nullable = false)
    private UUID grantorUid;

    /** To whom the consent was granted. */
    @Enumerated(EnumType.STRING)
    @Column(name = "grantee_type", nullable = false, length = 20)
    private PartyType granteeType;

    @Column(name = "grantee_uid", nullable = false)
    private UUID granteeUid;

    /** What the consent is about (an identity profile, an object, an organization, a request). */
    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 20)
    private PartyType subjectType;

    @Column(name = "subject_uid", nullable = false)
    private UUID subjectUid;

    /** Free-form scope label (e.g. {@code PROFILE_ACCESS}); refined in a future phase. */
    @Column(name = "scope", length = 60)
    private String scope;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GrantStatus status;

    /** Optional validity window end; {@code null} means open-ended. */
    @Column(name = "valid_until")
    private LocalDateTime validUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @PrePersist
    void onCreate() {
        if (consentUid == null) {
            consentUid = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = GrantStatus.ACTIVE;
        }
    }
}
