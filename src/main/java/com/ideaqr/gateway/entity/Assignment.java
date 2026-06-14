package com.ideaqr.gateway.entity;

import com.ideaqr.gateway.enums.AssignmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Linking layer. Stores the explicit relation between a subject, its QR and a concrete object.
 * Example: User X -> QR-123 -> Warehouse #7 (object_uid = "INFRA_WAREHOUSE_7").
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

    @Column(name = "identity_uid", nullable = false)
    private UUID identityUid;

    @Column(name = "qr_uid", nullable = false)
    private UUID qrUid;

    @Column(name = "object_uid", nullable = false, length = 128)
    private String objectUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AssignmentStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
