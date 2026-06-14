package com.ideaqr.gateway.dto;

import com.ideaqr.gateway.enums.QrType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Payload to register a QR code for an identity.
 * Must reference a QR_CREATION request that already carries an APPROVED decision.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QrCreationRequest {

    @NotNull(message = "identityUid must not be null")
    private UUID identityUid;

    @NotNull(message = "requestUid must not be null")
    private UUID requestUid;

    @NotNull(message = "qrType must not be null")
    private QrType qrType;
}
