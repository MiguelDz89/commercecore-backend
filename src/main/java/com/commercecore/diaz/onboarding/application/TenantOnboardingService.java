package com.commercecore.diaz.onboarding.application;

import com.commercecore.diaz.audit.application.OnboardingAuditService;

import com.commercecore.diaz.identity.application.CommercialProfile;
import com.commercecore.diaz.identity.application.IdentityOnboarding;
import com.commercecore.diaz.identity.application.PlatformAuthorization;
import com.commercecore.diaz.tenant.application.TenantCreation;
import jakarta.enterprise.context.ApplicationScoped;
import io.quarkus.narayana.jta.QuarkusTransaction;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class TenantOnboardingService implements TenantOnboarding {
    private final PlatformAuthorization authorization;
    private final IdentityOnboarding identity;
    private final TenantCreation tenants;
    private final OnboardingAuditService audit;
    private final OnboardingIntegrityGuard guard;

    public TenantOnboardingService(PlatformAuthorization authorization, IdentityOnboarding identity,
            TenantCreation tenants, OnboardingAuditService audit, OnboardingIntegrityGuard guard) {
        this.authorization=authorization; this.identity=identity; this.tenants=tenants; this.audit=audit; this.guard=guard;
    }

    @Override
    public TenantOnboardingResult create(CreateTenantCommand command) {
        return QuarkusTransaction.disallowingExisting().call(() -> createInTransaction(command));
    }

    private TenantOnboardingResult createInTransaction(CreateTenantCommand command) {
        Objects.requireNonNull(command,"command");
        if(!authorization.canCreateTenant()) throw new SecurityException("Operation denied");
        Set<CommercialProfile> profiles=command.commercialProfiles().stream()
                .map(CommercialProfile::valueOf).collect(Collectors.toUnmodifiableSet());
        var accounts=identity.prepare(command.initialAdministratorAccountId());
        var tenant=tenants.create(command.slug(),command.legalName(),command.displayName(),command.currencyCode(),command.timeZone(),
                command.fractionalQuantitiesEnabled());
        var membership=identity.bootstrap(tenant,accounts,profiles);
        var auditEvidence=audit.recordCreation(tenant.tenantId(),accounts.actorAccountId());
        guard.complete(membership,auditEvidence);
        return new TenantOnboardingResult(tenant.tenantId(),command.slug(),membership.membershipId());
    }
}
