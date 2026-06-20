package com.ideaqr.gateway.domain;

import com.ideaqr.gateway.domain.enums.ModuleStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A platform module — a distinct sphere of interaction (Users, Services, Goods,
 * Medicine, Infrastructure …). Administrators can create, edit and disable
 * modules. Every module is expected to use the single platform chain
 * (Identity → Request → Decision → Interaction → Event → History); the module is
 * only metadata describing the sphere, never a bypass of that chain.
 */
@Entity
@Table(name = "platform_modules",
        uniqueConstraints = @UniqueConstraint(name = "uk_module_code", columnNames = "code"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlatformModule {

    @Id
    @Column(name = "module_uid", nullable = false, updatable = false)
    private UUID moduleUid;

    /** Stable machine code, e.g. USERS, SERVICES, GOODS, MEDICINE, INFRASTRUCTURE. */
    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 400)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ModuleStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (moduleUid == null) {
            moduleUid = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = ModuleStatus.ACTIVE;
        }
    }
}
