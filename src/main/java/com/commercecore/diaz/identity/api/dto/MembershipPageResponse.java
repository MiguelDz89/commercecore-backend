package com.commercecore.diaz.identity.api.dto;

import java.util.List;

public record MembershipPageResponse(List<MembershipResponse> items, int offset, int limit) {
    public MembershipPageResponse { items=List.copyOf(items); }
}
