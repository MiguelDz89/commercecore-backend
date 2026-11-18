package com.commercecore.diaz.catalog.domain;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Validated retail price and quantity breaks, independent of persistence and HTTP. */
public record PriceSchedule(BigDecimal retailPrice, List<Tier> tiers) {
    private static final BigDecimal MAX_MONEY = new BigDecimal("9999999999999999.99");
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("999999999999999.999");
    private static final BigDecimal FIRST_TIER_MINIMUM = BigDecimal.valueOf(2);

    public record Tier(BigDecimal minimumQuantity, BigDecimal unitPrice) {
        public Tier {
            minimumQuantity = requireQuantity(minimumQuantity, true);
            unitPrice = money(unitPrice);
        }
    }

    public PriceSchedule {
        retailPrice = money(retailPrice);
        tiers = Objects.requireNonNull(tiers).stream()
                .sorted(Comparator.comparing(Tier::minimumQuantity)).toList();
        BigDecimal previousQuantity = BigDecimal.ZERO;
        BigDecimal previousPrice = retailPrice;
        for (Tier tier : tiers) {
            if (tier.minimumQuantity().compareTo(previousQuantity) <= 0 || tier.unitPrice().compareTo(previousPrice) > 0)
                throw new IllegalArgumentException("Invalid price tiers");
            previousQuantity = tier.minimumQuantity();
            previousPrice = tier.unitPrice();
        }
    }

    /** The write-side minimum stays at two units. Previous database rows remain readable. */
    public void validateNewTiers(boolean fractional) {
        for (Tier tier : tiers) {
            BigDecimal quantity = requireQuantity(tier.minimumQuantity(), fractional);
            if (quantity.compareTo(FIRST_TIER_MINIMUM) < 0)
                throw new IllegalArgumentException("Invalid tier threshold");
        }
    }

    public boolean hasFractionalTiers() {
        return tiers.stream().anyMatch(tier -> isFractional(tier.minimumQuantity()));
    }

    public BigDecimal quote(BigDecimal quantity) {
        requireQuantity(quantity, true);
        BigDecimal price = retailPrice;
        for (Tier tier : tiers) {
            if (tier.minimumQuantity().compareTo(quantity) > 0) break;
            price = tier.unitPrice();
        }
        return price;
    }

    public static BigDecimal requireQuantity(BigDecimal value, boolean fractional) {
        if (value == null || value.signum() <= 0 || value.scale() > 3 || value.compareTo(MAX_QUANTITY) > 0)
            throw new IllegalArgumentException("Invalid quantity");
        if (!fractional && isFractional(value))
            throw new IllegalArgumentException("Fractional quantity not enabled");
        return value;
    }

    private static boolean isFractional(BigDecimal value) {
        return value.stripTrailingZeros().scale() > 0;
    }

    public static BigDecimal money(BigDecimal value) {
        if (value == null || value.scale() > 2 || value.signum() < 0 || value.compareTo(MAX_MONEY) > 0)
            throw new IllegalArgumentException("Invalid amount");
        return value;
    }
}
