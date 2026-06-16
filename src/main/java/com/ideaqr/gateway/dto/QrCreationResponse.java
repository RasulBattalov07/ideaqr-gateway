package com.ideaqr.gateway.dto;

import lombok.Builder;
import lombok.Data;

/** Result of minting a governed QR through the admin panel. */
@Data
@Builder
public class QrCreationResponse {

    private boolean success;
    private String outcome;
    private String reason;

    private String objectUid;
    private String displayName;
    private String category;
    private String qrUid;

    /** A real, scannable QR PNG as a data URI (generated server-side). */
    private String qrImageDataUri;

    // --- Governance chain --------------------------------------------------
    private String identityUid;
    private String requestUid;
    private String decisionUid;
    private String interactionUid;
    private String historyUid;

    private String createdAt;
}
