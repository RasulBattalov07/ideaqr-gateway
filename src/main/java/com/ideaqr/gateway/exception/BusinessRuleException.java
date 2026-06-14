package com.ideaqr.gateway.exception;

/**
 * Thrown when an operation violates a governance rule
 * (e.g. attempting QR creation without an APPROVED decision).
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
