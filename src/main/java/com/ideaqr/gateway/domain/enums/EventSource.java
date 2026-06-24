package com.ideaqr.gateway.domain.enums;

/**
 * Origin channel of an {@code Event} (Document 22 — Event Source). Recorded on every
 * event so the audit/analytics layers can later attribute activity to a channel
 * (e.g. {@code IDENTIFIER_SCANNED → source = QR_SCAN}). Foundation only — no analytics
 * engine in the MVP.
 */
public enum EventSource {

    WEB,
    MOBILE_APP,
    QR_SCAN,
    API,
    ADMIN_PANEL,
    SYSTEM
}
