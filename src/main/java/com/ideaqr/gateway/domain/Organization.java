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
 * An organization an identity can be affiliated with (used by working mode,
 * clearances and access policies). Stored independently of identities; the link
 * is an {@link OrganizationMembership}.
 */
@Entity
@Table(name = "organizations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Organization {

    @Id
    @Column(name = "organization_uid", nullable = false, updatable = false)
    private UUID organizationUid;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    /** Free-form organization type, e.g. MEDICAL, INFRASTRUCTURE, RETAIL, GOVERNMENT. */
    @Column(name = "type", length = 40)
    private String type;

    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (organizationUid == null) {
            organizationUid = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = "ACTIVE";
        }
    }
}
