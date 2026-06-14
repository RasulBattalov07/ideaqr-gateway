package com.ideaqr.gateway.entity;

import com.ideaqr.gateway.enums.DecisionResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Dedicated entity that records the verdict produced by the rules engine.
 * The decision logic itself lives in ValidationService (the rules engine),
 * this entity only persists the outcome.
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

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 32)
    private DecisionResult result;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
