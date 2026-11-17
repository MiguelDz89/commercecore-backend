package com.commercecore.diaz.catalog.infrastructure.persistence;

import com.commercecore.diaz.catalog.application.CategoryConflictException;
import com.commercecore.diaz.catalog.application.port.CategoryStore;
import com.commercecore.diaz.catalog.domain.Category;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class JdbcCategoryStore implements CategoryStore {
    private final DataSource source;
    public JdbcCategoryStore(DataSource source) { this.source=source; }
    private static final String FIELDS="category_id,tenant_id,parent_category_id,code,name,description,status,created_at,updated_at";
    @Override public void lockHierarchy(UUID tenant) {
        try(var c=source.getConnection()) {
            try(var s=c.prepareStatement("SHOW transaction_isolation");var r=s.executeQuery()) {
                if(!r.next() || !"read committed".equalsIgnoreCase(r.getString(1)))
                    throw new IllegalStateException("Category hierarchy requires READ COMMITTED");
            }
            String previous;
            try(var s=c.prepareStatement("SELECT current_setting('lock_timeout')");var r=s.executeQuery()) {
                if(!r.next()) throw new SQLException("Missing lock timeout"); previous=r.getString(1);
            }
            try(var s=c.prepareStatement("SELECT set_config('lock_timeout',?,true)")) { s.setString(1,"3s"); s.execute(); }
            try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended('commercecore:catalog:category:' || ?,0))")) {
                s.setString(1,tenant.toString()); s.execute();
            }
            try(var s=c.prepareStatement("SELECT set_config('lock_timeout',?,true)")) { s.setString(1,previous); s.execute(); }
        } catch(SQLException e) {
            if("55P03".equals(e.getSQLState()) || "40P01".equals(e.getSQLState()) || "40001".equals(e.getSQLState()))
                throw new CategoryConflictException("Concurrent category modification",e);
            throw failure(e);
        }
    }
    @Override public Optional<Category> find(UUID tenant,UUID id) {
        try(var c=source.getConnection();var s=c.prepareStatement("SELECT "+FIELDS+" FROM category WHERE tenant_id=? AND category_id=?")) {
            s.setObject(1,tenant);s.setObject(2,id);try(var r=s.executeQuery()) { return r.next()?Optional.of(row(r)):Optional.empty(); }
        } catch(SQLException e) { throw failure(e); }
    }
    @Override public List<Category> list(UUID tenant,int offset,int limit,String search) {
        try(var c=source.getConnection();var s=c.prepareStatement("SELECT "+FIELDS+" FROM category WHERE tenant_id=? AND (strpos(lower(name),lower(?))>0 OR strpos(lower(code),lower(?))>0) ORDER BY lower(name),category_id LIMIT ? OFFSET ?")) {
            s.setObject(1,tenant);s.setString(2,search);s.setString(3,search);s.setInt(4,limit);s.setInt(5,offset);
            var result=new ArrayList<Category>();try(var r=s.executeQuery()) { while(r.next()) result.add(row(r)); } return List.copyOf(result);
        } catch(SQLException e) { throw failure(e); }
    }
    @Override public boolean createsCycle(UUID tenant,UUID id,UUID parent) {
        try(var c=source.getConnection();var s=c.prepareStatement("""
                WITH RECURSIVE ancestors(id) AS (
                  SELECT CAST(? AS uuid) UNION
                  SELECT c.parent_category_id FROM category c JOIN ancestors a ON c.category_id=a.id
                  WHERE c.tenant_id=? AND c.parent_category_id IS NOT NULL
                ) SELECT EXISTS(SELECT 1 FROM ancestors WHERE id=?)
                """)) {
            s.setObject(1,parent);s.setObject(2,tenant);s.setObject(3,id);try(var r=s.executeQuery()){r.next();return r.getBoolean(1);}
        } catch(SQLException e) { throw failure(e); }
    }
    @Override public boolean hasActiveChildren(UUID tenant,UUID id) {
        try(var c=source.getConnection();var s=c.prepareStatement("SELECT EXISTS(SELECT 1 FROM category WHERE tenant_id=? AND parent_category_id=? AND status='ACTIVE')")) {
            s.setObject(1,tenant);s.setObject(2,id);try(var r=s.executeQuery()){r.next();return r.getBoolean(1);}
        } catch(SQLException e) { throw failure(e); }
    }
    @Override public Category insert(UUID tenant,String code,String name,String description,UUID parent) {
        try(var c=source.getConnection();var s=c.prepareStatement("INSERT INTO category(tenant_id,code,name,description,parent_category_id,status) VALUES (?,?,?,?,?,'ACTIVE') ON CONFLICT (tenant_id,code) DO NOTHING RETURNING "+FIELDS)) {
            s.setObject(1,tenant);s.setString(2,code);s.setString(3,name);s.setString(4,description);s.setObject(5,parent);
            try(var r=s.executeQuery()){if(!r.next()) throw new CategoryConflictException("Category code already exists");return row(r);}
        } catch(SQLException e) { throw failure(e); }
    }
    @Override public Category save(Category category) {
        try(var c=source.getConnection();var s=c.prepareStatement("UPDATE category SET name=?,description=?,parent_category_id=?,status=?,updated_at=clock_timestamp() WHERE tenant_id=? AND category_id=? RETURNING "+FIELDS)) {
            s.setString(1,category.name());s.setString(2,category.description());s.setObject(3,category.parentId());s.setString(4,category.status().name());
            s.setObject(5,category.tenantId());s.setObject(6,category.id());try(var r=s.executeQuery()){if(!r.next())throw new SecurityException("Category unavailable");return row(r);}
        } catch(SQLException e) { throw failure(e); }
    }
    private static Category row(ResultSet r) throws SQLException {
        return new Category(r.getObject("category_id",UUID.class),r.getObject("tenant_id",UUID.class),r.getObject("parent_category_id",UUID.class),
                r.getString("code"),r.getString("name"),r.getString("description"),Category.Status.valueOf(r.getString("status")),
                r.getObject("created_at",OffsetDateTime.class).toInstant(),r.getObject("updated_at",OffsetDateTime.class).toInstant());
    }
    private static IllegalStateException failure(SQLException e) { return new IllegalStateException("Category persistence failed",e); }
}
