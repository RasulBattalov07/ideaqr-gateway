package com.ideaqr.gateway.domain;

import com.ideaqr.gateway.domain.enums.EmploymentStatus;
import com.ideaqr.gateway.tenant.TenantListener;
import com.ideaqr.gateway.tenant.TenantScoped;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A persistent user account. Stage 3 adds this on top of the Stage 2 identity
 * layer: each user is linked to exactly one {@link Identity} (stored as a UUID
 * field, consistent with the rest of the schema).
 *
 * <p>The password is stored as a BCrypt hash and is never exposed by the API.</p>
 */
@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(name = "uk_users_username", columnNames = "username"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(TenantListener.class)
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class User implements TenantScoped {

    @Id
    @Column(name = "user_uid", nullable = false, updatable = false)
    private UUID userUid;

    @Column(name = "username", nullable = false, length = 60)
    private String username;

    /** BCrypt-encoded password hash. */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "first_name", nullable = false, length = 80)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 80)
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_status", nullable = false, length = 20)
    private EmploymentStatus employmentStatus;

    /** Profession key (e.g. DOCTOR, RETAIL_ADMIN, INSPECTOR, CITIZEN). */
    @Column(name = "profession", nullable = false, length = 40)
    private String profession;

    /** Whether this account reaches the administrator governance panel. */
    @Column(name = "is_admin", nullable = false)
    private boolean admin;

    /**
     * Whether the account is blocked (banned) by an administrator. A blocked user
     * cannot authenticate (Spring Security locks the account) and cannot make any
     * authenticated API call (rejected at {@code AuthSupport}). Defaults to false.
     */
    @Column(name = "blocked", nullable = false)
    private boolean blocked;

    /** When the account was blocked (null while active). */
    @Column(name = "blocked_at")
    private LocalDateTime blockedAt;

    /** Admin-supplied reason for the block, surfaced in the User Management table. */
    @Column(name = "blocked_reason", length = 300)
    private String blockedReason;

    /**
     * Forces a password change on next use (audit 4.9). Set when an administrator
     * resets the password to a temporary one; cleared once the user picks a new one.
     */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    /**
     * The governance subject this account belongs to. Modelled as a real
     * {@code @ManyToOne} backed by a database foreign key (audit 3.6) instead of a
     * loose UUID. Loaded lazily; {@link #getIdentityUid()} returns the FK value
     * without initialising the proxy, so existing call sites are unaffected.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "identity_uid", nullable = false,
            foreignKey = @ForeignKey(name = "fk_users_identity"))
    private Identity identity;

    /** Owning tenant (organisation) — enforces hard SaaS isolation (audit 5.3). */
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** FK accessor that does not initialise the lazy {@link #identity} association. */
    public UUID getIdentityUid() {
        return identity != null ? identity.getIdentityUid() : null;
    }

    @PrePersist
    void onCreate() {
        if (userUid == null) {
            userUid = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
