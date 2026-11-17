package com.commercecore.diaz.catalog.infrastructure.persistence;

import com.commercecore.diaz.catalog.application.ProductConflictException;
import com.commercecore.diaz.catalog.application.port.ProductStore;
import com.commercecore.diaz.catalog.domain.Product;
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

@ApplicationScoped @Transactional(Transactional.TxType.MANDATORY)
public class JdbcProductStore implements ProductStore {
    private static final String FIELDS="product_id,tenant_id,category_id,sku,name,description,status,version,created_at,updated_at";
    private final DataSource source;
    public JdbcProductStore(DataSource source){this.source=source;}
    @Override public Product insert(UUID tenant,UUID actor,UUID category,String sku,String name,String description){
        try(var c=source.getConnection();var s=c.prepareStatement("INSERT INTO product(tenant_id,category_id,sku,name,description,structure_type,status,created_by,updated_by) VALUES (?,?,?,?,?,'SIMPLE','DRAFT',?,?) ON CONFLICT (tenant_id,sku_normalized) DO NOTHING RETURNING "+FIELDS)){
            s.setObject(1,tenant);s.setObject(2,category);s.setString(3,sku);s.setString(4,name);s.setString(5,description);s.setObject(6,actor);s.setObject(7,actor);
            try(var r=s.executeQuery()){if(!r.next())throw new ProductConflictException("SKU already exists");return row(r);}
        }catch(SQLException e){throw failure(e);}
    }
    @Override public Optional<Product> find(UUID tenant,UUID id){
        try(var c=source.getConnection();var s=c.prepareStatement("SELECT "+FIELDS+" FROM product WHERE tenant_id=? AND product_id=? AND structure_type='SIMPLE'")){
            s.setObject(1,tenant);s.setObject(2,id);try(var r=s.executeQuery()){return r.next()?Optional.of(row(r)):Optional.empty();}
        }catch(SQLException e){throw failure(e);}
    }
    @Override public List<Product> list(UUID tenant,int offset,int limit,String sku,String name,UUID category,Product.Status status){
        try(var c=source.getConnection();var s=c.prepareStatement("SELECT "+FIELDS+" FROM product WHERE tenant_id=? AND structure_type='SIMPLE' AND strpos(sku_normalized,upper(btrim(?)))>0 AND strpos(lower(name),lower(?))>0 AND (CAST(? AS uuid) IS NULL OR category_id=?) AND (CAST(? AS text) IS NULL OR status=?) ORDER BY lower(name),product_id LIMIT ? OFFSET ?")){
            s.setObject(1,tenant);s.setString(2,sku);s.setString(3,name);s.setObject(4,category);s.setObject(5,category);
            String state=status==null?null:status.name();s.setString(6,state);s.setString(7,state);s.setInt(8,limit);s.setInt(9,offset);
            var result=new ArrayList<Product>();try(var r=s.executeQuery()){while(r.next())result.add(row(r));}return List.copyOf(result);
        }catch(SQLException e){throw failure(e);}
    }
    @Override public Optional<Product> update(Product p,long expectedVersion,UUID actor){
        try(var c=source.getConnection();var s=c.prepareStatement("UPDATE product SET name=?,description=?,category_id=?,version=version+1,updated_by=?,updated_at=clock_timestamp() WHERE tenant_id=? AND product_id=? AND structure_type='SIMPLE' AND status<>'ARCHIVED' AND version=? RETURNING "+FIELDS)){
            s.setString(1,p.name());s.setString(2,p.description());s.setObject(3,p.categoryId());s.setObject(4,actor);s.setObject(5,p.tenantId());s.setObject(6,p.id());s.setLong(7,expectedVersion);
            try(var r=s.executeQuery()){return r.next()?Optional.of(row(r)):Optional.empty();}
        }catch(SQLException e){throw failure(e);}
    }
    private static Product row(ResultSet r)throws SQLException{
        return new Product(r.getObject("product_id",UUID.class),r.getObject("tenant_id",UUID.class),r.getObject("category_id",UUID.class),r.getString("sku"),r.getString("name"),r.getString("description"),Product.Status.valueOf(r.getString("status")),r.getLong("version"),r.getObject("created_at",OffsetDateTime.class).toInstant(),r.getObject("updated_at",OffsetDateTime.class).toInstant());
    }
    private static IllegalStateException failure(SQLException e){return new IllegalStateException("Product persistence failed",e);}
}
