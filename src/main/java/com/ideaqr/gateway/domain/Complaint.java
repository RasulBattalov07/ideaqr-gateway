package com.ideaqr.gateway.domain;

import com.ideaqr.gateway.domain.enums.ComplaintStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A complaint ("жалоба") raised by a user. Per the brief every complaint is tied
 * to a concrete {@link Interaction}, and carries a subject, a category and a free
 * description. Complaints are reviewed by administrators (status lifecycle:
 * NEW → IN_PROGRESS → RESOLVED / REJECTED).
 */
@Entity
@Table(name = "complaints")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Complaint {

    @Id
    @Column(name = "complaint_uid", nullable = false, updatable = false)
    private UUID complaintUid;

    /** Author of the complaint. */
    @Column(name = "identity_uid", nullable = false)
    private UUID identityUid;

    /** The interaction the complaint is about (mandatory per the brief). */
    @Column(name = "interaction_uid", nullable = false)
    private UUID interactionUid;

    @Column(name = "subject", nullable = false, length = 200)
    private String subject;

    @Column(name = "category", nullable = false, length = 60)
    private String category;

    @Column(name = "description", length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ComplaintStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (complaintUid == null) {
            complaintUid = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = ComplaintStatus.NEW;
        }
    }
}
