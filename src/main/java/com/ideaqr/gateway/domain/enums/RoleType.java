package com.ideaqr.gateway.domain.enums;

/**
 * Domain roles attached to an {@code Identity}. One identity may hold several
 * roles simultaneously (e.g. {@code DOCTOR} + {@code CITIZEN}).
 *
 * <p>These are <b>business</b> roles used by the policy / context engine
 * ({@code ValidationService}) to decide whether an action is permitted. They are
 * distinct from Spring Security URL authorities (ROLE_ADMIN / ROLE_USER), which
 * only gate which interface a user can reach.</p>
 */
public enum RoleType {
    CITIZEN,
    DOCTOR,
    ENGINEER,
    INSPECTOR,
    RETAIL_ADMIN,
    OBJECT_OWNER,
    ORG_STAFF,
    ADMIN
}
