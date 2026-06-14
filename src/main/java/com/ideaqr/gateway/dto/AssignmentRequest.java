package com.ideaqr.gateway.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Payload to bind an identity + QR to a concrete object.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssignmentRequest {

    @NotNull(message = "identityUid must not be null")
    private UUID identityUid;

    @NotNull(message = "qrUid must not be null")
    private UUID qrUid;

    @NotNull(message = "objectUid must not be null")
    private String objectUid;
}
