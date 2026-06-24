package com.ideaqr.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <b>Policy</b> (Document 22) — the catalog of named access rules ({@code MEDICAL_ACCESS},
 * {@code INFRASTRUCTURE_ACCESS}, …). Access rules must not be hard-wired in code: making
 * Policy a data row means new roles / objects / organizations / scenarios add a Policy
 * <i>row</i>, not a code change (платформенный принцип расширяемости).
 *
 * <p>Foundation only: the MVP keeps the actual decision logic in {@code ValidationService}
 * and merely <i>names</i> the governing policy. A data-driven Policy <b>engine</b> is a
 * future phase (explicitly excluded from the MVP).</p>
 */
@Entity
@Table(name = "policies", uniqueConstraints = @UniqueConstraint(name = "uk_policy_code", columnNames = "code"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Policy {

    @Id
    @Column(name = "policy_uid", nullable = false, updatable = false)
    private UUID policyUid;

    /** Stable machine code, e.g. {@code MEDICAL_ACCESS}. */
    @Column(name = "code", nullable = false, length = 60)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 400)
    private String description;

    /** Object category this policy governs (nullable for cross-cutting policies). */
    @Column(name = "object_category", length = 20)
    private String objectCategory;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (policyUid == null) {
            policyUid = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
