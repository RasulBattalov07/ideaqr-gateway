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
 * Links an {@link Identity} to an {@link Organization} with a work role. An
 * identity may belong to several organizations; activating working mode selects
 * one of these memberships as the active context.
 */
@Entity
@Table(name = "organization_memberships")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrganizationMembership {

    @Id
    @Column(name = "membership_uid", nullable = false, updatable = false)
    private UUID membershipUid;

    @Column(name = "identity_uid", nullable = false)
    private UUID identityUid;

    @Column(name = "organization_uid", nullable = false)
    private UUID organizationUid;

    /** Work role within this organization, e.g. DOCTOR, INSPECTOR, RETAIL_ADMIN. */
    @Column(name = "work_role", length = 40)
    private String workRole;

    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (membershipUid == null) {
            membershipUid = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = "ACTIVE";
        }
    }
}
