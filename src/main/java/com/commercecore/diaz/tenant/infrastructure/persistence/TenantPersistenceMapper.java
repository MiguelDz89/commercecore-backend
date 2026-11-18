package com.commercecore.diaz.tenant.infrastructure.persistence;

import com.commercecore.diaz.tenant.domain.Tenant;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TenantPersistenceMapper {
    TenantJpaEntity toEntity(Tenant tenant) {
        TenantJpaEntity entity = new TenantJpaEntity();
        entity.tenantId = tenant.tenantId();
        entity.slug = tenant.slug();
        entity.legalName = tenant.legalName();
        entity.displayName = tenant.displayName();
        entity.currencyCode = tenant.currencyCode();
        entity.timeZone = tenant.timeZone();
        entity.status = tenant.status();
        entity.fractionalQuantitiesEnabled = tenant.fractionalQuantitiesEnabled();
        entity.createdAt = tenant.createdAt();
        entity.updatedAt = tenant.updatedAt();
        return entity;
    }

    Tenant toDomain(TenantJpaEntity entity) {
        return new Tenant(entity.tenantId, entity.slug, entity.legalName, entity.displayName,
                entity.currencyCode, entity.timeZone, entity.status, entity.createdAt, entity.updatedAt, entity.fractionalQuantitiesEnabled);
    }
}
