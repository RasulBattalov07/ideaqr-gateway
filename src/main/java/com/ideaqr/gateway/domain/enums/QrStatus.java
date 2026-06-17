package com.ideaqr.gateway.domain.enums;

/** Governance status of a QR code. */
public enum QrStatus {
    /** Created, awaiting governance approval. */
    PENDING,
    /** Approved and scannable. */
    ACTIVE,
    /** Revoked by governance; no longer resolves. */
    REVOKED
}
