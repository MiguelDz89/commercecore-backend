package com.commercecore.diaz.identity.application;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PlatformAuthorizationPolicy implements PlatformAuthorization {
    private final CurrentActor actor;

    public PlatformAuthorizationPolicy(CurrentActor actor) {
        this.actor = actor;
    }

    @Override
    public boolean canCreateTenant() {
        return actor.id().isPresent() && actor.hasPlatformSuperAdminRole();
    }
}
