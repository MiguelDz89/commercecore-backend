package com.commercecore.diaz.catalog.application.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface ExchangeRateStore {
    record Entry(UUID id, LocalDate date, BigDecimal rate, UUID actor) { }

    Optional<Entry> onOrBefore(UUID tenant, String source, String target, LocalDate date);
    Entry put(UUID tenant, String source, String target, LocalDate date, BigDecimal rate, UUID actor);
}
