package com.commercecore.diaz.catalog.application;
import com.commercecore.diaz.catalog.domain.Category;
import java.util.List;
import java.util.UUID;
public interface CategoryAdministration {
    List<Category> list(UUID tenant,int offset,int limit,String search);
    Category get(UUID tenant,UUID id);
    Category create(UUID tenant,String code,String name,String description,UUID parent);
    Category update(UUID tenant,UUID id,CategoryPatch patch);
    Category deactivate(UUID tenant,UUID id);
    Category reactivate(UUID tenant,UUID id);
}
