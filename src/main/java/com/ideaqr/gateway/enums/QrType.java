package com.ideaqr.gateway.enums;

/**
 * Type of a registered QR code.
 * MAIN      - the single primary QR bound to a PRIMARY identity.
 * TEMPORARY - a short-lived QR (e.g. for guest / one-off scenarios).
 */
public enum QrType {
    MAIN,
    TEMPORARY
}
