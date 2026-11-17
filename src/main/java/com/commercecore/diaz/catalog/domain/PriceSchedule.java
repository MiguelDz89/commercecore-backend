package com.commercecore.diaz.catalog.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Validated retail price and quantity breaks, independent of persistence and HTTP. */
public record PriceSchedule(BigDecimal retailPrice, List<Tier> tiers) {
    private static final BigDecimal MAX = new BigDecimal("9999999999999999.99");

    public record Tier(long minimumQuantity, BigDecimal unitPrice) {
        public Tier {
            if (minimumQuantity < 2 || minimumQuantity > 999999999999999L)
                throw new IllegalArgumentException("Invalid minimum quantity");
            unitPrice = money(unitPrice);
        }
    }

    public PriceSchedule {
        retailPrice = money(retailPrice);
        tiers = List.copyOf(Objects.requireNonNull(tiers));
        long previousQuantity = 1;
        BigDecimal previousPrice = retailPrice;
        for (Tier tier : tiers) {
            if (tier.minimumQuantity() <= previousQuantity || tier.unitPrice().compareTo(previousPrice) > 0)
                throw new IllegalArgumentException("Invalid price tiers");
            previousQuantity = tier.minimumQuantity();
            previousPrice = tier.unitPrice();
        }
    }

    public BigDecimal quote(long quantity) {
        if (quantity < 1) throw new IllegalArgumentException("Invalid quantity");
        BigDecimal price = retailPrice;
        for (Tier tier : tiers) {
            if (tier.minimumQuantity() > quantity) break;
            price = tier.unitPrice();
        }
        return price;
    }

    public static BigDecimal money(BigDecimal value) {
        if (value == null || value.scale() > 2 || value.signum() < 0 || value.compareTo(MAX) > 0)
            throw new IllegalArgumentException("Invalid amount");
        return value;
    }
}
