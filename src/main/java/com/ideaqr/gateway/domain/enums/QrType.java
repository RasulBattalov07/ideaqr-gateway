package com.ideaqr.gateway.domain.enums;

/** Whether a QR identifies a person (primary identity QR) or a governed object. */
public enum QrType {
    /** The permanent, single identity QR created per verified person. */
    PRIMARY,
    /** A QR minted by an administrator to identify a registry object. */
    OBJECT
}
