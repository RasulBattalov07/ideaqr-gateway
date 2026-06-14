package com.ideaqr.gateway.exception;

/**
 * Thrown when a referenced entity (Identity, Request, etc.) cannot be found.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
