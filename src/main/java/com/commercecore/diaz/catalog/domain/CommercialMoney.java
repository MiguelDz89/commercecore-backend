package com.commercecore.diaz.catalog.domain;

import java.math.BigDecimal;
import java.util.Currency;

public final class CommercialMoney {
    private static final BigDecimal MAX_RATE = new BigDecimal("9999999999.99999999");

    private CommercialMoney() { }

    public static String currency(String code) {
        if (code == null || !code.matches("[A-Z]{3}"))
            throw new IllegalArgumentException("Invalid currency");
        return Currency.getInstance(code).getCurrencyCode();
    }

    public static BigDecimal validateRate(BigDecimal rate) {
        if (rate == null || rate.signum() <= 0 || rate.scale() > 8 || rate.compareTo(MAX_RATE) > 0)
            throw new IllegalArgumentException("Invalid exchange rate");
        return rate;
    }
}
