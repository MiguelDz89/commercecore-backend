package com.commercecore.diaz.catalog.application.port;
import com.commercecore.diaz.catalog.domain.Category;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public interface CategoryStore {
    void lockHierarchy(UUID tenant);
    Optional<Category> find(UUID tenant,UUID id);
    List<Category> list(UUID tenant,int offset,int limit,String search);
    boolean createsCycle(UUID tenant,UUID id,UUID parent);
    boolean hasActiveChildren(UUID tenant,UUID id);
    Category insert(UUID tenant,String code,String name,String description,UUID parent);
    Category save(Category category);
}
