package com.commercecore.diaz.tenant.application;

import com.commercecore.diaz.tenant.application.port.TenantCreationAuthorization;
import com.commercecore.diaz.tenant.application.port.TenantRepository;
import com.commercecore.diaz.tenant.application.port.TenantCreationGuard;
import com.commercecore.diaz.tenant.domain.Tenant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.postgresql.util.PSQLException;

@ApplicationScoped
public class TenantCreationService implements TenantCreation {
    private final TenantCreationAuthorization authorization;
    private final TenantRepository repository;
    private final TenantCreationGuard guard;
    private final TransactionSynchronizationRegistry transactions;

    public TenantCreationService(TenantCreationAuthorization authorization, TenantRepository repository, TransactionSynchronizationRegistry transactions, TenantCreationGuard guard) {
        this.authorization = authorization; this.repository = repository; this.transactions = transactions; this.guard = guard;
    }

    @Override @Transactional(Transactional.TxType.MANDATORY)
    public CreatedTenant create(String slug, String legalName, String displayName, String currencyCode, String timeZone) {
        return create(slug, legalName, displayName, currencyCode, timeZone, false);
    }

    @Override @Transactional(Transactional.TxType.MANDATORY)
    public CreatedTenant create(String slug, String legalName, String displayName, String currencyCode, String timeZone,
                                boolean fractionalQuantitiesEnabled) {
        if (!authorization.canCreate()) throw new SecurityException("Operation denied");
        var actor = authorization.actor().orElseThrow(() -> new SecurityException("Operation denied"));
        Tenant tenant = Tenant.create(slug, legalName, displayName, currencyCode, timeZone, fractionalQuantitiesEnabled);
        guard.begin(tenant.tenantId());
        try { repository.create(tenant); }
        catch (RuntimeException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof PSQLException sql && "23505".equals(sql.getSQLState())
                        && sql.getServerErrorMessage() != null
                        && "tenant_slug_key".equals(sql.getServerErrorMessage().getConstraint()))
                    throw new DuplicateTenantSlugException(e);
            }
            throw e;
        }
        var created = new CreatedTenant(tenant.tenantId(), transactions.getTransactionKey(), actor);
        transactions.putResource(created, created);
        return created;
    }
}
