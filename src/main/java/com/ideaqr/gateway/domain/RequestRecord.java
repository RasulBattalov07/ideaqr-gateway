package com.ideaqr.gateway.domain;

import com.ideaqr.gateway.domain.enums.RequestStatus;
import com.ideaqr.gateway.domain.enums.RequestType;
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
 * A request entering the pipeline. Named {@code RequestRecord} to avoid clashing
 * with HTTP request semantics; the table is {@code requests}.
 *
 * <p>No action is ever executed directly — every action becomes a request that
 * is then evaluated into a {@link Decision}.</p>
 */
@Entity
@Table(name = "requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(TenantListener.class)
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class RequestRecord implements TenantScoped {

    @Id
    @Column(name = "request_uid", nullable = false, updatable = false)
    private UUID requestUid;

    /** Owning tenant (organisation) — enforces hard SaaS isolation (audit 5.3). */
    @Column(name = "tenant_id")
    private UUID tenantId;

    /** Identity that initiated the request. */
    @Column(name = "identity_uid", nullable = false)
    private UUID identityUid;

    /**
     * Organisation the request is governed under — an EXPLICIT element of the golden
     * pipeline (Identifier → Identity/Object → Role → <b>Organization</b> → Request → …).
     * Resolved from the actor's organisation membership; {@code null} when the actor is a
     * citizen/guest acting personally.
     */
    @Column(name = "organization_uid")
    private UUID organizationUid;

    /** The target object UID (e.g. PATIENT_7291, RETAIL_ADIDAS_SHIRT). */
    @Column(name = "object_uid", length = 120)
    private String objectUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 30)
    private RequestType requestType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RequestStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (requestUid == null) {
            requestUid = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
