package com.commercecore.diaz.identity.application;

import com.commercecore.diaz.identity.domain.ActorId;
import java.util.UUID;

/** Issued only after the membership, role assignment and bootstrap audits succeed. */
public final class BootstrapEvidence {
    private final UUID tenantId, actorAccountId, membershipId;
    private final ActorId actor;
    BootstrapEvidence(UUID tenantId, UUID actorAccountId, UUID membershipId, ActorId actor) {
        this.tenantId=tenantId; this.actorAccountId=actorAccountId; this.membershipId=membershipId; this.actor=actor;
    }
    public UUID tenantId() { return tenantId; }
    public UUID actorAccountId() { return actorAccountId; }
    public UUID membershipId() { return membershipId; }
    public ActorId actor() { return actor; }
}
