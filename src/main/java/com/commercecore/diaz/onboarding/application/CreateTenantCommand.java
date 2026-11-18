package com.commercecore.diaz.onboarding.application;

import java.util.Set;
import java.util.UUID;

public record CreateTenantCommand(String slug, String legalName, String displayName, String currencyCode,
                                  String timeZone, UUID initialAdministratorAccountId, Set<String> commercialProfiles,
                                  boolean fractionalQuantitiesEnabled) {
    public CreateTenantCommand(String slug, String legalName, String displayName, String currencyCode,
                               String timeZone, UUID initialAdministratorAccountId, Set<String> commercialProfiles) {
        this(slug, legalName, displayName, currencyCode, timeZone, initialAdministratorAccountId, commercialProfiles, false);
    }
    public CreateTenantCommand {
        commercialProfiles = Set.copyOf(commercialProfiles);
        if (currencyCode == null) currencyCode = "PEN";
    }
}
