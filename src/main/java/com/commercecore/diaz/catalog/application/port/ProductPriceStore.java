package com.commercecore.diaz.catalog.application.port;

import com.commercecore.diaz.catalog.domain.PriceSchedule;
import java.math.BigDecimal;
import java.util.List;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Internal persistence contract: all operations require an active transaction. */
public interface ProductPriceStore {
    record Sale(boolean configured, BigDecimal price, String currency, long version, boolean archived, boolean fractionalQuantitiesEnabled) { }
    record Acquisition(BigDecimal sourceAmount, String sourceCurrency, LocalDate operationDate,
                       LocalDate rateDate, BigDecimal rate, UUID recordedBy) { }
    record Cost(BigDecimal price, long version, boolean archived, String currency, Acquisition acquisition) { }
    Optional<Sale> sale(UUID tenant, UUID product);
    Optional<Cost> cost(UUID tenant, UUID product);
    List<PriceSchedule.Tier> tiers(UUID tenant, UUID product);
    boolean updateSale(UUID tenant, UUID product, UUID actor, long version, PriceSchedule schedule);
    boolean updateCost(UUID tenant, UUID product, UUID actor, long version, BigDecimal cost, Acquisition acquisition);
}
