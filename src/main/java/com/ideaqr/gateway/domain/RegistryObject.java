package com.ideaqr.gateway.domain;

import com.ideaqr.gateway.domain.enums.ObjectCategory;
import com.ideaqr.gateway.domain.enums.ObjectStatus;
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
 * A governed registry object minted through the admin panel. Persisted so that
 * objects created by an administrator survive restarts and can be resolved by
 * the citizen terminal when its QR is scanned.
 *
 * <p>The contextual card payload is stored as a JSON document in
 * {@code data_json}; the gateway returns it verbatim to the client on an
 * approved scan.</p>
 */
@Entity
@Table(name = "registry_objects",
        uniqueConstraints = @UniqueConstraint(name = "uk_registry_object_uid", columnNames = "object_uid"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(TenantListener.class)
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class RegistryObject implements TenantScoped {

    @Id
    @Column(name = "registry_uid", nullable = false, updatable = false)
    private UUID registryUid;

    /** Owning tenant (organisation) — enforces hard SaaS isolation (audit 5.3). */
    @Column(name = "tenant_id")
    private UUID tenantId;

    /** Public object identifier encoded into the QR (e.g. RETAIL_3F9A1C2D). */
    @Column(name = "object_uid", nullable = false, length = 120)
    private String objectUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private ObjectCategory category;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    /**
     * Card payload as JSON (rendered by the client according to category). Stored as
     * a portable sized text column rather than a {@code @Lob}: a LOB maps to {@code CLOB}
     * on H2 but {@code OID} on PostgreSQL, which would force two divergent Flyway
     * baselines. {@code varchar(16000)} validates identically on both and is far larger
     * than any object card.
     */
    @Column(name = "data_json", nullable = false, length = 16000)
    private String dataJson;

    /** Identity that minted and governs this object. */
    @Column(name = "created_by_identity_uid", nullable = false)
    private UUID createdByIdentityUid;

    /** The governed QR backing this object. */
    @Column(name = "qr_uid")
    private UUID qrUid;

    /**
     * Lifecycle status (OBJECT LIFECYCLE requirement). Defaults to {@code ACTIVE}
     * on creation; transitions are driven through the governance pipeline and
     * recorded in History so the object's change-history is never lost.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ObjectStatus status;

    /**
     * Trust Score (0–100) of the object itself — the brief states the score
     * "относится к Identity или Object". Recomputed as the object accumulates
     * interactions, confirmations and complaints.
     */
    @Column(name = "trust_score")
    private Integer trustScore;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Last lifecycle/data change — part of the object's digital continuity. */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (registryUid == null) {
            registryUid = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = ObjectStatus.ACTIVE;
        }
        if (trustScore == null) {
            trustScore = 50;
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }
}
