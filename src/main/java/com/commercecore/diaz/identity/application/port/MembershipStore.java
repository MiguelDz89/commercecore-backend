package com.commercecore.diaz.identity.application.port;

import com.commercecore.diaz.identity.domain.ActorId;
import com.commercecore.diaz.identity.domain.Membership;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MembershipStore {
    Optional<UUID> activeAccount(ActorId actor);
    boolean isActiveAccount(UUID userId);
    Optional<Membership> find(UUID tenantId, UUID membershipId);
    boolean isTenantAdmin(UUID tenantId, UUID membershipId);
    Optional<Membership> invite(UUID tenantId, UUID userId);
    Membership transition(Membership member, Membership.Status target);
    List<Membership> list(UUID tenantId, int offset, int limit);
}
