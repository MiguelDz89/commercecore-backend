package com.commercecore.diaz.audit.application;

import java.util.UUID;

/** Looks up historical commercial activity without exposing private audit data. */
public interface ProductCommercialHistoryQuery {
    boolean hasPriceHistory(UUID tenantId, UUID productId);
}
