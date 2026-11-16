package com.commercecore.diaz.onboarding.application;

import java.util.Set;
import java.util.UUID;

public record CreateTenantCommand(String slug, String legalName, String displayName, String currencyCode,
                                  String timeZone, UUID initialAdministratorAccountId, Set<String> commercialProfiles) {
    public CreateTenantCommand { commercialProfiles = Set.copyOf(commercialProfiles); }
}
