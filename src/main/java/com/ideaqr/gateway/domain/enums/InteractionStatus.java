package com.ideaqr.gateway.domain.enums;

/**
 * Lifecycle of an {@link com.ideaqr.gateway.domain.Interaction} per the brief.
 * A public scan is {@code CONFIRMED} immediately; a person-to-person profile
 * access starts {@code PENDING} and becomes {@code CONFIRMED} or {@code REJECTED}
 * once the owner decides.
 */
public enum InteractionStatus {
    PENDING,
    CONFIRMED,
    REJECTED
}
