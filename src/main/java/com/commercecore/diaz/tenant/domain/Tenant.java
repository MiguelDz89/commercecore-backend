package com.commercecore.diaz.tenant.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record Tenant(UUID tenantId, String slug, String legalName, String displayName,
                     String currencyCode, String timeZone, TenantStatus status,
                     Instant createdAt, Instant updatedAt, boolean fractionalQuantitiesEnabled) {
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    public Tenant {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (slug == null || slug.length() > 80 || !SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException("Invalid tenant slug");
        }
        requireName(legalName, 200, "legalName");
        requireName(displayName, 160, "displayName");
        if (currencyCode == null || !CURRENCY.matcher(currencyCode).matches()) {
            throw new IllegalArgumentException("Invalid currency code");
        }
        try {
            Currency.getInstance(currencyCode);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid currency code", e);
        }
        if (timeZone == null || timeZone.length() > 64 || !ZoneId.getAvailableZoneIds().contains(timeZone)) {
            throw new IllegalArgumentException("Invalid time zone");
        }
    }

    public Tenant(UUID tenantId, String slug, String legalName, String displayName, String currencyCode,
                  String timeZone, TenantStatus status, Instant createdAt, Instant updatedAt) {
        this(tenantId, slug, legalName, displayName, currencyCode, timeZone, status, createdAt, updatedAt, false);
    }

    public static Tenant create(String slug, String legalName, String displayName, String currencyCode, String timeZone) {
        return create(slug, legalName, displayName, currencyCode, timeZone, false);
    }

    public static Tenant create(String slug, String legalName, String displayName, String currencyCode, String timeZone,
                                boolean fractionalQuantitiesEnabled) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return new Tenant(UUID.randomUUID(), slug, legalName, displayName, currencyCode, timeZone,
                TenantStatus.ACTIVE, now, now, fractionalQuantitiesEnabled);
    }

    public Tenant suspend() {
        return transition(TenantStatus.ACTIVE, TenantStatus.SUSPENDED);
    }

    public Tenant reactivate() {
        return transition(TenantStatus.SUSPENDED, TenantStatus.ACTIVE);
    }

    private Tenant transition(TenantStatus expected, TenantStatus target) {
        if (status != expected) {
            throw new IllegalStateException("Invalid tenant status transition");
        }
        return new Tenant(tenantId, slug, legalName, displayName, currencyCode, timeZone,
                target, createdAt, Instant.now().truncatedTo(ChronoUnit.MICROS), fractionalQuantitiesEnabled);
    }

    private static void requireName(String name, int maxLength, String field) {
        if (name == null || name.isBlank() || name.length() > maxLength) {
            throw new IllegalArgumentException("Invalid " + field);
        }
    }
}
