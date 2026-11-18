package com.commercecore.diaz.catalog.domain;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PriceScheduleTest {
    @Test void refinedExampleAppliesOneUnitPriceToEveryUnit() {
        var schedule = new PriceSchedule(new BigDecimal("12"), List.of(
                new PriceSchedule.Tier(BigDecimal.valueOf(101), new BigDecimal("6")),
                new PriceSchedule.Tier(BigDecimal.valueOf(20), new BigDecimal("8")),
                new PriceSchedule.Tier(BigDecimal.valueOf(6), new BigDecimal("10")),
                new PriceSchedule.Tier(BigDecimal.valueOf(50), new BigDecimal("7"))));
        long[] quantities = {1, 5, 6, 19, 20, 49, 50, 100, 101};
        int[] expected = {12, 12, 10, 10, 8, 8, 7, 7, 6};
        for (int i = 0; i < quantities.length; i++)
            assertEquals(0, schedule.quote(BigDecimal.valueOf(quantities[i])).compareTo(BigDecimal.valueOf(expected[i])));
        assertThrows(IllegalArgumentException.class, () -> new PriceSchedule(new BigDecimal("-0.01"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new PriceSchedule(new BigDecimal("10000000000000000"), List.of()));
        assertEquals(0, new PriceSchedule(BigDecimal.ZERO, List.of()).quote(BigDecimal.valueOf(1)).signum());
    }
    @Test void resolvesBoundariesAndRejectsInvalidSchedules() {
        var schedule = new PriceSchedule(new BigDecimal("10.00"), List.of(
                new PriceSchedule.Tier(BigDecimal.valueOf(5), new BigDecimal("8.00")),
                new PriceSchedule.Tier(BigDecimal.valueOf(10), new BigDecimal("7.00"))));
        assertEquals(new BigDecimal("10.00"), schedule.quote(BigDecimal.valueOf(4)));
        assertEquals(new BigDecimal("8.00"), schedule.quote(BigDecimal.valueOf(5)));
        assertEquals(new BigDecimal("7.00"), schedule.quote(BigDecimal.valueOf(10)));
        assertThrows(IllegalArgumentException.class, () -> schedule.quote(BigDecimal.valueOf(0)));
        assertThrows(IllegalArgumentException.class, () -> new PriceSchedule(BigDecimal.ONE, List.of(new PriceSchedule.Tier(BigDecimal.ONE, BigDecimal.ONE))).validateNewTiers(true));
        assertThrows(IllegalArgumentException.class, () -> new PriceSchedule(new BigDecimal("1.001"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new PriceSchedule(BigDecimal.ONE, List.of(new PriceSchedule.Tier(BigDecimal.valueOf(2), BigDecimal.TEN))));
        assertThrows(IllegalArgumentException.class, () -> new PriceSchedule(BigDecimal.TEN, List.of(new PriceSchedule.Tier(BigDecimal.valueOf(2), BigDecimal.ONE), new PriceSchedule.Tier(BigDecimal.valueOf(2), BigDecimal.ONE))));
    }

    @Test void handlesExactDecimalThresholdsWithoutRounding() {
        var price = new PriceSchedule(new BigDecimal("20.00"),
                List.of(new PriceSchedule.Tier(new BigDecimal("2.500"), new BigDecimal("15.00"))));
        price.validateNewTiers(true);
        assertThrows(IllegalArgumentException.class, () -> price.validateNewTiers(false));
        assertEquals(new BigDecimal("20.00"), price.quote(new BigDecimal("2.499")));
        assertEquals(new BigDecimal("15.00"), price.quote(new BigDecimal("2.50")));
        assertEquals(new BigDecimal("15.00"), price.quote(new BigDecimal("2.501")));
        assertEquals(new BigDecimal("20.00"), price.quote(new BigDecimal("0.001")));
        assertThrows(IllegalArgumentException.class, () -> price.quote(new BigDecimal("2.5001")));
        assertThrows(IllegalArgumentException.class, () -> price.quote(new BigDecimal("1000000000000000")));
        assertEquals(new BigDecimal("15.00"), price.quote(new BigDecimal("999999999999999.999")));
        assertEquals(new BigDecimal("5.000"), PriceSchedule.requireQuantity(new BigDecimal("5.000"), false));
        assertThrows(IllegalArgumentException.class, () -> new PriceSchedule(BigDecimal.TEN,
                List.of(new PriceSchedule.Tier(new BigDecimal("2.5"), BigDecimal.ONE),
                        new PriceSchedule.Tier(new BigDecimal("2.500"), BigDecimal.ONE))));
    }
}
