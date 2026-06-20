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
    ADMIN,

    // Specialised roles (minimal-access principle): each sees only what its
    // function needs once the data owner has granted access.
    PHARMACIST,       // prescriptions and appointments only — not the full medical card
    SELLER,           // order / delivery data only
    SERVICE_OPERATOR  // client service requests only
}
