package com.commercecore.diaz.identity.application;

import com.commercecore.diaz.identity.domain.Membership;
import java.util.List;
import java.util.UUID;

public interface MembershipAdministration {
    Membership invite(UUID tenantId, UUID userId);
    List<Membership> list(UUID tenantId, int offset, int limit);
    Membership accept(UUID tenantId, UUID membershipId);
    Membership suspend(UUID tenantId, UUID membershipId);
    Membership reactivate(UUID tenantId, UUID membershipId);
}
