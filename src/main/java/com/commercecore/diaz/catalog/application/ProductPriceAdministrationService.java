package com.commercecore.diaz.catalog.application;

import com.commercecore.diaz.audit.application.PriceAuditRecorder;
import com.commercecore.diaz.catalog.application.port.CategoryStore;
import com.commercecore.diaz.catalog.application.port.ProductPriceStore;
import com.commercecore.diaz.catalog.domain.PriceSchedule;
import com.commercecore.diaz.catalog.domain.CommercialMoney;
import com.commercecore.diaz.catalog.application.port.ExchangeRateStore;
import com.commercecore.diaz.identity.application.ActiveAccountQuery;
import com.commercecore.diaz.identity.application.TenantAuthorization;
import com.commercecore.diaz.identity.application.TenantPermissionDecision;
import com.commercecore.diaz.tenant.application.TenantStatusQuery;
import com.commercecore.diaz.tenant.application.TenantCommercialSettingsQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ProductPriceAdministrationService implements ProductPriceAdministration {
    private final ProductPriceStore store; private final CategoryStore locks; private final TenantAuthorization auth;
    private final TenantStatusQuery tenants; private final ActiveAccountQuery accounts; private final PriceAuditRecorder audit;
    private final TenantCommercialSettingsQuery settings;
    private final ExchangeRateStore rates;
    public ProductPriceAdministrationService(ProductPriceStore store,CategoryStore locks,TenantAuthorization auth,TenantStatusQuery tenants,ActiveAccountQuery accounts,PriceAuditRecorder audit,TenantCommercialSettingsQuery settings,ExchangeRateStore rates){
        this.store=store;this.locks=locks;this.auth=auth;this.tenants=tenants;this.accounts=accounts;this.audit=audit;this.settings=settings;this.rates=rates;
    }
    @Transactional public ProductPriceStore.Sale sale(UUID tenant,UUID product){prepare(tenant,"PRODUCT_READ",false);return requireSale(tenant,product);}
    @Transactional public List<PriceSchedule.Tier> tiers(UUID tenant,UUID product){prepare(tenant,"PRODUCT_PRICE_TIER_READ",true);requireSale(tenant,product);return store.tiers(tenant,product);}
    @Transactional public ProductPriceStore.Cost cost(UUID tenant,UUID product){prepare(tenant,"PRODUCT_COST_READ",false);return requireCost(tenant,product);}
    @Transactional public BigDecimal quote(UUID tenant,UUID product,BigDecimal quantity){
        prepare(tenant,"PRODUCT_PRICE_TIER_READ",true);
        var sale=requireSale(tenant,product);
        var mode = requireCommercialSettings(tenant);
        requireSameCurrency(mode.currency(),sale.currency());
        boolean fractional = mode.fractionalQuantitiesEnabled() && sale.fractionalQuantitiesEnabled();
        PriceSchedule.requireQuantity(quantity,fractional);
        if(!sale.configured())throw new ProductConflictException("Price not configured");
        var schedule=new PriceSchedule(sale.price(),store.tiers(tenant,product));
        if(!fractional && schedule.hasFractionalTiers())
            throw new ProductConflictException("Current quantity settings do not permit the stored tiers");
        return schedule.quote(quantity);
    }
    @Transactional public void updateSale(UUID tenant,UUID product,long version,PriceSchedule schedule){
        if(version<0 || schedule==null)throw new IllegalArgumentException("Invalid price");
        UUID actor=prepare(tenant,"PRODUCT_PRICE_UPDATE",true);var previous=requireSale(tenant,product);
        if(previous.archived() || previous.version()!=version)throw new ProductConflictException("Product version conflict");
        var mode = requireCommercialSettings(tenant);
        requireSameCurrency(mode.currency(),previous.currency());
        schedule.validateNewTiers(mode.fractionalQuantitiesEnabled() && previous.fractionalQuantitiesEnabled());
        var before=store.tiers(tenant,product);
        if(!store.updateSale(tenant,product,actor,version,schedule))throw new ProductConflictException("Product version conflict");
        audit.sale(tenant,actor,product,snapshot(previous.configured(),previous.configured()?previous.price():null,before),snapshot(true,schedule.retailPrice(),schedule.tiers()));
    }
    @Transactional
    public void updateCost(UUID tenant,UUID product,long version,BigDecimal cost,String sourceCurrency,LocalDate date) {
        if(version<0) throw new IllegalArgumentException("Invalid version");
        UUID actor=prepare(tenant,"PRODUCT_COST_UPDATE",true);
        var previous=requireCost(tenant,product);
        if(previous.archived() || previous.version()!=version) throw new ProductConflictException("Product version conflict");
        var mode=requireCommercialSettings(tenant);
        requireSameCurrency(mode.currency(),previous.currency());
        var evidence=acquisition(tenant,mode,actor,cost,sourceCurrency,date);
        BigDecimal converted=evidence==null || evidence.rate()==null ? cost
                : PriceSchedule.money(cost.multiply(evidence.rate()).setScale(2,RoundingMode.HALF_UP));
        if(!store.updateCost(tenant,product,actor,version,converted,evidence))
            throw new ProductConflictException("Product version conflict");
        audit.cost(tenant,actor,product,costSnapshot(previous.price(),previous.currency(),previous.acquisition()),
                costSnapshot(converted,mode.currency(),evidence));
    }

    private ProductPriceStore.Acquisition acquisition(UUID tenant,TenantCommercialSettingsQuery.Settings mode,
            UUID actor,BigDecimal cost,String sourceCurrency,LocalDate date) {
        if(cost==null) {
            if(sourceCurrency!=null || date!=null) throw new IllegalArgumentException("Unexpected acquisition metadata");
            return null;
        }
        PriceSchedule.money(cost);
        String source=sourceCurrency==null?mode.currency():CommercialMoney.currency(sourceCurrency);
        if(source.equals(mode.currency())) {
            LocalDate operationDate=date==null?LocalDate.now(ZoneId.of(mode.timeZone())):date;
            return new ProductPriceStore.Acquisition(cost,source,operationDate,null,null,actor);
        }
        if(date==null) throw new IllegalArgumentException("Foreign costs require an operation date");
        var rate=rates.onOrBefore(tenant,source,mode.currency(),date)
                .orElseThrow(()->new ProductConflictException("No exchange rate for acquisition date"));
        return new ProductPriceStore.Acquisition(cost,source,date,rate.date(),rate.rate(),actor);
    }

    private static PriceAuditRecorder.CostSnapshot costSnapshot(BigDecimal amount,String currency,
            ProductPriceStore.Acquisition evidence) {
        if(evidence==null) return new PriceAuditRecorder.CostSnapshot(amount,currency,null,null,null,null,null,null);
        return new PriceAuditRecorder.CostSnapshot(amount,currency,evidence.sourceAmount(),evidence.sourceCurrency(),
                evidence.operationDate(),evidence.rateDate(),evidence.rate(),evidence.recordedBy());
    }
    private TenantCommercialSettingsQuery.Settings requireCommercialSettings(UUID tenant) {
        return settings.commercialSettings(tenant).orElseThrow(ProductPriceAdministrationService::denied);
    }

    private static void requireSameCurrency(String company, String product) {
        if (!company.equals(product)) throw new ProductConflictException("Historical product currency requires reconciliation");
    }

    private UUID prepare(UUID tenant,String permission,boolean write){
        if(tenant==null)throw new IllegalArgumentException("Invalid tenant");if(write)locks.lockHierarchy(tenant);
        if(auth.hasPermission(tenant,permission)!=TenantPermissionDecision.ALLOW || tenants.statusOf(tenant)!=TenantStatusQuery.Result.ACTIVE)throw denied();
        return accounts.currentAccountId().orElseThrow(ProductPriceAdministrationService::denied);
    }
    private ProductPriceStore.Sale requireSale(UUID t,UUID p){return store.sale(t,p).orElseThrow(ProductPriceAdministrationService::denied);}
    private ProductPriceStore.Cost requireCost(UUID t,UUID p){return store.cost(t,p).orElseThrow(ProductPriceAdministrationService::denied);}
    private static SecurityException denied(){return new SecurityException("Product unavailable");}
    private static PriceAuditRecorder.SaleSnapshot snapshot(boolean configured,BigDecimal amount,List<PriceSchedule.Tier> tiers){
        return new PriceAuditRecorder.SaleSnapshot(configured,amount,tiers.stream().map(t->new PriceAuditRecorder.Tier(t.minimumQuantity(),t.unitPrice())).toList());
    }
}
