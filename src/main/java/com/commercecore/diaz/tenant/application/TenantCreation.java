package com.commercecore.diaz.tenant.application;

public interface TenantCreation {
    CreatedTenant create(String slug, String legalName, String displayName, String currencyCode, String timeZone);
}
