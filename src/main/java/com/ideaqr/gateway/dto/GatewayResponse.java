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

    /**
     * Data sensitivity of the object (Document 22 — Data Classification):
     * PUBLIC | RESTRICTED | CONFIDENTIAL | SECRET.
     */
    private String dataClassification;

    /** The governing {@code Policy} code that drove the decision (Document 22 — Policy catalog). */
    private String policy;

    // --- Golden pipeline chain ---
    //  Identifier → Identity/Object → Role → Organization → Request → Decision →
    //  Interaction → Event → History → Audit
    private String identityUid;

    /** Explicit Organization element of the pipeline — the org the request is governed under. */
    private String organizationUid;
    private String organizationName;

    private String requestUid;
    private String decisionUid;
    private String interactionUid;
    private String historyUid;

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

    /**
     * Phase 2 — контекстный QR. Какое представление одного и того же личного QR получил
     * сканирующий, исходя из его роли и режима: {@code MEDICAL} (врач → медкарта через
     * согласие пациента), {@code PRESCRIPTIONS} (фармацевт → только рецепты),
     * {@code LEGAL} (полиция → правовое досье), {@code BUSINESS_CARD} (гражданин → визитка).
     * {@code null} для обычных объектных сканов.
     */
    private String contextView;

    /** ФИО владельца отсканированного личного QR (заголовок контекстного представления). */
    private String subjectName;
}
