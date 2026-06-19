package com.ideaqr.gateway.domain;

import com.ideaqr.gateway.domain.enums.NotificationStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A user-facing notification raised when something noteworthy happens (a new
 * request, a decision, an SOS, a working-mode change, a new interaction).
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @Column(name = "notification_uid", nullable = false, updatable = false)
    private UUID notificationUid;

    @Column(name = "identity_uid", nullable = false)
    private UUID identityUid;

    /** Notification type, e.g. DECISION, SOS, WORKING_MODE, REPORT. */
    @Column(name = "notification_type", nullable = false, length = 40)
    private String notificationType;

    @Column(name = "title", nullable = false, length = 240)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private NotificationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (notificationUid == null) {
            notificationUid = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = NotificationStatus.NEW;
        }
    }
}
