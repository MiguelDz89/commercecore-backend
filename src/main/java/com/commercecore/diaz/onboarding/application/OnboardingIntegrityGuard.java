package com.commercecore.diaz.onboarding.application;

import com.commercecore.diaz.audit.application.OnboardingAuditEvidence;
import com.commercecore.diaz.identity.application.BootstrapEvidence;
import com.commercecore.diaz.identity.application.CurrentActor;
import com.commercecore.diaz.identity.application.PlatformAuthorization;
import com.commercecore.diaz.identity.domain.ActorId;
import com.commercecore.diaz.tenant.application.port.TenantCreationGuard;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.UUID;

/** Keeps incomplete creations from committing; no database work runs in synchronization callbacks. */
@ApplicationScoped
public class OnboardingIntegrityGuard implements TenantCreationGuard {
    private final TransactionSynchronizationRegistry transactions;
    private final CurrentActor currentActor;
    private final PlatformAuthorization platform;
    public OnboardingIntegrityGuard(TransactionSynchronizationRegistry transactions, CurrentActor currentActor, PlatformAuthorization platform) {
        this.transactions=transactions; this.currentActor=currentActor; this.platform=platform;
    }
    private static final class State {
        final Map<UUID,ActorId> pending = new ConcurrentHashMap<>();
    }
    private State state() { return (State) transactions.getResource(OnboardingIntegrityGuard.class); }
    private ActorId actor() {
        if (!platform.canCreateTenant()) throw new SecurityException("Operation denied");
        return currentActor.id().orElseThrow(() -> new SecurityException("Operation denied"));
    }
    @Override public void begin(UUID tenantId) {
        if (transactions.getTransactionKey()==null) throw new IllegalStateException("Transaction required");
        ActorId actor=actor();
        State state=state();
        if (state==null) {
            state=new State();
            transactions.putResource(OnboardingIntegrityGuard.class,state);
            State registered=state;
            transactions.registerInterposedSynchronization(new Synchronization() {
                @Override public void beforeCompletion() {
                    if (!registered.pending.isEmpty()) transactions.setRollbackOnly();
                }
                @Override public void afterCompletion(int status) { registered.pending.clear(); }
            });
        }
        if (state.pending.putIfAbsent(tenantId,actor)!=null) throw new IllegalStateException("Duplicate onboarding");
    }
    @Override public void requireStarted(UUID tenantId) {
        State state=state();
        if (state==null || !actor().equals(state.pending.get(tenantId))) throw new SecurityException("Operation denied");
    }
    public void complete(BootstrapEvidence bootstrap, OnboardingAuditEvidence audit) {
        State state=state();
        if (state==null || bootstrap==null || audit==null || transactions.getResource(bootstrap)!=bootstrap
                || transactions.getResource(audit)!=audit || !bootstrap.tenantId().equals(audit.tenantId())
                || !bootstrap.actorAccountId().equals(audit.actorAccountId())
                || !bootstrap.actor().equals(actor()) || !bootstrap.actor().equals(state.pending.get(bootstrap.tenantId()))) {
            throw new SecurityException("Operation denied");
        }
        transactions.putResource(bootstrap,Boolean.FALSE);
        transactions.putResource(audit,Boolean.FALSE);
        state.pending.remove(bootstrap.tenantId());
    }
}
