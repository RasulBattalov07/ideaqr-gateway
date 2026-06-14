package com.ideaqr.gateway.entity;

import com.ideaqr.gateway.enums.QrType;
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
 * A QR code registered in the system.
 * Creation is only permitted when an APPROVED Decision exists for a QR_CREATION Request.
 */
@Entity
@Table(name = "qrs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QrCode {

    @Id
    @Column(name = "qr_uid", nullable = false, updatable = false)
    private UUID qrUid;

    @Column(name = "identity_uid", nullable = false)
    private UUID identityUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "qr_type", nullable = false, length = 32)
    private QrType qrType;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
