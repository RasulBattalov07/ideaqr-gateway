package com.ideaqr.gateway.domain.enums;

/** Event types written to the append-only History journal. */
public enum HistoryEventType {
    ACCESS_GRANTED,
    ACCESS_DENIED,
    ACCESS_REVIEW,
    QR_CREATED,
    ISSUE_REPORTED,
    IDENTITY_CREATED,
    USER_REGISTERED
}
