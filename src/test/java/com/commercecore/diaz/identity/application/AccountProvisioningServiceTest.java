package com.commercecore.diaz.identity.application;

import com.commercecore.diaz.identity.domain.ActorId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AccountProvisioningServiceTest {
    @Test
    void refusesAnonymousActorWithoutCallingStore() {
        CurrentActor anonymous = new CurrentActor() {
            public Optional<ActorId> id() { return Optional.empty(); }
            public boolean hasPlatformSuperAdminRole() { return false; }
        };
        AccountProvisioning service = new AccountProvisioningService(anonymous,
                actor -> { throw new AssertionError("store must not be called"); });
        assertThrows(SecurityException.class, service::provisionCurrentActor);
    }
}
