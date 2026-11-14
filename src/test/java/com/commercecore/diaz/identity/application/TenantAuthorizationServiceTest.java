package com.commercecore.diaz.identity.application;

import com.commercecore.diaz.identity.domain.ActorId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TenantAuthorizationServiceTest {
    @Test
    void rejectsMissingActorOrArgumentsWithoutConsultingPersistence() {
        CurrentActor anonymous = new CurrentActor() {
            public Optional<ActorId> id() { return Optional.empty(); }
            public boolean hasPlatformSuperAdminRole() { return false; }
        };
        TenantAuthorization authorization = new TenantAuthorizationService(anonymous,
                (actor, tenant, permission) -> { throw new AssertionError("Lookup must not run"); });
        assertEquals(TenantPermissionDecision.DENY, authorization.hasPermission(UUID.randomUUID(), "TENANT_READ"));
        assertEquals(TenantPermissionDecision.DENY, authorization.hasPermission(null, "TENANT_READ"));
        assertEquals(TenantPermissionDecision.DENY, authorization.hasPermission(UUID.randomUUID(), null));
        assertEquals(TenantPermissionDecision.DENY, authorization.hasPermission(UUID.randomUUID(), "  "));
    }
}
