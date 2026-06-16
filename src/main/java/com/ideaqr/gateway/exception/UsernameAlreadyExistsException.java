package com.ideaqr.gateway.exception;

/** Thrown when registration is attempted with a username that already exists. */
public class UsernameAlreadyExistsException extends RuntimeException {

    public UsernameAlreadyExistsException(String message) {
        super(message);
    }
}
