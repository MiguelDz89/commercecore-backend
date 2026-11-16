package com.commercecore.diaz.identity.application;

import com.commercecore.diaz.audit.application.TenantAuditEvent;
import com.commercecore.diaz.audit.application.TenantAuditRecorder;
import com.commercecore.diaz.identity.application.port.IdentityOnboardingStore;
import com.commercecore.diaz.identity.application.port.TenantAdministrationLock;
import com.commercecore.diaz.identity.domain.ActorId;
import com.commercecore.diaz.identity.domain.TenantRole;
import com.commercecore.diaz.tenant.application.CreatedTenant;
import com.commercecore.diaz.tenant.application.TenantStatusQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class IdentityOnboardingService implements IdentityOnboarding {
    private final CurrentActor currentActor;
    private final PlatformAuthorization platform;
    private final IdentityOnboardingStore store;
    private final TenantAdministrationLock lock;
    private final TenantStatusQuery tenants;
    private final TenantAuditRecorder audit;
    private final TransactionSynchronizationRegistry transactions;

    public IdentityOnboardingService(CurrentActor currentActor, PlatformAuthorization platform, IdentityOnboardingStore store,
            TenantAdministrationLock lock, TenantStatusQuery tenants, TenantAuditRecorder audit, TransactionSynchronizationRegistry transactions) {
        this.currentActor=currentActor; this.platform=platform; this.store=store; this.lock=lock;
        this.tenants=tenants; this.audit=audit; this.transactions=transactions;
    }

    @Override @Transactional(Transactional.TxType.MANDATORY)
    public PreparedOnboardingAccounts prepare(UUID administratorAccountId) {
        ActorId actor=authorizedActor();
        UUID target=Objects.requireNonNull(administratorAccountId,"administratorAccountId");
        Object key=Objects.requireNonNull(transactions.getTransactionKey(),"transaction");
        UUID actorAccount=store.lockAndVerifyAccounts(actor,target);
        var accounts = new PreparedOnboardingAccounts(key,actor,actorAccount,target);
        transactions.putResource(accounts, accounts);
        return accounts;
    }

    @Override @Transactional(Transactional.TxType.MANDATORY)
    public BootstrapEvidence bootstrap(CreatedTenant created, PreparedOnboardingAccounts accounts, Set<CommercialProfile> profiles) {
        Objects.requireNonNull(created,"tenant"); Objects.requireNonNull(accounts,"accounts");
        Set<CommercialProfile> selected=Set.copyOf(Objects.requireNonNull(profiles,"profiles"));
        ActorId actor = authorizedActor();
        accounts.consume(transactions, actor);
        created.consume(transactions, actor.issuer(), actor.subject());
        UUID tenant=created.tenantId();
        lock.acquireAll(List.of(tenant));
        if(tenants.statusOf(tenant)!=TenantStatusQuery.Result.ACTIVE) throw new SecurityException("Operation denied");
        store.verifyLockedAccounts(accounts.actor,accounts.actorAccount,accounts.administratorAccount);
        UUID adminRole=store.createRole(tenant,TenantRole.TENANT_ADMIN,TenantRole.tenantAdminPermissions());
        recordRole(tenant,accounts.actorAccount,adminRole,TenantRole.tenantAdminPermissions());
        UUID membership=store.createMembership(tenant,accounts.administratorAccount);
        audit.record(new TenantAuditEvent(tenant,accounts.actorAccount,TenantAuditEvent.EntityType.MEMBERSHIP,membership,
                TenantAuditEvent.Action.MEMBERSHIP_BOOTSTRAPPED,accounts.administratorAccount,null));
        assign(tenant,accounts.actorAccount,membership,adminRole);
        for(CommercialProfile profile:selected.stream().sorted().toList()) {
            UUID role=store.createRole(tenant,profile.name(),profile.permissions());
            recordRole(tenant,accounts.actorAccount,role,profile.permissions());
            assign(tenant,accounts.actorAccount,membership,role);
        }
        var evidence = new BootstrapEvidence(tenant,accounts.actorAccount,membership,actor);
        transactions.putResource(evidence,evidence);
        return evidence;
    }

    private ActorId authorizedActor() {
        if(!platform.canCreateTenant()) throw new SecurityException("Operation denied");
        return currentActor.id().orElseThrow(() -> new SecurityException("Operation denied"));
    }
    private void recordRole(UUID tenant, UUID actor, UUID role, Set<String> permissions) {
        audit.record(new TenantAuditEvent(tenant,actor,TenantAuditEvent.EntityType.ROLE,role,
                TenantAuditEvent.Action.ROLE_CREATED,null,null,Set.of(),permissions));
    }
    private void assign(UUID tenant, UUID actor, UUID membership, UUID role) {
        store.assignRole(tenant,membership,role);
        audit.record(new TenantAuditEvent(tenant,actor,TenantAuditEvent.EntityType.MEMBERSHIP,membership,
                TenantAuditEvent.Action.ROLE_ASSIGNED,role,null));
    }
}
