package com.commercecore.diaz.catalog.api.dto;
import java.util.List;
public record CategoryPageResponse(List<CategoryResponse> items,int offset,int limit) {
    public CategoryPageResponse { items=List.copyOf(items); }
}
