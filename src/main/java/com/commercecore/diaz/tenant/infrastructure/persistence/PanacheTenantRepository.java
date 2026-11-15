package com.commercecore.diaz.tenant.infrastructure.persistence;

import com.commercecore.diaz.tenant.application.port.TenantRepository;
import com.commercecore.diaz.tenant.domain.Tenant;
import com.commercecore.diaz.tenant.domain.TenantStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class PanacheTenantRepository implements TenantRepository {
    private final TenantPersistenceMapper mapper;
    private final TenantPanacheStore store;

    public PanacheTenantRepository(TenantPersistenceMapper mapper, TenantPanacheStore store) {
        this.mapper = mapper;
        this.store = store;
    }

    @Override
    @Transactional
    public void create(Tenant tenant) {
        if (tenant.status() != TenantStatus.ACTIVE) {
            throw new IllegalArgumentException("A new tenant must be active");
        }
        store.persistAndFlush(mapper.toEntity(tenant));
    }

    @Override
    public Optional<Tenant> findByTenantId(UUID tenantId) {
        return store.findByIdOptional(tenantId).map(mapper::toDomain);
    }

    @Override
    public Optional<TenantStatus> findCurrentStatus(UUID tenantId) {
        return store.getEntityManager().createQuery(
                "select t.status from TenantJpaEntity t where t.tenantId = :tenantId", TenantStatus.class)
                .setHint("org.hibernate.cacheable", false)
                .setParameter("tenantId", tenantId).getResultStream().findFirst();
    }

    @Override
    public Optional<Tenant> findBySlug(String slug) {
        return store.find("slug", slug).firstResultOptional().map(mapper::toDomain);
    }
}

@ApplicationScoped
class TenantPanacheStore implements PanacheRepositoryBase<TenantJpaEntity, UUID> {
}
