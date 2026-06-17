package com.ideaqr.gateway.domain;

import com.ideaqr.gateway.domain.enums.DecisionOutcome;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The verdict produced for a request. Every decision carries a human-readable
 * reason (in Russian) so the user can see why an action was allowed or denied,
 * plus a machine-readable reason code and the risk level assessed.
 */
@Entity
@Table(name = "decisions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Decision {

    @Id
    @Column(name = "decision_uid", nullable = false, updatable = false)
    private UUID decisionUid;

    @Column(name = "request_uid", nullable = false)
    private UUID requestUid;

    @Column(name = "identity_uid", nullable = false)
    private UUID identityUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    private DecisionOutcome outcome;

    /** Machine-readable reason code (English), e.g. ROLE_REQUIRED_DOCTOR. */
    @Column(name = "reason_code", length = 60)
    private String reasonCode;

    /** Human-readable reason shown in the UI (Russian). */
    @Column(name = "reason", length = 500)
    private String reason;

    /** Risk level: LOW / MEDIUM / HIGH / CRITICAL. */
    @Column(name = "risk_level", length = 20)
    private String riskLevel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (decisionUid == null) {
            decisionUid = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
