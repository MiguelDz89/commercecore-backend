package com.commercecore.diaz.catalog.application;

import com.commercecore.diaz.audit.application.CategoryAuditEvent;
import com.commercecore.diaz.audit.application.CategoryAuditRecorder;
import com.commercecore.diaz.catalog.application.port.CategoryStore;
import com.commercecore.diaz.catalog.domain.Category;
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
public class CategoryAdministrationService implements CategoryAdministration {
    private final CategoryStore store;
    private final TenantAuthorization authorization;
    private final TenantStatusQuery tenants;
    private final ActiveAccountQuery accounts;
    private final CategoryAuditRecorder audit;
    public CategoryAdministrationService(CategoryStore store,TenantAuthorization authorization,TenantStatusQuery tenants,
                                         ActiveAccountQuery accounts,CategoryAuditRecorder audit) {
        this.store=store;this.authorization=authorization;this.tenants=tenants;this.accounts=accounts;this.audit=audit;
    }
    @Override @Transactional public List<Category> list(UUID tenant,int offset,int limit,String search) {
        if(offset<0 || limit<1 || limit>100 || (search!=null && search.length()>200)) throw new IllegalArgumentException("Invalid pagination or search");
        prepare(tenant,false,false);
        return store.list(tenant,offset,limit,search==null?"":search);
    }
    @Override @Transactional public Category get(UUID tenant,UUID id) {
        prepare(tenant,false,false);return require(tenant,id);
    }
    @Override @Transactional public Category create(UUID tenant,String code,String name,String description,UUID parent) {
        UUID actor=prepare(tenant,true,true);
        if(code==null || code.isBlank() || code.length()>80 || name==null || name.isBlank() || name.length()>160)
            throw new IllegalArgumentException("Invalid category");
        if(parent!=null) requireActiveParent(tenant,parent);
        Category created=store.insert(tenant,code,name,description,parent);
        audit.record(new CategoryAuditEvent(tenant,actor,created.id(),CategoryAuditEvent.Action.CREATED,null,snapshot(created)));
        return created;
    }
    @Override @Transactional public Category update(UUID tenant,UUID id,CategoryPatch patch) {
        UUID actor=prepare(tenant,true,true); Objects.requireNonNull(patch,"patch");
        Category old=require(tenant,id);
        if(!patch.namePresent() && !patch.descriptionPresent() && !patch.parentPresent()) throw new IllegalArgumentException("Empty change");
        UUID parent=patch.parentPresent()?patch.parentId():old.parentId();
        if(patch.parentPresent() && parent!=null) {
            if(parent.equals(id)) throw new CategoryConflictException("Invalid hierarchy");
            requireActiveParent(tenant,parent);
            if(store.createsCycle(tenant,id,parent)) throw new CategoryConflictException("Invalid hierarchy");
        }
        Category desired=new Category(old.id(),old.tenantId(),parent,old.code(),patch.namePresent()?patch.name():old.name(),
                patch.descriptionPresent()?patch.description():old.description(),old.status(),old.createdAt(),old.updatedAt());
        Category updated=store.save(desired);
        audit.record(new CategoryAuditEvent(tenant,actor,id,CategoryAuditEvent.Action.UPDATED,snapshot(old),snapshot(updated)));
        return updated;
    }
    @Override @Transactional public Category deactivate(UUID tenant,UUID id) { return transition(tenant,id,Category.Status.ACTIVE,Category.Status.INACTIVE); }
    @Override @Transactional public Category reactivate(UUID tenant,UUID id) { return transition(tenant,id,Category.Status.INACTIVE,Category.Status.ACTIVE); }
    private Category transition(UUID tenant,UUID id,Category.Status expected,Category.Status next) {
        UUID actor=prepare(tenant,true,true);Category old=require(tenant,id);
        if(old.status()!=expected) throw new CategoryConflictException("Invalid category transition");
        if(next==Category.Status.ACTIVE && old.parentId()!=null) requireActiveParent(tenant,old.parentId());
        if(next==Category.Status.INACTIVE && store.hasActiveChildren(tenant,id)) throw new CategoryConflictException("Active child categories exist");
        Category updated=store.save(new Category(old.id(),old.tenantId(),old.parentId(),old.code(),old.name(),old.description(),next,old.createdAt(),old.updatedAt()));
        audit.record(new CategoryAuditEvent(tenant,actor,id,next==Category.Status.ACTIVE?CategoryAuditEvent.Action.REACTIVATED:CategoryAuditEvent.Action.DEACTIVATED,snapshot(old),snapshot(updated)));
        return updated;
    }
    private UUID prepare(UUID tenant,boolean write,boolean lock) {
        Objects.requireNonNull(tenant,"tenant");if(lock)store.lockHierarchy(tenant);
        boolean allowed=authorization.hasPermission(tenant,"CATEGORY_MANAGE")==TenantPermissionDecision.ALLOW;
        if(!write && !allowed) allowed=authorization.hasPermission(tenant,"PRODUCT_READ")==TenantPermissionDecision.ALLOW;
        if(!allowed || tenants.statusOf(tenant)!=TenantStatusQuery.Result.ACTIVE) throw denied();
        return accounts.currentAccountId().orElseThrow(CategoryAdministrationService::denied);
    }
    private Category require(UUID tenant,UUID id) { return store.find(tenant,Objects.requireNonNull(id,"id")).orElseThrow(CategoryAdministrationService::denied); }
    private void requireActiveParent(UUID tenant,UUID id) {
        Category parent=require(tenant,id);if(parent.status()!=Category.Status.ACTIVE) throw new CategoryConflictException("Parent unavailable");
    }
    private static SecurityException denied(){return new SecurityException("Category unavailable");}
    private static CategoryAuditEvent.Snapshot snapshot(Category c) {return new CategoryAuditEvent.Snapshot(c.code(),c.name(),c.description(),c.parentId(),c.status().name());}
}
