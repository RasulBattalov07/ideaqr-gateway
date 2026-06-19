package com.ideaqr.gateway.domain;

import com.ideaqr.gateway.domain.enums.SessionMode;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The active session context for an identity (one row per identity). Holds the
 * current mode (personal / working), the active organization and work role, and
 * timing. Provides the foundation for future working / crisis / offline modes.
 */
@Entity
@Table(name = "user_sessions",
        uniqueConstraints = @UniqueConstraint(name = "uk_session_identity", columnNames = "identity_uid"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSession {

    @Id
    @Column(name = "session_uid", nullable = false, updatable = false)
    private UUID sessionUid;

    @Column(name = "identity_uid", nullable = false)
    private UUID identityUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 20)
    private SessionMode mode;

    @Column(name = "active_organization_uid")
    private UUID activeOrganizationUid;

    @Column(name = "active_role", length = 40)
    private String activeRole;

    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (sessionUid == null) {
            sessionUid = UUID.randomUUID();
        }
        if (startedAt == null) {
            startedAt = LocalDateTime.now();
        }
        if (status == null) {
            status = "ACTIVE";
        }
        if (mode == null) {
            mode = SessionMode.PERSONAL;
        }
    }
}
