package com.ideaqr.gateway.dto;

import com.ideaqr.gateway.enums.DecisionResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Outbound response describing the full outcome of a processed scan.
 * Carries the identifiers of every artefact produced along the pipeline.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GatewayResponse {

    private UUID identityUid;
    private UUID requestUid;
    private UUID decisionUid;
    private UUID interactionUid;

    private DecisionResult result;
    private String reason;

    private String objectUid;

    /** Mock registry payload returned only when access is APPROVED; null otherwise. */
    private Object registryData;

    private LocalDateTime processedAt;
}
