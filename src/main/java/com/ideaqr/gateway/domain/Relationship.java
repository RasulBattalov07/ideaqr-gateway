package com.ideaqr.gateway.domain;

import com.ideaqr.gateway.domain.enums.PartyType;
import com.ideaqr.gateway.domain.enums.RelationshipType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <b>Relationship</b> (Document 22) — a universal, directed edge between any two
 * participants of the ecosystem (Identity ↔ Identity / Object / Organization). Without
 * it the platform only sees isolated entities. Stored as two {@code (PartyType, uuid)}
 * endpoints so that <b>new object types never require a new relationship table</b>
 * (an explicit Document 22 requirement).
 *
 * <p>Foundation only — the MVP defines the model; relationship-driven logic is future work.</p>
 */
@Entity
@Table(name = "relationships")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Relationship {

    @Id
    @Column(name = "relationship_uid", nullable = false, updatable = false)
    private UUID relationshipUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_type", nullable = false, length = 20)
    private PartyType fromType;

    @Column(name = "from_uid", nullable = false)
    private UUID fromUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_type", nullable = false, length = 20)
    private PartyType toType;

    @Column(name = "to_uid", nullable = false)
    private UUID toUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "relationship_type", nullable = false, length = 40)
    private RelationshipType relationshipType;

    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (relationshipUid == null) {
            relationshipUid = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = "ACTIVE";
        }
    }
}
