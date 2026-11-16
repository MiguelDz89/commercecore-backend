package com.commercecore.diaz.audit.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;
import java.util.Objects;
import java.util.UUID;

@ApplicationScoped
public class OnboardingAuditService {
    private final TenantAuditRecorder recorder;
    private final TransactionSynchronizationRegistry transactions;
    public OnboardingAuditService(TenantAuditRecorder recorder, TransactionSynchronizationRegistry transactions) {
        this.recorder=recorder; this.transactions=transactions;
    }
    @Transactional(Transactional.TxType.MANDATORY)
    public OnboardingAuditEvidence recordCreation(UUID tenantId, UUID actorAccountId) {
        Objects.requireNonNull(transactions.getTransactionKey());
        recorder.record(new TenantAuditEvent(tenantId,actorAccountId,TenantAuditEvent.EntityType.TENANT,
                tenantId,TenantAuditEvent.Action.TENANT_CREATED,null,null));
        var evidence = new OnboardingAuditEvidence(tenantId,actorAccountId);
        transactions.putResource(evidence,evidence);
        return evidence;
    }
}
