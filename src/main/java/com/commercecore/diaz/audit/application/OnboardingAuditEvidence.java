package com.commercecore.diaz.audit.application;

import java.util.UUID;

/** Issued only after a tenant creation audit record has been written. */
public final class OnboardingAuditEvidence {
    private final UUID tenantId, actorAccountId;
    OnboardingAuditEvidence(UUID tenantId, UUID actorAccountId) { this.tenantId=tenantId; this.actorAccountId=actorAccountId; }
    public UUID tenantId() { return tenantId; }
    public UUID actorAccountId() { return actorAccountId; }
}
