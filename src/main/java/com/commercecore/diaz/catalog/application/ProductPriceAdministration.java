package com.commercecore.diaz.catalog.application;

import com.commercecore.diaz.catalog.application.port.ProductPriceStore;
import com.commercecore.diaz.catalog.domain.PriceSchedule;
import java.math.BigDecimal;
import java.util.List;
import java.time.LocalDate;
import java.util.UUID;

public interface ProductPriceAdministration {
    ProductPriceStore.Sale sale(UUID tenant,UUID product);
    List<PriceSchedule.Tier> tiers(UUID tenant,UUID product);
    ProductPriceStore.Cost cost(UUID tenant,UUID product);
    BigDecimal quote(UUID tenant,UUID product,BigDecimal quantity);
    void updateSale(UUID tenant,UUID product,long version,PriceSchedule schedule);
    void updateCost(UUID tenant,UUID product,long version,BigDecimal cost,String sourceCurrency,LocalDate operationDate);
}
