package com.ideaqr.gateway.domain.enums;

/**
 * The domain a registry object belongs to. Determines which policy branch the
 * decision engine applies and which contextual data-card the client renders.
 */
public enum ObjectCategory {
    MEDICAL,
    RETAIL,
    ECO,
    INFRASTRUCTURE,
    GENERAL,
    UNKNOWN
}
