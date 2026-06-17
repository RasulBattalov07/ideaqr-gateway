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

    @Column(name = "qr_uid", nullable = false)
    private UUID qrUid;

    @Column(name = "identity_uid", nullable = false)
    private UUID identityUid;

    /** Free-form role of the assignment, e.g. OWNER or GOVERNOR. */
    @Column(name = "assignment_role", length = 40)
    private String assignmentRole;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private LocalDateTime assignedAt;

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
