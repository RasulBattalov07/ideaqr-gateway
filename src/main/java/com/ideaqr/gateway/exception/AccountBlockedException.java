package com.ideaqr.gateway.exception;

/**
 * Thrown when a blocked (banned) account tries to act on an authenticated
 * endpoint. Mapped to HTTP 403 so an already-logged-in user who is blocked
 * mid-session is rejected on their very next request.
 */
public class AccountBlockedException extends RuntimeException {

    public AccountBlockedException(String message) {
        super(message);
    }
}
