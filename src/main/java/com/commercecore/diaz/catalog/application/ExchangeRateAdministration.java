package com.commercecore.diaz.catalog.application;

import com.commercecore.diaz.audit.application.PriceAuditRecorder;
import com.commercecore.diaz.catalog.application.port.CategoryStore;
import com.commercecore.diaz.catalog.application.port.ExchangeRateStore;
import com.commercecore.diaz.catalog.domain.CommercialMoney;
import com.commercecore.diaz.identity.application.ActiveAccountQuery;
import com.commercecore.diaz.identity.application.TenantAuthorization;
import com.commercecore.diaz.identity.application.TenantPermissionDecision;
import com.commercecore.diaz.tenant.application.TenantCommercialSettingsQuery;
import com.commercecore.diaz.tenant.application.TenantStatusQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.Optional;

@ApplicationScoped
public class ExchangeRateAdministration {
    public record Rate(String source, String target, LocalDate date, BigDecimal rate) { }

    private final ExchangeRateStore rates;
    private final TenantAuthorization authorization;
    private final TenantStatusQuery tenants;
    private final TenantCommercialSettingsQuery settings;
    private final ActiveAccountQuery accounts;
    private final CategoryStore locks;
    private final PriceAuditRecorder audit;

    public ExchangeRateAdministration(ExchangeRateStore rates, TenantAuthorization authorization, TenantStatusQuery tenants,
                                      TenantCommercialSettingsQuery settings, ActiveAccountQuery accounts,
                                      CategoryStore locks, PriceAuditRecorder audit) {
        this.rates=rates;
        this.authorization=authorization;
        this.tenants=tenants;
        this.settings=settings;
        this.accounts=accounts;
        this.locks=locks;
        this.audit=audit;
    }

    @Transactional
    public Optional<Rate> get(UUID tenant, String from, LocalDate date) {
        authorize(tenant,"PURCHASE_EXCHANGE_RATE_READ");
        String to=validatePair(tenant,from);
        return rates.onOrBefore(tenant,from,to,date)
                .filter(rate -> rate.date().equals(date))
                .map(rate -> new Rate(from,to,date,rate.rate()));
    }

    @Transactional
    public void put(UUID tenant, String from, LocalDate date, BigDecimal amount) {
        locks.lockHierarchy(tenant);
        authorize(tenant,"PURCHASE_EXCHANGE_RATE_UPDATE");
        UUID actor=accounts.currentAccountId().orElseThrow(ExchangeRateAdministration::denied);
        String to=validatePair(tenant,from);
        CommercialMoney.validateRate(amount);
        var before=rates.onOrBefore(tenant,from,to,date)
                .filter(rate -> rate.date().equals(date)).map(rate -> snapshot(from,to,rate)).orElse(null);
        var entry=rates.put(tenant,from,to,date,amount,actor);
        audit.exchangeRate(tenant,actor,entry.id(),before,snapshot(from,to,entry));
    }

    private void authorize(UUID tenant,String permission) {
        if (authorization.hasPermission(tenant,permission)!=TenantPermissionDecision.ALLOW
                || tenants.statusOf(tenant)!=TenantStatusQuery.Result.ACTIVE)
            throw denied();
    }

    private String validatePair(UUID tenant,String from) {
        CommercialMoney.currency(from);
        String to=settings.commercialSettings(tenant).orElseThrow(ExchangeRateAdministration::denied).currency();
        if(from.equals(to)) throw new IllegalArgumentException("An exchange rate requires two currencies");
        return to;
    }

    private static PriceAuditRecorder.ExchangeRateSnapshot snapshot(String from,String to,ExchangeRateStore.Entry entry) {
        return new PriceAuditRecorder.ExchangeRateSnapshot(from,to,entry.date(),entry.rate(),entry.actor());
    }

    private static SecurityException denied() {
        return new SecurityException("Exchange rates unavailable");
    }
}
