package com.commercecore.diaz.catalog.application;

import com.commercecore.diaz.audit.application.ProductAuditEvent;
import com.commercecore.diaz.audit.application.ProductAuditRecorder;
import com.commercecore.diaz.catalog.application.port.CategoryStore;
import com.commercecore.diaz.catalog.application.port.ProductStore;
import com.commercecore.diaz.catalog.domain.Category;
import com.commercecore.diaz.catalog.domain.Product;
import com.commercecore.diaz.identity.application.ActiveAccountQuery;
import com.commercecore.diaz.identity.application.TenantAuthorization;
import com.commercecore.diaz.identity.application.TenantPermissionDecision;
import com.commercecore.diaz.tenant.application.TenantStatusQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@ApplicationScoped
public class ProductAdministrationService implements ProductAdministration {
    private final ProductStore store; private final CategoryStore categories; private final TenantAuthorization authorization;
    private final TenantStatusQuery tenants; private final ActiveAccountQuery accounts; private final ProductAuditRecorder audit;
    public ProductAdministrationService(ProductStore store,CategoryStore categories,TenantAuthorization authorization,TenantStatusQuery tenants,ActiveAccountQuery accounts,ProductAuditRecorder audit){
        this.store=store;this.categories=categories;this.authorization=authorization;this.tenants=tenants;this.accounts=accounts;this.audit=audit;
    }
    @Override @Transactional public Product create(UUID tenant,UUID category,String sku,String name,String description){
        UUID actor=prepare(tenant,"PRODUCT_CREATE",true);valid(sku,name);activeCategory(tenant,category);
        Product created=store.insert(tenant,actor,category,sku,name,description);
        audit.record(new ProductAuditEvent(tenant,actor,created.id(),ProductAuditEvent.Action.CREATED,null,snapshot(created)));
        return created;
    }
    @Override @Transactional public Product get(UUID tenant,UUID id){prepare(tenant,"PRODUCT_READ",false);return require(tenant,id);}
    @Override @Transactional public List<Product> list(UUID tenant,int offset,int limit,String sku,String name,UUID category,Product.Status status){
        if(offset<0 || limit<1 || limit>100 || (sku!=null && sku.length()>80) || (name!=null && name.length()>200))throw new IllegalArgumentException("Invalid product search");
        prepare(tenant,"PRODUCT_READ",false);return store.list(tenant,offset,limit,sku==null?"":sku,name==null?"":name,category,status);
    }
    @Override @Transactional public Product update(UUID tenant,UUID id,ProductPatch patch){
        UUID actor=prepare(tenant,"PRODUCT_UPDATE",true);Objects.requireNonNull(patch,"patch");
        if(patch.version()<0 || (!patch.namePresent() && !patch.descriptionPresent() && !patch.categoryPresent()))throw new IllegalArgumentException("Invalid product change");
        Product old=require(tenant,id);
        if(old.status()==Product.Status.ARCHIVED)throw new ProductConflictException("Product archived");
        if(old.version()!=patch.version())throw new ProductConflictException("Product version conflict");
        UUID category=patch.categoryPresent()?patch.categoryId():old.categoryId();
        if(patch.categoryPresent())activeCategory(tenant,category);
        Product desired=new Product(old.id(),old.tenantId(),category,old.sku(),patch.namePresent()?patch.name():old.name(),
                patch.descriptionPresent()?patch.description():old.description(),old.status(),old.version(),old.createdAt(),old.updatedAt());
        Product updated=store.update(desired,patch.version(),actor).orElseThrow(() -> new ProductConflictException("Product version conflict"));
        audit.record(new ProductAuditEvent(tenant,actor,id,ProductAuditEvent.Action.UPDATED,snapshot(old),snapshot(updated)));
        return updated;
    }
    private UUID prepare(UUID tenant,String permission,boolean write){
        Objects.requireNonNull(tenant,"tenant");if(write)categories.lockHierarchy(tenant);
        if(authorization.hasPermission(tenant,permission)!=TenantPermissionDecision.ALLOW || tenants.statusOf(tenant)!=TenantStatusQuery.Result.ACTIVE)throw denied();
        return accounts.currentAccountId().orElseThrow(ProductAdministrationService::denied);
    }
    private void activeCategory(UUID tenant,UUID id){
        if(id==null)throw new IllegalArgumentException("Category required");
        Category category=categories.find(tenant,id).orElseThrow(ProductAdministrationService::denied);
        if(category.status()!=Category.Status.ACTIVE)throw new ProductConflictException("Category unavailable");
    }
    private Product require(UUID tenant,UUID id){return store.find(tenant,Objects.requireNonNull(id,"id")).orElseThrow(ProductAdministrationService::denied);}
    private static void valid(String sku,String name){
        if(sku==null || sku.isBlank() || sku.length()>80 || name==null || name.isBlank() || name.length()>200)throw new IllegalArgumentException("Invalid product");
    }
    private static ProductAuditEvent.Snapshot snapshot(Product p){return new ProductAuditEvent.Snapshot(p.categoryId(),p.sku(),p.name(),p.description(),p.status().name(),p.version());}
    private static SecurityException denied(){return new SecurityException("Product unavailable");}
}
