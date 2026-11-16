package com.commercecore.diaz.identity.api.dto;

import com.commercecore.diaz.identity.domain.Membership;
import java.time.Instant;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

public record MembershipResponse(UUID membershipId, UUID tenantId, UUID accountId,
        @Schema(enumeration={"INVITED","ACTIVE","SUSPENDED"}) String status, Instant createdAt) {
    public static MembershipResponse from(Membership membership) {
        return new MembershipResponse(membership.id(),membership.tenantId(),membership.userId(),
                membership.status().name(),membership.createdAt());
    }
}
