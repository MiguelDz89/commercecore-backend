package com.commercecore.diaz.onboarding.application;

public interface TenantOnboarding {
    TenantOnboardingResult create(CreateTenantCommand command);
}
