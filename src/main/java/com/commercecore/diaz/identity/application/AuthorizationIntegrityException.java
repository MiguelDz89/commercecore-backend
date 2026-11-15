package com.commercecore.diaz.identity.application;

/** The actor's active tenant roles are inconsistent; no authorization can be granted. */
public class AuthorizationIntegrityException extends RuntimeException {
    public AuthorizationIntegrityException(String message) { super(message); }
}
