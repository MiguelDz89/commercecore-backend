package com.commercecore.diaz.identity.application.port;

import com.commercecore.diaz.identity.application.CommercialProfile;
import com.commercecore.diaz.identity.domain.ActorId;
import java.util.Set;
import java.util.UUID;

/** Identity-owned persistence operations; caller must hold an active transaction. */
public interface IdentityOnboardingStore {
    UUID lockAndVerifyAccounts(ActorId actor, UUID targetAccountId);
    void verifyLockedAccounts(ActorId actor, UUID actorAccountId, UUID targetAccountId);
    UUID createMembership(UUID tenantId, UUID accountId);
    UUID createRole(UUID tenantId, String code, Set<String> permissions);
    void assignRole(UUID tenantId, UUID membershipId, UUID roleId);
}
