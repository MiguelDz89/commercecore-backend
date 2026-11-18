package com.commercecore.diaz.catalog.infrastructure.persistence;

import com.commercecore.diaz.catalog.application.CreateProduct;
import com.commercecore.diaz.catalog.application.ProductConflictException;
import com.commercecore.diaz.catalog.application.ProductStatusConflictException;
import com.commercecore.diaz.catalog.application.ProductStatusConflictException.Code;
import com.commercecore.diaz.catalog.application.ProductStatusConflictException.Conflict;
import com.commercecore.diaz.catalog.application.port.ProductStore;
import com.commercecore.diaz.catalog.domain.Product;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

@ApplicationScoped @Transactional(Transactional.TxType.MANDATORY)
public class JdbcProductStore implements ProductStore {
    private static final String FIELDS="product_id,tenant_id,category_id,sku,name,description,status,version,created_at,updated_at,base_unit,fractional_quantities_enabled,structure_type,parent_id,variant_attributes,published,ever_published";
    private final DataSource source;
    private final ObjectMapper mapper;

    public JdbcProductStore(DataSource source,ObjectMapper mapper){this.source=source;this.mapper=mapper;}

    @Override public Product insert(UUID tenant,UUID actor,CreateProduct command,String currency){
        String sql="""
                INSERT INTO product(tenant_id,category_id,sku,name,description,structure_type,status,created_by,updated_by,
                    base_unit,fractional_quantities_enabled,currency_code,parent_id,variant_attributes)
                VALUES (?,?,?,?,?,?,'DRAFT',?,?,?,?,?,?,CAST(? AS jsonb))
                ON CONFLICT DO NOTHING RETURNING
                """+FIELDS;
        try(var c=source.getConnection();var s=c.prepareStatement(sql)){
            s.setObject(1,tenant);
            s.setObject(2,command.categoryId());
            s.setString(3,command.sku());
            s.setString(4,command.name());
            s.setString(5,command.description());
            s.setString(6,command.type().name());
            s.setObject(7,actor);
            s.setObject(8,actor);
            s.setString(9,command.baseUnit());
            s.setBoolean(10,command.fractionalQuantitiesEnabled());
            s.setString(11,currency);
            s.setObject(12,command.parentId());
            s.setString(13,writeAttributes(command.attributes()));
            try(var r=s.executeQuery()){
                if(!r.next())throw new ProductConflictException("SKU or attribute combination already exists");
                return row(r);
            }
        }catch(SQLException e){throw failure(e);}
    }
    @Override public Optional<Product> find(UUID tenant,UUID id){
        try(var c=source.getConnection();var s=c.prepareStatement("SELECT "+FIELDS+" FROM product WHERE tenant_id=? AND product_id=?")){
            s.setObject(1,tenant);s.setObject(2,id);try(var r=s.executeQuery()){return r.next()?Optional.of(row(r)):Optional.empty();}
        }catch(SQLException e){throw failure(e);}
    }
    @Override public List<Product> list(UUID tenant,int offset,int limit,String sku,String name,UUID category,Product.Status status,Product.Type type,UUID parent){
        String sql="SELECT "+FIELDS+"""
                 FROM product WHERE tenant_id=?
                    AND strpos(coalesce(sku_normalized,''),upper(btrim(?)))>0
                    AND strpos(lower(name),lower(?))>0
                    AND (CAST(? AS uuid) IS NULL OR category_id=?)
                    AND (CAST(? AS text) IS NULL OR status=?)
                    AND (CAST(? AS text) IS NULL OR structure_type=?)
                    AND (CAST(? AS uuid) IS NULL OR parent_id=?)
                ORDER BY lower(name),product_id LIMIT ? OFFSET ?
                """;
        try(var c=source.getConnection();var s=c.prepareStatement(sql)){
            s.setObject(1,tenant);s.setString(2,sku);s.setString(3,name);s.setObject(4,category);s.setObject(5,category);
            String state=status==null?null:status.name();s.setString(6,state);s.setString(7,state);
            String structure=type==null?null:type.name();s.setString(8,structure);s.setString(9,structure);
            s.setObject(10,parent);s.setObject(11,parent);s.setInt(12,limit);s.setInt(13,offset);
            var result=new ArrayList<Product>();try(var r=s.executeQuery()){while(r.next())result.add(row(r));}return List.copyOf(result);
        }catch(SQLException e){throw failure(e);}
    }
    @Override public Optional<Product> update(Product p,long expectedVersion,UUID actor){
        String sql="""
                UPDATE product SET name=?,description=?,category_id=?,base_unit=?,fractional_quantities_enabled=?,
                    variant_attributes=CAST(? AS jsonb),version=version+1,updated_by=?,updated_at=clock_timestamp()
                WHERE tenant_id=? AND product_id=? AND status<>'ARCHIVED' AND version=? RETURNING
                """+FIELDS;
        try(var c=source.getConnection();var s=c.prepareStatement(sql)){
            s.setString(1,p.name());s.setString(2,p.description());s.setObject(3,p.categoryId());s.setString(4,p.baseUnit());
            s.setBoolean(5,p.fractionalQuantitiesEnabled());s.setString(6,writeAttributes(p.attributes()));s.setObject(7,actor);
            s.setObject(8,p.tenantId());s.setObject(9,p.id());s.setLong(10,expectedVersion);
            try(var r=s.executeQuery()){return r.next()?Optional.of(row(r)):Optional.empty();}
        }catch(SQLException e){
            if ("23505".equals(e.getSQLState())) throw new ProductConflictException("Attribute combination already exists");
            throw failure(e);
        }
    }

    @Override
    public List<Product> findSelected(UUID tenant, List<UUID> productIds) {
        var products = new ArrayList<Product>();
        try (var connection = source.getConnection();
                var statement = connection.prepareStatement(
                        "SELECT " + FIELDS + " FROM product WHERE tenant_id=? AND product_id=ANY(?)")) {
            statement.setObject(1, tenant);
            statement.setArray(2, connection.createArrayOf("uuid", productIds.toArray(UUID[]::new)));
            try (var result = statement.executeQuery()) {
                while (result.next()) products.add(row(result));
            }
            return products;
        } catch (SQLException error) {
            throw failure(error);
        }
    }

    @Override
    public List<VariantReference> findUnarchivedVariants(UUID tenant, List<UUID> parentIds) {
        if (parentIds.isEmpty()) return List.of();
        var variants = new ArrayList<VariantReference>();
        try (var connection = source.getConnection();
                var statement = connection.prepareStatement("""
                        SELECT parent_id, product_id
                        FROM product WHERE tenant_id=? AND parent_id=ANY(?) AND status<>'ARCHIVED'
                        ORDER BY parent_id, product_id
                        """)) {
            statement.setObject(1, tenant);
            statement.setArray(2, connection.createArrayOf("uuid", parentIds.toArray(UUID[]::new)));
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    variants.add(new VariantReference(result.getObject(1, UUID.class), result.getObject(2, UUID.class)));
                }
            }
            return variants;
        } catch (SQLException error) {
            throw failure(error);
        }
    }

    @Override
    public void changeStatus(UUID tenant, UUID actor, Product.Status target, List<Product> selected) {
        try (var connection = source.getConnection();
                var statement = connection.prepareStatement("""
                        UPDATE product SET status=?, published=false, version=version+1,
                                           updated_by=?, updated_at=clock_timestamp()
                        WHERE tenant_id=? AND product_id=? AND version=? AND status=?
                        """)) {
            for (Product product : selected) {
                statement.setString(1, target.name());
                statement.setObject(2, actor);
                statement.setObject(3, tenant);
                statement.setObject(4, product.id());
                statement.setLong(5, product.version());
                statement.setString(6, product.status().name());
                statement.addBatch();
            }
            int[] counts = statement.executeBatch();
            if (counts.length != selected.size()) throw new IllegalStateException("Incomplete product status update");
            var conflicts = new ArrayList<Conflict>();
            for (int index = 0; index < counts.length; index++) {
                if (counts[index] == 0) {
                    conflicts.add(new Conflict(selected.get(index).id(), Code.CONCURRENT_MODIFICATION));
                } else if (counts[index] != 1) {
                    throw new IllegalStateException("Unexpected product status update count");
                }
            }
            if (!conflicts.isEmpty()) throw new ProductStatusConflictException(conflicts);
        } catch (SQLException error) {
            throw failure(error);
        }
    }

    @Override public boolean hasCommercialValues(UUID tenant,UUID product) {
        return exists("""
                SELECT EXISTS (SELECT 1 FROM product p
                  WHERE p.tenant_id=? AND p.product_id=? AND (
                    p.sale_price_configured OR p.sale_price <> 0 OR p.cost_price IS NOT NULL
                    OR EXISTS (SELECT 1 FROM product_price_tier tier
                         WHERE tier.tenant_id=p.tenant_id AND tier.product_id=p.product_id)))
                """,tenant,product);
    }

    @Override public boolean hasFractionalTiers(UUID tenant,UUID product) {
        return exists("""
                SELECT EXISTS (SELECT 1 FROM product_price_tier
                  WHERE tenant_id=? AND product_id=? AND minimum_quantity <> trunc(minimum_quantity))
                """,tenant,product);
    }

    private boolean exists(String sql,UUID tenant,UUID product) {
        try (var connection=source.getConnection(); var statement=connection.prepareStatement(sql)) {
            statement.setObject(1,tenant);
            statement.setObject(2,product);
            try (var result=statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        } catch (SQLException error) {
            throw failure(error);
        }
    }

    private Product row(ResultSet r)throws SQLException{
        return new Product(r.getObject("product_id",UUID.class),r.getObject("tenant_id",UUID.class),
                r.getObject("category_id",UUID.class),r.getString("sku"),r.getString("name"),r.getString("description"),
                Product.Status.valueOf(r.getString("status")),r.getLong("version"),r.getObject("created_at",OffsetDateTime.class).toInstant(),
                r.getObject("updated_at",OffsetDateTime.class).toInstant(),r.getString("base_unit"),r.getBoolean("fractional_quantities_enabled"),
                Product.Type.valueOf(r.getString("structure_type")),r.getObject("parent_id",UUID.class),readAttributes(r.getString("variant_attributes")),
                r.getBoolean("published"),r.getBoolean("ever_published"));
    }

    private Map<String,String> readAttributes(String json) {
        if (json==null) return Map.of();
        try {
            return mapper.readValue(json,new TypeReference<Map<String,String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid stored variant attributes",e);
        }
    }

    private String writeAttributes(Map<String,String> attributes) {
        if (attributes.isEmpty()) return null;
        try {
            String json=mapper.writeValueAsString(attributes);
            // JSONB output adds spaces after colons and commas. Bound the indexed key size.
            if (json.getBytes(StandardCharsets.UTF_8).length+2L*attributes.size()>2000)
                throw new IllegalArgumentException("Variant attributes exceed the storage limit");
            return json;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize variant attributes",e);
        }
    }
    private static IllegalStateException failure(SQLException e){return new IllegalStateException("Product persistence failed",e);}
}
