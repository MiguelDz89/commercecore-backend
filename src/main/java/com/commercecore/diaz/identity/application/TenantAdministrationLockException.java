package com.commercecore.diaz.identity.application;

/** The transaction must roll back; retries, if appropriate, must restart the whole operation. */
public class TenantAdministrationLockException extends RuntimeException {
    public enum Reason { TIMEOUT, DEADLOCK, DATABASE_ERROR }
    private final Reason reason;

    public TenantAdministrationLockException(Reason reason, Throwable cause) {
        super("Unable to acquire tenant administration lock: " + reason, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
