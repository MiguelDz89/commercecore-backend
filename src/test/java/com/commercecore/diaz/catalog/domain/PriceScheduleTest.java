package com.commercecore.diaz.catalog.domain;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PriceScheduleTest {
    @Test void resolvesBoundariesAndRejectsInvalidSchedules() {
        var schedule = new PriceSchedule(new BigDecimal("10.00"), List.of(
                new PriceSchedule.Tier(5, new BigDecimal("8.00")),
                new PriceSchedule.Tier(10, new BigDecimal("7.00"))));
        assertEquals(new BigDecimal("10.00"), schedule.quote(4));
        assertEquals(new BigDecimal("8.00"), schedule.quote(5));
        assertEquals(new BigDecimal("7.00"), schedule.quote(10));
        assertThrows(IllegalArgumentException.class, () -> schedule.quote(0));
        assertThrows(IllegalArgumentException.class, () -> new PriceSchedule.Tier(1, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> new PriceSchedule(new BigDecimal("1.001"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new PriceSchedule(BigDecimal.ONE, List.of(new PriceSchedule.Tier(2, BigDecimal.TEN))));
        assertThrows(IllegalArgumentException.class, () -> new PriceSchedule(BigDecimal.TEN, List.of(new PriceSchedule.Tier(2, BigDecimal.ONE), new PriceSchedule.Tier(2, BigDecimal.ONE))));
    }
}
