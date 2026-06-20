package com.ideaqr.gateway.domain.enums;

/**
 * The unified event model required by the IDEA QR architecture
 * (Identity → … → Interaction → <b>Event</b> → History). Unlike a {@code History}
 * row (the human-readable journal), an {@link com.ideaqr.gateway.domain.Event} is a
 * machine-oriented fact intended for future audit, notifications, analytics and
 * AI modules. Each event links the actor (identity), the object and the
 * interaction that produced it.
 */
public enum EventType {
    IDENTITY_CREATED,
    IDENTITY_VERIFIED,
    REQUEST_CREATED,
    DECISION_APPROVED,
    DECISION_REJECTED,
    DECISION_REVIEW,
    INTERACTION_CREATED,
    QR_VIEWED,
    PROFILE_OPENED,
    ACCESS_REQUESTED,
    ACCESS_CONFIRMED,
    SOS_CREATED,
    ASSIGNMENT_CREATED,
    WORKING_MODE_ACTIVATED,
    WORKING_MODE_DEACTIVATED,
    COMPLAINT_CREATED,
    GUEST_MERGED,

    // Object lifecycle + service events (EVENT ENGINE requirement). These let any
    // module — goods, services, medicine, infrastructure, transport — record the
    // same machine-readable facts without inventing its own event stream.
    SERVICE_STARTED,
    SERVICE_COMPLETED,
    OBJECT_TRANSFERRED,
    OBJECT_MODIFIED,
    OBJECT_ARCHIVED,

    // Administrator user-management actions.
    USER_BLOCKED,
    USER_UNBLOCKED,
    USER_ROLE_CHANGED,
    USER_PASSWORD_RESET
}
