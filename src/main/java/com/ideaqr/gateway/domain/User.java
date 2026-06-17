package com.ideaqr.gateway.domain;

import com.ideaqr.gateway.domain.enums.EmploymentStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
public class User {

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

    /** Link to the Stage 2 identity layer (foreign key stored as a field). */
    @Column(name = "identity_uid", nullable = false)
    private UUID identityUid;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

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
