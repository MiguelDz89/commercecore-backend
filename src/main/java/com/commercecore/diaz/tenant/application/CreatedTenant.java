package com.commercecore.diaz.tenant.application;

import com.commercecore.diaz.tenant.application.port.TenantCreationAuthorization.Actor;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.util.UUID;

/** Transaction-bound, one-use proof of an authenticated tenant creation. */
public final class CreatedTenant {
    private final UUID id;
    private final Object transactionKey;
    private final Actor actor;
    private boolean consumed;

    CreatedTenant(UUID id, Object transactionKey, Actor actor) {
        this.id = id; this.transactionKey = transactionKey; this.actor = actor;
    }
    public UUID tenantId() { return id; }

    public synchronized void consume(TransactionSynchronizationRegistry transactions, String issuer, String subject) {
        Object key = transactions.getTransactionKey();
        if (key == null || !transactionKey.equals(key) || transactions.getResource(this) != this
                || consumed || !actor.issuer().equals(issuer) || !actor.subject().equals(subject)) {
            throw new SecurityException("Operation denied");
        }
        consumed = true;
    }
}
