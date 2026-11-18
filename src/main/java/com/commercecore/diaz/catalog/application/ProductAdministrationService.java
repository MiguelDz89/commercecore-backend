package com.commercecore.diaz.catalog.application;

import com.commercecore.diaz.audit.application.ProductAuditEvent;
import com.commercecore.diaz.audit.application.ProductAuditRecorder;
import com.commercecore.diaz.audit.application.ProductCommercialHistoryQuery;
import com.commercecore.diaz.catalog.application.port.CategoryStore;
import com.commercecore.diaz.catalog.application.port.ProductStore;
import com.commercecore.diaz.catalog.application.ProductStatusConflictException.Code;
import com.commercecore.diaz.catalog.application.ProductStatusConflictException.Conflict;
import com.commercecore.diaz.catalog.domain.Category;
import com.commercecore.diaz.catalog.domain.Product;
import com.commercecore.diaz.identity.application.ActiveAccountQuery;
import com.commercecore.diaz.identity.application.TenantAuthorization;
import com.commercecore.diaz.identity.application.TenantPermissionDecision;
import com.commercecore.diaz.tenant.application.TenantStatusQuery;
import com.commercecore.diaz.tenant.application.TenantCommercialSettingsQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@ApplicationScoped
public class ProductAdministrationService implements ProductAdministration {
    private final ProductStore store; private final CategoryStore categories; private final TenantAuthorization authorization;
    private final TenantStatusQuery tenants; private final ActiveAccountQuery accounts; private final ProductAuditRecorder audit;
    private final ProductCommercialHistoryQuery history;
    private final TenantCommercialSettingsQuery commercialSettings;
    public ProductAdministrationService(ProductStore store,CategoryStore categories,TenantAuthorization authorization,TenantStatusQuery tenants,ActiveAccountQuery accounts,ProductAuditRecorder audit,ProductCommercialHistoryQuery history,TenantCommercialSettingsQuery commercialSettings){
        this.store=store;this.categories=categories;this.authorization=authorization;this.tenants=tenants;this.accounts=accounts;this.audit=audit;this.history=history;this.commercialSettings=commercialSettings;
    }
    @Override @Transactional public Product create(UUID tenant,CreateProduct command){
        UUID actor=prepare(tenant,"PRODUCT_CREATE",true);
        activeCategory(tenant,command.categoryId());
        if (command.type()==Product.Type.VARIANT) {
            Product parent=require(tenant,command.parentId());
            if (parent.type()!=Product.Type.VARIABLE || parent.status()==Product.Status.ARCHIVED)
                throw new ProductConflictException("Invalid variant parent");
        }
        String currency= commercialSettings.commercialSettings(tenant).orElseThrow(ProductAdministrationService::denied).currency();
        Product created=store.insert(tenant,actor,command,currency);
        audit.record(new ProductAuditEvent(tenant,actor,created.id(),ProductAuditEvent.Action.CREATED,null,snapshot(created)));
        return created;
    }
    @Override @Transactional public Product get(UUID tenant,UUID id){prepare(tenant,"PRODUCT_READ",false);return require(tenant,id);}
    @Override @Transactional public List<Product> list(UUID tenant,int offset,int limit,String sku,String name,UUID category,Product.Status status,Product.Type type,UUID parent){
        if(offset<0 || limit<1 || limit>100 || (sku!=null && sku.length()>80) || (name!=null && name.length()>200))throw new IllegalArgumentException("Invalid product search");
        prepare(tenant,"PRODUCT_READ",false);return store.list(tenant,offset,limit,sku==null?"":sku,name==null?"":name,category,status,type,parent);
    }
    @Override @Transactional public Product update(UUID tenant,UUID id,ProductPatch patch){
        UUID actor=prepare(tenant,"PRODUCT_UPDATE",true);Objects.requireNonNull(patch,"patch");
        if(patch.version()<0 || (!patch.namePresent() && !patch.descriptionPresent() && !patch.categoryPresent() && patch.baseUnit()==null && patch.fractionalQuantitiesEnabled()==null && patch.attributes()==null))throw new IllegalArgumentException("Invalid product change");
        Product old=require(tenant,id);
        if(old.status()==Product.Status.ARCHIVED)throw new ProductConflictException("Product archived");
        if(old.version()!=patch.version())throw new ProductConflictException("Product version conflict");
        UUID category=patch.categoryPresent()?patch.categoryId():old.categoryId();
        if(patch.categoryPresent())activeCategory(tenant,category);
        String baseUnit=patch.baseUnit()==null?old.baseUnit():patch.baseUnit();
        Product.requireBaseUnit(baseUnit);
        if (!baseUnit.equals(old.baseUnit()) && (store.hasCommercialValues(tenant,id) || history.hasPriceHistory(tenant,id)))
            throw new ProductConflictException("Base unit has commercial history");
        boolean fractional=patch.fractionalQuantitiesEnabled()==null
                ?old.fractionalQuantitiesEnabled():patch.fractionalQuantitiesEnabled();
        if (!fractional && Boolean.FALSE.equals(patch.fractionalQuantitiesEnabled()) && store.hasFractionalTiers(tenant,id))
            throw new ProductConflictException("Fractional price tiers must be corrected first");
        if (patch.attributes()!=null && old.type()!=Product.Type.VARIANT)
            throw new IllegalArgumentException("Attributes require a variant");
        Product desired=new Product(old.id(),old.tenantId(),category,old.sku(),patch.namePresent()?patch.name():old.name(),
                patch.descriptionPresent()?patch.description():old.description(),old.status(),old.version(),old.createdAt(),
                old.updatedAt(),baseUnit,fractional,old.type(),old.parentId(),patch.attributes()==null?old.attributes():patch.attributes(),
                old.published(),old.everPublished());
        Product updated=store.update(desired,patch.version(),actor).orElseThrow(() -> new ProductConflictException("Product version conflict"));
        audit.record(new ProductAuditEvent(tenant,actor,id,ProductAuditEvent.Action.UPDATED,snapshot(old),snapshot(updated)));
        return updated;
    }

    @Override
    @Transactional
    public UUID changeStatus(UUID tenant, ProductStatusChange change) {
        if (tenant == null || change == null) throw new IllegalArgumentException("Tenant and status change are required");
        UUID actor = prepare(tenant, "PRODUCT_STATUS_UPDATE", true);
        var items = change.items().stream().sorted(Comparator.comparing(ProductStatusChange.Item::productId)).toList();
        var ids = items.stream().map(ProductStatusChange.Item::productId).toList();
        var selected = store.findSelected(tenant, ids).stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));
        if (selected.size() != ids.size()) throw denied();

        var conflicts = new ArrayList<Conflict>();
        var orderedProducts = new ArrayList<Product>();
        var eligible = new HashSet<UUID>();
        for (var item : items) {
            var product = selected.get(item.productId());
            orderedProducts.add(product);
            boolean versionMatches = product.version() == item.version();
            boolean transitionAllowed = product.status().permitsTransitionTo(change.target());
            if (!versionMatches) conflicts.add(new Conflict(product.id(), Code.VERSION_CONFLICT));
            if (!transitionAllowed) conflicts.add(new Conflict(product.id(), Code.INVALID_TRANSITION));
            if (versionMatches && transitionAllowed) eligible.add(product.id());
        }
        if (change.target() == Product.Status.ARCHIVED) {
            var groups = orderedProducts.stream().filter(product -> product.type() == Product.Type.VARIABLE)
                    .map(Product::id).toList();
            for (var variant : store.findUnarchivedVariants(tenant, groups)) {
                if (!eligible.contains(variant.variantId())) {
                    conflicts.add(new Conflict(variant.parentId(), Code.UNARCHIVED_VARIANT, variant.variantId()));
                }
            }
        }
        if (!conflicts.isEmpty()) throw new ProductStatusConflictException(conflicts);

        store.changeStatus(tenant, actor, change.target(), orderedProducts);
        UUID correlationId = UUID.randomUUID();
        var events = orderedProducts.stream().map(product -> new ProductAuditEvent.StatusChange(product.id(),
                new ProductAuditEvent.StatusSnapshot(product.status().name(), product.version(),
                        product.published(), product.everPublished()),
                new ProductAuditEvent.StatusSnapshot(change.target().name(), product.version() + 1,
                        false, product.everPublished()))).toList();
        audit.recordStatusChanges(tenant, actor, correlationId, change.reason(), events);
        return correlationId;
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
    private static ProductAuditEvent.Snapshot snapshot(Product p){return new ProductAuditEvent.Snapshot(p.categoryId(),p.sku(),p.name(),
            p.description(),p.status().name(),p.version(),p.baseUnit(),p.fractionalQuantitiesEnabled(),p.type().name(),p.parentId(),p.attributes());}
    private static SecurityException denied(){return new SecurityException("Product unavailable");}
}
