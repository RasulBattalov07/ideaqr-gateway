package com.ideaqr.gateway.domain.enums;

/**
 * Type of a digital identity.
 *
 * <ul>
 *   <li>{@code PRIMARY} — a verified, permanent identity backed by a user account.</li>
 *   <li>{@code GUEST}   — a temporary identity created on the fly for unauthenticated
 *       traffic; its history can later be merged into a PRIMARY identity.</li>
 * </ul>
 */
public enum IdentityType {
    PRIMARY,
    GUEST
}
