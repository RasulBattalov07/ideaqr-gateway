package com.ideaqr.gateway.domain;

import com.ideaqr.gateway.domain.enums.QrStatus;
import com.ideaqr.gateway.domain.enums.QrType;
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
 * A QR code under governance. A QR is purely an identifier — it carries no roles
 * and no access rights. Two kinds exist: the permanent {@code PRIMARY} identity
 * QR (one per verified person) and {@code OBJECT} QRs minted by administrators.
 */
@Entity
@Table(name = "qrs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(TenantListener.class)
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class Qr implements TenantScoped {

    @Id
    @Column(name = "qr_uid", nullable = false, updatable = false)
    private UUID qrUid;

    /** Owning tenant (organisation) — enforces hard SaaS isolation (audit 5.3). */
    @Column(name = "tenant_id")
    private UUID tenantId;

    /** Encoded payload — the object UID, or IDENTITY:&lt;uuid&gt; for primary QRs. */
    @Column(name = "qr_value", nullable = false, length = 200)
    private String qrValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "qr_type", nullable = false, length = 20)
    private QrType qrType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private QrStatus status;

    /** Identity that owns / governs this QR — real {@code @ManyToOne} + FK (audit 3.6). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_identity_uid", nullable = false,
            foreignKey = @ForeignKey(name = "fk_qrs_owner_identity"))
    private Identity ownerIdentity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** FK accessor that does not initialise the lazy {@link #ownerIdentity} association. */
    public UUID getOwnerIdentityUid() {
        return ownerIdentity != null ? ownerIdentity.getIdentityUid() : null;
    }

    @PrePersist
    void onCreate() {
        if (qrUid == null) {
            qrUid = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
