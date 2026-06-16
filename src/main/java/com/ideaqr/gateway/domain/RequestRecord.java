package com.ideaqr.gateway.domain;

import com.ideaqr.gateway.domain.enums.RequestStatus;
import com.ideaqr.gateway.domain.enums.RequestType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
public class RequestRecord {

    @Id
    @Column(name = "request_uid", nullable = false, updatable = false)
    private UUID requestUid;

    /** Identity that initiated the request. */
    @Column(name = "identity_uid", nullable = false)
    private UUID identityUid;

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
