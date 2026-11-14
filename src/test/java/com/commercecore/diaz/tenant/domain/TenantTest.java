package com.commercecore.diaz.tenant.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TenantTest {
    @Test
    void createsActiveTenantAndAllowsOnlyExplicitTransitions() {
        Tenant tenant = Tenant.create("taller-andino", "Taller Andino SAC", "Taller Andino", "PEN", "America/Lima");
        assertNotNull(tenant.tenantId());
        assertEquals(TenantStatus.ACTIVE, tenant.status());
        assertEquals(tenant.createdAt(), tenant.updatedAt());
        Tenant suspended = tenant.suspend();
        assertEquals(TenantStatus.SUSPENDED, suspended.status());
        assertEquals(TenantStatus.ACTIVE, suspended.reactivate().status());
        assertThrows(IllegalStateException.class, tenant::reactivate);
        assertThrows(IllegalStateException.class, suspended::suspend);
        Tenant closed = new Tenant(UUID.randomUUID(), "closed", "Legal", "Nombre", "PEN", "UTC",
                TenantStatus.CLOSED, Instant.now(), Instant.now());
        assertThrows(IllegalStateException.class, closed::suspend);
        assertThrows(IllegalStateException.class, closed::reactivate);
    }

    @Test
    void rejectsInvalidSlugsAndNames() {
        for (String slug : new String[]{"", "-inicio", "dos--guiones", "Mayuscula", "á", "a".repeat(81)}) {
            assertThrows(IllegalArgumentException.class, () -> Tenant.create(slug, "Legal", "Nombre", "PEN", "UTC"));
        }
        assertThrows(IllegalArgumentException.class, () -> Tenant.create("valid", "   ", "Nombre", "PEN", "UTC"));
        assertThrows(IllegalArgumentException.class, () -> Tenant.create("valid", "Legal", "\t", "PEN", "UTC"));
        assertThrows(IllegalArgumentException.class, () -> Tenant.create("valid", "x".repeat(201), "Nombre", "PEN", "UTC"));
        assertThrows(IllegalArgumentException.class, () -> Tenant.create("valid", "Legal", "x".repeat(161), "PEN", "UTC"));
    }

    @Test
    void rejectsInvalidCurrencyAndTimeZone() {
        assertThrows(IllegalArgumentException.class, () -> Tenant.create("valid", "Legal", "Nombre", "ZZZ", "UTC"));
        assertThrows(IllegalArgumentException.class, () -> Tenant.create("valid", "Legal", "Nombre", "pen", "UTC"));
        assertThrows(IllegalArgumentException.class, () -> Tenant.create("valid", "Legal", "Nombre", "PEN", "not-a-zone"));
        assertThrows(IllegalArgumentException.class, () -> Tenant.create("valid", "Legal", "Nombre", "PEN", " "));
    }
}
