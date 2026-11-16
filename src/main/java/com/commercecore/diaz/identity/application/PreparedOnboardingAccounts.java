package com.commercecore.diaz.identity.application;

import com.commercecore.diaz.identity.domain.ActorId;
import java.util.UUID;
import jakarta.transaction.TransactionSynchronizationRegistry;

/** Opaque transaction-bound result of identity and account-lock checks. */
public final class PreparedOnboardingAccounts {
    final Object transactionKey;
    final ActorId actor;
    final UUID actorAccount;
    final UUID administratorAccount;
    PreparedOnboardingAccounts(Object key, ActorId actor, UUID actorAccount, UUID administratorAccount) {
        this.transactionKey = key; this.actor = actor; this.actorAccount = actorAccount; this.administratorAccount = administratorAccount;
    }
    private boolean consumed;
    synchronized void consume(TransactionSynchronizationRegistry transactions, ActorId currentActor) {
        Object key = transactions.getTransactionKey();
        if (key == null || !transactionKey.equals(key) || transactions.getResource(this) != this
                || consumed || !actor.equals(currentActor)) throw new SecurityException("Operation denied");
        consumed = true;
    }
    public UUID actorAccountId() { return actorAccount; }
}
