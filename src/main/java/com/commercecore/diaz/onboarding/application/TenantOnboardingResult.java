package com.commercecore.diaz.onboarding.application;

import java.util.UUID;

public record TenantOnboardingResult(UUID tenantId, String slug, UUID initialMembershipId) {}
