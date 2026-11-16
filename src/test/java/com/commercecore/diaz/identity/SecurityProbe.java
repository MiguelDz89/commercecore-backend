package com.commercecore.diaz.identity;

import com.commercecore.diaz.identity.application.PlatformAuthorization;
import com.commercecore.diaz.identity.application.AccountProvisioning;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import java.util.UUID;
import com.commercecore.diaz.identity.application.MembershipAdministration;
import com.commercecore.diaz.identity.application.TenantRoleAdministration;
import com.commercecore.diaz.onboarding.application.TenantOnboarding;
import com.commercecore.diaz.onboarding.application.CreateTenantCommand;
import com.commercecore.diaz.tenant.application.DuplicateTenantSlugException;

@Path("/test-only-platform-authorization")
@Authenticated
public class SecurityProbe {
    @Inject PlatformAuthorization authorization;
    @Inject AccountProvisioning accounts;
    @Inject MembershipAdministration memberships;
    @Inject TenantRoleAdministration roles;
    @Inject TenantOnboarding onboarding;
    @Inject com.commercecore.diaz.tenant.application.TenantCreation tenantCreation;
    @Inject com.commercecore.diaz.tenant.application.port.TenantRepository tenantRepository;
    @Inject com.commercecore.diaz.identity.application.IdentityOnboarding identityOnboarding;
    @Inject com.commercecore.diaz.onboarding.application.OnboardingIntegrityGuard onboardingGuard;
    @Inject com.commercecore.diaz.audit.application.OnboardingAuditService onboardingAudit;

    @GET
    public String check() {
        if (!authorization.canCreateTenant()) {
            throw new WebApplicationException(403);
        }
        return "allowed";
    }

    @GET
    @Path("/account")
    public String provisionAccount() {
        return accounts.provisionCurrentActor().toString();
    }

    @POST
    @Path("/membership/{tenant}/{membership}/accept")
    public String accept(@PathParam("tenant") UUID tenant, @PathParam("membership") UUID membership) {
        return memberships.accept(tenant, membership).status().name();
    }

    @POST
    @Path("/role/{tenant}/{role}/clear")
    public void clearRole(@PathParam("tenant") UUID tenant, @PathParam("role") UUID role) {
        roles.changePermissions(tenant, role, java.util.Set.of());
    }

    @POST
    @Path("/platform/{tenant}/{membership}/{operation}")
    public void platformRole(@PathParam("tenant") UUID tenant, @PathParam("membership") UUID membership,
                             @PathParam("operation") String operation) {
        try {
            switch (operation) {
                case "designate" -> roles.designateAdmin(tenant, membership);
                case "remove" -> roles.removeAdmin(tenant, membership);
                default -> throw new WebApplicationException(404);
            }
        } catch (SecurityException e) {
            throw new WebApplicationException(403);
        } catch (com.commercecore.diaz.identity.application.MembershipConflictException e) {
            throw new WebApplicationException(409);
        }
    }

    @POST
    @Path("/onboard/{slug}/{account}")
    public String onboard(@PathParam("slug") String slug, @PathParam("account") UUID account,
                          @QueryParam("profile") java.util.List<String> profiles) {
        try {
            return onboarding.create(new CreateTenantCommand(slug,"Empresa", "Empresa", "PEN", "UTC",account,
                    new java.util.HashSet<>(profiles))).tenantId().toString();
        } catch (SecurityException e) { throw new WebApplicationException(403); }
        catch (DuplicateTenantSlugException e) { throw new WebApplicationException(409); }
        catch (IllegalArgumentException e) { throw new WebApplicationException(400); }
    }

    @POST
    @Path("/integrity/{scenario}/{slug}/{account}")
    public void integrity(@PathParam("scenario") String scenario, @PathParam("slug") String slug,
                          @PathParam("account") UUID account) {
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            if (scenario.equals("repository")) {
                tenantRepository.create(com.commercecore.diaz.tenant.domain.Tenant.create(slug,"Empresa","Empresa","PEN","UTC"));
                return;
            }
            if (scenario.equals("direct")) {
                tenantCreation.create(slug,"Empresa","Empresa","PEN","UTC");
                return;
            }
            var prepared=identityOnboarding.prepare(account);
            var tenant=tenantCreation.create(slug,"Empresa","Empresa","PEN","UTC");
            if (scenario.equals("premature")) {
                onboardingGuard.complete(null,null);
                return;
            }
            var evidence=identityOnboarding.bootstrap(tenant,prepared,java.util.Set.of());
            if (scenario.equals("noaudit")) return;
            if (scenario.equals("other-tenant")) {
                var other=tenantCreation.create(slug+"-other","Empresa","Empresa","PEN","UTC");
                var wrongAudit=onboardingAudit.recordCreation(other.tenantId(),prepared.actorAccountId());
                onboardingGuard.complete(evidence,wrongAudit);
                return;
            }
            if (scenario.equals("double")) {
                identityOnboarding.bootstrap(tenant,prepared,java.util.Set.of());
                return;
            }
            var auditEvidence=onboardingAudit.recordCreation(tenant.tenantId(),prepared.actorAccountId());
            onboardingGuard.complete(evidence,auditEvidence);
        });
    }

    @POST
    @Path("/integrity-reuse/{outcome}/{slug}/{account}")
    public void integrityReuse(@PathParam("outcome") String outcome, @PathParam("slug") String slug,
                               @PathParam("account") UUID account) {
        var created = new com.commercecore.diaz.tenant.application.CreatedTenant[1];
        var prepared = new com.commercecore.diaz.identity.application.PreparedOnboardingAccounts[1];
        try {
            io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
                prepared[0]=identityOnboarding.prepare(account);
                created[0]=tenantCreation.create(slug,"Empresa","Empresa","PEN","UTC");
                if (outcome.equals("rollback")) throw new IllegalStateException("intentional rollback");
                var evidence=identityOnboarding.bootstrap(created[0],prepared[0],java.util.Set.of());
                var audit=onboardingAudit.recordCreation(created[0].tenantId(),prepared[0].actorAccountId());
                onboardingGuard.complete(evidence,audit);
            });
            if (outcome.equals("rollback")) throw new AssertionError("rollback expected");
        } catch (IllegalStateException ex) {
            if (!outcome.equals("rollback")) throw ex;
        }
        try {
            io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() ->
                    identityOnboarding.bootstrap(created[0],prepared[0],java.util.Set.of()));
            throw new AssertionError("reused receipt accepted");
        } catch (SecurityException expected) {
            // The receipt must not authorize a later transaction.
        }
    }

    @POST
    @Path("/onboard-in-transaction/{slug}/{account}")
    public void onboardingInTransaction(@PathParam("slug") String slug, @PathParam("account") UUID account) {
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() ->
                onboarding.create(new CreateTenantCommand(slug,"Empresa","Empresa","PEN","UTC",account,java.util.Set.of())));
    }
}
