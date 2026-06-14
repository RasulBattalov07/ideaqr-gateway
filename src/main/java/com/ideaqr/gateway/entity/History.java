package com.ideaqr.gateway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Immutable (append-only) deep event journal for auditing.
 * event_payload stores a JSON string snapshot of the relevant state.
 *
 * This entity is written but never updated or deleted by the application layer.
 */
@Entity
@Table(name = "histories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class History {

    @Id
    @Column(name = "history_uid", nullable = false, updatable = false)
    private UUID historyUid;

    @Column(name = "interaction_uid")
    private UUID interactionUid;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "event_payload", columnDefinition = "TEXT")
    private String eventPayload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
