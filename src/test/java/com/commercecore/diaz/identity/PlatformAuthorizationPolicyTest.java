package com.commercecore.diaz.identity;

import com.commercecore.diaz.identity.application.CurrentActor;
import com.commercecore.diaz.identity.application.PlatformAuthorizationPolicy;
import com.commercecore.diaz.identity.domain.ActorId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlatformAuthorizationPolicyTest {
    @Test
    void requiresVerifiedActorAndGlobalRole() {
        assertTrue(policy(Optional.of(new ActorId("issuer", "subject")), true).canCreateTenant());
        assertFalse(policy(Optional.of(new ActorId("issuer", "subject")), false).canCreateTenant());
        assertFalse(policy(Optional.empty(), true).canCreateTenant());
    }

    private static PlatformAuthorizationPolicy policy(Optional<ActorId> id, boolean role) {
        return new PlatformAuthorizationPolicy(new CurrentActor() {
            public Optional<ActorId> id() { return id; }
            public boolean hasPlatformSuperAdminRole() { return role; }
        });
    }
}
