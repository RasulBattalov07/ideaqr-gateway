package com.ideaqr.gateway.domain.enums;

/**
 * Lifecycle of a granted permission — shared by {@code Consent} and {@code Delegation}
 * (Document 22). A grant is {@code ACTIVE} until it is explicitly {@code REVOKED} or its
 * validity window lapses ({@code EXPIRED}). Status transitions are a future phase; the
 * MVP only persists the state.
 */
public enum GrantStatus {

    ACTIVE,
    REVOKED,
    EXPIRED
}
