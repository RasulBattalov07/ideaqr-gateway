package com.ideaqr.gateway.domain;

import com.ideaqr.gateway.domain.enums.EventSource;
import com.ideaqr.gateway.domain.enums.EventType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The standalone <b>Event</b> of the IDEA QR event model
 * (Identity → Role → Request → Decision → Interaction → <b>Event</b> → History →
 * Trust Score). Where {@link History} is the human-readable, append-only journal,
 * an Event is a normalised machine fact that future modules (audit, notifications,
 * analytics, AI) can consume.
 *
 * <p>Each event records the classic "who / what / when" dimensions: the actor
 * ({@code identityUid}), the object ({@code objectUid}), the originating
 * {@code interactionUid} and the timestamp — kept in a shape ready for later
 * analytics (the "AI-ready layer" of the brief).</p>
 */
@Entity
@Table(name = "events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Event {

    @Id
    @Column(name = "event_uid", nullable = false, updatable = false)
    private UUID eventUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private EventType eventType;

    /** Actor — the identity that triggered the event ("who"). */
    @Column(name = "identity_uid")
    private UUID identityUid;

    /** Object the event concerns ("what"), if any. */
    @Column(name = "object_uid", length = 120)
    private String objectUid;

    /** Originating interaction, if the event was produced by one. */
    @Column(name = "interaction_uid")
    private UUID interactionUid;

    /** Short human-readable summary ("why"), kept for the analytics layer. */
    @Column(name = "summary", length = 300)
    private String summary;

    /** Origin channel of the event (Document 22 — Event Source). Defaults to SYSTEM. */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 20)
    private EventSource source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (eventUid == null) {
            eventUid = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (source == null) {
            source = EventSource.SYSTEM;
        }
    }
}
