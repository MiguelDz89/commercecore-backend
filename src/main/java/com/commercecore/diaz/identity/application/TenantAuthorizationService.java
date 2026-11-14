package com.commercecore.diaz.identity.application;

import com.commercecore.diaz.identity.application.port.TenantPermissionLookup;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class TenantAuthorizationService implements TenantAuthorization {
    private final CurrentActor currentActor;
    private final TenantPermissionLookup lookup;

    public TenantAuthorizationService(CurrentActor currentActor, TenantPermissionLookup lookup) {
        this.currentActor = currentActor;
        this.lookup = lookup;
    }

    @Override
    public TenantPermissionDecision hasPermission(UUID tenantId, String permissionCode) {
        if (tenantId == null || permissionCode == null || permissionCode.isBlank()) {
            return TenantPermissionDecision.DENY;
        }
        return currentActor.id()
                .filter(actor -> lookup.hasPermission(actor, tenantId, permissionCode))
                .map(actor -> TenantPermissionDecision.ALLOW)
                .orElse(TenantPermissionDecision.DENY);
    }
}
