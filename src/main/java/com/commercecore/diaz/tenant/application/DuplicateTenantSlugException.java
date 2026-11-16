package com.commercecore.diaz.tenant.application;

public class DuplicateTenantSlugException extends RuntimeException {
    public DuplicateTenantSlugException(Throwable cause) { super("Tenant slug already exists", cause); }
}
