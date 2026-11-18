package com.commercecore.diaz.audit.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Sensitive price events require separate authorization on any future audit reader. */
public interface PriceAuditRecorder {
    record Tier(BigDecimal minimumQuantity, BigDecimal unitPrice) { }
    record SaleSnapshot(boolean configured, BigDecimal retailPrice, List<Tier> tiers) { }
    record CostSnapshot(BigDecimal cost, String currency, BigDecimal sourceAmount, String sourceCurrency,
                        LocalDate operationDate, LocalDate rateDate, BigDecimal rate, UUID recordedBy) { }
    record ExchangeRateSnapshot(String source, String target, LocalDate date, BigDecimal rate, UUID recordedBy) { }
    void exchangeRate(UUID tenant, UUID actor, UUID rate, ExchangeRateSnapshot before, ExchangeRateSnapshot after);
    void sale(UUID tenant, UUID actor, UUID product, SaleSnapshot before, SaleSnapshot after);
    void cost(UUID tenant, UUID actor, UUID product, CostSnapshot before, CostSnapshot after);
}
