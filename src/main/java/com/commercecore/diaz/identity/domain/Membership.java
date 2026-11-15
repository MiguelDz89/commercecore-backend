package com.commercecore.diaz.identity.domain;

import java.time.Instant;
import java.util.UUID;

public record Membership(UUID id, UUID tenantId, UUID userId, Status status, Instant createdAt) {
    public enum Status { INVITED, ACTIVE, SUSPENDED }
}
