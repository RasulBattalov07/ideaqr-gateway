package com.ideaqr.gateway.enums;

/**
 * Type of a digital identity in the system.
 * PRIMARY - a fully registered, permanent subject.
 * GUEST   - an ephemeral identity created on the fly for an unregistered scan.
 */
public enum IdentityType {
    PRIMARY,
    GUEST
}
