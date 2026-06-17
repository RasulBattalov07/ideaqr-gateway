package com.ideaqr.gateway.dto;

import lombok.Builder;
import lombok.Data;

/**
 * The response returned for a scan. Carries the verdict, the localized reason,
 * the contextual data payload (when approved), and every UUID produced along the
 * pipeline so the client can display the governance chain.
 */
@Data
@Builder
public class GatewayResponse {

    private boolean success;

    /** APPROVED / REJECTED / REVIEW. */
    private String outcome;

    /** Machine-readable reason code (English). */
    private String reasonCode;

    /** Human-readable reason shown to the user (Russian). */
    private String reason;

    /** Assessed risk level: LOW / MEDIUM / HIGH / CRITICAL. */
    private String riskLevel;

    private String objectUid;

    /** MEDICAL / RETAIL / ECO / INFRASTRUCTURE / UNKNOWN. */
    private String category;

    /** Contextual card payload; null unless the scan was approved. */
    private Object data;

    // --- The governance chain (Stage 2 pipeline) --------------------------
    private String identityUid;
    private String requestUid;
    private String decisionUid;
    private String interactionUid;
    private String historyUid;

    private String timestamp;
}
