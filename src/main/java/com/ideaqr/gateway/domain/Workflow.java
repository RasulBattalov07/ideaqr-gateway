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
 * Architectural scaffolding for multi-step approval. The current pipeline is
 * Request → Decision; a Workflow lets future stages (approval → extra check →
 * decision) attach to a request for critical objects, financial operations or
 * special permissions. Only the entity and its link to a request are provided now.
 */
@Entity
@Table(name = "workflows")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Workflow {

    @Id
    @Column(name = "workflow_uid", nullable = false, updatable = false)
    private UUID workflowUid;

    /** The request this workflow governs. */
    @Column(name = "request_uid", nullable = false)
    private UUID requestUid;

    /** Workflow type, e.g. CRITICAL_ACCESS, FINANCIAL, SPECIAL_PERMISSION. */
    @Column(name = "workflow_type", length = 40)
    private String workflowType;

    /** Stage status, e.g. PENDING, APPROVING, COMPLETED. */
    @Column(name = "status", length = 30)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (workflowUid == null) {
            workflowUid = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = "PENDING";
        }
    }
}
