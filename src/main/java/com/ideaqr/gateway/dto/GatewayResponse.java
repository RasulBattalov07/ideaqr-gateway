package com.ideaqr.gateway.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Unified response for the governance terminal ({@code /scan}, {@code /report}).
 * Carries the verdict, the localized reason, the assessed risk level, the data
 * payload (only when access is granted) and the full chain of UUIDs that the SPA
 * animates in its governance-pipeline tracker.
 */
@Data
@Builder
public class GatewayResponse {

    private boolean success;

    /** APPROVED | REJECTED | REVIEW. */
    private String outcome;

    /** Human-readable reason (Russian). */
    private String reason;

    /** LOW | MEDIUM | HIGH | CRITICAL. */
    private String riskLevel;

    /** MEDICAL | RETAIL | ECO | INFRASTRUCTURE | GENERAL | UNKNOWN. */
    private String category;

    private String objectUid;

    /** Category-specific card payload; present only on an approved access. */
    private Object data;

    // --- Governance chain (Identity → Request → Decision → Interaction → History) ---
    private String identityUid;
    private String requestUid;
    private String decisionUid;
    private String interactionUid;
    private String historyUid;

    /**
     * Trust Score (0–100) of the acting identity, recomputed as the final stage of
     * the pipeline (… → History → <b>Trust Score</b>). Lets the demo show the score
     * being recalculated on every governed interaction.
     */
    private Integer trustScore;

    /**
     * Visibility tier of the returned {@link #data}: {@code PUBLIC} for the guest
     * projection (name / image / short description / rating only) or {@code FULL} for a
     * registered identity. Present only on an approved access.
     */
    private String accessTier;

    /** True when the viewer is a guest and must register to unlock the full card. */
    private boolean registrationRequired;

    /** Localized call-to-action shown to guests (Scenario #1 / ГОСТЕВОЙ ДОСТУП). */
    private String cta;
}
