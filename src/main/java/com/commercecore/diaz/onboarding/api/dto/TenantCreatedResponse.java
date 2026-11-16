package com.commercecore.diaz.onboarding.api.dto;
import java.util.UUID;
public record TenantCreatedResponse(UUID tenantId, String slug, UUID initialMembershipId) { }
