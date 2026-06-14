package com.ideaqr.gateway.dto;

import com.ideaqr.gateway.enums.RequestType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Inbound payload for a scan / access attempt.
 *
 * identityUid is optional: if null, the gateway treats the scan as anonymous and
 * dynamically provisions a GUEST identity.
 *
 * objectUid is the target object (e.g. "INFRA_WAREHOUSE_7", "MED_WARD_3").
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScanRequest {

    /** Optional. When null, an anonymous GUEST identity is created. */
    private UUID identityUid;

    /** The object the subject is attempting to interact with. */
    @NotNull(message = "objectUid must not be null")
    private String objectUid;

    /** The kind of action being requested. */
    @NotNull(message = "requestType must not be null")
    private RequestType requestType;

    /** Free-form interaction descriptor (e.g. "PHYSICAL_GATE", "API_CALL"). Optional. */
    private String interactionType;
}
