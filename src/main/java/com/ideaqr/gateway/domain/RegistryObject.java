package com.ideaqr.gateway.domain;

import com.ideaqr.gateway.domain.enums.ObjectCategory;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
public class RegistryObject {

    @Id
    @Column(name = "registry_uid", nullable = false, updatable = false)
    private UUID registryUid;

    /** Public object identifier encoded into the QR (e.g. RETAIL_3F9A1C2D). */
    @Column(name = "object_uid", nullable = false, length = 120)
    private String objectUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private ObjectCategory category;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    /** Card payload as JSON (rendered by the client according to category). */
    @Lob
    @Column(name = "data_json", nullable = false)
    private String dataJson;

    /** Identity that minted and governs this object. */
    @Column(name = "created_by_identity_uid", nullable = false)
    private UUID createdByIdentityUid;

    /** The governed QR backing this object. */
    @Column(name = "qr_uid")
    private UUID qrUid;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (registryUid == null) {
            registryUid = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
