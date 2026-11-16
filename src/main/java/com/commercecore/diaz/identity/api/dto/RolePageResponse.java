package com.commercecore.diaz.identity.api.dto;
import java.util.List;
public record RolePageResponse(List<RoleResponse> items, int offset, int limit) {
    public RolePageResponse { items=List.copyOf(items); }
}
