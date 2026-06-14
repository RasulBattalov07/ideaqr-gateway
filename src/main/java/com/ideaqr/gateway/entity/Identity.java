package com.ideaqr.gateway.entity;

import com.ideaqr.gateway.enums.IdentityStatus;
import com.ideaqr.gateway.enums.IdentityType;
import com.ideaqr.gateway.enums.Role;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Primary subject of the system.
 * One real person = one Identity = one main QR code.
 * Roles are held as a collection so a single Identity can carry several roles at once.
 */
@Entity
@Table(name = "identities")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Identity {

    @Id
    @Column(name = "identity_uid", nullable = false, updatable = false)
    private UUID identityUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "identity_type", nullable = false, length = 32)
    private IdentityType identityType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private IdentityStatus status;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "identity_roles",
            joinColumns = @JoinColumn(name = "identity_uid")
    )
    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 32)
    @Builder.Default
    private Set<Role> roles = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public void addRole(Role role) {
        if (this.roles == null) {
            this.roles = new HashSet<>();
        }
        this.roles.add(role);
    }
}
