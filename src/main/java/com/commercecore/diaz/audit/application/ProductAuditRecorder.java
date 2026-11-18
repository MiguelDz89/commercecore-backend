package com.commercecore.diaz.audit.application;

import java.util.List;
import java.util.UUID;

public interface ProductAuditRecorder {
    void record(ProductAuditEvent event);
    void recordStatusChanges(UUID tenant, UUID actor, UUID correlationId, String reason,
                             List<ProductAuditEvent.StatusChange> changes);
}
