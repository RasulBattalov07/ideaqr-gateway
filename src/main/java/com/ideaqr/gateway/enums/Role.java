package com.ideaqr.gateway.enums;

/**
 * Roles that may be assigned to an Identity. Roles are no longer baked into the QR;
 * a single Identity may hold several roles simultaneously (e.g. ENGINEER + CITIZEN).
 *
 * The {@code objectPrefix} maps a role to the object sub-network it governs
 * (e.g. ENGINEER governs INFRA_ objects).
 */
public enum Role {
    DOCTOR("MED_"),
    ENGINEER("INFRA_"),
    FINANCIER("FIN_"),
    CITIZEN("CIV_"),
    ADMIN("ADM_");

    private final String objectPrefix;

    Role(String objectPrefix) {
        this.objectPrefix = objectPrefix;
    }

    public String getObjectPrefix() {
        return objectPrefix;
    }
}
