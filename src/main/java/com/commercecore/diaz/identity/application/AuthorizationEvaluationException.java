package com.commercecore.diaz.identity.application;

/** Authorization could not be evaluated; callers must not treat this as ALLOW. */
public class AuthorizationEvaluationException extends RuntimeException {
    public AuthorizationEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }
}
