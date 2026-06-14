package com.ideaqr.gateway.entity;

import com.ideaqr.gateway.enums.RequestStatus;
import com.ideaqr.gateway.enums.RequestType;
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
 * Every action in the system begins with the generation of a Request.
 * Named RequestRecord (not "Request") to avoid clashing with Spring's HttpRequest semantics,
 * but mapped to the "requests" table.
 *
 * identityUid is stored as a plain FK column rather than a JPA association to keep the
 * audit chain decoupled and to allow guest-migration re-pointing without cascade surprises.
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

    @Column(name = "identity_uid", nullable = false)
    private UUID identityUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 48)
    private RequestType requestType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RequestStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
