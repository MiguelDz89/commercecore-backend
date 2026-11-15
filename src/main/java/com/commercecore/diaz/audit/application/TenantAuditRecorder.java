package com.commercecore.diaz.audit.application;

/** Records an event in the caller's existing transaction. */
public interface TenantAuditRecorder {
    void record(TenantAuditEvent event);
}
