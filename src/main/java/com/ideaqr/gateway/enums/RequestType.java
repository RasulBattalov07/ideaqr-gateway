package com.ideaqr.gateway.enums;

/**
 * The kind of action a Request represents. Every action in the system starts
 * with the generation of a Request of one of these types.
 */
public enum RequestType {
    QR_CREATION,
    OBJECT_ACCESS,
    MEDICAL_ACCESS,
    INFRASTRUCTURE_ACCESS,
    FINANCE_ACCESS
}
