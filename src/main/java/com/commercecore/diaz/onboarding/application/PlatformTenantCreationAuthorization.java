package com.commercecore.diaz.onboarding.application;

import com.commercecore.diaz.identity.application.PlatformAuthorization;
import com.commercecore.diaz.identity.application.CurrentActor;
import java.util.Optional;
import com.commercecore.diaz.tenant.application.port.TenantCreationAuthorization;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PlatformTenantCreationAuthorization implements TenantCreationAuthorization {
    private final PlatformAuthorization authorization;
    private final CurrentActor currentActor;
    public PlatformTenantCreationAuthorization(PlatformAuthorization authorization, CurrentActor currentActor) { this.authorization = authorization; this.currentActor = currentActor; }
    @Override public Optional<Actor> actor() { return currentActor.id().map(id -> new Actor(id.issuer(), id.subject())); }
    @Override public boolean canCreate() { return authorization.canCreateTenant(); }
}
