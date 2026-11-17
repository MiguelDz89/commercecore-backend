package com.commercecore.diaz.catalog.api.dto;
import java.util.List;
public record ProductPageResponse(List<ProductResponse> items,int offset,int limit){
    public ProductPageResponse {items=List.copyOf(items);}
}
