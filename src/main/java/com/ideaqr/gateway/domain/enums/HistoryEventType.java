package com.ideaqr.gateway.domain.enums;

/** Event types written to the append-only History journal. */
public enum HistoryEventType {
    ACCESS_GRANTED,
    ACCESS_DENIED,
    ACCESS_REVIEW,
    QR_CREATED,
    ISSUE_REPORTED,
    IDENTITY_CREATED,
    IDENTITY_VERIFIED,
    USER_REGISTERED,

    // Scenario events (working mode, SOS, guest lifecycle, notifications).
    WORKING_MODE_ACTIVATED,
    WORKING_MODE_DEACTIVATED,
    SOS_CREATED,
    GUEST_CREATED,
    GUEST_MERGED,
    NOTIFICATION_CREATED
}
