package com.commercecore.diaz.identity.application;

import com.commercecore.diaz.identity.application.port.LocalAccountStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.UUID;

@ApplicationScoped
public class AccountProvisioningService implements AccountProvisioning {
    private final CurrentActor currentActor;
    private final LocalAccountStore accounts;

    public AccountProvisioningService(CurrentActor currentActor, LocalAccountStore accounts) {
        this.currentActor = currentActor;
        this.accounts = accounts;
    }

    @Override
    @Transactional
    public UUID provisionCurrentActor() {
        var actor = currentActor.id().orElseThrow(() -> new SecurityException("Authenticated actor required"));
        if (actor.issuer().length() > 300 || actor.subject().length() > 255) {
            throw new IllegalArgumentException("OIDC identity exceeds account field limits");
        }
        return accounts.findOrCreate(actor);
    }
}
