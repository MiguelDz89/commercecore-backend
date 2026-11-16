package com.commercecore.diaz.identity.application;

import com.commercecore.diaz.tenant.application.CreatedTenant;
import java.util.Set;
import java.util.UUID;

public interface IdentityOnboarding {
    PreparedOnboardingAccounts prepare(UUID administratorAccountId);
    BootstrapEvidence bootstrap(CreatedTenant tenant, PreparedOnboardingAccounts accounts, Set<CommercialProfile> profiles);
}
