package com.ideaqr.gateway.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Binds a QR to the identity it belongs to (e.g. a primary QR assigned to its
 * owner, or an object QR assigned to the administrator who minted it). Kept as a
 * first-class record so the assignment history is auditable.
 */
@Entity
@Table(name = "assignments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Assignment {

    @Id
    @Column(name = "assignment_uid", nullable = false, updatable = false)
    private UUID assignmentUid;

    /** The QR being assigned — real {@code @ManyToOne} + FK (audit 3.6). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "qr_uid", nullable = false,
            foreignKey = @ForeignKey(name = "fk_assignments_qr"))
    private Qr qr;

    /** The identity the QR is assigned to — real {@code @ManyToOne} + FK (audit 3.6). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "identity_uid", nullable = false,
            foreignKey = @ForeignKey(name = "fk_assignments_identity"))
    private Identity identity;

    /** Free-form role of the assignment, e.g. OWNER or GOVERNOR. */
    @Column(name = "assignment_role", length = 40)
    private String assignmentRole;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private LocalDateTime assignedAt;

    /** FK accessor that does not initialise the lazy {@link #qr} association. */
    public UUID getQrUid() {
        return qr != null ? qr.getQrUid() : null;
    }

    /** FK accessor that does not initialise the lazy {@link #identity} association. */
    public UUID getIdentityUid() {
        return identity != null ? identity.getIdentityUid() : null;
    }

    @PrePersist
    void onCreate() {
        if (assignmentUid == null) {
            assignmentUid = UUID.randomUUID();
        }
        if (assignedAt == null) {
            assignedAt = LocalDateTime.now();
        }
    }
}
