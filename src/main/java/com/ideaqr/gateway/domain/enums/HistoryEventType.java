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
    NOTIFICATION_CREATED,

    // Person-to-person profile access + complaints.
    PROFILE_ACCESS_REQUESTED,
    PROFILE_ACCESS_CONFIRMED,
    PROFILE_ACCESS_REJECTED,
    COMPLAINT_CREATED,

    // Object lifecycle transitions (OBJECT LIFECYCLE requirement) — appended so the
    // object's full change-history is preserved and never deleted.
    OBJECT_MODIFIED,
    OBJECT_ARCHIVED,
    OBJECT_TRANSFERRED,

    // Administrator user-management actions (append-only governance trail).
    USER_BLOCKED,
    USER_UNBLOCKED,
    USER_ROLE_CHANGED,
    USER_PASSWORD_RESET
}
